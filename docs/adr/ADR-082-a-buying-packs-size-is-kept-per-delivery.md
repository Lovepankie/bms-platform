# ADR-082: A buying pack's size is kept per delivery, and "use from now on" changes the usual size

## Status

Proposed (owner decision D23 of 23 Oct 2026, issue #554; docs/specs/units-packs-and-costing.md section 3 and section 9 step 2),
accepted on merge. Amends the ADR-033 amendment for units of measure (issue #194), decisions 1 and 5 (a line converts with its
pack's one factor, which belongs to the catalogue), for buying only, and builds on ADR-079 (the average cost per smallest unit): a delivery re-averages at its line's
own cost per piece and takes the product row lock before any balance row, as ADR-079 orders. Adds FR-RET-72. No migration.

## Context

Cartons of the same item do not always hold the same number of pieces: one supplier's carton has 24, another's 30 (spec section
1). Until now a pack had one size (`retail_product_units.factor`), and Buy stock and goods received converted every pack line with
it. Recording a carton of 30 meant editing the item's pack to 30 before the delivery and back to 24 after it, which changed
selling, scanning and every later delivery in between, or typing the pieces by hand and losing the carton and its price. The
owner decided (D23) that a buying pack may change size per delivery while a selling pack keeps one fixed size and its own price.

Both line tables already keep the factor in force when the line was made (`unit_factor` on `retail_purchase_lines` since V39 and
on `retail_goods_receipt_lines` since V47), and every later reading of a line (the void of a restock or a receipt, the supplier
invoice's three-way match, a supplier credit for goods sent back, a landed cost, the purchases summary) already converts with the
line's own `unit_factor`, never the item's current one.

## Decision

1. **The size is the line's.** A restock line and a goods received line in a pack take an optional `pack_size`: how many base
   units each pack held in this delivery, at least 1 with at most three decimals (422 on the line's `pack_size` field otherwise, and
   on a line not in a pack). Absent means the pack's usual size, as before. It is stored in the line's existing `unit_factor`, so
   the line's base quantity is the packs times that size, the cost per base unit is the pack cost divided by it (rounded half up,
   the remainder to purchase rounding as before), and the stock movement and the branch's average (ADR-079) use them. The packs
   times the delivery's size must be exact in thousandths (422 `quantity_not_representable`) and fit the quantity columns, per
   branch and for the line (422 `quantity_out_of_range`), judged with this size and never the usual one. No column is
   added: `unit_factor` already means "the factor in force when the line was made".
2. **Use from now on.** `use_from_now_on: true` with a `pack_size` also makes that size the pack's usual size
   (`retail_product_units.factor`), in the same transaction, with a `retail.product_unit.updated` audit row naming the delivery
   (`source_type`, `source_id`). Without it the usual size stays. It changes the item, so it needs `retail.catalogue.manage`, the
   permission that edits packs (403 `permission_denied` in plain words before anything is written); someone who only buys records
   the delivery's own size. Two lines of one request that set it are applied in request order, so the later one wins, as for prices.
3. **Selling, scanning and the item are unchanged.** A carton sold is always the usual size; a carton barcode still names the pack,
   and on Buy stock and goods received fills the pack and its usual size, which the person may change for this delivery.
4. **Lock order.** The update of the usual size is made after the product rows are locked in id order and before the stock ledger
   locks any balance row (ADR-079 watch for), the order a pack edit (product row lock) and every delivery already take. Two
   deliveries of one item serialise on the product row; each one's average is taken under the balance row's lock as before.
5. **An order counts packs.** Goods received against an order line made in a pack count the packs received in that unit whatever
   each held (ten cartons of 30 receive ten cartons ordered at 24); a line received in another unit counts its base units in packs
   of the size ordered. The over-receipt allowance and the order's status (`partly_received`, `received`) use that measure, and the
   order line carries it as `received_unit_qty` with the item's `base_unit`, so the screen shows what is still to come in cartons.
   `received_qty` stays in base units.
6. **Units off is today.** With the units capability off no line may name a pack, so no line may carry a size and nothing changes.

## Consequences

**Better:** a delivery of cartons of a different size is recorded as it came, with its stock, its cost per piece and the average
right, without touching selling or the item; voids, supplier invoices and goods sent back take the line's own size, because they
already read it from the line; the owner changes the usual size from the delivery that showed it, in one tick.

**Worse:** the item's pack no longer tells how many pieces every past carton held; the line does. A purchase order's received
figure in packs and in base units can disagree when cartons differed (both are shown).

**Watch for:** anything new that converts a stored purchase or receipt line must use the line's `unit_factor`, never the item's
current pack. Selling packs with their own prices (spec step 3) must keep the selling size fixed as decided here.
