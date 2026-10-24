# ADR-070: Barcodes on products, scanning when selling, restocking and counting, and printed labels

## Status

Accepted (approved by the dev lead on 22 Oct 2026; issue #345, FR-RET-68; migration `V87`). Builds on ADR-020 (the retail
vertical), ADR-033 (pack sizes, the `units` capability) and ADR-009 (the PWA). Numbered ADR-070 because ADR-067 (offline sales) and ADR-068 (shop
logos) are taken and pending ADR-069 is claimed by an open pull request; renumbered at merge if needed. Amended on 23 Oct 2026 by
the owner's decision D7 (decision 11): a scanner is read only through a dedicated Scan box; this replaces the screen-wide
keyboard-wedge reading first written in decision 5. Amended on 24 Oct 2026 by the owner's decision D25 (decision 12, issue #524,
migration `V93`): the codes the system makes are GS1 EAN-13 in the restricted-circulation range; this replaces the Code 128 `IN`
codes of decision 7 for new labels.

## Context

The owner wants the product to sell to supermarkets, hardware shops and every other kind of shop, and supermarket style selling
means scanning: a product's barcode is read at the till and the item lands on the sale. Until now products had no barcode field and
`docs/specs/retail-mvp-scope.md` listed barcode scanning as out of scope. The team's DSN code base already prints Code 128 labels and
reads scans from USB "keyboard wedge" scanners typing into a focused field, which is also how this design reads them (decision
11); it has no camera decoding. Shops differ: some have a USB
or Bluetooth scanner at the till, many have only phones.

## Decision

1. **Barcodes are a part of the product, several per product.** A new table `retail_product_barcodes` (V87) holds each code with its
   format, the product and, optionally, one of the product's pack sizes (V39), so a carton's barcode adds a carton. Changing barcodes
   does not move the product's version, like its packs.
