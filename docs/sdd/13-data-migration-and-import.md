# 13. Data Migration and Import

**Status:** Draft · **Owner:** Hillary

## 13.1 Purpose

The pilot tenant's loan book lives in one flat spreadsheet with 25 columns and known
quality problems (`docs/specs/pilot-data-dictionary.md`). This chapter specifies how that
spreadsheet becomes members, next of kin, collateral items, loans, schedules, historic
repayments and opening balances, without losing a row and without guessing.

The import framework (core module `imports`) is generic: batches, rows, issues, review,
preview, commit, reconciliation. The pilot register is its first **template**,
`pilot_loan_register_v1`, registered by the lending module (chapter 5 section 5.4.3).
Requirements: FR-IMP-01 to FR-IMP-09, FR-GL-09.

## 13.2 Principles

1. **Every source row is kept.** Each row below the header becomes one `import_rows`
   record, including blank, sub-header and placeholder rows. Row counts reconcile exactly.
2. **Never guess silently.** A value is changed only by a deterministic rule in this
   chapter, and every change is recorded as an issue a person can see. Where a rule cannot
   decide, the issue is blocking and a person decides.
3. **Every decision is attributable.** Each resolution records who, when, the before and
   after value, and a note.
4. **Commit is all or nothing, and checked by a second person** (FR-IMP-07,
   `import_commit` in chapter 8).
5. **Deterministic.** Re-parsing the same file with the same template version produces the
   same rows, classes, normalised values and issues. The golden test in 13.12 holds this.

## 13.3 Pipeline

| Stage | What happens | Batch status after |
|---|---|---|
| Upload | File stored in object storage, SHA-256 computed, duplicate check (FR-IMP-01); user chooses template, target branch, default responsible officer, and the loan product for imported loans | `uploaded` |
| Parse | Header row located and matched to the template's 25 columns; each row read with cell types preserved | `parsing` |
| Classify | Each row classified (13.5) | |
| Normalise | Each field normalised (13.6); issues raised (13.7) | |
| Link | Borrowers deduplicated and matched to existing members; next of kin linked to borrowers (13.9) | `in_review` |
| Review | People resolve issues (FR-IMP-05) | `in_review` |
| Preview | System computes what commit will create (FR-IMP-06) | `ready` when no unresolved blocking or warning issue remains |
| Commit | Approval request `import_commit`; on approval, one transaction creates everything (13.10) | `committed` |
| Reconcile | Reconciliation report stored as a document (FR-IMP-08) | `committed` |

A batch can be `cancelled` at any status before `committed`. Parsing failure of the
whole file (unreadable, header not found) sets `failed` with a reason and creates no rows.

## 13.4 Tables

All tenant-owned, RLS as chapter 6 section 6.3.

### `import_batches`

| Column | Type | Notes |
|---|---|---|
| (std) | | |
| `branch_id` | `uuid NOT NULL` | Target branch for everything created. |
| `template_key` | `text NOT NULL` | For example `pilot_loan_register_v1`. |
| `template_version` | `integer NOT NULL` | Rules version, for determinism. |
| `source_filename` | `varchar(255) NOT NULL` | |
| `source_sha256` | `char(64) NOT NULL` | Partial unique `(tenant_id, source_sha256) WHERE status <> 'cancelled'`. |
| `source_document_id` | `uuid NOT NULL` | Original file in object storage. |
| `sheet_name` | `varchar(100)` | XLSX only. |
| `header_row_no` | `integer` | 1-based row number of the header in the source. |
| `default_officer_user_id` | `uuid NOT NULL` | |
| `loan_product_version_id` | `uuid NOT NULL` | Must be `flat`, `per_term`, `bullet` for this template. |
| `business_date` | `date NOT NULL` | Tenant business date at upload; used for range checks. |
| `status` | `text NOT NULL` | [`uploaded`, `parsing`, `in_review`, `ready`, `committed`, `cancelled`, `failed`] |
| `counts` | `jsonb NOT NULL DEFAULT '{}'` | Rows by class and status, issues by code and severity. |
| `failure_reason` | `text` | |
| `uploaded_by` | `uuid NOT NULL` | |
| `approval_request_id` | `uuid` | |
| `committed_at` | `timestamptz` | |
| `opening_journal_entry_id` | `uuid` | |
| `reconciliation_document_id` | `uuid` | |

### `import_rows`

| Column | Type | Notes |
|---|---|---|
| (std) | | |
| `batch_id` | `uuid NOT NULL` | |
| `source_row_no` | `integer NOT NULL` | 1-based row number in the source sheet. `UNIQUE (batch_id, source_row_no)`. |
| `raw` | `jsonb NOT NULL` | Per column: `{"value": ..., "type": "date" or "number" or "text" or "empty"}` exactly as read. Never modified. |
| `row_class` | `text NOT NULL` | [`data`, `subheader`, `placeholder`, `amounts_only`, `blank`] |
| `context_month` | `date` | First day of the month from the most recent sub-header above this row, if any. |
| `normalised` | `jsonb NOT NULL DEFAULT '{}'` | Normalised values keyed by target field (13.8). Edited only through resolutions. |
| `status` | `text NOT NULL` | [`ok`, `needs_review`, `excluded`, `committed`] |
| `excluded_reason` | `text` | Required when `excluded`. |
| `linked_row_id` | `uuid` | For an amounts-only row linked to another row. |
| `member_key` | `text` | Dedupe key after linking (13.9). |
| `created_member_id`, `created_loan_id` | `uuid` | Set at commit. |

