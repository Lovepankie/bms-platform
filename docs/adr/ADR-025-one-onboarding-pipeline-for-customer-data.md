# ADR-025: One onboarding pipeline for customer data: quarantined staging, canonical templates with versioned mappings, resumable maker-checker commit

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
