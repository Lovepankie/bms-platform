# ADR-055: Retail roles: staff per shop or store, a Storekeeper role, Purchasing as a role the owner gives, sellers who see only their own figures

## Status

Proposed (2026-10-21, issue #399; the product owner's go of 21 October 2026 on a retail owner's ask of 19 and 20 October). Built in two
slices: slice 1 (branch assignment, the Storekeeper and Purchasing roles, the administrator never limited by branch; migration `V73`) and
slice 2 (sellers see only their own figures; migration `V74`; both renamed to the next free numbers at merge if needed). Builds on ADR-017 (per-permission branch
scope), ADR-020 (the retail Sales role and `retail.profit.read`) and ADR-048 (a Store does not sell). Renumbered at merge if another ADR takes 055
first.

## Context

A retail owner runs shops that sell and stores that only hold stock. The people who work there are not all the same: a seller sells in one shop,
a storekeeper moves goods between the stores and the shops and counts them but never sells or handles money, and the owner wants to choose who
may record purchases and restocks, because that sets buying prices. The owner also does not want a seller to see the business's profit,
margins, buying prices, the value of the stock, or what colleagues sold.

The platform already has what this needs at its core: a user holds roles, each assignment carries a branch (or every branch), the effective
permissions are the union and each permission keeps its own branch scope (ADR-017, `user_role_assignments`, `Grants`). Every retail route
declares a permission and checks the branch of what it reads or writes. Cost and profit fields are absent without `retail.profit.read` in the
row's branch (ADR-020 decision 10, review F5). What was missing was a role for the store, a way to give purchasing alone, a Users screen that
assigns a person to several branches in plain words, a guarantee that the owner is never narrowed by any of it, and a rule for a seller's
own figures.

## Decision

1. **Staff per shop or store is the existing branch scope.** A role that applies in several branches is one assignment per branch, as it
   always could be through `PUT /users/{id}/roles`. No new table and no parallel mechanism: everything a person sees and does through a role
   is limited by the scope of the permissions of that role, checked on the server as before. The Users screen now asks "Where does this
   person work?" with a tick box per shop and store (stores marked as such) or "Every shop and store", and sends one assignment per ticked
   branch.
2. **A Storekeeper role (`retail_storekeeper`, migration `V73`)** holds `retail.stock.read`, `retail.stock.transfer`,
   `retail.stocktake.commit` and `retail.usage.report`: it sends stock from its branches, receives what arrives there (the two-step transfer),
   counts and corrects stock and reports usage and damage, at its own branches only. It holds no sale, price, purchase, cash book, payable,
   document or profit permission, so it cannot sell, set a price, touch money or see a cost. The value of the stock, even at the selling
   price, is absent for it as for a seller (the valuation, the stock summary and stock health give quantities only without
   `retail.profit.read`). **The owner decided on 21 October 2026 that a storekeeper sees no selling prices.** A selling price
   (`sell_minor`) is in a response only for a caller who sells, sets prices or reads cost (`retail.sale.create`,
   `retail.price.edit` or `retail.profit.read`, and the supervisor's `retail.sale.all_sellers`, since it oversees selling; `RetailCatalogue.maySeePrices`; prices are tenant wide, so these permissions count at any branch): the items and one item, the stock list of a branch
   and of every branch, stock by location, the valuation and every item picker; the price history is refused (403). The storekeeper
   reads names, codes, quantities and the stock movements of its branches with their quantities.
3. **Purchasing is a role the owner gives (`retail_purchasing`, migration `V73`)**, holding `retail.stock.read` and
   `retail.purchase.create`, given on top of another role (for example a seller who also buys) at the branches it applies in. By default
   only the tenant administrator holds `retail.purchase.create`, as before. What the owner gives with it, at those branches: recording
   restocks (and purchase orders and goods received with the purchasing capability), reading the restocks, orders and receipts the
   person recorded themselves with their buying prices and totals (the owner's decision of 21 October 2026: without
   `retail.profit.read` the lists and the records answer only one's own), money out of the branch's cash or bank for a restock paid that way, and the item's cost for the whole
   business, since the latest purchase sets it (ADR-020 decision 5). A new selling price applies in every shop, so a restock or a goods
   receipt that sets one also needs `retail.price.edit` (the unchanged price may be sent back, as the restock form does). Supplier
   invoices and payments stay with `retail.payable.manage`. A role, not a per-user permission grant, because roles are the one way
   permissions reach a person today and a second way would be the parallel mechanism this ADR avoids.
4. **The owner and the administrators are never limited by branch or by the new roles.** The tenant admin assignment has no branch: the API
   has always refused one, and `V73` widens any limited administrator row found in the data (revoked where the person already covers every
   branch) and adds the check `user_role_assignments_admin_every_branch`, so the database refuses it too. An administrator therefore does
   everything and sees every shop and store, every aggregation across branches, every KPI, report, export and analysis, Controls, profit and
   cost, whatever branch assignment they have or lack. A further role given to an administrator (a storekeeper role at one store, say) only
   adds to the union and never narrows it, because no check reads a role name: every check is a permission and its scope. Integration tests
   prove an administrator with no branch assignment sees every branch's data and reports, and that adding a branch-limited role to the same
   person changes nothing.
5. **Sellers see only their own figures (slice 2, migration `V74`).** A new permission, `retail.sale.all_sellers`, lets a holder see
   everybody's sales at its branches; the tenant administrator holds it, no other seeded role does. A holder of `retail.sale.read`
   without it (in every branch the figures cover) sees only the sales they made: the sales list and its grouped summary are filtered on
   the seller in the query, one sale and its payments are not found when they are someone else's, a `seller_id` other than one's own
   and the salesperson level are refused, and the reports that add up everybody's sales (the owner dashboard, the sales analysis, the
   delivery fees) are refused with 403; the Retail home shows the seller "Your sales" (today and the last 7 days) from the summary
   instead of the dashboard. Profit, margins, buying prices, costs and the value of the stock are absent without `retail.profit.read`
   (slice 1 extends that rule to the value at the selling price). One exception keeps the till working: the debts still owed
   (`owing` and `overdue`, and opening a credit sale that still owes) are shown whoever made the sale, so whoever is at the till can
   take a buyer's payment. A debt is the buyer's figure, not a colleague's takings; the grouped summary is never widened by it and stays the caller's own sales. The owner decided the rest on 21 October 2026. **The cash book** is hidden from ordinary
   sellers: the Sales role lost its four cash book permissions in `V74`, and a new one, `retail.till.close` ("Can close the till and do
   the banking"), is given per person through the role `retail_till` at the branches the person works in, with those four; every cash
   book route (its lists, reports, savings, banking, expenses and advances) also needs it, checked in code; administrators have it
   everywhere and no one else gets it by default. **A buyer's account** (the account, the statement and its PDF, the payments on
   account, credit control, customer ageing, and opening a credit sale that still owes) stays open to the sellers of its branches so
   they can take payments: its totals include colleagues' sales, which are the buyer's debt, but it never names who made a sale or took
   or reversed a payment (only the owner, holding `retail.profit.read` there, or the person themselves), and cost and profit stay out.
   **The order list** stays the shop's shared work: every seller of the branch sees the orders and their values, but the sale a
   colleague made on delivery is not linked (its id and number are left out, and the history does not name it), and the
   order and its history name only the caller's own steps (who took, moved or delivered it); the payments list of a sale
   names only the caller's own payments. **A Supervisor** (`retail_supervisor`: sale read,
   `retail.sale.all_sellers` and stock read, scoped to its branches) sees every seller's sales and totals, the dashboard and the sales
   analysis at its branches, with no profit, cost, margin, stock value, Users or Controls, and can be combined with the till.
   The order reports (delivery fees, cancellations) are the owner's (403).
   Two consequences to know: the stock list still shows each item's quantity and selling price, so a seller can multiply them for one
   item (a shelf price times a count, never a total over the stock); and imported history, whose `created_by` is the import actor
   (`RetailHistory.IMPORT_ACTOR`), belongs to no seller, so only the owner (or a holder of `retail.sale.all_sellers`) sees it.
6. **Existing users keep what they have.** The administrator and every lending role keep exactly their permissions, the Sales role keeps its
   permissions, and every existing assignment keeps its branch; the only rows `V73` changes are administrator rows limited to a branch, which
   it widens. Slice 2 narrows what the Sales role sees of other people's sales, which is the change the owner asked for.
7. **Plain words.** The Users screen and the invitation carry a "Who can do what" card, one line per role, built from the same words as the
   role pick-list so the two cannot disagree; the invitation offers Purchasing and "Can close the till and do the banking" as tick
   boxes. The Users screen names the roles "Administrator", "Sales person", "Storekeeper" and "Purchasing (records purchases and
   restocks)" with one sentence each, the branches as shops and stores, and says that an administrator works in every shop and store.