### `import_issues`

| Column | Type | Notes |
|---|---|---|
| (std, no `version`) | | |
| `row_id` | `uuid NOT NULL` | |
| `code` | `text NOT NULL` | 13.7 |
| `severity` | `text NOT NULL` | [`info`, `warning`, `blocking`] |
| `field` | `text` | Target field, or NULL for row-level issues. |
| `message` | `text NOT NULL` | Human explanation, with the original and proposed values. |
| `original_value` | `jsonb` | |
| `suggested_value` | `jsonb` | Only when a rule in this chapter produces one. |
| `status` | `text NOT NULL` | [`open`, `resolved`] (`info` issues are created `resolved`). |

### `import_issue_resolutions` (append-only)

`id`, `tenant_id`, `created_at`, `issue_id uuid NOT NULL`, `resolution text NOT NULL`
[`accept_suggestion`, `edit_value`, `override`, `exclude_row`, `link_row`, `link_member`,
`reclassify`], `value jsonb`, `note text`, `resolved_by uuid NOT NULL`. `override` is
allowed for warnings only and requires a note.

## 13.5 Row classification

Applied in this order; the first match wins.

| Class | Rule | Row status |
|---|---|---|
| `blank` | All 25 cells empty or whitespace. | `excluded`, reason `blank`, info `BLANK_ROW` |
| `subheader` | At most 2 non-empty cells, none of them in an amount column (PRINCIPAL REQUESTED, AMOUNT TO BE RETURNED, CURRENT PAYMENT, OUTSTANDING BALANCE), and at least one non-empty cell matches a month label: a month name or 3-letter abbreviation (case-insensitive), optionally followed by a 2 or 4 digit year, for example `MARCH`, `Mar 2026`, `APRIL 26`. | `excluded`, reason `subheader`, info `SUBHEADER_ROW`. Sets `context_month` for following rows until the next sub-header. A month without a year takes the year of the next data row's DATE if unambiguous, else the previous context's year. |
| `placeholder` | CUSTOMER NAME is empty or matches a placeholder pattern (`^x+$`, `^-+$`, `^\.+$`, `^\?+$`, `^(n/?a|none|nil|name|tbd|test)$`, case-insensitive), AND CUSTOMER PHONE and CUSTOMER ID are empty, zero or placeholders, AND every amount column is empty or zero. | `needs_review`, blocking `PLACEHOLDER_ROW`, suggestion `exclude_row` |
| `amounts_only` | CUSTOMER NAME, CUSTOMER PHONE and CUSTOMER ID are all empty, AND at least one amount column is non-empty and non-zero. | `needs_review`, blocking `AMOUNTS_ONLY_ROW`, suggestions `exclude_row` or `link_row` to the nearest preceding `data` row |
| `data` | Anything else. | Normalised; `ok` or `needs_review` by its issues |

A reviewer may `reclassify` a row (for example a placeholder that is really a borrower
with missing name). Reclassifying re-runs normalisation for that row.

## 13.6 Normalisation rules

Each rule below states the input it accepts, the output, and the issues it raises. The
same functions are shared with the staff forms (FR-MEM-02, FR-MEM-03), so a value is
normalised identically whichever way it enters.

### 13.6.1 Phone numbers (CUSTOMER PHONE, NEXT OF KIN PHONE)

1. Take the cell as text. If it came from a number cell, drop a trailing `.0`.
2. If the text contains `/`, `,` or ` or ` separating two numbers, take the first as the
   phone and the second as the alternate phone (info `PHONE_MULTIPLE`).
3. Strip every non-digit.
4. Map to the Uganda national significant number (9 digits):
   - 12 digits starting `256`: drop `256`;
   - 10 digits starting `0`: drop the `0`;
   - 9 digits: as is (this is the "stored as a number, leading 0 lost" case);
   - anything else: `PHONE_UNPARSEABLE`.
5. Output `+256` plus the 9 digits. If the input was not already in that exact form, info
   `PHONE_NORMALISED` with the original.
6. If the national number does not start with `7`, warning `PHONE_NOT_MOBILE` (SMS will
   not reach a landline).

Severity of `PHONE_UNPARSEABLE`: blocking for CUSTOMER PHONE (a member needs a phone),
warning for NEXT OF KIN PHONE (the kin record is created without a phone).
Empty CUSTOMER PHONE on a `data` row: blocking `PHONE_MISSING`.

