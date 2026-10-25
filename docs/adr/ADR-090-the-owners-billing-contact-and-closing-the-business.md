# ADR-090: The Owner's billing contact, closing the business with removal after a cooling-off, and businesses with no Owner

## Status

Proposed (issue #571, the rest of #325 found in the #570 review, FR-SUB-13, FR-TEN-09, FR-IAM-18). Builds on ADR-062 (the Owner and
the owner step-up), ADR-073 (the Owner's own plan, the read-only business), ADR-074 (Download my data), ADR-075 (reminders and
receipts) and ADR-016 (platform operations through definer functions). Amends FR-TEN-09 (the operator's export and scheduled
deletion becomes the Owner's request with the operator's confirmation). Migration `V102`, directly after `V101` (#577, merged);
renumbered at merge if the order changes. The decision numbers 087 to 089 went to #577, #579
and #583.

## Context

Issue #325 gave the Owner three powers that ADR-062 left out. First, many owners have an accountant who pays the bills, and the
reminders and receipts of ADR-075 go only to the Owner's own sign-in email. Second, Uganda's Data Protection and Privacy Act,
2019 gives people the right to have their data removed, and a business that stops for good has no way to ask: cancelling (ADR-073)
keeps everything, read only, for ever. Third, the V79 backfill left some businesses with no Owner, and the operator sees them only
in a migration log line. Sign-in itself is out of scope: #322 and #349 change it, behind their own owner decisions.

## Decision

1. **A billing contact, the Owner's alone.** `billing_contacts` (one row per business, V102): a name, an email and an optional
   Ugandan mobile number. Reminders and receipts go to it when it is set and to the Owner otherwise, as before; SMS reminders,
   when the platform sends them, go to its phone. `PUT /billing/contact` sets it and `DELETE /billing/contact` removes it, the
   Owner only (403 `owner_only`), audited `core.billing.contact_changed` with before and after. `GET /billing` shows every
   administrator who gets the bills. It is never a sign-in contact: no sign-in code, password link or security notice reads the
   table, and setting it changes no user, credential or `contact_changed_at`, so it starts no cooling period of ADR-062. No
   step-up: the contact receives bills, not access, and the change is audited.
2. **Closing is the Owner's request, with the step-up.** `POST /closure` (the Owner only, 403 `owner_only`; the step-up of ADR-062,
   422 `password_required` or `totp_required`) writes a `tenant_closures` row with `remove_after`, the business's own date plus
   the cooling-off period (`bms.platform.closure-cooling-off-days`, 30 by default). One open request per business (409
   `closure_already_requested`). It is audited in the business's log (`core.business.closure_requested`) and the platform's
   (`platform.tenant.closure_requested`), every active operator is emailed, and the Owner is emailed what happens and when. A
   business whose subscription ended (ADR-073) may still ask. Closing is separate from cancelling: cancelling keeps the data.
