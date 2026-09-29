# Spec review: recommendations

**Date:** 2026-09-29 · **Reviewer:** Dennis Kaweesi · **Spec owner:** Hillary Arinda
**Scope:** `docs/sdd/` chapters 3 to 15, `docs/adr/`, `docs/specs/` as merged in PR #2 and PR #4.

The specification is complete enough to build from, and the hard parts are already right:
forced RLS with composite keys, integer money with one posting operation, append-only
corrections, maker-checker in the database, a fixture that reproduces every register
problem without real data. Nothing below changes a decided ADR.

These are suggestions, not decisions. Each one names the increment it would affect, so it
can be accepted, deferred or rejected before that increment starts. Accepted items become a
chapter change (and an ADR where it is a decision) in the usual way.

## Summary

| # | Recommendation | Area | Affects increment | Priority |
|---|---|---|---|---|
| 1 | Invitation delivery that does not wait for an email or SMS provider | IAM | 1 (now) | High |
| 2 | TOTP recovery codes and an admin MFA reset path | IAM | 1 (now) | High |
| 3 | Cashier till and end-of-day cash-up | New area (cash control) | 5 | High |
| 4 | Duplicate check on manual mobile money references | REP | 5 | High |
| 5 | Backdated repayments and penalties already charged | R-PEN, R-DPD | 5, 6 | High |
| 6 | Partial prepayment on declining balance loans | R-ALLOC | 5 | High |
| 7 | Flat early-settlement rebate has no effect on bullet loans | R-PAYOFF | 5 | High |
| 8 | Regulatory guardrails on product rates and charges | PRD | 4 | Medium |
| 9 | Loan agreement PDF in the MVP if the licence requires it | DOC | 5 | Medium |
| 10 | Period close checks pending approvals dated in the period | GL, APR | 2 | Medium |
| 11 | Authenticated backup encryption, and WAL archiving before go-live | BAK | 8 | Medium |
| 12 | Dry run of import classification on the real file early | IMP | 3 to 7 | Medium |
| 13 | Define the approval subject for each action type | APR | 1 | Low |
| 14 | Threshold splitting | APR | 1 | Low |

Process: section P below.

## 1. Invitation delivery in increment 1

FR-IAM-01 sends a one-time link by email or phone. Increment 1 delivers invitations, but
the SMS adapter arrives in increment 6 (pending ADR-013) and chapter 12 section 12.6
defines the `EmailProvider` interface without naming a provider.

**Suggestion:** for increment 1, show the one-time link once to the inviting tenant admin
(copy button, audited as `invitation_link_revealed`) in addition to queuing the email, so
the demo and staging never block on a provider. Choose the transactional email provider
now; it is a small decision and SPF, DKIM and DMARC setup (chapter 9) takes time to
propagate.

## 2. TOTP recovery

FR-IAM-06 makes TOTP mandatory for tenant admins and super admins, but no requirement
covers a lost phone. A tenant admin who loses their device locks the tenant out of every
admin-only action, including write-off approval.

**Suggestion:** add a requirement for ten single-use recovery codes shown at enrolment
(stored hashed like one-time codes, chapter 8 section 8.2), and an MFA reset for a staff
user performed by another tenant admin, or by a super admin through an audited platform
action when the tenant has only one admin. Both events belong in FR-AUD-03.

## 3. Cashier till and end-of-day cash-up

Cash is the highest-risk asset in a money lending business, and the cashier handles it.
Today cash maps to a GL account per branch (FR-GL-08) but nothing ties cash to the cashier
who holds it: there is no opening float, no end-of-day count and no variance.

**Suggestion:** a small cash-control area in the core (not lending specific, retail will
need it too):

- a till session per cashier per business date: opening float issued by the branch
  manager, closing count declared by the cashier;
- expected cash computed from the cashier's cash transactions in the session;
- a variance posts to a new system account `cash_over_short` (expense or income), and a
  variance above a tenant threshold needs branch manager approval;
- a cashier cannot record cash transactions without an open session, and cannot open a new
  session while the previous one is unclosed;
- report `core.till_summary` per branch and cashier.

This is a normal control in microfinance systems and auditors ask for it. It fits
increment 5, where cash first moves.

## 4. Duplicate mobile money references

Chapter 12 section 12.4.3 makes manual recording of mobile money receipts the MVP path.
`lending_loan_transactions.external_reference` has no uniqueness rule, so one real mobile
money transaction can be recorded twice, on the same loan or on two loans, by mistake or on
purpose.

**Suggestion:** on repayment, deposit and funding, check `(tenant_id, payment_method_key,
external_reference)` against non-reversed transactions. A match returns
`duplicate_external_reference` with the existing record. Recording it anyway requires a
branch manager override with a reason, audited. A soft check is better than a unique index
here, because a reversed transaction must allow the reference to be recorded again
correctly.

## 5. Backdated repayments and penalties already charged

FR-REP-01 allows a value date earlier than today within the open period. R-PEN charges
penalties from the nightly job, keyed by `(schedule_item_id, period_no)`. Neither rule
says what happens when a repayment is recorded after penalties were charged for periods in
which, by value date, the item was not overdue. Example: a payment made on Friday is
recorded on Monday; the weekend job has already charged a penalty.

**Suggestion:** add to R-PEN: after a repayment or reversal whose value date is earlier
than the last job run, recompute penalty periods for the affected items from the value
date forward. Unpaid charges that would not have arisen are voided with reason
`backdated_payment` (a memorandum change, no journal, since penalties are cash basis).
Paid charges are left alone and reported. DPD is already recomputed in the same
transaction (R-DPD); snapshots (FR-ARR-02) stay as recorded, which is correct for history.
FR-REP-05 (reversal re-allocation) needs the same rule in the other direction.