| Input (fabricated) | Output | Issue |
|---|---|---|
| `700000001` (number) | `+256700000001` | `PHONE_NORMALISED` |
| `0700000003` (text) | `+256700000003` | `PHONE_NORMALISED` |
| `256700000002` (number) | `+256700000002` | `PHONE_NORMALISED` |
| `+256 700 000 001` | `+256700000001` | `PHONE_NORMALISED` |
| `+256700000001` | `+256700000001` | none |
| `70000001` | none | `PHONE_UNPARSEABLE` |

### 13.6.2 National ID (CUSTOMER ID, NEXT OF KIN ID)

Upper case, remove spaces and hyphens. Valid when it matches `^C[MF][A-Z0-9]{12}$`.
Invalid and non-empty: warning `NIN_INVALID`; the raw value is kept as `other_id_number`
with `id_type = other`. Empty: info `NIN_MISSING` (the member is created with
`id_type = none`, which keeps KYC `incomplete`).

### 13.6.3 Dates (DATE, EXPECTED DATE OF RETURN, DATE OF CURRENT PAY)

**Reading a cell into candidates.**

| Cell as read | Candidates |
|---|---|
| A real date (XLSX date-typed cell, an XLSX numeric serial in a date-formatted cell, or in CSV an ISO `YYYY-MM-DD` value) | `as_is` = the date read. `swapped` = the same year with day and month exchanged, when both are 12 or less and differ. |
| Text `a/b/yyyy`, `a-b-yyyy` or `a.b.yyyy` (1 or 2 digit parts; 2 digit year means 20yy) | `text_mdy` = month a, day b, and `text_dmy` = day a, month b, each included only if it is a valid date. |
| Text with a month name, for example `16 Mar 2026`, `March 16, 2026` | One candidate, `text_named`. |
| Anything else | No candidate: blocking `DATE_UNPARSEABLE`. |

Any candidate outside the window from 1 January 2015 to the batch business date plus 400
days is discarded; if that discards all candidates, blocking `DATE_OUT_OF_RANGE`.

**Resolving DATE and EXPECTED DATE OF RETURN together.** Let `D` be the parsed duration
(13.6.4). A pair `(d, e)` from the two candidate sets is **consistent** when
`e = d + D` using the calendar rules of chapter 3 R-TERM (months clamp to month end), or,
for durations in months, `e = d + 30 x months` days (info `DATE_MONTH_AS_30_DAYS`).

| Consistent pairs found | Result |
|---|---|
| Exactly one | Use it. If either date used a `swapped` candidate: warning `DATE_SWAP_REPAIRED` on that field, suggestion = the repaired date, original kept. If a text date was used: info `DATE_TEXT_PARSED`. |
| More than one | Keep the pairs whose `d` falls in the row's `context_month`. If exactly one remains, use it with warning `DATE_SWAP_REPAIRED` citing the sub-header. Otherwise blocking `DATE_AMBIGUOUS`, with every consistent pair listed as options. |
| None | Blocking `DATE_INCONSISTENT`, listing the candidates. The reviewer edits the dates or the duration. |
| One of the two dates, or the duration, is missing | Cannot cross-check. If each present date has exactly one candidate: warning `DATE_UNVERIFIED`. Otherwise blocking `DATE_AMBIGUOUS`. If EXPECTED DATE OF RETURN is missing but DATE and DURATION resolve, the suggestion is `d + D`. |

A single real date whose day equals its month, or whose day is above 12, has only the
`as_is` candidate and needs no cross-check to be unambiguous.

**DATE OF CURRENT PAY.** Candidates as above; keep those on or after the resolved DATE
and on or before the batch business date. One left: use it. Two left: warning
`PAY_DATE_AMBIGUOUS`, suggestion the `as_is` or `text_mdy` candidate matching the
convention the row's other dates resolved to. None left: warning `PAY_DATE_OUT_OF_RANGE`.

### 13.6.4 Duration (DURATION)

Case-insensitive. Accepts a count (digits, or the words one to twelve) and a unit, with
or without a space: `1 Month`, `1Month`, `2 Week`, `3 weeks`, `14 days`, `2 MONTHS`,
`one month`. Units: `day`, `days`, `d`; `week`, `weeks`, `wk`, `wks`, `w`; `month`,
`months`, `mon`, `mth`, `mths`, `mnth`, `m`. Output `{term_count, term_unit}`. If the
input was not already `<n> <unit>` with a single space, info `DURATION_NORMALISED`.
Anything else, including fractions: blocking `DURATION_UNPARSEABLE`.

### 13.6.5 Rate (PERCENTAGE)

The spreadsheet stores the rate for the whole term as a fraction (0.2 means 20 percent),
so the output is `interest_rate_bp` with `rate_unit = per_term`.

| Input | Result |
|---|---|
| Number `v` with `0 < v < 1` | `round(v x 10000)` bp |
| Text with `%` (`20%`) | Parsed as percent; info `RATE_TEXT_PARSED` |
| `v = 0` | Warning `RATE_ZERO` |
| `1 <= v <= 100` | Warning `RATE_PERCENT_FORM`, suggestion `v / 100` |
| `v > 100` | Blocking `RATE_OUT_OF_RANGE`; suggestion = the implied rate below, if it lies strictly between 0 and 1 |
| Empty | Blocking `RATE_MISSING`; suggestion = implied rate if available |

