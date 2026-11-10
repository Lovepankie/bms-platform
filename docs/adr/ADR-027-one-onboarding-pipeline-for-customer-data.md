# ADR-027: One onboarding pipeline for customer data: quarantined staging, canonical templates with versioned mappings, resumable maker-checker commit

## Status

Proposed (2026-10-06, issue #125), accepted on merge. Builds on SDD chapter 13 (the import
framework and the lending register template), ADR-020 decision 9 (the retail history import),
ADR-004 (opening positions through the ledger), ADR-003 (tenant isolation) and ADR-015
(maker-checker). Specification: `docs/specs/customer-data-onboarding.md`. Runbook template:
`docs/runbooks/customer-data-cutover.md`.

## Context

Every tenant we sign arrives with data in a spreadsheet, a form app or both: a retail tenant now, a
lending tenant now, a second retail tenant next. Two import paths exist and differ:

- Chapter 13 designs a framework for the lending register: batches, rows, issues, a review queue,
  a preview and one commit transaction approved by a checker. It is not built yet (#110), and its
  rules are written for one sheet shape.
- The retail import was built as a one-off command, `import-retail`, over a normalised export a
  person prepares by hand. Its lessons are good (a dry run that is the real run rolled back, a
  per-file report, an anomaly list with file and line, a balances checksum, `source_ref`
  idempotency, a verified pre-import dump, a frozen source at an agreed cutover moment) but they
  live in a command and a runbook, and the export step is new work for every customer.

Doing each customer by hand does not scale and is where errors come from: a new script per sheet,
a review held in chat, real data copied to places it should not be, a run that half commits and
nobody knows how to resume. The product owner's requirement is that onboarding is seamless, robust
and error free for every customer.

## Decision

1. **One pipeline for every vertical.** All customer data enters through the `core.imports`
   framework: upload, parse, normalise, validate, review, preview and dry run, commit,
   reconciliation, sign-off, purge. The retail command becomes a wrapper over the framework once the
   retail template reproduces its golden results exactly; until then it stays the retail path.
2. **Quarantine before commit.** Uploaded files are stored encrypted with a per-run key under a
   quarantine prefix, and staging rows live only in the tenant-scoped `import_*` tables that no
   business module reads. No vertical or ledger table is written before commit.
3. **Canonical templates are code; a customer's sheet is a versioned mapping.** Each vertical owns
   a canonical template (`retail_v1`, `lending_loans_v1`, later savings and investments) with
   typed fields, rules, stable issue codes and a loader, registered with the core and tested once
   with golden fixtures. A customer's sheet shape is a mapping document, validated against the
   template, versioned append-only per tenant and recorded on each batch. A shape the template
   cannot express is a new template version decided in discovery, never a cutover-day script.
4. **The run is the recoverable unit; each batch commits in one transaction.** A run commits its
   batches in dependency order, one transaction each, after a maker requests and a different
   checker approves `import_commit`. Every created record is registered in `import_refs` by
   `source_ref` (file, tab, row and content hash) in the same transaction, so an interrupted run
   resumes from the first uncommitted batch and a re-run writes nothing twice. The verified
   pre-import dump is recorded on the run and is required to commit; restoring it is the only
   whole-run rollback, decided by the dev lead.
5. **The dry run is the commit rolled back,** in one outer transaction, and prints the same report.
6. **Guards in the product, not in the runbook only:** a refusal to commit into a tenant that
   already trades unless an operator sets an audited override approved by the dev lead; one
   committing run per tenant and an advisory lock per batch; a platform kill switch and a per-run
   halt checked between batches and every 1,000 rows; file size and row limits sized for the
   900 MB staging stack; a cutover moment after which source rows are refused.
7. **Policy decisions are recorded data.** Where a source lacks something the books need (credit
   sales without payments, loans without a disbursement record), the run carries an explicit,
   customer-confirmed decision and is not `ready` without it.
8. **Reconciliation ties before sign-off; sign-off triggers deletion.** The customer signs the
   reconciliation in the app; within 30 days the quarantined files, the plaintext export, the
   per-run key, the personal values in the staging rows and the pre-import dump are deleted and a
   certificate of deletion is issued.
9. **Every run is rehearsed** against last night's production backup restored into a throwaway
   database before cutover day.

## Consequences

**Better:** a new customer is a mapping and a checklist, reviewed in the app with an audit trail;
the lessons of the first retail import apply to every vertical; a crash, a second operator or a
changed sheet has a defined outcome; real data stays on production and is deleted on a schedule
the customer can see; metrics per run show where onboarding goes wrong.

**Worse:** the framework is a larger build than another one-off command, and the retail command and
the framework coexist until the retail template matches it. One transaction per batch means a
failed run can be partly committed; resume and the pre-import dump cover it, at the cost of an
operator decision. The rehearsal adds a step and host load before every cutover.

**Watch for:** canonical templates growing customer-specific options until they are scripts in
disguise (a new option needs a golden case and a reviewer's agreement that a second customer could
use it); the batch transaction timeout against real volumes (the big file test sets the limits);
mappings drifting from the template version a run started with; operators bypassing the app with
host-side files after self service exists.

## Note (10 November 2026, issue #141): the screens of the whole pipeline

Three choices the screens make. The raw cells of a staged row are a permission of their own, `core.imports.view_raw` (V149),
held by the tenant administrator, the role the operator runs a business's onboarding with and, in self service, the admin of
the business whose sheet it is; the row is answered one issue at a time and every opening is audited without a value, so
reading the quarantine leaves a trail per row, not per screen. The operator portal's actions on a run (stop, start, resume, the
trading override, the dump record) are platform routes that name the business by its slug and run bound to it, rather than a
second set of definer functions: the run's own service already holds every rule and the business's row-level security still
applies, and only a platform principal reaches a platform path, so the operator ids the service records are always operators'.
The commit and its resume take one transaction per batch and can outlast a request, so the portal splits them: the
business's commit loop is claimed and the guards, the move to `committing` and its audit row (the operator as actor) run in
the request, which answers every refusal, and only the batches go on on their own thread, holding the loop until they end.
So a second start, or a resume while a loop is alive, is refused at once and never undoes a stop; a batch that fails
afterwards is logged by run id and kind and leaves the run `committing` and resumable, as decision 4 already provides. The approval of the commit stays a tenant approval (decision 4); the platform operator as
checker of a first import is self service's (#142).

