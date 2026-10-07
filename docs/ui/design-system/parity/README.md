# Screenshots of the retail parity pass (#145, #144)

Taken on 2026-10-06 against the real stack: PostgreSQL 16 with the repository's init script, migrated to V23, the API jar
from `mvn verify` on the `dev` profile, the PWA from `npm run build` served by a small static server that proxies `/api`
(Host header kept, so `shop.localhost` resolves the tenant), and headless Chromium driven by Playwright, signed in for
real (password, TOTP). All data is fabricated: the tenant "Sample Shop (fabricated)" (retail on) has three branches (Head
Office, Town Shop, Market Kiosk), twelve products in five categories restocked to all three, cash, mobile money and credit
sales (paid, part paid, unpaid, overdue), one damage report and one negative balance left as imported history.

Phone files are 360x740, desktop files 1280x800; no screen overflowed sideways at either width.

| File | Screen |
|---|---|
| `01-stock-branch-phone` | Stock on one branch: category under each item name, tabs All items, Out of stock, Low stock |
| `03-stock-all-branches-expanded-phone` | Stock with All branches: one card per item with the total, Show branches opens the quantity in each branch |
| `03b-stock-negative-all-branches-phone` | All branches with "Show only negative stock": the negative flag on the one branch cell that is negative |
| `04-stock-category-low-phone` | Category filter (Fittings) with the Low stock tab at Market Kiosk |
| `05-stock-value-profit-phone` | Stock value on one branch: at cost, at selling price, expected profit and percent, by category |
| `06-stock-value-all-branches-phone` | Stock value with All branches: a card per branch, then the total |
| `08-credit-sales-phone` | Credit sales: buyer, date, amount, due date, what is owed, state |
| `09-credit-sale-detail-phone` | A sale opened: facts, lines, paid and still owed, profit (admin only) |
| `10-all-sales-phone` | All sales with its filters |
| `11-sale-choose-branch-phone` | Record a sale with All branches chosen: the branches as buttons |
| `20-stock-all-branches-desktop` | Stock with All branches on a wide screen with three branches: the Item and Total columns stay in view (sticky), the branch columns are the ones that scroll, and Price and Cost are not cut off. Re-taken after the review of #154 from the built-in fabricated mock data (`VITE_RETAIL_MOCK=1`, three branches, headless Chromium at 1280x800), not the real stack |
| `23-stock-matrix-scrolled-desktop` | The All branches table at 1280x800 scrolled sideways (scrollLeft above 0), three branches (a column cloned in the page) and a long unbreakable word in the first item: header and body Total sit at the same offset and the word wraps inside the Item column. Fabricated mock data |
| `24-stock-matrix-scrolled-narrow` | The same at 720x800, the narrowest width that shows the table (below 720px the Stock page shows cards, not the table) |
| `22-credit-sales-desktop` | Credit sales on a wide screen |

The walk scripts are not committed.
