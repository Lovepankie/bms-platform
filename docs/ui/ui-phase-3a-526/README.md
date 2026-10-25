# UI phase 3a, the cross-cutting fixes (issue #526)

Phone screenshots, 412 by 900, taken with Playwright against `VITE_RETAIL_MOCK=1 npm run dev` (fabricated data, no backend),
as the mock administrator. Each pair is the screen on `main` before the change and on the branch after it. The mock answers no
Home summary, so Home's cards say "Could not load" on both sides.

| Pair | What changed |
|---|---|
| `01-home` | One 56px top bar (the business and branch as the title, Search, one menu); the cards end in a chevron row, not an underlined link |
| `02-sale` | The empty list is one line ("No items yet. Search or scan to add."); Record sale is brand filled and sticky over the tabs |
| `03-stock` | No "Branch:" line; the phone crumb is a back chevron with no slash |
| `04-expenses` | Expenses report and Expense lists are chevron rows; Save expense is brand filled and sticky |
| `05-savings` | Suggested savings is the key figure; the records take the period picker |
| `06-cash-summary` | Today, This week, This month, Custom in place of two US date fields; the days read "17 Oct to 24 Oct 2026" |
| `07-empty-transfers` | The empty state is one plain line, no dashed box; Move stock to another branch is a button |
| `08-report-profit` | The period picker; Profit for the period as the key figure; days read "18 Oct 2026" |
| `09-credit-control` | The help paragraph is one line with an info button; the period picker |
| `10-no-access` | No dashed box: who can give access, and Go to Home |
| `11-top-menu-after` | The one menu: who is signed in, Help, Refresh with when the page was loaded, the theme, (Sign out from 720px) |
