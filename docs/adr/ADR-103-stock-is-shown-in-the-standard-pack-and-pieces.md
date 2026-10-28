# ADR-103: Stock is shown in the item's standard pack and its pieces

## Status

Accepted (issue #650; docs/specs/units-packs-and-costing.md section 3, "Stock is shown both ways", and section 9 step 4).
Builds on ADR-099 (a pack says what it is for) and ADR-100 (a unit keeps its usual pack). No migration.

## Context

Stock is kept in the item's smallest unit (ADR-033). A shop that buys and sells by the carton counts its shelf in cartons,
so "127 Piece" makes the person divide in their head, and Count stock and Move stock asked for pieces only. The spec asks
for "5 cartons and 7 pieces (127 pieces)", using the item's standard pack. An item may have several packs (a dozen, a half
carton, a carton) and each says whether it is for buying, selling or both, so one has to be chosen. The stock lists did not
carry any pack: a stock row had the unit and the quantity only.

## Decision

1. **The standard pack is the pack the shop counts and sells in.** Of the item's active packs holding more than one base
   unit: first a pack whose unit has a usual pack (ADR-100) and that is used for selling or both (ADR-099); else the
   biggest pack used for selling or both; else the biggest active pack. Within each step the biggest wins, and a tie goes
   to the lower unit id, never the name. So a Bale of 100 that is only bought is never how the shelf is counted while the
   item has a Dozen or a Carton it sells. An item with no such pack, or a shop with the units capability off, shows its
   stock in the base unit, as before (review of #654, the PR author's rule).
2. **One rule, on the server.** The rule is one SQL subquery, `RetailCatalogue.standardPackSql`. `GET /retail/stock` and
   `GET /retail/stock/all-branches` carry `standard_pack: {unit, factor}` on each row while the units capability is on,
   and every pack of an item carries `standard`, true for that one pack, which Move stock reads from the item it picked
   (`standardPack` in `units-state.ts` only reads the flag).
3. **One formatter and one parser.** `packsAndPieces` writes "5 Cartons and 7 Pieces (127 Pieces)"; less than a pack reads
   in pieces only ("7 Pieces"); below zero reads "1 Carton and 3 Pieces short (27 Pieces short)". `packsAndPiecesMilli` reads a
   count or a move typed as whole packs and pieces, or pieces alone, into pieces. Stock, Count stock (its total, and the
   words of a big drop, issue #635) and Move stock use only these.
4. **A count or a move keeps the pack it was typed in.** Count stock and Move stock keep the pack (unit and size) with the
   first carton typed, so a pack switched off, resized or gone while the sheet is open, or an item read again into a saved
   move, never reads the same number in another size; the line says the pack has changed since.
5. **Storage and the API stay in pieces.** A count and a move still send `counted_qty` and `qty` in the base unit. Nothing
   stored changes.

## Consequences

**Better:** the shelf is read and counted the way it is packed, the same words on all three screens, and the total in
pieces is always shown before a count is reviewed or a move is saved.

**Worse:** a pack switched off or resized changes how stock reads at once, by design; only a count already typed keeps its
pack. Another rule is one subquery and one test to change.

## Note (28 October 2026, issue #660, reviews of #654 and #658)

Tier one of decision 1 is tightened. A pack comes first only when it is used for selling or both and its unit has a usual
pack (ADR-100) with a size (`pack_size` not null) that counts the item's own base unit (`pack_of_unit_id` is the item's
unit) and is itself for selling or both (`pack_purpose IN ('sell', 'both')`). `pack_purpose` has no default, so a null one
is excluded rather than read as both; V111's check keeps the three columns all set or all null, so neither null occurs today.
A unit whose usual pack is a box of 50 metres is no longer promoted on an item counted in pieces, and a usual pack only
bought promotes nothing. The other tiers and the tie-break (the biggest, then the lower unit id) are unchanged.

The rule stays one subquery, `RetailCatalogue.standardPackSql`, and the PWA only reads it: it takes the pack marked
`standard` and never ranks packs itself (a pack it is sent carries no usual pack), so server and client cannot disagree.
`RetailUnitsIT.aUsualPackOfAnotherUnitIsNotPromoted` pins the usual-pack cases and the tie-break;
`RetailUnitsIT.stockRowsCarryTheStandardPack` pins the fallbacks (the biggest pack for selling, then the biggest of any, a
tie going to the lower unit id, and an item pack only for buying never promoted). The first test also asserts that the live
item packs and stock row of the box of 50 metres case equal `fixtures/retail/standard-pack-660.json`. The PWA's contract test
(`transfer-packs.dom.test.tsx`) feeds that same file to Move stock, which moves the item in cartons, never in boxes.

Folded in from the review of #659: Move stock says "That is more pieces than BMS can move at once" when the pieces alone are
too large to hold, and "That is more than BMS can move at once" when the cartons, or cartons and pieces together, are. Cartons
typed for an item with no pack or no unit never read as too many.
