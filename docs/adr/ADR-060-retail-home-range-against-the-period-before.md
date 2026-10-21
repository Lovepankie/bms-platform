# ADR-060: The Retail home follows one chosen range, set against the period before, from the dashboard's own dated queries

## Status

Proposed (owner request, issue #428, 21 Oct 2026). Extends the owner dashboard of issue #149 without changing its body when no
range is asked for, and keeps every rule of ADR-043 (credit sales counted when the buyer pays), ADR-055 (retail roles: a seller sees
only their own figures, no cost, profit, margin or stock value without `retail.profit.read`, branch scope, the Supervisor at its
branches) and ADR-047 (no league tables). The number follows pending ADR-058 (host checks, pull request 427) and pending ADR-059 (pull request 433),
which merge first. No migration.

## Context

The Retail home showed fixed windows (today, the last 7 and the last 30 days) and two small sparklines. The owner asked for the
range row of a stocks app (1D, 1W, 1M, YTD, 1Y, all, and two chosen days), with every figure on the page following it, a chart
large enough to read with the period before beside it and a value for each point, and more detail under it: the best shop, the best
seller, the busiest day, the average sale and the number of sales, each against the period before. The roles of ADR-055 had just
landed on main, so whatever the page shows must hold them, and the page is used on phones on slow connections and as the installed
app.

Three questions had to be settled: what "the period before" is for each range, where the figures come from, and how a seller's own
card (ADR-055 keeps the dashboard from them) follows the same range.

## Decision

1. **The ranges and their period before.** `GET /retail/reports/dashboard` takes `period`: `today`, `7d`, `30d`, `ytd`, `1y`,
   `all`, or `custom` with `from` and `to` (dates alone are a custom range). The days are counted on the business date. Today, 7 and
   30 days and a custom range are set against as many days just before them; This year against the same days of last year (1
   January to the same date, 29 February read as the 28th); 1 year (today and the 364 or 365 days before) against the year before
   it. Across a leap boundary the two years differ by a day (java.time maps 29 February of one year to the 28th of the other), so
   the period before of This year or 1 year can be one day longer or shorter than the range; the chart lines its steps up from each
   period's own first day and drops a step of the period before that falls past the last step of the range. This is accepted: it
   is the same rule as any "same day last year" comparison. All starts at the first completed sale in the branches covered, at
   most three years back, and has no period before, so nothing is compared; with no completed sale in scope it is the single day
   today. A custom range follows the window of every other dated report (at most 366 days, the first day not after the last) and
   may not end after today; a wrong period, dates with a named period, a custom range without both days, or one ending in the
   future is 422.
2. **One read of the dashboard's own queries.** The range reuses the dated queries the dashboard already had: the sales by branch,
   day and way of paying over the net lines of sales and returns, the credit recognition reads of ADR-043 (the sales counted when the
   buyer pays, the payments, the money collected, the bad debt), and the sales analysis grouping for the best selling item and the
   top salesperson (one row, and the same item or person over the period before). The last 30 days (the Today card, the 7 and
   30 day figures, the shops) are read over their own 30 days, and the range with its period before over its own days, never over
   everything from the period before up to today: an old custom range reads its own span and the span before it, and a range
   that falls inside the last 30 days shares that one read. The credit events read picks its sales by the events the branch scope
   reports, in SQL, instead of reading every event of the tenant. All is bounded to three years, and over more than two years (731 days) the
   best seller and the top salesperson, which scan every sale line of the span, are left out (the home says so). No new table,
   index or permission.
3. **The body stays lean.** Without a range the body is exactly as it was. With one it carries `range`: the figures of the range
   and of the period before (sales, number of sales, average sale, profit only with `retail.profit.read` in every branch covered,
   money collected only under `on_payment`), the chart as steps (a day up to 62 days, seven days from the first day up to a year, a
   calendar month beyond, which only All reaches) with the period before on the same step, the busiest day, the best shop, the best
   seller and the top salesperson. The 30 day list and the shops' fixed windows are left out; each shop carries its range sales, its
   sales in the period before and, with profit read in that shop, its profit. A year is 53 steps and All a step a month, so the
   body stays a few kilobytes on a phone.
4. **Roles.** The dashboard is still 403 without `retail.sale.all_sellers` in every branch covered (ADR-055) and covers the
   supervised branches of a Supervisor. Every range figure follows the same branch filter. No cost, profit, margin or stock value
   appears without `retail.profit.read`. The top salesperson is a ranking of colleagues, so it follows the rule of the salesperson
   level of the sales summary: only with `retail.profit.read` in every branch covered (ADR-047 keeps rankings off the Controls page).
   The best shop is chosen among the shops whose sales the caller reads.
5. **A seller's own card follows the same range** (its today is the business's own day, from the session's tenant time zone, as the
   dashboard's) through the sales summary that already serves it (ADR-055): by day up to 31
   days, by month up to a year and by year for All, following the summary's pages, with the period before worked out in the PWA by
   the same rules as decision 1. It shows the seller's total, number of sales, average sale and best day or month against the period
   before, and never a cost or profit field, because the summary sends none to a seller.
6. **What does not follow the range.** The stock value and the out of stock and low stock counts are a position, so they stay as
   of now and say so. The cash book cards (banked against expected, savings set aside, expenses) follow the range from the daily
   cash summary for a person who closes the till (`retail.cashbook.read` with `retail.till.close`), up to 31 days at a time; longer
   ranges link to the Cash summary. Reports and exports that take dates are unchanged.
7. **The choice is remembered per person per device** in the browser's storage, keyed by the host and the user, like the chosen
   branch; a value that is no longer a choice falls back to 7 days. It is a convenience, not a setting, so nothing is stored on the
   server.
8. **The Today card stays.** Above the picker the home keeps the card it always had (Sales today, Cash and mobile money, On credit,
   Profit today with the same permission gating; under `on_payment` Sales today, Money collected, On credit and Profit today),
   whatever range is picked. The range view sits below it and adds to it; no figure was dropped to make room.
9. **The PWA.** The picker is a row of buttons (Today, 7 days, 30 days, This year, 1 year, All, Choose days) that wraps on a phone;
   the chart is inline SVG with no style attribute (the CSP), the period before dashed, a step read out under it on hover, tap or the
   arrow keys (the chart is a slider; the spoken readout settles after a pause instead of following every move of a drag, and the
   chart's box is read once per touch, not on every move); up is the success colour and down the danger colour, each also said in words with an arrow. The home page tour gains
   the step for the picker (version 3).

## Consequences

**Better:** every figure on the home follows one choice and is read against a fair period before; the owner sees who and what did
best without opening a report; the figures come from the same queries as before, so they agree with the dashboard and the sales
analysis; nothing new to migrate or to secure.

**Worse:** the dashboard reads as many days as the range and its period before (two years for 1 year, three years for All) plus
the last 30 days; the best seller and top salesperson are absent over a range longer than two years; the PWA works out the seller's days itself, so the rules live in two places (`DashboardRange` and `range-state.ts`), each
with its own tests.

**Watch for:** the time of All on a tenant with three years of busy history against the 60 second statement timeout of the
deployment (CI allows 180); a tenant whose history is older than three years sees All start three years back;
the cash book cards if the 31 day bound proves too short; a request to remember the choice across devices, which would need a
user preference on the server (ADR-025 keeps tour progress there).
