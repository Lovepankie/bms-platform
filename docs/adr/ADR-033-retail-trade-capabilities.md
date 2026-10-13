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
   latest-receipt-wins rule (weighted average cost stays pending ADR-021).
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
   One ledger entry posts in the receiving branch with a credit to trade debtors for each sale, so cash lands where it is held. It debits the seeded retail account of the method by system key (`cash_on_hand`, `mobile_money`, `bank`) as every retail posting does, and does not read the `payment_methods` mapping (ADR-026: retail keeps its accounts until #236); the mapping already refuses to move `cash` or `bank` elsewhere while retail is on.
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