Implied rate = `AMOUNT TO BE RETURNED / PRINCIPAL REQUESTED - 1`, rounded to the nearest
basis point. After the rate is settled, if principal and amount to be returned are both
present and `round(P x (1 + r)) <> AMOUNT TO BE RETURNED`, warning `AMOUNT_MISMATCH` with
two suggestions: correct the amount to `round(P x (1 + r))`, or correct the rate to the
implied rate.

### 13.6.6 Amounts (PRINCIPAL REQUESTED, AMOUNT TO BE RETURNED, CURRENT PAYMENT, OUTSTANDING BALANCE)

Numbers are taken as is. Text is parsed after removing spaces, commas and a leading
`UGX` or `Shs`. Negative: blocking `NEGATIVE_AMOUNT`. Not an integer: warning
`AMOUNT_NOT_INTEGER`, suggestion rounded half up. Unparseable: blocking
`AMOUNT_UNPARSEABLE`. Empty PRINCIPAL REQUESTED on a data row: blocking
`PRINCIPAL_MISSING`. Empty AMOUNT TO BE RETURNED: suggestion `round(P x (1 + r))`,
warning `RETURN_AMOUNT_DERIVED`.

Balance check, after all amounts and the rate are settled: expected outstanding =
`AMOUNT TO BE RETURNED - CURRENT PAYMENT` (payment empty counts as 0). If OUTSTANDING
BALANCE is present and differs: warning `BALANCE_MISMATCH` with both values; the system
uses the computed value unless the reviewer overrides. If CURRENT PAYMENT exceeds AMOUNT
TO BE RETURNED: warning `PAYMENT_EXCEEDS_RETURN` (the excess becomes a member credit if
accepted).

### 13.6.7 Categorical fields

| Field | Mapping (case-insensitive, trimmed) | Unmapped |
|---|---|---|
| MARITAL STATUS | `single`, `s` to single; `married`, `m` to married; `divorced` to divorced; `widow`, `widowed`, `widower` to widowed; `separated` to separated; empty to unknown | info `MARITAL_UNMAPPED`, value `unknown`, original kept in the issue |
| RELATIONSHIP OF NEXT OF KIN | `wife`, `husband`, `spouse` to spouse; `mother`, `father`, `mum`, `dad`, `parent` to parent; `son`, `daughter`, `child` to child; `brother`, `sister`, `sibling` to sibling; `uncle`, `aunt`, `aunty`, `cousin`, `nephew`, `niece`, `in-law`, `in law`, `grandmother`, `grandfather`, `relative` to relative; `friend` to friend; `boss`, `employer` to employer | info `RELATIONSHIP_UNMAPPED`, value `other`; original kept in `relationship_text` |
| STATUS | `active`, `running`, `ongoing`, `pending`, empty: open; `cleared`, `paid`, `completed`, `finished`, `closed`: settled | info `STATUS_UNMAPPED` |

The loan status at commit is derived from the computed outstanding balance, not from the
STATUS text: zero outstanding is `closed`, otherwise `active`. If the STATUS text says
settled but outstanding is above zero, or says open but outstanding is zero: warning
`STATUS_BALANCE_CONFLICT`.

### 13.6.8 Collateral (COLLATERAL/ PLEDGE)

| Pattern (case-insensitive) | Collateral item |
|---|---|
| `land`, `land title`, `title`, `plot` | `land_title`, no reference number |
| `id`, `national id`, `nid` | `national_id`, reference = the borrower's NIN if known |
| `car log`, `log book`, `logbook`, `log` | `vehicle_logbook` |
| `car <text>`, `motorcycle <text>`, `bike <text>`, `vehicle <text>` | `vehicle`, reference = `<text>` upper case without spaces (the plate) |
| empty | none |
| anything else | `other`, description = the text; info `COLLATERAL_UNRECOGNISED` |

Recognised patterns raise info `COLLATERAL_PARSED`. Imported items are created with
`custody_status = pledged`, `estimated_value_minor = NULL` and a note "imported; verify
custody and value", and the collateral register report lists them for verification. A
plate already on another open loan in the batch or the tenant: warning
`COLLATERAL_PLEDGED_TWICE`.

### 13.6.9 Text fields

CUSTOMER NAME, NEXT OF KIN, LOCATION, LOCATION OF NEXT OF KIN, OCCUPATION, OTHER SOURCE
OF INCOME, REASON and REMARKS: trim, collapse internal whitespace, keep case as typed.
Names are not re-cased or split. REASON maps to `purpose_text`; `purpose_category` is set
to `other` for every imported loan (the spreadsheet has no category).

## 13.7 Issue catalogue

