# 13. Data Migration and Import

**Status:** Draft · **Owner:** Hillary

## 13.1 Purpose

The pilot tenant's loan book lives in one flat spreadsheet with 25 columns and known
quality problems (`docs/specs/pilot-data-dictionary.md`). This chapter specifies how that
spreadsheet becomes members, next of kin, collateral items, loans, schedules, historic
repayments and opening balances, without losing a row and without guessing.

The import framework (core module `imports`) is generic: batches, rows, issues, review,
preview, commit, reconciliation. The rules of this chapter are the canonical **template**
`lending_loans_v1`, registered by the lending module (chapter 5 section 5.4.3), and the pilot
register is its first **mapping** (planned as the template `pilot_loan_register_v1`; built so in
#136, section 13.14.1 10/16). Requirements: FR-IMP-01 to FR-IMP-09, FR-GL-09.

ADR-027 makes this framework the one onboarding pipeline for every tenant's data, with canonical
templates per vertical and versioned mappings per customer sheet (section 13.14,
`docs/specs/customer-data-onboarding.md`). The lending templates `lending_savings_v1` (savings
accounts, opening balances, historic deposits) and `lending_investments_v1` (investment holdings,
opening balances) are later work, with phase 2 savings and investments: they are not built, and a
savings or investments sheet is not imported by `lending_loans_v1`.

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
| Preview | System computes what commit will create from the staged rows alone, writing nothing (FR-IMP-06): rows by outcome per batch (to write, excluded, blank, blocked), records by type, and the template's predicted balances (stock quantity and value per branch, receivables, loan principal outstanding and arrears buckets, each opening journal with its debit and credit totals) | |
| Dry run | The commit's own code with the same writer, inside one outer transaction that is rolled back (ADR-027 decision 5); its report (per batch read, written, existing, skipped; anomalies with file and line; opening journals; balances checksum) is stored on the run with a stamp of the run's inputs, and compared with the preview's opening journals | `ready` when no unresolved blocking or warning issue remains and a stored dry run counts (no batch, mapping version or resolution changed after it), did not fail and matched the preview |
| Commit | Approval request `import_commit`; on approval, one transaction creates everything (13.10) | `committed` |
| Reconcile | Reconciliation report stored as a document (FR-IMP-08) | `committed` |

A batch can be `cancelled` at any status before `committed`. Parsing failure of the
whole file (unreadable, header not found) sets `failed` with a reason and creates no rows.

Since #127 the states above belong to the run that owns the batches (section 13.14): the preview and the dry run work on a
run `in_review`, and the run, not the batch, becomes `ready`. As built for the preview and the dry run: section 13.14.1, 6/16.

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
| `batch_id` | `uuid NOT NULL` | As built (V140, #130). |
| `row_id` | `uuid NOT NULL` | Its issues go with the row when a new parse replaces it. |
| `source_row_no`, `ordinal` | `integer NOT NULL` | The line, and the engine's order within the batch (V140). |
| `code` | `text NOT NULL` | 13.7 and the shared codes of the spec's section 3.4.1 |
| `severity` | `text NOT NULL` | [`info`, `warning`, `blocking`] |
| `field` | `text` | Target field, or NULL for row-level issues. |
| `message` | `varchar(500) NOT NULL` | Plain language from the code's template with field, file and line; never a personal value (13.7, #130). |
| `original_value` | `jsonb` | The cell text the issue concerns; shown only to a holder of `core.imports.view_raw`. |
| `suggested_value` | `jsonb` | As built (V141, #131): `{"value": ...}` for a rounded amount or quantity the engine proposes, `{"review": "as_uploaded"}` on `ROW_CHANGED_SINCE_REVIEW`; never a personal value. The rules of 13.6 add theirs with their template. |
| `status` | `text NOT NULL` | [`open`, `resolved`] (`info` issues are created `resolved`). |
| `version` | `integer NOT NULL DEFAULT 1` | As built (V141): a resolution names it; a second resolver of the same version gets `version_conflict`. An issue found again on its row with the same code and field keeps its id with the next version. |
| `ask_customer` | `boolean NOT NULL DEFAULT false` | As built (V141): a question for the customer (spec section 3.5). |
| `resolution_id` | `uuid` | As built (V141): the resolution that resolved it. |

As built (V140), `import_rows` carries `normalised` and `source_ref`, written by the validation, and not yet `row_class`,
`status`, `excluded_reason`, `linked_row_id`, `member_key` or the created ids, which come with the steps that need them.

### `import_issue_resolutions` (append-only)

`id`, `tenant_id`, `created_at`, `issue_id uuid NOT NULL`, `resolution text NOT NULL`
[`accept_suggestion`, `edit_value`, `override`, `exclude_row`, `link_row`, `link_member`,
`reclassify`], `value jsonb`, `note text`, `resolved_by uuid NOT NULL`. `override` is
allowed for warnings only and requires a note.

As built (V141, #131, section 13.14.1 5/16): `value` is `before_value` and `after_value`; the row also carries `seq`, `run_id`,
`batch_id`, `row_key` and `raw_sha256` (the row it was made on, so it is found again after a new validation or upload),
`source_row_no`, `code`, `field` and `bulk_id`; `issue_id` has no foreign key, since a validation replaces a batch's issues;
the kinds are those above without `reclassify` (which comes with the row classes of 13.5) and with `map_value` and
`ask_customer`; `exclude_row` requires a note too. `import_rows` gains `excluded_by`, `linked_row_id` and `linked_member_id`
in place of the planned `status`, `excluded_reason` and `linked_row_id`.

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

Upper case, remove white space (the member API and the staff form remove white space only, so a hyphen makes a NIN invalid there; an import that wants hyphens removed does it before the check). Valid when it matches `^C[MF][A-Z0-9]{12}$`.
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
| `BLANK_ROW` | info | row | | none (auto-excluded); a shared code since #130 |
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

**The catalogue as a contract (#130).** This table is the own catalogue of the `lending_loans_v1`
template (#136); the validation engine adds the shared codes of the spec's section 3.4.1 in front of
it, so a lending row also gets `REQUIRED_MISSING`, `TYPE_INVALID`, `REF_UNKNOWN`, `DUPLICATE_SOURCE_REF`,
`VALUE_NORMALISED` and the rest from the engine, and the template reports the codes above through its
normalisers and cross-field rules. `BLANK_ROW` is now a shared code, with the same name and meaning, and
so is not listed again by the template. The rules every catalogue keeps:

- A code is never renamed or reused. A code no longer produced stays in its catalogue, marked retired
  (`IssueCode.retired`); the engine refuses to report a retired code. Every code ever registered is
  pinned in `fixtures/imports/issue-codes.txt`, and `ImportCatalogueTest` fails when one is removed,
  renamed, given another severity or loses its retired mark. The lending codes joined that file with #136
  (`lending_loans_v1`, pinned by `LendingLoansV1TemplateTest`).
- Every code that is not retired is produced by a fabricated fixture row (the golden run of the
  template), or, for a code another stage owns (the upload, the mapping validator, the commit guards),
  by the named test of that stage; a code whose producer is a later build step is listed as pending that
  step and leaves the list with it.
- A message is written for the customer from a template with the field, the file and the line
  (`{field} on {file} line {line} ...`) and only the placeholders of `ImportTemplates.MESSAGE_PLACEHOLDERS`.
  It never interpolates a field the template marks personal (a name, a contact; a `phone` or `nin` field
  always counts as personal), and the registry refuses at start-up a template whose message does. The
  column "Message" of 13.4 therefore no longer carries the original and proposed values: the original
  is stored beside the message (`import_issues.original_value`) and shown only in the review screen to
  a holder of `core.imports.view_raw`, and a suggestion is the resolution's (#131).

As built in #136 (section 13.14.1 10/16), the catalogue differs from the table above in four ways. `SUBHEADER_ROW`
(info) and `PLACEHOLDER_ROW` (blocking) are shared codes, the codes of the shared row rules `subheader_month` and
`placeholder_rows` that any template may offer, as `BLANK_ROW` is the shared code of a blank row. A code has one
severity, so the kin's `PHONE_UNPARSEABLE` (a warning) is its own code, `KIN_PHONE_UNPARSEABLE`. Four codes are added:
`BRANCH_UNKNOWN` and `PRODUCT_UNKNOWN` (blocking, a row's branch or loan product that the business has not open or
active), `BRANCH_IN_TWO_FILES` (blocking, on every row of a later file of the run for a branch an earlier file has; the
run is not `ready` while one is open on a row it keeps, so a branch's one opening journal holds all its loans) and
`DISBURSEMENT_DECISION_MISSING` (the run's refusal while the decision of 13.8 row 15 is not recorded). And
`reclassify` is not a resolution: a row of a class is completed by `edit_value` of its cells (the row is classed again
from the edit) or left out by `exclude_row`, and `link_row` on `AMOUNTS_ONLY_ROW` decides nothing the commit writes.
`DATE_AMBIGUOUS` has no suggestion (a resolution names one value, so the reviewer edits the date); `KIN_PHONE_MATCH`
links the kin with `link_status = suggested` whether it is accepted or overridden, for the member's screen to confirm,
since the commit cannot tell the two resolutions apart.

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

As built for every template (#133, section 13.14.1 7/16): the commit is per run, one transaction per batch in the
template's dependency order, after the `import_commit` approval and with a verified pre-import dump; the register batch
stays one transaction. Opening journals post one per batch, branch and kind through `post_entry` against
`opening_balance_equity` and are registered in `import_refs` in the same transaction; a failed batch rolls back alone and
the run, still `committing`, resumes from it. No SMS, task or approval request is made inside a batch (a trigger refuses
it). The lending template of #136 builds steps 1 to 7 above as its loader.

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

Both of the last two are in the report #134 built (the Lead's ruling on #873): the `ledger` section lists each opening
journal by its entry number, in the ledger's order, with its branch, kind, debits and credits (`LedgerAccounts.lines`
gives the number), and the `review` section lists every resolution of the run in the order made: the file and line, the
code and field, what was decided, who (the user's id), when, before, after and the note. A before or after is shown
only for a field the committed batch's set knows and does not mark personal; one of a personal field, a field the set
does not know, a resolution of the whole row, or a batch that is not among the run's committed ones (a replaced upload)
fails closed as "a personal value, not shown". A before, after or note that names a personal value of the run is hidden
too, as the resolution's audit row keeps them out.

As built for every template (#134, section 13.14.1 8/16; spec sections 3.9 and 3.10): the reconciliation is made by the
commit as it finishes and stored as a document (`import_reconciliations`, append-only, migration `V144`), and made again
on request as the next version until the customer signs it. "Stored as a document" is met by that row and not by a file
in `core.documents` (the Lead's decision on #134, ADR-027 note of 7 November 2026): the row holds the whole report as
JSONB with the SHA-256 of its plain text, the text is rendered from it byte for byte, the database refuses any change to
it, and the download is that same text, so a stored file would only be a second copy that could drift. Each check shows a **source value**, a **system value** and the
**difference** (source less system). The source is the run's staged rows: every row that is not blank, the rows left out
included, measured by the template's own forecast: the rows written are netted per product over the whole run (one
forecast fed every written row of every batch), and each row left out is measured alone, so its own part in every check
is known and its item explains exactly that. The system is the
books, never the commit's report: the run's journals (found in `import_refs`) read line by line through the ledger's port,
the ledger of the whole business, and the stock ledger and loan book through the template's `ImportBooks`. The sections
are:

| Section | Checks | System side |
|---|---|---|
| `counts` | rows per file and per entity set (not blank); facts per file: read, blank, left out, written by this import, already imported before, not found in the books; records created per target type | rows whose `source_ref` `import_refs` holds |
| `stock_quantity` | per branch and product: the balances checksum | the stock ledger's movements the run registered (`ImportBooks.stock`), never the business's whole balance, so stock held before or moved after the import is not a difference |
| `stock_value` | per branch | the run's journal lines on `inventory`; each negative balance listed separately as a `policy` item at its value, which is below zero like the difference it explains (source less system). Rule for every writer (spec section 3.9, the retail rule 5): the opening `inventory` journal holds the positive balances only, at the product's cost, so the source, which counts every balance, is the journal plus the negative ones. A writer that journals a negative value leaves it unexplained and the sign-off is refused. The item's value is the books' (quantity times the cost the writer recorded), so a writer whose cost differs from the sheet's amount leaves that gap unexplained too, on purpose: the sheet and the books disagree. An item's rows count together over the whole run, on all three sides (the Lead's ruling on #873): the source holds the run's written rows measured as one under the template's rules, every batch included; the writer journals per batch the change in what the run's items counted above zero are worth, so a fall in a later batch is a journal the other way (credit inventory) and the run's journals net to its positive balances, deriving both what the items are worth and what is already journalled from the database (its earlier batches' written rows and its own journals in `import_refs`), never from memory, so a resumed run journals what an unbroken one would (`BatchWriter`); and `ImportBooks.stock` nets each item over the run's movements. A row left out keeps its own part, which its item explains (`ImportReconciliationIT`: one count of -2; counts of 5 and -2, and of -2 and 1, in one batch and in two; and a run killed after the batch of 5 and resumed with -2) |
| `receivables` | per branch, as if every credit sale were opened | the run's journal lines on `trade_debtors` |
| `loan_principal` | per branch | the run's journal lines on `loans_receivable` |
| `arrears` | loans and principal per branch and bucket, as at the cutover day | the template's loan book |
| `ledger` | each opening journal per branch and kind (its debits); the run's journals balanced, none out of balance, none other than opening journals; the business's trial balance (debits less credits) zero, no entry out of balance; facts: each opening journal by its entry number | the ledger |
| `review` | no check; facts: every resolution of the review, in the order made, with who, when, before, after and note | `import_issue_resolutions` |

Every difference is itemised: `excluded` (a row left out in the review or held back by an open blocking issue, with its
file, line, codes and its own part of each check; a row is counted once, left out decided first, and a left-out row adds
nothing to the system side even when an earlier run holds it; a row an earlier run imported and this one writes nothing
for is itemised for the values and stock this run did not post; a left-out row the template's rules cannot measure is
named so in its item with the kind of failure, while a written one, or one whose recorded receivable decision the rules
cannot measure (named `decision:` and the kind), is a check nothing explains, named the same way, which holds the
sign-off), `overridden` (a written row whose warning was overridden, listed with no amount for the signer to see) and `policy`
(the receivable decision's effect, as the source measured under `open_from_list` less the books under the decision, with
the option and the day the customer confirmed it; a negative stock balance). A check whose difference the items do not
explain does not tie, and a reconciliation with such a check is not signable: the sign-off is refused (409
`unitemised_difference`) and the database refuses it too (`import_signoff_rules`). Values are exact decimal strings, money
in minor units, never rounded; branches and products are named by their codes, rows by file and line, and a text that names
a personal value of the run is hidden.

The **sign-off** (spec section 3.10, `core.imports.sign_off`, the tenant admin's): the admin opens the newest
reconciliation, ticks each section, types their name and today's date; the sign-off names the reconciliation and its
SHA-256 as read (409 `reconciliation_changed` if it was made again), and inside its own transaction, which holds the run,
measures the books again: when the text that measurement gives has another SHA-256 than the one read, what the import
brought in changed since: that measurement is stored as the next version (audited `core.import_run.reconciled` by the
signer) and the sign-off is refused (409 `reconciliation_changed` with `reconciliation_id` and `version` of the new one,
and words that name it), so the signer reads the books as they are now and never gets the same version back. The screen
shows the API's words, refetches the newest and clears the ticks; should a refetch still bring back the version refused,
it offers "Make the reconciliation again" to whoever holds `core.imports.manage` rather than sign it once more. Whether every section is ticked is checked
before the measurement. That measurement runs with a 60-second limit per statement, not a batch's 10 minutes, because it
holds the run inside a request; a run too big for it is refused with 503 `sign_off_too_big` ("too big to sign here at
once, and nothing was signed; ask Rincol Tech to help you sign it"). The limit is per statement, so a run of many
1,000-row chunks can still take longer in all; that and the wait for the run's lock behind a remake are what #874 removes. A sign-off that
measures in the background for very large runs is #874. It is stored in
`import_signoffs` (append-only, one per run), moves the run to `signed_off` and is audited `core.import_run.signed_off`
without the name. The run reaches `signed_off` no other way. After it, no new reconciliation of the run is stored (the
trigger takes the run `FOR UPDATE` in both cases, so a reconciliation and a sign-off never pass each other), so the signed
document stays the run's newest for good. The removal of a closed business replaces the typed name with "Removed"
(`imports_erase_personal_data`). The downloaded text (`reconciliation.txt`) is the signed document, so it carries the name
the signer typed and the date; that name is the only personal value of the reconciliation, it is never written to a log,
and the audit row of the sign-off does not hold it.

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
(`docs/specs/retail-pilot-data-dictionary.md`). Its import is an application command, run once per
tenant at cutover by a platform operator, on a **normalised export** that a person prepares from the
spreadsheet (data dictionary section 4). The dry run and its report stand in for the preview, and the
review of the source happens before the export. The procedure is `docs/runbooks/import-retail.md`.
Since #135 the command is a wrapper of the framework of 13.14: the export's fields are the canonical
template `retail_v1`, each file is a batch of a run of the framework written by that template's
loader, and the references below are in `import_refs` (13.14.1, 9/16). The next retail customer's
sheet is a mapping onto `retail_v1` through the import screens, with no export script (spec Appendix A).

```
java -jar bms-api.jar import-retail --tenant <slug> --dir <path> [--dry-run] [--first-live-date yyyy-mm-dd]
```

**How it runs.** The command starts the application without the web server and the job scheduler,
connected as `bms_app` like the API (the database role guard refuses the owner role), binds the
tenant by slug through `core.jobs` (only an active tenant with retail switched on), and writes
through the retail modules' history ports, so every insert is under the tenant's row-level security.
Each file is one transaction, in the order branches, categories, units, products, suppliers,
customers, purchases, sales, usage, balances. A file that fails stops the run; the files before it
stay committed and a re-run skips what they wrote. The tables written are append-only, so a wrong
committed run is reversed by restoring the verified pre-import backup, never by editing rows
(`docs/runbooks/import-retail.md`, "If the real run was wrong"). Every committed file, on every
run, writes one `retail.import.file_imported` audit row with its counts. `--dry-run` runs every file inside one outer
transaction that is rolled back, and prints the same report.

**What it writes** (chapter 6 section 6.11.4):

| Export | Becomes |
|---|---|
| Branches, categories, units, suppliers, credit buyers | Created when no existing one matches (branch code, or name ignoring case) |
| Products | Matched by code normalised as the catalogue normalises it (`RetailCatalogue.normaliseCode`, review F9) and ignoring case (data dictionary rule 1); a duplicate is one product and is reported. New products take the master's prices with an `initial` history row. An existing product is never changed, prices included, and gets no history row, so a price edited in the app between two runs stays (#73); when the master's prices differ, the report lists it as information. The code is at most 40 characters after normalisation; a longer one is a skipped row |
| Sales, restocks, usage and damage | Historical documents and movements, no journals, keyed by `source_ref` in `import_refs` by key alone (`sales.jsonl\|-\|ref:<source_ref>#by-key`, and still in `retail_import_refs` until the contract step, #135); each movement's `business_date` is the source row's sale, purchase or usage date in the tenant's zone, so valuation `as_of` and daily profit read them by business date |
| Restocks of kind `adjustment` or `return` | `adjustment` or `return` movements, not purchases, with no supplier payable |
| Restock price changes | An `import` history row where consecutive restocks of a product changed its cost or sell price: consecutive by date, then by `source_ref`, among the batch's rows and the restocks earlier runs imported (read a page of items at a time, #881), so a split of the history over runs records what one run records. A restock dated before one an earlier run imported adds the change that later restock now calls for when the books lack it; one the books hold otherwise is kept, as history is append only, and reported as an anomaly |
| Balances | One `legacy_balance` movement per branch and product, equal to the source quantity less the balance the imported history left, so the derived balance equals the source exactly, dated with the import date as its business date; then one opening journal per branch, dated the same day, (debit `inventory`, credit `opening_balance_equity`) for the positive balances at the product's current cost, posted through the framework's `openingJournal` since #135, once per branch, ever: a later run posts none for a branch that has one (reported already posted) and lists each difference for a stock-take. A balance the history already gives (a zero difference) writes nothing and counts as existing |

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
no row; a dry run writes nothing and prints the same report; another tenant is untouched. Further
cases (#73): a price edited through the API between two runs survives the re-run with no new
history row and the product counted as existing; a zero-difference balance is existing on both
runs; a code that NFKC makes longer than 40 characters is a skipped row, not a failed file.

### 13.13.1 Cash book tabs (ADR-022; FR-RET-30; issue #147)

The same command, the same JSON Lines contract and the same rules as above, extended with the cash
book files (the shapes are in `docs/specs/retail-pilot-data-dictionary.md` section 5). All nine files
are optional; an export without them imports exactly as before and an absent file is not listed in the
report. They are imported after `balances`, in the order that keeps the figures consistent: `cash_parties`,
`expense_categories`, `savings`, `expenses`, `withdrawals`, `advances`, `advance_payments`, `banking`,
`cash_balances`. Banking comes after the files its expected amount is computed from, although the
report (and the data dictionary) lists it before advances. The writes go through the port
`CashBookHistory` of `retail.cashbook` (the pattern of `SaleHistory`), in the caller's transaction; the
opening journal of `cash_balances` is the one journal of these files. The option
`--first-live-date yyyy-mm-dd` (default: the import day in the tenant's zone) sets the first day the shop
records in the application; the opening entries are dated the day before it.

| Export | Becomes |
|---|---|
| Expense categories and items, parties | Matched by name ignoring case, else created and reported. Never guessed: the real lists come from the pilot app's expense categories tab |
| Daily savings, expenses, banked, withdrawals | Historical rows (`historical = true`), `created_by` the import actor, `occurred_at` from the source, `business_date` the source's date in the tenant's zone, **no journals**; keyed by `source_ref` in `retail_import_refs`. A second savings row for one shop and day is reported and skipped (the unique rule), never merged |
| Advances and their payments | Historical advances with their repayments; every imported advance takes the next value of the live `retail_advance_no` sequence, in business date then source id order, and the source id is kept in `retail_import_refs` and the note, never reused as `advance_no`, so an imported number cannot collide with a live one (test: after the import a live advance gets a number above every imported one and no two advances share a number); a party must be an owner, staff member or related entity, and one marked as the company is reported and skipped, never mapped; a non-zero `processing_fee_minor` is reported and appended to the note; a payment against an unknown advance is reported and skipped; `repaid_minor` equals the sum of the payments (the deferred check of V34 holds because the advance and each payment are written in the same transaction, `repaid_minor` rising with each), and a payment above the remaining principal is reported and skipped; a payment against an advance already opened is reported as an anomaly and balanced by a supplementary entry keyed per repayment (debit `opening_balance_equity`, credit `owner_advances`, the advance as subledger), so the ledger and `repaid_minor` agree; advances are numbered in natural source id order within a day (`ADV-9` before `ADV-10`) |
| `cash_balances` | One opening journal per branch, dated the day **before** the first live day (importer option `--first-live-date`, default the import day) so the first live day's opening shows the carried balance: debit `cash_on_hand`, `bank`, `savings_reserve` as given, and one `owner_advances` line **per outstanding imported advance** with that advance as subledger (like the lending opening import; never one figure per branch; each advance is opened at its principal less the repayments imported with it, not less live repayments, which post their own credit); credit `opening_balance_equity` (ADR-020 decision 9). A branch that has outstanding advances but no row is reported as an anomaly and each of its advances is opened by its own supplementary entry (debit `owner_advances` with the advance as subledger, credit `opening_balance_equity`, keyed per advance so a re-run adds nothing; an advance counts as opened only through such an opening debit, never through a live repayment that names it); `bank_minor` is signed, an overdrawn bank posting a credit line; a row with no shop uses the tenant's single head office branch, whatever any scope is. Imported days are shown separately in the banking report and are excluded from the unbanked running total, which starts at the first live day. The import records `retail_import_refs` (`source_file = 'cash_opening'`, `source_ref` the branch id, `target_type = 'journal'`, `target_id` the entry): the read model finds the first live day from exactly that. **The first live day is fixed by the first run that posts an opening entry** (a run with no `cash_balances` row and no outstanding advance posts none, so pass the same `--first-live-date` until one exists): once an opening entry exists, a re-run takes it from that entry's date plus one day, and a different `--first-live-date` is refused (exit 1) |

A cash book row, `banked_at` and `paid_on` included, dated on or after the first live day is skipped as live history; an expense explanation under 3 characters (the live minimum) is skipped. A row with an unknown branch, a missing field, a future date or a malformed amount is skipped and listed
with its file and line (13.2). `--dry-run` covers these files too. The banked row's `expected_minor` is computed from the imported history of its branch and day with the formula of the read model (expected to bank), so it is imported after savings, expenses, advances and payments; the golden test is `RetailCashbookImportIT` (chapter 15). A wrong committed run is reversed from
the verified pre-import backup, like the others.


## 13.14 Customer data onboarding for every tenant (ADR-027; #125)

Sections 13.1 to 13.12 design the framework for one lending sheet; section 13.13 describes the
retail command. ADR-027 makes the framework the one pipeline for every tenant's data, and
`docs/specs/customer-data-onboarding.md` specifies it; the operator's procedure is the template
`docs/runbooks/customer-data-cutover.md`. What changes in this chapter's design:

- **Runs over batches.** An `import_runs` row owns the batches of one onboarding attempt (one batch
  per file or tab), with the states `draft`, `uploaded`, `in_review`, `ready`, `committing`,
  `committed`, `signed_off`, `purged` and `cancelled`. The run records the cutover moment, the
  policy decisions (the receivable decision for credit sales without payments, and the
  disbursement assumption of 13.8 row 15), the dry run report, the verified pre-import dump and
  the sign-off.
- **Quarantine.** Uploaded files are encrypted with a per-run key under a quarantine prefix. The
  `import_*` tables are the staging area; no vertical or ledger table is written before commit.
  Columns a mapping ignores are not stored in `import_rows.raw`. Limits: 25 MB per file, 100,000
  rows per batch (25,000 until #900, section 13.14.1 11/16), 200 columns, streaming parsers.
- **Canonical templates and mappings.** A template (`retail_v1`, `lending_loans_v1`, later savings
  and investments) is code in its vertical, registered with the core. A customer's sheet is a
  versioned mapping document in `import_mappings` (append-only), validated against the template:
  columns to fields, value maps, the date convention and row rules such as the sub-header rows of
  13.5. `pilot_loan_register_v1` of this chapter becomes the `lending_loans_v1` template with the
  pilot register as its first mapping (built in #136, 13.14.1 10/16; `lending_savings_v1` and
  `lending_investments_v1` are later work, not built). A new resolution kind, `map_value`, adds a value map from
  the review queue and creates a new mapping version.
- **Issue codes.** Every template has a stable code catalogue (13.7 for lending; the shared and
  retail codes are in the spec, section 3.4.1). Messages are plain language and carry the field,
  file and line, never a personal value. The engine that applies them is built (#130, section
  13.14.1 4/16).
- **Dry run and commit.** The dry run is the commit in one outer transaction that is rolled back,
  with the same report as `import-retail`; it is stored on the run, stops counting once a batch, mapping version or
  resolution of the run changes, and a run is `ready` only with one that counts (built in #132, section 13.14.1 6/16). Commit is maker-checker (`import_commit`), one
  transaction per batch in dependency order; the register batch of this chapter stays one
  transaction (13.10). Every created record and every opening journal is registered in
  `import_refs` by `source_ref` in the same transaction (the generalisation of `retail_import_refs`),
  so an interrupted run resumes from the first uncommitted batch and a re-run writes nothing
  twice. A commit needs a verified pre-import dump recorded on the run; restoring it is the only
  whole-run rollback.
- **Guards.** A commit into a tenant that already trades is refused unless an audited operator
  override is approved by the dev lead; one `committing` run per tenant and an advisory lock per
  batch; a platform kill switch and a per-run halt checked between batches and every 1,000 rows; a
  10 minute statement timeout per batch transaction; rows dated after the cutover moment refused.
- **Reconciliation and deletion.** The reconciliation of 13.11 gains stock quantity and value,
  receivables, arrears buckets and a balanced ledger check, and is signed in the app. Sign-off starts
  the deletion of the quarantined files, the per-run key, the staging personal values and the
  pre-import dump within 30 days, with a certificate of deletion (built in #139, section 13.14.1 13/16).
- **Rehearsal.** Each run is rehearsed against last night's production backup restored into a
  throwaway database before cutover day.
- **Metrics and hypercare.** Each run's metrics are computed from its stored data, and defects found after the cutover
  are logged against it with the code they should have had (built in #140, section 13.14.1 14/16).

The build order is the spec's section 9. FR-IMP-01 to FR-IMP-09 keep their meaning; the new
requirements are written with the tasks that build them.

### 13.14.1 As built

**1/16, runs and guards (#127, migration `V127`).** The module `core.imports` (public API `ImportRuns`) holds:

- `import_runs` with the states above, enforced by the service: an illegal move is `invalid_status_transition` (409), and
  `committing` and `committed` are reached only through the commit.
- `import_batches` in its first form (run, order, `pending` or `committed`, counts); #128 adds the upload columns of 13.4.
- `platform_import_settings`, the kill switch (chapter 6 section 6.4).

The commit is a sequence of transactions:

1. **Start.** The kill switch must be on (`imports_disabled`) and the dump recorded (`pre_import_dump_missing`). The trading
   tenant guard asks every `LiveRecordProbe` (a kernel port: retail answers for sales and retail journals not in
   `retail_import_refs`, lending for loan transactions and lending journals, the ledger for journals posted by a core
   module). A trading business without the approved override is refused with `TENANT_TRADING`, and the refusal is audited.
   Otherwise the run moves `ready` to `committing`. A second committing run of the business hits the partial unique index
   and gets `IMPORT_IN_PROGRESS`, as does a second commit of the same run.
2. **One transaction per batch, in `seq` order.** Each takes `pg_advisory_xact_lock` on the business's import key and skips
   a batch already committed. It checks the kill switch and the run's halt before the batch and every 1,000 rows through the
   `BatchWriter` checkpoint. A stop rolls the batch back and leaves the run `committing` (`HALTED`). Any other failure of
   the writer does the same, is audited as `core.import_run.batch_failed` (the batch and the kind of failure, never the
   message, which may quote a row) and is rethrown.
3. **Finish.** The run moves to `committed`, and a halt pressed during the last batch, after its last checkpoint, is cleared.

`resume` continues a committing run from its first pending batch and clears the halt. The commit loop has an owner (issue
#779): before anything else, a commit or a resume takes `pg_try_advisory_lock`, a session lock, on the business's loop key,
on a connection of its own held in autocommit outside every transaction, and keeps it until the loop ends (committed,
halted or failed). A second commit or resume of the business, on any instance, finds the lock taken and gets
`IMPORT_IN_PROGRESS`, in a batch and in the gap between two batches alike: a resume never clears a halt under a loop that
is still alive, so a halt pressed between batches stops the loop at the next batch. The lock is the loop's heartbeat:
PostgreSQL drops it when the loop's connection or process dies, so a crashed loop never blocks a resume, and no column or
migration is needed. The loop checks its own claim before each batch (issue #789): on the claim connection it asks
`pg_locks` whether its session still holds the loop key, waiting about 10 seconds for an answer. The bound is the
claim connection's network timeout (`setNetworkTimeout`, issue #792), which bounds each socket read (not the whole probe,
nor its writes), set for the probe and put back after it on every path; a timeout that cannot be put back counts as a lost
connection, whatever the probe read. It is not a query timeout: pgjdbc cancels a query on a new socket and never unblocks a read on a black-holed connection, which would wait
for TCP to give up (about 15 minutes); a read that times out ends the connection instead. If the probe fails
(`claim_connection_lost`: an idle timeout, a NAT or firewall that dropped the idle connection, or no answer within the
10 seconds) or answers no
(`claim_not_held`), a resume may already own the business, so the loop writes nothing more, audits
`core.import_run.claim_lost` with the batch and the reason, and ends `HALTED` with the run `committing`; a resume then goes
on from the first pending batch. The audit of a failed batch never replaces the batch's error: when it fails too (a lost connection
fails both), its error is attached to the batch's as suppressed. It does not ask the trading guard
again: the batches already committed are the business's records now. The override is set by one platform operator with a
reason and approved by a different one (the CHECK `import_runs_checker_not_maker`, and `self_approval_forbidden`); both
steps are audited. Branch limits are each template writer's: a writer checks that every row it writes falls in a branch
the import may write to, first in the template of #136. The maker-checker
`import_commit` request is built in #133 (7/16 below); the commit is still called by the operator's tooling. The operator screens and the
stop button are #141.

**2/16, the quarantined upload (#128, migration `V129`).** `core.imports` gains the public API `ImportUploads` (upload, parse
again, cancel a batch, read batches); the operator's route and screen are #141, so until then it is called by the operator's
tooling, as the commit is. Every call works on the bound business only: a run or batch of another business is
`not_found`, and the quarantine port refuses another business's key.

- **Upload.** A file is added to a run that is `draft`, `uploaded` or `in_review` (the first moves the run to `uploaded`).
  Its name's extension gives the type (`.csv`, `.xlsx`, `.jsonl` or `.ndjson`) and its first bytes must agree: a zip for XLSX;
  for the text types no zip, old Excel, PDF or NUL byte, and `{` first for JSON Lines. Anything else is
  `FILE_TYPE_UNSUPPORTED` (422). The file is read once, in blocks of 64 KiB, counting its size against the limit
  (`FILE_TOO_LARGE`, 413, also when a declared size is over it), taking its SHA-256 and checking for a CSV or JSON Lines file
  whether it is UTF-8 (otherwise it is read as Windows-1252 and the batch carries `ENCODING_CONVERTED`). As it is read it is
  encrypted with the run's data key into a work file, the only copy on disk, which goes to object storage under
  `tenants/<tenant_id>/quarantine/imports/<run_id>/<batch_id>.bin` (chapter 12 section 12.5). The upload is refused with
  `UPLOAD_DISK_LOW` (507) when free disk is under twice the file, and with `FILE_ALREADY_UPLOADED` (409) when the run already
  holds the same bytes. The batch row records the file name, type, size, SHA-256, encoding, uploader and time; no transaction
  is open while the file streams or goes to storage.
- **Encryption.** The run's data key is 32 random bytes made at its first upload and stored only wrapped by the platform
  import key `BMS_IMPORT_KEY` (AES-256-GCM, bound to the business and the run, `import_runs.data_key_wrapped`). A file is
  sealed in segments of 64 KiB, each AES-256-GCM with its number and a last-segment mark in its IV and the batch id in its
  associated data, so neither side holds more than a segment and a file cut short, changed, reordered or moved onto another
  batch does not open (`quarantine_unreadable`, nothing changed or deleted). Without the server's import key, uploads answer
  503 `imports_key_missing`.
- **Parse.** The encrypted object is copied to a work file and read back through the decryption by a streaming reader: CSV
  (RFC 4180), XLSX (StAX over the zip in two forward passes, the workbook, styles and shared strings, then the sheet named or
  the first visible one, with values as the sheet shows them: a formula's cached value, numbers to 15 significant digits,
  dates in a date style as `2026-06-30` or with their time, never shifted by a zone) and JSON Lines (one object per line, its
  keys the columns). The header is the first of the first 20 rows that holds every header the mapping requires, or the first
  row that is not blank (`HEADER_NOT_FOUND`). Every row below it becomes one `import_rows` row with its cells in `raw`, blank
  rows included and trailing blank rows not, and columns the mapping ignores are dropped. Rows are written 1,000 at a time in
  one transaction, so the limits (`TOO_MANY_ROWS` over 100,000, 25,000 until #900, `TOO_MANY_COLUMNS` over 200) and a damaged file
  (`FILE_UNREADABLE`: an unclosed quote, a cell over 32,767 characters, a broken zip or XML) roll every row back: the batch
  is `failed` with its code and a reason that names a limit, a row or a column, never a value, and its file is deleted. A
  batch can be parsed again with other options before the run is ready; its rows are replaced. Since #129 the required
  headers, the ignored columns, the sheet and a fixed header row come from one source of a stored mapping version
  (`ImportMappings.parseOptions`, below), and the batch records that version; a caller may still pass the first three
  alone. A repeated header gets the first count no
  other header holds (`Phone`, `Phone (2)`, and for `Phone, Phone, Phone (2)` the third is `Phone (2) (2)`), and a cell
  beyond the header is `column_7` unless a header already has that name, so no cell is lost under another's name. A
  refusal on a re-parse deletes the file like any other, a `HEADER_NOT_FOUND` caused by the options included. #129 did not
  change this: keeping the file after a refusal the mapping caused would need a failed batch that can be parsed again and a
  cleanup that spares it, which is left open for the review queue (#131); until then the operator checks a mapping with
  `ImportMappings.validate` against the file's header row before the upload, and uploads again after a refusal.
- **States.** A batch is `uploaded`, `parsed`, `failed` or `cancelled` (`pending` and `committed` keep their meaning for the
  commit). A run becomes `ready` only when no batch is `uploaded` or `failed`, and the commit writes the `pending` and
  `parsed` batches. Cancelling a batch deletes its rows and file.
- **Cleanup.** The daily `core.imports.quarantine-cleanup` (chapter 9 section 9.9) removes the rows and files of cancelled
  runs, files whose delete failed (a failed or cancelled batch keeps its file's key until storage confirms the delete),
  files no batch names after 24 hours, and work files a crash left. The purge of a removed business deletes its quarantine
  prefix and, when storage refuses any file, stops so it runs again the next night.
- **Concurrency.** The parse writes in a transaction that first takes the run `FOR SHARE` and the batch `FOR UPDATE` and
  checks again that the run is open and the batch still `uploaded` or `parsed` with the same file, so a cancel, a move to
  ready or a second parse while the file was being fetched is refused and nothing is written; a refusal recorded after that
  point is dropped the same way. The cancel takes the same locks in the same order. The run stays held `FOR SHARE` for
  the whole parse (a few seconds at the row limit, `UploadMemoryTest`), so an upload to the same run, which takes the run
  `FOR UPDATE`, waits for it; the wait counts against the 60 second `statement_timeout`, and each insert of 1,000 rows is
  one short statement, well inside it and the 60 second `idle_in_transaction_session_timeout`.
- **Memory.** Besides the row chunk, the XLSX reader keeps only what its first pass needs (shared strings, styles, sheet
  list), charged at an estimate of its heap cost against 96 MiB, so many empty or short strings are stopped as surely as
  long ones, and never more shared strings than the sheet can have cells (rows plus the header, times columns); every text is read in pieces and stopped at 32,767 characters, and a shared or inline string made of many runs is checked after each run, its phonetic readings (`rPh`) left out in both; a part named twice is refused; and each pass may
  unpack at most 1 GiB, skipped parts included. A text used many times is charged at every use: the header names and
  texts the rows hold may total at most eight times the file size limit in characters (200 million at 25 MB), past which
  the batch is `FILE_TOO_LARGE` and its file deleted, so one 32,767 character shared string in every cell of a few KB of
  zipped sheet is refused in about a second instead of staging gigabytes. A chunk is also written early once its JSON
  reaches 8 million characters, so wide rows of long texts never fill the heap between writes. A sheet name over the 100
  characters `sheet_name` holds is
  `FILE_UNREADABLE`. A NUL in a cell or anywhere in nested JSON, which PostgreSQL cannot store, is `FILE_UNREADABLE`, as is
  any value the database refuses anywhere in the parse's write, the batch's own update included. A write that passes the
  `statement_timeout` (57014) would time out again on a retry, so it fails the batch with `FILE_TOO_LARGE`. Any other
  transient database error (a lost connection, a lock timeout) stays a server error and the batch keeps its file for
  another try. A failure reason is cut to 300 characters without splitting a character.
- **Proven by** `ImportUploadsIT` (only `import_*` and the audit log change; the stored object opens with the run's key and
  not without; each refusal; isolation; a file at the row limit streamed in blocks; no file content, personal value or file
  name in the logs; the cleanup), `UploadMemoryTest` (a CSV and an XLSX of 100,000 rows parse in a child JVM at the staging
  heap of 224 MB, and one long shared string in every cell is refused there in seconds), `SourceParserTest`,
  `QuarantineCipherTest`, `UploadServiceTest` (the reason's cut, the timeout check), `ReaderLimitsTest` (the memory
  budget, a zip bomb in a skipped part, texts over the cell limit, many runs in one inline cell, one long shared string in
  every cell, a phonetic reading in an inline cell, a sheet name over 100 characters, a part named
  twice, a number format with a unit, NUL in every format and not in the text of an escaped backslash, headers renamed
  without collisions) and `ImportUploadReviewIT` (a cancel or a move to ready while a file is fetched writes nothing, a NUL,
  a long sheet name and a refused update of the batch fail the batch with a code, the daily job as the scheduler runs it);
  `BusinessClosureIT` shows a removed business's quarantined file deleted, and `QuarantinePurgeIT` a refused delete that
  stops the purge and goes on the next run.

**3/16, canonical templates and versioned mappings (#129, migration `V139`, ADR-027 decision 3).** `core.imports` gains the
template contract, its registry and a business's mappings. No route yet: the operator's screen is #141, so until then the
operator's tooling calls the services, as it calls the upload and the commit.

- **The contract (`ImportTemplate`, the SPI).** A template has a key (`retail`, `lending_loans`) and a version (1, 2, ...;
  read as `retail_v1`), its entity sets in commit order, each with typed fields of spec section 3.2 (`text(n)`, `money`,
  `qty`, `date`, `datetime`, `phone`, `nin`, `enum(...)`, `bool`, `ref(entity set)`), a required flag and a named
  normaliser per field (`trim` by default; `collapse_spaces`, `upper`, or the vertical's own form function under its own
  name), the row rules a mapping may switch on per entity set (`subheader_month`, `totals_row`, `placeholder_rows`), its
  own issue codes, and its loader: the `BatchWriter` the commit runs for a batch of one entity set (#133). A change is a
  new version; a batch records the key and version it was parsed with.
- **The registry (`ImportTemplates`, chapter 5 section 5.4.3).** A vertical registers a template by declaring a bean that
  implements `ImportTemplate` in its own module; `TemplateRegistry` takes every such bean through an `ObjectProvider`, so the
  core starts with none and never names a vertical (`ModularityTest`). Each template is checked once at start-up, and one
  that fails stops the application with the reason: a key and version registered twice, a key, set, field or code that is
  not a plain name, a field declared twice, a `ref` to a set not committed before it (or itself), an `enum` without
  distinct values, a `text` of no length, a code that is a shared one or declared twice. The catalogue of a template is the
  shared codes (`ImportTemplates.SHARED_CODES`: the shared codes of spec section 3.4.1 and the mapping codes below) then its
  own. No production template is registered yet: `retail_v1` is #135 and `lending_loans_v1` #136; the tests register the
  fabricated `fixture_shop` version 1.
- **One JSON Schema per template version.** `ImportTemplates.schema(key, version)` returns a draft 2020-12 schema built from
  the template: the shared shape of a mapping, narrowed per entity set (through `if`/`then` on `entity_set`) to its own
  fields, the values of its enums and the row rules it offers. The service checks a mapping with exactly that document, by
  an interpreter of the keywords the schema uses (`SchemaCheck`), which refuses any keyword it does not interpret, so the
  published schema never says more than is checked; the fixtures pass a standard draft 2020-12 validator too.
- **A mapping document** (spec section 4.2): `template`, `template_version`, `date_convention` (`day_first`, `month_first`,
  `iso`), `time_zone`, `currency` (three capital letters), optional `unit_maps` (source label to unit), and `sources`, an
  object keyed by a lower-case source key, each with: `entity_set`; optional `file` and `sheet` (which file or tab feeds it);
  `header_row` (`"auto"` or a fixed row from 1 to 20); `columns`, each source header to `{"field": ...}` or
  `{"ignore": true, "reason": ...}` (a reason of 3 to 200 characters); optional `value_maps` and `enum_maps` (per field,
  source label to value; an enum map's values must be the enum's); optional `row_rules`; and `source_ref`
  (`{"rule": "row_hash"}`, the tab, row number and content hash, or `{"rule": "column", "column": ...}`).
- **The validator.** First the schema (`MAPPING_INVALID`, one per violation, with its JSON Pointer and never a value; a
  template not registered is `TEMPLATE_UNKNOWN` alone; a pattern's final `$` is the end of the text, as in ECMA-262, so a
  trailing line break never passes), then, on every part the schema accepted (a bad currency does not hide a source's
  missing field), what a schema cannot say: a required field no column feeds
  (`REQUIRED_FIELD_UNMAPPED`), one field fed by two columns (`FIELD_MAPPED_TWICE`), a column written twice ignoring case and
  spaces (`COLUMN_DUPLICATE`), a value or enum map for a field no column feeds (`MAP_FIELD_UNMAPPED`), a `source_ref` column
  the source does not keep (`SOURCE_REF_COLUMN_UNKNOWN`), a `source_ref` column that feeds a personal field, a name, a
  phone or an ID number (`SOURCE_REF_COLUMN_PERSONAL`, #139: the reference outlives the purge in `import_refs`), an unknown zone (`ZONE_UNKNOWN`). Against a file's headers,
  compared trimmed and ignoring case: one `COLUMN_UNMAPPED` per header neither mapped nor ignored, and
  `REQUIRED_COLUMN_MISSING` per required field whose column the file lacks. A mapping is valid only with no problem at all,
  so a column left unmapped and not ignored makes it invalid. The messages are the catalogue's, filled with the source,
  column and field names (header names, not personal data).
- **`import_mappings` (append-only).** `ImportMappings.save(name, document, by, note)` validates the document on its own and
  stores the next version of the named mapping (422 `mapping_invalid` with every problem in `errors`, 422
  `TEMPLATE_UNKNOWN`, 409 `mapping_version_conflict` when two saves race); audited as `core.import_mapping.saved` with
  the name, version, template and number of sources. `get`, `latest`, `versions`, `check` (a document without storing it),
  `validate(mapping, source, headers)` and `validateBatch(batch)`.
- **A batch records its mapping.** `ImportMappings.parseOptions(mapping, source)` gives the upload the source's tab, header
  row, the columns of its required fields (to find an automatic header row, or to check the fixed one: a fixed row that is
  blank or lacks one of them is `HEADER_NOT_FOUND`), the columns it ignores (never stored) and the mapping itself. The parse
  writes `mapping_id`, `mapping_source`, `template_key`, `template_version` and `source_headers` (every header name the parse
  found, ignored ones included, and any column a row holds beyond the header) on the batch; a mapping of another business
  is `not_found`. When options name a mapping, the upload and the parse use that mapping's own options for the source and
  nothing else the caller passed, so a batch never records a mapping it was not parsed with; a source the mapping lacks is
  `SOURCE_UNKNOWN` before the file is stored. A header name holding a NUL is `FILE_UNREADABLE`. A parse that fails clears the
  five columns, so a failed batch never shows an earlier version's headers (`validateBatch` on it is 409
  `batch_not_parsed`; on a batch read without a mapping it reports `BATCH_HAS_NO_MAPPING`). A new mapping version is a new
  row, so a batch keeps reading the version it was parsed with; a parse is refused once the run is ready, and the trigger
  `import_batches_mapping_kept` refuses any change to those five columns in an update that leaves or makes the batch
  `committed`, for every role, the removal and purge of a closed business aside (V102's `bms_erasing`).
- **Fixtures.** `fixtures/imports/` holds the fabricated mappings the tests read (`fixture_shop_v1.mapping.json`, valid;
  `fixture_shop_v1.invalid.mapping.json`; a header row). Real mappings are never committed (spec section 4.2).
- **Proven by** `MappingValidatorTest` (the registry's checks, the schema and its narrowing, every validator code, one
  `COLUMN_UNMAPPED` per column, the fixed header row), `ImportMappingsIT` (versions append-only for every role; a refused
  mapping stores nothing; a batch records its version and its unmapped columns are reported, its ignored column not stored;
  a committed batch keeps its version after a new one; another business can neither read nor write a mapping), `RlsIsolationIT`
  (forced RLS and append-only on `import_mappings`), `MigrationOrderIT` (V139 keeps every batch) and `ModularityTest`.

**4/16, the validation engine (#130, migration `V140`).** `core.imports` gains the public API `ImportValidation`
(`validate(run)`, `issues(batch)`); no route yet (#141), so the operator's tooling calls it, as it calls the upload.

- **What is checked.** Every parsed batch of a run that is `uploaded` or `in_review` (409 `run_not_open` otherwise),
  against its template and the mapping version the batch recorded (422 `BATCH_HAS_NO_MAPPING` for a batch read without
  one, 409 `templates_differ` when the batches name two template versions). Per field, in the template's order: required
  (`REQUIRED_MISSING`); the mapping's value map, and for an enum its enum map, as the mapping's explicit word, without an
  issue; the normaliser, which is the shared form function (section 13.6): the field's own `Normaliser` for text, codes and
  references (`collapse_spaces`, `upper`, `RetailCatalogue.normaliseCode` for retail codes under the vertical's own name),
  `PhoneNumbers.normaliseUganda` for a `phone` and `NationalIds.normaliseNin` for a `nin`, each change an `info`
  `VALUE_NORMALISED` with the original; group separators in a number are taken only between groups of three digits after a
  first group that does not start with 0 (`12,500`, `12 500`; `12,50`, `1,5` and `0,250` are `TYPE_INVALID`, never 1250, 15
  or 250) and their removal is a `VALUE_NORMALISED`
  of the rule `thousands_separator`; the type (`TYPE_INVALID`, `MONEY_FRACTION` for a fraction of a shilling,
  `QTY_PRECISION` beyond three places, `TEXT_TOO_LONG`; dates and moments read as ISO under every convention and day first
  or month first under the mapping's, a moment in the mapping's zone); a hidden character inside a key or a reference
  (`CODE_CONTROL_CHAR`); the field's range (`VALUE_OUT_OF_RANGE`); a date or moment after the run's `cutover_at`
  (`DATE_AFTER_CUTOVER`). Per row: its `source_ref` (the mapping's column, or the source, line and a hash of its kept
  cells), repeated in the run (`DUPLICATE_SOURCE_REF`); a reference to a code no batch of the run holds and the business does
  not hold either (`REF_UNKNOWN`; the template answers for its own tables through `existingKeys`, inside a read-only
  transaction bound to the business); a key repeated across the run ignoring case and spaces (`CODE_DUPLICATE`); a NIN
  repeated in the entity set within the run (`NIN_DUPLICATE`; a NIN the business already holds is matched by the
  template's linking step, section 13.9, with #136); then the template's cross-field rules (`rowChecks`), whose parameters
  may only be a number, a code in capitals or a name of the template's entity sets and fields, and never a personal value
  of the row (of four characters or more, as written, normalised or by its digits); anything else is shown as `(hidden)`,
  so a rule never leaks a value and ordinary data never stops the run. A rule that does it is a programming error the
  template's golden run catches: its test fails on any message that shows `(hidden)`. A row whose kept cells are all empty is
  `BLANK_ROW` and nothing else.
- **The template contract grows.** A `Field` may be `personalData()` (never interpolated; a `phone` or `nin` always is), the
  set's key (`asKey()`, at most one, a `text`, which a `ref` to the set names) and have a `range(min, max)` (money and qty).
  A template may offer `rowChecks(set)` and `existingKeys(set, keys)`. The registry refuses at start-up a key that is not
  text, a range on another kind, a `ref` to a set without a key, and a message that holds the name of a personal field or a
  placeholder outside `ImportTemplates.MESSAGE_PLACEHOLDERS`.
- **Two passes, deterministic.** `RowValidator` is plain Java. Pass 1 reads the run's batches in commit order (the
  template's entity sets, then the batch's order), 1,000 rows at a time, and learns the first line of each key,
  `source_ref` and NIN and the codes each reference names; pass 2 checks each row and answers its typed values, its
  `source_ref` and its issues in a fixed order (fields in the template's order, then the row's reference, then per field its
  reference and duplicates, then the cross-field rules). The same rows, template and mapping version give the same issues,
  messages and values every time.
- **A run that changes while it is checked.** A validation refuses to start while a file of the run is stored and not
  yet read (409 `batch_not_parsed`). Pass 1 records each batch's stamp (status, mapping version, parse time, row count).
  Pass 2 and the end run in one transaction that holds the run `FOR UPDATE`, as an upload does, so no file of the run is
  added, read again or cancelled meanwhile (the parse and the cancel take the run `FOR SHARE`). It writes only if every
  batch of the run still has the stamp pass 1 saw; otherwise 409 `batch_changed`, nothing of the run is written, and the
  run stays where it was, so no batch ever keeps issues built from another batch's old content. A second validation that
  finishes later finds the run already in review.
- **Where the results are.** In that transaction, for each parsed batch: the batch's issues are
  deleted and written again in `import_issues` (code, severity, field, line, message, the original cell text, `open` or
  `resolved` for `info`), and each row's `normalised` and `source_ref` are set; `raw` is never changed (a column grant). A
  committed batch is read for its keys and references and never written; the triggers `import_issues_batch_kept` and
  `import_rows_validation_kept` refuse it for every role but a migration and the removal of a business, a delete cascaded
  from the rows included: the purge of #139, which removes a committed batch's staged rows, brings its own way past the
  guard (Lead ruling on #867). An issue's row is of its
  business and its batch (the foreign key `(tenant_id, batch_id, row_id)`). An `uploaded` run
  moves to `in_review`. Audited as `core.import_run.validated` with counts by code; the log line carries the run id and the
  counts by code only.
- **Messages.** Built from the code's template, in one pass over the template (a placeholder written inside a file name
  is never filled), with the field name, the batch's file name (at most 100 characters, the middle elided), line numbers
  and the rule; never a cell's text. A template is at most 200 characters (the registry refuses a longer one), and a
  message is cut to the 500 its column holds. Section 13.7 sets the rules of the catalogue; the golden run proves them.
- **Not in this step.** The row rules a mapping switches on (the sub-header rows, totals row and placeholder rows of
  section 13.5) are not applied yet: a totals row is checked as a data row. `ROW_CHANGED_SINCE_REVIEW` (#131) and
  `CHANGED_SINCE_IMPORT` (#133) are pending the steps that hold a row's reviewed and imported content (the first is produced
  since 5/16, below).
- **Proven by** `ImportCatalogueTest` (the golden files of `fixtures/imports/golden/` give exactly `expected-issues.txt`;
  the same rows give identical issues, originals and values; every normaliser change is an `info` with its original;
  every code of the catalogue is produced by a fixture row, by the named test of its stage, or is pending or retired; the
  catalogue equals `fixtures/imports/issue-codes.txt`; no message interpolates a personal field or holds a personal value;
  the registry refuses a leaking message, a non-text key, a misplaced range and a `ref` to a set without a key; a retired or
  unknown code is never reported), `ImportValidationIT` (the golden files uploaded through the quarantine validate into the
  expected issues, with typed values stored and `raw` unchanged; the messages, the audit row and the logs hold none of the
  fixture's names, phones or NINs; validating again stores the same issues, values and references; a committed batch is
  read and never written, by the service or by `bms_app` directly; another business can neither validate the run, read
  its issues nor lend it a reference; a cancelled run is not validated; a file read again between the passes is refused
  with `batch_changed` and a file not yet read with `batch_not_parsed`; deleting a committed batch's rows is refused by
  the guard; and, in `ImportCatalogueTest`, each code of another stage is asserted in its named test method, two long file
  names fit a message, ambiguous commas are refused and a rule's personal parameter is refused), `RlsIsolationIT` (forced RLS on `import_issues`),
  `MigrationOrderIT` (V140 keeps every staged row) and `ModularityTest`.

**5/16, the review queue (#131, migration `V141`, FR-IMP-05, spec section 3.5).** `core.imports` gains the public API
`ImportReview` and the first routes of the pipeline, under `/imports` (chapter 7 section 7.11.9), all with
`core.imports.manage` and on the business of the host only. The queue works for every template; the rules below are the
template's catalogue and severities, nothing per template.

- **The resolutions.** `edit_value` (the text in place of the field's cell; the row is validated again with it), `exclude_row`
  (a reason required; the row stays in the batch and in the report, every open issue of it resolved and the row marked
  `excluded_by`), `accept_suggestion` (the engine's suggestion: for now a fraction of a shilling rounded half up to whole
  shillings for `MONEY_FRACTION` and a quantity rounded half up to three places for `QTY_PRECISION`, never on a personal field;
  accepted as an edit), `link_row` (to another parsed row of the run; `linked_row_id`), `link_member` (to a record the
  business holds, which the template confirms through the new SPI `ImportTemplate.recordExists`, false by default, so no
  template links yet; `linked_member_id`), `override` (a warning only, with a note: 422 `override_not_allowed` on a blocking
  issue, as 13.7 says) and the new `map_value`. Each is checked against its issue (422 `resolution_not_available` for an
  edit of a row-level issue, `no_suggestion`, `link_target_unknown`), and one issue open (409 `issue_not_open`). Bulk per code
  (`/imports/runs/{run_id}/issues/bulk-resolve`): every open issue of one code in the run, or in one batch of it, with
  `edit_value`, `exclude_row`, `accept_suggestion`, `override` or `ask_customer`, one resolution row each under one
  `bulk_id`, refused as a whole when one issue refuses.
- **The trail.** Every resolution is one append-only `import_issue_resolutions` row (who, when, the kind, `before_value`,
  `after_value`, the note) and one audit row `core.import_issue.resolved` (the spec's `imports.*`, written `core.import_*` in
  this module since #127) that names the run, batch, line, code, field, kind and resolution and never a value or the note. The
  value before and after an edit may be personal: it is kept only in the resolution row, which follows the retention of
  `import_rows` (the quarantine; the generic purge of a closed business; its hash only after #139). An issue on an edited
  field keeps no `original_value`, since its text is the operator's, and its message never holds it.
- **`map_value`.** On an issue of a field that is not personal (422 `field_is_personal`: a mapping never holds a cell of a
  customer's records, spec section 4.2) with cell text (422 `nothing_to_map`): the text becomes a label of the field's value
  map (its enum map for an enum) in a new version of the batch's mapping, saved through `ImportMappings.save` (validated,
  audited `core.import_mapping.saved`, append-only as #129), which must be the newest version of its name (409
  `mapping_not_latest`). A label the mapping already maps to another value, in any spelling the parse compares alike, is 409
  `label_already_mapped`, never replaced. Every parsed batch of the run read with the old version records the new one, and exactly the rows of
  that source whose field reads the label, as stored or as edited, are validated again. The resolution records both versions,
  the label and the value.
- **Concurrency.** A change takes the run `FOR UPDATE` (as a validation's write does) and then the issue `FOR UPDATE`, and
  names the issue's `version`: another resolver's version is 409 `version_conflict`, and so is an issue a resolution was made on
  and an edit or a validation has since replaced. A validation that meets a resolution made after its first pass writes
  nothing (409 `review_changed`).
- **Applied by the validation.** A resolution applies to a row while its cells are those it was made on: it is found again by
  `row_key` (the row's reference without the content hash: `col:<source>:<value>` for a reference column, `row:<source>:<line>`
  for `row_hash`) and `raw_sha256` (the SHA-256 of the cells as stored), with its code and field, among every resolution of the
  run. So it carries across a new validation, a new parse and a new upload of the file into the run (the old batch cancelled).
  A line repeated verbatim has its twin's identity and cells, so a resolution also records its `occurrence`, the n-th row of
  that identity and those cells in the run's order (batch, then line), and `copies`, how many there were: a decision on
  one copy never reaches another, and a link names the exact copy it points at (review of #868). A new upload that holds
  another number of such copies is never matched by guess: the old decision applies to none of them and each comes back
  as `ROW_CHANGED_SINCE_REVIEW` (Lead ruling on #868). The copies are counted before pass 1 (the rows are then read once
  more, only when the run has a resolution). A row left out is not indexed by pass 1, so it is no other row's duplicate
  and no reference reaches it.
  Both passes read the edits in force in place of their fields' cells, so duplicates and references see them; an issue is
  resolved by the resolution in force for its code and field, or by the row's exclusion; `ask_customer` is kept the same way.
  A row whose identity had a deciding resolution on other cells and none on its own cells gets `ROW_CHANGED_SINCE_REVIEW`
  (blocking, first on its row, suggestion `{"review": "as_uploaded"}`) and its old resolutions no longer apply, so it comes
  back to the queue; accepting the suggestion reviews it as it now is. With `row_hash`, a row moved to another line is a
  changed row. A partial validation (one row for an edit, the rows a map reaches) reads the whole run in pass 1 and writes
  only its rows; an edit that changes another row's duplicate or reference is seen by the next full validation.
- **Ordinals per row.** An issue's `ordinal` counts within its row (`UNIQUE (batch_id, source_row_no, ordinal)`), so the
  order of a batch (line, then the engine's order) is unchanged and one row's issues are written again alone.
- **Questions for the customer.** `PUT /imports/issues/{issue_id}/ask-customer` marks an issue (an `ask_customer` resolution
  row, which decides nothing about the row); `GET /imports/runs/{run_id}/questions` and `.../questions.csv` give the open
  ones, numbered in batch then line order, with file (cut to 100 characters), line, field, code and message, never a cell's
  value or a note; the CSV, the file sent to the customer, is audited `core.import_run.questions_exported` with the count.
  A resolution is made only on an issue of a parsed batch (409 `batch_not_parsed`, `batch_committed`), and a bulk resolution
  that names a batch not of its run is 404.
- **Not in this step.** `reclassify` and the row classes of 13.5; the raw values and the resolution history for a holder of
  `core.imports.view_raw` (the permission is not catalogued yet) come with the operator screens of #141.
- **Proven by** `ImportReviewIT` (every kind writes one resolution row with who, when, before, after and note and one audit
  row without a value or a note, and nothing personal reaches a message, an original, an audit row or a log line; the trail
  is append-only for every role; a full validation keeps every decision; a bulk resolution writes one row each and a blocking
  code is never overridden; an override of a blocking issue and one without a note are refused; `map_value` makes mapping
  version 2, moves the run's batches to it and validates exactly the one row it reaches, every other issue keeping its id,
  version and status; two resolvers at once, the second gets `version_conflict`, and so does a second edit of an issue an
  edit replaced; after a new upload a changed row comes back with `ROW_CHANGED_SINCE_REVIEW` and an unchanged one keeps its
  exclusion; of three lines repeated verbatim, an exclusion, an override or an edit of the second reaches neither other copy
  through a full validation, and a new upload with two copies sends both back as changed; the questions, as JSON and CSV, are numbered with file and line and hold no personal value; another business
  reaches none of the routes; an unknown property is refused), `ImportCatalogueTest` (`ROW_CHANGED_SINCE_REVIEW` leaves the
  pending list, produced by `ImportReviewIT#aChangedRowComesBackToTheQueueAndAnUnchangedRowKeepsItsResolution`),
  `RlsIsolationIT` (forced RLS and append-only on `import_issue_resolutions`), `MigrationOrderIT` (V141 keeps every issue),
  `PermissionMatrixIT` and `RoutePermissionIT` (each route refuses a caller without `core.imports.manage`).

**6/16, the preview and the dry run (#132, migration `V142`, FR-IMP-06, ADR-027 decision 5, spec section 3.6).**
`core.imports` gains the public API `ImportPreview`, the report shape `CommitReport`, the template SPI `ImportForecast`
(`ImportTemplate.forecast()`, none by default), and on `ImportRuns` the dry run and the stored reports. No route yet: the
operator's screens are #141, so the operator's tooling calls them, as it calls the commit. This step builds the report and
the rolled-back outer transaction; the maker-checker request and the templates' real loaders are #133 to #136. Since the
Lead's ruling on #869 the service resolves every batch's writer itself, its template's `loader(entitySet)` for the entity
set its mapping source names: `commit(run)`, `resume(run)` and `dryRun(run, by)` take no writer, so no caller can commit
code the dry run did not run. A batch read without a mapping has no writer (422 `BATCH_HAS_NO_MAPPING`). Tests reach the
commit only through test-only templates (`FixtureShopTemplate`, `ScriptedTemplate`, `RetailExportTestTemplate`).

- **The preview** (`ImportPreview.preview(run)`, read-only, writes nothing). For every `pending` or `parsed` batch of the
  run, in commit order (the template's entity sets, then `seq`), each row's outcome, decided in this order: `excluded` (left
  out by the review), `blank` (`BLANK_ROW`), `blocked` (an open blocking issue), else `write`. An open warning does not hold a
  row back. Records by type are the rows to write per entity set. The template's forecast is given every row to write, in
  that order, 1,000 at a time, with its typed values (as a `RowCheck` sees them), and answers the predicted balances per
  branch code: stock quantity and value, receivables, loan principal outstanding, loans by arrears bucket, and each opening
  journal with its debit and credit totals. A run whose batches name two template versions is 409 `templates_differ`.
- **The report** (`CommitReport`, the same for a dry run and a real run): per source the rows `read`, `written`,
  `existing`, `skipped` (a batch, or each file of a batch that holds several, as the retail export does); the anomalies
  with file and line; the opening journals (branch, kind, the debit and credit totals the ledger holds for the entry, read
  back from its lines, status, entry number); the balances written per branch (stock quantity and value, receivables, loan
  principal, loans and principal by arrears bucket); notes; the balances checksum; and the failure. `render()` prints it as
  plain text in the form `import-retail` prints. A writer tells it what it did through its `BatchWriter.Checkpoint`, now a
  `CommitReport.Sink` (`counts`, `anomaly`, `journal`, `stock`, `receivable`, `loanPrincipal`, `arrears`, `note`,
  `checksum`); without per file counts, a batch is one line from the map its writer returns, `read` defaulting to its row
  count. Every text a writer gives (a source's name, an anomaly's file and message on any line, a journal's branch, kind,
  status and entry number, a balance's branch or bucket, a note, the checksum) is screened against the personal values of
  every staged row of the run, every batch and every line, read once before the first batch: the cells a mapping feeds into
  a personal field and those fields' typed values, of four characters or more as the validation's rule, found anywhere in
  the text in any case, and their digits, seven or more, found among the text's digits. One that holds one is kept as
  `CommitReport.HIDDEN`. A failure names the batch and the kind of failure (`batch 3 failed (IllegalStateException)`), never
  the message of an unexpected error, which may quote a row; a dry run stopped by a coded refusal (an `ApiException`)
  names its code and a fixed sentence written in the code for it (`ImportRunService.REFUSAL_SENTENCES`), never the
  exception's message (#885, the Lead on #902): `batch N failed (plan_limit_reached: the plan's limit refused this
  batch; move the business to a plan with room)`, and for any other code `batch N failed (refused with code
  duplicate_sku)`. Each batch's part is stored in `import_batches.report` in its commit transaction, so
  `ImportRuns.commitReport(run)` is the committed batches' parts in order, resumed or not.
- **The dry run** (`ImportRuns.dryRun(run, by)`, a run `in_review`, or `committing` for a resume on a new build, else 409
  `run_not_open`; 409 `batch_not_parsed`
  while a file is not read or was refused). It first takes the business's commit loop, the session lock a commit holds
  (#779), and keeps it to the end, and every batch, of a dry run or a commit, takes the business's import key with
  `pg_try_advisory_xact_lock`, never a wait (Lead ruling 5 on #869): a commit or resume of the business while a dry run
  runs, a dry run while a commit runs, and a batch that finds the key taken are all answered at once with 409
  `IMPORT_IN_PROGRESS` ("Another import of this business is being committed or dry run; try again when it ends." when
  the loop is held, "Another import is being written for this business" when the key is), so a dry run never makes a
  commit fail at
  a statement timeout. A server that cannot tell its build apart (no `build-info.properties`, no commit SHA) refuses the
  dry run, the commit and a resume with 503 `build_unknown`. The commit's trading guard is asked before any row is held:
  the writers' postings lock the business's ledger and stock rows until the rollback, so a business that already trades is
  dry run only under the approved trading override (409 `TENANT_TRADING` otherwise, with nothing audited), and then within
  `bms.imports.dry-run-limit-while-trading` (60 seconds), the outer transaction's timeout: past it every statement of the
  dry run is refused, it fails as `batch N stopped at the time limit of a dry run into a trading business`, and the staff's
  postings wait no longer. Every other dry run is bounded by `bms.imports.dry-run-limit` (15 minutes), so one into a
  business whose staff begin to post while it runs still ends. The limit is a deadline checked at each statement, so a
  writer never keeps a long step without one. One outer transaction, marked rollback-only first, resolves the writers as the commit does and
  runs the commit's own batch code for every batch in order: the import key, the batch taken `FOR UPDATE`, the writer, the
  batch's move to `committed` with its counts and report part. The kill switch and the halt, which guard writes, do not
  stop it. The first batch that throws stops it, as in a real run, and the failure is recorded. Then the transaction is
  rolled back, so no row, no sequence value and no file is left (a writer must not open a transaction of its own, write a
  file or call `nextval`). In a second transaction, holding the run `FOR UPDATE`, the stamp of the run's inputs is taken
  again: if a batch, issue, resolution, the cutover moment or a policy decision changed while the dry run ran, nothing is
  stored (409 `run_changed`); otherwise the report, the time, who ran it, the stamp, the writers' identity and whether the
  preview's balances equal the dry run's are stored on the run. The balances compare per branch and kind, summed, in any
  order, a zero the same as none: stock quantity and value, receivables, loan principal, loans and principal per arrears
  bucket; and the journals journal by journal (branch, kind, debit and credit), never summed, so two of 500 are not one of
  1,000. It writes no audit row, so that every table's row count is unchanged; the
  run row itself records who ran it and when. The log line carries the run id and counts only.
- **The writers it rehearsed.** The dry run stores a first line naming the build (its version, build time and commit
  SHA), then, one line per batch, the batch's number, its template and version, the template's class, the entity set and
  the loader's `BatchWriter.identity()` (by default its class without the JDK's per-process lambda suffix; the retail
  export's writer adds the SHA-256 of every export file and the first live day, the business's today when none is given).
  The same text is part of the stamp, so a template, version, loader or deploy changed since the dry run makes it stop
  counting. The commit's start, and a resume for the batches it has left, refuse another build or writers that are not
  those lines (409 `dry_run_writer_differs`), so a run never commits code its dry run did not run: a loader's class name
  alone does not tell two builds apart. A run part committed when a new build is deployed needs a new dry run before it
  resumes (Lead on #869): a run `committing` and not being written may be dry run for the batches it has left, on the
  build that will resume it, and a resume refuses a dry run that failed (409 `dry_run_failed`) or did not match the
  preview (409 `preview_differs`), as `ready` does. A loader whose behaviour depends on state it captures names that state
  in its identity (`ImportTemplate.loader`), since two loaders of one lambda are otherwise the same writer. The trading guard is not
  asked of that dry run, as it is not asked of the resume, but the time limit holds while the business trades.
- **When it counts.** `ImportRuns.dryRunReport(run)` answers the stored dry run and whether it still counts: its stamp is
  the run's stamp now. The stamp is an MD5 over the application's version and commit (another build of the templates' code),
  the cutover moment and policy, every batch's status, file, mapping version, parse and row count, every issue's status,
  version and resolution, every resolution, every staged row's review columns and typed values, and the last batch any run
  of the business committed, each part with a marker for NULL. So a new resolution, a new mapping version (a `map_value`
  moves the batches to it), a new upload, a parse, a cancel, a new validation (which gives every issue found again its next
  version), a deploy or another run's commit into the business makes it stop counting. Records the business makes by
  trading are not in the stamp: the trading tenant guard refuses the dry run and the commit into a business that trades
  unless the override is approved (1/16). The stamp
  reads every staged row of the run each time it is taken (the dry run, `ready`, the commit's start, a read of the report);
  its cost at the row limit is measured by the big file test of #137.
  `ready` needs a stored dry run that counts
  (409 `dry_run_required`), did not fail (409 `dry_run_failed`) and matched the preview (409 `preview_differs`), and the
  commit's start checks the same again. A `ready` run cannot change, so the dry run it was made ready with is the one
  that counts at the commit.
- **The retail export.** Until the retail template of #135, `import-retail` stays the retail path and its own logic is the
  writer the retail template's loader will be: `RetailImporter.exportWriter(dir, firstLiveDate)` imports the whole export
  directory as one batch in the batch's transaction (each file's transaction joins it) and tells the checkpoint what the
  command's report holds (`ImportReport.into`, each opening journal's debit and credit read from its entry's lines) and the
  stock of every branch as the books hold it (the quantity on hand from the stock ledger, the value from the inventory
  account, at three places or more, never rounded). A file changed while the export is read stops the batch; the window
  between the commit's check of the writer and that read closes with #135, whose staged files the stamp holds.
  `RetailImporter.toCommitReport` gives a run of the command the same report, so the framework's dry run of such
  a run is the command's real path rolled back. Without a retail forecast its preview predicts nothing, so such a run is
  not made `ready` (`preview_differs`) until #135 gives it one. Loan principal and arrears buckets belong to the lending
  template, whose preview #136 proves against the loan book.
- **Proven by** `ImportPreviewIT` (the golden files' rows by outcome and records by type, and an exclusion moving a row; the
  fixture forecast's balances on two branches, worked out by hand; the comparison sums per branch and kind and tells a
  different quantity, bucket, branch, an unbalanced journal or one journal split in two; a dry run leaves every table's row count, every sequence and
  the business's stored files as they were, the audit log and the ledger included; its report equals the real commit's on
  the same input with the same writer; the books after the commit, read from the ledger per branch and the stock ledger
  (stock quantity and value, receivables, loan principal, arrears aged from the entries' days, each journal's debit and
  credit from its lines), equal the preview; no text of the report names a personal value of the run, a careless writer's
  names in notes, on other files, on line 0, of other lines and batches, in the checksum and as a source all hidden, a
  buyer's number kept; a stored dry run stops counting after an override, a `map_value`, another run's commit into the
  business and a cancelled batch, and `ready` is refused until a new one; the writers are the templates', one line per
  batch, no method of `ImportRuns` takes a writer, and a run whose writers changed since its dry run is refused and left
  untouched; a run rehearsed on another build does not resume until a new dry run of its batches left, and a server with
  neither build-info nor a commit SHA refuses the dry run, the commit and a resume, leaving no trace; a trading
  business is not dry run without the approved override, nothing audited, and under it a dry run past its time limit is
  stopped and stored as failed; a name in capitals is hidden too; a dry run that finds the business's import key taken is answered at once and stores nothing, and a commit
  and a dry run of the business while a dry run runs are answered at once; a failed dry run is stored, leaves no trace
  and refuses `ready`; a dry run that differs from the preview refuses `ready`; the refusals outside review and with a
  refused file; another business can neither preview, dry run nor read either stored report, and reads none of
  `import_runs` or `import_batches` as `bms_app` under forced RLS), `RetailDryRunIT` (the framework's dry run over
  `fixtures/retail/import-sample/` writes nothing, every table and sequence counted and every file listed, and equals a
  real `import-retail` run's report, the golden counts and opening journals included; after the real run, the stock per
  branch it reported (three branches) is the stock ledger's and the inventory account's, and each journal's debit and
  credit are its lines', balanced; a changed export file makes the stored dry run stop counting; it names no buyer;
  another business cannot reach it), `ImportRunsIT` (every writer through the test-only `ScriptedTemplate`),
  `ImportMappingsIT` (the fixture's loader resolved for the dry run and the commit), `ImportUploadsIT` and
  `ImportUploadReviewIT` (`dry_run_required`, and `BATCH_HAS_NO_MAPPING` for a file read without a mapping), and
  `MigrationOrderIT` (V142 keeps every run and batch, and the CHECK keeps the dry run columns whole).

**7/16, the commit (#133, migration `V143`, FR-IMP-07, ADR-027 decisions 4 and 7, spec sections 3.7, 3.8 and 10.2).**
The commit of 1/16, with the writers of 6/16, gains what makes it the recoverable unit of ADR-027. There is one commit
path, `ImportRuns.commit(run)` and `resume(run)`; the dry run runs the same batch code. No route yet (#141).

- **Maker and checker (`import_commit`, chapter 8 section 8.4).** `ImportRuns.requestCommit(run)` is the maker's request
  for a `ready` run, by the signed-in staff user, who needs `core.imports.manage` (the approvals module refuses anyone else,
  403). It needs the verified dump and every policy decision first, and is raised in the head office's branch with a
  checker notice of the number of files, never a value. A different user with `core.imports.approve_commit` approves it
  in the approvals queue (`self_approval_forbidden` for the maker; the database refuses it with
  `approval_checker_is_not_maker` on the request and `import_runs_commit_checker_not_maker` on the run). The approval
  executes no batch, since a batch commits in its own transaction: it records the maker, the request, the checker and the
  time on the run and the run's version just after it (`commit_approved_version`). The commit's start then requires the
  run at that version (409 `commit_not_approved`), so a dump recorded or a trading override set after the approval needs
  a new approval; a run that changes while the request waits makes it stale (FR-APR-08). The action is not a row of the
  approval rules (ADR-091): it always asks.
- **The verified dump.** `recordPreImportDump` records the dump (1/16) and clears any earlier check;
  `verifyPreImportDump(run, sha256, listedEntries)` records the check: the SHA-256 again, which must be the recorded one
  (422 `pre_import_dump_differs`), and the number of entries `pg_restore --list` listed, above zero. The commit, a resume
  and the request need it (409 `pre_import_dump_unverified`).
- **Dependency order, one transaction each, 10 minutes a statement.** The batches are written in the template's
  dependency order, its entity sets and then their number (as the validation and the preview order them), so a run
  uploaded sales first still writes items first; a resume goes on from the first batch of that order not committed. Each
  batch's transaction runs `SET LOCAL app.import_commit = 'on'` after it takes the import key, and sets the statement
  timeout: 10 minutes on a real commit, the dry run's own limit in a dry run (60 seconds into a trading business, 15
  minutes otherwise; Lead ruling 4 on #872). The run's report lists the batches by the dependency index of their entity set (`dependency_rank`) and
  then by batch. A resume is refused without the verified dump, without the approval, or on another build than the one
  the dry run rehearsed (`dry_run_writer_differs`).
- **`import_refs` and `source_ref`.** The generalisation of `retail_import_refs` (V20): `run_id`, `batch_id`, `source_key`,
  `source_ref`, `target_type`, `target_id`, append-only under forced RLS. `ImportRefs.sourceRef(file, tab, row,
  contentHash)` is the one function that makes a reference: the mapping source's file and tab, the row (`ref:<code>` for
  a source known by its reference column, `line:<n>` for one known by position) and `ImportRefs.contentHash` of the row's
  mapped values (their canonical JSON: fields sorted, a null left out, each number in one form, a date, an instant or any
  other value as its ISO text, so the validation's values and the same values read back hash alike). A source known by
  position is keyed by its upload instead of its file name (`upload:<sha256>`), so two till rolls of one run, or next
  month's roll, never share a line key (Lead ruling 1 on #872). `source_key` is the reference without the hash. A writer reads its rows through `BatchWriter.Checkpoint.rows`,
  each with its reference and whether the business's imports hold it (`NEW`, `EXISTING`), and registers every record it
  creates with `Checkpoint.register` in the batch's transaction; an `EXISTING` row is counted existing and written no
  more, so a resume, or a re-run of the same rows, writes nothing twice. A row whose key is held with another hash is
  `CHANGED_SINCE_IMPORT`: the validation reports it on the row, blocking, so the commit never reaches it, and the commit
  refuses the batch if one reaches it all the same; the change is made in the app (spec section 10.1). The preview leaves
  out rows already imported with the same content, so a re-run's preview predicts only what is new.
- **Opening balances.** `Checkpoint.openingJournal(ImportRefs.Opening)` posts one journal per batch, branch and kind (two batches
  that hold one branch's stock post one each, and the template's forecast predicts one per batch) through
  `post_entry` (`LedgerPosting`), against `opening_balance_equity` (debit the account for what the business holds,
  credit it for what it owes), source `core.imports`, `opening_balance`, and registers it in `import_refs` in the same
  transaction, so it is never posted twice; the report reads its totals back through `LedgerPosting.totals`. A zero is
  `nothing to post`. The ledger's trading probe does not count journals of `core.imports`.
- **Policy decisions (spec section 3.8, ADR-027 decision 7).** A template declares its decisions (`ImportTemplate.policies`,
  `ImportTemplate.Policy`): key, options, options allowed only with the dev lead's exception, the refusal code and the rows
  that need it. `Policy.receivable(entitySet, rule)` is the receivable decision (`open_from_list`, `treat_as_settled`,
  `defer`, refused with `CREDIT_DECISION_MISSING`). `ImportRuns.recordPolicy(run, key, decision)` records the option, the
  day the customer confirmed it and the reference of the confirmation, and for `defer` the platform operator who granted
  the exception and the day of the follow-up (422 `policy_unknown`, `policy_option_unknown`,
  `policy_confirmation_required`, `policy_exception_required`), on a run not yet ready, audited. `ready`, the request and
  the commit refuse a run whose rows need a decision it has not recorded. The loader reads it with `Checkpoint.policy` and
  the forecast with `ImportTemplate.forecast(policy)`, so the preview predicts the journals the decision gives.
- **No side effects.** While `app.import_commit` is on, triggers refuse a new row of `notification_outbox`,
  `sms_deliveries`, `approval_requests` and `scheduled_tasks`: an imported record sends no SMS or email, starts no task and
  raises no approval; such a writer fails its batch. Every application event carries the `imported` marker
  (`ImportedRecords.Marked`: `PushNotice` and `ApprovalAmountsChanged`, set when an import batch's transaction makes
  them), and every listener skips an imported one, so a stock change an import makes commits and raises no low-stock
  notice (V76). `ImportSideEffectGuardTest` fails the build on a published event without the marker, a listener that
  does not skip it, and a `REQUIRES_NEW` (which `SET LOCAL` does not reach) not listed with a reason.
- **Only opening journals (Lead ruling 6 and the Lead's note on the review of 8e570b0, #872).** Writers never vouch for
  their journals: at the end of each batch the framework reads every entry the transaction posted from the ledger. One
  passes as the framework's opening journal (`core.imports`, `opening_balance`, a line on `opening_balance_equity`,
  registered by `Checkpoint.openingJournal`, the only way a writer registers a journal: `Checkpoint.register` refuses
  `ledger.journal_entry`). The one exception until #135 moves retail onto `openingJournal`: an opening journal of the
  `import-retail` command, whose logic the retail export's writer reuses (module `retail`, source type
  `retail.import_opening`, `retail.cash_opening`, `retail.advance_opening` or `retail.advance_repayment_opening`, a line
  on `opening_balance_equity`), which the framework, not the writer, registers. Any other journal fails the batch.
- **Kill and resume.** `CommitSeams.afterBatch` is a test hook only: a test bean ends the loop after batch k as a killed
  process would. The run stays `committing`, the batches before stay committed with their references, and the resume
  reaches the state of an uninterrupted run.
- **Operators.** `allowTradingTenant`, `approveTradingTenant`, `setCommitEnabled` and a `defer` exception take only an
  active platform operator (403 `operator_required`, review of #736).
- **Proven by** `ImportCommitIT` (dependency order with uploads out of order; the statement timeout and the import setting
  of a batch; a kill after batch k and the resume equal an uninterrupted run, no reference, row or journal twice; a failing
  batch rolled back alone and the run resumed; every journal balanced and none but the registered opening journals; each
  receivable decision's journals; every refusal of the commit and the request; `CHANGED_SINCE_IMPORT` and a re-run that
  writes nothing; two till rolls and next month's roll; two stock batches of one branch; a dates and instants hash;
  each refusal of a resume; no message and no approval from a batch; another business reads no reference and reaches no
  run), `RetailDryRunIT` (the retail export committed through this path with `import-retail`'s own logic),
  `PushEverydayNoticesIT` (an imported stock change tells nobody),
  `ImportRunsIT`, `ImportPreviewIT` and `ImportMappingsIT` (every commit through the request and approval),
  `ImportCatalogueTest` (`CHANGED_SINCE_IMPORT` and `CREDIT_DECISION_MISSING` produced by named tests, none pending) and
  `MigrationOrderIT` (V143 keeps every run, the CHECKs, append-only `import_refs` under forced RLS, the trigger).

**8/16, the reconciliation and the sign-off (#134, migration `V144`, FR-IMP-08, ADR-027 decision 8, spec sections 3.9 and
3.10).** `core.imports` gains the public API `ImportReconciliation` (`reconcile`, `latest`, `forSignOff`, `signOff`), the
template SPI `ImportBooks` (`ImportTemplate.books()`: the stock ledger and the loan book a template answers for, none by
default), `ImportForecast.productStock()` (the forecast's stock per branch and product, none by default), and on the
ledger's port `LedgerAccounts.lines(entries)` and `trialBalanceCheck()`. The first routes for the tenant admin and the
sign-off screen (chapter 7 section 7.11.9). What each check reads and how differences are itemised is section 13.11.

- **Made after the commit.** The commit's last step, once the run is `committed`, makes version 1 in its own transaction;
  a failure there is logged (the run and the kind of failure) and never undoes the commit, and the reconciliation is made
  again on request (`core.imports.manage`, audited `core.import_run.reconciled` with the version, whether it can be signed
  and how many checks do not tie).
- **Independent of the writer.** Neither the commit's stored report nor a writer's counts are read: the source is the
  staged rows measured by the template's own forecast (the rows written netted per product over the whole run, each row
  left out alone, keeping its own part), and the system is what the ledger, the stock
  ledger, the loan book and `import_refs` hold. A writer that posts more or less than it reported, or registers no record
  for a row, shows as a difference no item explains.
- **Stored as a document, signed once.** `import_reconciliations` (one row per version: the whole report as JSON, its
  SHA-256, the day it is as at, whether it can be signed) and `import_signoffs` (one per run: the reconciliation and its
  SHA-256 as read, the sections ticked, the name and date typed, who signed). Both append-only under forced row-level
  security, with no update path; the trigger `import_signoff_rules` refuses a sign-off of a run that is not `committed`, of
  a reconciliation that is not the newest, with a difference not itemised or with another SHA-256, and any new
  reconciliation of a signed run. `ImportRuns.transition` no longer reaches `signed_off`.
- **Templates until #135 and #136.** No production template is registered yet: the retail checks are proven with the
  test-only `fixture_shop` template, whose books are the retail stock ledger its test writer posts to, and the lending
  checks (loan principal, arrears) with the same template, whose loan book is the lines of its `loans_receivable` opening
  journals aged from their day; `retail_v1` (#135) and `lending_loans_v1` (#136) give their own `ImportBooks` and
  `productStock` and run the same reconciliation on their golden files.
- **Not in this step.** The certificate of deletion and the purge that sign-off starts are #139; the operator's screens
  are #141.
- **Review of #873.** The stock quantity reads only the movements the run registered; the sign-off measures the books
  again (409 `reconciliation_changed` on a mismatch); a row is counted once, left out decided first, and a left-out row
  adds nothing to the system side, whatever an earlier run holds; the trigger takes the run in both branches; stock values
  are exact decimals; a left-out row the rules cannot measure is named with the kind of failure, and a written one is a
  difference nothing explains, named the same way; the measurement runs under a 10 minute statement timeout; the signer's
  name is erased with the business and never logged; the screen offers "Make the reconciliation again" to whoever holds
  `core.imports.manage`.
- **Proven by** `ImportReconciliationIT` (on the golden files every check ties and every difference is exactly the rows left
  out, by file and line, the system values worked out by hand from the books; a receivable decision is one `policy` item;
  a deliberately broken balance does not tie and the sign-off is refused by the service and by the database; the sign-off
  needs `core.imports.sign_off`, every section, the name and today's date, refuses a reconciliation made again since it was
  read and an unknown property, and is audited without the name; the signed report never changes and no new one is stored;
  no text of the report holds a personal value of the golden files; another business reaches none of it, through the
  service, the routes or `bms_app`), `RlsIsolationIT` (forced RLS and append-only on both tables), `MigrationOrderIT` (V144
  keeps every run, the permission is the tenant admin's alone, the trigger's refusals), `PermissionMatrixIT` and
  `RoutePermissionIT`.

**9/16, `retail_v1` on the framework and `import-retail` as its wrapper (#135, migration `V145`, ADR-027 decision 1, spec
section 4.1 and Appendix A).**

- **The template.** `retail_v1` (`RetailV1Template` in `retail.imports`, a bean the registry finds) has the ten entity sets
  of the normalised export in its import order, each field of `docs/specs/retail-pilot-data-dictionary.md` section 4 with
  its type (codes by the catalogue's normaliser, `ref` from history and balances to branches and products, money ranged
  from zero, dates as the export writes them, `qty_by_branch` a JSON object or `BRANCH=qty` pairs from a sheet), the retail
  codes of spec 3.4.1 (`NAME_MISSING`, `NEGATIVE_BALANCE`, `PRICE_DIFFERS_EXISTING`, `CREDIT_SALE_UNPAID`; the receivable
  decision's `CREDIT_DECISION_MISSING` is shared), the receivable decision with `treat_as_settled` and `defer` (the export
  holds no outstanding amount per buyer, so `open_from_list` is not offered and is refused with `policy_option_unknown`),
  a forecast and its books. The cash book files of 13.13.1 are not in it: they stay the command's.
- **One loader, two drivers.** Its loader is the command's own code (`RetailImporter`): a batch's staged rows are written
  back as the export's lines and run through the same file code, so a sheet read with a mapping and the export read by
  the command write one way. Nothing is kept from one batch to the next: the names a products row may use are the
  catalogue's, the instant before the history is the stock ledger's, and each branch's opening stock journal is posted
  through `openingJournal` (ledger type `retail.import_opening`, source module `core.imports`). **One opening stock
  journal per branch, ever:** the first run that commits balances for a branch posts it, for what the branch's balance
  rows above zero are worth at each item's current cost (the source's quantity, which the legacy balance makes the
  derived one whichever run wrote the history, so the first journal after a stopped or killed run still values
  everything imported; the rules of #872 and #873: a negative balance is never journalled and is listed for the first
  stock-take; an item with history and no balance row is not journalled, as the old command did). A later run, command
  or screens, for a branch that already has an import opening journal (the framework's, the command's or the old
  command's) posts none for it ("already posted"); its rows still go through, a new item's legacy balance is written,
  and each difference against the books is listed for a stock-take. The forecast applies the same rule through the same
  function (`RetailV1Template.BranchOpening.journal`, the opened branches read by `openedBranches`, leaving out the
  forecast run's own journal, `ImportTemplate.forecast(policy, runId)`), so the preview and the commit always agree.
  Every row the loader writes is registered, the records it matched included, so the reconciliation finds each one in
  the books.
- **The wrapper.** `import-retail --tenant --dir [--dry-run] [--first-live-date]` keeps its interface, its tolerant reading
  of the export and its report, and runs on the framework (`ImportCommandRuns`): a run of origin `import_retail`, one
  batch per file recording the export's mapping (the identity mapping of the export onto `retail_v1`, stored once per
  business as `import-retail-export`), each written with the commit's guards (the import key, `app.import_commit`, the
  imported marker, the end-of-batch journal check), its records in `import_refs`. The command is the platform operator's
  tool on the host under its runbook, so it has no upload, review, stored dry run or maker and checker, and its runs are
  not reconciled in the app; its dry run is one outer transaction rolled back, run included. A second command is refused
  with `IMPORT_IN_PROGRESS` while the first writes a batch (it holds the import key) or within its lease of 15 minutes
  after its last batch; a run of the command a killed process left `committing` past its lease is cancelled by the next
  one. Each file of a real run is its own transaction.
- **`retail_import_refs` into `import_refs`, expand and contract (chapter 6 section 6.9).** V145 copies every reference of
  the old table into `import_refs` under the key a JSON Lines upload of the export derives (`sales.jsonl|-|ref:SAL-00011`)
  with the marker `#by-key` (`ImportRefs.BY_KEY`): the command never hashed a row, so such a key is the same row whatever
  its content, for the command and for the framework (no `CHANGED_SINCE_IMPORT`). The command now registers by key in
  `import_refs` and still writes the old table; the readers moved to `import_refs` (the trading probe `RetailActivity`, the
  command's own) except the cash book's first live day, which keeps reading the old table. Dropping it is a later release.
- **Proven by** `RetailImportIT` (unchanged, against the wrapper), `RetailImportReportIT` (the report text on the sample
  is the golden text captured from the command before the wrapper), `RetailV1TemplateIT` (the sample through the framework
  writes nothing in its dry run, commits what the dry run predicted, gives the books the command gives, reconciles and is
  signed off; a fabricated spreadsheet-shaped book with a mapping gives the state the same data as a JSON Lines export
  gives; `open_from_list` refused; another business reaches nothing), `RetailImportRefsBackfillIT` (a re-run after the
  backfill recognises the old command's rows), `RetailV1TemplateTest` and `MigrationOrderIT` (V145).

**10/16, `lending_loans_v1` and the pilot register as its first mapping (#136, no migration, ADR-027 decision 1, spec
section 4.1; coordinated with #110, which builds no second framework).**

- **The template.** `lending_loans_v1` (`LendingLoansV1Template` in `lending.loans`, a bean the registry finds) has one
  entity set, `loans`: a row per loan of a register, its 25 columns as the fields of 13.8 (`disbursed_on`,
  `customer_name`, ..., `remarks`, every one read as text and typed by the rules of 13.6), plus the batch's `branch` (a
  branch code; empty is the head office) and `product` (a loan product code, required). The rules are pure functions
  (`LoanRegister`) that the validation, the forecast and the loader all call, so what the review shows, what the preview
  predicts and what the commit writes agree. Its catalogue is 13.7 as amended there; the codes are pinned in
  `fixtures/imports/issue-codes.txt`.
- **Rules over the run's rows (the SPI `ImportTemplate.SetRules`).** The engine of 4/16 sees one row at a time; the row
  classes of 13.5, the dates of 13.6.3 and the linking of 13.9 need the rows around it. A template may now answer
  `setRules(entitySet)`: pass 1 indexes every row the review has not left out, in commit order; pass 2 classes a row first
  (a classed row has no record, so none of its fields is checked: no `REQUIRED_MISSING` on a sub-header), then, after the
  fields and the row checks, answers its findings and the values that replace its typed ones (a resolved date as ISO
  text), so the preview, the forecast and the loader read the resolved date. A finding may carry a suggestion (a repaired
  date, the implied rate, the rounded amount), kept only on a field that is not personal. A row classed `SUBHEADER_ROW`
  is left out like a blank row (`ImportTemplates.LEFT_OUT_CODES`: the preview counts it blank, the commit never writes it,
  the reconciliation counts it blank).
- **Register-specific behaviour as mapping row rules (spec section 4.2).** `row_rules.subheader_month: true` classes a row
  of at most two cells, none an amount, one a month label, as a sub-header that dates the rows below it (a month without a
  year takes the next data row's year when every reading of its DATE has one); `row_rules.placeholder_rows` holds the
  placeholder patterns of 13.5 (the mapping's, whole-cell, ignoring case). A row of amounts only is the template's own
  class, whatever the mapping.
- **The batch's branch and product (13.3), as the mapping's constants.** A mapping source may now give `defaults`: the
  value of a field no column of it feeds (the schema allows only a field the set does not mark personal; a field both a
  column and a default feed is `FIELD_MAPPED_TWICE`; a required field fed by a default is fed). The pilot register's
  sources give `product` (and the second branch's `branch`), so one register file is one batch of one branch. The loans
  officer is the person who opened the run (`import_runs.created_by`); a remark is kept as a collection note only when that
  person is a staff user of the business.
- **The pilot register as a fabricated fixture mapping.** `fixtures/imports/lending_loans_v1.pilot_register.mapping.json`
  maps the 25 columns of `fixtures/pilot_loan_register_sample.csv` onto the template (source `register`), switches on the
  two row rules, and names a second source `register_br2` for the fabricated second branch
  (`fixtures/imports/lending/fab-register-branch-two.csv`). Real mappings are never committed.
- **The disbursement decision (13.8 row 15) on the run.** The policy `disbursed_principal`, option
  `requested_as_disbursed`, is needed by every register row: `ready`, the commit's request and the commit refuse a run
  without it (`DISBURSEMENT_DECISION_MISSING`), recorded with the customer's confirmation like the receivable decision.
  The loader writes the requested principal as approved and disbursed.
- **Linking against the business's own members (13.9, the note of #130 on #136).** Each borrower is matched in the run's
  order, as the loader matches it: the same key earlier in the same file is that member (`DUPLICATE_BORROWER`); else the
  member with the NIN, or the one member holding the phone unless they differ (both NINs given and different, or a name
  that differs when one has none), among the business's members (`MEMBER_MATCHED_EXISTING`) and those the run's earlier
  rows make; a member on that phone who differs, or two on it, is `MEMBER_PHONE_CONFLICT` and a new member. The reads go
  through the lending port `MemberImports` inside the validation's transaction, so only the business's own rows (each
  lookup also names the business). A review's `link_member` reaches the loader (`ImportRefs.StagedRow.linkedMemberId`):
  every row of the linked row's key in the batch takes that member, and a key whose rows are linked to two members fails
  the batch. An existing member's fields are never changed.
- **The loader (13.10 steps 1 to 7).** One batch, one transaction: every borrower matched or made (the details of the
  key's latest row by date), then per row its next of kin (a duplicate for the member collapses; a kin whose NIN is a
  member's is linked `nin`, `confirmed`), its collateral item (`pledged`, no value, "imported; verify custody and value";
  a reference another active item holds is kept in the description; the item of a loan the register shows repaid is
  `released`, its pledge too), the loan (`channel = import`, flat, per term, bullet; inserted as a draft so its pledge
  is accepted, as the V8 trigger `lending_loan_collateral_guard` asks, then moved to its status from the balance), its
  one bullet item, a historic disbursement and the historic repayment with its allocations by the product's order
  (`is_historic`, no journal), the position as at the cutover day (DPD). Every record a row made is registered in
  `import_refs`, the loan's own rows too: member, next of kin, collateral item, loan, status history, schedule item,
  transaction, allocation, pledge and collection note. A row's branch must be open (`Branches.lockBranch`), or the batch
  is refused (the branch limit of 1/16). No SMS, task or approval (the trigger of 7/16).
- **One opening journal per branch, ever (the rule of #135).** After its rows, the batch posts, for each branch it wrote
  that has no import opening journal of loans yet, one journal through `openingJournal` for every loan imported into the
  branch (by any run) still owing principal: debit `loans_receivable`, one line per loan with the loan as subledger (the
  framework's `ImportRefs.Opening.lines`), credit `opening_balance_equity` the total; and the same for members' credits
  (`member_overpayments`). Valuing every imported loan of the branch makes the first journal after a stopped run value
  everything imported. A branch that already has one takes none; the report lists its new loans for a correcting entry.
  "Ever" holds under concurrency because every batch of the business first takes the business's import key
  (`pg_try_advisory_xact_lock` in `ImportRunService`, Lead ruling 5 on #869), so two batches never check and post a
  branch's journal at once. Within a run, a branch is one file: two files of one branch get `BRANCH_IN_TWO_FILES` on the
  later file's rows, and a template's ready-blocking codes (the SPI `ImportTemplate.readyBlockingCodes`, none by default)
  keep the run from `ready` while one is open on a row the review keeps.
  The forecast applies the same rule (the branches opened by other runs, the principal their imported loans still hold,
  and this run's rows by `LoanRegister.plan`), so the preview and the commit agree.
- **The forecast and the books.** The forecast predicts per branch the loan principal outstanding, the loans and
  principal per days-past-due bucket as at the cutover day (`current`, `1-30`, `31-60`, `61-90`, `over-90`) and the
  opening journals; the books (`ImportBooks.arrears`) age the run's loans from their schedule items in the loan book.
- **The trading probe.** Lending's `LiveRecordProbe` no longer counts the historic transactions of a loan the framework
  imported (`import_refs`, `lending.loan`), so a resumed or later run is not refused for what an earlier one wrote.
- **Proven by** `LendingLoansV1TemplateTest` (the pilot register through its mapping gives the issues, suggestions and
  values of 13.12 exactly; after the standard resolutions only rows 7, 9 and 13 stay blocked; a register of fabricated
  edge cases gives every other code of the catalogue; the codes are pinned; no message holds a value of the register; the
  normalisers read the tables of 13.6; the forecast predicts the journal of 2,810,000 in six lines and the days past due
  of 13.12; a branch another run opened is predicted none) and `LendingLoansV1TemplateIT` (on PostgreSQL as `bms_app`,
  at two branches: the dry run writes nothing and matches the preview; the commit creates what 13.12 lists; the preview's
  loan principal per branch equals the loan book and the `loans_receivable` lines, its loans and principal per bucket the
  loan book aged to the cutover day, each opening journal's debit and credit its `journal_lines`; every record is in
  `import_refs`; the reconciliation ties; the same register as a workbook gives the books the CSV gives; a re-run and a
  resume after a kill post no second journal and write nothing twice; a business with a member and a loan keeps them,
  has its borrowers linked to that member and journals only the imported loans; a run without the decision is not
  ready), with `ImportCatalogueTest` (the two shared codes and the lending catalogue pinned).

**11/16, the error-proofing suite (#137, no migration, spec section 5).** Tests, and one fix the fuzzing found:

- **A formula is read as the sheet shows it, or reported.** A workbook cell that holds a formula's error (`#N/A`, `#REF!`)
  was read as that text, and a formula saved without its value (a file a program wrote and no spreadsheet calculated) as
  an empty field, so an optional field lost it in silence. The reader now marks the second as a cell of type `formula`
  (`XlsxRows.FORMULA`, empty value), and the validation reports both, on any field, as `TYPE_INVALID`
  (`RowValidator.UNREAD_CELLS`); an operator's edit of the field replaces the cell as before. A formula with its value is
  read by its value, unchanged.
- **The suite** is SDD chapter 15 section 15.7: the golden report of `retail_v1` over fabricated edge cases, the property
  tests on generated shop books, the fuzzed files, and the big file test at each limit of 13.14 on the staging heap, with
  its times.
- **Open findings.** #900, what is left after #880 (PR #898: the loader of `retail_v1` streams the batch in pages of 250
  rows and writes each page in statement batches, a batch plans each statement for its tables as they are, each key is
  looked up by its index, the reconciliation reads a template's forecast inputs once, and the per-row keys of the
  validation, the forecast and the loader are held compactly): the validation and preview of 100,000 rows fit the
  staging heap, but in a database other businesses' imports already fill the batch's lookups of `import_refs` and of
  the items of the same name still grow with its rows, and the commit of 100,000 items fills the old generation. The
  test that shows it is disabled with that number. #881 (a restock's price change looked for within its batch only, so
  splitting the restocks over two runs lost it) is fixed: see the restock price changes in section 13.13's table.
- **The row limit is 25,000 per batch until #900** (`bms.imports.max-rows`, `BMS_IMPORTS_MAX_ROWS`): the big file test
  dry runs it in 185 s and commits it in 246 s on the staging heap with the old generation at 60 percent; 50,000 stopped
  at the 900 s dry run limit (chapter 15 section 15.7). It goes up to 100,000 with #900. The test profile keeps 100,000
  for the readers' streaming proofs; `UploadServiceTest` pins production's.

**12/16, the rehearsal on last night's backup (#138, migration `V146`, ADR-027 decision 9, spec section 5.5).**

- **The host side** is `deploy/restore-drill.sh --rehearse-import <run id>` (chapter 9 section 9.10 item 7, runbook
  `docs/runbooks/restore-from-backup.md` section 5.2): a throwaway PostgreSQL restored from the newest backup on an internal
  network of its own, the live API's migrations, the run's staged rows copied from the live database in one read-only
  transaction, then `rehearse-import` in a short-lived API container, then one `import_rehearsals` row copied onto the live
  run as `bms_app`, then everything removed. With no argument the script is the weekly drill, unchanged.
- **The application side** is `ImportRehearsals` and the command `rehearse-import`: the run goes back to review in the
  throwaway database, is dry run on the restored state and this build, made ready and committed by the same commit as any
  run, which waives the checker's approval and the pre-import dump only after finding the rehearsal's one-time token in
  the database (`bms_rehearsal_marker`, made by the script, never by a migration): `not_a_rehearsal_database` otherwise.
  The kill switch, the trading guard, the stored dry run, the policy decisions and the writers all hold. On staging a run
  not marked fabricated (`import_runs.fabricated`, said when the run opens and fixed after) is `run_not_fabricated`; a run
  neither in review nor ready is `run_not_rehearsable`.
- **What is stored** (`import_rehearsals`, chapter 6 section 6.5): the backup, the build, the outcome and its failure's
  code, the commit's report, the reconciliation the commit made, whether the report says what the run's stored dry run
  (the live one, copied with the run) said (`CommitReport.sameAs`), and the milliseconds of each step on the host and in
  the API. The source file stays encrypted in object storage and is never fetched: the commit reads the staged rows.
- **The run of the acceptance check** comes from `stage-import-run --tenant <slug> --dir <path>` (`FabricatedRunStager`):
  an `import-retail` export of fabricated data staged as a run of the import screens, marked fabricated, reviewed as an
  operator would, dry run and ready; only where `BMS_ENVIRONMENT` is exactly `staging`, and only the repository's
  `fixtures/retail/import-sample/`, checked file by file against the SHA-256 the code holds. It stands in for the upload
  form until #141. A dry run that fails is refused with its failure (`refused: dry_run_failed: batch N failed
  (plan_limit_reached: ...)`, #885), so a plan without room for the sample's three branches says so. The refused run
  stays in review and blocks nothing: staging again after moving the business to a larger plan opens a new run.
- **Running alone** (review of #882): the script holds its own lock and the puller's and deploy's locks to the end,
  starts nothing in the weekly drill's window or beside a drill, stops at its deadline (six hours, or Sunday 03:20 if sooner, by a watchdog), loads the copy in one transaction committed only when
  the live read succeeded and compares every table's count and ids, keeps keys off command lines, and runs its
  containers in the background so a stop removes them at once.
- **Not built here:** the upload form's fabricated confirmation (the operator screens, #141, pass it to `open`), and the
  production refusal of files carrying the fabricated fixture marker (spec section 5.4).

**13/16, retention, purge and the certificate of deletion (#139, migration `V147`, ADR-027 decision 8, spec section 6.4).**

- **What the purge deletes.** The run's quarantined files from object storage (each batch's `object_key` cleared and
  `quarantine_deleted_at` stamped), its wrapped data key (`import_runs.data_key_wrapped`, so any copy of a file left
  anywhere no longer opens), the cells and typed values of its staged rows (`import_rows.raw` becomes `{}`, `normalised`
  NULL, `source_ref` its hash), the original and suggested values of its issues (`import_issues.original_value` and
  `suggested_value` NULL, `message` its hash), its batches' `sheet_name` and `failure_reason` (their hash), the signer's
  name (`import_signoffs.signed_name`, its hash), and the values
  before and after, the row key and the operator's note of its resolutions (`import_issue_resolutions.before_value`,
  `after_value`, `row_key` and `note`), which become their hash: SHA-256 over a salt made for the purge and kept nowhere, so equal values keep equal
  hashes within the run and nobody can find a phone number again by hashing every number. The run becomes `purged` with
  `purged_at`; a run is never purged twice.
- **What it keeps.** The run, its batches with the file names, sizes and SHA-256, its staged rows and issues as records
  without values (so every count stays), its resolutions with hashes, the dry run and commit reports, the signed
  reconciliation and sign-off, the rehearsals, `import_refs` (the references of the records the import created, which a
  later run of the business matches on: a mapping's reference column is a business code, never a personal value, and
  the mapping refuses one that feeds a name, a phone or an ID number (`SOURCE_REF_COLUMN_PERSONAL`); its
  content hash is the first 32 hex digits of the SHA-256 of the whole mapped row's canonical JSON (`ImportRefs.contentHash`),
  which can confirm a guess of an entire row but reveals no value, and
  is what tells a later run that a row changed since its import), and the audit trail. The records the import created are the business's own and are not touched.
  One rule, no case by case (Lead on #886): every free-text column on the run's tables is hashed or cleared, and only
  codes, counts, hashes, statuses, ids and timestamps stay in clear. Besides the above, that is the trading override's
  reason and each policy decision's confirmation reference (`import_runs.allow_trading_reason` and the `confirmation` of
  each entry of `import_runs.policy`), and the checker's note and any execution error on every approval request whose
  subject is the run, whatever its action type (`approval_requests.decision_note` and `execution_error`; `import_commit`
  until V150 widened it, issue #887), with the payload of every such request but `import_commit`'s; a request of the run
  still pending is cancelled by the purge, so no note is typed on it afterwards. Their audit rows, never purged, record only that
  the text was given and its length (`core.import_run.trading_override_set`, `core.import_run.policy_recorded`), never
  an unsalted hash a guess of every phone number would undo; an approval's decision is audited without its note. A run
  has no notes and no halt or cancel reason (V127). `ImportPurgeIT` names every text column of the run's tables and its
  request as hashed, kept or codes only, so a new column fails CI until it is classed. The columns kept readable,
  each with its one reason, are listed in the V150 header (checked by `ImportPurgeIT`) and the ADR-027 note of 9 November: the file names, sizes and
  hashes and the dump's name and hash (the certificate names them), the sheets' column names, `import_refs`, and the
  reports and certificate made by the application.
- **The way past the guards** of a committed batch (Lead ruling on #867): the purge is one definer function,
  `import_purge_run(run)`, called in the business's transaction, which refuses while a file of the run is still in
  storage. `bms_import_purging` lets an UPDATE of a staged row, an issue or a resolution through
  `import_rows_validation_kept`, `import_issues_batch_kept` and `reject_mutation` only inside a definer function called by
  another role's session (`current_user` the owner, `session_user` not, so a session of the owner itself never passes),
  with `bms.purging_import_run` naming the row's own run, the row of the bound business, that run purged in this very
  transaction (`purged_at = now()`), and only the personal columns changed, to exactly what the purge writes. No delete, no
  other run, no other table, nothing in a later transaction. The setting is a custom setting any session can set, so the
  marker that matters is the definer context and the run purged in the same transaction; and the state `purged` itself is
  guarded the same way (`import_runs_purged_by_purge`: only inside the purge, never left). The removal and purge of a
  closed business keep their own way (`bms_erasing`), unchanged. `MigrationOrderIT` checks that each replaced guard is its
  V146 body with only the purge's block added.
- **Who and when.** The operator at stage 9 with the host command `purge-import --tenant <id> --run <id> --operator <email>
  --witness <email> [--dump-file <name> --dump-sha256 <hex>]` (`ImportPurges.purge`): a `signed_off` run only
  (`run_not_signed_off`), the operator and a different active platform operator as witness (`witness_is_operator`,
  `platform_operator_unknown`), and the pre-import dump they deleted on the host by its file name and SHA-256 as recorded on
  the run (`pre_import_dump_confirmation_required`, `pre_import_dump_differs`, `no_pre_import_dump`); the audit row records
  the host and user the command ran as beside the two names, which are the operator's word. And the daily job
  `core.imports.retention` (chapter 9 section 9.9), for every business with a run to purge, suspended ones too: on day 29
  after the sign-off and 90 days after the last activity of a run never signed off, never a `committing` run (its way out
  is a resume or the dev lead's restore). The operators are warned a week before; the deadline wins over a warning that
  never went (Lead ruling on #886, ADR-027 decision 8): the purge goes ahead and enqueues `imports.purged_without_warning`
  to the operators' chat and the Owner by email in its own transaction.
- **Failures.** Files are deleted first, with no transaction open, then the run is purged in one transaction. Storage that
  refuses a delete is 503 `storage_unavailable`, and any failure after the deletes rolls the transaction back: either way
  some files may be gone while the run, its key and its rows are unchanged, and running the purge again finishes it (a
  missing object is not an error). An upload that finishes after the purge is refused under the run's lock (the run is not
  open), and its stored file, which no batch names, goes with the daily cleanup's orphans.
- **The certificate of deletion** (`import_deletion_certificates`, append-only, chapter 6 section 6.5) is stored with the
  purge in its transaction: the business, the run, when, why, the operator and the witness (none for the schedule), each file
  with its name, size, SHA-256 and deletion time and its staged row count, the counts of rows cleared, issue values deleted
  and resolutions hashed, the pre-import dump's name and hash and whether the operator confirmed its deletion, and what is
  retained, as JSON and as the plain text the command prints, with the text's SHA-256. The scheduled purge cannot reach the
  host, so its certificate says the dump's deletion is not confirmed; the operator's later `purge-import` with the dump
  issues the next version, signed by them and the witness, and changes nothing else. The operator sends the text to the
  customer (runbook section 6.2); a download from the screens comes with #141.
- **Audit and logs.** `core.import_run.purged` (counts, the reason, whether the dump was confirmed and the certificate's
  hash; actor `platform` with the operator, or `system`), `core.import_run.deletion_certificate_reissued` and
  `core.import_run.purge_warned`; logs carry run ids, counts and classes of failure only.
- **Tests.** `ImportPurgeIT` scans every text, JSON and text array column of every table that refers to the run (found from
  the foreign keys, so a table added later is scanned too) and the object store, before and after; `ImportReconciliationIT`
  does the same after the real commit, reconciliation and sign-off; `ImportRetentionIT` fixes the business clock for both
  deadlines and the warning; `ImportPurgeIT` also proves the guards let nothing else through and that another run and
  another business are untouched.

**14/16, metrics and the post-cutover defect log (#140, migration `V148`, ADR-027, spec section 8).**

- **Metrics from stored data, never from logs.** `ImportMetrics.metrics(run)` is the database function
  `import_run_metrics(run)` read as JSON, one definition for the business's route and the platform report (chapter 14
  section 14.6): the run's stamps; the ten stages of the spec's section 2, each from the stamps that bound it (discovery
  happens before the run opens and is not recorded; field mapping from the run's opening to its first upload; cleaning and
  review to `ready`; the rehearsal to the newest rehearsal before the commit; the dry run to `dry_run_at`; the cutover from
  `committing_at` to `committed_at`; the reconciliation to the sign-off; the source deletion to `purged_at`; hypercare from
  the sign-off to the last defect closed); the time of each pipeline step; rows by outcome per batch (the file's rows,
  those left out in the review from `import_rows.excluded_by`, and read, written, existing and skipped from the batch's
  report part) and per template; issues by code and severity with the open ones; resolutions by kind; the questions to the
  customer (issues marked `ask_customer`, asked at the first `ask_customer` resolution of the same line, code and field,
  answered when the issue was resolved) with the median and longest days to answer; the mapping versions the batches were
  read with and the versions of those mappings saved while the run was open; the rehearsals and, for the newest committed
  one, each source count that differs from the cutover's; and the defects by code. Every figure comes from a column the
  purge of V147 keeps (codes, counts, statuses, ids, timestamps and the reports), so a purged run has the same metrics.
- **Step times are stored where the step ends** (V148, they were not stored before): `import_batches.upload_ms` (the upload
  request to the stored batch), `parse_ms` (reading the stored file into rows) and `commit_ms` (the batch's commit
  transaction, written with its counts); `import_runs.validated_at` and `validate_ms` (the last full validation) and
  `dry_run_ms` (the last stored dry run). NULL for what ran before V148; the metrics leave such a step out.
- **The defect log** (`import_defects`, chapter 6 section 6.5): during hypercare a holder of `core.imports.manage` logs a
  defect against an imported run (`committed`, `signed_off` or `purged`; otherwise 409 `run_not_imported`) with a
  description that names nobody and the issue code it should have had, which must be a code of the issue-code catalogue
  of the run's template (otherwise 422 `expected_code_unknown`; Lead on #888, so no business types a code). When no code
  fits, the defect is logged with none and `new_code_needed`, and the code proposed goes in the description, which is
  screened and hashed (spec section 8: each defect becomes a new rule or a new code). It closes once with the fix's reference (`#123`, a commit hash
  or a GitHub link). Both are audited (`core.import_run.defect_logged` with the code, `core.import_run.defect_closed` with
  the reference), never with the description. The trigger `import_defects_kept` holds the rules for every role: logged
  open against an imported run, closed whole and never opened again, its run, code, author and time fixed.
- **The description and personal data.** The service refuses one that names a personal value of the run's staged rows
  (the commit's own `RunPrivacy`), or that the kernel's log redaction finds an email address, an ID number or a phone
  number in, or that holds seven digits in a row (422 `description_names_personal_data`). And it follows the purge's one
  rule for free text: the trigger `import_defects_purged` hashes every description of the run with a salt kept nowhere in
  the purge's transaction, open or closed, and a defect logged against a run already purged is stored with its
  description hashed at once, so a purged run never holds a defect's words (review of #888: a bare name is no pattern the
  application can refuse once the staged values are gone). `ImportPurgeIT` classes the column as hashed; the removal of a closed business
  writes "Removed" (`imports_erase_personal_data`).
- **The platform report** (`GET /platform/imports/report` and `.../report.csv`, `platform.tenants.read`, on the platform
  host only) reads every business's runs through the definer function `platform_import_runs_report`, which refuses a call
  bound to a business and names each business by its slug, never its name; the CSV is recorded in the platform audit log.
- **Tests.** The golden runs of `retail_v1` (`RetailV1TemplateIT`) and `lending_loans_v1` (`LendingLoansV1TemplateIT`)
  check every figure against the run's tables queried on their own and against the commit's report (`MetricsCheck`);
  `ImportPurgeIT` proves the metrics are the same after the purge and the descriptions hashed; `ImportMetricsIT` logs and
  closes a defect with its audit rows, the refusals, another business's 404, and the platform report refused to anonymous
  calls and to a tenant's staff; `MigrationOrderIT` and `RlsIsolationIT` cover V148.

**15/16, the screens of the whole pipeline (#141, migration `V149`, ADR-027, spec sections 2, 3 and 11).**

- **In the business** (Business set-up, Bring in your data, `core.imports.manage`; chapter 7 section 7.11.9): the imports,
  newest first, each with its step; one import's seven steps in plain words (upload, check, fix and try, approval, bring in,
  compare and sign, delete the copies), its files with the sheet layout each was read with (a searchable choice of every
  source of the business's newest mapping versions; a mapping is still made by the operator, spec section 11), the check, the
  preview with the predicted balances per branch, the stored dry run, the approval request, the sign-off progress (brought in,
  compared, signed, copies deleted, from the run's stamps) and the certificate of deletion, saved as the text sent to the
  customer. The one main action is always the next step. The problems of an import are grouped by code with the plain
  message the check wrote and fixed in place (a new value, the line left out with a reason, a warning accepted with a note, a
  question for the owner), or every problem of one code the same way; the questions download as the numbered CSV. The
  sign-off itself stays the screen of #134.
- **The raw row** (`GET /imports/issues/{issue_id}/raw`, the new permission `core.imports.view_raw` of V149, the tenant
  administrator's): the cells of the issue's row as uploaded and its original value, on the fix sheet only, audited
  `core.import_row.raw_viewed` per opening with the issue, run, batch and line and never a value (spec section 6.3).
- **In the operator portal** (Imports, platform routes of chapter 7 section 7.11.3): the run queue is the platform report of
  #140 grouped by state, each run with its stage of spec section 2; the kill switch with a reason; one run's stop, resume and
  start, the trading override asked by one operator and approved by another, and the pre-import dump recorded and checked.
  Each action names the business by its slug and runs bound to it, so row-level security holds as on its own host; only a
  platform principal reaches a platform path, so the operator ids the service records are always operators' (comment on
  #141). The commit and its resume take one transaction per batch and may run for minutes, so the portal splits them
  (`ImportRuns.startCommit`, `startResume`): the business's commit loop is claimed and the guards, the move to
  `committing` (or the cleared halt) and its audit row, with the operator as actor, run in the request, whose answer is
  every refusal; only the batches go on on their own thread, which holds the loop until they end, so a second start or a
  resume while a loop is alive is refused at once (review of 1c9568b on #895). The queue follows the run's state. The `import_commit` approval is still given in the
  business's Approvals by a holder of `core.imports.approve_commit`; the portal shows whether it is given.
- **Not in this build:** recording a policy decision (spec section 3.8) and saving a mapping from a screen. A run that
  needs a decision is refused with `CREDIT_DECISION_MISSING`, whose words the screen shows; the decision and the mapping
  stay with the operator's host tools until #142.
- **Tests.** `ImportScreensIT` (the business's routes, the raw row refused without `core.imports.view_raw` and audited
  per opening without a value, another business's 404; the portal's routes refused to anonymous calls and a tenant's staff,
  a stop refused in plain words and recorded, the override approved only by a second operator, the dump recorded and
  checked, a start before the approval refused); `MigrationOrderIT` covers V149. In the frontend: the stage progress
  (`pipeline.test.ts`, `stage-progress.dom.test.tsx`), the stop button (`import-run.dom.test.tsx`) and the sign-off flow
  (`run.dom.test.tsx`).
