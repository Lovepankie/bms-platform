# ADR-038: The reports pack: asynchronous runs over one consistent snapshot, PDF through the shared document templates and Excel through a streaming writer, one definition per number

## Status

Proposed (issue #210, design only; spec `docs/specs/client-extras.md` section 6; delivers increment 8,
issue #111). The run framework is built by slice D1 (issue #214, migration V49), which amends decisions 1, 3 and 7 (see
"Amendment (issue #214)" at the end); the PDF, Excel, the other six reports and the pack follow in slices D2 to D5. Builds on ADR-003 (row-level security), ADR-004 (integer money), ADR-008 (jobs), ADR-030
(insights definitions) and, for PDF rendering, ADR-034 (accepted, merged in
pull request 205). Implements FR-RPT-01 to FR-RPT-05 and
chapter 14 sections 14.3 to 14.7. Migrations are numbered when each build slice merges.

## Context

Increment 8 asks for the formal reports: portfolio, PAR, collections, disbursements, officer
performance, collateral register and the regulator summary, as PDF and Excel, run asynchronously,
for a lending client's board, auditors and regulator. Chapter 14 defines every report's formula and
chapter 6 has a `report_runs` table, but nothing says how a run executes, how the files are built,
how the numbers stay equal to the insights page, or how large reports are bounded. The PDF decision
(ADR-034) sets OpenHTMLtoPDF on PDFBox with Thymeleaf templates, stored once for receipts and
rendered per request for statements, with a quality bar the product owner wrote into pull request 205.
Reports differ from receipts: they are long tables, wide, run for minutes on a large book, and must
be reproducible.

## Decision

1. **A run is a row and a job.** `POST /report-runs` writes a `report_runs` row (`queued`) and
   returns it; the job `core.reporting.report-runner` (every 15 seconds, per tenant, `FOR UPDATE SKIP
   LOCKED`, in the style of the outbox) claims one run at a time per tenant and at most two across the
   platform, runs it, stores the file and sets `ready` or `failed` with a safe error. A request whose
   report key, parameters, format and as-of date equal a run queued or running for the same user
   returns that run, so a double click makes one.
2. **Query first, then render, so a long render never holds a snapshot.** The runner executes all
   queries of a run in one read-only `REPEATABLE READ` transaction under the tenant's row-level
   security, writes each report's rows into a compact temporary spool file on disk, and **commits**,
   releasing the snapshot; rendering then reads the spool. A pack's numbers still agree with each
   other because every row came from one snapshot. The run records its `as_of` business date and
   snapshot time. A past date reads `lending_loan_daily_snapshots` as chapter 14 section 14.1 says; a
   date with no snapshot is `snapshot_unavailable`, never a guess.

3. **One definition per number, through a public port.** The insights queries and `Scope` are internal
   to `lending.insights` and stay there. `lending.insights` publishes a second public read port,
   `InsightsFigures`, returning the typed results of its own queries (the same figures the page
   shows), taking a public `PortfolioScope` record (range, branch ids, officer id, product id) that
   it resolves against the caller's permissions itself. `lending.reports` uses that port for the
   portfolio, PAR, collections, disbursements and officer figures, so the module boundary test passes
   and there is no second implementation of PAR or collected. Only the reports the page does not
   compute (collateral register, regulator summary, the arrears list layout) have queries in
   `lending.reports`. A cross check test asserts the pack's PAR30, collection rate and disbursed
   equal the insights values for the same range (chapter 14 section 14.9), and each report keeps the
   hand-computed golden dataset of FR-RPT-05.

4. **The pack is a bundle.** A "monthly pack" run takes a period end and branches and produces one
   combined PDF (cover with parameters and sign-off block, contents, then the seven reports) and one
   Excel workbook (a sheet per report plus a Parameters sheet), all from the same snapshot. Each
   report can also be run alone. Loan officers see only their own loans in collections and officer
   reports (chapter 8 section 8.3.2); the pack needs the union of the report permissions and is
   refused otherwise.