## 6. Partial prepayment on declining balance loans

R-ALLOC step 2 applies money beyond the due items to future items in schedule order, and
within each item in the product's allocation order (default penalty, fee, interest,
principal). For a declining balance loan, that pays future-period interest that has not
been earned, before principal. It is also inconsistent with R-PAYOFF, which rebates exactly
that future interest when the loan is paid off in full.

**Suggestion:** add a product field `prepayment_handling` with two values:

- `hold_as_advance` (default): excess over due items is held against the loan as an
  advance (a liability, or a memorandum balance) and applied on each future due date by
  the nightly job;
- `reduce_principal`: excess reduces outstanding principal and the remaining schedule is
  recomputed (same instalment, shorter term; or same term, lower instalment, as a second
  option).

The flat method can keep today's rule, because under flat the interest is fixed by the
contract.

## 7. Flat early-settlement rebate and bullet loans

R-PAYOFF with `flat_early_settlement_rebate = true` rebates interest on items due after the
value date, but always charges "the next item's interest". A bullet loan has one item, so
the next item is the only item, and the rebate is always zero. Worked example A (the
pilot's product shape) is bullet, and open question 1 asks whether any interest is given
back on early settlement.

**Suggestion:** until the pilot tenant answers, state explicitly in R-PAYOFF that the flag
has no effect on bullet loans, and add a third option for when it is needed:
`pro_rata_days`, where interest due is `round(I x days_elapsed / term_days)` with a
minimum of one period (for example one week or one month, a product setting).

## 8. Regulatory guardrails on products

A Tier 4 money lender operates under the Tier 4 Microfinance Institutions and Money
Lenders Act, 2016 and the regulator's rules made under it. Recent regulations for money
lenders have been reported to set a maximum monthly interest rate; the current text and
figure should be confirmed with the regulator before encoding anything.

**Suggestion:** product save (FR-PRD-01) validates the effective monthly rate, and the
total of interest plus fees plus the penalty cap relative to principal, against limits
held in platform configuration per jurisdiction (not per tenant, and not in the
repository's seed). A product outside the limit is refused with `rate_above_regulatory_limit`.
This protects the platform as well as the tenant. Add it to open question 5.

## 9. Loan agreement in the MVP

FR-DOC-01 puts the loan offer and agreement in P2. For a licensed lender, giving the
borrower a written agreement with the terms, the schedule and the total cost is usually a
licence condition, and it is what a court would ask for in a dispute.

**Suggestion:** confirm with the pilot tenant and the licence text (open question 5). If
required, move the agreement PDF to increment 5 alongside the schedule PDF; the template is
mostly the schedule plus the product terms.

## 10. Period close and pending approvals

FR-GL-06 closes a month; FR-APR-07 keeps requests pending for 7 days. A disbursement or
reversal requested with a value date in the month, still pending when the month closes,
fails with `period_closed` on approval.

**Suggestion:** closing a period lists pending approval requests whose value or entry date
falls in it, and refuses with `period_has_pending_approvals` until they are decided or
cancelled. The checker of the close sees the same list.

## 11. Backups

Chapter 9 section 9.10 encrypts with `openssl enc -aes-256-cbc`. CBC mode has no
authentication, so a corrupted or tampered file decrypts without error and only fails
later inside `pg_restore`, possibly during a real incident.

**Suggestions:**

- encrypt with `age` (or `gpg --symmetric`, which is authenticated), and write a SHA-256
  of the ciphertext next to each object so the monthly check (NFR-BAK-05) and the restore
  drill can verify before decrypting;
- reconsider the timing of WAL archiving (NFR-BAK-06 says `Later`). With an RPO of 24
  hours, losing the host at 18:00 loses a full business day of receipts, disbursements and
  approvals, and the cashiers' paper trail is the only way back. `wal-g` or `pgBackRest`
  to the same R2 bucket is a small addition on a single host and could be part of
  increment 8's go-live readiness.

## 12. Early dry run of the import on the real file

The pilot import (increment 7) is the riskiest step to go-live, and the only one that
depends on data the team has not fully seen. The fixture reproduces the known problems,
but the real file may have more.

**Suggestion:** once parsing and classification exist (they have no dependency on loans),
run them on the real file on a developer machine, outside the repository and outside any
shared environment, and record only issue codes and counts in a meeting note. New
problems then become fixture rows and chapter 13 changes months before go-live, rather than
in increment 7.

## 13. Approval subjects

`approval_requests` has a partial unique index on `(tenant_id, action_type, subject_id)`
for pending requests. The subject is not defined for every action type: for
`charge_waiver`, a loan subject blocks two waivers on different items of the same loan; for
`manual_journal` and `period_close`, there is no existing subject when the request is made.

**Suggestion:** add a column to the chapter 8 section 8.4 table giving `subject_type` for
each action (for example `lending.schedule_item` for waivers, the draft journal id for
manual journals, the period id for close).

## 14. Threshold splitting

FR-APR-04 lets an action below the threshold execute without a checker. Splitting one
disbursement or withdrawal into several below-threshold actions is the usual way around
it.

**Suggestion (optional):** the threshold applies to the sum of the same action type by the
same maker for the same member on the same business date. Cheap to add in increment 1,
harder to retrofit.

## P. Process: send the open questions now

`docs/specs/lending-mvp-scope.md` section 3 lists 13 open questions with defaults. Questions
1 (allocation and penalties), 4 (branches and approvers) and 7 (product shapes) decide the
shape of increments 4 to 6, and 5 (compliance) affects items 8 and 9 above. Sending the
list to the pilot tenant now, rather than when each increment starts, gives the answers
time to arrive before they block work.
