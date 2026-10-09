# Screenshots of the retail analytics screens (#149)

Taken on 2026-10-07 against the real stack: PostgreSQL 16 with the repository's init script, migrated to V28, the API jar from
`mvn package` on the `dev` profile, the PWA from `npm run build` served by a small static server that proxies `/api` (Host header
kept, so `sample.localhost` resolves the tenant), and headless Chromium driven by Playwright, signed in for real (invitation,
password, TOTP) as the tenant administrator. All data is fabricated: the tenant "Sample Shop (fabricated)" (retail on) has three
shops (Head Office, Town Shop, Market Kiosk), fifteen items in four categories, 44 days of sales by three fabricated sellers
(cash, mobile money and credit), a restock, usage and damage reports, a stock-take that found two items missing, three price
changes with sales after them, and credit sales part paid. The screens are shown for the Head Office branch.

Phone files are 360x740, desktop files 1280x800, each a screenshot of the window after scrolling to the section named. No screen
overflowed sideways at either width, and no console error or failed request was seen.

| File | Screen |
|---|---|
| `01-owner-dashboard-phone` | Retail home: today's sales, cash against credit, profit, the 7 day sparkline |
| `02-dashboard-shops-cash-book-phone` | The table by shop, the two marked cash book placeholders, then the screen tiles |
| `03-sales-analysis-phone` | Sales analysis: range, grouping and limit, the totals and the sales over time |
| `04-sales-analysis-top-items-phone` | Sales analysis: the top items as bars |
| `05-margins-price-changes-phone` | Margins: price changes, a card each with the margin before and after |
| `06-stock-health-reorder-phone` | Stock health: items under 14 days of cover and how much to buy |
| `07-credit-control-ageing-phone` | Credit control: what is owed by age and who owes it |
| `08-business-evaluation-phone` | Business evaluation: the stated formulas on today's prices, shown as an estimate |
| `09-owner-dashboard-desktop` | Retail home on a wide screen: the dashboard in the wide column |
| `10-margins-desktop` | Margins on a wide screen |

What the first pass changed: two money columns of seven digits did not fit a phone, so the profit goes under the sales (and the
quantity and margin under an item); the price changes became a card each; the dead stock and shrinkage tables were cut off and
became compact tables and cards; "1 reports" now reads "1 report"; the first table under a row of controls got room above it; the
home is the wide column when it carries the dashboard; and for a seller the tiles come before the dashboard.

The walk scripts are not committed.
