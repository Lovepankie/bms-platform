# Closing a business: the Owner's request, the removal, and businesses with no Owner

For the platform operator (issue #571, ADR-090). How an Owner closes their business, what you do after the waiting period, what
removal deletes and keeps, the deletion of the kept money records years later, and how to give an Owner to a business that has none.

## What the Owner does

1. Business set-up > **Close the business**: the Owner reads what closing means, then a review of exactly what happens, and proves
   it is them (the authenticator code, else the password).
2. From that moment the business is **read only**: everyone can sign in, look and download, nobody can add or change anything. A
   full "Download my data" workbook is made for the Owner. The Owner and every operator get an email.
3. Until you remove it the Owner can **Cancel the closing** on the same page, and the business works again at once.

Closing is not cancelling. Cancelling the subscription (Plan and modules) keeps every record, read only. Closing removes the
people's data.

## Remove a business

1. Open **Businesses closing** in the operator portal. Each request shows the day it can be removed: 30 days after it was asked
   for, in the business's own time zone.
2. Before that day there is no Remove button and the API answers 422 `cooling_off`. Do not shorten the wait to oblige anyone: the
   wait is the Owner's chance to change their mind, and the Owner may call to say a stranger asked.
3. On or after that day, call the Owner on the number you know (not one given in the request) to confirm. Then press **Remove the
   business**, type a code from your own authenticator app and a reason, and confirm. It cannot be undone.
4. The answer shows the day the kept money records may be deleted (`keep_until`). The platform history has
   `platform.tenant.removed` with what was erased.

What removal deletes and keeps is listed in ADR-090 decisions 7 and 8. In short: names, phone numbers, ID numbers, emails, notes
about people and every stored file are deleted or replaced by placeholders; sign-in is gone; the money records stay, without names,
for the set years (5 unless `BMS_CLOSED_RETENTION_YEARS` says otherwise) after the end of the business's financial year in which the owner asked, then the daily job deletes them. A buyer's TIN stays on a kept tax invoice.

The removal checks in the database that you are signed in and used your code in the last two minutes; if it answers that your
code is needed, type a fresh one and remove again. If the erasure fails, nothing was erased (one transaction) and the request
shows as begun and not finished; fix the cause the error log names and press Remove the business again to finish it.

While a business is closing, its scheduled jobs (penalties, interest, returns, daily notices) and its SMS and push messages stop,
as for a suspended business; they start again if the owner cancels.

## The kept money records are deleted after `keep_until`

Owner decision D46: the money records a removed business kept (sales, payments, invoices, credit notes, expenses, the journal,
tax figures, the generated invoice and receipt PDFs) stay read only for `BMS_CLOSED_RETENTION_YEARS` (5 unless set) after the
end of the business's financial year in which the owner asked to close it. The year ends in the month the owner set in Business
set-up, else in June. The day after `keep_until` the job `core.platform.closed-retention` (daily, 03:20 Kampala time) deletes
every row the business still holds, except its `tenants` row, its anonymised users and its closure rows. The platform history
has `platform.tenant.purged` with the number of rows. Nothing is needed from you. If the error log names a closure the job could
not purge, the request stays `removed` and the job tries again the next night; fix the cause (usually a table added later whose
rows something else still points at) and let it run. The job is off where `BMS_PLATFORM_CLOSED_RETENTION_JOB_ENABLED=false`.

## Give a business an Owner

1. Open **Businesses with no owner** in the operator portal. Each business is listed with its active administrators and when they
   last signed in.
2. Confirm with the business who owns it. Press **Set as the owner** beside that administrator, type a code from your own
   authenticator app and a reason. It is ADR-062's Make the owner: audited in the business's history and the platform's.
3. A business with no active administrator says so: open its Business page and send its first administrator a new invitation.
