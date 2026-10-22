# ADR-064: Collection lists: a loan officer sees their own portfolio, and only callers see phone numbers

## Status

Accepted (owner decision delegated to the Dev Lead, 22 Oct 2026, the second review of pull request #272, issue #266,
FR-CLN-01 to FR-CLN-05). Migration `V83`. Builds on ADR-017 (per-permission branch scope) and ADR-062 (the Owner).

## Context

The due list, the arrears list and the promise list (chapter 14 section 14.4) carry each borrower's name and phone number. In
the seeded roles every holder of `lending.collections.read` (administrator, branch manager, loan officer, cashier, accountant,
auditor) also holds `lending.members.read`, so masking the phone on members read alone protected nothing: a cashier or an
auditor could page through the whole branch's arrears with full numbers. And a loan officer saw every officer's borrowers in
the branch, although they collect only their own.

## Decision

1. A new permission `lending.members.contact` ("see a borrower's phone number on the collection lists") is granted to the
   people who call borrowers: tenant administrators, branch managers and loan officers. Cashiers, accountants and auditors do
   not hold it. Wherever the collection lists show a phone it is whole only with this permission in the loan's branch, and
   masked as `+256*****0001` otherwise. No collections audit row or export carries a phone.
2. A new permission `lending.collections.all_officers` lets a caller see every officer's loans on the collection lists. It
   is granted to administrators, branch managers, cashiers, accountants and auditors, not to loan officers. Without it the
   list covers only the caller's own portfolio (the loans they are the responsible officer for), whatever `officer_user_id`
   was asked for, as `lending.insights.all_officers` does for insights (V33).
3. A reassignment of a loan's officer (FR-CLN-01) requires a reason of 5 to 300 characters, kept in each loan's audit row,
   as ADR-062 asks of an administrator's changes.

## Consequences

- A cashier taking payments sees who is in arrears and how much, but not the contact book; the number is a call away through
  the loan officer or the manager. A business that wants a cashier to call gives them the permission on the Users screen.
- A loan officer's lists are short and theirs; a manager reassigning loans still sees the branch.
- Watch for: a custom role that holds collections read without either permission sees only its own (usually empty)
  portfolio with masked phones; that is the safe default.
- What is protected is the bulk contact book: a cashier can still read one member's phone at a time through
  `GET /lending/members/{id}` (`lending.members.read`), as they need to serve the member at the counter; each such read is
  one record, scoped by branch.
- The whole-branch view is decided per branch: a person who is a branch manager in one branch and a loan officer in
  another sees every officer's loans in the first and only their own in the second.
