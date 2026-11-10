# Customer data onboarding

**Status:** Draft · **Owner:** Hillary · **Issue:** #125 · **Decision:** ADR-027 ·
**Design:** SDD chapter 13 (section 13.14 summarises this spec) · **Runbook:**
`docs/runbooks/customer-data-cutover.md`

Every new tenant arrives with data: a retail tenant with a spreadsheet and a form app on top, a
lending tenant with a loan register in one sheet, a second retail tenant with a sheet of its own
shape. This spec turns what we learned from the first retail import (`import-retail`,
`docs/runbooks/import-retail.md`) and the lending import design (SDD chapter 13) into one process
and one pipeline that we run the same way for every customer. The goal is that onboarding a new
customer is a mapping and a checklist, never new code and never improvisation.

It holds no real data. Customers are "a retail tenant" and "a lending tenant"; every example value
is fabricated.

## 1. Principles

1. **Never lose a row, never guess silently** (chapter 13 section 13.2). Every source row is
   accounted for in the report as written, existing, skipped or excluded, with a reason and its
   file and line.
2. **Nothing touches tenant tables until commit.** Upload, parse, validate, review and preview work
   only in the quarantined staging area (section 3.1).
3. **The dry run is the real run, rolled back.** It executes the same code inside one transaction
   that is rolled back and prints the same report. A dry run that passes predicts the real run.
4. **Re-running is always safe.** Every committed row carries a `source_ref`; a re-run recognises
   what is already imported and writes nothing for it.
5. **Balances tie or we do not sign off.** The reconciliation compares the source with what the
   platform derives (counts, stock quantity and value, loan principal and arrears, journals
   balanced) and the customer signs that comparison.
6. **Money enters only through the ledger.** Opening positions are journals posted by `post_entry`
   (ADR-004); nothing writes a balance directly.
7. **Real data only on production, fabricated data everywhere else.** Staging, CI, fixtures and
   the repository never see a customer's file (AGENTS.md rule 10).
8. **A customer's sheet is a mapping, not code.** The vertical's canonical template is code and
   tested once; the customer's sheet shape is versioned data that maps onto it (section 4).

## 2. The process

Three owners. **Customer:** the tenant's owner or the person they name, who knows the data and
signs off. **Operator:** the Rincol person running this customer's onboarding (platform operator
role, ADR-016). **Platform:** the development team, represented by the dev lead, who owns the
tools, decides on restores and approves exceptions.

Each stage has entry criteria (do not start without them) and exit criteria (do not leave without
them). The cutover record (runbook section 1) holds the evidence for each exit.

| # | Stage | Owner | Entry criteria | Work | Exit criteria |
|---|---|---|---|---|---|
| 1 | **Discovery** | Operator, with customer | Tenant applied or created (ADR-024); modules chosen | Inventory every sheet, tab, form app and paper book that holds data; who edits each; how often; what is out of scope (for example the cash book tabs, pending ADR-022). The customer shares a **copy** of each source through the encrypted channel (section 6.2) | Source inventory in the cutover record: each source, its owner, its tabs, its row count, in scope or not and why. Open questions listed |
| 2 | **Field mapping** | Operator | Discovery exit | Pick the vertical's canonical template (section 4.1); write the customer's mapping (section 4.2) from their headers; run the mapping validator. Unknown columns are mapped, ignored with a reason, or raised as questions | Mapping version 1 saved and valid; every source column is mapped or explicitly ignored; the data dictionary entries it relies on exist |
| 3 | **Cleaning and review round** | Customer answers, operator runs | Mapping valid | Upload the copy to the quarantine; validate; work the review queue. Questions only the customer can answer (an unnamed item, a negative stock, a borrower without a phone, a credit sale with no payment record) go to the customer as a numbered question list, with file and line but without copying personal data into chat | Zero blocking issues; every warning resolved or overridden with a note; the **receivable decision** (section 3.8) and any other policy decisions recorded and signed by the customer |
| 4 | **Rehearsal on a copy** | Operator, platform supports | Review exit | Restore last night's production backup into a throwaway database and run the real commit against it (section 5.5) | Rehearsal report equals the preview; reconciliation ties; timings recorded and inside the cutover window |
| 5 | **Dry run** | Operator | Rehearsal exit, on the cutover day, against production | The dry run on production with the final source (section 3.6) | Report identical in counts to the rehearsal except for rows the customer added since the freeze, each explained; checksum `match=yes`; go/no-go criteria of the runbook met |
| 6 | **Cutover** | Operator commits, platform checker approves | Dry run exit; source frozen; verified pre-import dump | Commit by maker and checker (section 3.7) | Every file committed; run state `committed`; no file failed |
| 7 | **Reconciliation** | Operator prepares, customer checks | Cutover exit | Reconciliation report (section 3.9); the customer checks totals against their own figures and spot checks a sample of records in the app | Every control total ties or each difference is itemised and explained |
| 8 | **Sign-off** | Customer signs, operator records | Reconciliation exit | The customer signs the reconciliation (a typed name and date in the app, section 3.10) | Signed reconciliation stored as a document; the run is `signed_off` |
| 9 | **Source deletion** | Operator, platform witnesses | Sign-off | Delete the plaintext export, the quarantined raw files, the staging rows' personal data and the pre-import dump; issue the certificate of deletion (section 6.4) | Certificate stored and sent to the customer |
| 10 | **Hypercare** | Operator, platform on call | Sign-off | Two weeks of close support: daily check of the tenant's first trading days, defects logged against the run (section 8), the first stock-take for negative balances planned | Defect log closed or handed to normal support; metrics recorded; lessons fed into the template or this spec |

A stage can loop back: a review answer that changes the mapping returns to stage 2 with a new
mapping version; a rehearsal that does not tie returns to stage 3. Looping back is normal; skipping
a stage is not allowed without the dev lead's written exception in the cutover record.

The screens (#141) show these ten stages to the operator (the portal's run queue gives each run its stage, from the run's
stamps, `import_run_metrics`) and seven plain steps to the business, from the run's state: upload your files (`draft`),
check the files (`uploaded`), fix the problems and try it (`in_review`), a second person approves (`ready`), bring the data
in (`committing`), compare and sign (`committed`), delete the copies (`signed_off`); `purged` has every step done. Each
step says done, now or later in words, so the state never rests on colour.

## 3. The technical pipeline

This generalises the import framework of chapter 13 (`core.imports`) so that the lending register,
the retail export and every later template run through one pipeline. The `import-retail` command
stays as it is until the retail template on the framework passes its golden test with identical
results (section 9, task 9); then the command becomes a thin wrapper that runs the framework.

### 3.1 Upload into quarantine

