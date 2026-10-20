# ADR-041: Closing a branch keeps its history; a branch can be marked as not stocking

## Status

Proposed (2026-10-20, issue #329; not yet accepted by the product owner). Builds on ADR-017 and ADR-020. The migration is `V61`, the first free number after `V60`.

## Context

A shop owner has a shop that has not sold since August and a head office that only keeps accounts. The closed shop still
appears in the branch picker and in the stock list, and the head office appears wherever stock can be received, so stock
is sometimes added there by mistake. The platform already had half of the answer: `branches.status` accepts `active` and
`inactive`, `POST /branches/{id}/deactivate` sets `inactive` (refused while loans, savings or investments are open),
`Branches.findActive` and `/me` leave an inactive branch out, and the retail stock reads keep an inactive branch that still
holds a balance as a column so totals agree with the valuation. What was missing: a way back, a check of what the owner
is closing over, a stock list that leaves a closed shop out by default, a way to say that a branch keeps no goods, and a
screen.

## Decision

- **Closed is the existing `inactive` status; no new status value.** The owner's word is "closed"; the stored value and
  every existing check on `status = 'active'` stay as they are. The API and screens say closed. `deactivate` becomes
  `POST /branches/{id}/close` and a new `POST /branches/{id}/reopen` reverses it. Both need `If-Match` and are audited
  (`core.branch.closed`, `core.branch.reopened`). The head office cannot be closed. Reopening is subject to the plan's
  branch limit like creating a branch.
- **Nothing is deleted, moved or posted by closing.** History, closing stock and sales stay and remain in reports
  (the stock-by-location report and the valuation keep a closed branch that holds stock, flagged `closed`). The default
  stock list (`GET /retail/stock/all-branches`) leaves closed branches out; `include_closed=true` adds the ones that hold
  stock, and the response says how many were left out (`closed_branches`) so the screen offers a Show closed switch.
- **A warning, not a block, over stock and transfers on the road.** If the branch holds stock or has a transfer that is
  dispatched and not received, closing is refused with 422 `branch_close_needs_reason`, in plain words with the stock
  value, until the administrator sends a `reason`. The reason is kept on the branch (`closed_reason`) and in the audit row
  with the figures shown. Nothing is written off or moved automatically. The check is supplied by the module that keeps
  the goods through the port `core.tenancy.BranchClosingCheck` (retail implements it), so the core names no vertical.
- **Not stocking is a branch flag, `branches.stocks_goods` (default true).** A branch that keeps no goods is refused for
  sales (and their price resolution), restocks, purchase orders and goods receipts, transfers into it (and the receipt
  of a transfer), stock-takes (made or committed) and the restock side of a customer return, with 422
  `branch_not_stocking`, enforced on the server in `RetailBranchContext` (`resolveStocking`, `requireStocks`,
  `requireStocksReceiving`). It stays in cash book and administrator scope exactly as before: only goods are refused.
  Goods can still **leave** it: a branch that keeps no goods may be the source of a transfer and may report usage and
  damage, so stock that ends up there (an old balance, or a void of a sale made before the flag was set) is never
  stranded, and the flag need not be switched back to move it. Voids stay allowed, because the sale exists.
  Marking a branch as not stocking is refused (422 `branch_still_has_stock`) while it holds stock, has a transfer on
  the road or has a stock-take started and not committed, because the stock would arrive where nothing can take it in;
  move it first.
- **Concurrency.** Every retail write that names a branch reads the branch row `FOR SHARE` in its own transaction
  (`Branches.lockBranch`), and a close or a not-stocking change holds it `FOR UPDATE`. A write that is checking the
  branch therefore queues behind the change and sees it, and the change waits for a write already past its check, so
  goods cannot land in a branch that has just stopped keeping them and a sale cannot commit at a branch that has just
  closed. Read only requests read the branch plainly. A rename or a new location takes no explicit lock and relies on the version
  check of its own update, so it never holds up a sale; only a change of the flag, a close and a reopen take the lock. A
  request that waits for the row longer than its lock timeout (5 seconds with an `Idempotency-Key`) gets 409 `lock_timeout`
  ("Try again in a moment") and saved nothing.
- **A closed branch may still receive a transfer already on the road to it** (the goods are physically there). The ledger
  normally refuses an entry for an inactive branch; the two entries of a transfer receipt (the receipt and the shortage)
  alone carry a flag (`EntryRequest.allowClosedBranch`, set through `Posting.closedBranchOk`) so the ledger checks that the
  branch exists, not that it is open. The stock then sits in the closed branch, outside the default stock list but in the valuation, and the Branches screen
  shows what a closed branch still holds. The importer refuses a balance row for a branch that keeps no goods and reports
  it like any other refused row.
  `/me` reports `stocks_goods` per branch so the sale, restock and transfer forms skip it, and the default branch avoids it.
- **Permission: the existing `core.branches.manage`** (tenant administrator only, chapter 8). Reads stay `core.branches.read`.
  No permission is added.
- **A Branches screen under Business set-up** lists the branches in plain words with Close, Reopen and the stocking
  switch, a Show closed filter and a Closed badge.

## Consequences

**Better:**
- One status vocabulary; every existing `findActive` caller already treats a closed branch correctly.
- Reversible and audited; the owner sees the stock value before agreeing.
- A head office that keeps no goods can no longer receive stock by mistake, whatever the screen.

**Worse, and to watch:**
- A tenant that already has an inactive branch holding stock will see its default all-branches stock total drop after
  deploy (the branch is now left out unless asked for); the valuation does not change. The stock list says how many
  closed shops are hidden, and the release note says so.
- A closed branch's stock stays on its books until the owner moves it or writes it off before closing; the default stock
  total no longer includes it, while the valuation still does. The warning says so.
- The migration adds three columns to `branches` (expand only). The real `V` number must be set at merge.
