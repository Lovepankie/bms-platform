# ADR-123: A wrong usage or damage entry is undone like a voided sale

## Status

Accepted (issue #818, from the owner's old app compared screen by screen with BMS on 3 November 2026; FR-RET-88). Builds on
FR-RET-04 (the void of a sale), FR-RET-07 (usage and damage), ADR-079 (the average cost) and ADR-047 (the Controls page).
Migration `V133`; renumbered at merge if the order changes.

## Context

A "used in the shop" or damage entry takes stock out at the branch's average cost and posts the cost to stock shrinkage. Until
now a wrong entry could only be put right with a stock count, which hides what happened: the shrinkage stays on the books, the
count looks like a miscount, and nobody sees who made the mistake or why. The owner's old app let a wrong entry be taken back,
and the owner asked for the same here, with the entry kept.

A sale already has this: a void reverses its stock and its journal entries on the day of the void, keeps the sale marked
voided with who, when and why, and shows on Controls.

## Decision

1. **Undo, never edit or delete.** `POST /retail/usage/{report_id}/undo` with a required `reason` (at most 300 characters).
   `retail_usage_reports` stays append-only and unchanged; one row of the new `retail_usage_undos` (forced RLS, append-only,
   `UNIQUE (tenant_id, report_id)`) names the entry with `undone_on`, `reason`, `undone_by` and its time.
2. **The stock and the books go back at the same cost.** Every stock movement of the entry is reversed by a `return` movement
   of source type `retail.usage_undo` naming the movement it reverses (`reverses_movement_id`, unique since V11), at the unit
   cost the entry carried, so the average re-averages exactly as before the entry (ADR-079). The shrinkage entry is reversed by
   the ledger's reversal, which names the entry it reverses; inventory comes back to its value before the entry.
3. **The day of the undo, as a void.** The return movements and the reversal are dated today, the business day of the undo,
   never the day of the entry, so a closed day keeps its figures, exactly as a sale void does. A closed accounting period
   refuses the reversal as it refuses a void.
4. **The same permission as a void.** The route needs `retail.sale.void`, and the entry's branch must be in the caller's scope
   for it; an entry outside it is 404, as a sale is. No new permission: whoever may take back a sale may take back a usage
   entry, and nobody else (a storekeeper who reports usage cannot undo it). Sale voids may ask for approval above an amount
   (ADR-091); an undo does not, since no money changes hands and it is shown on Controls.
5. **Once.** The undo row is written first; a second undo, or two at once, finds the unique key taken and gets 409
   `usage_undone`. An entry brought in from old records (`historical`) is refused with 422 `usage_imported`; it has no entry of
   its own to reverse, and the stock list's correction is the way.
6. **Audited and shown.** The audit entry `retail.usage.undone` records the reason, as `retail.sale.voided` does. The movements
   list carries `undone` (`at`, `by`, `by_name`, `reason`) on each movement of an undone entry; Past usage and damage shows
   "Undone" in words with an icon and who, when and why, and its grouped totals leave undone entries out, as a voided sale counts
   in no sales total; so do the daily profit's usage and the stock health's usage, which read the entries. The item page names
   the stock that came back "Usage or damage undone", never a customer return. Controls gains the card `usage_undos` (who entered it, who undid it, the reason, the item kind, the value at
   the selling price now, never the cost; no rate, as the other stock cards).

## Consequences

**Better:** a wrong entry is put right in the open, with its reason, and the books and stock read as if it had never been
made, from the day it was undone. The owner sees every undo on Controls next to voided sales.

**Worse:** the flat list shows an undone entry with its mark while the grouped totals leave it out; the hint and the mark say
so. One more table and one more Controls card.

**Watch for:** an entry undone and then entered again correctly is two entries and one undo; Controls counts the undo. If the
owner later wants undoing to wait for an administrator above an amount, it becomes an approval action like `retail_sale_void`.
