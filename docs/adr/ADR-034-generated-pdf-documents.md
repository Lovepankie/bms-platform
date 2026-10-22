# ADR-034: Generated PDF documents: XHTML templates rendered with OpenHTMLtoPDF, on demand, stored once per version

## Status

Accepted (2026-10-11, product owner decision on #205; issue #120, the FR-DOC-01 follow-up that
#126 deferred). Builds on ADR-004 (integer
money), ADR-024 (the outbox) and ADR-026 (receipt and voucher numbers, FR-DOC-04). Needs a decision
before code, because no PDF library was in the project and chapter 5 named PDF rendering in the
documents module without choosing how.

## Context

FR-DOC-01 asks for four MVP documents: the repayment receipt, the disbursement voucher, the
repayment schedule and the loan statement. Each needs a template and a golden-file test with
fabricated data. FR-DOC-02 and FR-DOC-03 already cover storage under a tenant-prefixed key and
five-minute signed URLs. FR-TEN-08 gives each tenant a logo, a theme colour and a receipt footer
that the documents must carry.

Four questions are open:

1. Which library renders the PDF.
2. What a template is written in.
3. When a document is rendered: on demand in the request, or by the worker through the outbox
   (chapter 5 lists the worker as draining PDF rendering).
4. How a golden-file test stays stable when PDF bytes carry timestamps and object ids.

## Decision

1. **Library: OpenHTMLtoPDF** (`io.github.openhtmltopdf`, the maintained fork, on Apache PDFBox).
   It lays out XHTML and CSS, including tables, page headers and footers, page numbers and
   embedded fonts. A receipt or a statement is a page of tables; drawing them by coordinates
   would be several hundred lines per document. Licences: the renderer is LGPL 2.1 and PDFBox is
   Apache 2.0. Both are used unmodified as libraries in a hosted service, with nothing
   distributed, so neither licence adds an obligation.
2. **Templates: Thymeleaf XHTML** under `resources/templates/documents/`, one per document. It
   escapes every value by default, so a member's name cannot break the markup. Money and dates
   are formatted in Java, once, by the same helpers the API uses. The tenant's logo, theme colour
   and footer come from the branding of FR-TEN-08. The self-hosted Open Sans is embedded, so
   names and amounts render the same on every device.
3. **On demand, stored once per version.**
   - **Receipt and voucher.** These are fixed once the transaction exists. The first download
     renders the document in the request (well under a second for one page), stores it under
     FR-DOC-02 and records it in `documents`. Every later download returns a signed URL to the
     stored copy. What a receipt shows must therefore be what was true when the payment was
     recorded and nothing later: what the payment was applied to is the transaction's own
     allocation rows, and "Loan after this payment" (outstanding, credit held, next due date) is
     computed from the transactions recorded up to and including that payment (`LoanLedger.after`),
     never from the loan's balances today, and carries no "as at today" date. A receipt first opened
     a month later is identical to one opened at once.
   - **Reversals.** A receipt is never first issued for a transaction that was reversed (422
     `transaction_reversed`: the repayment no longer stands, and the reversal shows on the
     statement). A receipt issued before the reversal stays byte for byte as issued, since a
     stored document is immutable, and the way a client shows it as reversed is the response of
     `POST /lending/loans/{loan_id}/transactions/{txn_id}/document` and the list
     `GET /lending/loans/{loan_id}/documents`, which carry `reversed` and `reversed_on`. The core
     `GET /documents/{id}` and download URL serve the stored copy and know nothing of the loan.
   - **Schedule and statement.** These change with every repayment, so they are rendered on each
     request "as at" the business date and are not stored. Storing them would mean invalidating
     them on every money event. The statement is the loan account: the disbursement at its gross
     amount (with the fees deducted from the proceeds and the net paid out as notes), the interest
     and fees of the schedule as charges, each repayment, rebate, reversal and write-off as a credit
     or a charge with a running balance, and a closing balance that is the loan's outstanding (a
     difference the ledger cannot explain shows as an explicit adjustment line, never as a closing
     balance that disagrees with the loan). Money that does not move the balance (an upfront fee, a
     recovery after a write-off, credit held from an overpayment) is a note.
   - **No render under a lock.** A repayment waits on the loan's row lock, so no document work
     happens while it is held, and none inside a transaction. The data is read in a short read-only
     transaction; the PDF is rendered and uploaded with no database connection held
     (`Documents.putGenerated`); the loan's row lock is then taken only to look again, and record
     (`Documents.recordGenerated`). Two concurrent first requests both render, one records and the
     other gets that document back and deletes its own upload. That lock is the guarantee of one
     stored document per transaction, so no unique constraint on `documents` was added (a migration
     would add nothing the only writer does not already enforce); `frDoc02_concurrentFirstRequests`
     proves it.
   - **One snapshot.** A statement is built from several queries (transactions with their allocation
     rows, schedule items, balances, fees). Everything one document reads runs in one read-only
     REPEATABLE READ transaction, so a repayment that commits between two of those queries cannot make
     the lines disagree with the balance (a false "Other adjustments" line).
     `LoanDocumentsIsolationIT` proves it deterministically: the documents' template sees the same
     rows at its second query although another connection committed after its first, and a
     READ COMMITTED control sees the change.
   - **A cap on concurrent renders.** Each render holds a document in memory, and the smallest host
     has a heap of about 224 MB, so renders take a slot from a fair semaphore (default two,
     `bms.documents.max-concurrent-renders`). A request that finds no slot for three seconds
     (`bms.documents.render-wait-ms`) is answered 503 `documents_busy` with `Retry-After`; the slot is
     released however the render ends (`PdfRendererTest`). The statement is not paged: its lines are
     a few hundred rows of short strings, and `StatementMemoryTest` renders a five hundred line
     statement three times in a child JVM capped at 224 MB.
   - **Not cached.** Every response that carries a document, a list of them or a signed link is
     `Cache-Control: no-store` with `Pragma: no-cache`.
   - **Numbers.** A receipt or voucher has its own gap-free number. A schedule or statement is
     numbered `SCH-` or `ST-`, the branch code, the loan number and the statement date
     (`ST-HQ-LN000001-20261013`), so two statements of different days never share a number.
   - **The worker.** The outbox path stays for what is slow or not interactive, such as report
     exports and documents attached to SMS or email. Nothing in the MVP four needs it.
