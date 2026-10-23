# ADR-071: Existing loans are brought in with their real dates, past repayments as history, and one opening entry at go-live

## Status

Accepted (Hillary Arinda, Dev Lead, 22 Oct 2026, issue #466; the penalty default of decision 6 is his to change later).
Migration `V89`. FR-EXL-01 to FR-EXL-09. Builds on ADR-004 (integer money and the double-entry ledger), ADR-015
(maker-checker actions registered by their modules), ADR-026 (loan servicing: allocation rows by repayment), ADR-062
(the Owner of a business) and the arrears run of #270 (penalties start at the business's first run).

## Context

A lending business that moves to the app from a paper or spreadsheet register already has loans out: given on real
dates, partly repaid, some of them late. Until now the only way in was the normal origination, which refuses a past
proposed disbursement date, so an existing loan looked new: its due dates, its days overdue, the arrears lists and the
reminders all ran from the wrong day. The business needs each loan as it really stands.

Three things make this delicate. The money already moved before the app existed, so the books must not show it as
today's cash. A long overdue loan would, under the arrears run, earn penalties for months nobody charged at the time.
And a route that accepts a past disbursement date is a way to backdate new lending, unless it is bounded. The rules must
fit every lending business, not one register.

## Decision

1. **Record, then approve.** A tenant administrator or branch manager (`lending.loans.bring_in`) records an existing
   loan: member, product, principal, the real disbursement date, the term, either the product's terms or the total the
   borrower agreed to repay, an optional register number, and the repayments already received (date, amount, method,
   note). The loan is stored as `brought_in` with its repayment lines (`lending_brought_in_repayments`, append-only) and
   counts nowhere (no schedule, transaction, journal, balance, arrears run or list) until a second person approves the
   `loan_bring_in` action with `lending.disbursements.authorise`, the checker of a disbursement. No threshold applies:
   what it brings into the books was never seen by the app. The maker may withdraw it (`cancelled`) or ask again after
   a rejection, an expiry or a stale request. A check (`dry_run`) shows the outcome without saving anything.
2. **The real schedule.** The schedule is built from the real disbursement date with the loan's terms, exactly as a
   disbursement on that date builds it (R-TERM with R-FLAT or R-DECL, fees added to the loan included), with the
   product version in force on that date: the latest version created by its end, or the product's first version when
   the product was set up in the app after it. When the
   register records an agreed total instead of a rate, the principal and the difference are each spread as R-FLAT
   spreads them, with no fee. Fees deducted from the proceeds or paid upfront were settled in cash before go-live and
   are not recorded. The past repayments are applied in value date order (then the order entered), each by the
   product's allocation order (R-ALLOC), as if the app had recorded them on the day; the position on today's business
   date (balances, arrears, next due date, days past due from the real due dates, R-DPD) follows. Repayments that come
   to more than the loan owed are refused, never turned into a credit: the register is checked again.
3. **History, not cash.** On approval, in one transaction, the disbursement and every repayment become
   `lending_loan_transactions` rows with `is_historic` true, no journal and no receipt or voucher number (the V29 check
   already allows a historic row without a journal; receipt numbers stay gap-free for the app's own receipts), with their
   allocation rows; the API marks them `historic` and the loan screen labels them as from the paper register. Insights
   leave a brought-in loan out of the application funnel and the median time to decision, since it was never decided
   as an application in the app. A reversed bring-in (decision 9) is no disbursement in insights and no earlier loan for
   the repeat-borrower count, so a register mistake recorded again counts its principal once. Staff activity credits no
   one with the register's work: a brought-in loan is no submitted application and a historic row no disbursement or
   repayment of the person who keyed it in. The loan becomes `active`, or `closed` on its last repayment's date when the register shows it repaid
   in full. A historic repayment cannot be reversed (422 `historic_not_reversible`): it moved no cash in the books, so a
   mistake in it is corrected by withdrawing or recording the loan again before approval.
4. **One opening entry at go-live.** For the principal still owed, one journal entry dated at the business's go-live
   date: debit `loans_receivable` with the loan as subledger, credit `opening_balance_equity` (3030, already in the
   lending chart). Nothing is posted for the old disbursement or the old repayments, and no cash, bank or mobile money
   account moves. Interest, fees and penalties still owed are not posted, as for every loan: the app recognises them
   when they are paid (ADR-004, chapter 6 section 6.6.3), so the receivable always equals the principal outstanding and
   the subledger check holds for brought-in loans as for any other. A repaid loan has no opening entry. The entry's
   source is the loan (`loan_opening`), keyed `lending.opening:<loan id>`, so it can never post twice.
5. **The go-live date bounds it.** A loan comes in only with a disbursement date before the business's go-live date, and
   its repayments too: a repayment on or after go-live is a normal repayment in the app's own books, recorded after
   approval. So the route cannot be used to backdate new lending. The go-live date is a tenant setting
   (`go_live_date`, today or earlier) that only the Owner of the business sets in the settings (ADR-062) or the platform
   operator sets for a business with a reason, both audited. A business with no Owner has no administrator who sets it:
   403 `owner_only` for all of them, unlike the other Owner-only acts of ADR-062, and the operator sets it; until set, it is the business date of the first sign-in of
   an administrator, stored at that sign-in (`go_live_source` `first_admin_sign_in`, audited
   `core.settings.go_live_defaulted`) so that later sign-ins, removed roles or sessions never move it; V89 stores it for a
   business whose administrators signed in before, or that has a loan in the app. The default never lands later than
   the real start, or a loan made in the app could come back as a paper loan: it is the earliest of that sign-in, the
   first session of anyone ever an administrator, the earliest last sign-in of the staff, the day the business was
   created, and the first loan made in the app (created or disbursed), since sessions are pruned and administrators
   leave. The go-live date can only move earlier, otherwise operator only (the
   owner's agreed rule): once a date is stored, by default or by anyone, the Owner moves it only earlier (422
   `go_live_locked`), whether or not lending is in the app's books yet, since a later date would let a loan disbursed
   after go-live come in as if from paper. Only the platform operator moves it later (`PUT /platform/tenants/{tenant_id}/go-live`),
   with a reason, audited in the tenant's log and the platform's. A go-live date moved after a loan was recorded makes its pending request stale (409
   `go_live_changed`); asking again checks the loan against the new date.
6. **No penalties for the time before the bring-in** (Hillary's default, the borrower-friendly one; it may be changed
   later). Each brought-in loan keeps `brought_in_on`, the later of the go-live date and the approval's business date. The
   nightly arrears run charges a penalty period of a brought-in loan only when the period STARTS on or after
   `brought_in_on`, its start being its date less one period unit (a day, a week or a month). A period that began before
   the bring-in and ends after it is not charged either, and a daily period is charged only on a day strictly after
   `brought_in_on`: none on the bring-in day itself. This sits on top of the rule every loan already has (no period dated
   before the later of the business's first run and the loan's own start). Later periods are charged as for any loan.
   Days past due, the arrears, the collection lists and the reminders count from the real due dates.
7. **The CSV import** reuses the check-then-commit pattern of the retail item import (#146), with its CSV reader moved
   into the kernel (`CsvTable`) so both share one: rows of kind `loan` and `repayment` tied by `ref`, the register
   number; a check reports every row; a commit with any error records nothing; a loan whose `ref` is already in the app
   is skipped, so a file committed twice records each loan once. Every loan passes exactly the checks of the form and
   waits for its own checker.
8. **Audit every step:** `lending.loan.brought_in_recorded`, `core.approval.*`, `lending.loan.brought_in`,
   `lending.loan.brought_in_cancelled`, `lending.loan.existing_imported`, and for the go-live date
   `core.settings.updated` or `core.settings.go_live_set` with `platform.tenant.go_live_set`.
9. **A bring-in is reversed by a second person, while nothing else happened.** An approved bring-in entered by mistake
   (a loan entered twice, the wrong member) is taken back through the maker-checker action `loan_bring_in_reversal`
   (maker `lending.loans.bring_in`, checker `lending.disbursements.authorise`, never the maker, no threshold). The
   approval mirrors the opening entry dated today (ADR-004: corrections are reversals, nothing is deleted), marks each
   historic disbursement and repayment reversed with a historic reversal row on that row's own value date and the
   negatives of its allocation rows (so no period of the app's books and no collection report moves), clears the loan's
   figures and cancels it, which frees its register number for the correct entry. It is refused (422
   `bring_in_has_activity`) once the loan has any transaction made in the app or a penalty charge: from then it is a loan
   of the app's own books and is corrected the usual way. Audited `lending.loan.bring_in_reversed`.
10. **No arrears SMS in the first seven days.** The nightly run queues no arrears notice for a brought-in loan until
    seven days after its bring-in date (due reminders still go): a borrower long behind on paper hears first from the
    business, not from an automatic text the night the loan is approved, and a register mistake can still be put right
    (decision 9). The approvals inbox says so beside a `loan_bring_in` request. The check, the waiting loan and each row
    of the import also warn, without refusing, when a loan of the same member for the same principal and date is
    already in the app, unless both loans carry register numbers and they differ: a number on one side only tells
    nothing, since the same paper loan may come in once by the form without one and again by the import with one.

### How an accountant reconciles it

At go-live the ledger holds, for the existing book, one debit to loans receivable per open brought-in loan, each equal to
that loan's principal outstanding on the register, and the matching credit to opening balance equity. So:

- Loans receivable at go-live equals the sum of the principal outstanding of the brought-in loans, and loan by loan it
  equals the subledger (the app's own check, `booksAgree` in the tests, holds).
- Opening balance equity carries the same total. When the business also brings in its opening cash and bank balances
  (a manual journal, maker-checker), the equity account nets the opening position of the business; any amount left
  there after all openings are in is a difference between the register and the cash counts, investigated before the
  first period close.
- The old disbursements and repayments are visible on each loan (its transactions and statement show them as history,
  without a journal) and in the register; they are not in the cash book, so the cash book starts at go-live with the
  counted cash, never with years of old receipts.
- Interest received before go-live is income of the years before; it is in the register's books, not the app's. From
  go-live every repayment posts as usual: principal to the receivable, interest, fees and penalties to income.

## Consequences

**Better:** a business moves off paper with each loan's true dates, so due dates, days overdue, arrears lists and
reminders are right from the first day. The books open with a clean, reconcilable figure and no fake cash. Nobody is
charged a penalty for months the app did not know the loan, and the route cannot be used to backdate new lending. Both
the form and the file pass one set of checks, and nothing counts before a second person agrees.

**Worse:** brought-in loans carry no guarantors or pledged collateral in this change; they are added later through the
member's register when needed. Fees deducted or paid upfront before go-live are not recorded, so a fee income report
covers only the app's own period. The member limit of active loans (FR-ORG-07) does not refuse a brought-in loan: it
already exists. Insights figures by disbursement month include brought-in loans at their real months, which is the
business's true history but was never in the app's cash.

**Watch for:** an opening entry dated in a closed period is refused (`period_closed`) and the request stays pending
with the error, so bring the book in before closing the go-live month. A go-live date moved later reopens the window
for bringing loans in; that is why only the Owner and the operator move it, and why each move is audited. If the
penalty default of decision 6 changes, the change is the bring-in rule in `Penalties.due`, nothing else.
