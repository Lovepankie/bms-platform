# Trade capabilities of the retail module (wholesale support)

**Status:** Proposed (design only, issue #190) · **Decision record:** ADR-033 · **Module:** `retail`

Slices 1 (branch roles), 3 (two-step transfers), 4 (units of measure, section 4), 5 (price lists, section 3) and 6 (customer accounts, section 5) are built; the others are design only. This page designs how the retail module serves a wholesaler
without a second module and without changing anything for a plain shop. Migration numbers are not
given here: they are assigned at merge time (AGENTS.md). Every amount and name in this page is
fabricated.

## 0. The business flow this must carry

A wholesaler buys goods into several **stores** (Store 1, Store 2, Store 3). All the stores feed one
**shop** by stock transfer. **Only the shop sells to customers; the stores never sell.** A retail
shop that buys from that wholesaler does it without quotations, invoices or delivery notes, and the
shop's current app has none of them either.

Product owner rules that bind this design:

1. Wholesale differences are **generic, optional capabilities** switched per tenant. They are not a
   new vertical and not a second module (ADR-020 decision 11 stands: no field exists for one tenant).
2. **Documents are optional, off by default and never required.** The till receipt stays the default.
3. A plain shop (three shops, no stores, no documents) must work unchanged with every capability off.
4. "Can receive purchases" is **not a flag.** Restocking is the action; the branch's role decides
   what a branch may do.

What already exists and is reused unchanged: append-only stock movements and balances
(FR-RET-03), atomic transfers between branches at cost through inter-branch clearing (FR-RET-16),
restocks that set prices (FR-RET-06), credit sales and credit buyers (FR-RET-05), the retail chart
of accounts (section 6.11.1 of the SDD), per-permission branch scope (ADR-017), the maker of every
write audited (FR-RET-14).

## 1. Capabilities and the tenant profile

### 1.1 The eight capabilities

| Key | What it adds | Default |
|---|---|---|
| `branch_roles` | Branches have a role: store, shop or both (section 2) | off |
| `units` | Pack sizes with conversions to the product's base unit (section 4) | off |
| `price_lists` | Named price lists, quantity breaks, effective dates (section 3) | off |
| `customer_accounts` | Credit limit, terms, ageing, statement (section 5) | off |
| `documents` | Invoice, quotation, delivery note from a sale (section 6) | off |
| `purchasing` | Purchase order, goods received, supplier invoice, supplier credit, landed cost (section 7) | off |
| `transfer_receipt` | A transfer is dispatched, then received, so stock can be in flight (section 9.3) | off |
| `returns` | Customer returns and sales credit notes (section 5.3) | off |

Each is a boolean in the tenant settings group `retail.capabilities` (FR-TEN-08 typed schema;
absent keys read as off). Nothing else in the module reads a "wholesale" notion.

**Where this lives (module boundaries, ADR-002).** `TenantSettings` and `/me` are core, and the
boundary test forbids core depending on `retail.*`. So the core gains one small, vertical-neutral
hook of the kind section 5.4.3 of the SDD already describes for registries: a *module settings
group* contract. The core stores the group as opaque jsonb under its module key, calls the
registered group to validate and normalise it on write, and asks it for the capability names to
list in `/me`. The retail manifest (`retail.manifest`) supplies the group; lending supplies none.
Inside retail, **`retail.stock` owns branch roles and the capability reads** (a public
`RetailCapabilities` and `BranchRoles` interface), because stock is where branches and role checks
already live. `retail.sales` and `retail.purchasing` depend only on `retail.stock` and
`retail.catalogue` for them, never on each other's internals. The capability routes
(`/retail/settings/capabilities`) are retail's and call the core settings write through its public
interface. `ModularityTest` stays green; slice 1 adds the hook and its test.

**Capability off means behaviour exactly as today.** Data written while a capability was on is kept
and never deleted when it is switched off; it is simply not offered and not applied. A capability
that changes how a sale is computed (`price_lists`, `units`, `branch_roles`, `customer_accounts`, `returns`)
is ignored by the sale path when off, even if rows exist. This is what makes switching off safe.

### 1.2 The tenant profile switch

`retail.profile` in the same settings group is `shop` (default) or `trade`. A profile is a
**preset, not a mode**: choosing it writes a bundle of the capability flags, after which an
administrator can still toggle each flag. The module reads the flags, never the profile, so there
is no code path "if trade".

| Profile | Capabilities switched on by the preset |
|---|---|
| `shop` | none (all off) |
| `trade` | `branch_roles`, `units`, `price_lists`, `customer_accounts`, `returns`, `transfer_receipt`; `documents` and `purchasing` stay off until chosen, because the product owner's rule is that documents are never assumed |

Changing the profile or a flag needs `core.settings.manage` (administrators only), is audited
(`retail.capabilities.changed`, old and new values) and takes effect on the next request.

**Module-aware UI (issue #185).** The user's `/me` response lists the enabled modules; this design
adds the enabled capabilities of each module that has a settings group beside them as
`module_capabilities: {"retail": ["branch_roles", ...]}` (a vertical-neutral shape, because the core
never names a vertical). Built in slice 1 together with the profile preset.
The PWA's menu, home tiles and guided tours (ADR-025) read that one list: a screen, a menu entry, a
tile and a tour step appear only when its module and its capability are enabled, and only when the
user holds the permission. Tours are data with a version (ADR-025): each capability adds its own
tour, which starts the first time a user with the matching permission sees a newly enabled
capability. The component test required by #185 (one per module combination) extends to one per
capability, including "all off equals today".

Built in slice 2 (issue #192, PWA only): the Retail settings screen with the review-then-confirm step, the
branch roles screen, `SCREEN_CAPABILITY` (a screen of a capability is offered only while `/me` lists it) and
tours with a `capability` that start once when it is first on. A capability whose pages are not released has
no tour yet: the slice that releases its pages adds the tour and marks it `ready`, and a test fails otherwise.

## 2. Branch roles

### 2.1 Roles

A retail branch has a role, held in `retail_branch_roles` (a retail table, so the core `branches`
table is untouched, as ADR-001 asks when only one vertical needs the concept):

| Role | Sells (sales screen, `POST /retail/sales`) | Restock (`POST /retail/purchases`, later goods received) | Transfer out | Transfer in | Usage, damage, stock-take |
|---|---|---|---|---|---|
| `store` | no, refused | yes | yes | yes | yes |
| `shop` | yes | yes (by permission) | yes | yes | yes |
| `both` | yes | yes | yes | yes | yes |

`both` is today's behaviour for every branch and is the default. Payments on a credit sale, voids
of existing sales, the cash book and reports are not gated by role: a record that exists can always
be read, voided or paid, whatever the branch is today.

Rules:

- **Stores never sell.** A sale at a `store` branch is refused with 422 `branch_role_forbids_sales`
  (the sale cannot be made, so the server, not the screen, enforces it). The sales screen lists
  only `shop` and `both` branches in its branch picker, and a user whose one branch is a store
  lands on the stock screen.
- **Restock is the action, for every role.** There is no "can receive purchases" flag and the role
  never limits restocking (the column is yes for all three roles): who may restock is
  `retail.purchase.create` and branch scope, exactly as today. A tenant that does not want shops to
  buy does not grant it there. Role never grants a permission, it only removes the ability to do
  what the branch cannot do, which is selling at a store.
- **Roles are enforced only when `branch_roles` is on.** Off, every branch behaves as `both`.
- Changing a role needs the new permission `retail.branch_role.manage` (administrators), is audited
  (`retail.branch_role.changed`), and is refused (422 `branch_has_open_work`) while an unreceived
  transfer touches the branch. Changing a branch to `store` does not touch its past sales: history
  stays valid and can still be voided.

### 2.2 Permission and branch-scope interaction

Three tests all pass for an action: the user holds the permission, the permission's branch scope
covers the branch (ADR-017, `principal.may`), and the branch's role allows the action. Role is a
property of the branch, not of the user, so it can never widen scope or grant a permission; it can
only narrow what the branch accepts.

| Case | Result |
|---|---|
| User holds `retail.sale.create` at a store | 422 `branch_role_forbids_sales` (permission and scope pass, role refuses); not 403, because the user is entitled, the branch is not |
| User holds `retail.sale.create` only at stores | the sales menu is hidden; a direct sale at a store is 422 `branch_role_forbids_sales` (permission and scope pass); 403 only where the permission is not held |
| Transfer from a store to a shop | needs `retail.stock.transfer` at the source (as today); the destination branch must be active and may be outside the caller's scope (as today) |
| A manager scoped to Store 1 and the shop | sees both in stock views; sells only at the shop |
| A branch-scoped caller reads the stock of Store 2 | 404 as today for a branch outside scope |

A new tenant branch defaults to `both` through the absence of a row (section 2.3), so adding a
branch never needs the role step.

### 2.3 Migration plan

1. **Expand.** Create `retail_branch_roles (tenant_id, branch_id, role)` with RLS. The code treats
   a missing row as `both`.
2. **Backfill** one row per existing branch of every tenant with the retail module, role `both`, in
   the same migration. The backfill is a convenience for reading; correctness never depends on it
   because of the missing-row rule, so a branch created between backfill and deploy is safe.
3. **Switch on per tenant** only by an administrator, after the roles are set. No tenant is
   switched by the migration. A tenant of plain shops never switches it.
4. **No contract step.** Nothing is dropped or renamed.

Rollback: switch the capability off. Rows stay, behaviour is today's.

## 3. Price lists

**Built in slice 5 (issue #195, migration V41).** Choices the design left open: the sale's list is chosen as in 3.2 with a list that is switched off passed over (the next level applies; a named list that is off or unknown is a field problem); a break is `min_qty >= 0`, where 0 (the default) means from any quantity; an item prices one unit, so a carton line takes a carton item and a base item never prices a carton (a carton with no item takes the retail price of the carton, `sell x factor`); the date rules are: `valid_from` defaults to today and may not be in the past, `valid_to` is the last day, a new open ended item takes over from the current open ended one by closing it the day before (an item that starts on or before the old one's first day is 409 `price_item_overlap`), and any other overlap is refused, so a promotion inside an open price needs that price ended first; an open item is ended on a day not before today or its first day, and a typo in a price that starts today is fixed by ending it and adding the corrected one from the next day (or by an override with a reason), because an item takes no other change; a list is switched off, never deleted, and a switched-off list is passed over at sale time; the sale line stores `price_source`, the list, the break that applied, the price the system resolved per sale unit and the override reason, and the sale stores the list in force; a base unit cannot change while any list prices the product (422 `unit_in_use_by_price_list`); `retail.pricelist.use` goes to administrators only (the review table in 14.1 said otherwise; 3.2 and section 11 govern). A strict list means strict: an item missing from it cannot be sold to that list's customers even by a holder of `retail.price.override` who gives a reason (422 `no_price_in_list`; an override changes a price the system resolved, and there is none), the administrator adds the price or switches the list off. Every changed price is written to the audit entry of the sale (`price_overrides`: line, product, list, resolved price, price used, reason). With the capability off, an earlier sale shows no list and no pricing on its lines. A customer's own list and group are set with `PUT /retail/customers/{id}/pricing` (`retail.pricelist.manage`), because the sales role's `retail.customer.manage` edits name and contact only.

### 3.1 Model

- **The retail list is the product's own `sell_minor`.** It is not copied into a list table. This is
  the whole backward-compatibility story: with `price_lists` off, or with a sale that selects no
  list, the price is `retail_products.sell_minor`, written by the same events as today (ADR-020
  decision 5).
- **Named lists** (`retail_price_lists`): a name (unique ignoring case), currency, active flag. A
  list holds **items** (`retail_price_list_items`): product, unit (section 4), `min_qty` (the
  quantity break, in the item's unit), `price_minor` per that unit, `valid_from`, optional
  `valid_to`.
- **Customer links.** A credit buyer (`retail_customers`) may have a `price_list_id`, and may belong
  to a **customer group** (`retail_customer_groups`) that has a `price_list_id`. This is how
  "wholesale", "per customer" and "per customer group" are all the same table.
- Items are versioned like prices are today: a change inserts a new item and closes the old one by
  setting its `valid_to` (the only column an UPDATE may change, enforced by a guard trigger like
  `retail_transfers_guard_update`); the history of a list is the rows. A product's own price history
  (`retail_price_history`) is unchanged.

### 3.2 How a sale chooses a price

The server resolves each line; the client may suggest a list but never a price it cannot justify.

1. **Which list.** In order: the list the sale names explicitly (`price_list_id`, needs
   `retail.pricelist.use`, administrators only, to pick a list other than the customer's own or the
   customer group's; the customer's and the group's list always apply automatically to any seller), else the customer's own list, else
   the customer's group list, else none.
2. **Which item.** Within the list, for the line's product and sale unit, among items valid on the
   sale's business date (`valid_from <= sale_date` and `valid_to` null or on or after it), the item
   with the **largest `min_qty` not above the line quantity**. A break applies to the whole line, not
   in tiers, and quantities of the same product on two lines are not added together (deferred, 11).
3. **Fallback.** No item: the line takes the product's retail price converted to the sale unit
   (section 4.3), unless the list is marked `strict`, in which case the line is refused with 422
   `no_price_in_list`. Default is not strict, so a half-filled list never stops a sale.
4. **Override.** `unit_price_minor` on the request is an override, as today. When `price_lists` is on,
   an override of a resolved price needs `retail.price.override`; the migration seeds that permission
   to every role that already holds `retail.sale.create`, so nothing changes until an administrator
   removes it. The sale line stores `price_source` (`retail`, `list`, `override`) and the
   `price_list_id` that applied.

The sale stores the price actually charged in its snapshot columns, as now. Profit is computed from
snapshots, never from lists (ADR-020 decision 5), so editing a list never changes history.

### 3.3 Cost rules that stay

- **Price below cost.** The existing rule holds on the **resolved** price, compared on the **line**,
  never on a rounded per-unit figure: a line whose `line_total_minor` is not above its
  `line_cost_minor` (base quantity times the product's cost at the time, rounded half up once) is
  refused unless the user holds `retail.price.below_cost`. A pack of twelve costing 83 per piece
  (996) sold for 1,000 is above cost and accepted, though 1,000 / 12 rounded is 83. A list item below cost can be **saved** (a promotion or a
  clearance), but the editor warns, and a sale at it needs the permission anyway. This keeps the
  rule in one place: the sale.
- **Cost visibility.** Cost, margin and profit appear only with `retail.profit.read` covering the
  branch (omitted, never null). The price list screens are for `retail.pricelist.manage`;
  the editor's "margin" column and the below-cost warning need `retail.profit.read` and are absent
  without it. Resolving a price returns the price and its source, never the cost.
- **Managing and reading lists** needs `retail.pricelist.manage`; seeded to administrators only. A seller sees a price only through the resolved price of a sale line.

### 3.4 Effective dates and overlaps

Two items for the same list, product, unit and `min_qty` may not overlap in time. A unique index
covers the open-ended row (`valid_to is null`), and the service checks closed ranges under a lock on
the list row, so concurrent edits serialise and the second gets 409 `price_item_overlap`. A future
`valid_from` is allowed (a scheduled price change); a past one is refused (a sale already made keeps
its snapshot, so back-dating a list gains nothing).

## 4. Units of measure

**Built in slice 4 (issue #194, migration V39).** Choices the design left open: a pack's default price is `sell_minor x factor` rounded half up to a whole minor unit per pack, and the line is `sale_qty` times that price rounded half up once, so `line_total_minor = sale_qty x sale_unit_price_minor` holds exactly (for a whole factor the pack price is exact); the per-base price is `line_total / base_qty` rounded half up and is display only; a restock's `sell_minor` stays per base unit; `purchase_rounding` (5120) is seeded by the migration for every retail tenant and by the chart function for new ones, instead of when the capability is first switched on, because an account that stays at zero costs nothing and a switch-on hook would need the chart in the settings path; the pack list is read with `retail.stock.read` so the sales screen can offer it, and only changed with `retail.catalogue.manage`; a pack is addressed by its unit, keyed `/retail/products/{id}/units/{unit_id}`; a pack is switched off, never deleted (only an active pack ties its unit and the item's unit down; switching on again confirms the factor); a factor is at least 1, so the smaller unit is always the base unit; a naming of the base unit as `unit_id` is the base unit and takes the pre-existing path with the capability on or off. A factor change applies to later lines because every line keeps the factor it used.

### 4.1 Rules

- **The base unit** is the product's existing `unit_id`. **Stock is always held in the base unit**:
  `retail_stock_movements.qty` and `retail_stock_balances.qty` stay as they are. No existing row,
  report, valuation or reconciliation changes meaning.
- A product may have **pack units** (`retail_product_units`): a unit (a carton, a box, a roll) with a
  `factor`, the number of base units in one pack, `numeric(14,3)`, greater than zero. A pack of
  twelve pieces has factor `12.000`. A pack cannot itself be a base unit of another pack (no
  chains); a product with no pack rows behaves as today.
- **Price is per unit.** The product's `sell_minor` is per base unit. A pack with no price list item
  is priced at `sell_minor x factor`, rounded half up once for the line (R-ROUND). A price list item
  may state its own pack price (a case discount), which wins.
- **Purchase and sale in any unit.** A sale line and a restock line may name a unit and a quantity in
  that unit. The server converts to base units for the movement and the balance.

### 4.2 Rounding and exactness

- **Quantities never round.** `base_qty = qty x factor` must be exactly representable in three
  decimal places; otherwise 422 `quantity_not_representable`. Nothing is silently rounded in stock,
  consistent with FR-RET-03.
- **Money rounds once per line**, half up, on the line total (R-ROUND), as every other retail line.
  The below-cost test uses these line totals (section 3.3), not the derived per-base price.
- A sale line stores both worlds: `qty` and `unit_price_minor` stay in **base units** (so every
  existing report, which sums `qty` and `line_total_minor`, is unchanged), and the new columns
  `sale_unit_id`, `sale_qty`, `sale_unit_price_minor` and `unit_factor` record what was entered.
  `line_total_minor = sale_qty x sale_unit_price_minor` exactly; the per-base `unit_price_minor` is
  the derived, rounded display value. For rows before the change the new columns are null, meaning
  "entered in the base unit".
- **Cost per base unit from a pack cost.** A restock line in packs gives a cost per pack. The
  per-base cost is `pack cost / factor` rounded half up to the minor unit, and is what the movement
  snapshot and the product cost hold. Because that can differ from the pack cost times the quantity
  by a few minor units, the ledger takes the **paid or owed amount** (the line total as entered) as
  the credit and the **movements at the rounded unit cost** as the debit to inventory, and posts the
  difference, when not zero, to a new system account `purchase_rounding` (expense, code 5120). It is
  small by construction (under half a minor unit per base unit) and visible in one place.

### 4.3 Import impact

- The retail product CSV import (issue #146) gains two optional columns, `pack_unit` and
  `pack_factor`, one pack per row; a row without them behaves as today. A header without the new
  columns is accepted unchanged.
- `import-retail` (the pilot history import) is untouched: historical rows are in base units and get
  null sale-unit columns.
- A unit used by a pack cannot be deactivated while any pack row references it (same rule as units
  in use today, FR-RET-01).

## 5. Customer accounts

### 5.1 What exists and must be respected

`retail_customers` today are **credit buyers**: a name, a contact, and a balance derived from open
credit sales (FR-RET-05). Credit sales post debit trade debtors, and payments post against the sale.
Imported credit sales are historical, **unpaid and not journalled** (ADR-020 decision 9), and
issue #96 asks the business and its accountant to decide how they become receivables (an opening
journal per customer, tracking in the credit buyer list only, or another approach).

This design **does not decide #96 and does not post anything for imported credit sales.**

- The customer balance shown on the account is computed as today, from sales and payments, and
  imported unpaid credit sales continue to appear in it, flagged `historical`. Whatever #96 decides
  (a repeatable opening journal, or leaving them out of the ledger) changes the ledger side only;
  the account screen needs no change, because it never reads the ledger.
- The debtors reconciliation (ledger `trade_debtors` against open credit sales) keeps excluding
  historical rows until #96 is closed. The statement lists historical rows in a separate section
  titled "before system start", so a statement never claims a ledger figure it cannot support.
- Tenants that do not use credit are untouched: every new column is null and every new rule is
  skipped when it is null.

### 5.2 Additions (capability `customer_accounts`)

**Built in slice 6 (issue #196, migration V44).** Choices the design left open: payment on account takes `method` (`cash`, `mobile_money`, `bank`) and an optional `paid_on` (not in the future; a sale dated after the payment is skipped), and may carry `allocations` to choose the sales by hand instead of oldest first (they must add up to the amount, each within the sale's balance, none imported or out of scope); it creates one receipt (`retail_account_payments`, numbered `RC` and eight digits) and one ordinary sale payment per sale, with one ledger entry in the receiving branch that credits trade debtors once per sale. It is **reversible**: `POST .../payments/{id}/reverse` (`retail.sale.void`, a reason) undoes the receipt as a whole, once, reversing the entry on the business day and giving each sale its paid amount back; there is no partial reversal or re-allocation in place, so a change of allocation is a reversal and a new payment. The sale payment rows of a reversed receipt stay (the table is append-only) and are ignored by the sale's payment list, while the credit control report, like the ledger and the cash book, keeps the money on the day it was received and shows the reversal as a negative row on the day it was undone, so a day already reported never changes; the cash book keeps the payment on the day it was received and takes the money back on the day of the reversal, in the receiving branch (a payment on account is a cash receipt of the branch that holds the money, not of the sale's branch). The V14 guard that let a sale's paid amount only rise is replaced so it may fall only inside the reversal of one named receipt (a transaction-local setting holding the receipt's id; the receipt must read reversed and have paid that sale) and only by exactly what that receipt allocated to the sale, never below its unreversed payments; a plain UPDATE that lowers it is refused as before. Oldest first means the oldest sale date, ties in the order the sales were made, not the due date (the ageing buckets are by due date, the allocation is by invoice date). A scoped caller's views agree: the receipt list and the statement count a payment in the branches of the sales it paid, so a scoped user sees a receipt that paid their branch's sales with only those allocations and the sum of them as its amount, while credit control's payments received, like the ledger and the cash book, count a payment on account in the receiving branch. Limit and terms are each given or cleared on purpose in `PUT .../account`; a reversal reason is at least five characters once trimmed. The statement closes at what the ledger holds for the buyer's sales in the caller's branch scope (`closing_minor`; imported sales have no debit there and are listed apart as "before system start"), and `balance_minor` adds the imported sales, which equals the customer balance in the same scope. Exposure is the tenant-wide balance of completed credit sales, imported ones included, and is shown to a caller only when their scope covers every branch. The override is a reason of at least five characters, written to the audit trail with the exposure and the amount over; `customer_required` applies only to credit sales, and a typed name alone is refused. The limit and terms appear on a buyer in the list only while the capability is on. The customer ageing report is on the same sales and buckets as the credit control report, which also carries the limit while the capability is on. The customer statement as a PDF is part of the documents slice; this slice has the on-screen statement and the API only.

| Item | Rule |
|---|---|
| Credit limit | `credit_limit_minor` on the customer, null means no limit. **Setting or changing a limit or terms needs `retail.credit.manage` (administrators only)**: the sales role already holds `retail.customer.manage` and must not be able to raise its own customers' limits, so that permission edits name and contact only. A credit sale that would take the balance above the limit is refused with 422 `credit_limit_exceeded` unless the user holds `retail.credit.override`. **Concurrency:** the sale first locks the customer row (`SELECT ... FOR UPDATE`, taken before the balance locks, in the same lock order as the other sale locks) and only then computes exposure, so two shops selling to one customer at once cannot both pass the check. **A customer record is required:** with `customer_accounts` on, a credit sale must name a `customer_id` (422 `customer_required`); a typed `buyer_name` alone is refused, otherwise it would bypass every limit (V11 allowed a buyer name with no record). The check uses the **tenant-wide** balance including historical rows, because it measures exposure; the response says only that the limit is exceeded and by how much, and never other branches' sales, so branch scope is not leaked |
| Terms | `terms_days` on the customer, null means none. It defaults the sale's `due_date` to `sale_date + terms_days`; the date stays editable, as the proposed payment date is today |
| Ageing | Report `GET /retail/reports/customer-ageing`: per customer, open balance in buckets by days past `due_date` (not yet due, 1 to 30, 31 to 60, 61 to 90, over 90; fixed buckets in the first version), plus a total row. No cost or profit appears. Scoped by `retail.sale.read` |
| Statement | `GET /retail/customers/{id}/statement?from&to`: opening balance, credit sales, returns (5.3), payments, closing balance, with the "before system start" section of 5.1. Built from `retail_sales`, `retail_sale_returns` and `retail_sale_payments`; no new ledger event. Like the existing `/customers/{id}/balance` it covers **the caller's branch scope**; its test compares the statement's closing balance with that scoped balance (and, for a caller with full scope, with the tenant-wide balance). The limit check alone is tenant-wide |
| Payment on account | `POST /retail/customers/{id}/payments` with `branch_id` (the branch that received the money, in the caller's `retail.sale.create` scope). One amount, allocated oldest first, creating ordinary `retail_sale_payments` rows. **Allocation skips** (a) `historical` imported sales, which stay outside the ledger until issue #96 is decided, so a payment never credits trade debtors for an amount that was never debited; and (b) sales outside the caller's branch scope (they would be 404). The amount that cannot be allocated is refused (422 `payment_exceeds_allocatable`, the response says how much can be); nothing is held as a deposit (deferred). **Posting branch:** each payment entry posts in the receiving branch (debit cash, mobile money or bank of that branch; credit `trade_debtors`, the sale as subledger), not in the branch of the sale, so cash lands in the shop that holds it. Trade debtors per branch can therefore differ from that branch's open sales; the debtors reconciliation is tenant-wide, as the customer balance is. An explicit payment against one named sale (today's route) is unchanged |
| Group and list | `customer_group_id`, `price_list_id` (section 3) |

Stores never sell, so a credit sale can exist only at a shop; the customer balance still spans
shops, which is why the limit check reads tenant-wide.

### 5.3 Customer returns and credit notes (capability `returns`)

A sale can be partly or fully returned after the fact. A return is its own record, never an edit
of the sale (sales are append-only apart from the void):

- `POST /retail/sales/{id}/returns` (**M**, `retail.sale.return`, scope on the sale's branch):
  lines of product, quantity (at most sold less already returned, in the sale's unit), a reason,
  and the **refund**: `cash`, `mobile_money`, `bank` (money out of the receiving branch) or
  `account` (reduces what the customer owes; only for a sale with an open balance, never below
  zero, the rest refunded in money).
- Stock: returned goods go back into the branch as a `return` movement at the sale line's cost
  snapshot, if resaleable; damaged goods are a usage report instead, so a return never inflates
  stock. A voided sale cannot be returned (the void already reverses it); a historical sale can be
  returned for stock and a money refund, but an `account` refund is refused for it until issue #96
  is decided.
- Posting: debit `sales_returns` (new contra income account 4110) for the returned value, credit the
  refund account (or `trade_debtors` for `account`); debit `inventory`, credit `cost_of_goods_sold`
  at the snapshot cost. Key `retail.sale_return:<id>`; void by reversal.
- Reports: sales analysis and daily profit net returns by return date; the customer statement lists
  them. The credit note document is a template of the return (section 6).

## 6. Optional documents

### 6.1 Principles

- **Off by default (`documents` capability), never required.** No sale, transfer or restock ever
  waits for a document, depends on one, or fails because one failed. A document is an optional
  **after-step** that reads a committed record.
- **The till receipt stays the default** and is not a numbered document: it is the existing receipt
  view, with no series.
- Templates, not features: an invoice, a quotation, a delivery note and (with `returns`) a credit
  note are templates of one `retail_documents` record.

### 6.2 Behaviour

| Document | Source | Notes |
|---|---|---|
| Invoice | A completed sale | Buyer name and contact, lines in the sale unit, totals, payment status, due date for a credit sale |
| Delivery note | A completed sale | Same lines, **no prices** by default (a template option shows them); signature line |
| Credit note | A customer return (section 5.3) | Lines returned, refund method, reference to the invoice if one was issued |
| Quotation | Lines entered without a sale (a draft of lines, buyer, `valid_until`) | Reserves no stock and posts nothing. `convert to sale` creates a sale: lines re-priced at conversion unless the quoted price is still valid (list item still effective) and the user holds the override permission; a converted quotation records the sale id |

- **Snapshot.** Issuing a document stores a JSON snapshot (tenant header, buyer, lines, totals,
  currency) so it reprints identically after prices change. Documents are immutable once issued.
- **Numbering.** One series per tenant and document type (`retail_document_series`: type, prefix,
  next number). The number is allocated at issue under a lock on the series row, so the sequence is
  gapless and per tenant, not per branch. Voiding a sale marks its invoice `void` and keeps the
  number; a number is never reused. The prefix is a tenant setting (for example `INV-`), the value
  is zero-padded.
- **Never blocking.** Issue is a separate call (`POST /retail/sales/{id}/documents`), idempotent by
  `(sale, type)`. A failure leaves the sale committed.
- **Rendering.** First version: a print-styled page in the PWA (browser print or save). PDF
  generation through `core.documents` is later and belongs with the documents follow-up
  (issue #120); the snapshot is what it will render, so nothing is rebuilt.
- **Permissions.** `retail.document.issue` (issue and reprint), `retail.document.void` is not needed
  because a document follows its sale. Seeded to administrators, and to the sales role only when an
  administrator chooses.

### 6.3 What a retail shop sees

A tenant that never switches `documents` on sees no change at all: no menu entry, no button, no
series, no settings. This is the case that must stay true for the shop buying from a wholesaler with
no documents.

## 7. Optional purchasing

The simple path is **plain restock** (`POST /retail/purchases`): choose supplier, enter quantities
and costs, done; it posts inventory against cash, bank or trade creditors and sets prices
atomically (ADR-020 decisions 5 and 7). It stays exactly as it is, stays the default, and keeps
working with `purchasing` on. The optional flow is for a wholesaler who orders, receives against an
order and pays against the supplier's invoice. Both write the same `purchase` stock movements, so
valuation, stock and reports do not care which path made them.

### 7.1 Documents and states

| Record | States | Effect |
|---|---|---|
| Purchase order | draft, approved, partly received, received, cancelled | Posts nothing, moves no stock |
| Goods received note (GRN) | posted, voided | Receives into **a branch** (normally a store): `purchase` movements, cost set by the rule below, posted to **goods received not invoiced** |
| Supplier invoice (bill) | posted, voided | Records the supplier's invoice number, date, due date and amount against one or more GRNs; clears goods received not invoiced into trade creditors; any price or quantity difference is shown, and a difference above the tenant tolerance (default zero) needs a reason |
| Supplier credit note | posted, voided | Reduces what is owed to a supplier: goods returned (a `return_to_supplier` movement, negative, guarded like a sale so stock cannot go below zero) or a price adjustment |
| Supplier payment | posted, voided | Cash, mobile money or bank against a supplier's open **bills and open credit restocks** alike: a plain credit restock only raises trade creditors today and nothing can pay it, so the payment applies to either and works for a tenant that never uses orders. **Cash goes through the cash book (ADR-022):** a cash supplier payment is a cash-out the daily cash summary and the expected-to-bank amount include for the paying branch, so it never makes the till look short; mobile money and bank do not touch the cash book |

A PO may be raised without a supplier invoice ever following, and a GRN may be received without a
PO (a "blind receipt"), because small wholesalers do both; matching is **reported, not enforced**:
the three-way view (ordered, received, billed) flags mismatches and blocks nothing. Approval of a PO
above a tenant threshold is a maker-checker action registered by the module (ADR-015), off unless a
threshold is set.

### 7.2 Landed cost

Freight, duty, clearing fees and similar charges arrive as a **landed cost bill** against one or
more GRNs (a supplier bill of kind `landed_cost`). It is allocated to the receipt lines by **value**
(default), by **quantity**, or by a manual split that must add to the bill. Rounding is half up per
line with the remainder on the line with the largest value, so the parts add exactly.

- The allocation raises the receipt lines' landed unit cost. The product's cost is set to the
  landed unit cost with a price history row (`source` = `receipt`), by the existing rule that the
  latest receipt wins (ADR-020 decision 5), under the guard below. Weighted average cost stays pending (pending ADR-021).
- Posting: debit inventory, credit trade creditors (the carrier or agent as supplier) for the
  allocated amount.
- **Cost update guard.** The product's cost is changed by the allocation only when this receipt is
  still the product's **latest cost-setting event**; if a newer receipt, restock or manual edit has
  since set the cost, the allocation posts to inventory and records a price history row noting it
  was not applied, and never overwrites the newer cost.
- **Known simplification.** Stock movement snapshots are immutable (FR-RET-03), so a charge that
  arrives after some of the goods were sold or transferred is added to inventory in full, and the
  cost of those earlier sales is not restated. The difference sits in the revaluation difference
  that valuation already reports (ADR-020 decision 8). The mitigation is procedural and built in:
  a GRN can be held `received, not costed` and the tenant setting `purchasing.require_costing_before_sale`
  (default off) refuses transfers out of it until it is costed.

### 7.3 Postings

See section 12.3. Plain restock postings do not change.

## 8. Reports

All reports are read only, respect branch scope per row, and omit cost, value and profit without
`retail.profit.read` covering the row's branch (absent, not null).

| Report | Content |
|---|---|
| Stock by store and shop | `GET /retail/reports/stock-by-location`: per product, the balance in each branch the caller may read, grouped by role (stores, then shops), a subtotal per role and a **combined total**; stock **in transit** (section 9.3) as its own column so the combined total neither double counts nor loses it. Extends `/retail/stock/all-branches`, which stays |
| Combined total | The sum across all readable branches, quantity and, with profit read, value at cost; the revaluation difference is shown as in valuation |
| Transfers in flight | `GET /retail/reports/transfers-in-flight` (needs `transfer_receipt`): dispatched and not received, with age, source, destination, lines. Empty by construction when transfers are atomic |
| Supplier payables | `GET /retail/reports/supplier-payables` (needs `purchasing`): per supplier, open bills by days past due (same buckets as customer ageing), unbilled receipts (goods received not invoiced) and open credit notes. For tenants without `purchasing`, credit restocks are shown from trade creditors by supplier |
| Customer ageing | Section 5.2 |

## 9. Workflow for the wholesaler

### 9.1 The target flow with only the first slice

Branch roles alone carry the whole wholesaler flow, with nothing else switched on:

1. Set Store 1, 2 and 3 to `store` and the shop to `shop` (or `both`).
2. Restock into a store with the existing restock screen (branch picker lists stores).
3. Transfer from a store to the shop with the existing transfer screen (atomic, at cost).
4. The shop sells with the existing sales screen; stores do not appear on it, and a call to sell
   from a store is refused.

### 9.2 Retail shop buying from the wholesaler

A retail shop is a different tenant. It records the purchase as a plain restock (a supplier, lines,
cash or credit) with no document on either side. The wholesaler may issue an invoice or delivery
note if it has `documents` on, and the shop never needs it.

### 9.3 Transfers: atomic by default, two-step optionally

Today a transfer is one action: out at the source and in at the destination in one transaction. That
stays the default. With `transfer_receipt` on, a transfer is **dispatched** (the source's out
movement and entry post at once, status `in_transit`) and later **received** (the destination's in
movement and entry), possibly with a received quantity different from dispatched, in which case the
difference is a recorded `transfer_shortage` posted as stock shrinkage at the source cost (the cost it left the source at).
While in transit the stock is in neither branch's balance. The in-transit value is the sum of the
open dispatches' source entries (the report is built from the transfers, not from the account):
account 1190 is shared with the lending chart, so its balance is **never** read as the in-flight
figure, and the reconciliation compares only the entries whose source is a retail transfer
(`retail.transfer_out`, `retail.transfer_in`, their voids and the shortage key).

**A shortage at receipt is valued at the source's cost** (the cost both sides already carry), not
the destination's: debit `stock_shrinkage` at the destination branch and credit
`interbranch_clearing` for the missing quantity at that cost, so the clearing legs of this transfer
net to zero. Receiving more than dispatched is refused (422 `received_exceeds_dispatched`).

**Cancel and void of a dispatched transfer.** Cancelling a transfer still `in_transit` moves each
line back to the source with a `transfer_in` of the dispatched quantity at the original cost and
reverses the source's out entry (the destination never booked anything); status `cancelled`. The
existing void remains for `completed` (atomic) and `received` transfers, with its rule that the
destination still holds what came in, applied to the received quantity.

**Built in slice 3 (issue #193, migration V38).** The choices the paragraphs above left open:

- `POST /retail/transfers` is the dispatch while `transfer_receipt` is on (no mode flag); the response
  status is `in_transit`. Only the source's `transfer_out` movement and entry exist until the receipt.
- The destination receives with `POST /retail/transfers/{id}/receive` (scope on the destination).
  The body names how much of every line arrived (0 up to the dispatched quantity), or is omitted for
  "all of it". What arrived is a `transfer_in` movement per line and one entry (debit inventory, credit
  clearing); the shortage is a second entry at the destination (debit `stock_shrinkage`, credit
  clearing, key `retail.transfer_shortage:<id>`), stored as `shortage_entry_id`, valued per line at the
  dispatch cost. A line with none received is allowed (a whole line of shortage).
- The source cancels with `POST /retail/transfers/{id}/cancel` (scope on the source): each line comes
  back as a `transfer_in` that reverses the dispatch movement, at the original cost, and the source
  entry is reversed (key `retail.transfer_out_cancel:<id>`). Cancelling a received, completed, cancelled
  or voided transfer is 409 `transfer_not_in_transit`.
- A transfer on its way cannot be voided (409 `transfer_in_transit`: cancel it); a cancelled one cannot
  (409 `transfer_cancelled`). Voiding a received transfer returns the dispatched quantity to the source
  and takes what arrived back from the destination, which must still hold it (422
  `transfer_stock_moved`), and reverses the dispatch, receipt and shortage entries, so the transfer is
  undone as a whole.
- Receive and cancel are money-moving (`Idempotency-Key`); a replayed key returns the first response and
  two racing receipts receive once (the transfer row is locked).
- Switching `transfer_receipt` off, directly or by choosing the `shop` profile, is refused (422
  `transfers_in_transit`) while any transfer is in transit.
- `GET /retail/transfers` gains a `status` filter, which is how the in-transit list is read with its
  lines; `GET /retail/reports/transfers-in-flight` adds the age, the value and the reconciliation.
- Not done here: the shortage is posted to the ledger but is not yet a line of the daily profit report.

## 10. Data model sketch

All tables are tenant-owned: `tenant_id`, forced RLS with the standard policy
(`bms_apply_tenant_rls`), composite foreign keys on `(tenant_id, x_id)`, money as `bigint` minor
units with a `currency`, quantities `numeric(14,3)`, grants through `bms_grant_app`. Append-only
where noted, with the `reject_mutation` trigger. Names are provisional.

| Table | Purpose and notable columns | Indexes |
|---|---|---|
| `retail_branch_roles` | `branch_id`, `role` in (`store`, `shop`, `both`), `changed_by`, `changed_at`; one row per branch; missing row means `both` | PK `(tenant_id, branch_id)` |
| `retail_product_units` | `product_id`, `unit_id`, `factor numeric(14,3) > 0`; one pack per unit per product | unique `(tenant_id, product_id, unit_id)` |
| `retail_price_lists` | `name`, `currency`, `strict`, `active`, `version` | unique `(tenant_id, lower(name))` |
| `retail_price_list_items` | `price_list_id`, `product_id`, `unit_id`, `min_qty`, `price_minor`, `valid_from`, `valid_to`; UPDATE limited to `valid_to` by a guard trigger | unique open row `(tenant_id, price_list_id, product_id, unit_id, min_qty) where valid_to is null`; lookup `(tenant_id, price_list_id, product_id, valid_from desc)` |
| `retail_customer_groups` | `name`, `price_list_id` | unique `(tenant_id, lower(name))` |
| `retail_customers` (add columns) | Slice 6: `credit_limit_minor`, `terms_days`, nullable. Slice 5 (price lists): `customer_group_id`, `price_list_id`, nullable, with a partial index on `price_list_id`. The two groups of columns arrive in the slice that needs them | as said |
| `retail_sale_returns`, `retail_sale_return_lines` | `sale_id`, `branch_id`, `reason`, `refund_method`, `refund_minor`, `journal_entry_id`, `status`; lines with product, qty, `line_total_minor`, `line_cost_minor`; append-only apart from the void | `(tenant_id, sale_id)`; `(tenant_id, branch_id, created_at)` |
| `retail_sale_lines` (add columns) | `sale_unit_id`, `sale_qty`, `sale_unit_price_minor`, `unit_factor`, `price_list_id`, `price_source`; all nullable for history | none new; the V26 covering index is kept |
| `retail_sales` (add column) | `price_list_id` nullable | none |
| `retail_document_series` | `type`, `prefix`, `next_no`; the row lock is the allocator | unique `(tenant_id, type)` |
| `retail_documents` | `type`, `number`, `sale_id` nullable, `quotation_id` nullable, `issue_date`, `status` (`issued`, `void`), `snapshot jsonb`, `valid_until`; append-only except `status` | unique `(tenant_id, type, number)`; unique `(tenant_id, sale_id, type) where sale_id is not null` |
| `retail_quotations`, `retail_quotation_lines` | draft lines, buyer, `valid_until`, `converted_sale_id` | `(tenant_id, status, created_at)` |
| `retail_purchase_orders`, `_lines` | supplier, branch, status, lines with ordered qty, unit, cost; `received_qty` is derived from GRN lines, not stored | `(tenant_id, supplier_id, status)` |
| `retail_goods_receipts`, `_lines` | branch, optional order, supplier; lines with received qty, unit, cost, `landed_unit_cost_minor` set by allocation; append-only; the movement carries `source_type = retail.goods_receipt` | `(tenant_id, supplier_id, received_on)` |
| `retail_supplier_bills`, `_allocations` | supplier, number, date, due date, amount, `kind` (`goods`, `landed_cost`), allocations to GRNs | unique `(tenant_id, supplier_id, number)` ; `(tenant_id, supplier_id, due_date)` for ageing |
| `retail_supplier_credits` | supplier, kind (`goods`, `price`), lines, amount | `(tenant_id, supplier_id)` |
| `retail_supplier_payments` | supplier, method, amount, bill allocations, journal entry | `(tenant_id, supplier_id, paid_on)` |
| `retail_transfers` (change) | V22 is not just extended. It (a) limits `status` to `completed` or `voided`, (b) requires `out_entry_id` and `in_entry_id` to be both null or both set, (c) ties `voided_at`, `voided_by` and `void_reason` to the status `voided` in two further checks, and (d) has a guard trigger that lets an UPDATE change only the void columns once. Migration V38 **replaces** all five: (a) `status` in (`completed`, `in_transit`, `received`, `cancelled`, `voided`); (b) `completed` keeps both-or-neither, `in_transit` has the source entry only (none at zero cost), `cancelled` has no destination entry, `received` has the source entry unless the cost is zero (its destination entry is null only when nothing of value arrived), `voided` has no rule because it follows either; (c) the void columns belong to `voided` and `cancelled`, and `received_at` and `received_by` are set together, only on `received` or a received transfer later `voided`; (d) the guard allows exactly `in_transit` to `received` (sets `in_entry_id`, `shortage_entry_id`, `received_at`, `received_by`, once), `in_transit` to `cancelled` (void-style columns once), and `completed` or `received` to `voided` as before, and refuses every other change. Added columns: `received_at`, `received_by`, `shortage_entry_id`; line `received_qty` (set once while in transit, so the lines' append-only trigger becomes a guard that allows only that). Each new check is added `NOT VALID`, validated and the old one dropped last; the transfer table is small so this is one migration. Existing rows are all `completed` or `voided` and satisfy every new rule | partial `(tenant_id, created_at desc, id desc) where status = 'in_transit'` |

Stock movement kinds gain `return_to_supplier` (negative). `retail_stock_movements` is the largest
table and its `kind` and sign checks (V11) must be **replaced**, so the migration adds the new
constraints `NOT VALID` (a metadata change that takes no long lock), and a separate, later
migration runs `VALIDATE CONSTRAINT`, which scans without blocking writes; the old constraints are
dropped only after validation. `transfer_shortage` is not a kind: a shortage is a recorded
difference posted as shrinkage, with the receipt as its source. RLS tests
(NFR-ISO-04) cover every table above; the isolation test enumerates tables from the catalogue, so a
table without the policy fails CI.

Sequences: document numbers come from `retail_document_series`, not a database sequence, so a
failed issue cannot leave a gap; purchase order, GRN and bill numbers follow the existing
`RP00000001` style, per tenant.

## 11. API sketch

All routes are under `/retail`, refused with 404 `module_not_enabled` when retail is off and 404
`capability_not_enabled` when the capability is off. `M` requires `Idempotency-Key` (SDD 7.8).
Branch-bound routes check scope per ADR-017. Full catalogue goes in SDD 7.11.23.

| Method | Path | Permission | Capability |
|---|---|---|---|
| GET, PUT | `/retail/settings/capabilities` | `core.settings.manage`; PUT needs `If-Match` (the settings version, as `PATCH /settings`) | none |
| GET, PUT | `/retail/branch-roles`, `/retail/branch-roles/{branch_id}` | read: `retail.stock.read`; write: `retail.branch_role.manage` | `branch_roles` |
| GET, POST, PATCH | `/retail/products/{id}/units`, `/retail/products/{id}/units/{unit_id}` | read: `retail.stock.read`; write: `retail.catalogue.manage` (built, issue #194) | `units` |
| GET, POST, PATCH | `/retail/price-lists`, `/retail/price-lists/{id}`, `/retail/price-lists/{id}/items`, `/retail/price-lists/{id}/items/{item_id}`; `/retail/customer-groups` (GET, POST, PUT); `PUT /retail/customers/{id}/pricing` | read and write: `retail.pricelist.manage` (built, issue #195; the design said read `retail.sale.create`, which would let any seller list every customer's negotiated prices, so the sale asks `/retail/prices/resolve` instead) | `price_lists` |
| POST | `/retail/prices/resolve` | `retail.sale.create` (built, issue #195) | `price_lists`; `{customer_id?, price_list_id?, branch_id, lines: [{product_id, unit_id?, qty}]}` returns price and source per line, never cost |
| POST | `/retail/sales` | `retail.sale.create` | existing route; lines gain `unit_id?`, request gains `price_list_id?` |
| GET, PUT | `/retail/customers/{id}/account` | read `retail.sale.read`; write limit and terms `retail.credit.manage` (built, issue #196) | `customer_accounts` |
| GET | `/retail/customers/{id}/statement` | `retail.sale.read` (built, issue #196) | `customer_accounts` |
| POST (M), GET | `/retail/customers/{id}/payments` | `retail.sale.create`; list `retail.sale.read` (built, issue #196) | `customer_accounts` |
| POST (M) | `/retail/customers/{id}/payments/{payment_id}/reverse` | `retail.sale.void` at the receiving branch (built, issue #196; not in the first design) | `customer_accounts` |
| POST (M), GET | `/retail/sales/{id}/returns` | `retail.sale.return` | `returns` |
| GET | `/retail/reports/customer-ageing` | `retail.sale.read` | `customer_accounts` |
| POST (M), GET | `/retail/sales/{id}/documents`, `/retail/documents/{id}` | `retail.document.issue` | `documents` |
| POST, GET, PATCH | `/retail/quotations`, `/retail/quotations/{id}/convert` | `retail.document.issue`; convert needs `retail.sale.create` | `documents` |
| POST (M), GET | `/retail/purchase-orders`, `/retail/goods-receipts`, `/retail/supplier-bills`, `/retail/supplier-credits`, `/retail/supplier-payments` | `retail.purchase.create` (orders and receipts), `retail.payable.manage` (bills, credits, payments) | `purchasing` |
| POST (M) | `/retail/supplier-bills/{id}/landed-cost` | `retail.payable.manage` | `purchasing` |
| POST (M) | `/retail/transfers/{id}/receive`, `/retail/transfers/{id}/cancel` | `retail.stock.transfer` | `transfer_receipt` |
| GET | `/retail/reports/stock-by-location`, `/retail/reports/transfers-in-flight`, `/retail/reports/supplier-payables` | `retail.stock.read` (payables: `retail.payable.manage`) | as stated in section 8 |

New permissions (design only; each is added to the SDD 8 matrix by the migration of the slice that needs it, so the matrix never lists a permission no migration seeds):
`retail.branch_role.manage`, `retail.pricelist.manage`, `retail.pricelist.use`,
`retail.price.override`, `retail.credit.manage`, `retail.credit.override`, `retail.sale.return`,
`retail.document.issue`, `retail.payable.manage`.
Seeding rule: only `retail.price.override`, which guards behaviour the sales role already has (a unit price override), is granted to every role that holds `retail.sale.create`. Every other new
permission, including `retail.pricelist.use`, goes to administrators only.

## 12. Ledger postings

New rows for SDD 6.11.2. Existing rules do not change. All post through `post_entry`, one entry per
branch, in the transaction of the event, with idempotency keys of the form `retail.<event>:<id>`.

### 12.1 Accounts added (seeded when the capability is first switched on, kept if present)

| Code | Name | Type | `system_key` | Used by |
|---|---|---|---|---|
| 2110 | Goods received not invoiced | liability | `goods_received_not_invoiced` | `purchasing` |
| 5120 | Purchase price rounding | expense | `purchase_rounding` | `units` |
| 5130 | Purchase price variance | expense | `purchase_price_variance` | `purchasing` |
| 4110 | Sales returns | income (contra) | `sales_returns` | `returns` |

### 12.2 Sales, units, transfers

A sale in a pack unit posts the same two entries as today (revenue and cost), from `line_total_minor`
and the base-quantity cost snapshot. A credit sale beyond a limit is refused before posting. Price
list use changes no posting. A two-step transfer posts the existing out entry at dispatch and the
existing in entry at receipt; a shortage at receipt debits `stock_shrinkage` and credits
`interbranch_clearing` for the missing quantity at the source cost (section 9.3), so this transfer's clearing legs return to zero. A return posts as section 5.3.

### 12.3 Purchasing

| Event | Debit | Credit | Key |
|---|---|---|---|
| Goods received, per branch | `inventory`, quantities at the line costs | `goods_received_not_invoiced` | `retail.goods_receipt:<id>` |
| Supplier invoice for goods | `goods_received_not_invoiced`, the receipts it clears; `purchase_price_variance` for a difference | `trade_creditors` (supplier as subledger), the bill amount | `retail.supplier_bill:<id>` |
| Landed cost allocation | `inventory`, the allocated amount | `trade_creditors` (the carrier, subledger) | `retail.landed_cost:<id>` |
| Supplier credit, goods returned | `trade_creditors` | `inventory` at the cost returned; any remainder to `purchase_price_variance` | `retail.supplier_credit:<id>` |
| Supplier credit, price adjustment | `trade_creditors` | `inventory` for goods still on hand at the receipt, else `cost_of_goods_sold` | `retail.supplier_credit:<id>` |
| Supplier payment | `trade_creditors` | `cash_on_hand`, `mobile_money` or `bank` | `retail.supplier_payment:<id>` |
| Voids | The reversal of each entry (`reverses_entry_id` set) | | `<key>_void` |

## 13. Test strategy

Follows SDD 15.4.2 and adds, per slice:

- **All off equals today.** A contract test runs the existing retail suites (sales, restock,
  transfer, valuation, import, reports) on a tenant with every capability off and after each new
  migration, and requires identical responses. This is the guard for plain shops.
- **Isolation (NFR-ISO-04).** Every new table: another tenant cannot read or write; the catalogue
  enumeration test fails a table without forced RLS.
- **Roles.** Sales refused at a store (service and API), allowed at shop and both; transfer from
  store to shop; role off means both; role change refused with an unreceived transfer; the matrix of
  section 2.2 as a table-driven test.
- **Units.** A pack sale above cost on the line total is accepted although the rounded per-base price equals the cost; property tests: for random factors and quantities, `base_qty` is exact or refused,
  stock is conserved across a pack sale and a base sale; rounding remainder equals the
  `purchase_rounding` posting; a whole month of mixed-unit sales reconciles to the ledger (the
  date-logic rule of testing a whole month).
- **Price lists.** Resolution order; largest break not above the quantity; effective-date edges
  (valid on the first and last day); overlap refused under concurrency; below-cost refused on the
  resolved price; cost absent without profit read; editing a list leaves old sales' snapshots.
- **Customer accounts.** Limit at the boundary (equal allowed, above refused); **two shops selling
  to one customer concurrently cannot both pass the limit** (two connections, deterministic);
  a credit sale with only a buyer name is 422 `customer_required`; the sales role cannot set a limit
  (403 without `retail.credit.manage`); historical rows counted in exposure and excluded from the
  debtors reconciliation and from automatic allocation; payment on account posts in the receiving
  branch and skips out-of-scope sales; statement closing equals the caller's branch-scoped customer
  balance (and the tenant-wide balance for a full-scope caller); payment on account allocates oldest first and never overpays; a tenant with
  null limits behaves as today.
- **Documents.** Gapless numbering under concurrent issue; a failing render leaves the sale
  committed; a void sale voids its invoice and keeps the number; the capability off hides routes
  (404) and every sale test passes without any series existing.
- **Purchasing.** GRN then bill clears the accrual to zero; partial receipts; bill with a price
  difference posts variance; landed cost allocation adds exactly (remainder rule); credit note
  cannot return more than on hand; every entry balances (deferred balance trigger); plain restock
  results are identical on the old and new path.
- **Returns.** A return cannot exceed sold less returned; stock and cost restore at the sale snapshot; `account` refund never takes the balance below zero; sales analysis nets returns; every entry balances.
- **Transfers.** The replaced status check, entry-pair check and update guard allow exactly the transitions of section 10 and refuse the rest; cancel of a dispatched transfer restores the source and reverses its entry; in-transit stock is in neither balance and the in-flight report equals the open dispatches (never read from account 1190);
  shortage posts to shrinkage and clearing returns to zero; cancel restores the source.
- **Permissions.** Every new route in the route permission test; cost fields absent without profit
  read on every new response; a branch-scoped caller cannot see another branch's balances in the
  location report or customer exposure.
- **UI.** Component tests per capability combination (issue #185), tours reference only existing
  anchors (the existing tour test).

## 14. Adversarial review of this design

Answers written after one pass looking for what breaks.

### 14.1 What breaks for a plain shop

| Risk | Answer |
|---|---|
| A new column or table slows or changes the sales path | The sale path reads capability flags once per request; with all off it takes the pre-existing code path (no list lookup, no unit conversion, no role lookup). New columns on `retail_sale_lines` are nullable and add no index; the covering index V26 is untouched, so the daily profit query plan is unchanged. The performance benchmark queries k01 to k15 run in the slice that adds columns |
| A menu or tour shows something new | Nothing renders unless its capability is on; the component test "all off equals today" fails otherwise |
| A new permission appears and the sales role loses something | A permission that guards existing behaviour (`price.override`) is seeded to every holder of `retail.sale.create`; `pricelist.use` is not, because it names a list other than the customer's own, which no seller could do before. They are only consulted when the capability is on |
| The profile preset surprises an administrator | `shop` is the default and switches nothing on; `trade` is an explicit choice that lists the flags it will set and asks for confirmation; flags remain individually editable |
| Settings read fails or a flag is absent | Absent reads as off. A failure to read settings fails the request closed, as every settings read does |
| A plain shop is accidentally given `branch_roles` and a branch is a store | Role changes are an explicit administrator action; the default is `both`; a store can never be created by default |

### 14.2 What breaks for existing data

| Risk | Answer |
|---|---|
| Historical sale lines have no unit columns | Null means "entered in the base unit"; every report keeps summing `qty` and `line_total_minor`, which keep their meaning |
| Stock quantities change meaning | They do not: stock stays in the base unit; packs convert at the edge |
| Imported credit sales (issue #96) | Never posted by this design. They stay `historical`, count in exposure, are excluded from the debtors reconciliation and statement totals shown as a separate section. The #96 decision (a), (b) or (c) needs no change to the account screens |
| A branch later changed to `store` has past sales | Past sales stay, can be voided, paid and reported; only new sales are refused |
| Backfill misses a branch | The missing-row rule reads as `both`, so a miss is harmless |
| Changing a product's base unit once packs exist | Refused (422 `unit_in_use_by_pack`): factors are relative to the base, so changing it would silently rescale stock |
| Open transfers when `transfer_receipt` is switched on or off | On: existing completed transfers are untouched. Off while any transfer is `in_transit`: refused until they are received or cancelled |
| Switching `price_lists` off with open quotations | A quotation keeps its stored prices; conversion re-prices at the product price when lists are off, and says so |
| Landed cost after goods were sold | Documented simplification (7.2) reported through the revaluation difference; mitigation setting `purchasing.require_costing_before_sale` |
| Pack cost rounding | One visible account `purchase_rounding`, bounded by half a minor unit per base unit |
| Migrations | Mostly additive, but not all: slice 3 replaces the transfer status check, entry-pair check and update guard, and slice 8 replaces the stock movement kind and sign checks. Replacements add the new constraint `NOT VALID` first and `VALIDATE` in the same migration for a small table (a later migration for the largest tables), and drop the old one last. Numbering is assigned at merge, and the migration order test (issue #172) is updated by each slice |

### 14.3 Permissions

| Risk | Answer |
|---|---|
| Role used as authorisation | It is not: it can only refuse. A user's permissions and branch scope are checked first and unchanged |
| Cost leaks through the new surfaces | Price resolution returns price and source only; list margin and below-cost warnings, supplier bill margins, landed unit costs and the location report's value need `retail.profit.read` covering each branch; absent, never null |
| A sales clerk raises a customer's limit | Limit and terms need `retail.credit.manage`, administrators only; `retail.customer.manage` (held by the sales role) edits name and contact only |
| Credit exposure leaks other branches' sales | The limit check runs server side tenant-wide and returns only "exceeded by X"; the statement and ageing are filtered by the caller's scope |
| A store clerk sells by a direct call | The service refuses on role, not the screen (422); covered by the API test |
| Document issue exposes a sale outside scope | The document route resolves the sale under the caller's scope first (404 otherwise); the snapshot is built from the scoped view |
| A credit sale names no customer to dodge the limit | With `customer_accounts` on a credit sale requires a customer record |
| Quotation conversion bypasses the price floor | Conversion runs the normal sale path, including below-cost and override rules |
| Supplier payables and bills seen by shop staff | `retail.payable.manage` is administrators only; the sales role has none |
| Self-approval of high purchase orders | Registered as a maker-checker action (ADR-015) when a threshold is set; the database enforces checker is not maker |
| A capability turned on or off silently | Audited with old and new values; administrators only |
| Idempotency | Every money-moving new route claims an `Idempotency-Key` |

### 14.4 Findings that changed the design

- Role could have been a column on `branches`; it is a retail table instead, so the core `branches`
  table is not touched for one vertical and the missing-row rule needs no data migration to be
  correct. The core still gains one vertical-neutral hook for module settings groups (section 1.1),
  because settings and `/me` are core.
- The first draft stored the retail price as a default price list. It stays on the product, so
  nothing about prices changes for plain shops and no price is copied (the pilot's failure mode of
  copied prices, ADR-020 context).
- Transfers in flight need the two-step transfer; making it a capability keeps the atomic transfer,
  which plain shops rely on, as the default.

## 15. Risks

1. **Scope creep toward a full ERP.** Mitigation: each capability is a slice with a stop line; the
   deferred list below is firm.
2. **Rounding of pack costs on currencies with no minor subunit** is visible as a small expense;
   a finer cost scale would be a later change touching every cost column.
3. **Landed cost timing** (7.2) is an accepted simplification.
4. **Capability combinations** multiply test paths; the all-off contract test and one component test
   per combination bound it.
5. **Documents creeping into the sale's critical path.** Mitigation: the after-step rule and a test
   that fails the sale suite if issuing a document is required.
6. **Migration number collisions** with parallel branches: numbers are assigned at merge time and the
   migration order test is updated by each slice (issue #172).

## 16. Deliberately deferred

Weighted average cost (pending ADR-021); tiered or mix-and-match quantity breaks across lines;
promotions and coupons; tax and VAT handling on documents (the module has no tax model today);
unallocated customer deposits; server-side PDFs (documents
follow-up, issue #120); sending documents by message or email; serial or batch tracking and expiry;
multi-currency lists; barcode scanning for pack units; reorder points and automatic purchase orders;
bank reconciliation of supplier payments; role-specific mobile layouts for store clerks beyond
hiding the sales screen.

## 17. Build plan

Ten slices, each a GitHub task referencing #190, each independently mergeable with all
capabilities off changing nothing. Order matters: **slice 1 alone unblocks the wholesaler flow**
(section 9.1).

| Slice | Issue | Delivers | Depends on |
|---|---|---|---|
| 1 | #191 | Branch roles, the capability settings group and the core module-settings hook; enforcement on sales only | none |
| 2 | #192 | Administrator screen for the profile and the flags, capability-aware menus and tours for the other capabilities (with #185). The profile preset and `module_capabilities` in `/me` are built in slice 1. Built: no migration and no API change; PWA only (`docs/specs/retail-ui-notes.md`, "Retail settings and branch roles") | 1 |
| 3 | #193 | Stock by store and shop report, combined total, and two-step transfers (replaced status check, entry-pair check, void-column checks, update guard) with the in-flight report. **Built**, migration V38 | 1 |
| 4 | #194 | Units of measure: packs, conversions, sale and restock in any unit, import columns | 1 |
| 5 | #195 | Price lists, quantity breaks, effective dates, customer groups (adds the group and list columns to customers). **Built**, migration V41 | 4 |
| 6 | #196 | Customer accounts: limit, terms (admin only), ageing, statement, payment on account. **Built**, migration V44 | none |
| 7 | #201 | Customer returns and sales credit notes (`returns`) | 6 |
| 8 | #197 | Optional documents: invoice, delivery note, quotation, credit note, numbered series | 5, 6 and 7 optional |
| 9 | #198 | Optional purchasing: purchase order, goods received, supplier invoice, supplier credit, supplier payment (also against credit restocks) | 1, 4 |
| 10 | #199 | Landed cost allocation and the supplier payables report | 9 |
