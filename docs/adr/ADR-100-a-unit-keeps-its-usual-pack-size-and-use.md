# ADR-100: A unit keeps its usual pack size and purpose

## Status: Accepted

## Context
Two testers got stuck on an empty catalogue and empty units (issue #625). The units spec (`docs/specs/units-packs-and-costing.md`, section 9 step 5) asks for starting templates: a new shop owner picks a kind of shop and gets its categories, units and packs with the usual size, such as a roll of 100 metres or a box of 50 pieces, and says for each pack whether it is used for buying, selling or both.

Until now a pack size lived only on an item: `retail_product_units` (V39, ADR-033) holds a unit and a factor per product. A new shop has no item, so the usual size of a roll had nowhere to live, and nothing anywhere recorded what a pack is for. Selling packs with their own prices (#624, ADR-099, V110) was built at the same time and gave an item's pack its `purpose`.

## Decision
1. A unit may carry a usual pack: a size of at least 1 in another switched-on unit of the business and a purpose, `buy`, `sell` or `both`, in three nullable columns on `retail_units` (V111), all three or none. The purpose has the values and words (Used for: Both, Selling, Buying) of the purpose of a pack on an item that #624 adds (`retail_product_units.purpose`, V110), so the app has one idea of what a pack is for and a usual pack maps one to one onto an item's pack. The units routes take and return it as `usual_pack`; nothing else about a unit changes.
2. It is a starting number, not a rule. Adding the pack to an item fills in its Used for from the unit, and its size when the item is counted in that smaller unit; a pack added through the API with no `purpose` takes the unit's usual one (a usual `buy` sent with a price is added as `both`, since a priced pack is sold). The item's own pack, its factor and purpose, is what buying, selling and stock use. No price is kept on the unit; prices belong to the item's packs (#624).
3. Set up your shop is bundled data in the frontend (eight kinds of shop, `shop-starters.ts`), shown with no network and no AI, and added through the existing routes that add one category or one unit. A name the shop has, in any case, is skipped; a unit the shop has with no usual pack (Box and Pack are default units, ADR-098) gets the usual pack set, never a second row; a unit that has one keeps it. Running it again adds nothing new. No bulk endpoint is added: a set is at most about twenty small writes, and a failure halfway is safe to repeat.

## Consequences
- Better: a new shop gets its categories, units and usual pack sizes in one step, and the pack form of an item starts from the shop's usual size.
- Worse: the usual size is kept in two places for a shop that uses it (the unit and each item's pack); changing the unit's size does not change items already made, by design. The starting sets are English names fixed in the app; a change to them needs a release.
- Not atomic: a set added while the network drops is partly added, says what was added and is finished by tapping Add these again.
- The foreign key from a unit to its smaller unit is `NOT VALID` (every earlier row is null, and validating it would read the table under its forced row-level security with no tenant bound); it holds for every row written since V111.

## Note (issue #637, review of #628)
- A pack added through the API with no `purpose` is for `both`, as #624 set (PR #627, ADR-099); the server no longer takes the unit's usual purpose. The pack form of an item fills in the unit's usual Used for and sends it, so the screens behave as decision 2 says, and a caller that is not a screen (a script, an older cached app) keeps the contract of #627. The CSV import makes its packs through the same code as `POST /products/{id}/units` (`CatalogueService.createPack`): the same checks and the same `retail.product_unit.created` audit, with `source` `import`.
- A usual pack can be changed, and cleared with `clear_usual_pack` on the unit's PATCH, from the Units page. Items' packs never follow.
- A usual pack is one level only, checked by the server with no migration: the smaller unit may not have a usual pack of its own, and a unit that another unit's usual pack counts may not get one. So Roll of Metre and Metre of Roll can never both be kept. Two phones saving the two halves of a cycle at the same moment could still store both; this is harmless, since a usual pack is only a starting number.
