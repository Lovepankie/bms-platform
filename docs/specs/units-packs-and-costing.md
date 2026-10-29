# Units, packs, buying and selling in packs, and profit per piece

Status: decided (the owner, 23 and 24 Oct 2026: D14, D16, D22 to D26 and D32 yes). Built so far: average cost (#529, ADR-079, V94), shop EAN-13 barcodes (#528, V93), the pack size per delivery on Buy stock and goods received (#554, ADR-082, no migration), selling packs with their own prices (#624, ADR-099, V110), and Set up your shop, the starting templates of section 9 step 5 (#625, ADR-100, V111), and stock shown in packs and pieces (#650, ADR-103). It follows the set-up of an electronics shop in the pilot.
Applies to every retail business: small shops, wholesalers and the ones in between.

## 1. The problem, in the shop owner's words

- The shop owner buys some goods by the carton. Cartons of the same item do not always hold the same number of pieces: one supplier's carton has 24, another's has 30.
- Sometimes he buys in other packs: a box, a dozen, a sack, a bale, a tray.
- He sells in many ways: a full carton, a half carton, a dozen, or single pieces. Each way has its own price, and the carton price is not just 24 times the piece price.
- What he paid for a carton is not what he sells a carton for.
- At the end of the day he must know his profit, and profit must be worked out per piece (the smallest unit). Then it is right whichever way the goods came in and went out.

## 2. What the app does today

The `units` capability, switched off by default (ADR-033, V39), already gives:

- one smallest unit per item. Stock is always counted in it.
- packs on an item (carton = 12 pieces, dozen = 12). There is one fixed size per pack type, used when buying, selling and scanning.
- a pack's selling price defaults to the piece price times the pack size. Since #624 (ADR-099) a selling pack may have its own price, which walk-in sales use.
- the buying price per piece is the carton cost divided by the pieces. Until #529 the last delivery overwrote the item's cost; ADR-079 (V94) now keeps the moving average per branch.
- barcodes per pack, so scanning a carton barcode sells a carton.

The gaps:

- A carton's size cannot change per delivery.
- A half carton has no clean way in.
- Fixed in #624: walk-in pack prices can now be set on the item.
- Fixed in #529: the cost used to jump with every delivery instead of averaging; it now keeps the moving average per item per branch.

## 3. The model, in plain words

**Every item has a smallest unit.** This is how it is counted and how profit is worked out. Examples: piece, kilo, litre, metre, pair, bottle.

**An item can have packs.** A pack is a named bundle of smallest units (carton, half carton, dozen, box, sack, bale, tray, crate, jerrycan, roll, bundle, packet, tin, set). Each pack says how many smallest units it holds, and whether it is used for **buying**, **selling** or **both**.

**Buying packs can change size at each delivery.**
- On Restock, after the seller picks "Carton", the app asks "How many pieces in each carton?". It offers the usual size (24) and accepts what came (30).
- That count is saved on the restock line only. Stock goes up by cartons times pieces.
- The item's usual carton size stays as it is unless the seller ticks "Use 30 from now on".

**Selling packs have a fixed size and their own price.**
- A carton sold is always the shop's standard carton, for example 24 pieces.
- Each selling pack has its own selling price, set on the item. The app suggests one (the piece price times the size), and the owner types the real one:

  | Selling pack | Size | Price |
  |---|---|---|
  | Piece | 1 | 1,000 |
  | Dozen | 12 | 11,000 |
  | Half carton | 12 | 10,500 |
  | Carton | 24 | 20,000 |

- Walk-in sales use these prices. Price lists for special customers still win over them.

**Half cartons and the like are just selling packs.** "Half carton = 12 pieces" is a pack like any other, with its own price and barcode if it has one.

**Stock is shown both ways.** Stock is kept in smallest units. It is shown as "5 cartons and 7 pieces (127 pieces)", using the standard carton.

**Buying and selling prices are kept apart.**
- The buying price is per pack as bought ("I paid 18,000 for this carton").
- The selling price is per pack as sold.
- The app never assumes one from the other, except to suggest a starting selling price.

**Profit is per piece, using the average buying price.**
- Each delivery updates the item's average cost per piece in that branch (per item per branch, as V94 stores it). The formula is (pieces already in stock in that branch × old average + pieces received × this delivery's cost per piece) ÷ the new number of pieces. This is the moving weighted average, the usual method for shops (ADR-079, built in #529).
- Each sale line keeps a copy of the average cost per piece at the moment of sale. Profit on the line is its price minus the pieces sold times that cost.
- Reports add up the lines, so the profit is right whether the goods were sold as cartons, dozens or pieces.
- The last buying price is still shown ("last paid 750 a piece"), because owners use it to set prices.

**Selling below cost is still caught.** If a pack's price is below its size times the average cost per piece (for example a carton of 24 below 24 × 750), the app warns, as it does today for single items.

## 4. Starting points (defaults) so nobody starts from nothing

When the shop switches packs on, or opens a new item, it picks "How do you buy and sell this?". Each answer pre-fills the packs, and the owner only changes the numbers. The pack sizes below are only starting numbers.

| Kind of goods | Smallest unit | Buying pack | Selling packs |
|---|---|---|---|
| Phones, accessories, TVs | piece | box (10) | piece, box |
| Bulbs, sockets, extensions, cables | piece (cable: metre) | carton (100 to 200) | piece, dozen, carton |
| Drinks | bottle | crate (24), carton (12 or 24) | bottle, crate, carton |
| Soap, sugar packets, biscuits | piece or packet | carton (24 to 48) | piece, half carton, carton |
| Sugar, rice, beans, flour loose | kilo | sack (50 kg or 100 kg) | kilo, half sack, sack |
| Cooking oil | litre | jerrycan (20 L), carton (12 × 1 L) | litre, bottle, jerrycan |
| Fabric, wire, pipe | metre | roll or bundle | metre, roll |
| Eggs | piece | tray (30) | piece, tray |
| Hardware (nails, screws) | piece or kilo | box or kilo bag | piece, dozen, box, kilo |

The unit list is seeded with names Ugandans use: piece, kilo, litre, metre, pair, set, dozen, half dozen, carton, half carton, box, packet, sack, half sack, bale, tray, crate, jerrycan, bundle, roll, tin, bottle. The shop can add its own.

## 5. Words in the app (proposal: one glossary, used everywhere)

| Today | Proposed | Why |
|---|---|---|
| Restock | Buy stock | The owner: "restocking is buying". Restock is a rare word here |
| Cost price / cost | Buying price | Done in #495 |
| Sell price | Selling price | Done in #495 |
| Average cost, a bare Price or Cost column, at cost, at price | Average buying price, Selling price, Buying price, at buying price, at selling price | Done in #680; the glossary guard retires "average cost", "cost price" and "unit cost", and the design guard fails a bare Price or Cost column on a retail screen. Profit reports keep "Cost of goods sold" |
| Unit (of an item) | Counted in (smallest unit) | "Unit" is unclear |
| Pack sizes | Cartons, boxes and other packs | Concrete |
| Stock-take | Count stock | Plain verb |
| Move stock | Move stock | Already clear, keep |
| Credit buyers | Customers who owe | What it is |
| Suppliers | Suppliers (where you buy) | Keep, with the hint |
| Variance | Difference | Plain |
| Shrinkage | Lost or damaged stock | Plain |
| Item code | Item code | D16: the owner chose "Item code" |

Rule: every label is a word a shop attendant in Kampala uses. There is a glossary file in the frontend, and a test fails when a banned word ("SKU", "variance", "shrinkage", "UOM") appears in UI copy.

## 6. Item codes from the category (story #493, D14 yes: codes are made from the category)

- Each category has a short prefix of 2 to 4 letters, made from its name and editable: Bulbs BLB, TVs TV, Phone accessories PHA, Extensions EXT.
- A new item added inside a category gets the next number: BLB-0001, BLB-0002. Two people adding at once still get different numbers.
- A code that already exists is kept. Nobody types a code for a new item: the app always makes it (owner decision of 25 Oct 2026, issue #562). Edit shows the code but never changes it. The import keeps the codes in a shop's file and makes the blank ones; with the tick Let the app make the codes, it makes every code and ignores the file's (owner decision D45). A supplier's or maker's code goes in the barcode.
- A new category is not asked for its letters: the app makes them from the name and shows them. Whoever manages the catalogue can change them on the category's page.
- Packs do not get their own item code. A pack is the same item, and is told apart by its barcode.
- The code names the item, not the delivery. Moving an item to another category keeps its code; the new category's numbering is not used.

## 7. Barcodes: what the standard says (GS1)

**A barcode identifies a kind of product, not one physical piece.** Every 500 ml Coca-Cola bottle in Uganda carries the same EAN-13 number. That number is its GTIN (Global Trade Item Number), owned by the brand through GS1. So your take is right: one barcode per item, the same on every piece.

**Each packaging level has its own GTIN.**
- The piece has one GTIN.
- The carton of 24 has a different GTIN, often printed as ITF-14 or as a GTIN-14 on the box.
- If the carton size changes (24 to 30), the brand must give it a new GTIN. A carton barcode therefore always means one exact size, which fits section 3: the carton barcode points to that pack, at that size.
- The app already stores barcodes per pack (ADR-070). With this spec, a scanned carton barcode on Restock also fills in the carton size.

**Items with no barcode** (local goods, loose goods, unbranded accessories) get shop codes.
- GS1 sets aside prefixes **20 to 29** of EAN-13 for "restricted circulation", meaning numbers used only inside one business.
- The app used to print its own Code 128 labels ("IN00000001"). It now prints EAN-13 codes (D25, #528): made codes are `210` + a 9-digit number + a check digit (D32), and `211` to `219` stay free for later, such as weighed items. Every scanner and till reads them, and they never clash with a real brand's GTIN. Old `IN` codes keep working.

**When each piece is different** (TVs, phones), the barcode is the same and the **serial number** or IMEI tells the pieces apart. Warranty and theft checks need the serial. That is a later, optional "Serial numbers" capability: scan or type the serial at Buy stock and at Sale.

## 8. Moving stock between branches and GS1

- **A timber traceability project uses GS1 between organisations.** Timber moves from forest to sawmill to buyer, so it uses GS1 logistics labels (SSCC) and event records. Regulators and other companies need to follow each consignment.
- **A shop's own store-to-shop move stays inside one business.** The store and the shop already know both ends. Full GS1 logistics (SSCC labels, EPCIS events) would add labels and steps and give the shop nothing.
- **What we take from GS1:**
  1. GTINs per packaging level, so a carton can be scanned when moving and when receiving.
  2. Scan to receive: the shop scans what arrived, and the app compares it with what was sent. This replaces typing counts.
  3. Optional batch and expiry dates, for businesses that sell food, drinks or medicine, so the oldest stock is moved and sold first. This would be a later capability, off by default.
- **SSCC** comes in only if a business moves pallets to another company that asks for it. It is not needed for our shops today.

## 9. Build order (after the owner's decisions)

1. **Average cost per piece** (moving weighted average), with sale lines keeping their cost. Built in #529 (ADR-079, V94). Everything else depends on profit being right.
2. **Pack size per delivery on Buy stock.** The restock line keeps its own size. Includes "use this size from now on".
3. **Selling packs with their own prices**, including half carton and dozen. Walk-in sales use them, the below-cost warning applies, and price lists still win. Built in #624 (ADR-099, V110): each pack says whether it is for buying, selling or both; Edit item shows the packs in one table (pack, size, selling price) with the suggested price and Below cost.
4. **Stock shown in packs and pieces** on Stock, Count stock and Move stock. Built in #650 (ADR-103, no migration): the item's standard pack is the pack the shop counts and sells in (a selling pack whose unit has a usual pack of the item's base unit for selling or both, issue #660, else the biggest selling pack, else the biggest pack; a tie goes to the unit id); Stock reads "5 Cartons and 7 Pieces (127 Pieces)", and Count stock and Move stock take cartons and pieces, or pieces alone, show the total in pieces, and still save pieces.
5. **Starting templates** ("How do you buy and sell this?") and the Ugandan unit list. Built in #625 (ADR-100, V111) as **Set up your shop**, reached from the first-run card (#615) and from Items and categories:
   - The owner answers "What kind of shop is it?": bedding and home, electrical and electronics, hardware and building, phones and accessories, clothing and shoes, supermarket and general, pharmacy and cosmetics, or Something else (just the common units).
   - Each kind lists its categories (the app makes their code letters, section 6), its units, and its packs with the usual size, such as roll = 100 metres and box = 50 pieces for hardware. Each is ticked; the owner unticks what they do not sell, may change a pack's size, chooses what each pack is **Used for** (`both`, `sell` or `buy`, the purpose of a pack on an item, ADR-099), then taps **Add these**.
   - The usual size and use are kept on the unit as a starting number; adding the pack to an item fills in its Used for from the unit, and its size when the item is counted in that smaller unit. A pack added through the API with no purpose is for both, as #624 set (#637); the screens send the unit's usual one. No price is set here: selling pack prices belong to step 3 (#624).
   - Names the shop has, in any case, are skipped and never made twice; a default unit with no usual size (Box, Pack) gets one. Running it again adds nothing new.
   - A usual pack is changed or removed on the Units page (**Usual pack** on the unit's row, #637); items keep their own packs. It is one level only: its smaller unit is never a pack itself (ADR-100 note).
   - The lists are bundled with the app, so they show offline; adding needs the network and uses the routes that add one category or one unit.
   - **Then the items, from the owner's own list** (#626, ADR-102, owner decision D62): for a business the platform names in `BMS_SETUP_AI_TENANTS`, the success screen offers **Add your items from a list**, which opens Import items with the kind of shop. There **Let the app read your list** takes a typed list, a photo of the stock book or a supplier invoice, or a CSV, Excel or PDF file; a free tier model drafts the categories, units, packs with their sizes and items with the prices it can read, using the shop's own names. The owner changes or removes rows, taps **Check these items** (the import's own check) and **Add**. Nothing is saved before that tap. The opening stock is not drafted: the item import has no stock column, so the owner counts stock after.
6. **Glossary and the banned-word test**, then the renames in section 5.
7. **Shop barcodes as EAN-13 with prefix 210** (built in #528; new labels only; existing codes keep working), and a carton barcode filling the carton size on Buy stock.
8. **Later, optional capabilities:** serial numbers (TVs, phones) and batch with expiry.

Each step is its own pull request with tests. Migrations take their numbers in merge order.

## 10. Decisions (answered)

- **D22 yes:** profit uses the moving average buying price per piece (built, #529).
- **D23 yes:** buying packs may change size per delivery; selling packs have a fixed size and their own price.
- **D24 yes:** one glossary, starting with Restock becoming Buy stock.
- **D25 yes:** shop barcodes as GS1 EAN-13 for new labels (built, #528); **D32 yes:** the layout is `210` + 9 digits + check.
- **D26 yes:** serial numbers and batch with expiry come later, as optional capabilities.
- **D16:** the field is called Item code.
- **D14 yes:** item codes are made from the category prefix (#493).