An **import run** (`import_runs`, one per onboarding attempt of a tenant) owns one or more
**batches** (`import_batches`, one per source file or tab), as in chapter 13 section 13.4.

- The file is streamed to object storage under a quarantine prefix, encrypted with a per-run data
  key (section 6.2), with its SHA-256, size, uploader and time. Nothing is parsed in memory as a
  whole.
- Staging rows live only in the `import_*` tables, which are tenant-owned with forced RLS
  (ADR-003) but are **not** read by any business module. No vertical table, no ledger table and no
  audit row other than `imports.*` is written before commit.
- Accepted formats: CSV (UTF-8, or Windows-1252 converted with a warning), XLSX (formatted values,
  as data dictionary section 3 learned), JSON Lines (the retail export format). Anything else is
  refused with `FILE_TYPE_UNSUPPORTED`.
- Limits, sized for the staging stack's 900 MB cap (ADR-018) and the same on production: at most
  25 MB per file, 100,000 rows per batch and 200 columns (2,500 rows per batch until #880: the
  size the big file test of #137 measured as safe on the staging heap); XLSX is read with a streaming reader;
  the API never holds more than one chunk of 1,000 rows in memory. A file over a limit is refused
  at upload with `FILE_TOO_LARGE` or `TOO_MANY_ROWS` and the operator splits it. These limits are
  configuration, raised only after the big file test (section 5.3) passes at the new size.

### 3.2 Parse with strict typed schemas

The canonical template declares each target field with a type (`text(n)`, `money`, `qty`,
`date`, `datetime`, `phone`, `nin`, `enum(...)`, `bool`, `ref(entity)`), whether it is required,
and its normaliser. The mapping (section 4.2) says which source column feeds which field. Parsing
reads each row's raw cells with their types into `import_rows.raw` (never modified afterwards) and
produces typed values or an issue. A whole-file problem (unreadable, header not found, a required
column missing from the mapping) fails the batch with a reason and creates no rows.

### 3.3 Normalise

Normalisers are the shared functions the forms use (chapter 13 section 13.6, and
`RetailCatalogue.normaliseCode` for codes), so a value is normalised identically whether typed or
imported. Each change a normaliser makes is an `info` issue with the original value, never a
silent change.

### 3.4 Validate each row

Each row is validated against the template: types, required fields, ranges, references to other
batches of the same run (a sale's product code must exist in the products batch), duplicates
(same `source_ref`, same product code ignoring case, same NIN) and cross-field rules (chapter 13
section 13.6 for lending; quantity and price rules for retail).

Every issue has:

- a **stable code** from one catalogue per template (chapter 13 section 13.7 for lending; section
  3.4.1 below for retail and the shared codes). A code is never renamed or reused; a retired code
  stays in the catalogue marked retired;
- a **severity**: `info`, `warning`, `blocking` (chapter 13 semantics);
- a **plain-language message** written for the customer, built from a template with the field
  name and the file and line, never with the value of a personal field (a name, phone, NIN or
  contact). The value is shown only in the review queue screen to someone with permission to see
  the raw data (section 6.3).

#### 3.4.1 Shared and retail codes (first set)

| Code | Severity | Message template |
|---|---|---|
| `FILE_TYPE_UNSUPPORTED` | batch fails | The file is not CSV, XLSX or JSON Lines. |
| `FILE_TOO_LARGE`, `TOO_MANY_ROWS` | batch fails | The file is over the limit of {limit}; split it and upload the parts. |
| `HEADER_NOT_FOUND` | batch fails | No row matches the mapping's headers in the first 20 rows. |
| `TOO_MANY_COLUMNS` | batch fails | The file is over the limit of {limit} columns; remove the columns not needed. (#128) |
| `FILE_UNREADABLE` | batch fails | The file is damaged at {row}: a quote never closed, a cell over 32,767 characters, a broken workbook, a sheet name over 100 characters, a value the database cannot store. (#128) |
| `FILE_ALREADY_UPLOADED` | upload refused | This import already holds this file. (#128) |
| `UPLOAD_DISK_LOW` | upload refused | The server has too little free disk for this file now; try again later. (#128) |
| `COLUMN_UNMAPPED` | warning | Column {column} is not in the mapping; map it or mark it ignored. |
| `REQUIRED_MISSING` | blocking | {field} is empty on {file} line {line}. |
| `TYPE_INVALID` | blocking | {field} on {file} line {line} is not a valid {type}. |
| `MONEY_FRACTION` | blocking | {field} on {file} line {line} has a fraction; amounts are whole shillings. |
| `QTY_PRECISION` | blocking | {field} on {file} line {line} has more than three decimal places. |
| `DUPLICATE_SOURCE_REF` | blocking | Line {line} of {file} repeats the reference of line {other}. |
| `REF_UNKNOWN` | blocking | {field} on {file} line {line} names a record of {entity} that is not in the import or the tenant. |
| `CODE_DUPLICATE` | warning | {field} on {file} line {line} is the same as line {other} ignoring case and spaces; they become one record of {entity}. (shared since #130) |
| `CODE_CONTROL_CHAR` | blocking | {field} on {file} line {line} has a hidden character inside it. (shared since #130) |
| `NAME_MISSING` | blocking | The item on line {line} has no name or description. |
| `NEGATIVE_BALANCE` | warning | The source shows a negative quantity for this item at {branch}; it is imported as it is and listed for the first stock-take. |
| `PRICE_DIFFERS_EXISTING` | info | The item already exists with other prices; its prices are left unchanged. |
| `CREDIT_SALE_UNPAID` | info | Credit sale imported without payment history; the receivable decision applies. |
| `CREDIT_DECISION_MISSING` | blocking (run) | Credit sales exist and no receivable decision is recorded. |
| `DATE_AFTER_CUTOVER` | blocking | {field} on {file} line {line} is after the agreed cutover moment. |
| `TENANT_TRADING` | run refused | The tenant already has live transactions; an import needs the trading override. |
| `IMPORT_IN_PROGRESS` | request refused | Another import is being committed for this business; wait for it or stop it. |
| `ENCODING_CONVERTED` | warning | The file was not UTF-8 and was read as Windows-1252; check accented names in the preview. |
| `ROW_CHANGED_SINCE_REVIEW` | blocking | Line {line} changed since it was reviewed; review it again. |
| `CHANGED_SINCE_IMPORT` | blocking | Line {line} was already imported with other content; correct it in the app, not by import. |
| `TEMPLATE_UNKNOWN` | mapping refused | No template {template} is registered on this server. (#129) |
| `MAPPING_INVALID` | mapping refused | The mapping does not match the template's schema at {pointer}: {reason} (#129) |
| `REQUIRED_FIELD_UNMAPPED` | blocking | {field} of {entity} is required and no column of source {source} feeds it. (#129) |
| `FIELD_MAPPED_TWICE` | blocking | Columns {column} and {other} of source {source} both feed {field}; keep one and ignore the other. (#129) |
| `COLUMN_DUPLICATE` | blocking | Column {column} of source {source} is written twice in the mapping, ignoring case and spaces. (#129) |
| `MAP_FIELD_UNMAPPED` | blocking | Source {source} maps values of {field}, which no column of it feeds. (#129) |
| `SOURCE_REF_COLUMN_UNKNOWN` | blocking | The reference column {column} of source {source} is not a column the mapping keeps. (#129) |
| `SOURCE_REF_COLUMN_PERSONAL` | blocking | The reference column {column} of source {source} feeds {field}, which is personal data; choose a business code such as an account or item number, or the row hash. (#139: the reference stays in clear in `import_refs` after the purge) |
| `ZONE_UNKNOWN` | blocking | The time zone {zone} is not a known zone. (#129) |
| `SOURCE_UNKNOWN` | request refused | The mapping has no source {source}. (#129) |
| `BATCH_HAS_NO_MAPPING` | request refused | The file was read without a mapping; read it again with one before checking its columns. (#129) |
| `REQUIRED_COLUMN_MISSING` | blocking | Column {column}, which feeds the required {field}, is not in the file of source {source}. (#129) |
| `VALUE_NORMALISED` | info | {field} on {file} line {line} was tidied by the rule {normaliser}; the original is kept with this note. (#130) |
| `TEXT_TOO_LONG` | blocking | {field} on {file} line {line} is longer than {limit} characters. (#130) |
| `VALUE_OUT_OF_RANGE` | blocking | {field} on {file} line {line} is outside the allowed range: {range}. (#130) |
| `BLANK_ROW` | info | Line {line} of {file} is blank; it is left out. (#130; the code of chapter 13 section 13.5, now shared) |
| `NIN_DUPLICATE` | warning | {field} on {file} line {line} is the same national ID number as line {other}; check whether they are one person. (#130) |

The shared codes of this table are `ImportTemplates.SHARED_CODES` in `core.imports` (#129, #130); the retail
codes (`NAME_MISSING`, `NEGATIVE_BALANCE`, `PRICE_DIFFERS_EXISTING`, `CREDIT_SALE_UNPAID`, `CREDIT_DECISION_MISSING`)
are listed by `retail_v1` itself (#135). `CODE_DUPLICATE` and `CODE_CONTROL_CHAR` were planned as retail codes; the
validation engine of #130 finds them for the key field of any template, so they are shared, with the same names and
meanings. A message may hold only the placeholders `{field}`, `{file}`, `{line}`, `{other}`, `{row}`, `{column}`,
`{source}`, `{entity}`, `{template}`, `{type}`, `{limit}`, `{range}`, `{normaliser}`, `{pointer}`, `{reason}`, `{zone}`
and `{branch}` (`ImportTemplates.MESSAGE_PLACEHOLDERS`), and never the name of a field the template marks personal; the
registry refuses a template that breaks this at start-up. Every code ever registered is pinned in
`fixtures/imports/issue-codes.txt`: a test fails when one is removed, renamed, given another severity or loses its retired
mark (SDD chapter 13 section 13.14.1). A mapping is valid
only with no problem at all, so a column the mapping neither maps nor ignores (`COLUMN_UNMAPPED`, one per
column) makes it invalid even though the code is a warning on a row.

### 3.5 Review queue

The review queue of chapter 13 (FR-IMP-05), for every template. An operator can, per issue or in
bulk per code:

- **fix** a value (`edit_value`), which re-runs the row's validation;
- **skip** a row (`exclude_row`) with a reason; the row stays in the batch and in the report;
- **map** a value (`map_value`): a new resolution kind that adds a value map to the mapping (for
  example "Stock take" in the supplier column means `kind = adjustment`), creates a new mapping
  version and re-validates every row it affects;
- accept a suggestion, link rows, override a warning with a note (chapter 13 semantics).

Every resolution is an append-only `import_issue_resolutions` row with who, when, before, after
and note, and is listed in the reconciliation. Questions for the customer are a filter of the
queue (issues tagged `ask_customer`), exported as a numbered list without personal values.

Built in #131 (SDD chapter 13 section 13.14.1, 5/16; routes in chapter 7 section 7.11.9): every kind above, bulk per code,
optimistic versions on issues (`version_conflict`), the resolutions carried to the identical rows of a new upload and
`ROW_CHANGED_SINCE_REVIEW` on a changed one, and the question list as JSON and CSV. `reclassify` comes with the row classes.

The screen (#141, Problems to fix): the open issues grouped by code, the groups that must be fixed first, then the biggest,
each with a plain title for its code (an unknown code reads as its words) and the message the engine wrote. Fixed in place in
a sheet over the list: a new value (`edit_value`), the line left out with a reason (`exclude_row`), a warning accepted with a
note (`override`, offered on warnings only) or a question for the owner (`ask_customer`); or every issue of one code the same
way (`bulk-resolve`: the suggestion, offered only when every issue has one, leaving out, accepting a warning or asking). The
questions download as the numbered CSV. A holder of `core.imports.view_raw` may open the line as uploaded in the same sheet;
`link_row`, `link_member` and `map_value` stay with the operator's tools for now.

### 3.6 Preview and dry run

The preview computes what commit will create: records by type, rows by outcome per batch, and the
**predicted balances**: stock quantity and value per branch, receivables, loan principal
outstanding and arrears buckets, and each opening journal with its debit and credit totals.

The dry run is the commit code (section 3.7) run inside one outer transaction that is rolled back,
exactly as `import-retail --dry-run` does today. It prints and stores the same report as a real
run: per batch `read`, `written`, `existing`, `skipped`; the anomaly list with file and line; the
opening journals; and the balances checksum. The preview is accepted only when the dry run's
predicted balances equal the preview's (a test holds this, FR-IMP-06).

Built in #132 (SDD chapter 13 sections 13.3 and 13.14.1, 6/16): the preview reads the staged rows only; the dry run is the
commit's own batch code with the commit's writer inside one outer transaction marked rollback-only, its report stored on the
run with a stamp of the run's inputs, and the run is `ready` only while that stored report still counts (no batch, mapping
version, issue or resolution changed after it, no other build deployed and no other run committed into the business), did
not fail and matched the preview's balances (every kind, per branch). The service resolves each batch's writer from its
template; no caller passes one, and the commit and its resume refuse writers other than those the dry run rehearsed. A dry
run and a commit of one business never wait on each other: whichever finds the other answers 409 at once. The commit
writes the same report, batch by batch. The writers that make a real template's dry run are the templates' loaders, wired by the
commit of #133; since #135 the retail one is `retail_v1`'s loader, which is the code of `import-retail`.

### 3.7 Commit

- **Maker and checker.** The maker requests `import_commit` (chapter 8 section 8.4); a different
  user with `core.imports.approve_commit` approves. The database refuses a checker who is the
  maker.
- **One transaction per batch, in dependency order** (for retail: branches, categories, units,
  products, suppliers, customers, purchases, sales, usage, balances; for lending the register is
  one batch that creates members, next of kin, collateral, loans, historic repayments and the
  opening journal in one transaction, chapter 13 section 13.10, and savings and investments
  batches follow it). A batch commits whole or not at all (FR-IMP-07 at batch level).
- **The run is the recoverable unit.** The run moves `ready` to `committing` to `committed`, and
  records each batch's commit with its time and counts. A run interrupted between batches
  (restart, kill switch, a failed batch) stays `committing` and resumes from the first batch not
  committed; batches already committed are recognised by their `source_ref` rows and skipped row
  by row. A run is rolled back as a whole only by restoring the pre-import dump (section 7.3), so
  the dump is part of the unit: no dump, no commit.
- **Idempotency.** Every created record is registered in `import_refs` (`run_id`, `batch_id`,
  `source_ref`, target type and id), the generalisation of `retail_import_refs`. A `source_ref` is
  derived from the source file, tab, row number and a hash of the mapped content, by one function
  in the framework, so a re-run of the same rows matches. A row whose content changed since it
  was imported gets a new hash and is reported as `CHANGED_SINCE_IMPORT` (blocking), never written
  twice (section 10.1).
- **Opening balances through the ledger.** Opening inventory, receivables, loans receivable and
  member credits post through `post_entry` against `opening_balance_equity`, one journal per
  branch per kind, each registered in `import_refs` so a re-run does not post it again.
- **No side effects.** Imported records send no SMS, start no workflows and raise no approval
  requests. Scheduled jobs pick them up from the next nightly run, as chapter 13 section 13.10
  states for loans.

Built in #133 (SDD chapter 13 section 13.14.1, 7/16; migration V143): `import_commit` registered with the approvals (the
approval marks the run approved at its version, and a change after it needs a new approval); the verified dump as a
recorded check (`pg_restore --list` entries and the SHA-256 again); the batches in the template's dependency order with a
10 minute statement timeout each; `import_refs` with `ImportRefs.sourceRef`, the one function of file, tab, row and
content hash, and `CHANGED_SINCE_IMPORT` reported by the validation; the opening journals through the checkpoint, one per
batch, branch and kind (the register batch of a lending run is one batch, so one per branch); and triggers that refuse an outbox row, an SMS delivery, a scheduled task or an approval request
inside an import batch. The preview leaves out rows already imported with the same content. A source known by position
(a till roll) is keyed by its upload's SHA-256, so two rolls of one run, or next month's, never share a line. An event an
import publishes carries an `imported` marker its listeners skip (no low-stock notice from an imported stock change), and
a batch that posts any journal but its opening journals, as the ledger shows them, fails. A resume needs the verified dump, the approval and
the build the dry run rehearsed.

### 3.8 The receivable decision

A source that records credit sales or loans without their payments (the first retail import) needs
a recorded decision before the run can be `ready`. The run's `policy` holds one of:

- **open from a customer list:** the customer gives the outstanding amount per buyer at the
  cutover moment; it is imported as one opening receivable per buyer (debit receivables, credit
  opening balance equity) and the historic credit sales stay without journals;
- **treat as settled:** the historic credit sales are imported as history only and no receivable
  is opened; the customer confirms nothing is owed or chooses to track it outside the platform;
- **defer:** no receivable now; the operator lists the unpaid credit sales in the reconciliation
  and a dated follow-up task is recorded. Allowed only with the dev lead's exception.

`CREDIT_DECISION_MISSING` blocks the run until one is recorded with the customer's confirmation.
The same mechanism carries other policy decisions a template declares (for lending: whether the
requested principal is taken as disbursed, chapter 13 section 13.8 row 15).

Built in #133: a template declares its decisions (`ImportTemplate.Policy`, `Policy.receivable` for this one) with the rows
that need it; `ImportRuns.recordPolicy` stores the option with the day and reference of the customer's confirmation, and for
`defer` the platform operator who granted the exception and the follow-up day; `ready`, the commit request and the commit
refuse a run that needs a decision it lacks (`CREDIT_DECISION_MISSING`); the loader and the forecast read the decision, so
the preview predicts the journals it gives.

### 3.9 Reconciliation

The reconciliation report is generated after commit and stored as a document. It must tie to the
source before sign-off. Per template it shows source value, system value and difference:

| Check | Retail | Lending |
|---|---|---|
| Counts | Rows per batch: read, written, existing, skipped, excluded; records created and matched per entity | Same, plus members created and matched, next of kin linked |
| Quantities | Stock quantity per branch and product: source equals derived (the balances checksum, `match=yes`) | n/a |
| Values | Stock value per branch at current cost; equals the opening inventory journal plus the value of negative balances listed separately | Principal outstanding per branch equals the opening loans receivable journal |
| Receivables | Per the receivable decision: opened total equals the customer's list | Interest outstanding in the subledger equals the source total |
| Arrears | n/a | Loans by days past due bucket as at the cutover date; source status against derived status, conflicts listed |
| Ledger | Every journal the run posted is balanced; the tenant's trial balance balances; no journal other than the opening journals was posted by the import | Same |
| Differences | Every difference is a listed row with its reason (excluded, overridden, policy) | Same |

A difference that is not itemised blocks sign-off.

Built in #134 (SDD chapter 13 sections 13.11 and 13.14.1 8/16; migration V144): the commit makes the reconciliation as it
finishes and stores it in `import_reconciliations`, append-only, versioned per run; it is made again on request until it is
signed. The source is the staged rows (every row not blank, the rows left out included), measured by the template's own
forecast: the rows written netted per product over the whole run, each row left out alone, keeping its own part; the
system is the books (the run's journals through the ledger's port, the business's ledger, and
the stock ledger and loan book through the template's `ImportBooks`), never the commit's report. Every check shows source,
system, difference, itemised and not itemised, as exact decimals with money in minor units; the items are the rows left
out or held back (`excluded`, by file and line), the written rows with an overridden warning (`overridden`, no amount) and
the receivable decision and negative stock balances (`policy`). The retail checks are proven on `retail_v1` itself since #135
(the fabricated sample reconciles and is signed); until #136 the lending checks on the test-only `fixture_shop` template.

### 3.10 Sign-off

The customer's sign-off is recorded in the app: the tenant admin opens the reconciliation, ticks
each section, and types their name and the date. The signed report is stored as a document; the run
becomes `signed_off`. Sign-off is the trigger for source deletion (section 6.4).

Built in #134: the screen Imports to sign off (`/staff/setup/imports`, the new permission `core.imports.sign_off`, the
tenant admin's alone). The admin ticks every section, types their name and today's date; the sign-off names the
reconciliation and its SHA-256 as read and is refused while any difference is not itemised, by the service and by the
database. It is stored in `import_signoffs`, append-only, one per run; the run becomes `signed_off` (no other path reaches
it) and no new reconciliation of it is stored; audited `core.import_run.signed_off` without the name. The deletion it
starts is #139.

The screens (#141): the run's screen shows the sign-off's progress to whoever runs the import (brought in, figures compared,
signed by the owner, copies deleted, each with its day from the run's stamps), and its one main action while the run is
`committed` opens the sign-off above; someone who may not sign is told the owner signs.

### 3.11 Run states

```
draft -> uploaded -> in_review -> ready -> committing -> committed -> signed_off -> purged
                \__________________\________\-> cancelled
```

`ready` needs: every batch parsed; no open blocking or warning issue; every policy decision
recorded; a dry run whose report is stored. `committing` needs: an approved `import_commit` and a
verified pre-import dump recorded on the run. `purged` means the staging rows' personal data and
the quarantined files are deleted (section 6.4); counts, hashes, resolutions and reports stay.

On the screens (#141) the one main action of a run's screen is always its next step: add a file (`draft`), check the files
(`uploaded`), fix the open blocking and warning issues, then try the import (a dry run, again while the stored one no longer
counts, failed or did not match the preview), then mark it ready (`in_review`); ask for approval (`ready`). The operator
portal then records the dump and its check, asks for and approves the trading override when the business trades, and
starts the commit once the approval and the checked dump are both on the run. The start and a resume check and move the run
in the request, with the operator named in the audit row, and only the batches go on in the background (one transaction
per batch can take minutes) while the portal follows the state; a second start, or a resume while a commit is still
writing, is refused at once. The stop button is offered while the run is
`committing` and the halt is not yet asked; once asked, Resume (while the kill switch allows commits).

## 4. Templates as versioned data

### 4.1 Canonical templates (code, one per vertical and version)

A canonical template is the vertical's import contract: its entity sets, fields, types, rules,
issue codes and loader. It is code in the vertical (registered with `core.imports` through the
registry of chapter 5 section 5.4.3, so core never depends on a vertical) and is tested once with
golden fixtures. Planned:

| Template | Vertical | Entity sets | Notes |
|---|---|---|---|
| `retail_v1` | retail | branches, categories, units, products, suppliers, customers, purchases, sales, usage, balances | The normalised export of `docs/specs/retail-pilot-data-dictionary.md` section 4 is its exact field list |
| `lending_loans_v1` | lending | members, next of kin, collateral, loans, historic repayments | Chapter 13 rules; the pilot register becomes its first mapping |
| `lending_savings_v1` | lending | savings accounts, opening balances, historic deposits | With phase 2 savings |
| `lending_investments_v1` | lending | investment holdings, opening balances | With phase 2 investments |

A template changes only by a new version (`retail_v2`); a run keeps the version it started with.

Built for retail in #135 (SDD chapter 13 section 13.14.1, 9/16): `retail_v1` is the export's ten entity sets with their fields,
the four retail codes above, the receivable decision with `treat_as_settled` and `defer` only (the export holds no outstanding
amount per buyer), its forecast and books, and the code of `import-retail` as its loader; `import-retail` is its wrapper, a
run of the framework of its own origin recording the export's mapping. The cash book files stay outside it.

Built for lending in #136 (SDD chapter 13 section 13.14.1, 10/16): `lending_loans_v1` has one entity set, `loans`, a row per
loan of a register carrying its member, next of kin, collateral and historic payment (the flat sheet of chapter 13 section
13.8; its loader creates the members, next of kin, collateral, loans and historic repayments of the planned sets), the codes
of chapter 13 section 13.7, the rules of 13.5, 13.6 and 13.9 over the run's rows (sub-header rows and placeholder patterns as
the mapping's row rules), the disbursement decision (`disbursed_principal`, `requested_as_disbursed`) as a policy of the run,
its forecast and books, and one opening journal of loans per branch, ever. The pilot register is its first mapping, as the
fabricated `fixtures/imports/lending_loans_v1.pilot_register.mapping.json`. A mapping source may give `defaults`, a constant
for a field no column feeds (the branch and loan product of a register). `lending_savings_v1` and `lending_investments_v1`
are later work, not built.

### 4.2 Mappings (data, one per customer source and version)

A mapping is a JSON document, validated against the canonical template's schema, stored per tenant
in `import_mappings` (append-only versions), and recorded on every batch that used it. It says:

- which file or tab feeds which entity set, and where the header row is;
- which source column feeds which field, or that a column is ignored and why;
- value maps (`"Stock take" -> kind adjustment`, `"CLEARED" -> settled`), unit maps and enum maps;
- the date convention (day first or month first, formatted values), the time zone and currency;
- row rules the template offers as options (sub-header rows as month context, a totals row to
  ignore at a fixed position, rows to treat as placeholders);
- how `source_ref` is formed when the source has no stable id (tab, row number and content hash
  by default).

As built (#129, SDD chapter 13 section 13.14.1): the contract is `ImportTemplate`, the registry
`ImportTemplates` (with one JSON Schema per template version) and the mappings `ImportMappings` over
`import_mappings` (V139). A mapping document has `template`, `template_version`, `date_convention`
(`day_first`, `month_first`, `iso`), `time_zone`, `currency`, `unit_maps`, and `sources` keyed by a
source key, each with `entity_set`, `file`, `sheet`, `header_row` (`"auto"` or 1 to 20), `columns`
(each to `{"field"}` or `{"ignore", "reason"}`), `value_maps`, `enum_maps`, `row_rules`
(`subheader_month`, `totals_row` first or last, `placeholder_rows`) and `source_ref` (`row_hash` or
`column`). The fabricated example is `fixtures/imports/fixture_shop_v1.mapping.json`.

A new customer with a different sheet shape is a new mapping and a review of it, not new code.
A source shape the canonical template cannot express (a new entity, a new rule) is a template
change: a new version, a golden fixture and a pull request, decided before the project starts,
not on cutover day.

Mappings hold header names and value labels, not personal data. They are not committed to the
repository (a header can identify a customer's business); the fabricated mappings used by tests
live in `fixtures/imports/`.

## 5. Error-proofing

### 5.1 Golden tests per template

Each canonical template has a fabricated fixture set and an expected report, checked in CI on
PostgreSQL as `bms_app`, the way `RetailImportIT` and chapter 13 section 13.12 do: every issue code
is produced by at least one fixture row; preview equals commit; a dry run writes nothing; another
tenant is untouched. A golden mapping per known source shape (the retail pilot export, the lending
register) runs the same fixture through the mapping path.

### 5.2 Property tests

Over generated fabricated rows (jqwik or an equivalent added to the test stack in chapter 15):

- importing twice equals importing once (same tenant state, second report all `existing`);
- a permuted row order within a batch gives the same tenant state and the same reconciliation;
- splitting a batch into two runs gives the same state as one run;
- a dry run followed by a real run gives the same report as a real run alone;
- for retail, the derived balance equals the source balance for every branch and product.

### 5.3 Malformed and big files

- **Fuzzing:** truncated files, wrong encodings, a byte order mark in the middle, CSV with
  unbalanced quotes, XLSX with merged cells, formulas, hidden sheets, dates as text and as serials,
  very long cells, control characters and right-to-left marks in codes. Every case ends in a batch
  failure with a code or row issues with codes, never an unhandled exception and never a partial
  write.
- **Big file test:** a generated file at each limit of section 3.1 is uploaded, validated, previewed
  and committed inside the staging memory cap, with the API container at its configured heap, and
  the times are recorded. The commit of a batch at the row limit must finish inside the batch
  transaction timeout (10 minutes, `SET LOCAL statement_timeout` per batch).

Built in #137 (SDD chapter 13 section 13.14.1 11/16; the suite and the big file times in chapter 15 section 15.7): the
golden report of `retail_v1` over fabricated edge cases next to the existing goldens of `lending_loans_v1` and the test
template; the five properties of 5.2 with jqwik on `retail_v1`; the fuzzed files of 5.3 at the readers and through the
upload, validation and preview; and the big file test on the staging heap, on request. The fuzzing found that a formula's
error and a formula saved without its value were read as a value and as an empty field; both are now `TYPE_INVALID`.
Two findings are open: #880 (a `retail_v1` batch at the row limit does not validate, dry run or commit within the staging
heap and the time limits) and #881 (a restock's price change is lost when the restocks are split over two runs); their
tests are disabled with those numbers.

### 5.4 Guards in the product

- **Trading tenant refusal.** A commit is refused (`TENANT_TRADING`) when the tenant has any live
  business record (a sale, a loan transaction, a journal not posted by an import) unless the run
  carries the `allow_trading_tenant` flag, which only a platform operator can set, with a reason,
  audited, and which the dev lead approves as the checker. This is how a later top-up import (a
  forgotten tab) is allowed deliberately.
- **Per-tenant import lock.** At most one run per tenant is in `committing` at a time (a partial
  unique index on `import_runs (tenant_id) WHERE status = 'committing'`), and every batch commit
  takes `pg_advisory_xact_lock` on the tenant's import key, so a second operator's commit waits or
  is refused (`IMPORT_IN_PROGRESS`), never interleaves.
- **Kill switch.** A platform setting `imports.commit_enabled` (on by default) and a per-run
  `halt_requested` flag. The commit loop checks both before each batch and every 1,000 rows inside
  one; when set, the current batch transaction rolls back and the run stays `committing`, resumable
  (section 3.7). The operator console has a "stop the import" button that sets the flag.
- **Cutover moment.** A run has `cutover_at`. A source row dated after it is `DATE_AFTER_CUTOVER`;
  the source is frozen at that moment (runbook).
- **Environment guard.** Production refuses a run whose files carry the fabricated fixture marker;
  staging refuses a run not marked `fabricated` by the uploader, and the uploader confirms it in the
  upload form (section 6.1).

### 5.5 Rehearsal on last night's backup

Before cutover day, the operator runs the rehearsal: the restore drill script (`deploy/restore-drill.sh`,
`docs/runbooks/restore-from-backup.md` section 5) gains a rehearsal mode that restores last night's
production backup into a throwaway database on the production host, starts a short-lived API
container against it, copies the run's staging rows and the encrypted source into it, and runs the
real commit. The rehearsal report, its reconciliation and its timings are stored on the run; the
throwaway database is dropped. The rehearsal proves the import against the real tenant state,
the real migrations and the real data volume, without touching production.

## 6. Security and privacy

### 6.1 Minimisation

Only fields the canonical template uses are imported; the mapping must mark every other column
ignored, and ignored columns are not stored in `import_rows.raw` (the raw cells of ignored columns
are dropped at parse). Columns that are personal data and not needed (a second ID number, a
customer's date of birth when the module does not use it) are ignored by default.

### 6.2 Encryption

- In transit: the customer shares a copy through an encrypted channel the operator provides (upload
  in the app over TLS, or an encrypted archive whose password goes by a different channel), never
  as an email attachment in clear. Operator to host is SSH or the app.
- At rest: quarantined files are encrypted with a per-run data key, itself encrypted with the
  platform's import key (environment secret, never in the repository); object storage encrypts as
  well. A plaintext export needed on the host for a command (`import-retail` today) lives only in
  `/opt/bms/import/<slug>/`, mode 700, on an encrypted volume where the host has one, and is
  deleted at stage 9. The pre-import dump is mode 600 and deleted at stage 9.

### 6.3 Who may see the raw export

| Data | Who | How |
|---|---|---|
| The customer's original file | The operator of this run and the dev lead | Download from the run, audited (`imports.raw_downloaded`) |
| Raw cell values in the review queue | Users with `core.imports.view_raw` (operator; tenant admin in self service) | Review screen only; audited per row opened |
| Issue messages, counts, reports | `core.imports.manage` | No personal values in them (section 3.4) |
| Production data in staging, CI, a laptop or the repository | Nobody | Never |

Built in #141 (migration V149): `core.imports.view_raw`, granted to the tenant administrator (the operator runs a business's
onboarding signed in to it with that role; in self service it is the business's own admin, looking at its own sheet). The
review screen's fix sheet offers "See the line as uploaded" to its holder alone: `GET /imports/issues/{issue_id}/raw` answers
the row's kept cells and the issue's original value, and every call is audited `core.import_row.raw_viewed` with the issue,
the run, the batch and the line, never a value. The download of the original file stays the host's.

### 6.4 Retention and the certificate of deletion

- The plaintext export, the quarantined files, the per-run data key and the raw and normalised
  values in `import_rows` are deleted at stage 9, at the latest 30 days after sign-off. A run never
  signed off is purged 90 days after its last activity, after a warning to the operator.
- Kept after purge: run, batch and issue records without personal values; counts; file hashes;
  resolutions (with personal values replaced by their hash); reports; the signed reconciliation.
- The **certificate of deletion** is a generated document per run: what was deleted (file names,
  sizes, SHA-256 of each file deleted, staging row counts, the dump's file name and hash), when,
  by whom, witnessed by whom, and what is retained. It is stored on the run and sent to the
  customer.

Built in #139 (migration V147, SDD chapter 13 section 13.14.1 13/16): the operator's stage 9 is the host command
`purge-import` with a second platform operator as witness and the dump's file name and SHA-256 as recorded on the run; the
daily job `core.imports.retention` purges on day 29 after the sign-off and 90 days after the last activity of a run never
signed off (activity after the warning starts the clock again; a run `committing` is never purged), in every business,
suspended ones too. The operators' chat is warned a week before; if that warning never went, the deadline still wins and
the purge tells the operators and the Owner in its own transaction (Lead ruling on #886). The staged rows and issues stay as records without values, so every count stays; the
resolutions' values, row keys and notes, the issues' messages, the batches' sheet names and failure reasons, the
override reason, the policy confirmations and the signer's name become a hash salted with a value kept nowhere (one rule:
every free-text column; ADR-027 lists them and the few kept readable for the certificate). The certificate is stored append-only
(`import_deletion_certificates`) with its text and that text's SHA-256; a scheduled purge cannot reach the host, so its
certificate says the dump is not confirmed until the operator confirms it with `purge-import`, which issues the next
version. The operator sends the printed text to the customer; since #141 the run's screen shows the newest certificate once
the run is purged (`GET /imports/runs/{run_id}/certificate`) and saves it as that text file.

### 6.5 Audit, logs and environments

- Audit rows (`imports.*`) for upload, mapping version, every resolution, dry run, commit request,
  approval, each batch committed, kill switch, sign-off, purge and raw downloads.
- No personal data in logs or error messages (AGENTS.md rule 9): log lines carry run id, batch id,
  file name, line number and issue code only. A test asserts that the report and the logs of the
  golden run contain none of the fixture's names, phones or NINs.
- No real data in the repository or in CI: fixtures are fabricated (AGENTS.md content boundary);
  mappings of real customers stay in the tenant's database.

## 7. Cutover, go/no-go and rollback

The operational detail is in `docs/runbooks/customer-data-cutover.md`. In summary:

### 7.1 Readiness checklist

Tenant created with the right modules and branches; staff accounts invited; mapping valid; review
queue empty of blocking and warning issues; policy decisions signed; rehearsal tied; customer
available on cutover day; operator and checker available; backups healthy (last drill green);
cutover moment agreed; customer knows the source freeze.

### 7.2 Cutover day timeline

Freeze the source at the agreed moment; take the final copy; upload; validate; compare with the
rehearsal; dry run; go/no-go; verified pre-import dump; commit; reconciliation; customer checks;
sign-off or rollback decision; the customer starts trading on the platform.

### 7.3 Rollback and decision points

| Decision point | Go when | Otherwise |
|---|---|---|
| After the dry run | Report matches the rehearsal; checksum `match=yes`; no blocking issue | Postpone; the source stays in use; no harm done |
| Before commit | Verified pre-import dump recorded on the run; lock acquired; tenant not trading | Do not commit |
| During commit | Batches commit in order | A batch fails: it rolls back alone; fix and resume (section 3.7), or decide to restore |
| After reconciliation | Every check ties | Restore the pre-import dump if nothing but the import wrote since it (runbook); else correct in the application with reversals |
| After the customer starts trading | n/a | No restore: corrections only through the application and the ledger |

The restore decision belongs to the dev lead, as in `docs/runbooks/import-retail.md`.

## 8. Metrics

Recorded on each run and summarised in a platform report, so each customer teaches us something:

- rows read, written, existing, skipped and excluded, per batch and per template;
- issues by code and severity, and how each was resolved (fixed, mapped, skipped, overridden);
- questions sent to the customer and days to answer;
- time per stage of section 2 (stage timestamps on the run) and time per pipeline step (upload,
  parse, validate, dry run, commit per batch);
- mapping versions per run (how often the mapping changed);
- rehearsal against cutover differences;
- defects found after cutover, logged against the run during hypercare with the code they should
  have had, so each one becomes a new rule or a new code.

Built in #140 (migration V148, SDD chapter 13 section 13.14.1 14/16, chapter 14 section 14.6): the metrics are the
database function `import_run_metrics`, computed from the run's stored data and only from columns the purge keeps, answered
at `GET /imports/runs/{run_id}/metrics`; the time of each pipeline step is stored where the step ends. The defect log is
`import_defects`: logged against an imported run with a description that names nobody and the code it should have had,
which must be a code of the template's issue-code catalogue; when none fits the defect is marked "new code needed" and
the code proposed goes in the description (Lead on #888). It is closed once with the fix's reference, both audited. The
description is hashed with the run's other free text when the run is purged, and a defect logged against a run already
purged is stored with its description hashed at once. The platform report (`GET /platform/imports/report` and its CSV)
lists every business's runs with their metrics, for platform operators only, each business by its slug and without its
own labels (mapping names, mapping sources and source file names left out).

## 9. Build plan

The build is split into tasks under #125, in this order (#127 to #142, one per line). Each
delivers its tests, docs and migrations; none uses real data.

1. #127: Import runs, run states, per-tenant lock, kill switch, trading tenant guard.
2. #128: Quarantined upload with encryption, limits and streaming parsers (CSV, XLSX, JSON Lines).
3. #129: Canonical template registry and versioned mappings with a validator.
4. #130: Validation engine, issue code catalogue and plain-language messages without personal data.
5. #131: Review queue generalised: fix, skip, map, bulk, audit trail, customer question list.
6. #132: Preview with predicted balances, and the dry run as a rolled-back commit with the same report.
7. #133: Commit: maker and checker, one transaction per batch, resumable run, `import_refs`, opening
   balances through the ledger, policy decisions.
8. #134: Reconciliation report and in-app sign-off.
9. #135: `retail_v1` canonical template on the framework; `import-retail` becomes a wrapper; identical
   golden results.
10. #136: `lending_loans_v1` and the pilot register mapping (with #110, which builds the lending rules of
    chapter 13 on this framework).
11. #137: Error-proofing suite: golden per template, property tests, fuzzing, big file test.
12. #138: Rehearsal mode of the restore drill.
13. #139: Retention, purge and the certificate of deletion.
14. #140: Metrics and the post-cutover defect log.
15. #141: Operator screens for the whole pipeline.
16. #142: Self service for tenant admins (later, section 11).

## 10. Adversarial review

Questions asked of this design before it was written down, with the answers it gives.

### 10.1 The customer's sheet changes mid-project

- **New or renamed column.** The mapping no longer matches: `COLUMN_UNMAPPED` (warning) or
  `HEADER_NOT_FOUND` (batch fails) on the next upload. The operator updates the mapping, which
  creates a new version; earlier batches keep their version and are re-validated on request.
  Nothing is guessed.
- **Rows edited after a review answer.** A re-upload is a new batch; resolutions carry over only
  where the raw row is identical (matched by `source_ref`, which includes the content hash).
  Edited rows lose their resolution and come back to the queue, flagged `ROW_CHANGED_SINCE_REVIEW`.
- **Rows edited after they were committed** (a top-up run): the content hash differs, so the row is
  `CHANGED_SINCE_IMPORT` (blocking) and is never written twice. The correction is made in the
  application.
- **The customer keeps trading in the sheet after the rehearsal.** Expected: the final copy is
  taken at the cutover moment, and the dry run on cutover day reports the new rows. Rows dated
  after the cutover moment are `DATE_AFTER_CUTOVER`. The runbook makes the freeze explicit.

### 10.2 The import is half done and the server restarts

- A batch transaction in flight is rolled back by PostgreSQL; nothing of it is visible.
- Batches committed before the restart stay committed with their `import_refs`.
- The run stays `committing`, with the lock row held by status, not by a connection. On restart
  the console shows the run as interrupted; the operator presses resume (or re-runs the command),
  which re-checks the dump, the lock and the kill switch, and continues from the first batch not
  committed. Re-processed rows of committed batches are `existing`.
- Opening journals are registered in `import_refs` in the same transaction as their posting, so a
  restart between the balances batch and the journals cannot post a journal twice or skip it.
- If the restart was the host failing during the commit, the dev lead decides between resume and
  restore with the same decision table (section 7.3).

### 10.3 Two operators run it at once

- Two dry runs at once are harmless (each rolls back) but are serialised by the advisory lock so the
  host is not loaded twice within the 900 MB cap.
- Two commits of the same run: the second request finds the run `committing` and is refused with
  `IMPORT_IN_PROGRESS`.
- Two runs for the same tenant: the partial unique index allows one `committing` run per tenant;
  the second is refused.
- Two operators resolving the same issue: resolutions are optimistic (`version` on the issue); the
  second gets `version_conflict` and reloads.
- The same person as maker and checker: refused by the database (AGENTS.md rule 4).

### 10.4 Other failure modes considered

- **Wrong tenant.** The run is created inside the tenant (host or slug) and every staging row is
  under RLS; the command prints the tenant's name and slug and the operator confirms it before the
  commit. The rehearsal uses the same tenant id.
- **Clock and time zone.** Dates are read in the tenant's zone with formatted values; the cutover
  moment is stored as an instant; the business date of opening journals is the cutover date.
- **Backup missing or corrupt.** No verified dump recorded on the run, no commit (section 3.11).
- **Disk full on the host.** Upload is refused before writing when free space is under twice the
  file size; the pre-import dump is checked with `pg_restore --list` (runbook).
- **A template bug found on cutover day.** Stop at the go/no-go; never patch on the day. Fix in a
  pull request with a golden case, deploy, rehearse again.

## 11. Self service later

What a tenant admin could do alone once the operator path is proven with three customers:

| Action | Tenant admin | Operator only |
|---|---|---|
| Upload a file, choose a published mapping | Yes | |
| Validate, read issues, fix values, skip rows, answer questions | Yes | |
| Create or edit a mapping | No: request a mapping from the operator | Yes |
| Dry run and preview | Yes | |
| Request activation (commit) | Yes, as the maker | |
| Approve the commit | No | Yes, a platform operator is the checker for a first import |
| Trading tenant override, kill switch, resume, restore decision | No | Yes (restore: dev lead) |
| Sign off the reconciliation | Yes | |
| Download the raw file | No | Yes, audited |

Permissions to add (chapter 8): `core.imports.view_raw`, `core.imports.request_commit` for tenant
admins, and platform permissions `platform.imports.approve_commit`, `platform.imports.override`.
UI: an import area in the tenant's settings (upload, issue list grouped by code with plain
messages, fix in place, progress through the stages of section 2, sign-off), and a run queue in
the operator portal (ADR-024) with the approve, stop and resume actions.

Built for the operator path in #141 (chapter 13 section 13.14.1 15/16): the import area (Business set-up, Bring in your data,
`core.imports.manage`) and the run queue (operator portal, Imports). As built, the approval of the commit stays a tenant
approval (`core.imports.approve_commit` in the business's Approvals); the portal shows whether it is given and starts the
commit once it is. The upload chooses one source of the business's newest mapping versions in a searchable list; making or
editing a mapping and recording a policy decision stay operator work on the host until #142, so the permissions of the table
above (`core.imports.request_commit`, `platform.imports.approve_commit`, `platform.imports.override`) come with self service.

## Appendix A. What we do for the next retail customer

Reusable from the first retail import, as it is today:

- The normalised export format (`docs/specs/retail-pilot-data-dictionary.md` section 4) and the
  `import-retail` command, with its dry run, per-file report, anomaly list, balances checksum,
  `source_ref` idempotency, prices of existing products left unchanged, legacy balance per branch
  and product, and one opening journal per branch.
- The runbook `docs/runbooks/import-retail.md`: host layout, the `docker compose run` invocation
  with the memory settings, the verified pre-import dump, "If the real run was wrong".
- The data dictionary's known issues: formatted dates, codes differing by case or hidden spaces,
  stock-takes and returns hidden in the restock log, shop-to-shop moves as signed adjustments,
  hard-coded totals rows, negative balances for the first stock-take, unused branches.
- The fabricated fixture `fixtures/retail/import-sample/` and `RetailImportIT` as the regression
  guard.
- The cutover runbook template `docs/runbooks/customer-data-cutover.md` and the question list.

What is new for the next retail customer:

1. Discovery of their sheet; a mapping from their columns to the export format (by hand with the
   export script until task 9 lands, then as an `import_mappings` document).
2. Their receivable decision (section 3.8) before the dry run, signed.
3. A rehearsal on last night's backup (by hand with the scratch database steps of the import-retail
   runbook until task 12 lands).
4. Anything their sheet holds that `retail_v1` cannot express is a template change decided in
   discovery, with a fabricated golden case, not on cutover day.