| Code | Severity | Field | Suggestion | Allowed resolutions |
|---|---|---|---|---|
| `BLANK_ROW` | info | row | | none (auto-excluded) |
| `SUBHEADER_ROW` | info | row | | `reclassify` |
| `PLACEHOLDER_ROW` | blocking | row | exclude | `exclude_row`, `reclassify` |
| `AMOUNTS_ONLY_ROW` | blocking | row | exclude, or link to preceding data row | `exclude_row`, `link_row`, `reclassify` (after editing identity fields) |
| `PHONE_NORMALISED` | info | phone | | |
| `PHONE_MULTIPLE` | info | phone | | |
| `PHONE_NOT_MOBILE` | warning | phone | | `override`, `edit_value` |
| `PHONE_UNPARSEABLE` | blocking (borrower), warning (kin) | phone | | `edit_value`, `override` (kin only) |
| `PHONE_MISSING` | blocking | customer_phone | | `edit_value`, `exclude_row` |
| `NIN_INVALID` | warning | nin | | `edit_value`, `override` |
| `NIN_MISSING` | info | nin | | |
| `DATE_TEXT_PARSED` | info | date | | |
| `DATE_SWAP_REPAIRED` | warning | date | repaired date | `accept_suggestion` (bulk), `edit_value` |
| `DATE_MONTH_AS_30_DAYS` | info | date | | |
| `DATE_AMBIGUOUS` | blocking | date | options | `accept_suggestion` (choose option), `edit_value` |
| `DATE_INCONSISTENT` | blocking | date | | `edit_value` (dates or duration), `exclude_row` |
| `DATE_UNVERIFIED` | warning | date | | `override`, `edit_value` |
| `DATE_UNPARSEABLE` | blocking | date | | `edit_value`, `exclude_row` |
| `DATE_OUT_OF_RANGE` | blocking | date | | `edit_value`, `exclude_row` |
| `PAY_DATE_AMBIGUOUS` | warning | pay_date | candidate | `accept_suggestion`, `edit_value` |
| `PAY_DATE_OUT_OF_RANGE` | warning | pay_date | | `edit_value`, `override` |
| `DURATION_NORMALISED` | info | duration | | |
| `DURATION_UNPARSEABLE` | blocking | duration | | `edit_value` |
| `RATE_TEXT_PARSED` | info | rate | | |
| `RATE_ZERO` | warning | rate | | `override`, `edit_value` |
| `RATE_PERCENT_FORM` | warning | rate | `v / 100` | `accept_suggestion` (bulk), `edit_value` |
| `RATE_OUT_OF_RANGE` | blocking | rate | implied rate | `accept_suggestion`, `edit_value` |
| `RATE_MISSING` | blocking | rate | implied rate | `accept_suggestion`, `edit_value` |
| `AMOUNT_MISMATCH` | warning | return_amount | corrected amount, or implied rate | `accept_suggestion` (choose), `override` |
| `AMOUNT_NOT_INTEGER` | warning | amount | rounded | `accept_suggestion` (bulk) |
| `AMOUNT_UNPARSEABLE` | blocking | amount | | `edit_value` |
| `NEGATIVE_AMOUNT` | blocking | amount | | `edit_value` |
| `PRINCIPAL_MISSING` | blocking | principal | | `edit_value`, `exclude_row` |
| `RETURN_AMOUNT_DERIVED` | warning | return_amount | derived | `accept_suggestion` (bulk) |
| `BALANCE_MISMATCH` | warning | outstanding | computed | `accept_suggestion` (bulk), `override` |
| `PAYMENT_EXCEEDS_RETURN` | warning | payment | | `override`, `edit_value` |
| `STATUS_UNMAPPED` | info | status | | |
| `STATUS_BALANCE_CONFLICT` | warning | status | | `override` |
| `MARITAL_UNMAPPED` | info | marital_status | | |
| `RELATIONSHIP_UNMAPPED` | info | kin_relationship | | |
| `COLLATERAL_PARSED` | info | collateral | | |
| `COLLATERAL_UNRECOGNISED` | info | collateral | | |
| `COLLATERAL_PLEDGED_TWICE` | warning | collateral | | `override`, `edit_value` |
| `DUPLICATE_BORROWER` | info | row | | (rows merged into one member) |
| `MEMBER_MATCHED_EXISTING` | info | row | | `link_member` (to choose a different member) |
| `MEMBER_PHONE_CONFLICT` | warning | customer_phone | same person, or different people | `link_member` / `link_row` (merge), `override` (keep separate) |
| `KIN_IS_MEMBER` | info | kin | | |
| `KIN_PHONE_MATCH` | warning | kin | link | `accept_suggestion`, `override` (do not link) |
| `MULTIPLE_ACTIVE_LOANS` | info | row | | |

Severity semantics: `info` issues are recorded resolved and never block. `warning` and
`blocking` issues start `open`; commit requires none open (FR-IMP-07). `override` is not
available on blocking issues.

## 13.8 Column mapping

