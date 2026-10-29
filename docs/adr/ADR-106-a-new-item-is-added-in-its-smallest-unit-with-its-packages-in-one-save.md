# ADR-106: A new item is added in its smallest unit, with its packages in one save

## Status

Accepted (issue #689, owner request of 29 Oct 2026; FR-RET-80). Builds on ADR-033 (a pack is a unit and a factor of the
item's smallest unit), ADR-099 (a pack says what it is for and may have its own price), ADR-100 (a unit keeps its usual pack)
and ADR-103 (stock is shown in the standard pack and pieces). No migration.

## Context

A shop added a bulb with the unit Box. A box is a package, not the smallest unit, so its stock read "0 Box" and every price was
the price of a box. Until now Add an item took one unit, "Counted in", and a shop went back to Edit item later to add its
cartons. The owner asked for one workflow: record the item in its smallest unit, made clear, then say whether it is bought or
sold in packages and give them, all in one form and one save.

## Decision

1. **A package word is never a new item's smallest unit.** A unit named box, carton, dozen, pack, packet, bag, sack, crate,
   case, tray, bundle or bale (one or many, any case, with or without "of N"), or "set of N", is a package. `POST
   /retail/products`, and so Quick add, which makes its item through the same call (ADR-101), refuses it as `unit_id` with 422
   `unit_is_package`: "A box is a package. Choose the smallest unit, like piece, then add the box below." The unit may still be
   a pack of any item. Items already counted in such a unit, and Edit item, are unchanged.
2. **One list, on the server.** The words are one pattern, `RetailCatalogue.PACKAGE_WORDS`. Every unit `GET /retail/units`
   sends carries `is_package` from it, and the PWA reads that flag, so the form and the API refuse the same units and a new word
   is one change.
3. **The item and its packages in one request and one transaction.** `POST /retail/products` takes `packs` (at most ten), each
   the body of `POST /retail/products/{id}/units` (`unit_id`, `factor`, `purpose`, `sell_minor`), made after the item through
   the same `createPack`, with the same checks (the units capability, the price permission and a priced pack only for buying
   checked before anything is written; an unknown, switched-off, repeated or own unit after) and the same
   `retail.product_unit.created` audit. Any refusal rolls the whole request back: no item, no pack, no audit entry. No new
   table and no new pack model: the packages are rows of `retail_product_units`.
4. **The form.** Add an item calls the unit "Smallest unit you sell" with the hint "The single thing a customer can buy. For
   example piece, bulb, metre, litre." Under it, while the units capability is on, "Do you buy or sell it in boxes, cartons or
   other packages?" No or Yes. Yes shows a package row: the package (the unit picker with Add a new unit), "How many bulbs in one
   carton" (a whole number, 2 or more, which the form asks for though the API keeps ADR-033's rule of 1 or more), Used for (the
   one list of ADR-099 and ADR-100: Both, Selling, Buying; Both first) and an optional "Selling price of one carton". Add another
   package adds a row. A package unit's usual pack fills in the size and Used for, as on Edit item. The prices read "Buying price
   of one bulb" and "Selling price of one bulb". The toast says "60W bulb added, with carton of 10", and the new item leads the
   Items list.

## Consequences

**Better:** a box can no longer be an item's smallest unit through Add an item, Quick add or the API, and an item and its
cartons are added in one go, so stock reads in cartons and pieces (ADR-103) from the first purchase.

**Worse:** a shop whose smallest thing really is a bag or a sack (cement, rice) must name its unit more fully, such as "Bag of
cement" (only "of" and a number makes a size), since "Bag" alone is a package; the owner chose the list with bag and sack in
it. The word list is English only.

**Watch for:** a new way to make items must go through `POST /retail/products` (or its `create`) to keep the rule; the CSV
import and Edit item do not apply it yet. Quick add will reuse the package rows once issue #688 lands.
