# ADR-093: Entry warnings are rule based, read from the item's own history, and never block

## Status

Proposed (issue #353, the owner's request of 20 Oct 2026 in the go-live queue), accepted on merge. Builds on ADR-044 (entry
mistakes corrected inside the app, the name key of #352), ADR-055 (who sees prices), ADR-079 (base units) and ADR-082 (a buying
pack's size per delivery). Adds FR-RET-74 and FR-RET-75 and migration `V104` (three indexes only). The AI suggestion step the issue
calls optional is parked by the owner and is not built: nothing here calls a model.

## Context

Staff make entry mistakes the app can see coming: 2.5 mm cable bought in metres when the shop always buys it in rolls, 200 typed
for 2, a selling price typed with an extra zero, the same item added again under a slightly different name, an item filed in the
wrong category. The owner wants the app to catch these itself, from the business's own records, before they are saved (#351 and
#352 correct them after). Main already had the parts that stay as they are: the price floor (`retail.price.below_cost`, a
refusal), the duplicate name refusal and the close name warning at save time (#352), the per-delivery pack size (#554, #555),
the one category picker (#584) and item codes made by the app (#568, #562).

## Decision

1. **Rule based, deterministic, explainable.** Every warning is a sentence built from figures the person can check: the median
   of the item's recent lines, the unit most of them used, the last price charged. No model, no score, no learning.
2. **The norms, read once per item.** `GET /retail/products/{id}/entry-norms` (`retail.stock.read`) returns, for the item's
   newest 20 purchase lines (voided restocks left out), 20 sale lines (voided sales left out) and 20 move lines (voided and
   cancelled moves left out): how many lines were read, the median quantity in base units, and the usual unit, the one used on
   more than half of those lines (a pack's unit, or the item's own). A median and a usual unit need at least 3 lines; with fewer
   the screens warn about nothing. The median of an even count is the mean of the two middle lines. It also returns the price per
   base unit of the last sale (line total over base quantity, rounded half up) and its date, only to someone who sees prices
   (ADR-055): a storekeeper gets quantities only, and a seller who sees only their own figures (no `retail.sale.all_sellers`)
   gets the last price of their own sales among the item's newest 200 sale lines, never another seller's. History is a sale
   whose status is `completed` (a sale is `completed` or `voided`; a credit sale written off, an imported one and one recorded
   offline are `completed`, so they count; a return does not take a line out), a purchase with no void, and a move that is
   neither voided nor cancelled (a move on its way, `in_transit`, counts: the quantity was moved). Quantities are base units: a line sold or bought in a pack keeps `qty` as the packs times
   the factor (V39), and its pack is its unit. The thresholds are sent with the norms (`quantity_factor` 10, `price_factor` 2,
   `min_lines` 3) so the screens never hold their own copy. A screen reads an item's norms once and keeps them five minutes;
   a read that fails or has not answered yet means no warning, never a wait.
3. **Bounded reads.** Each read walks one item's index entries newest first and stops at its limit: `V104` adds
   `(tenant_id, product_id, created_at DESC, id DESC)`, the order the reads ask for, on `retail_sale_lines`,
   `retail_purchase_lines` (neither had an index on the product) and `retail_transfer_lines` (whose `(tenant_id, product_id)`
   index has no order, so a move read would have taken and sorted every move the item ever had). `EntryChecksIT` checks the
   plans at the size of a busy shop: each is a Limit over that index, with no sort and no scan of the line table; the last
   price read sorts at most the few lines under its inner Limit (1, or 200 for a seller's own). `V104` is indexes only, built in Flyway's transaction behind a five second `lock_timeout`,
   as `V101`.
4. **The checks.** On Buy stock and Sell, where a unit is chosen, a line in another unit than the usual one says so: "You
   usually buy this in rolls; you entered 200 metres. Is that right?". Otherwise a quantity ten times the median or more, or a
   tenth of it or less, says the usual amount in the unit entered ("You usually buy about 3.5 rolls of this at a time; you
   entered 40 rolls."). A line gets at most one of these two: a wrong unit makes the quantity look wrong too. Move stock is always
   in the item's own unit and compares with the item's moves, or with its purchases while it has fewer than 3 moves. Count stock
   compares a count with what the records hold at the branch, or, where they hold nothing, with the usual purchase; a count of 0
   (the item is gone) is never questioned. On Sell, a price the seller typed that is twice the last price or more, or half of it
   or less, says the last price in the unit sold; a price the app filled (the item's, a price list's) is the owner's own and is not
   questioned. The price floor stays the server's refusal, unchanged.
5. **Never a block.** The warnings show only when the person saves, each next to its field as a tinted note with Confirm and
   Change. Any change to a line, a reset of the form included, takes the warnings showing, the save waiting on
   them and what was confirmed away, so Save asks again about the
   form as it is. Confirm checks the screen as it is at that moment: a warning whose line was edited since is gone, a new one shows,
   and only when none is open does the save go on, with the values the screen shows then (never a request built earlier); Change takes the warning away, puts
   the cursor in the field and saves nothing. The save button is never disabled by a warning, and the server never refuses a
   line for being unusual. A confirmed warning stays confirmed while the line says the same; a changed line is checked again.
   Offline, or for a sale whose request was already sent, nothing is read or asked.
6. **Recorded when confirmed and saved.** Once the save a confirmation went with has succeeded (on Count stock, once the count
   is committed: the review is not the save), the screen sends each confirmed
   warning of that save to `POST /retail/entry-warnings/confirmations` (`retail.stock.read`, 204), which writes one audit row,
   `retail.entry_warning.confirmed`, on the item, with the screen, the kind (the warning's code) and its numbers only: the
   quantity entered (digits only), the pack unit's id, the median or records compared with and, for a price, both prices. No
   text from the client is stored; the branch is kept only when the caller works there. A warning
   confirmed beside one that was changed, or a save the server refused, records nothing. A confirmation answers that one save
   only: the next entry, even of the same item and quantity, is asked about again. No table is
   added: the audit log is where the owner already reviews what staff did. A failed record never stops the save.
7. **Naming a new item.** While a new item's name is typed (Add an item, the item form in a category, and the new item on Buy
   stock and Count stock), `GET /retail/products/name-hints` (`retail.catalogue.manage`) gives "Did you mean": at most 5 active
   items whose name, with case, accents, spaces and punctuation left out (the #352 name key, spaces removed), holds what is typed
   (those starting with it first), then those a few letters apart (the #352 edit distance rule). It also gives the category of
   similar items: of the active items sharing the most words of 3 letters or more with the name, the category most of them are
   in (ties to the one with more items sharing any word, then by name), with one of them as the example. Fewer than 3 letters and
   digits give nothing. The person taps an item to open it (on Buy stock and Count stock, to use it instead), or taps Choose
   Cables to choose the category; nothing is chosen for them, no code is typed (#562), and the save-time name rules of #352 are
   unchanged. Not offered while an existing item is changed. The typed name is read up to 200 characters.

## Consequences

- Most mistakes of unit and magnitude are caught at the moment they are typed, from the business's own records, at the cost of
  one small indexed read per item on the screen.
- A new item, or one with fewer than 3 lines, is never warned about; a business's first weeks see few warnings.
- A genuinely unusual delivery (a year's stock bought at once) is warned about once per line and confirmed; the confirmation is
  on record, which the owner wanted.
- A known limit of the name hints: the close names (a few letters apart) and the items sharing words are taken in item code
  order up to a bound (5,000 and 200) before they are scored, so on a very large catalogue a close name or the best category
  can be missed because of where its code sorts. A catalogue of that size needs a search index on the name, not built here.
- The thresholds are fixed (10 for quantities, 2 for prices). A per-business setting is left for when the owner asks.
- The AI step stays parked; if it is ever built it sits behind the AI gateway, off by default, and only adds a suggestion with
  its reason beside these rules, never replacing or saving.