4. **Golden files compare text, not bytes.** Tests render with a fixed creation date and document
   id. They extract the text with PDFBox and compare it with a checked-in `.txt` golden file per
   document. A second check asserts the page count and that the logo image is present. Every golden
   file is one page, so a separate test renders a statement of several pages and asserts that the
   table header repeats on each page and that the footer reads "page x of y" with the real numbers.
5. **The renderer loads only `data:` URIs.** The logo is passed as a `data:` URI; an access rule on
   the renderer (checked before any URI is resolved) refuses every other address, so a value in a
   template can never make it read a file or fetch a URL (`PdfRendererTest`).
6. **Text is made printable first.** Every string handed to a template has the characters XML cannot
   hold (control characters) removed, and a character Open Sans has no glyph for is printed as `?`
   instead of an empty box. Open Sans covers Latin (with its extensions), Greek and Cyrillic, so
   names such as Amélie, Zoë and Привет print as typed; Chinese, Japanese, Korean, Arabic, Hebrew and
   emoji do not. A fallback font was not added: a CJK or Arabic face is 5 to 20 MB (the image would
   grow by more than the whole renderer), and Arabic needs shaping the renderer does not do, so a
   fallback would still misprint. A tenant whose members use those scripts needs a decision of its own
   (a font per tenant, or Latin transliteration of names at entry). Markup characters are escaped by
   the template engine and print as typed.

## Alternatives considered

- **Apache PDFBox directly.** Apache 2.0 throughout and fully deterministic, but every table,
  line wrap and page break is hand-placed code. That is cheap for a receipt and expensive for a
  multi-page statement. Kept as the fallback if the LGPL dependency is refused.
- **OpenPDF (the iText 4 fork).** LGPL or MPL, with a table API, so less code than PDFBox. Its
  layout is still built in Java, which makes branding a code change rather than a template change.
- **Render everything through the outbox.** The user waits for a job, the page must poll, and a
  one-page receipt gains nothing from it.

## Consequences

**Better:** a document's look is one template file, so a template change is a review of HTML, not
Java. Receipts are stored exactly as first issued. Golden tests read as text in a diff.

**Worse:** one new runtime dependency family (OpenHTMLtoPDF, PDFBox, Thymeleaf) and one embedded
font, about 6 MB in the image. The first download of a receipt costs one render inside the
request.

**Quality bar** (product owner, 2026-10-11). It applies to #120 and to every later document:

1. One shared header and footer for all documents. The header has the tenant logo and business
   details on the left, the branch address, and a right-aligned line with the document number
   and date. Below it comes a full-width rule in the tenant brand colour, then a centred document
   title (RECEIPT, VOUCHER, LOAN SCHEDULE, STATEMENT).
2. The tenant brand colour is used for the rule, the table header and the title, with a pale tint
   of it for alternate rows. Body text is dark grey. The table header is white on the brand
   colour, contrast-checked by the existing brand contrast rule.
3. Amounts appear in figures and in words, with the currency shown. Money is right aligned in a
   tabular figure font, and the totals row is clearly set off.
