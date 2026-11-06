# Three rules for every screen

**Status:** Draft · **Issue:** #336 (split task #794) · **Decision:** ADR-049 (Proposed) · **Lives beside:** `docs/ui/design-system.md`, `docs/ui-rules.md`

The owner's aim is that a new user needs no training. Every staff screen follows these three rules. The audit of each screen
against them is in `docs/specs/retail-ui-notes.md`, section "Navigation and tour coverage" (rule 2) and section "The three
rules, screen by screen" (rules 1 and 3).

## Rule 1. Show little, let people open more

A screen opens short. A list is grouped (by category, by day, month or year, by branch or by buyer), each group header says
what is in it (a count and a total), and the rows sit behind the header until it is tapped. A record shows its main facts
first and keeps the rest behind a button.

- **Good.** All sales opens on one row per year with the number of sales and the takings. Tap a year for its months, a month
  for its days, a day for its sales (`sales-chain.tsx`).
- **Bad.** A list that draws every row the server sent, hundreds of cards one under another, with no group and no Show more.

Use `useCategoryGroups` with `GroupedCards` or `GroupedBodies` (`retail/category-groups.tsx`) for items, `SalesChain` for
sales, and `ListGroupBy` with `useListView` (`retail/list-chain.tsx`) for other chains. A short list (30 rows or fewer)
may start open.

## Rule 2. Every name opens its record

A name on a screen is a way in. A buyer's name opens the buyer's account, an item opens its stock, a sale number opens the
sale with its documents and returns, a branch opens its stock, and a total opens the rows behind it. The breadcrumb trail is
always there and leads back.

- **Good.** A credit note shows "Sale S-HQ-000123" as a row with a chevron; tapping it opens that sale
  (`/staff/retail/sales?sale=<id>`).
- **Bad.** The same sale number printed as plain text, so the user goes to All sales and searches for it by hand.

Use `ScreenLink` and `SaleLink` (`retail/screen-link.tsx`) and `ToStock` with `item` or `branch`
(`retail/analytics-ui.tsx`): each draws a link only for a person who may open the screen, and plain text otherwise, so a
link never leads to "not allowed". A screen that opens one record has a stable address that can be linked to and shared
(`useAddress` in `retail/screen-link.tsx` reads it):

| Record | Address |
|---|---|
| A sale | `/staff/retail/sales?sale=<id>` (Credit sales takes the same `sale`) |
| An item's stock | `/staff/retail/stock?item=<id>` |
| A branch's stock | `/staff/retail/stock?branch=<id>` (switches to that branch when the person may use it) |
| A category's items | `/staff/retail/catalogue/products?category=<id>` (`ToItems`, issue #798) |
| Used and damaged stock of a branch | `/staff/retail/usage-history?branch=<id>&from=<day>&to=<day>` (`ToUsage`, issue #798) |
| What stock counts found missing at a branch | `/staff/retail/usage-history?branch=<id>&from=<day>&to=<day>&kind=missing` (`ToUsage`, issue #863) |
| A unit's items | `/staff/retail/catalogue/products?unit=<id>` (for that visit only, issue #863) |
| A loan, a member (from an Insights drill-down) | `/staff/lending/loans/<id>`, `/staff/lending/members/<id>` (issue #863) |
| A buyer's account, a credit note, a delivery, an order | their own routes with the id in the path |

## Rule 3. No training needed

Plain words a shop owner uses, one clear main action, and an empty screen that says what to do next. The page tour helps,
but a screen should make sense without it.

- **Good.** An empty list says what to do: "No expenses in this period. Record one with Record an expense." and the one
  brand button on the screen is Record an expense.
- **Bad.** "No records found." on an empty page, two brand coloured buttons side by side, or a heading such as "GRNI
  variance" with no help tip.

In practice:

1. Words come from `frontend/src/app/glossary.ts` (`WORDS`). A word a shop owner would not use gets a `HelpTip`.
2. One primary button per view (`docs/ui-rules.md` rule 6). Other actions are plain buttons or rows.
3. An empty list uses `EmptyState` or `EmptyChoice` and names the next step, with a button or link to it when the person
   may take it.
4. No dashes, no emoji, no coloured side or top stripes, no gradients, no purple; info alerts are neutral grey.

## Checking a screen

Before a pull request adds or changes a staff screen, check it against the three rules, add its row to the audit when it
breaks one, and keep the page tour current (ADR-049; `tour-guard` and `tour/route-coverage.test.ts` check the tour).