3. **Read only at once, for people and for jobs.** While a request is open the resolver answers `closing` and every write is
   refused with 423 `business_closing`, except staff sign-in (`/auth/staff/...`, unchanged), refresh and sign-out, the exports
   (the audit CSV, report runs, Download my data and the signed address of any file, which is a read) and the Owner's cancel.
   Every member of staff sees the notice with the day of removal (`GET /plan/status`, `closing_remove_after`). The scheduled jobs
   leave the business out as they leave out a suspended one: `app_list_active_tenants`, `app_list_active_tenants_with_module`
   and `app_tenant_is_active` exclude a business with an open request, so the lending arrears run and its penalty charges,
   interest and returns, the daily notices and the approvals' expiry do not run for it, and the outbox's SMS and push channels
   send nothing for it (an email still goes, in the platform's look). Billing leaves it out (no reminder, never made overdue). The
   report runner, the weekly copy of its data and a payment that arrives (ADR-073 decision 5) keep their own lists and still
   serve it. A cancel puts it back in every list at once.
4. **A full data export for the Owner.** The request queues the full "Download my data" workbook of ADR-074 for the Owner,
   through the new public port `DataExports` of `core.reporting`; it is the full copy (personal columns included), made for the
   Owner alone and fetched after sign-in, never emailed. A failure to queue it never refuses the request: the Owner can make it
   from Business set-up, which keeps working.
5. **The Owner may cancel until the removal.** `POST /closure/cancel` (the Owner only) closes the request as `cancelled`, audited
   in both logs; the business works again at once. A cancelled request is never removed.
6. **The operator removes after the cooling-off, and only the operator can.** `GET /platform/closures` lists the requests, the
   soonest removable first. `POST /platform/closures/{id}/remove` needs the operator's own authenticator code and a reason (the
   operator's step-up of ADR-042), is refused during the cooling-off (422 `cooling_off`, checked before the code is spent) and for
   a request no longer open (409 `closure_not_open`). The removal starts in `platform_begin_tenant_removal`, which refuses a
   transaction bound to a business, computes the business's own date from its time zone (no date comes from the caller), and
   authenticates the operator in the database: an active operator with two-step sign-in, a live platform session of theirs (the
   access token's session) and an authenticator code used in the last two minutes (the step-up just made records it); otherwise
   `operator_not_verified`. Only then does the request become `removing`, and only a `removing` request can be erased
   (`app_mark_tenant_removal`, the erasers' own check). So a request of the business itself, or a fault in any of its routes,
   can neither start a removal nor skip the cooling-off. The removal is the Owner's request and the operator's confirmation: two
   people, which is how this reads FR-TEN-09's "two-person action on the platform side".
7. **What removal does, in one transaction bound to the business.** After the begin of decision 6, one transaction bound to the
   business marks itself for the erasers, runs the core's eraser and every module's `PersonalDataEraser` (a kernel port, so the
   core names no vertical), and ends in `platform_finish_tenant_removal`: the business closed (`tenants.status = 'closed'`, so its
   host resolves nothing and no job lists it), the subscription ended, the sign-up application it came from anonymised, and
   `platform.tenant.removed` written with the operator, the reason and what was erased. A failure rolls the erasure back and
   leaves the request `removing`; the operator removes again. Deleted at once: sign-in credentials, recovery codes, sessions,
   invitations, password links, push devices, SMS opt-outs, next of kin, the billing contact, and every stored file (member ID
   images, item photos, logos, report files, screenshots, quotations and delivery notes) except the generated invoices, receipts,
   credit notes and payment vouchers, which are money records (decision 8). Anonymised at once: staff and member users (a
   placeholder name and email, no phone, deactivated, nobody the Owner), members (the member number kept; name, phones, ID,
   birth date, place, occupation and income removed), customers, suppliers and cash book people (numbered placeholders without
   contact details), the buyer's name and contact on sales, quotations, invoices, credit notes and orders (in the printed snapshot
   too), collateral descriptions, owners, valuers and notes, message recipients and their values, SMS log recipients, feedback
   text, payment request phones, and the free text people typed on any record, money records included: every note, memo,
   reason, explanation, purpose and message column of the business's tables, read from the catalogue, becomes `Removed` (a
   column whose check lists codes holds a code and stays). The business's audit rows and its rows in the platform's log keep
   who did what, when, and every amount, date, id, code and status of a money change; `bms_strip_personal` takes out every key
   that names, reaches or describes a person and every value that looks like a phone number, an email or a national ID, and the
   addresses go. The append-only and guard triggers of the business's tables (every BEFORE UPDATE or DELETE row trigger, read
   from the catalogue) let a change through only inside such a removal or the purge of decision 8 (`bms_erasing`: a definer
   function running as `bms_owner`, the transaction marked for this business, its request `removing` or `purging`).