## Note (10 November 2026, issue #140): metrics and the defect log

The metrics of spec section 8 are computed when asked from what the run stored, never from logs, by one database function
(`import_run_metrics`) that the business's route and the platform report share, so the two never disagree. It reads only
columns the purge keeps, so a run's figures survive its purge. What was not stored before, the time each pipeline step
took, is now written where the step ends (V148). The platform report reads across businesses through one definer function
that refuses a call bound to a business and names each business by its slug only.

The defect log is the one new place an operator types free text against a run, so the one rule for typed text of the
note of 9 November covers it: `import_defects.description` joins the hashed list (hashed when the run is purged, open or
closed, and stored hashed when logged against a run already purged, so a purged run never holds a defect's words; review of
#888), and before that the application
refuses a description that names a personal value of the run's staged rows, an email address, an ID number, a phone number
or seven digits in a row. The code it should have had (`expected_code`) is only ever a code of the platform's own
issue-code catalogue, refused otherwise when the defect is logged (Lead on #888): a code no template has yet is not typed
into it; the defect says `new_code_needed` and its description, screened and hashed, proposes the code. So the code holds
nothing a business typed, stays readable after the purge and may be shown on the platform report. The fix's reference
(`fix_ref`) is held by a CHECK to an issue or pull request number, a commit hash or a GitHub link.

## Note (9 November 2026, issue #139): decision 8 as built

The deletion is a purge of the run, not of its records: the staged rows and issues stay without their values, so every
count of the run and its certificate can still be checked, and the review's resolutions keep a salted hash of each value
(the salt kept nowhere) in place of the value. The append-only guards of a committed batch, which nothing else may pass,
let the purge through only inside one definer function, for the run it purges and in its own transaction (Lead ruling on
#867); the removal of a closed business keeps its own way. The dump lives on the host, out of the application's reach: the
operator confirms its deletion by name and hash at stage 9, with a witness, and a scheduled purge's certificate says it was
not confirmed until they do, which issues the certificate's next version. The certificate is stored append-only on the run
as JSON and as the plain text the operator sends, with the text's SHA-256, as the reconciliation is (note of 7 November).

The deadline wins over the warning (Lead ruling on #886, M4): personal data is not kept past 30 days after the sign-off,
or past 90 days of inactivity, because a warning failed to go out. The operators are warned a week before each deadline;
when that warning never went (the job or the outbox was down), the purge still happens on the deadline and enqueues a
"purged without warning" notice to the operators and to the business's Owner in the same transaction as the purge, so they
hear of it. The daily job purges a signed run on day 29, so it is never late by the hours between its run and the deadline
(review of #886, S3). Every business with a run to purge is visited, a suspended one too. The references of imported records (`import_refs`) are kept unhashed:
a later run of the business matches its rows on them, and their content hash covers a whole mapped row, so it can confirm
a guess of an entire row but reveals no value.

One rule for typed text (Lead on #886): the purge hashes or clears every free-text column a person can type on the run's
tables and keeps in clear only codes, counts, hashes, statuses, ids and timestamps. Hashed or cleared:

| Table | Columns |
|---|---|
| `import_rows` | `raw` (emptied), `normalised` (cleared), `source_ref` (hashed) |
| `import_issues` | `original_value`, `suggested_value` (cleared), `message` (hashed) |
| `import_issue_resolutions` | `before_value`, `after_value`, `row_key`, `note` (hashed) |
| `import_batches` | `sheet_name`, `failure_reason` (hashed); the quarantined file and its `object_key` (deleted) |
| `import_runs` | `allow_trading_reason` and each policy decision's confirmation (hashed), `data_key_wrapped` (cleared) |
| `import_signoffs` | `signed_name` (hashed) |
| `approval_requests` (the run's `import_commit`) | `decision_note`, `execution_error` (hashed) |

The run has no notes and no halt or cancel reason (a boolean and a time, V127). The audit rows, never purged, hold no
typed text and no unsalted hash of one, which a guess of every phone number would undo (review of #886, MF3): the
override's reason and a policy's confirmation are audited only as given, with their length. Every other text column of
these tables holds codes only, and `ImportPurgeIT` names each column in one list, so a new column fails CI until it is
classed. Kept readable, each for one reason:

| Column | Why |
|---|---|
| `import_batches.source_filename`, `size_bytes`, `source_sha256`; `import_runs.pre_import_dump_file`, `pre_import_dump_sha256` | the certificate names the files and the dump it deletes |
| `import_batches.source_headers` | the sheet's column names, which the business's mapping (not of the run) holds and a later run is mapped by |
| `import_refs.source_key`, `source_ref` | the file, tab and reference code a later run matches its rows on; the mapping refuses a reference column that feeds a personal field (`SOURCE_REF_COLUMN_PERSONAL`) |
| `import_reconciliations.report` | the signed document, codes, counts and amounts only, that the sign-off's SHA-256 names |
| `import_rehearsals`, the dry run and batch reports | made by the application, codes, counts and amounts only |
| `import_deletion_certificates.document` | the certificate itself, naming the platform operator, the witness and the files |
| `approval_requests.payload` of the run's `import_commit` | the run's id, its file count and the application's notice |

## Note (8 November 2026, issue #138): decision 9 as built

The rehearsal is `deploy/restore-drill.sh --rehearse-import <run id>`, opt in by its flag; with no argument the script is
the weekly drill, unchanged. In the throwaway database the run is dry run again and committed by the same commit as any
run, which waives two guards only there: the checker's approval of `import_commit` and the verified pre-import dump, both
of which protect a live database that a rehearsal never writes and mean nothing in one dropped minutes later. The
application waives them only after finding the rehearsal's one-time token in the database, a table the script makes in
its throwaway database and no migration makes, so the live database can never take a commit without them. Staging's
environment guard is the run's `fabricated` flag, said when the run opens and fixed after (V146). The rehearsal is stored
on the live run as one append-only `import_rehearsals` row, the only write it makes there.

## Note (7 November 2026, issue #134, review of #873)

Decision 8's reconciliation is "stored as a document" (spec section 3.10) as a JSONB row with the SHA-256 of its plain
text (`import_reconciliations`, append-only under forced row-level security), not as a file in `core.documents`. The Lead
accepted this for #134. The row is the document: the plain text is rendered from it byte for byte, the sign-off names
its SHA-256, the database refuses any change to it, and the download (`reconciliation.txt`) is that same text with the
sign-off. A file in `core.documents` would be a second copy of the same text that could drift from the row, with nothing
the row does not already give. A rendered file stored at sign-off can be added later without changing what is signed.
