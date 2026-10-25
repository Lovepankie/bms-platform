# ADR-084: Buy stock on credit and pay suppliers without the purchasing capability, on the purchasing payables

## Status

Proposed (owner decision D15 of 23 Oct 2026, issue #557), accepted on merge. Amends ADR-033 as amended by issue #198 (optional
purchasing): its supplier payments, open items and payables report leave the `purchasing` capability; purchase orders, goods
received, supplier invoices, landed costs, supplier credit notes and the purchasing settings stay behind it. Adds FR-RET-73.
Migration `V97` (one check of `retail_purchases`).

## Context

A shop buys stock from a supplier and often pays later. Buy stock (the plain restock of issue #53) already took "credit" as a way
to pay: it posts inventory against trade creditors with the supplier as the subledger, and V12 makes a credit restock name its
supplier. But nothing outside the optional purchasing capability (issue #198, V47) could see or pay that debt: the supplier
payment, the open items of a supplier and the payables report answered 404 `capability_not_enabled`, and that capability brings
purchase orders, goods received and the three-way match, which a small shop does not want. The owner asked (D15) for what the app
already does for customers who owe: buy on credit, see what each supplier is owed, pay a supplier, a statement with a running
balance, and the right journal and cash book.

V47 already has everything a payable needs: `retail_supplier_payments` and their allocations pay a credit restock (or an invoice),
split per branch, posting trade creditors against the method's account, with the cash book's cash-out for a cash payment
(`CashPurchases`), a whole-payment reversal and a supplier row lock against two payments spending one balance.

## Decision

1. **One payable system: the purchasing payables, reused.** No second payable table, account or permission. A credit restock is
   an open item of its supplier as before; a payment pays it through `retail_supplier_payments`. The routes
   `POST`, `GET /retail/supplier-payments`, `GET /retail/supplier-payments/{id}`, `POST .../{id}/reverse`,
   `GET /retail/suppliers/{id}/open-items` and `GET /retail/reports/supplier-payables` no longer need the `purchasing`
   capability. They keep `retail.payable.manage` (the administrator's, ADR-055); a buyer with `retail.purchase.create` may buy on
   credit but not pay or see what is owed. With the capability on, supplier invoices are more open items of the same supplier, as
   before.
2. **Paid how on Buy stock.** A restock is paid in cash, by mobile money, by bank, or on credit. Mobile money is new: it credits
   the branch's `mobile_money` account and, like bank, never touches the till (`V97` widens the `payment_method` check). On credit
   still needs a supplier (422 on `supplier_id`, and the screen says so before it sends).
3. **A payment has a date.** `paid_on` is optional (today in the business time zone when absent) and never in the future (422
   `future_date` on `paid_on`). The payment posts on that day, in every branch's entry, and a cash payment is a cash-out of that
   day's till, which `CashPurchases` already reads by `paid_on`. It pays only what was owed on that day: the open items dated on or
   before it (a manual allocation to a later one is 422 `after_payment_date`; oldest first stops at what was owed then, 422
   `payment_exceeds_open`), so the statement's running balance never goes below nothing, and no payment is dated before the
   oldest thing it pays, as a customer payment is dated between its sale and today. A cash payment is refused once a banking that
   stands at the paying branch is dated on that day or later (422 `cash_day_banked`): the cash book carries unbanked cash forward,
   so such a banking already counted that day's cash, and a banked day is not changed after the fact (today too, once today is
   banked). The check runs after the supplier row lock, which does not serialise it with a banking
   being recorded at the same moment (a watch for below). The cash book has no other close of a day and expenses no back-date
   window, so none is copied here; the ledger refuses a date in a closed period. Mobile money and bank never touch the till. On
   the screen the date stays empty, meaning today, until the person picks one, so a form left open overnight pays the day it is
   sent. A reversal stays dated the day it is made.
4. **The supplier statement.** `GET /retail/suppliers/{id}/statement?from&to` (`retail.payable.manage`, the last 90 days by
   default, at most 731 days and 2,000 lines): the balance at the start of `from`, then each credit restock (never an imported one,
   which no payment can pay) and its void, each supplier invoice and credit note and their voids, and each payment and its reversal
   on its own day with the balance after it, and the closing balance. Within a day lines keep the order they were entered, and a
   void or reversal never comes before what it undoes. In a limited branch scope an item counts when all its branches are in scope
   (as on the payables report) and a payment by what it paid on those items, so the closing balance is what the open items say is
   still to pay. The screen shares it as plain text through the phone's share options, or WhatsApp where the phone has none; no
   PDF and nothing stored.
5. **Screens.** Buy stock asks Paid how (required, four choices, glossary words); What I owe suppliers sits under the Money tab
   with the total and, per supplier, Pay and Statement; Pay a supplier asks the date paid; the supplier statement is
   `/staff/retail/suppliers/$supplierId/statement`. With the purchasing capability the payables page keeps its ageing table and
   the goods received and not yet invoiced.

## Consequences

**Better:** a shop buys on credit and pays later with no capability to switch on and no purchase-order flow; the debt, the
journal and the cash book come from the code that was already right for the purchasing capability; a business that later turns
purchasing on sees the same payments and adds invoices beside them.

**Worse:** the payment and payables routes answer the same with the capability on or off, so "capability off equals today"
(ADR-033) no longer holds for them; a cash payment dated on an earlier day that is not yet banked changes that day's cash book,
as a back-dated expense does.
There is no back-date window, as for expenses (`CashbookSupport.businessDate` refuses only a future date): the banked-day guard
(`cash_day_banked`) and the oldest purchase paid are the only limits on how far back a payment may go.

**Watch for:** a banking and a back-dated cash payment of the same branch and day recorded at the same moment can each miss the other; a branch-day lock shared with the cash book would close it. A ledger period closed by an accountant refuses a payment dated inside it (the ledger's own rule), which the screen
shows as the server's message. Unapplied supplier credits and a cash refund from a supplier stay deferred (issue #199). A PDF of the
supplier statement, if owners ask for one, belongs with the documents capability like the customer statement's.