| # | Source column | Target | Rule |
|---|---|---|---|
| 1 | DATE | `lending_loans.disbursed_on` (and `proposed_disbursement_date`) | 13.6.3 |
| 2 | CUSTOMER NAME | `lending_members.full_name` | 13.6.9; identity for dedupe (13.9) |
| 3 | LOCATION | `lending_members.location` | 13.6.9 |
| 4 | CUSTOMER PHONE | `lending_members.phone_e164`, `alt_phone_e164` | 13.6.1 |
| 5 | CUSTOMER ID | `lending_members.national_id` (or `other_id_number`), `id_type` | 13.6.2 |
| 6 | MARITAL STATUS | `lending_members.marital_status` | 13.6.7 |
| 7 | NEXT OF KIN | `lending_next_of_kin.full_name` | 13.6.9 |
| 8 | NEXT OF KIN PHONE | `lending_next_of_kin.phone_e164` | 13.6.1 |
| 9 | NEXT OF KIN ID | `lending_next_of_kin.national_id` | 13.6.2 |
| 10 | RELATIONSHIP OF NEXT OF KIN | `lending_next_of_kin.relationship`, `relationship_text` | 13.6.7 |
| 11 | LOCATION OF NEXT OF KIN | `lending_next_of_kin.location` | 13.6.9 |
| 12 | OCCUPATION | `lending_members.occupation` | 13.6.9 |
| 13 | OTHER SOURCE OF INCOME | `lending_members.other_income_source` | 13.6.9 |
| 14 | REASON | `lending_loans.purpose_text`; `purpose_category = other` | 13.6.9 |
| 15 | PRINCIPAL REQUESTED | `requested_principal_minor`, `approved_principal_minor`, `principal_disbursed_minor`, schedule item `principal_due_minor` | 13.6.6. The spreadsheet has no separate approved or disbursed amount; the requested amount is taken as disbursed in full (open question for the pilot tenant) |
| 16 | PERCENTAGE | `lending_loans.interest_rate_bp`, `rate_unit = per_term`, `interest_method = flat` | 13.6.5 |
| 17 | DURATION | `approved_term_count`, `term_unit`, `repayment_pattern = bullet` | 13.6.4 |
| 18 | EXPECTED DATE OF RETURN | Schedule item 1 `due_date`, `lending_loans.maturity_date` | 13.6.3 |
| 19 | AMOUNT TO BE RETURNED | Schedule item 1: `interest_due_minor = amount - principal` | 13.6.5, 13.6.6 |
| 20 | COLLATERAL/ PLEDGE | `lending_collateral_items` plus `lending_loan_collateral` | 13.6.8 |
| 21 | CURRENT PAYMENT | One historic `lending_loan_transactions` row (`repayment`, `is_historic = true`) with allocations | 13.6.6; allocated per R-ALLOC (interest first by default) |
| 22 | DATE OF CURRENT PAY | That transaction's `value_date` | 13.6.3 |
| 23 | OUTSTANDING BALANCE | Check only (`BALANCE_MISMATCH`); not stored | 13.6.6 |
| 24 | STATUS | Check only (`STATUS_BALANCE_CONFLICT`); loan status is derived | 13.6.7 |
| 25 | REMARKS | Note on the loan (`lending_collection_actions` row of type `other`, performed by the importer) | 13.6.9 |

Loan fields not in the spreadsheet: `officer_user_id` = the batch default officer;
`product_version_id` = the batch product; `channel = import`; `submitted_by`,
`approved_by` = NULL with status history reason `imported`; the approver CHECK does not
apply to NULL approvers.

## 13.9 Member deduplication and relationship linking

**Borrowers.** For each `data` row, a member key is formed:

1. If the NIN is valid: key `nin:<NIN>`.
2. Else if the phone normalised: key `phone:<E.164>`.
3. Else: key `row:<source_row_no>` (never merged automatically).

Rows with the same key become one member (info `DUPLICATE_BORROWER` on the second and
later rows). Where the same phone appears under two different NINs, or under a NIN key
and a phone key with a different name: warning `MEMBER_PHONE_CONFLICT`, and the reviewer
merges or keeps separate. Where name, phone or NIN differ between merged rows, the
latest row by DATE wins for the member's fields and the differences are listed in the
issue message.

Each key is then matched against the tenant's existing members (NIN first, then phone).
A match links the rows to the existing member instead of creating one (info
`MEMBER_MATCHED_EXISTING`); existing member fields are never overwritten by an import.

**Next of kin.** For each data row with a NEXT OF KIN name, one `lending_next_of_kin`
record is planned for the row's member. Duplicates for the same member (same NIN, or same
phone, or same name when neither is present) collapse to one. Linking, run after all
borrowers are keyed:

- next of kin NIN equals a borrower's NIN in the batch or an existing member's NIN:
  `linked_member_id` set, `link_method = nin`, `link_status = confirmed`, info
  `KIN_IS_MEMBER`;
- else next of kin phone equals a borrower's or member's phone: warning `KIN_PHONE_MATCH`,
  suggestion to link; accepted gives `link_method = phone`, `link_status = confirmed`,
  overridden gives `link_status = rejected`.

