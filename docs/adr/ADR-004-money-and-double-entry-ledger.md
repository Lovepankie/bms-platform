# ADR-004: Money and accounting: integer minor units and a double-entry general ledger

## Status

Accepted (2026-09-29)

## Context

The pilot tenant keeps its loan book in a spreadsheet. It has no repayment tracking, no
general ledger and no way to produce a trial balance or a balance sheet. A licensed
money lender must be able to produce financial statements and regulator returns, and
must be able to show where every shilling went.

Two sub-decisions are needed: how amounts are stored, and how financial events are
recorded.

For amounts, floating point was rejected outright: binary floats cannot represent most
decimal amounts exactly, and rounding drift across thousands of repayments produces
books that do not balance. Decimal columns were considered; integers in the currency's
minor unit were preferred because they make the arithmetic exact, cheap and identical in
the database, the backend and the browser.

For recording, the options were a set of balance columns updated in place, or a
double-entry general ledger in which every financial event posts a balanced journal
entry and balances are derived. Balance columns cannot be audited, cannot be
reconciled and cannot produce a trial balance.

## Decision

**Amounts.**

- Every amount is stored as `bigint` in the currency's minor unit, in a column named
  `<name>_minor`, with a `currency char(3)` column on the same row. The one exception is
  `journal_lines.debit` and `journal_lines.credit`, which are minor units by definition.
  The `currencies` table records each currency's exponent. UGX has exponent 0
  (ISO 4217), so an amount in UGX is stored in whole shillings.
- Floats are banned for money in SQL, in the backend and in the frontend. The backend
  uses 64-bit integers for stored amounts and arbitrary-precision decimal arithmetic for
  intermediate calculation. The frontend receives
  amounts as JSON integers within the safe integer range and formats them for display
  only.
- Rates are stored as integer basis points in columns named `<name>_bp`
  (1 bp = 0.01 percent; a rate of 0.2 is 2000 bp).
- Rounding is half up to the currency's minor unit, applied once per computed line.
  Rounding residue is placed on the last line of a schedule so that totals are exact.

**General ledger.**

- The core owns a double-entry general ledger: `gl_accounts`, `gl_periods`,
  `journal_entries`, `journal_lines` (`docs/sdd/06-database-design.md` section 6.6).
- Every financial event in every module posts through the ledger module's single posting
  operation (`post_entry`),
  inside the same database transaction as the module's own writes. There is no other way
  to write a journal.
- Each line carries either a debit or a credit, never both, never negative. An entry
  balances per currency: `post_entry` validates before writing, and a
  `DEFERRABLE INITIALLY DEFERRED` constraint trigger rejects any unbalanced entry at
  commit, so no code path, raw SQL included, can commit one.
- **Every journal entry belongs to exactly one branch** (`journal_entries.branch_id`).
  An event that spans two branches (for example a repayment received at branch B for a
  loan booked at branch A) posts two entries, one per branch, each balanced through the
  inter-branch clearing account. Every branch therefore has its own balanced trial
  balance, and the consolidated trial balance is their sum, in which the clearing
  account nets to zero.
- Journal entries and lines are immutable. Corrections are made by a reversing entry
  that references the original. The application role has no `UPDATE` or `DELETE`
  privilege on journal tables, and a trigger rejects them for any role.
- Posting rules are data, owned by the module that raises the event: the module maps an
  event (for example "loan repayment, interest portion") to a `system_key` account in
  the tenant's chart of accounts. Default charts of accounts are seeded per tenant from
  the enabled modules' manifests.
- Postings go to open periods only. Closing a period is a maker-checker action.
- **Interest and penalty income are recognised on a cash basis in the MVP**: income is
  credited when a repayment is allocated to interest or penalty, not when it falls due.
  Scheduled but unpaid interest and assessed but unpaid penalties are tracked in the loan
  subledger and reported, but are not general ledger receivables. Accrual accounting
  (and any expected credit loss provisioning) is a later decision, to be recorded in its
  own ADR before it is built.
- Subledgers reconcile to the ledger: total outstanding principal across loans equals the
  balance of the loans receivable control account, per branch; total savings balances
  equal the member savings control account; total investment principal equals the
  investments payable control account. A nightly job checks each and raises an alert on
  any difference.

## Consequences

**Better:**

- Trial balance, profit and loss and balance sheet come straight from the ledger.
- Every amount is exact, and the same number is computed in the database, the API and
  the browser.
- Any balance can be explained by listing the journal lines behind it.

**Worse:**

- Every money-moving feature has to define its posting rules before it can ship.
- Cash-basis income understates income on a growing book compared with accrual. That is
  acceptable for the pilot tenant's size and is visible in the reports, which show
  scheduled interest outstanding next to recognised income.

**Watch for:**

- A module computing a balance by summing its own table and showing it as the ledger
  balance. Ledger figures come from the ledger.
- Currencies with an exponent other than 0 being added without testing the formatting
  path end to end.
- A subledger reconciliation difference being "fixed" by editing a subledger row. The fix
  is always a posted, audited correcting transaction.

## Related ADRs

- ADR-002 makes the posting call synchronous and in-transaction.
- ADR-003 isolates the ledger per tenant like every other table.
