# ADR-081: Item codes are made from the category, and items are added inside a category

## Status

Proposed (owner decisions D14 and D16, issue #493; docs/specs/units-packs-and-costing.md section 6), accepted on merge. Amends
FR-RET-01 (a code is no longer typed on every item) and the CSV import of #146 (the code column may be empty or left out). Adds
FR-RET-70. Migration `V96`, the next number after `V95` (pull request #540, merged). Amended on 25 Oct 2026 (issue #562, owner
decision): the app always makes the code of a new item and a typed one is refused; see the amendment at the end.

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

## Amendment, 25 Oct 2026: the app always makes the code (owner decision, issue #562)

The owner saw the editable Item code box of #550 and decided that **the app always makes the item code of a new item; nobody
types or changes it.** Supplier and maker codes go in the barcode field (ADR-070), which is unchanged.

- **Decision 3 now reads: every new item gets the category's next code**, `PREFIX-0001`, from `bms_next_item_code` as above,
  on every path that adds one item: the item form opened in a category, Add an item on Items, the new item added while buying
  or counting stock (FR-RET-71) and Add an item with sizes and colours. The import has its own rule (D45, below). The forms show the code that will be made as text ("Item
  code: BLB-0007, made by the app"), never an input; the number is the category's next and the saved code is the one in the
  answer, since another add may take that number first.
- **A typed code is refused, not ignored.** `POST /retail/products` with a `code` that is not blank answers 400
  `item_code_made_by_app` (field `code`, `not_allowed`) and adds nothing; so do a `base_code` on `POST /retail/product-families`
  and on the joins, and a `code` on any size line (`variants[i].code`). A blank `code` counts as none. Refusing beats dropping it
  silently: an older build of the app or a script would otherwise believe its code was kept, and print or share a code no item
  holds; the refusal says so at once and changes nothing. It is 400, not 422, because the field is no longer part of the
  request, not a rule about its value.
- **Decision 4 now also covers edits: existing codes never change.** `PATCH /retail/products/{id}` refuses a `code` other than
  the item's own with the same 400 (a blank code, or its own code sent back in any case, is no change), and the edit form shows the code as text.
- **Decision 6 now reads: the import keeps the file's codes unless "Let the app make the codes" is ticked** (owner decision
  D45, Hillary, 25 Oct 2026, option b). Unticked, the default, works as before: a row's code is kept, uniqueness is unchanged
  and a code an item already holds is reported in the dry run and skipped; a blank code (or no code column) gets the category's
  next code. Ticked (`make_codes=true` on `POST /retail/products/import`), every row gets its category's next code and the
  file's code column is not read at all; a row that had one says "The code X in the file is not used". The dry run and the
  apply both honour the tick, the screen runs the check again when the tick changes, and the summary audit row records it. A
  file is the one way a shop brings codes it already uses; adding an item in the app never takes a typed code.
- **Sizes and colours (ADR-051).** A new item with sizes takes the category's next code as its base code (BLB-0007) and each
  size gets the base code with its size and colour (BLB-0007-03M-BLUE); the preview shows them, with no code box. Giving an item
  sizes and joining items kept apart keep the codes the items have; later sizes start from the first item's code.
- **The code start** is not asked on the new category form: the app makes it from the name (decision 1) and the form shows it.
  Whoever manages the catalogue (`retail.catalogue.manage`) changes it on the category's page; the API keeps `code_prefix` on
  `POST /retail/categories` for the same people.
- Items brought in by the pilot importer (ADR-020 decision 9) keep the codes they had: they are existing items.
- No migration: the code column, its unique index and `bms_next_item_code` are unchanged, and the made code still steps over a
  number an existing item holds.
