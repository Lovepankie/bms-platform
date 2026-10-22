# ADR-074: "Download my data" as one Excel workbook made by the report runs, and a weekly copy emailed to the Owner

## Status

Accepted (issue #334, step 1; 22 Oct 2026). Builds on ADR-003 (row-level security), ADR-038 (the report runs: queued, one
read-only snapshot, a disk spool, files kept 30 days, the streaming workbook writer), ADR-024 (the platform outbox), ADR-054
(branded HTML emails) and ADR-062 (the Owner of a business). Migration `V94`, an ordered claim (AGENTS.md): `V80` to
`V84` are merged, and `V85` and `V86` (payments), `V87` (offline), `V88` (shop logos), `V89` (referrals), `V90` (barcodes), `V91`
(existing loans) and `V93` (the self-serve plan) are claimed before it. Flyway runs with `outOfOrder` off, so it merges after
those claims, or is renumbered to the next free number if it must merge sooner. Step 2 of the issue, a copy kept in the Owner's Google Drive, is a later decision and is not built.

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
6. **A weekly copy emailed to the Owner, on by default, without personal data.** The job `core.reporting.data-export-weekly`
   runs every 15 minutes. From 02:00 on Monday in the business's time zone, while the switch is on, it queues that week's
   workbook for the Owner (ADR-062) and writes the week's row in `data_export_weekly`, unique per business and week, so a second
   tick, a second instance or a restart queues nothing more. The weekly workbook is made to be emailed, so it leaves out the
   columns of personal data (the stored run parameter `copy = weekly`; each section names its columns in `personal`): members'
   phone numbers, ID numbers, dates of birth and villages, the phone on the Arrears sheet, and the contact of customers, credit
   buyers and suppliers. Names, member numbers, amounts and every business figure stay. Those columns are only in the full
   workbook, which the signed-in administrator makes and downloads from Business set-up, and the email says so in plain words:
   "contact details and ID numbers are only in the copy you download after signing in". The card lists the caller's newest
   full workbook, never the weekly copy. **Why (data protection):** the members and customers in these columns are data
   subjects under Uganda's Data Protection and Privacy Act, 2019, and the business is accountable for keeping what identifies
   or reaches them secure and no wider than its purpose needs. An email attachment leaves every control BMS has: it sits in
   mail servers and inboxes outside the business, is forwarded, and is opened on shared phones, every week, for as long as the
   switch is on. A weekly backup the Owner keeps does not need a member's national ID or phone to serve its purpose (knowing
   who owes what), while the copy inside BMS is reached only after sign-in (with two-step sign-in where the business requires
   it), for the person it was made for, for 30 days. So the default email carries the minimum, and the full copy stays behind
   the sign-in. Once the run is ready the email goes through the outbox, keyed by the week's row so it is queued once (the week
   is then `queued`, and `emailed` only when the outbox records it sent, or `failed` with `email_failed` when the outbox gives it
   up, so the card's "last weekly email" is an email that went): the
   workbook attached when it is at most `bms.data-export.attach-max-bytes` (5 MiB), otherwise the email has no file and a link
   to Business set-up on the business's own sign-in page (decision 9). The attachment is named in the outbox row as a
   reference, never as bytes; the sender reads it through the `EmailAttachments` port of `core.notifications`, for the row's
   tenant, only while the run is ready and unexpired. A week with no active Owner who has an email address is recorded as
   skipped; a run that failed ends the week as failed with its code. Turning the switch off stops the next week; turning it on
   mid-week sends that week's.
7. **Read only, and served to a business whose subscription has ended.** Making the workbook writes only the run's own rows and
   file. A suspended tenant answers 423 to every write except two reads that are a `POST`: asking for the workbook and the signed
   address of a file; the runner's three definer functions (V49) now include suspended tenants. The nightly expiry of report
   files no longer walks the active tenants only: `app_tenants_with_expired_report_files` lists every tenant with a file past
   its 30 days, whatever its status, so a suspended or ended business's workbook is deleted on time like any other. The weekly email follows the
   platform's active tenants only: an ended business keeps "Download my data" but no longer gets the Monday email.
8. **The card.** Business set-up shows "Download my data" to holders of the permission: when the business's data was last
   exported (by anyone) and last emailed, the caller's newest full workbook (preparing, ready to save until its expiry, or why it
   failed) and the weekly switch. The welcome tour has a step for it.
9. **Too large to attach: the sign-in link, not a public link.** When the workbook is over the attachment limit, the email
   carries no file and no address of one: only the business's own Business set-up (`/staff/setup` on the tenant's host), where
   the Owner signs in (with two-step sign-in where the business requires it) and makes and saves the full copy, which is bound
   to them as the requester. An unguessable link that works without signing in was considered and refused: whoever holds the
   email (a forwarded message, a shared inbox, a phone left unlocked) would hold the whole business, personal data included,
   for as long as the link lives, and a link cannot tell the Owner from anyone they forwarded it to. Costs: the Owner signs in
   for a large copy, and the week's own emailed copy, made before its size was known, is left to expire unused.

## Consequences

**Better:** an owner can take all their data away in a form they already know, at any time, even after the subscription ends;
the weekly email means a copy exists without anyone remembering; nothing new runs outside the report framework, so the snapshot,
the 30 day expiry, the RLS and the memory bound are the ones already tested.

**Worse:** the workbook is a copy, not a backup that can be restored; a business with years of sales gets many sheets; an
attachment of up to 5 MiB goes through the mail server every week for each business with the switch on.

**Tests:** `DataExportIT` (the shop and lender sheets and columns, business time and whole shillings, formula injection, one
business never sees another's rows or file, the permission, a suspended business still downloads and changes nothing, an ended
business's file is deleted after its 30 days, the weekly
job queues once and emails once and stops when switched off, the emailed copy leaves out the personal columns that the full
copy keeps, a week with no Owner is skipped once) and `DataExportSheetsTest`
(the year split and its numbered parts, the business date and time of a moment, the personal columns left out of the emailed
copy); `DataExportLinkIT` (over the attachment limit the email is the sign-in link with no file, and the documents module
refuses to read a report object larger than asked); `EmailLayoutTest` (a missing attachment fails the send as
`attachment_unavailable`; both weekly emails say where the contact details and ID numbers are).
