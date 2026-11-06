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

ADR-027 makes this framework the one onboarding pipeline for every tenant's data, with canonical
templates per vertical and versioned mappings per customer sheet (section 13.14,
`docs/specs/customer-data-onboarding.md`).

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
| `batch_id` | `uuid NOT NULL` | As built (V140, #130). |
| `row_id` | `uuid NOT NULL` | Its issues go with the row when a new parse replaces it. |
| `source_row_no`, `ordinal` | `integer NOT NULL` | The line, and the engine's order within the batch (V140). |
| `code` | `text NOT NULL` | 13.7 and the shared codes of the spec's section 3.4.1 |
| `severity` | `text NOT NULL` | [`info`, `warning`, `blocking`] |
| `field` | `text` | Target field, or NULL for row-level issues. |
| `message` | `varchar(500) NOT NULL` | Plain language from the code's template with field, file and line; never a personal value (13.7, #130). |
| `original_value` | `jsonb` | The cell text the issue concerns; shown only to a holder of `core.imports.view_raw`. |
| `suggested_value` | `jsonb` | Only when a rule in this chapter produces one; not built yet (#131 holds suggestions with the resolutions). |
| `status` | `text NOT NULL` | [`open`, `resolved`] (`info` issues are created `resolved`). |

As built (V140), `import_rows` carries `normalised` and `source_ref`, written by the validation, and not yet `row_class`,
`status`, `excluded_reason`, `linked_row_id`, `member_key` or the created ids, which come with the steps that need them.

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
  renamed, given another severity or loses its retired mark. The lending codes join that file with #136.
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
| Sales, restocks, usage and damage | Historical documents and movements, no journals, keyed by `source_ref` in `retail_import_refs`; each movement's `business_date` is the source row's sale, purchase or usage date in the tenant's zone, so valuation `as_of` and daily profit read them by business date |
| Restocks of kind `adjustment` or `return` | `adjustment` or `return` movements, not purchases, with no supplier payable |
| Restock price changes | An `import` history row where consecutive restocks of a product changed its cost or sell price |
| Balances | One `legacy_balance` movement per branch and product, equal to the source quantity less the balance the imported history left, so the derived balance equals the source exactly, dated with the import date as its business date; then one opening journal per branch, dated the same day, (debit `inventory`, credit `opening_balance_equity`) for the positive balances at the product's current cost. A balance the history already gives (a zero difference) writes nothing and counts as existing |

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
  rows per batch, 200 columns, streaming parsers.
- **Canonical templates and mappings.** A template (`retail_v1`, `lending_loans_v1`, later savings
  and investments) is code in its vertical, registered with the core. A customer's sheet is a
  versioned mapping document in `import_mappings` (append-only), validated against the template:
  columns to fields, value maps, the date convention and row rules such as the sub-header rows of
  13.5. `pilot_loan_register_v1` of this chapter becomes the `lending_loans_v1` template with the
  pilot register as its first mapping. A new resolution kind, `map_value`, adds a value map from
  the review queue and creates a new mapping version.
- **Issue codes.** Every template has a stable code catalogue (13.7 for lending; the shared and
  retail codes are in the spec, section 3.4.1). Messages are plain language and carry the field,
  file and line, never a personal value. The engine that applies them is built (#130, section
  13.14.1 4/16).
- **Dry run and commit.** The dry run is the commit in one outer transaction that is rolled back,
  with the same report as `import-retail`. Commit is maker-checker (`import_commit`), one
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
  pre-import dump within 30 days, with a certificate of deletion.
- **Rehearsal.** Each run is rehearsed against last night's production backup restored into a
  throwaway database before cutover day.

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
`import_commit` request is #133; until then the commit is called by the operator's tooling. The operator screens and the
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
  one transaction, so the limits (`TOO_MANY_ROWS` over 100,000, `TOO_MANY_COLUMNS` over 200) and a damaged file
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
  the source does not keep (`SOURCE_REF_COLUMN_UNKNOWN`), an unknown zone (`ZONE_UNKNOWN`). Against a file's headers,
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
  `CHANGED_SINCE_IMPORT` (#133) are pending the steps that hold a row's reviewed and imported content.
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
