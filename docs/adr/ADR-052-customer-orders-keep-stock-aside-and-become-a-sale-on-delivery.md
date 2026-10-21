# ADR-052: Customer orders keep stock aside and become an ordinary sale on delivery

## Status

Proposed (2026-10-21, issue #385; requested by the product owner for a baby clothes shop in Kampala that takes orders by phone, WhatsApp,
Instagram and Facebook and delivers them, and for a wholesaler). Builds on ADR-020 (stock as movements), ADR-033 (units, price lists,
customer accounts), ADR-043 (credit sale recognition) and ADR-048 (a store does not sell). The migration is `V70`, renamed at merge to the next
free number in merge order (main is at `V67`; the installable app, #380, takes `V68`; item variants, #384, is expected to take `V69`). The ADR
number is taken above the ones open pull requests hold (#380 holds pending ADR-050, #384 is expected to hold pending ADR-051) and is renumbered
at merge if needed.

## Context

A shop that sells through chats takes an order (who, the phone, where to deliver, the items, the delivery fee, notes) long before any money
moves. The goods must not be sold to someone else at the counter in the meantime, the order goes through packing and a rider, and on delivery
the customer pays all of it, part of it, or later on credit. The owner wants to see every order by step, the deliveries of the day, who did what
and when, the delivery fees taken and the orders cancelled and why, and where each order came from (WhatsApp, Instagram, Facebook, a phone
call, walk-in, other). BMS does not replace the chat apps: there is no chat, storefront or checkout of our own; the order is copied from the
chat, so entering it must take seconds.

The sale path (`SalesService.record`) already holds every rule of a sale: the branch and its role, a Store's refusal, units, price lists and
overrides, the price floor, the credit limit and its override, the receipt number, the stock movements, the two journal entries and the audit.
Quotations (ADR-033, #197) reuse it to become a sale. A second copy of that logic would drift; receipts, credit, returns, the reports and the
Controls page all read `retail_sales`.

## Decision

1. **An order is its own record in `retail.sales`**, three tables of V70: `retail_customer_orders` (number `OR` and six digits per tenant, the
   shop, the source, the customer as a credit buyer or a name, the phone, the place, the delivery day, the fee, the notes, the step, who took
   it out, the sale it became, how the fee was paid), `retail_customer_order_lines` (append-only: the product, the unit it was ordered in,
   the quantity, the base quantity kept aside, the price agreed) and `retail_customer_order_events` (append-only history: who did which
   step, when, with a note). Forced row-level security and composite tenant keys on all three. A guard trigger lets the step move forward only
   (ordered, packed, out for delivery, then delivered or cancelled) and by one version per change. The items of an order change while it is
   ordered or packed (amendment 1 below). The name, phone, place, day, fee and notes change while it is open, under `If-Match`.
2. **Stock is kept aside on the balance row.** `retail_stock_balances.reserved_qty` (V70, `CHECK >= 0`) is raised by `StockLedger.reserve`
   and lowered by `StockLedger.release`, both under the balance row's `FOR UPDATE` lock and in branch and product order like every movement,
   in the transaction of the order step. A hold larger than what is free (the balance less what other orders keep) is refused with 422
   `insufficient_stock`: two orders for the last unit are serialised on the row and one is refused. Every guarded movement (sale, usage,
   damage, transfer out, goods sent back to a supplier) may not take the balance below `reserved_qty`, so the till cannot sell a unit kept for
   an order; the refusal says the units are kept for customer orders. A stock-take may (the count is what is on the shelf), and the order then
   cannot be delivered until stock arrives. Reservations post nothing and move nothing, so the valuation, the reports and the reconciliation
   of balances against movements are unchanged. Imported history (`StockLedger.recordHistorical`) ignores what orders keep, as it ignores
   the zero floor: history already happened. A branch with an open order, or with stock kept aside, is neither closed nor made a Store
   (422 `branch_has_open_orders`, through the finding `open_orders` of the branch closing check; no reason overrides it).
3. **Prices are agreed when the order is taken**, by `SalesService.priceLines`, the rules a sale would apply that day (units, price lists, the
   price floor, an override with a reason). On delivery the sale is made at the agreed prices; while price lists are on an agreed price that
   now differs from the list is an override with the reason "Price agreed on order OR...". Delivery takes a trusted path of the sale
   (`SalesService.record(request, creditAmount, agreed)`): the price override permission, the price floor and the inactive product (or
   pack) are not checked again, since they were checked when the order was taken and the goods are already promised; the stock, the
   branch, the credit limit and every other rule of a sale are. So a price list change, a cost that rose above the agreed price, an item
   switched off since, or a deliverer who may not change prices never blocks a delivery. The order line keeps how its price was chosen
   (source, list, break, resolved price, and for a price changed then the reason and who changed it). On the sale line a price changed
   when the order was taken stays `override` with `price_overridden_by` naming that person, so the Controls page counts it once, for
   them; any other agreed price that today's list prices otherwise is `order_agreed`, which the Controls page does not count. The delivery
   fees report leaves out an order whose sale was voided.
4. **Delivery is one transaction through the ordinary sale path.** It locks the order row, then the credit buyer's row when customer accounts
   are on and the sale is on credit (the sale path's own order), releases what the order kept, calls `SalesService.record` (stock, the
   revenue and cost entries, credit and its limit, the receipt number, the audit), records the part paid now on a credit sale with
   `SalesService.recordPayment`, posts the delivery fee and marks the order delivered. The credit limit is checked against what actually
   goes on credit (the items less the part paid now, plus a fee left on the account), not the items total. A retry of packed, out for
   delivery (same rider) or cancel on an order already at that step answers the order as it is. Any refusal rolls the whole step back, the order still
   keeping its stock. It needs `retail.sale.create` at the order's shop besides `retail.order.manage`, and an `Idempotency-Key`.
5. **The delivery fee is income of its own.** It is posted as its own entry (debit the payment method's account, credit the new chart
   account 4120 `delivery_income`), never added to the sale, so the sale shows the items only, the profit reports stay item profit, and
   the fee is shown and reported separately. A cash customer pays it at the door: paid in full (cash, mobile money or bank) takes the
   items and the fee by that method; part payment and all later (credit) take at least the fee now (422 `delivery_fee_unpaid`), the fee
   first and the rest as a payment on the credit sale; paying everything with credit chosen is refused (422 `paid_in_full`). A credit
   buyer on the list (a wholesale or account customer) may have it put on the account instead (`fee_on_account`, credit only): the entry
   debits trade debtors with the sale as subledger, and until it is paid the fee is owed with that sale wherever a buyer's debt is read
   (the credit limit exposure, the buyer list and balance, the account statement as its own lines, credit control, the customer ageing,
   the credit counted when the buyer pays, and the check that refuses to make the shop a Store or to close it). The buyer's balance and
   the account page show it apart from what a payment on account can settle, each fee with its order, because it is paid on the order;
   a payment on account never takes it. Writing off the sale writes the fee off too (all of it a loss, Dr bad debt loss, Cr trade
   debtors, in the write-off's entry), keeps the fee part on the write-off (`fee_minor`) so the daily profit of goods counts only the
   goods as bad debt (the fee's income and its loss stay in the ledger), and marks it settled (`fee_paid_method` written_off); the
   sale shows the fee owed beside its balance, so a fee-only write-off can be made in the app, and an approved write-off is refused
   when the amount owed changed since it was asked for. It is paid once later (`POST /retail/orders/{id}/fee-payment`, debit the method's account, credit trade debtors against the sale). Voiding the
   sale of a delivered order reverses its fee entry in the same transaction and writes `sale_voided` in the history; a sale whose fee on
   account was already paid is not voided here (422 `order_fee_paid`). The cash book counts a cash fee as takings on the day of delivery
   (or of its later payment) and, when the sale is voided, among the voids of the day of the void, as it does a voided cash sale, so a past
   day is never rewritten and every day agrees with the ledger's cash on hand.
6. **Cancel releases the stock and keeps the reason** (at least three characters), with the day it was cancelled, for the cancellations
   report. A delivered or cancelled order changes no more.
7. **Where it came from and the quick entry.** Every order has a `source` (whatsapp, instagram, facebook, phone, walk_in, other); the list
   filters by it and the fees and cancellations reports group by it. `GET /retail/orders/customer-lookup?phone=` matches the last nine digits
   of a phone number (so +256 700 000 011 and 0700 000 011 are one number) against the caller's earlier orders and the credit buyers, and
   returns the name, the place and the fee of the newest order to fill in again. The screen asks for the phone first.
8. **One permission, `retail.order.manage`**, money moving, given to the administrator and to every role that holds `retail.sale.create`,
   scoped by branch. An order is taken at a shop (a Store refuses with `branch_does_not_sell`, ADR-048). No capability switch: the screens
   are offered to whoever holds the permission. No maker-checker action.
9. **Variants (#384).** An order line names a product and a unit like a sale line and is turned into a sale line on delivery, so when item
   variants land the line takes the variant the same way the sale line does and the reservation is per variant's balance row.

## Consequences

**Better:** one sale path, so receipts, credit sales, payments, returns, voids, the cash book, every sales report and the Controls page treat a
delivered order as the sale it is; the till cannot sell what was promised, and two orders cannot both get the last unit, enforced on the
balance row and not by the screens; the fee is visible on its own in the ledger and in a report; the owner sees each step, who did it and
when, where the orders come from and why they were cancelled.

**Worse:** a balance column that every guarded movement now reads; a stock-take can leave a balance below what orders keep, which the order
then shows when it is delivered and refused; a fee on account is paid on its order (the account page lists it apart and links to
it), not by a payment on account; the Controls rate of a person counts a change carried from an order they took against the sales
they entered themselves; the order's lines change only before it goes out (amendment 1); item photos in the order picker come with
#392.

**Watch for:** `reserved_qty` must always equal the base quantities of the open orders of that branch and product; a test proves it after
every step, and a nightly check beside the balance reconciliation is a follow-up. A future path that moves stock out must go through
`StockLedger.record` to keep the guard. If orders grow to be taken in a Store and sold in a shop, the reservation must move with a
transfer, which this design does not do.

## Amendment 1: the items of an open order change without cancelling it (2026-10-21, issue #393)

Chat customers change baskets often. A cancel and a new order filled the cancellations report and changed the order number, so the items
of an order now change in place.

1. **When.** Only while the order is ordered or packed. Out for delivery, delivered or cancelled refuse with 409 `order_step_not_allowed`
   in plain words (an order already out is cancelled and taken again). A packed order goes back to ordered (`packed_at` and `packed_by`
   cleared, the one step back the guard trigger allows), since what was packed is no longer what it holds; its history says so and to
   pack it again.
2. **How.** `POST /retail/orders/{id}/items` with the whole new set of lines, under `If-Match` (409 `version_conflict` on a stale
   version) and an `Idempotency-Key` (it is money moving: it changes what the order will sell). The permission is `retail.order.manage`
   at the order's shop; another branch's order is not found; a Store refuses as on taking an order.
3. **One transaction, the order row first.** The order row is locked, then the balance rows: `StockLedger.rehold` nets what the old lines
   kept against what the new lines keep per branch and product and applies it in one pass in branch and product order under each
   row's lock, so a change cannot deadlock against an order, a sale or a delivery. A rise larger than what is free (the balance less what
   orders keep) is refused with 422 `insufficient_stock`, naming the item by its description, and nothing changes. A change racing the delivery is serialised on the order row:
   either the change lands first and the delivery sells the new items, or the delivery does and the change is refused.
4. **Prices.** A line left as it was (the same item, unit and quantity, sent with no price or with the price agreed) keeps the price
   agreed and how it was chosen, written again as it was (an override stays the override of the person who changed it). It goes to
   `SalesService.priceLines` with an `Agreement` entry, as on delivery, so an item switched off since, a pack switched off or a cost
   risen above the agreed price does not block the change. Every other line is priced by `priceLines` exactly as on taking an order
   (units, price lists and their price breaks, the inactive item, the price floor, an override with `retail.price.override` and a
   reason, stored on the line with the caller as `overridden_by`). That includes a line already on the order whose quantity changed:
   its price break is found again for the new quantity and the agreed `price_break_min_qty` and resolved price are never carried onto
   it, and the agreed price sent with a larger quantity is a new override, needing the permission and a reason again and credited to
   the person changing it. Without a price, a new quantity takes today's price.
5. **The lines stay append-only.** Migration `V77` (main is at `V75` and PR #415 holds `V76` and merges first, so this one merges after
   #415) adds `retail_customer_orders.items_revision` and `retail_customer_order_lines.revision`; the new lines are written under the
   next revision and the order's current lines are those of its `items_revision`, so every earlier set of lines is kept. The guard
   trigger lets `items_revision` and `items_total_minor` change only on an ordered or packed order, by one revision with one version,
   leaving it ordered (a packed order goes back to ordered with its packing cleared). The order keeps its number.
6. **History and audit.** The history gets `items_changed` with who and when and a note in plain words (items added, removed, a
   quantity from and to, a price from and to, and the reason given); the audit row `retail.order.items_changed` keeps the lines before
   and after. Sending the same items again changes nothing and writes nothing.

The rule of **Watch for** holds after every change: `reserved_qty` equals the base quantities of the current lines of the open orders
(`RetailOrderItemChangesIT`).
