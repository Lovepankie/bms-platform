# Retail MVP scope

**Status:** Draft · **Owner:** Hillary · **Applies to:** the first retail tenant, then any retail tenant

Decision record: ADR-020. Tracking: story #50, tasks #51 to #56.

The retail pilot is a multi-shop electrical retailer. Today it runs shop stock, sales, restocks,
usage and cash handling from a spreadsheet with a form app on top. The MVP puts **stock, sales,
purchasing and valuation** on the platform, operated by shop staff on phones and by admins on
any device, starting from an import of the existing spreadsheet. The cash book follows.

The module is generic. A second retail tenant is the module switch plus a catalogue import.

## 1. Principles

1. Stock is derived from append-only movements. No screen or job edits a balance.
2. A price changes only inside the event that carries it (a restock or an audited edit), and
   every change leaves a history row.
3. Sales snapshot cost and price. Profit never depends on today's price.
4. Every financial event posts to the ledger in the same transaction (ADR-004).
5. Overselling is refused, as in the pilot's current app. There is no setting to allow it (ADR-020).
6. No client names, personal data or real figures in this repository. Real data is imported
   from outside it; the golden test uses a fabricated fixture.

## 2. Increments, in build order

| # | Increment | Delivers | Demo at the end |
|---|---|---|---|
| R1 | **Module and catalogue.** Manifest, categories, units, products, price history, permission catalogue, role mapping, default retail chart seeded on module switch | FR-RET-01, FR-RET-02, FR-RET-13, FR-RET-14 | Admin creates products; a price edit leaves a history row; a tenant without the module is refused |
| R2 | **Stock and sales.** Movements and balances, sale (cash, mobile money, bank, credit), void, stock-take, ledger posting for sales and their cost | FR-RET-03, FR-RET-04, FR-RET-05, FR-RET-08, FR-RET-11 | A sale at one branch moves only that branch; stock-take shows variance and adjusts |
| R3 | **Purchasing and usage.** Suppliers, restock that sets prices atomically, usage and damage, payment against a credit sale, ledger posting for restock and usage | FR-RET-05, FR-RET-06, FR-RET-07, FR-RET-11 | A restock at a new price changes price and valuation at once, with a history row |
| R4 | **Valuation and profit.** Stock value at cost, expected sales at price, daily profit per branch (admin only), stock view with negative flags | FR-RET-09, FR-RET-10 | Admin sees valuation and profit; a sales user cannot |
| R5 | **Import.** An `import-retail` application command (the same pattern as the existing `keys` command) that reads a normalised export of the pilot spreadsheet or a generic catalogue file and writes through the module's own services: products, suppliers, customers, history flagged historical, legacy balance movements, one opening journal per branch; a golden test on a fabricated fixture. The core import framework (review queue, preview, maker-checker commit; FR-IMP) is not built yet and follows later | FR-RET-12 | Fixture imported end to end on staging with balances and valuation matching exactly |
| R6 | **PWA screens.** Sale, restock, usage, stock, stock-take, valuation and profit, phone first | FR-RET-15 | A shop user records a sale and a restock on a phone; an admin reviews valuation |

R2 to R4 and R6 are built in parallel against the API contract in `docs/api`. R5 starts once R1
to R3 have merged. Target: usable on staging by 2026-10-07.

## 3. Requirements

