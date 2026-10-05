# Retail pilot app behaviour (parity baseline)

**Status:** Draft · **Applies to:** the retail screens (R6) and the pilot cutover

What the pilot's current app does, read from its definition on 2026-10-05. It holds no names,
emails or figures. Each item is either kept, changed on purpose, or dropped; the retail screens
must not lose any of them by accident.

## 1. Roles and access

- Two roles, admin and sales. The app tells them apart by listing admin accounts inside many
  show-if expressions, which is why the retail module uses permissions instead (ADR-020).
- Admin only: recording purchases, creating products, the stock valuation and expected profit
  views, sales by profit margin, purchase records, cost and profit columns, cash withdrawals.
- Everyone signed in: recording a sale, usage and damage, expenses, daily savings, cash banked,
  advances and their payments, credit sales, low and out of stock lists, a product catalogue that
  shows quantity and selling price but not cost.
- Staff belong to one shop. The shop on every form is taken from the user's profile and cannot be
  chosen.

## 2. Recording a sale

- Filled automatically: user, time, shop, product category and unit, cost, profit, location.
- Unit price defaults to the product's selling price and must be above its cost: "Selling price
  cannot be lower than standard buying price".
- Quantity may not exceed the shop's available stock: "Amount cannot be greater than available
  stock".
- Sale type is cash or credit. A credit sale asks for a buyer from a list of credit buyers, fills
  the buyer's phone number and asks for a proposed payment date.
- The stock before and after the sale is stored on the row.
- Location is captured with its distance from the shop, as an audit signal. It does not block.
- A credit sale can be edited later; other sales cannot.

## 3. Recording a restock (admin only)

- Quantities are entered per shop. Unit cost and unit sell price default to the product's current
  prices and can be changed; that change is what updates the product's prices.
- Purchase amount per shop, total cost, expected selling total and expected profit are computed.
- Stock before and after is stored per shop. The supplier comes from a list.
- Stock-takes and customer returns have been recorded here under special supplier names.

## 4. Usage and damage

- Reason and kind (used in shop, damaged), quantity not above stock, valued at cost. The loss as a
  share of stock is shown to admins.

## 5. Cash handling

- Daily savings default to half of the shop's profit for the day (a change made in 2026).
- Cash banked defaults to the day's sales for the shop minus that day's savings.
- Expenses use a category and an item chosen from a list; the item "others" asks for an explanation.
- Withdrawals from the bank are an admin form.
- Advances get sequential ids. A payment can only be recorded against an advance that still has a
  balance, and the balance is the principal minus payments.

## 6. Views and dashboards

- Navigation: sales, banking, savings, expenses, advances; a menu with the rest.
- Stock valuation per shop and combined, expected sales and expected profit (admin).
- Low stock and out of stock lists, credit sales, sales by cash and by profit margin, grouped by
  shop, year and month; damaged and used records; purchase records; expense records.

## 7. What the app does not do

No transfer between shops, no supplier payables, no returns screen, no approval step, no audit
trail beyond the sheet's own history, and a valuation total that depends on a fixed row number.

## 8. Disposition for the retail module

| Behaviour | Retail module |
|---|---|
| Oversell refused; price above cost | Kept (ADR-020 decisions 4 and 5) |
| Admin-only purchase, product and cost views | Kept as permissions |
| Shop fixed by user profile | Kept as branch scope |
| Stock before and after stored on the row | Replaced by derived balances |
| Credit sale edited in place | Replaced by payments against the sale (FR-RET-05) |
| Daily savings, cash banked, expenses, advances | Later, with the cash book (pending ADR-022) |
| Location with distance from the shop | Later |
| Valuation totals on a fixed row | Replaced by the valuation report (FR-RET-09) |
