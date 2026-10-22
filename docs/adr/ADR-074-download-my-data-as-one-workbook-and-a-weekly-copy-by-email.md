# ADR-074: "Download my data" as one Excel workbook made by the report runs, and a weekly copy emailed to the Owner

## Status

Accepted (issue #334, step 1; 22 Oct 2026). Builds on ADR-003 (row-level security), ADR-038 (the report runs: queued, one
read-only snapshot, a disk spool, files kept 30 days, the streaming workbook writer), ADR-024 (the platform outbox), ADR-054
(branded HTML emails) and ADR-062 (the Owner of a business). Migration `V94`: `V80` to `V84` are on main and `V85` to `V93` are
claimed by open pull requests; Flyway runs with `outOfOrder` off, so the number is renumbered at merge if it is no longer above
every merged one. Step 2 of the issue, a copy kept in the Owner's Google Drive, is a later decision and is not built.

## Context

Owners who came from the old AppSheet spreadsheet want a copy of their data the way that spreadsheet kept it: a sheet per table,
plain column names, opened in Excel without help. They want it on demand and without having to remember, and they want it most
when they are thinking of leaving, which includes a business whose subscription has ended (read only, FR-TEN-06). The copy must
never change data, never show one business another's rows, and never hold a large file in the API's memory (the API runs on a
224 MB heap).

## Decision

1. **One report every business has, `core.data_export`.** It is an ordinary report run (ADR-038): queued, made by the runner in
   one read-only `REPEATABLE READ` snapshot into the spool, stored as a `report` document, downloadable through the signed URL
   for 30 days, then deleted. It differs from the other reports in three ways: it is offered whatever the tenant's
   `reports_enabled` switch says (`ReportDefinition.everyBusiness`), it has no parameters and no branch scope (it is the whole
   business, every branch), and it has its own routes under `/api/v1/data-export` so the card works with reports off.
2. **Only the administrators may ask for it.** One permission, `core.data_export.run`, held by `tenant_admin` (the Owner is an
   administrator, ADR-062) and nobody else: the workbook is every branch's sales, credit and cash. The same permission turns the
   weekly email on or off (audited, `core.data_export.settings_changed`). A file made for one administrator is fetched only by
   them, like any run.
3. **A sheet per table, laid out like the old spreadsheet.** The core writes the Branches sheet; each vertical exposes a
   `DataExportSection` bean (the core never names a vertical, chapter 5 section 5.4.3) that writes its tables when the business
   has its module on. Retail: Products, Suppliers, Customers, Credit buyers (with what each owes), Stock balances, Sales, Sale
   lines, Sale payments, Account payments, Restocks, Goods receipts and the Cash book. Lending: Members, Loans, Repayments (with
   their reversals) and Arrears. Columns have plain names; a date is a real date and a moment is written as the business's own
   date and time (`HH:mm`) in its time zone, never UTC; money is a numeric cell in whole currency units (integer minor units,
   whole shillings for UGX) with the currency in the heading; a quantity keeps its fraction (`QUANTITY` column kind, exact).
   Text that begins like a formula is written as a string cell with the quote prefix (FR-RPT-07).
4. **Large histories are split by year.** A dated table longer than `bms.data-export.split-rows` (10,000) gets a sheet per year
   (`Sales 2025`, `Sales 2026`), and any sheet that reaches the split continues in a numbered part (`Sales 2025 (2)`), so no sheet
   passes the run's row cap; the split is never set above that cap.
5. **The existing streaming writer, not Apache POI's SXSSF.** The issue asked for a streaming writer such as SXSSF to keep memory
   low. ADR-038 decision 6 already chose a minimal streaming `.xlsx` writer over POI (about 20 MB of libraries on a 224 MB heap),
   which writes each sheet row by row from the spool with one row and one 64 KiB buffer in memory. The data export reuses it, so
   memory does not grow with the business and no library is added.
6. **A weekly copy emailed to the Owner, on by default.** The job `core.reporting.data-export-weekly` runs every 15 minutes. From
   02:00 on Monday in the business's time zone, while the switch is on, it queues that week's workbook for the Owner (ADR-062)
   and writes the week's row in `data_export_weekly`, unique per business and week, so a second tick, a second instance or a
   restart queues nothing more. Once the run is ready the email goes through the outbox, keyed by the week's row so it is queued
   once: the workbook attached when it is at most `bms.data-export.attach-max-bytes` (5 MiB), otherwise a link to Business set-up,
   where the signed-in Owner downloads it until it expires. The attachment is named in the outbox row as a reference, never as
   bytes; the sender reads it through the `EmailAttachments` port of `core.notifications`, for the row's tenant, only while the
   run is ready and unexpired. A week with no active Owner who has an email address is recorded as skipped; a run that failed
   ends the week as failed with its code. Turning the switch off stops the next week; turning it on mid-week sends that week's.
7. **Read only, and served to a business whose subscription has ended.** Making the workbook writes only the run's own rows and
   file. A suspended tenant answers 423 to every write except two reads that are a `POST`: asking for the workbook and the signed
   address of a file; the runner's three definer functions (V49) now include suspended tenants. The nightly expiry of report
   files no longer walks the active tenants only: `app_tenants_with_expired_report_files` lists every tenant with a file past
   its 30 days, whatever its status, so a suspended or ended business's workbook is deleted on time like any other. The weekly email follows the
   platform's active tenants only: an ended business keeps "Download my data" but no longer gets the Monday email.
8. **The card.** Business set-up shows "Download my data" to holders of the permission: when the business's data was last
   exported (by anyone) and last emailed, the caller's newest workbook (preparing, ready to save until its expiry, or why it
   failed) and the weekly switch. The welcome tour has a step for it.

## Consequences

**Better:** an owner can take all their data away in a form they already know, at any time, even after the subscription ends;
the weekly email means a copy exists without anyone remembering; nothing new runs outside the report framework, so the snapshot,
the 30 day expiry, the RLS and the memory bound are the ones already tested.

**Worse:** the workbook is a copy, not a backup that can be restored; a business with years of sales gets many sheets; an
attachment of up to 5 MiB goes through the mail server every week for each business with the switch on.

**Tests:** `DataExportIT` (the shop and lender sheets and columns, business time and whole shillings, formula injection, one
business never sees another's rows or file, the permission, a suspended business still downloads and changes nothing, an ended
business's file is deleted after its 30 days, the weekly
job queues once and emails once and stops when switched off, a week with no Owner is skipped once) and `DataExportSheetsTest`
(the year split and its numbered parts, the business date and time of a moment).