## Consequences

**Better:**
- The owner chooses, per person, the shops and stores they work in and what they do there, with the scope enforced where it always was.
- Store staff can do the stock work of a store without selling, setting prices, handling money, or seeing cost or the value of the
  stock; they still read shelf prices and quantities.
- Purchasing can be given to one trusted seller without making them an administrator.
- No owner or administrator can be locked out of a branch or a report by an assignment, by code or by data.

**Worse:**
- A role per branch per person means a person in five shops has five rows; the Users screen groups them, the API lists them. Taking
  off a person's last branch leaves nothing chosen, which the screen will not save, so it never widens them to every branch.
- `V73` widens any administrator row limited to a branch by the system, with an audit row for each (`core.user.roles_changed`, actor `system`, the branch it had) and the
  count in the migration log. The API never allowed such a row, so none is expected outside hand-edited data.
- Purchasing as a role means the owner gives it per branch; a person who buys for every branch gets one assignment for every branch.
- Seeded roles are global: a business cannot rename them or make its own mix; a custom role remains future work.

- Slice 2: a seller loses the owner dashboard, the sales analysis and the order reports (their own sales are on the Retail home
  and in the sales lists); the cash book, the buyer's account and the order list still show branch figures until the owner decides.

**Watch for:**
- The own-sales filter is `created_by` on `retail_sales` with no index of its own: the list and summary scan the branch's sales in
  the date range, as before, and drop the others; add an index on `(tenant_id, created_by, sale_date)` if a busy shop needs it.
- A new retail route must keep declaring a permission and checking the branch of what it touches; a route that reads across branches without
  the scope would leak to a storekeeper or a seller. `RetailRolesIT` is the place to add the new route to the role matrix.
- A future check that reads a role name (for example "is this a storekeeper") would break decision 4; check permissions, never role keys.