5. **PDF through the shared document templates.** Reports use the same header and footer, brand
   colour, fonts, amounts layout, signature blocks, numbering and page "x of y" as the other
   documents, and the eight points of the product owner's quality bar, including the PNG visual check
   in CI or a script and a reviewed sample sheet before merging. Report specifics: landscape for
   tables of more than seven columns; the table header repeats on each page; totals rows set off; a
   parameters box printed on every report (FR-RPT-02); a "generated at, by, as at" line. **Memory is
   bounded:** each report is rendered to its own PDF file, one at a time, and the pack is assembled
   by merging those files on disk with PDFBox using temporary-file storage, so no more than one
   report's page tree is in memory. Tables are capped at 3,000 rows in the PDF in production and 500
   on staging, with a printed note "full detail in the Excel file". A report file is stored once per
   run in object storage under the tenant prefix, recorded as a `document` of type `report` (the
   table is write-once, so the row stays as metadata) and downloaded through the signed URL path
   (FR-DOC-03).

6. **Excel through a streaming writer.** The workbook is written with Apache POI's streaming
   workbook (SXSSF) at most 100 rows in memory, one sheet per report, money as numeric cells in whole
   currency units with the number format, basis points shown as percentages with one decimal, dates as
   dates, frozen header row, column widths set, a Parameters sheet. Totals are written as values
   computed by the same code as the PDF (not spreadsheet formulas), so the two files cannot differ. A
   text cell that begins with `=`, `+`, `-` or `@` is written as an explicit string, so a member's
   name cannot become a formula. Row cap 200,000 per sheet, refused above it with `report_too_large`
   and a suggestion to narrow the branches or dates.
7. **Bounded and observable, sized for the smallest host.** Staging is a Raspberry Pi whose API has
   about 224 MB of heap (a 448 MB container limit with half given to the heap). The runner therefore
   takes `BMS_REPORTS_MAX_CONCURRENT_RUNS` (1 on staging, 2 elsewhere), `BMS_REPORTS_PDF_ROW_CAP` (500
   on staging, 3,000 elsewhere) and an Excel sheet cap (20,000 on staging, 200,000 elsewhere,
   `report_too_large` above). A memory test runs the pack on the fabricated demo book under the
   staging heap setting and must pass before the first report slice merges. A run times out after 10
   minutes, fails safely and releases its spool; a failed run keeps its error class, not data; the
   file object is deleted after 30 days by a nightly job (the `documents` row stays; a download then
   answers 410 `report_expired`, because reports hold member names and balances and are not kept
   longer than needed) and a run can be regenerated for the same as-of date (the result is equal for
   snapshot dates and for the live date only if nothing changed). The requester is told by an in-app
   notice and, if configured, email when a run is ready (FR-RPT-04). Every run, download and failure
   is audited with the report key, the parameters and the row count, never the rows.

8. **The regulator summary is a data summary, not a filed return.** Its format is an open question
   for the client (scope question 5); the PDF and Excel say so on the cover until the return formats
   are known and added as separate templates.

## Consequences

**Better:**

- The same number is computed once; the insights page, the PDF and the workbook cannot disagree.
- Large books do not block requests; the runner is bounded and idempotent.
- The board and the regulator get a consistent set from one instant, with the parameters printed.
- The Excel is real numbers, ready for the auditor's own checks, with no formula injection route.

**Worse:**

- The snapshot transaction now lasts only as long as the queries; the spool costs disk and a second
  pass, which is the price of not holding a snapshot (and the heap) during rendering.
- The 3,000 row PDF cap means a very large collateral register is complete only in Excel.
- Two renderers (PDF and Excel) per report double the layout work for each new report.
- Combined pack PDFs can be large; storage is bounded by the 30 day retention.

**Watch for:**

- `InsightsFigures` is the contract: a report added with its own SQL for a figure the page already
  computes breaks decision 3; the cross check test and review catch it.
- The document templates of ADR-034 are built by #120 (pull request 209); the first report PDF slice
  starts after they merge.
- Report durations at 20 times the demo seed are measured in the first slice (NFR-PERF-01 to
  NFR-PERF-05) and the caps tuned from the measurement, not from this record.

## Amendment (issue #214, slice D1: the run framework)

Building the framework settled five things the first text left open. None reverses a decision; each completes one.

