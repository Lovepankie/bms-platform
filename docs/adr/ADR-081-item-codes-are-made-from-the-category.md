# ADR-081: Item codes are made from the category, and items are added inside a category

## Status

Proposed (owner decisions D14 and D16, issue #493; docs/specs/units-packs-and-costing.md section 6), accepted on merge. Amends
FR-RET-01 (a code is no longer typed on every item) and the CSV import of #146 (the code column may be empty or left out). Adds
FR-RET-70. Migration `V96`, the next number after `V95` (pull request #540, merged).

## Context

At the demo of 23 Oct 2026 the owner asked what Code is and why the app does not make it, and said an item should only be added
under its category, so nobody picks a category on the item form and anybody can trace an item to its category by its code. Shop
owners starting from nothing invent codes badly or leave them out, and the code is required and unique (data dictionary rule 1).
D16 names the field Item code everywhere; the screens keep the word Items.

## Decision

1. **A category has a code start of 2 to 4 capital letters**, `retail_categories.code_prefix`, unique in the business (unique index
   `(tenant_id, code_prefix)`, 409 `duplicate_category_prefix`) and editable by whoever manages the catalogue. It is suggested from
   the name by one SQL function, `bms_category_prefix_base`, so the server, the backfill and the trigger agree: letters only, accents
   folded (`bms_fold`), small joining words left out; one word gives its first letter and the next two consonants (Bulbs BLB,
   Extensions EXT), a short or mixed case acronym keeps its capitals (TVs TV), two or more words give the first two letters of the
   first and the first letter of each next one, up to four (Phone accessories PHA). `bms_suggest_category_prefix` then finds a free
   one (the base, else three letters and one more, and so on). `GET /retail/categories/prefix-suggestion` returns it for the form.
2. **Existing categories get their suggested code start in the migration**, oldest first, so the oldest keeps the plainest; a
   category inserted without one (the import, an instance from before the deploy, a seed) gets it from a trigger.
3. **An item added without a typed code gets the category's next code**, `PREFIX-0001`, at least four digits, from
   `bms_next_item_code`: it locks the category row (`FOR NO KEY UPDATE`, which leaves free the key share lock the foreign key check of every
   item insert takes, so a typed add or a move never waits for it and two imports cannot deadlock through it), takes `code_next` and steps over any number an item of the business already holds
   as its code (ignoring case, as the unique index does), then raises `code_next`. The row lock lasts to the commit, so two people
   adding in one category at once get different numbers; a rolled back add leaves a gap, which is allowed. A typed code is kept; the
   uniqueness rule is unchanged. The counter belongs to the category, not to the code start: after the start changes, numbering goes
   on (BLB-0001, then BUL-0002), so a number is never given twice in one category.
4. **Existing items and their codes are not changed**, by the migration or by a change of the code start.
5. **The code names the item, not its place.** Moving an item to another category (the item's own form, audited with both category
   names, #351) keeps its code; the new category's numbering is not used. Packs get no item code of their own (spec section 6).
6. **The import fills blank codes** from each row's category on apply; a file may leave out the code column. Rows with a typed code
   go in first, so a made code steps over them, and the categories that make codes are locked in id order first, so two imports queue
   rather than deadlock. A dry run reports such a row with an empty code; the apply reports the code made. Applying the same file again stays idempotent: a row
   without a code is matched on its name and unit (the FR-RET-44 name rule already skips a name held in that unit, and a name repeated
   in the file), so it is skipped rather than added under a new number.
7. **The screens add items inside a category.** Categories opens a category page (its items, its code start, Add an item); the item
   form opened there has no category dropdown. The Items page keeps Add an item, with the category asked first (owner's list of
   23 Oct 2026, item 2).

## Consequences

- Shop owners never have to invent a code, and a code says which category an item was added in.
- A code no longer always matches the item's category after a move or a change of code start; that is the price of codes that never
  change, which receipts, labels and barcodes printed earlier rely on.
- Adds in one category are serialised on its row for the rest of the transaction: a few milliseconds, against adds that happen a few
  times a day.
- No permission is added. `Category` gains `code_prefix` and `next_code` (a hint; the number given on save may be later if another
  add took it first).