2. **Four formats, checked.** EAN-13, EAN-8 and UPC-A, whose last digit is checked by the GS1 rule, and Code 128 for any other
   printable code up to 48 characters (no spaces, none of `% ; ? # / \`, and not only dots, since the lookup carries the code in its path). A code of 13,
   12 or 8 digits is read as EAN-13, UPC-A or EAN-8 unless Code 128 is asked for. The server (`Barcodes.java`) and the PWA
   (`barcode-state.ts`) apply the same rules, so the item form says what is wrong before saving.
3. **Unique per business among active items.** A unique index on `(tenant_id, lookup_key)`; `lookup_key` is a generated column that
   folds a code stored as UPC-A into the thirteen digit EAN-13 a scanner may report, so the same printed code can never sit on two rows;
   twelve digits stored as Code 128 keep their own key, and a twelve digit scan tries the UPC-A key first, then the Code 128 one.
   A code held by an item that is switched off is released (deleted and audited `retail.barcode.released`) when an active item takes
   it; otherwise a second item is refused with 409 `barcode_taken`. The switched-off item loses the code for good: switching it back on
   does not bring the code back, and the 409 message and the import's dry run both say so. Two writers of one code meet at the index and the second gets 409.
   Each business has its own codes under forced row-level security; the same code may exist in two businesses.
4. **One lookup for every way of scanning.** `GET /api/v1/retail/products/by-barcode/{code}` (`retail.stock.read`, optional
   `branch_id` for that branch's balance) answers the active item, the barcode and the pack it names, or 404 `barcode_not_found`. A code on a pack that is switched off, or on any pack while the units capability is off, is 404 `pack_inactive` with a plain message; the forms never add one base unit in its place.
   Product responses (list, get) carry `barcodes`, so a screen that has read the items can match a scan without the server when it
   is offline. Until then the offline lookup uses only the products this screen has already read and cached (the item search, the
   lines on the form); an item the screen has not read is "could not be looked up" offline. It covers the whole catalogue only once
   the offline catalogue cache of issue #463 carries `barcodes`.
5. **Three ways in, one path out, on four forms.** The sale, the restock, the move stock and the stock count forms have a Scan box
   (decision 11): (a) a USB or Bluetooth scanner that types the code into the box and presses Enter; (b) a code typed there by hand
   and Enter; (c) the phone camera behind the camera button beside the box. A found item is added at 1, or its line in
   that unit gains one (a counted pack adds its factor). An unknown code says so in plain words; an administrator
   (`retail.catalogue.manage`) gets "Add this barcode to a product" on the spot, which assigns it and adds the item. A beep and a short
   vibration confirm a scan where the device allows.
6. **The camera uses the browser first.** The browser's `BarcodeDetector` where it exists (Chrome on Android); elsewhere, Safari on an
   iPhone in particular, Quagga2 (`@ericblade/quagga2`, MIT, pure JavaScript, maintained) reads a still of the frame. Quagga2 was chosen
   over the WebAssembly decoders because the production CSP is `script-src 'self'` without `wasm-unsafe-eval`; pure JavaScript needs no
   CSP change. Both the camera view and Quagga2 are loaded only when Scan is tapped (separate chunks), so the first load does not grow.
   A code counts only once two frames in a row read the same code, so one misread frame never adds the wrong item.
   The Scan button shows only where the browser has `getUserMedia`; on a plain http address the scan bar says the camera works only on the secure (https) address instead; a blocked, missing or busy camera is explained in plain words. The
   `Permissions-Policy` already allows `camera=(self)`.
7. **Labels for items without a code.** `POST /retail/products/barcodes/generate` makes a code from the
   business's sequence (`tenant_sequences`, key `retail_barcode`) for each item that has no made code; an item keeps the one it has.
   It was a Code 128 code `IN` plus eight digits; since decision 12 it is an EAN-13 from `210`.
   The Barcode labels page prints an A4 sheet of 3 by 8 labels of 70 by 37 mm with the name, the price and the barcode, drawn in the
   browser as SVG (a made EAN-13 in the GS1 layout since decision 12, an `IN` code of before in Code 128 set B) and printed with the browser's print dialog at zero margin (the page states the stock, and a 4 mm safe margin inside each label is an option for a printer that cannot print to the edge); no PDF is stored.
8. **Who may do what.** Reading and scanning need `retail.stock.read` (every seller, storekeeper and administrator holds it). Adding,
   removing and making barcodes, and the labels page, need `retail.catalogue.manage` (administrators). No new permission.
9. **The import file** takes the optional columns `barcode` (synonyms `barcodes`, `ean`, `upc`) and `pack barcode`, several codes in a
   cell separated by spaces or `|`, and `barcode format` (blank, or a format name). A bad check digit, a code another active item
   holds or a code repeated in the file is an error row, never a guess.
10. **Codes a spreadsheet damaged.** An all-digit code of 6, 7, 9, 10, 11 or 14 digits, or a number in scientific notation
   (`4.00638E+12`), is refused in the import and on the item form with "the spreadsheet may have dropped a leading zero or turned
   the code into a number", unless the row's `barcode format` (or the request's `format`) is `code128`: no EAN or UPC has those
   lengths, and a spreadsheet drops the leading zero of a UPC-A or an EAN-13 and writes long numbers that way.
11. **Scans go to a dedicated Scan box (owner decision D7, 23 Oct 2026).** A hardware scanner is read only through the labelled
   Scan box on each of the four forms, as Square and Loyverse do. The box takes the cursor when the form opens on a device with a
   mouse or trackpad (a till or a laptop; not on a phone, where it would open the keyboard), clears after each scan and keeps the
   cursor for the next one; on the stock count the line counted is marked. Its Enter looks the code up and never submits the form.
   Nothing listens to keys anywhere else on the screen: a quantity, price or count field is never read for a scan and never changed
   by one, and Enter there submits the form as usual. The first design read a scanner anywhere on the screen by its speed (keys
   within 35 ms, a per-device Scanner speed up to 80 ms, a stalled scan joined back by its check digit, the keys taken back out of
   the field they landed in). Every review round found another case where it failed: a person typing a number fast with key
   rollovers was taken for a scan, a scanner that stalled part way left part of the code in a quantity or submitted the sale, and
   the slower settings that slow Bluetooth scanners needed came closer to a person's typing. Speed cannot tell a scanner from a
   person for certain, and a wrong guess changes money or stock. A dedicated box needs no guess: what lands in it is a code, at any
   speed, and what lands anywhere else is typing. The cost is one habit, keeping the cursor in the Scan box, which the box helps
   by taking the cursor back after each scan; a scan with the cursor in another field types into that field, and the person sees it.

12. **The shop's own codes are EAN-13 in GS1's restricted-circulation range (owner decision D25, 24 Oct 2026, issue #524).** A
   made code is `21` (GS1 sets 20 to 29 aside for numbers used only inside one business and never gives them to a brand, so a made
   code can never be a real product's GTIN), then `0`, then nine digits from the same sequence key `retail_barcode` (no new key; the
   numbers simply go on from the last `IN` code), then the GS1 check digit: `2100000000425` for number 42. The `0` keeps the codes
   of `21` followed by 1 to 9 free for a later kind of shop code, such as a weighed item whose price is in the code. The issue's
   `21` plus nine digits plus a check digit is twelve digits, one short of an EAN-13; the `0` is that digit (pending the owner's
   confirmation). The spec `docs/specs/units-packs-and-costing.md` (pull request #513, not yet merged) says only `21`; when it lands,
   its section 7 takes this layout: made codes `210`, weighed items and any other kind from `211` to `219`, never `210`. Nine digits
   hold 999,999,999 codes per business; the generator refuses past that rather than wrap. Two runs never draw one number: the
   sequence row stays locked until the run's transaction ends, and every item of a run is locked, in a fixed order, before the
   sequence is touched, so two runs over overlapping lists queue rather than deadlock. A number whose code another item already
   holds (typed by hand, or a scale's label) is skipped and the next one tried, as before. Migration `V93` replaces the V87 check
   that let a made code be only Code 128 with one that allows `code128` or an `ean13` starting with 2. **Existing codes keep
   working:** an item that has an `IN` code keeps it, the generator returns it and makes nothing new, the lookup finds it and its
   label still prints it as Code 128. The label sheet draws an EAN-13 to the GS1 layout (start, centre and end guards reaching
   below the bars, the six left digits in the L and G sets chosen by the first digit, the right six in the R set) with the
   thirteen digits beneath, the first digit left of the bars, and at its own proportions, so a module on the 70 by 37 mm label is
   about 0.33 mm, the nominal size. Why: an EAN-13 reads on every scanner and till with its default settings, where Code 128 is
   switched off on some, and it looks like any product's barcode.

## Consequences

**Better:** a shop with a cheap USB scanner sells at supermarket speed with no setup; a shop with only phones scans with the camera;
an item with no manufacturer code gets a label; codes are checked, so a misread digit is caught at entry; a person's typing in a
quantity, price or count is never taken for a scan, and a scanner of any speed works in the Scan box with no setting.

**Worse:** one more table and one more lazy dependency (Quagga2, about 45 kB gzipped, loaded only on Scan). Quagga2 reads stills,
so the iPhone camera is slower than the native detector. Quagga2's Node-only optional dependencies (`sharp`, and `ndarray-pixels`
with its own `sharp`, native libvips binaries) add about 72 MB to `node_modules` and about 2 s to `npm ci` (measured 22 Oct 2026:
203 MB and 5 s on main, 280 MB and 7 s here); the web image is unchanged, since it carries only `dist`, and the bundle never loads
them. They are not removed: npm `overrides` cannot drop a dependency, a `file:` stub in `overrides` makes npm 10's `npm ci` refuse
the lockfile, and `--omit=optional` would also drop the native binaries Rollup and esbuild need to build. A code holding `% ; ? # / \` cannot be stored.

**Watch for (decision 12):** a scale or another system that also prints prefix 21 codes inside the same shop (its codes are skipped
by the generator only once typed in); a label printer that cannot hold the 0.33 mm module (the bars are 32 modules high, shorter than GS1's
full height, to fit the label).

**Watch for:** a scanner configured to send Tab instead of Enter (the Scan box needs Enter); a scan made with the cursor in
another field types the code into that field, as any typing would (the box takes the cursor back after each scan, and on a till it
has it when the form opens); label printers with other
sheet sizes (the CSS sheet is A4, 3 by 8); the V87 number, taken in the agreed merge order after V85 (#465) and V86 (#463), renumbered at merge if that order changes.
