# ADR-094: A purchase order is sent only while every item on it is active

## Status

Proposed (issue #376, a follow-up from the review of #364), accepted on merge. Amends ADR-045 (inactive products are never
deleted), whose Watch for left this case open. No migration, no new permission, no new error code.

## Context

ADR-045 decision 2 keeps an inactive item out of new documents: a sale, a restock, a stock move and a purchase order line refuse
it with 422 `product_inactive`, and goods received against an order that was already sent are the one exception, because they
are already on the way. Editing a draft order (`PUT /retail/purchase-orders/{id}`) plans every line again and so refuses an
inactive item too. Sending a draft (`POST .../send`) did not look at its items: a draft written while an item was active could
still be sent to the supplier after the item was switched off, and so could a draft whose send was waiting for a checker when
the item was switched off, since switching an item off does not move the order's version and so does not make the approval
request stale.

## Decision

1. **Sending is the moment the order becomes a commitment to the supplier, so it is checked like a new order line.** `POST
   /retail/purchase-orders/{id}/send` refuses a draft holding an inactive item with 422 `product_inactive`, naming the item's code
   and the two ways out: switch it on again on the Items screen, or take it off the order. The order stays a draft.
2. **The approval path checks again.** When the approval rules ask a checker (ADR-091), the check runs before the request is
   made, and again when the approval sends the order (`OrderSending.markSent`). An item switched off while the request waited
   is refused at that point: the approve call answers 409 `product_inactive`, the order is not sent and, as for every action the
   approval rules govern (ADR-091), the request ends `stale` with the reason. It is never approved later as it stood; once the
   item is switched on again (or taken off the draft) the maker sends the order again and a new request is asked.
3. **Unchanged:** goods received against an order already sent are still received whatever the item's state (ADR-045 decision
   2); a cancel, a receipt's void and the rest of ADR-045's corrections are not checked.

## Consequences

**Better:** a supplier is never sent an order for an item the business has stopped dealing in, and the send, the edit and the
new order now follow one rule.

**Worse:** a draft written before an item was switched off has to be edited, or the item switched on again, before it can be
sent; a waiting request for such an order ends `stale` and the maker asks again after the fix.

**Watch for:** a new way to send or re-send an order must call `OrderSending.requireActiveItems`. The check reads the items
without locking them, as the sale, restock and order-line checks of ADR-045 do: an item switched off in the same instant as
the send can still go out once, which the receipt then accepts (ADR-045 decision 2). Tests:
`RetailTradePurchasingIT.aDraftWithAnItemSwitchedOffSinceIsNotSentNorSentByItsApproval`.
