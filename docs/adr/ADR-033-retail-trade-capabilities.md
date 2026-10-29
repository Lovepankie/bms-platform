# ADR-033: Wholesale support as optional per-tenant capabilities of the retail module

## Status: Accepted

Proposed 2026-10-11 (issue #190), accepted on merge. Builds on ADR-020 (retail vertical) and keeps
its decision 11 (retail is generic). Design: `docs/specs/trade-capabilities.md`.

## Context

A wholesaler needs to run on the retail module: purchases go into several stores, all stores feed
one shop by stock transfer, and only the shop sells to customers. It also needs pack sizes, price
lists for trade customers, credit accounts with terms, and sometimes invoices, quotations, delivery
notes, purchase orders and landed costs. A retail shop that buys from that wholesaler uses none of
the documents, and the existing retail app has none of them. Plain shops already run on the module
(three shops, no stores, no documents) and must not change.

Options: a second vertical for trade; a copy of the module with extra fields; or optional
capabilities inside retail.

## Decision

1. **No second module.** Trade support is a set of optional capabilities of `retail`, each a
   boolean in the tenant settings group `retail.capabilities`: `branch_roles`, `units`,
   `price_lists`, `customer_accounts`, `returns`, `documents`, `purchasing`, `transfer_receipt`. Absent means
   off. Off means behaviour exactly as today, and data written while on is kept.
2. **A tenant profile (`shop` or `trade`) is a preset of the flags, not a mode.** The module reads
   flags only. `shop` is the default and switches nothing on. Changing flags needs
   `core.settings.manage` and is audited. The core gains one vertical-neutral hook, a module settings group contract that the retail manifest supplies (SDD 5.4.3 style registry), because settings and `/me` are core and core may not depend on retail. The enabled capabilities travel in `/me` so the PWA's
   menus, tiles and tours follow them (issue #185). The administrator switches the profile and the flags on a Retail settings screen that reads the change back before it is saved (issue #192).
3. **A branch has a role, `store`, `shop` or `both`, in a retail table `retail_branch_roles`; a
   missing row means `both`.** A store never sells (the service refuses, 422
   `branch_role_forbids_sales`); a store and a shop both restock and transfer. There is no "can
   receive purchases" flag: restocking is the action, permission and branch scope decide who may,
   and the role only removes what a branch cannot do. The core `branches` table is not changed. `retail.stock` owns branch roles and capability reads; sales and purchasing depend only on stock and catalogue for them.
   *Superseded in part by ADR-104 (issue #371, migration V115): a store is the branch flag `sells_goods = false` of ADR-048, the
   role keeps only `shop` and `both`, a Store reads as `store`, and a refused sale answers `branch_does_not_sell`.*
4. **The retail price stays the product's `sell_minor`.** Named lists, quantity breaks, effective
   dates and customer groups add to it; a sale with no list is priced as today. The below-cost rule
   and cost visibility apply to the resolved price.
5. **Stock stays in the base unit.** Packs convert at the edge; quantities are never rounded (a
   non-representable quantity is refused); money rounds once per line. A pack cost that does not
   divide exactly posts its remainder to a system account `purchase_rounding`.
6. **Customer accounts add a limit, terms, ageing and a statement over the existing credit buyers
   and credit sales.** Limit and terms are set only with `retail.credit.manage` (administrators); with the capability on, a credit sale needs a customer record and locks the customer row before checking the limit. Customer returns and credit notes are a separate capability, `returns`. Nothing is posted for imported credit sales; issue #96 stays the business's
   decision and no part of this design depends on its outcome.
7. **Documents are an optional after-step.** Invoice, delivery note and quotation are templates of
   one immutable snapshot record with a gapless numbered series per tenant and type. A sale never
   waits for, depends on or fails because of a document. The till receipt remains the default.
   PDF rendering is later.
8. **Optional purchasing sits beside plain restock and never replaces it.** Purchase order, goods
   received, supplier invoice, supplier credit and supplier payment post through goods received not
   invoiced; landed cost is allocated to receipt lines and sets the product cost by the existing
   latest-receipt-wins rule (weighted average cost stays pending ADR-021; since settled by ADR-079, where the latest receipt
   sets only the last price paid and each branch's average is re-averaged).
9. **Transfers stay atomic by default.** With `transfer_receipt` on they are dispatched and then
   received, and inter-branch clearing is the value in flight.
10. **Build in ten slices, the first (branch roles) alone enough for the wholesaler flow.**

## Consequences

**Better:**

- One module serves plain shops and wholesalers; a second retail tenant of either kind is settings
  only (ADR-020 decision 11).
- Plain shops change nothing: every capability defaults off and a contract test proves the existing
  retail behaviour on a tenant with all of them off.
- The first slice delivers the stated wholesaler flow without documents, price lists or purchasing.
- The core gains one small hook; the `branches` table and every vertical-neutral table are untouched, and no new component is added to the architecture model.

**Worse:**

- Capability combinations multiply test paths; the cost is carried by an all-off contract test and
  component tests per combination.
- Two replaced constraints (the transfer status and entry-pair checks and guard, the stock movement kind check) need the NOT VALID then VALIDATE pattern.
- The sale path gains conditional branches, kept off the hot path when capabilities are off.
- Pack cost rounding and late landed cost produce small, visible differences (an expense account and
  the existing revaluation difference) instead of being exact.

**Watch for:**

- A feature added later that quietly assumes a profile instead of a flag; the module must read flags
  only.
- A document becoming required by a screen or a rule; documents must stay an after-step.
- Imported credit sales: do not post them from this work; wait for issue #96.
- Migration numbers: assigned at merge time, above the highest on any open branch; each slice updates
  the migration order test (issue #172).

## Amendment of 2026-10-12 (issue #193): the two-step transfer, as built

Decision 9 is built in slice 3 (migration V38; `docs/specs/trade-capabilities.md` sections 9.3 and
10). The choices it left open:

1. **One route, no mode.** With `transfer_receipt` on, `POST /retail/transfers` dispatches (status
   `in_transit`); with it off the transfer is one step as before. Existing rows are all `completed` or
   `voided` and satisfy every replaced rule; nothing is switched on by the migration.
2. **Five rules replaced, not three.** V22 tied the status, the entry pair, the void columns (two
   checks) and the update guard to a two-status world. V38 replaces all five: the status check, the
   entry-pair check, both void-column checks (`voided_at`, `voided_by` and `void_reason` now belong to
   `cancelled` as well as `voided`) and the guard. Each new check is added `NOT VALID`, validated and
   only then is the old one dropped; the transfer table holds a few rows a day, so validation is in the
   same migration. The guard allows exactly `in_transit` to `received`, `in_transit` to `cancelled`,
   `completed` to `voided` and `received` to `voided`, each once; the transfer itself never changes.
   The lines gain `received_qty`, settable once while in transit.
3. **A transfer on its way is cancelled, never voided.** Cancel returns the lines to the source at the
   original cost (a movement that reverses the dispatch movement) and reverses the source entry; the
   destination never booked anything. Void keeps its V22 meaning for `completed` and `received`
   transfers; on a received transfer with a shortage it reverses the shortage entry too, so the
   transfer is undone as a whole.
4. **The shortage is its own entry at the destination, at the source cost.** Debit `stock_shrinkage`,
   credit `interbranch_clearing`; the receipt entry carries what arrived. Together they clear what the
   dispatch debited, so the clearing legs of a transfer net to zero whatever the product cost is by then.
5. **In flight is built from the transfers.** Account 1190 is shared with lending, so its balance is
   never the in-flight figure; the reconciliation reads the net of clearing over entries whose source is
   a retail transfer (`LedgerAccounts.balanceBySource`) and compares it with the open dispatches.
6. **Who acts.** The destination's scope receives, the source's scope cancels, both under
   `retail.stock.transfer`; no permission is added. Switching the capability off is refused while any
   transfer is in transit.

**Better:** the wholesaler's store-to-shop flow can carry stock in flight with a clear owner for each
step, and plain shops see nothing new. **Worse:** a shortage is visible in the ledger (stock
shrinkage) but not yet in the daily profit report, which reads sales, usage and stock-take
differences only. **Watch for:** any later change to the transfer table must extend the guard's list
of transitions and its test (`RetailTransferGuardIT`) in the same migration.

## Amendment of 2026-10-12 (issue #194): units of measure, as built

Decision 5 is built in slice 4 (migration V39; `docs/specs/trade-capabilities.md` section 4). The choices it
left open:

1. **The edge is the line.** A sale line or a restock line names a pack `unit_id` and enters the quantity and
   the price or cost in that unit. The server converts once: the stock movement, the balance, the cost
   snapshot and every existing report stay in base units. The line keeps the unit, the quantity, the price and
   the factor in force, in four nullable columns; null means "entered in the base unit", which is every row
   that existed and every line of a tenant with the capability off. Naming the base unit is the base unit.
2. **Quantities refuse, money rounds.** `qty x factor` must be exact to three places and fit the column, or
   the line is refused (422 `quantity_not_representable`); a pack price is `sell x factor` and a pack cost
   per base unit is `pack cost / factor`, both rounded half up once; all of it is `BigDecimal` and `long`.
3. **The price floor is judged on the line.** A pack line is below cost when its line total is not above its
   line cost; the rounded price per base unit is never compared, so a carton of twelve costing 83 a piece is
   accepted at 997 although 997 / 12 rounds to the cost.
4. **The remainder has one home.** A restock priced per pack posts the amount as entered (cash, bank or the
   supplier) and the movements at the rounded cost per base unit; the difference per branch posts to the new
   system account `purchase_rounding` (5120) in the branch's own entry, a debit or a credit, nothing when the
   cost divides exactly. The account is added to the retail chart for existing retail tenants by the
   migration, and stays at zero until a restock names a pack.
5. **Packs belong to the catalogue.** `retail_product_units` is catalogue data (a unit and a factor per
   product, one per unit, switched off rather than deleted). A factor is at least 1: a pack holds at least
   one base unit, so the smaller unit is the base unit and the larger the pack (a fraction such as a carton
   base sold by the piece at 0.083 would drift stock). Factors are relative to the base unit, so while a
   product has an **active** pack its base unit cannot change, and a unit that an **active** pack uses
   cannot be switched off (422 `unit_in_use_by_pack`); a pack switched off ties nothing down. Switching a
   pack on again must carry the factor (422 `factor_required`), and the unit must be active and no longer
   the item's own unit, because the base unit may have changed while the pack was off. No permission is
   added.
6. **Expand only.** One table with forced RLS, eight nullable columns, and checks and foreign keys added
   `NOT VALID` then validated in the same migration. Flyway runs the file in one transaction, so this does
   not shorten the lock: the exclusive lock on each line table is held through the validation scan to the
   commit. That is accepted because the new columns are null on every existing row and the line tables of a
   small or medium business are modest; a very large tenant would move the validation to a later migration.
   The checks also tie `qty` to `sale_qty x unit_factor` (and `qty_total` to `unit_qty_total x unit_factor`)
   so a line cannot say one thing in each unit. The covering index of V26 is untouched; the sale line read
   statements in `scripts/db-bench/queries` were updated to the new SQL, but the 25-times benchmark itself
   (k01 to k15, needs Docker and about 3.6 GB) was not run for this change: no column is indexed, no existing
   statement of the daily profit or analytics reports changed, and the new columns are nullable, so those
   plans cannot move; the benchmark should be run on a Docker host before the first tenant switches
   `units` on. A database at V37 holding sale and purchase lines is a migration test.
7. **One sale, many lines of an item.** A sale may hold a carton line and a loose-pieces line of the same
   item (the sale screen allows a second line of an item while the item has pack sizes).

**Better:** a wholesaler can buy by the carton and sell by the carton or the piece against one stock figure,
and nothing a plain shop reads has changed meaning. **Worse:** a pack cost that does not divide shows as a
small expense or income in `purchase_rounding`, and a factor with decimals makes the pack price a rounded
figure. **Watch for:** price lists (slice 5) key items by unit and must resolve a pack price before the
default `sell x factor`; the product CSV import skips an existing code, so packs cannot be added to existing
items by import, only by the pack routes.

## Amendment of 2026-10-12 (issue #195): price lists, as built

Decision 4 is built in slice 5 (migration V41; `docs/specs/trade-capabilities.md` section 3). The choices it left open:

1. **The server prices the line, in a fixed order.** The list is the one the sale names (a list other than the customer's own
   or the customer group's needs `retail.pricelist.use`), else the customer's own, else the customer group's, else none; a list
   that is switched off is passed over. In the list the item is the one for the product and the sale unit with the largest
   quantity break not above the line's quantity, valid on the sale date (a break applies to the whole line, never in tiers).
   No item takes the retail price of that unit (`sell x factor` for a pack), unless the list is strict, which refuses the sale
   with 422 `no_price_in_list`. A pack is priced from its own item only: a piece item never prices a carton.
2. **An item is a fact with a date range, never edited.** `retail_price_list_items` keeps a price per list, product, unit and
   break, with `valid_from` and an inclusive `valid_to`. The one change a row takes is its `valid_to`, once, by a guard trigger;
   deletes are refused. A new open ended price takes over from the current one on its first day; anything else that overlaps is
   409 `price_item_overlap`, checked under a row lock on the list so concurrent edits serialise (an exclusion constraint would
   need the `btree_gist` extension, and the unique index on the open ended row is the database's backstop). A price may not
   start in the past, because a sale already made keeps its price and back-dating gains nothing.
3. **History never changes.** The line stores how it was priced: `price_source` (`retail`, `list`, `override`), the list, the
   break that applied, the price the system resolved per sale unit and the override reason, in nullable columns checked to be
   all null or one of three shapes. The sale stores the list in force. Editing, ending or switching off a list therefore touches
   no sale, and a sale made while the capability is off writes nothing new.
4. **A different price is an override with a reason.** A typed `unit_price_minor` that differs from the resolved price needs
   `retail.price.override` (403) and a reason of at least five characters (422 `override_reason_required`); the same price typed
   back is not an override. The permission is seeded to every holder of `retail.sale.create`, so nothing changes until an
   administrator removes it, and it is consulted only while the capability is on. The price floor judges the charged price on
   the line, as for packs; a list item below cost can be saved and is flagged, only to a caller with `retail.profit.read`.
5. **Lists and groups are administrator work.** `retail.pricelist.manage` creates and edits lists, items and groups and sets a
   buyer's group and own list (`PUT /retail/customers/{id}/pricing`: choosing a buyer's list is choosing what that buyer pays, so
   it is not part of `retail.customer.manage`, which edits name and contact). `retail.pricelist.use` goes to administrators
   only. A list and a group are switched off, never deleted. The buyer's own list wins over the group's.
6. **Expand only, and the lock is honest.** Three tables with forced RLS, two nullable columns on `retail_customers`, one on
   `retail_sales`, five on `retail_sale_lines`, and three permissions. The foreign keys and the check on the existing tables are
   added `NOT VALID` and validated in the same migration. The lock cost is this, and not the milder one an earlier draft
   claimed: `ADD COLUMN` and `ADD CONSTRAINT ... CHECK` take an access exclusive lock, a foreign key takes share row
   exclusive, and Flyway runs the file in one transaction, so every one is held to the commit. Sales, sale history and the daily
   profit report on `retail_sale_lines`, `retail_sales` and `retail_customers` therefore wait for the migration; at the size
   of a small or medium business that is brief. The validation scan is kept because it is one pass over rows whose new columns
   are all null, it blocks nothing the `ADD COLUMN` lock did not already block, and a constraint left `NOT VALID` stays
   unvalidated for good. A very large tenant would add the columns in one migration and validate in a later one. The covering
   index of V26 is untouched. The 25-times benchmark (k01 to k15, needs Docker) was not run for this change: no column is
   indexed on a sale table and no existing report statement changed.
7. **Cost stays out.** Resolution returns the price and its source, never the cost; an item carries `below_cost` only for a
   caller with `retail.profit.read`; the refusal for a price at or below cost carries no figure.

**Better:** a wholesaler prices by customer, group and quantity without copying a price, and every sale says which price applied
and why. **Worse:** a typo in a price that starts today is fixed by ending it and adding the corrected one from tomorrow, or by an
override with a reason, because an item takes no other change; a promotion inside an open price needs that price ended first.
**Watch for:** an item prices one unit, so a product's base unit cannot change while a list prices it (422
`unit_in_use_by_price_list`); a pack switched off leaves its items in the list, unused; documents (slice 8) must read the line's
stored price and never resolve again; and quotations (slice 8) re-price through the normal sale path.

## Amendment of 2026-10-13 (issue #196): customer accounts, as built

Decision 6 is built in slice 6 (migration V44; `docs/specs/trade-capabilities.md` sections 5 and 11). The choices it left open:

1. **The limit is checked under the buyer's row lock, before any stock lock.** A credit sale with the capability on locks the
   customer row (`SELECT ... FOR UPDATE`) first, then compares exposure with the limit, then inserts the sale and moves stock,
   so two shops selling to one buyer queue on the row and the second sees the first one's sale. Exposure is the tenant-wide
   balance of completed credit sales, imported ones included, plus the sale. Equal to the limit is allowed. The refusal names
   the amount over the limit and nothing else, so no other branch's sales leak. A holder of `retail.credit.override` at the
   sale's branch goes ahead with a reason of at least five characters, which is written to the audit trail with the exposure
   and the amount over. A credit sale names a customer record (422 `customer_required`); a typed buyer name alone is refused.
2. **Limit and terms are columns of the buyer, set by `retail.credit.manage` only.** `credit_limit_minor` and `terms_days`, both
   nullable (null is no limit and no terms), under `If-Match`; `retail.customer.manage` still edits name and contact only. Terms
   default a credit sale's due date when none is typed. A buyer in the list carries the two only while the capability is on, so
   with it off no response changes.
3. **Payment on account is a receipt with allocations, built on ordinary sale payments.** `retail_account_payments` holds the
   receipt (the receiving branch, the amount, the method, `oldest_first` or `manual`, the ledger entry); each allocation is an
   ordinary `retail_sale_payments` row, linked from `retail_account_payment_allocations`, so every reader of sale payments
   keeps working and no ALTER touches the large sales tables. The allocation skips imported sales and sales outside the caller's
   scope, refuses what cannot be placed (422 `payment_exceeds_allocatable`, with how much can) and holds nothing as a deposit.
   One ledger entry posts in the receiving branch with a credit to trade debtors for each sale, so cash lands where it is held. It debits the seeded retail account of the method by system key (`cash_on_hand`, `mobile_money`, `bank`) as every retail posting does, and does not read the `payment_methods` mapping (ADR-026: retail keeps its accounts until #236); the mapping already refuses to move `cash` or `bank` elsewhere while retail is on. *Amended by ADR-105: the debit to `cash_on_hand` or `bank` now goes to the account `cash` or `bank` is mapped to at the receiving branch, and the refusal is gone.*
4. **Reversible as a whole.** `POST .../payments/{id}/reverse` (`retail.sale.void`, a reason) reverses the entry on the business
   day and gives each sale its paid amount back; there is no partial reversal. The V14 guard on `retail_sales` allowed
   `paid_minor` only to rise; it is replaced (a function body, no table lock) so that it may fall only inside the reversal, by exactly that
   allocation. The reversal sets a transaction-local setting
   (`bms.reverse_account_payment`, `set_config(..., true)`) to the id of the receipt for the one statement that gives the amount back, and the guard
   accepts a lowering only for a reversed receipt that allocated to that sale, by exactly its allocation, so a plain UPDATE is refused as it was before V44. The receipt is changed once, `posted` to
   `reversed`, by a guard trigger. The reason is at least five characters once trimmed, on the screen and on the server alike.
5. **The cash book follows the receiving branch.** A cash payment on account is takings of the branch that received it, on the day
   it was received; a reversal is taken back on the day of the reversal, as a voided cash sale is.
6. **The statement does not claim a ledger figure it cannot support.** It is built from sales, sale payments and receipts in the
   caller's branch scope: `closing_minor` is what the ledger holds as trade debtors for those sales, imported credit sales are listed
   apart as "before system start", and `balance_minor` adds them, so it equals the customer balance in the same scope. Nothing
   is posted for imported sales (issue #96 stays the business's decision). The statement as a PDF is part of the documents slice.
7. **Ageing is the credit control report's ageing.** The same completed credit sales and the same buckets (not yet due, 1 to 30,
   31 to 60, 61 to 90, over 90 days past the due date, on the business day), per buyer with a record, with the limit and terms;
   the credit control report and screen carry the limit while the capability is on.
8. **Expand only, and the lock is honest.** Two nullable columns and two checks on `retail_customers` (one row per buyer, so the
   access exclusive lock is brief), two new tables with forced RLS, two permissions. Creating the tables takes share row
   exclusive on the tables they reference until the commit, which blocks writes, not reads, for the length of the file. Flyway runs
   one transaction, so every lock is held to the commit.

**Better:** a wholesaler can sell on credit within a limit it controls, take money that is not tied to one sale, undo a mistake,
and read a statement that agrees with the books. **Worse:** a change of allocation is a reversal and a new payment, not an edit; the
paid amount of a sale can now fall, under a database rule that ties it to the reversal path and to the unreversed payments. **Watch for:** any new reader
of `retail_sale_payments` must leave out the payments of a reversed receipt (join the allocations and the receipt status), and
take cash by the receiving branch of an account payment; a later returns slice that refunds against the balance must go through the
same statement lines.

## Amendment: customer returns and credit notes (#201)

Decision 6's neighbour, the returns capability, is built in slice 7 (migration V45; `docs/specs/trade-capabilities.md` sections 5.3 and 11). The choices it left open:

1. **A return is its own record and the credit note.** `retail_sale_returns` and `retail_sale_return_lines`, append-only apart from one void, against a sale line each, in base units with the unit they were entered in. The sale is never edited. The credit of a line is its share of what the sale line was charged (the resolved price of decision 5 is stored there), rounded half up once, the last piece taking the remainder; the cost reversed is the same share of the line's cost snapshot. The number is `CN` + branch code + six digits from a sequence per tenant and branch, gap free like the receipt numbers.
2. **Stock back at the original cost, or written off.** A restocked line is a `return` movement valued from the same rounded share of the line's cost snapshot the journal takes, into any branch or store of the caller's scope (a pack that is no longer sold cannot be returned into); damaged goods never enter stock and their cost posts to shrinkage. This is a deviation from the spec's "a usage report instead": a usage report would need the goods to enter and leave stock in one transaction, there is no usage list in the module, and the return already records the write-off with its reason and cost; the shrinkage section of stock health, which reads usage reports, does not include it. Restocking in another branch posts one entry there through inter-branch clearing, as a transfer does. The movement kinds are not changed (no ALTER of the largest table): the void of a restock is an `adjustment` movement that reverses it, refused when the goods are gone.
3. **The balance is reduced through the path the guard already trusts.** An account refund raises `paid_minor` by `min(return, open balance)` (the rest is paid in money), so every reader of the balance is right without a change. Lowering it again, for a void, needs the same shape of proof as the reversal of a receipt: the V45 guard accepts it only for a voided return of that sale, named in the transaction-local setting `bms.void_sale_return`, by exactly the credit it gave, and never below the sum of the unreversed payments and the credits of returns that stand. The V44 binding to a reversed receipt is unchanged and its floor now counts those credits too, so the guard is only stricter. Money goes back to a credit buyer only up to the money that came in; a payment on account whose sale has money refunded cannot be reversed until that return is voided.
4. **A large return needs a second person only when the tenant says so, and the default is open.** The permission `retail.sale.return` is the administrator's. The tenant's approval threshold for `retail_sale_return` is applied to the returns of a sale together (the returns that were not voided, with this one), so splitting does not get round it; at or above it the return is a maker-checker action (ADR-015), executed in the approval transaction against the sale as it is then, and voiding a return that was approved or is worth the threshold alone is the action `retail_sale_return_void`, with the same checker rule. With no threshold set there is no checker for either: one administrator can refund up to the whole of a sale in cash. That is the default because the code cannot invent a currency amount for a business it does not know, the other optional thresholds of this ADR are off until set, and a one-administrator business must be able to take goods back; the cost is that the control is off until someone turns it on, so the retail settings screen carries a warning while returns are on and no threshold is set. Money leaves the till of a branch the caller names inside their scope (the receiving branch of the spec), through inter-branch clearing when it is not the sale's.
5. **Books and reports on the day of the return.** The entries are dated the business day of the return, in the tenant's zone; the cash book takes a cash refund out on that day (with the day's voids) and a voided return's cash back on the day of the void; the daily profit, the sales analysis, the margins, the dashboard and the evaluation net a return on its day, so a day already reported keeps its sales. They keep counting returns when the capability is switched off.
6. **Expand only, and the lock is honest.** Two new tables (a return is born completed: an insert trigger refuses a row inserted voided), one helper function, a new body for the sales guard (no table lock), the chart account 4110 and one permission; the seed and the permission come first in the file and the tables last, to hold the locks the tables take for the least time one transaction allows. The seed is not a V46 of its own: it never touches `journal_entries`, whose share row exclusive lock is the one that matters, so a second file would not shorten it. No ALTER touches `retail_sales`, `retail_sale_lines`, `retail_sale_payments` or `retail_stock_movements`. Creating the tables takes share row exclusive on the tables they reference until the commit, which blocks writes, not reads, for the length of the file; Flyway runs one transaction, so every lock is held to the commit.

**Better:** goods can come back against a sale in any pack, to any branch or to the bin, the customer is repaid in money or off their balance, and the books, the cash book and the reports agree on the day. **Worse:** `paid_minor` now means settled, by money or by a credit note, so a reader that wants only money must use the payment rows; a return of a sale cannot be edited, only voided. **The plans are not unchanged.** The returns half adds scans of two small tables and an index scan of `retail_sales`, and the lateral join one more node; the statements read more pages and plan a little longer. Measured with `EXPLAIN (ANALYZE, BUFFERS)`, median of seven runs, on a scratch PostgreSQL 17 sized like the HATS tenant (21,600 sales, 43,200 sale lines, 800 items, 3 branches): with no returns the month and year statements of the sales analysis, the dashboard, the evaluation, the daily profit and the margin impact took 16.6 to 74.1 ms before and 17.2 to 76.2 ms after (0 to 4 percent; planning 0.7 to 1.6 ms before, 1.2 to 2.3 ms after; pages read identical); with 3,000 returns (a seventh of the sales) they took 16.7 to 73.3 ms before and 17.8 to 80.1 ms after (+1 to +7 ms, 7 to 24 percent; pages 1.1 to 2 times, 825 to 1,549 for the month of sales analysis). Nothing changes shape for the worse (no index scan became a sequential scan or the reverse). The reports that net returns were measured on a scratch PostgreSQL 17 (one tenant, 1.2 million sale lines, 8,000 returns; not the 25 times data of db-bench, which needs Docker): the month statements of the sales analysis took 243 and 234 ms before and 225 and 247 ms after, and the price change impact statement 512 ms before (as on main, the changed items read twice) and 560 ms after; sale lines have no index on the product, before or after. The returns are a separate arm joined to a small table (`NET_LINES` for the statements that scan a window, a second arm beside the unchanged sales arm for the margin join), and the db-bench copies (`f03` to `f06`, `k01`, `k02`, `k05`, `k06`) are updated; they have not been run on the 25 times data. **Considered and not built:** a cap on what one person may return in a day across sales (a per-user daily limit as a setting). The threshold counts the returns of one sale, so many small returns on different sales need no second administrator; the settings warning says so. A daily cap needs a settings schema entry and a sum over the day by user, and belongs to a follow-up if the business asks for it. **Correction to the V45 header:** the tables' foreign keys also lock `retail_customers` (share row exclusive, as for the other referenced tables), and the file also creates the trigger functions `retail_sale_returns_guard_insert` and `retail_sale_returns_guard_update` and the helper `retail_sale_settled_minor`, which lock nothing; a merged migration's comment is not edited because its checksum is checked. **Watch for:** any new reader of sale lines that reports sales must join the returns (the shared `NET_LINES` of `SalesAnalysisRepository`), and any new writer of `paid_minor` must keep it between `retail_sale_settled_minor` and the total.

## Amendment of 2026-10-13 (issue #197): optional documents, as built

Decision 7 is built in slice 8 (migration V46; `docs/specs/trade-capabilities.md` sections 6 and 10). The product owner's rule
sets the shape: a sale is a till receipt, and a wholesaler needs a quotation, an invoice and a delivery note but not always (his
buyer buys into its own shops from a wholesaler whose system never had waybills), so documents are optional, off by default and
never in the sale's path. The choices the design left open:

1. **Series per tenant, type and branch, prefix configurable.** The spec said one series per tenant and type; the owner asked for the
   branch in the number, as ADR-034's quality bar item 5 already does for receipts. A number is `<prefix>-<branch code>-<six digit
   counter>` (`INV-HQ-000001`). The counter row is `retail_document_series (tenant, type, branch)`, locked with `SELECT ... FOR UPDATE`
   in the same transaction that writes the document, so a failed issue rolls the counter back and a series has no gap (ADR-034's rule
   for receipts, not the rule for statements, which are numbered by date). A guard trigger makes the counter rise by exactly one and
   never fall or disappear. The prefix is per tenant and type (`retail_document_prefixes`, default QT, INV, DN), changed by
   `core.settings.manage` and audited; it changes later numbers only.
2. **One snapshot, immutable, void by derivation.** `retail_documents` is append-only for every role and holds the snapshot JSON
   everything the document shows (business, branch, buyer, lines as sold with the pack unit and the base units it came to, the price
   list, totals, the user who issued it). Nothing updates a document: it reads as void when its sale is voided, its stock move
   cancelled or voided, or its quotation cancelled, so the void path of a sale does not touch documents at all and the number stays.
   A void document is never printed clean (review of #252): no new copy of the issued PDF is made for it, the copy stored before the
   void is no longer served (the document access refuses it, 403), and the first request after the void renders and stores a second
   immutable version with a VOID banner on every page (stored under the number with ` VOID`, said in words: its sale was voided, its
   stock move cancelled, the quotation cancelled). The list and the document carry only that version's `pdf_document_id`.
3. **Two steps, so nothing slow runs under a lock.** The first is a short write transaction (series lock, a second look because
   issuing is idempotent by sale and type, the number, the row). The second renders the PDF from the snapshot with no connection
   held, uploads it, and under an advisory lock on the document only looks again and records it, using the same `core.documents`
   calls as the loan documents. A render that fails (503 `documents_busy` or any other failure) leaves a numbered document with no
   PDF; the same request, or `POST /retail/documents/{id}/pdf`, renders it from the same snapshot. A sale never waits for any of it.
   Issuing takes a share lock on the sale's row first, so a void that has not committed yet finishes before the invoice looks at the
   sale, and a void that starts after waits for the document: no invoice is numbered for a sale that is being voided.
4. **Quotation.** Priced by the sale's own pricing code (units, price lists, the override rules, the price floor), so an offer is what
   a sale would charge. It reserves nothing and posts nothing, cannot be edited (a change is a new quotation), is cancelled once or
   converted once. Conversion runs the ordinary sale path in one transaction, so every rule of a sale holds; the prices are
   today's unless the quoted ones are kept, and a kept price that differs is a typed price on a sale (needs `retail.price.override`
   and a reason while price lists are on). The invoice of the new sale is optional and names the quotation. A capability switched
   off since the quotation was made is not an error (spec 14.1): with price lists off the lines are priced at the product's price and
   no list is sent, with pack sizes off a line quoted in cartons is sold in single units (at the price per unit the carton came to
   when the quoted prices are kept), and the answer says so in `note`.
5. **Delivery note.** Quantities in the unit sold (with the base units a pack came to), prices off unless the issuer asks, a checked
   column and a signature block for the receiver. Also issued for a stock move (source branch, to the destination, never prices),
   which is what a wholesaler sends with a van between a store and a shop.
6. **Customer statement PDF is the fourth document and is not stored.** It changes with every payment, so ADR-034 applies as for the loan
   statement: rendered on each request as at the business date, never stored, numbered by date (`ST-20261008-0F07DE94`), capped at 800
   lines so a render fits the 224 MB heap (`StatementMemoryTest`), and in the caller's branch scope like the screen. Its lines are
   the buyer's sales in the caller's scope of `retail.sale.read`, so the route needs that permission as well as
   `retail.document.issue` (403 otherwise). It shows the buyer's payment terms and credit limit, the opening balance, each movement
   with its running balance, the closing balance in figures and words, and imported credit sales apart.
7. **A credit note has one number: the return's.** Issue #201 (V45) numbers the credit note itself, per tenant and branch
   (`retail_sale_returns.credit_note_no`, for example `CNHQ-000001`), so there is no credit note series or prefix here (an earlier draft
   had a second one, `CN-HQ-000001`; it was removed). `POST /retail/returns/{return_id}/document` renders the return as the credit
   note PDF (`credit_note.html`, with the lines returned, the invoice or sale they were sold on, the way the buyer is repaid and the
   amount in figures and words) under that number and stores it once, immutable, in the documents store (subject type
   `retail.credit_note`, `doc_type` `credit_note`); the return's own rows are the record, so no snapshot table is needed. A voided return
   reads as void and is served only as a second version with the VOID banner, like every document; the banner says when it was voided, by whom
   and why, and the copy keeps the issuer, the issue day and the buyer's name of the original issue (`retail_credit_note_issues`, one append-only row
   per return written with the first PDF), not those of whoever asks after the void. A void that lands while a copy is being recorded is seen under
   a lock on the return (and the sale, for an invoice), and the copy is made again as the void one. It needs the `documents` and
   `returns` capabilities and both `retail.document.issue` and `retail.sale.return` in the return's branch.
8. **Permission.** One, `retail.document.issue`, administrators only (the spec's rule: the sales role only if an administrator chooses,
   and there is no per-role switch yet), consulted only while the capability is on, in the branch scope of the document. Everything
   else is an existing permission: converting also needs `retail.sale.create`, a prefix needs `core.settings.manage`.

**Better:** a wholesaler gets the three trade documents and a statement at the DSN quality bar without a screen of a plain shop changing;
a number can be trusted to have no gap; a document reprints identically. **Worse:** a document of a sale that is later voided still
prints as it was issued (the client shows it void, as with a loan receipt); VAT or tax is still not modelled (spec section 16).
**Watch for:** any screen that makes a document a step of the sale; a second place that allocates a number outside the series lock;
any change to `retail_documents` that would update a row (the snapshot is the record).
## Amendment: optional purchasing (#198)

Decision 8 is built in slice 9 (migration V47; `docs/specs/trade-capabilities.md` section 7.4), without the supplier credit note and landed cost, which are the next slice (issue #199). The choices it left open:

1. **Sending an order is the approval, and the checker has its own permission.** An order is a draft until it is sent; a draft is edited under `If-Match`, a sent order never changes (a guard trigger), and receipts move it between sent, partly received and received. At or above the tenant's threshold for `retail_purchase_order` the send waits for a second person holding `retail.purchase.approve` (ADR-015); with no threshold set there is no checker, the default being open for the reason given for returns. The maker holds `retail.purchase.create`, so a clerk can raise orders and cannot approve them, and the checker is none of the author, the last editor and the sender of the order. The threshold is per order and an order can be split below it; with a threshold set, a delivery with no order is refused at or above it and a delivery against an order is refused past the order's value plus the tolerance, so what a delivery raises in goods received not invoiced is bounded by what a second person authorised.
2. **A receipt is a restock whose credit is goods received not invoiced.** It reuses the restock's primitives (purchase movements in base units, the product's cost and an optional sell price with a history row, the pack cost remainder in purchase rounding) so valuation, stock and the reports do not care which path made a movement. The plain restock is not edited. Against an order a receipt may not exceed the ordered quantity by more than the tenant's over-receipt tolerance, zero by default.
3. **The invoice is matched by line, and quantity is the one hard stop.** A bill line bills a quantity of a receipt line at the supplier's price; it clears its share of the receipt line's value from goods received not invoiced (the last piece takes the remainder, so the pieces add up exactly) and the difference is the price variance, posted to purchase price variance and needing a reason above the tenant's tolerance. The design said matching blocks nothing; a bill nevertheless cannot bill more than was received and not yet billed, since the accrual cannot clear what was never put into it. One entry is posted per branch of the receipts: the bill's branch carries the whole debt in trade creditors and the other branches clear their part through inter-branch clearing, and a payment debits trade creditors in the branch of each invoice it pays, through inter-branch clearing when that is not the paying branch, as transfers and returns do. A credit restock credited the creditors of each branch its stock went into, so a payment on it is split over those branches in proportion to what each still owes, in exact integers, and every branch's creditor balance stays what it owes.
4. **A payment is allocated to open items and held as nothing.** Supplier invoices and credit restocks alike are open items (a payment works for a tenant that never uses orders); oldest due date first or chosen, always fully allocated; reversed as a whole. A cash payment is a cash-out in the cash book on its day.
5. **The supplier's row is the lock.** Receipts, invoices, payments and their voids take it first, so concurrent requests serialise per supplier without a lock on the append-only tables (the application role may lock only rows it may update).
6. **Settings outside the capability group.** The two tolerances are a table with one row per tenant, because the `retail.capabilities` group holds booleans; they are edited under `If-Match` by `core.settings.manage` and audited.
7. **Landed cost attaches later without an ALTER.** Receipt lines are append-only; the bill's kind already allows `landed_cost`; slice #199 adds an allocation table keyed by receipt line.
8. **Expand only, and the lock is honest.** Eleven new tables, two chart accounts, two permissions, no ALTER of an existing table. Flyway runs one transaction, so creating the tables holds share row exclusive on the tables they reference to the commit, which blocks writes to suppliers, products, units, restocks and journal entries, not reads, for the length of the file.

**Better:** a wholesaler can order, receive into a store, match the supplier's invoice and pay it in parts, with the books showing what is received and not invoiced; a plain shop sees nothing new. **Worse:** matching is stricter than the design (quantity only); a pack cost that does not divide shows in purchase rounding; the product's cost is not restored when a receipt is voided. **Watch for:** the supplier credit note and landed cost (#199) must post through the same accounts and the same supplier lock; a later reader of trade creditors by supplier must count invoices, credit restocks and payments (the payables report is the model); supplier returns and the credit note are not built, so a wrong delivery is voided and a wrong price carries a reason; the accounts 2110 and 5130 are seeded with the chart (by the migration for existing retail tenants, as V45 did for 4110) and not at first enablement; a tenant that already uses account codes 2110 or 5130 for something else gets `retail_chart_missing` on the first receipt until the account is mapped.


## Amendment: landed cost and supplier credit notes (#199)

Decision 8 is completed in slice 10 (migration V48; `docs/specs/trade-capabilities.md` section 7.5): landed cost allocation and the supplier credit note, which #198 left out. It also settles what the design called a known simplification, goods that were sold before the charge arrived. The choices:

1. **A landed cost is a document, and the payee's invoice is a supplier bill inside it.** Freight, duty, clearing, insurance and other charges of one payee (a supplier row, the forwarder) are listed as charges and spread over received lines. The payee's invoice is a `retail_supplier_bills` row of kind `landed_cost` (the value V47's check already allowed), so it is paid, reversed and aged by the supplier payment machinery and appears in the payables report with no new reader. The bill's `cleared_minor` is the whole amount (it clears nothing from goods received not invoiced) and its variance is zero. A landed cost may name the goods invoice it belongs to, in which case every line must be billed by that invoice. It cannot be voided from the invoice screen (409 `bill_is_landed_cost`).
2. **The spread is exact, with one remainder rule, and it replaces the design's.** The design said half up per line with the remainder on the line of the largest value; that can make the parts exceed the total (five lines worth half a unit each). The shares are the floor of each exact proportion, the units left over (always fewer than the lines) go one each to the largest fractional remainders, on a tie to the larger basis, then to the earlier line. The lines are put in one canonical order (receipt number, line number) first, so the same lines typed in another order give the same shares. The basis is the value the stock movement carried (`stocked_minor`), the quantity, a weight typed per line, or amounts typed per line that must add to the total (`manual`). A database trigger, deferred to the commit, refuses a document whose charges or allocated lines do not add up to its total.
3. **Goods already sold, used or in transit: the charge is split, and the stock valuation follows the ledger.** Stock is not tracked by lot, so which units of a product a receipt brought is read first in, first out over the units of the product on hand in every branch together: the balances, less the stock that came in after the receipt (positive movements that are not undone, not a transfer in and not a customer return, less what credit notes sent back out of them), up to the quantity received less what was sent back. The product's cost is one figure for the whole business and the valuation is balance times cost, so raising the cost per unit revalues those units wherever they are. The ledger follows: the cost change per unit (the charge over the quantity received, rounded down, so that no more is capitalised than was charged) times those units is debited to the inventory of the branches that hold the product, in proportion to what each holds, and the rest of the charge, which belongs to units already sold, used or still on the way between branches, is debited to cost of goods sold in the receiving branch on the day of the charge. So after a charge the valuation of a branch equals its inventory account (a test asserts it for two branches), a unit moved to another branch takes its share there, and a charge under one minor unit a unit, which cannot move the cost, is expensed. When a newer cost event has set the cost (item 4) the share for the units on hand goes to inventory all the same and the difference to the valuation is left to the revaluation difference, as the spec's first design said. This replaces the design's "added to inventory in full and left in the revaluation difference". Earlier sales are still not restated (their snapshots are immutable, FR-RET-03). The part for goods already sold reaches the daily profit report (and the cash book's savings suggestion, which reads it) as a cost of sales on the day of the charge, and a price credit's part takes it off on the day of the credit; the supplier invoice's own price variance was never in that report and is still not. The sales analysis, margins, dashboard and evaluation are analyses of what was sold at the cost it was sold at and do not change: feeding a charge into a per-sale margin would restate a sale. Considered and rejected: expensing everything (it overstates cost while goods are on the shelf), expensing what moved to another branch (it makes the valuation exceed the ledger there), and tracking lots (a different model of stock). The read is one range of the `(tenant, branch, product)` index of the movements per branch, made once when a charge is recorded and once when a void is checked, by an administrator; measured with `EXPLAIN (ANALYZE, BUFFERS)` on a scratch PostgreSQL 17 holding 400,000 movements, 300,000 of them one product in one branch, the read of the stock that came in later took 49 ms (the planner chose a parallel scan because that product and branch were most of the table; with a selective product it is an index range) and the check for a newer cost event, which stops at the first one and goes branch by branch so that it uses the same index and not a scan of the tenant's movements, took 16 ms when one exists (when none does it reads the whole range, as the first read does). It was not run on the 25 times data of db-bench (Docker is needed).
4. **The product's cost moves only for the latest cost event.** The cost per base unit rises by the charge over the received quantity, rounded down, when no purchase movement that stands, and no change of the cost in the price history that is not one of these documents, is newer than the receipt's movement. Otherwise the books are posted and a history row of source `purchase` (no check changes; the product row and its version are not touched, so nobody editing it is told it changed) with the same cost on both sides says it was not applied; a newer cost is never overwritten. The document's own rows and earlier landed costs on the same receipt do not count as newer. The history reason names the documents and never an amount, so a reader without `retail.profit.read` learns no cost from it.
5. **A supplier credit note is against an invoice and never exceeds what it leaves to pay.** Kind `goods` sends units back: a `return_to_supplier` movement per line (negative, guarded like a sale), the credit leaves inventory at what the units were carried at (the lot's stocked value plus landed cost less price credits, in proportion, the last units taking the remainder) and the difference to the credit goes to purchase price variance; the credit defaults to the billed price per unit, less what price credits have already taken off the line, and may differ; only units of that receipt still in its branch (first in, first out) can go back (422 `credit_exceeds_on_hand`), and the movement carries what the ledger took out of inventory per unit, not the product's cost of the day. Kind `price` is an adjustment with no goods moving: it first reverses the invoice line's own price variance, and the rest is spread the way a landed cost is, in reverse (inventory for units on hand, cost of goods sold for the rest, the cost per unit falling under the same guard). A credit is applied in full to its invoice when recorded, so an invoice cannot go below zero and the report's "open credits" of the design is zero by construction; a credit larger than what is left to pay needs the payment reversed first (422 `credit_exceeds_open`). Keeping an unapplied credit to spend on a later invoice, and a cash refund from the supplier, are deferred. A landed cost or price credit is refused on a line that has goods sent back (422 `line_returned`), so the carrying cost of a lot never has two denominators. The new movement kind needs the two checks on `retail_stock_movements` replaced by looser ones, added `NOT VALID` so no row is scanned, as the spec's data model required.
6. **Voiding has the guards of a bill, and one for stock.** A landed cost is voided as a whole by reversal, refused while a payment stands against the payee's invoice, while a credit note touches one of its lines and when the goods it was added to have been sold or moved since (422 `landed_cost_stock_moved`, as a receipt cannot be voided once its stock moved). The product's cost is taken back by the document's own change per unit (the cost now less what it added), so landed costs and credit notes may be voided in any order and each takes back exactly its part; it is not taken back, and the history says so, only when a different kind of cost event (a later receipt or restock, or a manual price change) has set the cost since, because then the arithmetic cannot be separated. A credit note is voided the same way; goods go back into stock, and a price credit is refused once the goods it credited have moved. A bill with a credit note or a landed cost standing against it cannot be voided (409 `bill_credited`, `bill_costed`), and a receipt with a landed cost cannot (409 `receipt_costed`). A voided landed cost puts the hold back on a receipt it released, when no other landed cost stands on it.
7. **The hold is the design's mitigation, as a port, and it holds the held receipt's quantity.** A receipt can be received held as received and not costed (`retail_receipt_holds`; the choice is made when the delivery is received, there is no route to hold a receipt later); with the tenant setting `require_costing_before_sale` on, a transfer out of the receiving branch may not take the quantity of a held receipt: of a product with a held receipt the branch can move out only what it holds beyond that quantity (422 `receipt_not_costed`), so other deliveries of the same product are not stopped. The held quantity is the quantity received, not a first in, first out reading, because reading the oldest units as the ones that left would free the held units once newer stock moved. The hold is released by a landed cost recorded on the receipt, or by an administrator with a reason under the receipt's lock (not on a voided receipt). Stock cannot depend on purchasing, so the rule reaches the transfer through a port in the stock module (`TransferOutGuard`) that purchasing implements; a sale from the same branch is not blocked, as stores do not sell. The setting is a column of the purchasing settings row, edited under `If-Match` and audited.
8. **No new permission, and no checker.** Landed cost and credit notes use `retail.payable.manage` (administrators), in the branch of every received line and of the invoice; so does releasing a hold (the receiving clerk, who holds `retail.purchase.create`, cannot lift the hold that stops the stock). A reader sees the lines of the branches in their scope only. The cost columns of a line (its share, what it was spread by, the parts for inventory and cost of goods sold, the cost before and after) need `retail.profit.read` in the line's branch and are absent otherwise. The credit notes have no checker: the spec asks for one only on large purchase orders, so none is added, and the owner should know that one administrator can record and void them; every write is audited, idempotent and numbered (`LC`, `SC` and the payee's invoice `SB`, per tenant and branch).
9. **Expand only, and the lock is honest.** Eight tables, one column on the settings table and the two movement checks; no permission and no chart account. The checks are the one statement on a large table: they take an exclusive lock for the milliseconds of the transaction's end, which is why the file sets `lock_timeout` to five seconds and places the statement last. The new checks are not yet validated; validating them (a later migration) cannot fail, as they are looser than the checks they replace.

**Better:** a wholesaler can spread freight, duty and clearing over what it received with a rule that always adds up, see the cost of stock and of sold goods both carry their share, pay the forwarder like any supplier, and send damaged goods back against a credit note that reduces what is owed; a plain shop sees nothing new. **Worse:** units still on the way between branches are expensed; the first in, first out reading is an approximation of lots that can disagree with the physical units; goods can be sent back to a supplier only out of the receipt's own units still in its branch; a landed cost cannot be voided once its goods have moved; a credit cannot exceed what an invoice leaves to pay; price credits and landed costs cannot be added to a line that has goods sent back. **Watch for:** any reader of the cost of a received line must add the standing landed cost and subtract the price credits (`LotRepository` is the model); a transfer reads the product's cost before it takes its balance locks (existing behaviour), so a transfer that overlaps a change of the cost can carry the older cost between two branches; any new writer of stock movements of a receipt's product that the first in first out reading should count as a later layer; `VALIDATE CONSTRAINT` on the two movement checks is still to run in a later migration; a second place that changes a product's cost must stay outside the history rows of these documents or the guard will read it as a newer event.

## Amendment of 2026-10-25 (issue #424): the kind of business

ADR-092 replaces the two profiles of decision 2 with four kinds named by what the owner has (`shops`, `stores_and_shops`,
`wholesale`, `wholesale_and_shops`). A kind is still a preset of the flags and never a mode; it sets the six flags it governs and
leaves `branch_roles` and `returns` as they are. Migration `V103` renames stored profiles without changing a flag.

## Amendment of 2026-10-28 (issue #630, owner decision D64): packs on for a new retail business

Decision 1 kept every flag off by default. Two pilot shops buy by the carton and could not find packs (`units`) until the owner
switched it on in Retail settings (D63). For `units` only, that default changes: a business that gets the retail module, when it is
created with it (by sign-up or by the operator) or when retail is switched on later, starts with `units` on. The other seven flags
stay off.

1. **Set up at the switch, not read as a default.** `retail.manifest` supplies `PacksOnAtStart`, a `core.tenancy.ModuleSetUp`
   (ADR-098), which writes `units: true` through the core settings write in the switching transaction, audited as
   `retail.capabilities.changed`. The stored kind is Shops, so a new business does not read as a wholesaler. The core calls the
   set-up only for a module that was off, so a modules change that keeps retail on leaves packs as the owner set them. An absent
   flag still reads as off, so no existing business changes and no migration is needed.
2. **A later switch-on turns packs on again.** A business whose retail was switched off and on again finds packs on, even if the
   owner had switched them off; they switch them off again in Retail settings. The packs and their prices are kept either way.
3. **A kind only ever turns packs on.** Wholesale and Wholesale and shops turn packs on as before; Shops and Stores and shops no
   longer switch them off (`BusinessTypes.NEVER_SWITCHED_OFF`), so a new shop that picks Shops on Retail settings keeps them. The
   other wholesale tools and the two-step stock move still follow the kind (ADR-092). Neither sign-up nor the first-run set-up asks
   for the kind; it is chosen only on Retail settings. Packs go off only on their own switch.
4. **The item form says packs exist.** While `units` is off, Add an item and Edit show one line, "Also sold or bought in cartons
   or boxes?". Someone with `core.settings.manage` turns packs on there through `PUT /retail/settings/capabilities` with its
   `If-Match` (sending `units` alone, which the group merges; a version conflict is read again once), and the pack section shows
   at once; anyone else reads "Ask the owner to turn on cartons in Retail settings." The line
   links the help article How do I sell or buy in cartons?

**Better:** a new shop that buys by the carton finds packs on the item form from the first day. **Worse:** a shop that never sells a
pack sees the pack section on Edit until it switches packs off, and choosing a kind no longer switches packs off. **Watch for:** a new path that switches retail on must go through
`ModuleSetUp`, or the business starts with packs off.
