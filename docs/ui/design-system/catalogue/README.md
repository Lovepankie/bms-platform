# Screenshots of the catalogue screens (#146, #121)

Taken on 2026-10-07 against the real stack: PostgreSQL 16 with the repository's init script, migrated to V28, the API jar from
`mvn verify` on the `dev` profile, the PWA from `npm run build` served by a small static server that proxies `/api` (Host header
kept, so `shop.localhost` resolves the tenant), and headless Chromium driven by Playwright, signed in for real (invitation,
password, TOTP). All data is fabricated: the tenant "Sample Shop (fabricated)" (retail on) has three branches (Head Office, Town
Shop, Market Kiosk), a handful of items in a few categories, two suppliers and two credit buyers.

Phone files are 360x740, desktop files 1280x800. No screen overflowed sideways at either width. The walk also added items,
renamed and switched off a category, edited a supplier and a buyer, changed prices and imported a file, and met one refusal on
purpose (a duplicate code, 409).

| File | Screen |
|---|---|
| `02-catalogue-home-phone` | Catalogue home: a tile for each screen the session may use |
| `03-items-phone`, `03-items-desktop` | Items: search, category and On sale filters, a card per item with Edit and Change price |
| `05-item-added-toast-phone` | After adding an item: the toast above the bottom bar (the on-screen message is above the list) |
| `13-price-history-phone` | Change price (cost shown with `retail.profit.read`) and the price history below it |
| `16-category-off-phone` | Categories: "Used by N items", a switched-off category and the toast |
| `21-buyers-phone` | Credit buyers |
| `24-import-checked-phone` | Import items after "Check the file": rows to add, skip and fix |
| `27-daily-profit-phone`, `27-daily-profit-desktop` | Daily profit with the stock-take difference (#121): a line under the day on a phone, its own column on a wide screen |

What the first pass changed: the item filters share one row and "Add an item" is a plain button, so the list starts higher
on a phone; the add forms of Categories, Units, Suppliers and Credit buyers open from a button instead of filling the first screen;
the Catalogue tiles got icons; the import report is a card per row instead of a four line table; the price history says "cost
stays" when the cost did not change; and Daily profit takes a wider column so the sixth column fits on a wide screen. The walk
also found a real defect, fixed with a test: the price history failed to draw because the server sends `null` for a price with no
earlier value.

The walk scripts are not committed.