8. **Which money records are kept, for how long, and who deletes them: the owner's decision D46 (Hillary, 26 Oct 2026),
   the years still to be confirmed by an accountant.** Kept read only, without the people in them: the money records, that is
   sales with their lines and payments, invoices (and their generated PDFs), receipts, returns and credit notes, expenses and the
   rest of the cash book, purchases, supplier bills and payments, the journal (entries and lines), the tax figures in them, stock
   movements, loans with their schedules, transactions, repayments and vouchers, savings and investments, and the subscription
   payments and receipts. They are kept for `BMS_CLOSED_RETENTION_YEARS` (5 by default) after the end of the business's own
   financial year in which it closed. **The closing** is the day of the Owner's request, in the business's own time zone: the
   day it went read only, so the clock does not move with the 30 days or with when the operator acts. **The financial year**
   ends in the month the Owner set in Business set-up (`financial_year_end_month`, the Owner's alone, 403 `owner_only`
   otherwise); the ledger's periods are calendar months and say nothing about a year end, so without a setting it is June, the
   end of the Uganda tax year. `tenant_closures.keep_until` is the last day of that month in the year the financial year ends,
   plus the setting's years, shown to the operator and in the removal's answer. The day after it, in the business's own time
   zone, a scheduled job (`core.platform.closed-retention`, daily at 03:20 Kampala time) deletes every stored file of the
   business and then every row it still holds through `platform_purge_closed_tenant`, which refuses before `keep_until`
   (computing the date itself) and refuses a transaction bound to a business; it keeps only the `tenants` row, the anonymised
   `users` and the `tenant_closures` rows, which the platform's own history points at. The request becomes `purged` and
   `platform.tenant.purged` is written with the number of rows and no personal data. No trigger is switched off by hand. Staff
   accounts, sign-in data and the personal details of customers and members are removed or anonymised at once when the removal
   is confirmed (decision 7), not at the end of the period. **The buyer's TIN** is the one exception D46 allows, on a kept tax
   document: the retail eraser keeps the buyer's `tin` (and any other key but the name and contact) in the snapshot of every
   kept document (`retail_documents.snapshot -> 'buyer'`), from which the invoice is printed. No column on customers, sales,
   quotations, documents or credit notes holds a buyer TIN today; one added later is kept the same way and named here.
9. **Businesses with no Owner, listed for the operator.** `GET /platform/tenants/without-owner` lists every active or suspended
   business with no Owner and its active administrators (`platform_tenants_without_owner`), and the portal page Businesses with
   no owner offers Set as the owner, which is ADR-062's existing route: the operator's code and a reason, audited in both logs.
   A business with no active administrator says to send its first administrator a new invitation from the Business page.

## Consequences

**Better:** an accountant gets the bills without touching anyone's sign-in; a business that stops for good can have its personal
data removed, as the law allows, with a month to change its mind and a full copy taken first; the operator sees every business with
no Owner and fixes it in two taps; the removal is one transaction that either removes everything or nothing, and a test checks every
tenant table afterwards.

**Worse:** removal cannot be undone, and a business that removes by mistake loses its people for good (the cooling-off, the review
step, the Owner's step-up, the emails and the operator's own code are the guards). The anonymised money records no longer say who
bought or borrowed. The guard trigger functions now carry one more condition, so a later migration that replaces one of them must
keep `bms_erasing(to_jsonb(OLD))`, or the removal or the purge fails (loudly, and `BusinessClosureIT` fails first). A closing
business's interest, returns and penalties do not run during the cooling-off; one that cancels does not get those days back.

**Watch for:** the five years of decision 8 are the owner's decision D46, still to be confirmed by an accountant (change
`BMS_CLOSED_RETENTION_YEARS`; it applies to removals made after the change). Whether the anti-money-laundering record-keeping
duty applies to a lender, so that borrowers' identification would have to be kept with the loans, is an open question: answering
it needs a new owner decision, and until one is made the identification is removed with every other personal detail as D46
says. Accepted gaps under D46, each listed here on purpose: the generated invoice, receipt, credit note and voucher PDFs kept as
money records still print the buyer's or borrower's name as it was on the day, until the purge deletes them; a payment's or
repayment's reference (often a mobile money transaction number) is kept as part of the money record; the platform's own log
keeps the operator's name and the reasons operators typed, which name no customer. A new tenant table with personal data needs
its module's eraser, and `BusinessClosureIT.frTen09_theRemovalLeavesNoPersonalDataInAnyTable` fails until it has one.