4. Signature blocks appear where the document type needs them (the voucher: prepared, approved,
   received), with names and dates. A blank placeholder is never left for data the system knows.
5. Document numbers are readable and unique per tenant and branch: a series with a prefix and
   the branch code. The number is repeated in the footer with page x of y.
6. Fonts are embedded (the self-hosted Open Sans) with A4 print margins and no cut-off tables.
   Long tables repeat their header on each page, and a receipt fits on one page.
7. Leave room in the receipt layout for an optional QR or short verification code later.
8. Visual check: each template is rendered to PNG by a documented script (or in CI), and a sample
   sheet is reviewed before merging. Golden text tests alone do not catch an ugly layout. The script
   is `make document-samples` (#120): a PDF and a PNG per page for each document, in
   `backend/target/document-samples`.

**Watch for:** the logo must stay PNG, JPEG or WebP (FR-TEN-08); SVG is never passed to the
renderer. A deducted fee that equals or exceeds the principal is refused (422 `fees_exceed_principal`)
instead of failing in the amount in words. The fees a voucher shows as deducted are the credit to
fee income on the disbursement's own journal entry, not a recomputation from the product.

**Penalties are not dated yet, and this is a known limit.** Nothing writes
`lending_schedule_items.penalties_due_minor` today (it is zero on every item), so the statement,
which charges the schedule's penalties on the disbursement date, and the receipt's "loan after this
payment", which starts from the schedule's amounts, are exact. Increment 6 will accrue penalties
later. The data model has no dated penalty record, so neither document can place a penalty in time
yet: a receipt for an earlier payment first opened after a penalty accrued would include it, and the
statement would show it charged at disbursement. The honest computation (penalties as of the payment
date) needs each penalty charge recorded with its date, as a loan transaction or a ledger row.
Increment 6 must add that and make `LoanLedger` read it. So that this cannot ship unnoticed,
`PenaltyDatingGuardTest` fails as soon as any source file other than the two reads in
`ServicingRepository` mentions `penalties_due_minor`; whoever adds penalties updates `LoanLedger` and
then that test.

A template change does not alter receipts already stored; that is intended, and the stored copy is the
one issued.

## Addendum of 2026-10-13 (issue #197): the retail documents use the same renderer

The optional retail documents (ADR-033 amendment of the same date) reuse this infrastructure and add no renderer: the invoice, the
delivery note and the quotation are Thymeleaf templates under `templates/documents/` that use `layout.html`, rendered by
`Documents.renderPdf` in the request with no connection held, stored once through `putGenerated` and `recordGenerated` under a lock on the
document only, and read back through the signed URL after the owning module's `DocumentAccess` allows it (subject type `retail.document`).
They differ from a receipt in one respect: the number is allocated before the PDF is rendered, in its own short transaction, because the
number is printed on the page and a render must never run under the series lock. So the document row (with its snapshot) is the record
and the PDF is a rendering of it; a render that fails leaves a numbered document that renders from the same snapshot on the next
request. The customer statement follows the schedule and statement rule: rendered on each request, never stored, numbered by date and a
short customer code. `make document-samples` renders them too.

## Addendum of 2026-10-14 (issue #264): penalties are dated

Increment 6 records every penalty as a `lending_loan_charges` row with the business date it is
charged for and the time the nightly run recorded it. `LoanLedger` no longer reads the items'
penalties due: the statement charges the penalties of each date on that date (before that day's
payments, because the run charges at the start of the business date), and a receipt's "loan after
this payment" adds only the penalties recorded before the payment. The known limit above is
closed. `PenaltyDatingGuardTest` now checks that `LoanLedger` never reads the items' penalties and
that only the nightly run writes them; a later writer (a waiver) must keep the same dating.

## Addendum of 2026-10-22 (review of #270): cancelled and recharged penalties

A repayment reaches only the penalties charged on or before its value date. After every repayment and
reversal each charged period is recomputed from the payments by value date. What a back-dated repayment
shows was not owed (the item was paid up before the period, or a percent penalty's base was smaller)
is cancelled as a dated `lending_charge_cancels` row and credited on the statement on the day of the
cancel ("Penalties cancelled after a back-dated payment"); what a reversal shows was owed after all is
charged again as a new `penalty_recharge` row, kept on the period's own date for the penalty logic and
printed on the day it was recorded ("Penalties charged again after a reversal"), as its cancel was. Both are timed with the transaction that caused them, and repayments are stamped with the
clock when recorded under the loan's lock, so a receipt counts exactly the penalties recorded up to its
payment and never changes after it is first opened. `PenaltyDatingGuardTest` allows the reconcile as
the one other writer of the items' penalties due.