This is what makes the relationship graph (FR-MEM-08) and exposure (3.18.1) work for
imported borrowers from day one.

**Concurrent loans.** When a member's earlier loan in the batch is still open on the
later loan's DATE (its source payments do not cover its amount to be returned, or were
paid after that DATE): info `MULTIPLE_ACTIVE_LOANS` on the later row.

## 13.10 Commit

Preconditions: batch `ready`; approval request `import_commit` approved by a user other
than the one who requested it; the target period for opening balances is open.

In one transaction, bound to the tenant:

1. Create members (with `source = import`, `kyc_status = incomplete`, `import_row_id`),
   in source row order, taking member numbers from the tenant sequence.
2. Create next of kin records and links.
3. Create collateral items and loan-collateral links.
4. For each data row not excluded, create the loan with the snapshot terms from the row,
   `status` derived per 13.6.7, one schedule item (bullet): due date = resolved EXPECTED
   DATE OF RETURN, principal due = principal, interest due = amount to be returned minus
   principal.
5. For each row with a CURRENT PAYMENT, create a historic repayment
   (`is_historic = true`, `journal_entry_id = NULL`, `source = import`) and its
   allocations per R-ALLOC; update the schedule item and loan balances; set
   `closed_on = value_date` when fully paid; excess over the amount to be returned
   becomes `credit_balance_minor`.
6. Recompute DPD for every created loan as at the batch business date.
7. Post one opening balance journal entry per branch (FR-GL-09):
   debit `loans_receivable` with one line per open loan for its outstanding principal
   (subledger = the loan), credit `opening_balance_equity` for the total. Member credits
   from step 5 add debit `opening_balance_equity`, credit `member_overpayments` lines.
   Unpaid interest is not a ledger receivable (ADR-004 cash basis); it stays in the
   loan subledger.
8. Mark rows `committed` with created ids; batch `committed`; audit one row per created
   entity plus one batch summary row.
9. Queue the reconciliation report (FR-IMP-08) and, if the tenant has enabled them,
   no SMS: imported loans never trigger disbursement or receipt messages. Reminders and
   arrears messages apply from the next nightly run.

Commit failure rolls back everything; the batch stays `ready` and shows the error.

## 13.11 Reconciliation report

Contents (FR-IMP-08):

- Source rows by class; excluded rows each with source row number and reason.
- Members created, members matched to existing, next of kin created and linked.
- Loans created by status; collateral items created by type.
- Control totals over committed data rows, from the source columns and from the system:
  principal, amount to be returned, current payment, outstanding balance (source column)
  versus outstanding (system). The two may differ only by rows with an accepted
  `BALANCE_MISMATCH` or override, each listed.
- Opening journal entry numbers per branch and their totals.
- Every resolution with who, when, before, after and note.

## 13.12 Golden fixture and expected outcome

`fixtures/pilot_loan_register_sample.csv` holds 12 fabricated rows reproducing every
observed quality problem. In that CSV, a real spreadsheet date is written as ISO
`YYYY-MM-DD` and a text date as it was typed; a phone stored as a number is written
without its leading `0` or `+`. Tests may also build an XLSX from the CSV (ISO values
become date-typed cells, numeric phone cells become number cells) and must get the same
result from both.

With the batch business date fixed at **30 June 2026**, parsing and classification must
produce exactly:

| Source row | Class | Issues (besides info `PHONE_NORMALISED`, `COLLATERAL_PARSED`) | Key normalised values |
|---|---|---|---|
| 2 | subheader | `SUBHEADER_ROW` | context month March 2026 |
| 3 | data | none | disbursed 2026-03-16, due 2026-04-16, 2000 bp, 1 month, principal 500,000, interest 100,000 |
| 4 | data | `DATE_SWAP_REPAIRED` (DATE, suggestion 2026-03-05), `DURATION_NORMALISED`, `KIN_IS_MEMBER` (kin is the borrower of row 3) | disbursed 2026-03-05, due 2026-04-05; collateral `vehicle` plate `UXX001X` |
| 5 | data | `DATE_TEXT_PARSED` (both dates) | disbursed 2026-03-20, due 2026-04-03, 2 weeks |
| 6 | data | `RATE_OUT_OF_RANGE` (blocking, suggestion 1000 bp from the implied rate) | principal 1,000,000, return 1,100,000 |
| 7 | placeholder | `PLACEHOLDER_ROW` (blocking, suggestion exclude) | |
| 8 | subheader | `SUBHEADER_ROW` | context month April 2026 |
| 9 | amounts_only | `AMOUNTS_ONLY_ROW` (blocking) | |
| 10 | data | none | historic payment 400,000 on 2026-05-14 allocated interest 160,000 then principal 240,000; principal outstanding 560,000 |
| 11 | data | `DATE_SWAP_REPAIRED` (DATE, suggestion 2026-04-07; EXPECTED DATE OF RETURN, suggestion 2026-05-07), `KIN_IS_MEMBER` (kin is the borrower of row 5) | disbursed 2026-04-07, due 2026-05-07 |
| 12 | data | `DUPLICATE_BORROWER` (same NIN as row 3), `MULTIPLE_ACTIVE_LOANS` | historic payment 480,000 on 2026-05-18; loan `closed` |
| 13 | data | `DATE_INCONSISTENT` (blocking), `NIN_MISSING` (kin), `COLLATERAL_UNRECOGNISED` | |

