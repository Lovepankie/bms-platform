# ADR-118: Whoever records expenses adds a missing expense item in place

## Status

Proposed (issue #802, 3 Nov 2026; FR-RET-17, FR-RET-24). Relaxes ADR-022 decision 13 for one thing: adding an item to an
active expense category, which moves from `retail.expense.manage` to `retail.expense.record`. Categories, renaming, switching
off and the "needs an explanation" flag stay with `retail.expense.manage`. No migration. Renumbered at merge if needed.

## Context

A pilot shop's expense items keep changing. On Expenses, a category with no items said "This category has no items yet. Ask
the owner to add its items first.", and a missing item in a category that had others could not be recorded at all, so sales
staff waited for the owner or picked a wrong item. The same screen already lets them add a beneficiary on the fly with
`retail.expense.record` (ADR-022 decision 13). The owner asked for items to work the same way.

## Decision

1. **The add route takes the record permission.** `POST /retail/expense-categories/{category_id}/items` declares
   `retail.expense.record`. A caller without `retail.expense.manage` may add an item only to an active category (422
   `category_inactive` otherwise) and may not send `requires_explanation: true` (403 `permission_denied`), so an item added in
   place never asks for an explanation until the owner flags it. The till rule of ADR-055 is unchanged.
2. **A name the category has already is picked, not made again.** A new item's name is stored with surrounding spaces removed
   and every run of spaces or tabs inside it written as one space. With `reuse_existing: true` the route answers an active item
   whose name is the same that way and ignoring case ("generator  FUEL" is "Generator fuel") with 200 and that item, and writes
   nothing. The create locks the category row (`FOR UPDATE`) for its whole transaction, so two tills sending the same name at
   once run one after the other and the second is answered with the first's item; the unique index on the category and the
   lower-cased name (V34) stays the backstop: the insert is `ON CONFLICT DO NOTHING`, and when a writer that skipped the lock
   won, the create reads that item and answers with it (with `reuse_existing`) or 409 `duplicate_item`, never a 500. A switched off item of
   that name is refused with 409 `item_switched_off` ("Ask the owner to switch it on again"), so a retired item is never
   offered. Without `reuse_existing` a taken name is 409 `duplicate_item` as before, which set-up keeps.
3. **In place on the form.** Under the Item picker a person who holds `retail.expense.record` at the form's shop sees "Add a
   new item", the same plain words as "Add a new beneficiary". It opens a small sheet with the item's name under the chosen
   category; saving puts the item in the list at once and picks it. In an empty category the same button replaces "Ask the
   owner to add its items first"; a person who may not add items at that shop still sees that line, or the owner's link to
   Expense lists. The screen offers the button only at a shop where the person records expenses, while the server accepts any
   holder of `retail.expense.record`, because the item lists are the business's, not a shop's (one list for every shop).
4. **Set-up is unchanged.** The owner still renames, flags and switches off items in Expense lists; an item added in place is
   an ordinary item there, with its `retail.expense_item.created` audit row naming who added it.

## Consequences

- Sales staff can grow the item list. The list is tenant data that touches no account (the category names the ledger account,
  ADR-022 decision 17), so a new item cannot move an expense to another account.
- A mistaken or duplicate-looking item is the owner's to rename or switch off, as for beneficiaries.
