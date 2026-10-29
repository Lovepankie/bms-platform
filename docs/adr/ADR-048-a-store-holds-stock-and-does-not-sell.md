# ADR-048: A store holds stock and does not sell

## Status

Proposed (2026-10-20, issue #366; the product owner decided the behaviour on 20 October 2026, the ADR is not yet accepted). Builds on ADR-041 and ADR-033. The migration is `V65` (merge order: #349 `V64`, this one `V65`, #359 `V66`, #361 `V67`). Amended by ADR-089 (a branch is
added from the app as a shop, a store or a head office, with its code made from the name when none is typed). Amended by ADR-104
(the ADR-033 role `store` is folded into this flag, migration V115; the paragraph "Relationship to branch roles" is superseded).

## Context

A shop owner keeps stock in a back room, a warehouse or a second building that nobody sells from. It receives purchases, sends stock to the
shops and takes stock back, but it has no till. Today a branch is either a place that can sell or, since ADR-041, a branch that keeps no goods at
all (a head office that only keeps accounts). There was no word for the branch in between. Two things already half-covered it: ADR-033 branch roles
(`store`, `shop`, `both`), which exist only while the `branch_roles` capability is on and are read by the sale path alone, and the stock
transfers between branches (V22) that never cared what a branch was. The owner wants one plain choice on the Branches screen, "Store (does not
sell)", that works for every business without switching a capability on.

## Decision

- **A Store is a branch flag, `branches.sells_goods boolean NOT NULL DEFAULT true` (migration V65).** A branch with `sells_goods = false` holds
  stock, receives purchases (restocks and goods receipts) and takes transfers in and out, and does not sell. Every existing branch keeps the default,
  so nothing changes until an administrator marks one. The flag sits beside `stocks_goods` (ADR-041) and is changed in the same call
  (`PATCH /branches/{id}`, `sells_goods`, also accepted when a branch is created).
- **A Store must keep goods.** A check constraint `branches_store_stocks CHECK (sells_goods OR stocks_goods)` holds the rule in the database, and the
  service refuses the combination first with 422 `store_must_stock`. What remains is a shop (sells and stocks), a Store (stocks, does not sell) and the
  head office that keeps accounts only (ADR-041).
- **What a Store refuses.** A sale (and its price resolution and a quotation, and converting a quotation), a payment taken at its till (a payment on a credit
  sale made there, and a payment on account received there), a refund paid out of its till (a customer return whose money leaves the branch) and the cash
  book kinds that take money in or move it through a till (savings, bankings, withdrawals and advance repayments) are refused with 422
  `branch_does_not_sell`, in plain words, enforced on the server in `RetailBranchContext.requireSells`. The check reads the branch row `FOR SHARE`
  in a write transaction exactly as `requireStocks` does, so it serialises with the administrator's change. A return restocked into a Store is
  allowed (the goods go on its shelves) and a void stays allowed (the sale exists). A refund that would leave a Store's till is refused when a store is
  named; when the sale itself was made at what is now a Store, the server uses the one branch the caller may sell at if there is exactly one, and otherwise
  refuses with 422 `refund_branch_required` asking the caller to choose a shop. The screen preselects and sends the only shop and drops "The usual branch".
- **The cash book at a Store.** A Store keeps no cash takings: savings, bankings, withdrawals and advance repayments (any money in) are refused with
  `branch_does_not_sell`. **Expenses are allowed**, because a store may pay transport or casual labour; an advance paid out is money out and stays allowed too.
  Voids of anything already recorded stay allowed. The Branches screen says so: "Nothing is sold here. No takings, savings, banking or repayments here; expenses can still be paid." The importer still brings historical sales made at a branch that is now a Store: they are history, not a till being used.
- **What a Store does.** Restock, goods receipt, purchase order, transfer in and out, stock-take, usage and damage work as for any branch with
  `stocks_goods` true. Shop to store, store to shop and store to store transfers need no new code: transfers were always branch to branch (V22), and
  integration tests now prove each direction.
- **Reports.** The stock lists, the valuation and the stock-by-location report show a Store like any branch (stock per store); the sales reports show no
  sales for it, because there are none.
- **Marking an existing branch as a Store is refused (422 `branch_has_open_credit_sales`) while a credit sale made there still has a balance owing**, since
  the payments on it are taken at the till that stops selling. Imported (historical) credit sales are not counted: nothing can be paid on them (#360), so they
  never block a branch becoming a Store. Credit sales that were written off (#349, ADR-043) are left out too: the debt is gone. The retail module reports it through the core port `core.tenancy.BranchSellingCheck`, so the
  core names no vertical. A day's cash is never "closed" in this product (the cash book is read by date and not locked), so no cash-day check exists.
- **`/me` reports `sells_goods` per branch.** The sale form, the default branch the staff area starts on and the till skip a Store; restock, goods
  receipt, transfer and stock-take forms include it. A Store is still listed, and the user's last choice is honoured, so stock work is never blocked.
- **Relationship to branch roles (ADR-033).** The role `store` is a retail setting behind a capability that is off by default; the flag is a property of
  the branch that applies to every tenant and is always enforced. Both refuse a sale, with different codes (`branch_role_forbids_sales`,
  `branch_does_not_sell`). A later change may fold the role into the flag; this ADR leaves the role as it is. *Superseded by
  ADR-104: the role `store` is now this flag, and only `branch_does_not_sell` is answered.*
- **Permission: the existing `core.branches.manage`.** No permission is added. The refusal applies to every caller, administrators included.

## Consequences

**Better:**
- One plain choice for the owner, no capability to switch on, and the database itself refuses a Store that keeps no goods.
- Stock in a back room or warehouse is visible per store and moves by the transfers already in use.
- No sale, payment or refund can be recorded at a till that does not exist, whatever the screen shows.

**Worse, and to watch:**
- Voiding a cash sale made before the branch was marked takes the cash out of the Store's figures, and reversing a payment on account can make a Store owe
  again; neither is blocked, and "Sells again" on the Branches screen is the way to settle it.
- The Branches screen shows the ADR-033 role beside the Store flag ("Branch role: store") so the two are not read as one; a follow-up folds the role into the flag.
- Two ways now say "this branch does not sell" (the ADR-033 role and this flag); a tenant that sets both sees whichever refusal fires first.
- A credit sale still owing at a branch blocks marking it; the owner has to let the buyers pay or settle them, or choose another branch.
- A refund for an old sale made at what is now a Store must name a shop as the paying branch.
- The migration adds one defaulted column and one check to `branches` (expand only). The number follows the merge order above.