After the fixture's standard resolutions (accept suggestions on rows 4, 6 and 11; exclude
rows 7, 9 and 13), commit must create:

- 6 members (rows 3 and 12 merge into one);
- 6 next of kin records, of which 2 are linked to members (rows 4 and 11);
- 7 loans: 6 `active` (rows 3, 4, 5, 6, 10, 11) and 1 `closed` (row 12);
- 7 collateral items;
- 2 historic repayments (rows 10 and 12), with no journal entries;
- one opening journal entry for the target branch: debit `loans_receivable` 2,810,000 in
  6 lines (500,000; 300,000; 200,000; 1,000,000; 560,000; 250,000), credit
  `opening_balance_equity` 2,810,000;
- days past due as at 30 June 2026 of 75 (row 3), 86 (row 4), 88 (row 5), 67 (row 6),
  16 (row 10) and 54 (row 11).

## 13.13 Retail pilot import (FR-RET-12; ADR-020 decision 9; #55)

The retail pilot moves from a spreadsheet with a form app on top
(`docs/specs/retail-pilot-data-dictionary.md`). Its import is not a template of the framework above:
it is a one-off application command, run once per tenant at cutover by a platform operator, on a
**normalised export** that a person prepares from the spreadsheet (data dictionary section 4). The
dry run and its report stand in for the preview, and the review of the source happens before the
export. The procedure is `docs/runbooks/import-retail.md`.

```
java -jar bms-api.jar import-retail --tenant <slug> --dir <path> [--dry-run]
```

**How it runs.** The command starts the application without the web server and the job scheduler,
connected as `bms_app` like the API (the database role guard refuses the owner role), binds the
tenant by slug through `core.jobs` (only an active tenant with retail switched on), and writes
through the retail modules' history ports, so every insert is under the tenant's row-level security.
Each file is one transaction, in the order branches, categories, units, products, suppliers,
customers, purchases, sales, usage, balances. A file that fails stops the run; the files before it
stay committed and a re-run skips what they wrote. `--dry-run` runs every file inside one outer
transaction that is rolled back, and prints the same report.

**What it writes** (chapter 6 section 6.11.4):

| Export | Becomes |
|---|---|
| Branches, categories, units, suppliers, credit buyers | Created when no existing one matches (branch code, or name ignoring case) |
| Products | Matched by code trimmed and ignoring case (data dictionary rule 1); a duplicate is one product and is reported. New products take the master's prices with an `initial` history row; an existing one whose prices differ takes them with an `import` row |
| Sales, restocks, usage and damage | Historical documents and movements, no journals, keyed by `source_ref` in `retail_import_refs` |
| Restocks of kind `adjustment` or `return` | `adjustment` or `return` movements, not purchases, with no supplier payable |
| Restock price changes | An `import` history row where consecutive restocks of a product changed its cost or sell price |
| Balances | One `legacy_balance` movement per branch and product, equal to the source quantity less the balance the imported history left, so the derived balance equals the source exactly; then one opening journal per branch (debit `inventory`, credit `opening_balance_equity`) for the positive balances at the product's current cost |

**Never guess, never lose a row (13.2).** A row with an unknown product or branch code, a missing
or malformed field, a duplicate `source_ref`, a duplicate product code or a duplicate balance is
skipped and listed in the report with its file and line; the export still holds it, and after the
export is fixed a re-run imports only what is missing. Names the reference files do not list
(a category, unit or supplier) are created exactly as written and reported. Negative source
balances are imported as they are, excluded from the opening journal and listed for the first
stock-take (ADR-020 decision 4). A history row has no oversell guard: it happened.

**The report** is plain text: rows read, written, already present and skipped per file; the opening
journal per branch; the negative balances; the credit sales imported unpaid (the source keeps no
payments, so no receivable is journalled for them); every anomaly; and a checksum line, the SHA-256
of the sorted `branch|product code|quantity` lines of the balances file and of the same lines with
the balance after the import. `match=yes` means every balance equals the source.

**Golden test.** `RetailImportIT` imports the fabricated `fixtures/retail/import-sample/` (three
branches, 20 products and a duplicate code differing by case, 221 sales including credit sales and
an unknown product code, 34 restock rows including an adjustment and a return, 15 usage rows, 60
balances including a negative one) on PostgreSQL as `bms_app`, and checks: every balance equals the
source; the valuation and one day's profit from the R4 reports include the imported history like
live data; exactly three opening journals, balanced, per branch; no other journal; a re-run adds
no row; a dry run writes nothing and prints the same report; another tenant is untouched.