1. **The scope is frozen when the run is queued.** A run executes in a job, where no user is signed in, so the branches and the
   officer a report may read cannot be resolved there. They are resolved in the request, by the code that already resolves them
   for the insights page, and stored on the run (`report_runs.scope`) with the business date of the request (`PortfolioScope.today`, so a run asked for at 23:59 and executed after midnight
   still reads the loan tables of the day it was asked for and does not fail for a snapshot that tonight's job has not written); the job reads only from
   the stored scope and never holds a principal, so it can neither widen nor lose what the requester could see. Decision 3 becomes a two step port:
   `InsightsFigures.scope(...)` runs in the request, with the principal, and returns a plain `PortfolioScope`; `InsightsFigures.par(...)`
   (and, with slice D2, the other figures) runs in the run's transaction from that value alone. A caller without
   `lending.insights.read` is refused at the first step. The requester's deactivation between queuing and running ends the run
   (`requester_deactivated`).
2. **The port starts with what the first report needs.** Slice D1 builds `InsightsFigures` with the PAR and ageing figures only;
   slice D2 adds the rest to the same port. The cross check test of decision 3 already compares the report's PAR figures with
   the insights metrics.
3. **A tenant switch and a run status for cancelling.** Report runs are off by default behind the tenant setting
   `reports_enabled` (`core.settings.manage`): off, the catalogue is empty and every run route answers 404 `reports_not_enabled`, and
   a run queued before the switch went off ends `reports_disabled`. A run may be `cancelled` by its requester: at once when
   queued, at the next checkpoint when running.
4. **The caps and the clock.** `BMS_REPORTS_XLSX_ROW_CAP` is the row cap of every tabular output, not only an Excel sheet: the JSON
   table and the CSV section obey it, and the PDF cap (`BMS_REPORTS_PDF_ROW_CAP`) is read by slice D2. A file is also capped in
   bytes (`BMS_REPORTS_MAX_FILE_BYTES`; 64 MiB, 8 MiB on staging), because a stored file is held in memory once to be written to
   storage. A run records the caps it was queued under and applies the smaller of those and the limits in force when it runs. The
   query transaction lifts the connection's 60 second statement and idle-in-transaction limits to the run's remaining time (with a
   margin, so the application's own deadline fires first and the run ends with `timeout`); every timestamp is written from the
   business clock so a test can pin it. Concurrency is counted in the database (a read-only definer function), so the limit holds
   across API instances, and a run left `running` by a process that died is failed as `interrupted`: every run is stamped with its process and beaten every 10
   seconds, a run with no beat for a minute is failed by the next runner tick (two beats on the first tick of a new process, since whatever was
   beating before it started is dead; who owned the run does not matter, a recreated container has a new host name), and a clean stop
   interrupts the workers, gives them 15 seconds inside the containers' 30 second stop grace period, and fails and audits what is left, so
   a deploy never blocks the one place of the staging host for the length of a timeout. The figures of the port are totalled as the loans arrive in blocks (memory
   by group, not by loan) and call a checkpoint every 1,000 loans, so a cancel or a timeout does not wait for the first row. The repeat
   check precedes the rate limit, and the limit is counted under a lock per requester.
5. **Files, access and expiry.** The file of a run is a `documents` row of type `report` whose subject is the run; only the
   requester may fetch it, judged when it is asked for: reports must still be on, they must still hold the report's permission, and
   their branches and officers must still cover the frozen scope (`InsightsFigures.covers`); every refusal is 404, not 403, so a run is
   not even known to anyone else (an auditor may read the status, not the file). The output is uploaded from the file on disk, never held
   in memory (`Documents.putFile(Path, ...)`, `RequestBody.fromFile`). The
   nightly expiry deletes the object through a narrow `Documents` method that refuses any other document type, and marks the run;
   the row stays and a download answers 410 `report_expired`. The in-app notice of decision 7 is the screen's own refresh of the
   run list while any run is open; e-mail notice is left to the report centre (slice D5).

**Better:** a report run cannot see more than its requester could, whatever happens between the request and the run; the framework
is proved on a JSON and CSV report before any PDF work; the staging host is protected by caps that were tested at their edge and by
`ReportMemoryIT` on a 224 MB heap.
**Worse:** the stored scope is a snapshot of the requester's permissions: a permission withdrawn after queuing is honoured at the
file download but not by the query already queued. The whole output file is held in memory once to be stored, which the byte cap
bounds.
**Watch for:** a report that reads tables itself instead of the port (decision 3 still applies); a new renderer that holds a table
in memory instead of reading the spool.