| ID | Requirement |
|---|---|
| FR-RET-01 | Products with category, unit of measure, current cost and sell price, active flag. Categories and units are managed per tenant. |
| FR-RET-02 | Price history: every change to cost or sell price records old and new values, who, when and the source (restock, manual edit, import). |
| FR-RET-03 | Stock movements are append-only. Balance per branch and product is derived and reconciled. A sale or usage above the branch's available stock is always refused. Negative balances can only come from imported history and are flagged for the first stock-take. |
| FR-RET-04 | A sale has lines, a branch taken from the user's context, a payment method or credit, an optional buyer, and snapshots of unit cost and unit price. A unit price not above the product's cost is refused unless the user holds `retail.price.below_cost`. Shop staff record sales for their assigned branch only. A void reverses the movement and the journal. |
| FR-RET-05 | A credit sale records the buyer and a proposed payment date. Payments against it reduce the trade debtor balance and may be partial. |
| FR-RET-06 | A restock records the supplier and per-branch quantities. Cost and sell price on its lines update the product and the history in the same transaction. |
| FR-RET-07 | Usage and damage reports reduce stock, record a reason and are valued at cost. |
| FR-RET-08 | A stock-take records counted quantities per branch and product and the balance when counted, shows the variance and on commit writes an adjustment of counted less that balance, so trading between count and commit stays in the balance. If later movements would make the result negative, the commit is refused (`stock_moved_since_count`) and those lines are recounted. |
| FR-RET-09 | Valuation shows quantity times cost and quantity times sell price per branch and product, with totals. |
| FR-RET-10 | Daily profit per branch and day equals sale lines less cost snapshots less usage at cost, and is readable only with `retail.profit.read`. |
| FR-RET-11 | Sales, their cost, restocks, usage and voids post balanced entries to the right branch through `post_entry`. |
| FR-RET-12 | Import of the pilot spreadsheet and a generic catalogue template; history marked historical posts no journals; a legacy balance movement ties each balance to the source; re-running is idempotent. |
| FR-RET-13 | Retail permissions and the sales and admin role mapping, with branch scope. In the pilot, recording purchases, creating products and seeing cost, valuation and profit are admin only. |
| FR-RET-14 | Every retail write is audited. |
| FR-RET-15 | Phone-first PWA screens for the flows above, permission gated. |
| FR-RET-16 | Stock transfers between branches: one action moves one or more products from a source branch to another branch in one transaction, at the source's current cost (no profit or loss), refused when the source lacks stock, shown as a transfer in both branches' history, posted as one entry per branch through inter-branch clearing, and voidable while the destination still holds the stock. Needs `retail.stock.transfer` on the source branch. Added by issue #84 (ADR-020 amendment); imported history is not rewritten. |

These are adopted into chapter 3 section 3.28 by R1; FR-RET-16 by issue #84.

## 4. Out of the first release

Cash book (expenses, banking, withdrawals, advances to the owner or company, a daily savings
target), wider financial and sales reports, receipt photos and location capture, offline use,
barcode scanning, weighted average cost, supplier statements beyond
trade creditors, SMS receipts, returns other than a void. The cash book is designed in ADR-022
(proposed): a retail sub-domain, not core, with its requirements in FR-RET-17 to FR-RET-32
and the comparison with the pilot app in `docs/specs/retail-cash-book.md`.

## 5. Onboarding a retail tenant

1. Platform operator creates the tenant, switches the retail module on and sets the first admin.
2. Admin creates branches, invites staff and assigns the sales or admin role.
3. Admin sets categories and units, or imports a catalogue.
4. Import opening stock, or run a stock-take as the first act.
5. Start recording sales and restocks. For a tenant coming from another system: freeze the old
   system, import the delta since the last import, then switch over. A one-period parallel run
   is recommended.

## 6. Open questions for the pilot

1. Which roles may void a sale, edit a price, and commit a stock-take?
2. Do credit sales need an approval above a limit?
3. Which branches trade, and which are only cash points?
4. Which units and categories are authoritative where the source has near duplicates?
5. When is the cutover, and who counts stock first?

## 7. Parity baseline and other data shapes

The pilot's current app behaviour is written down in `docs/specs/retail-pilot-app-behaviour.md` and is the
acceptance baseline for what the retail screens must do or deliberately change.

Onboarding a tenant with a different data shape (another retailer, a lender, and later a mobile money
agent business, pending ADR-023) is a mapping from that tenant's source to the importer's normalised
export, reviewed once and run outside the repository. It is not a code change. The mapping layer and
its templates become part of the core import framework when that is built.
