# ADR-070: Barcodes on products, scanning when selling, restocking and counting, and printed labels

## Status

Accepted (approved by the dev lead on 22 Oct 2026; issue #345, FR-RET-68; migration `V90`). Builds on ADR-020 (the retail
vertical), ADR-033 (pack sizes, the `units` capability) and ADR-009 (the PWA). Numbered ADR-070 because pending ADR-067, pending ADR-068
and pending ADR-069 are claimed by open pull requests; renumbered at merge if needed.

## Context

The owner wants the product to sell to supermarkets, hardware shops and every other kind of shop, and supermarket style selling
means scanning: a product's barcode is read at the till and the item lands on the sale. Until now products had no barcode field and
`docs/specs/retail-mvp-scope.md` listed barcode scanning as out of scope. The team's DSN code base already prints Code 128 labels and
reads scans from USB "keyboard wedge" scanners typing into a focused field; it has no camera decoding. Shops differ: some have a USB
or Bluetooth scanner at the till, many have only phones.

## Decision

1. **Barcodes are a part of the product, several per product.** A new table `retail_product_barcodes` (V90) holds each code with its
   format, the product and, optionally, one of the product's pack sizes (V39), so a carton's barcode adds a carton. Changing barcodes
   does not move the product's version, like its packs.
2. **Four formats, checked.** EAN-13, EAN-8 and UPC-A, whose last digit is checked by the GS1 rule, and Code 128 for any other
   printable code up to 48 characters (no spaces, no slash or backslash, since the lookup carries the code in its path). A code of 13,
   12 or 8 digits is read as EAN-13, UPC-A or EAN-8 unless Code 128 is asked for. The server (`Barcodes.java`) and the PWA
   (`barcode-state.ts`) apply the same rules, so the item form says what is wrong before saving.
3. **Unique per business among active items.** A unique index on `(tenant_id, lookup_key)`; `lookup_key` is a generated column that
   folds a twelve digit UPC-A into the thirteen digit EAN-13 a scanner may report, so the same printed code can never sit on two rows.
   A code held by an item that is switched off is released (deleted and audited `retail.barcode.released`) when an active item takes
   it; otherwise a second item is refused with 409 `barcode_taken`. Two writers of one code meet at the index and the second gets 409.
   Each business has its own codes under forced row-level security; the same code may exist in two businesses.
4. **One lookup for every way of scanning.** `GET /api/v1/retail/products/by-barcode/{code}` (`retail.stock.read`, optional
   `branch_id` for that branch's balance) answers the active item, the barcode and the pack it names, or 404 `barcode_not_found`. A code on a pack that is switched off, or on any pack while the units capability is off, is 404 `pack_inactive` with a plain message; the forms never add one base unit in its place.
   Product responses (list, get) carry `barcodes`, so a screen that has read the items can match a scan without the server when it
   is offline; the offline catalogue of the pending offline sales work (issue #458) caches those responses and gets the barcodes with
   them.
5. **Three ways in, one path out, on four forms.** The sale, the restock, the move stock and the stock count forms have a scan bar:
   (a) a keyboard-wedge scanner anywhere on the screen, told apart from a person by its speed (keys within 35 ms, at least four,
   closed by Enter); in a text field the burst is taken back out, so a scan with the cursor in a quantity box never changes it;
   (b) a code typed in the scan box and Enter; (c) the phone camera behind a Scan button. A found item is added at 1, or its line in
   that unit gains one (a counted pack adds its factor). An unknown code says so in plain words; an administrator
   (`retail.catalogue.manage`) gets "Add this barcode to a product" on the spot, which assigns it and adds the item. A beep and a short
   vibration confirm a scan where the device allows.
6. **The camera uses the browser first.** The browser's `BarcodeDetector` where it exists (Chrome on Android); elsewhere, Safari on an
   iPhone in particular, Quagga2 (`@ericblade/quagga2`, MIT, pure JavaScript, maintained) reads a still of the frame. Quagga2 was chosen
   over the WebAssembly decoders because the production CSP is `script-src 'self'` without `wasm-unsafe-eval`; pure JavaScript needs no
   CSP change. Both the camera view and Quagga2 are loaded only when Scan is tapped (separate chunks), so the first load does not grow.
   The Scan button shows only where the browser has `getUserMedia`; a blocked, missing or busy camera is explained in plain words. The
   `Permissions-Policy` already allows `camera=(self)`.
7. **Labels for items without a code.** `POST /retail/products/barcodes/generate` makes a Code 128 code `IN` plus eight digits from the
   business's sequence (`tenant_sequences`, key `retail_barcode`) for each item that has no made code; an item keeps the one it has.
   The Barcode labels page prints an A4 sheet of 3 by 8 labels of 70 by 37 mm with the name, the price and the barcode, drawn in the
   browser as SVG (Code 128 set B) and printed with the browser's print dialog; no PDF is stored.
8. **Who may do what.** Reading and scanning need `retail.stock.read` (every seller, storekeeper and administrator holds it). Adding,
   removing and making barcodes, and the labels page, need `retail.catalogue.manage` (administrators). No new permission.
9. **The import file** takes the optional columns `barcode` (synonyms `barcodes`, `ean`, `upc`) and `pack barcode`, several codes in a
   cell separated by spaces or `|`, and `barcode format` (blank, or a format name). A bad check digit, a code another active item
   holds or a code repeated in the file is an error row, never a guess.
10. **Codes a spreadsheet damaged.** An all-digit code of 6, 7, 9, 10, 11 or 14 digits, or a number in scientific notation
   (`4.00638E+12`), is refused in the import and on the item form with "the spreadsheet may have dropped a leading zero or turned
   the code into a number", unless the row's `barcode format` (or the request's `format`) is `code128`: no EAN or UPC has those
   lengths, and a spreadsheet drops the leading zero of a UPC-A or an EAN-13 and writes long numbers that way.

## Consequences

**Better:** a shop with a cheap USB scanner sells at supermarket speed with no setup; a shop with only phones scans with the camera;
an item with no manufacturer code gets a label; codes are checked, so a misread digit is caught at entry.

**Worse:** one more table and one more lazy dependency (Quagga2, about 45 kB gzipped, loaded only on Scan). Quagga2 reads stills,
so the iPhone camera is slower than the native detector. A code with a slash cannot be stored.

**Watch for:** a scanner configured to send Tab instead of Enter (the scan box and the wedge both need Enter); scanners that type
faster than the browser fires key events still fit the 35 ms window, a very slow Bluetooth scanner may not; label printers with other
sheet sizes (the CSS sheet is A4, 3 by 8); the V90 number against the open claims V81 to V89.
