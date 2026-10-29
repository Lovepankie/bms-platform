// Plain-words messages for the retail API's problem details (chapter 7 section 7.7). The server's
// `code` is the stable identifier; a code not listed here (for example one added after this file)
// shows the server's own message, so a new refusal is never hidden behind a vague text.

export interface RetailProblem {
  code?: string;
  detail?: string;
  title?: string;
  errors?: { field?: string; code?: string; message?: string }[];
}

/**
 * The idempotency answers that may hide a request that went through (chapter 7 section 7.8): a key still held by the first
 * request, and a key sent again with another body. The same strings as Idempotency.IN_PROGRESS and Idempotency.KEY_REUSED on the
 * server; quick-add.dom.test.tsx reads the Java source to keep them equal.
 */
export const IDEMPOTENCY_UNSURE_CODES: readonly string[] = ['idempotency_in_progress', 'idempotency_key_reused'];

const BY_CODE: Record<string, string> = {
  insufficient_stock: 'There is not enough stock at this branch for one of the items. Lower the quantity, or buy stock first.',
  price_below_cost: 'One of the prices is not above what the item cost. Raise the price.',
  idempotency_key_reused: 'This form was already used for a different entry. Reload the page and enter it again.',
  idempotency_in_progress: 'This is still being saved. Wait a moment and check Stock before trying again.',
  idempotency_key_missing: 'This could not be saved safely. Reload the page and try again.',
  permission_denied: 'You do not have permission to do this.',
  module_not_enabled: 'Retail is not switched on for this business.',
  branch_required: 'Choose one branch in the Branch box at the top of the page first.',
  branch_role_forbids_sales: 'This branch is a store and does not sell. Choose a shop in the Branch box.',
  branch_is_store: 'This branch is a store. Mark it as selling again on Branches before choosing shop or both.',
  branch_has_open_work: 'A stock move to or from this branch is not yet received. Finish it before changing the branch role.',
  capability_not_enabled: 'This is not switched on for this business.',
  // Price lists (ADR-033, issue #195).
  no_price_in_list: 'The price list has no price for one of the items. Add the price to the list, or sell without that list.',
  override_reason_required: 'Give a reason of at least 5 characters for changing a price.',
  price_item_overlap: 'This list already has a price for that item, unit and quantity in those dates. End the old price first, or choose other dates.',
  price_item_closed: 'This price already has an end date.',
  duplicate_price_list: 'A price list with this name already exists.',
  duplicate_customer_group: 'A group with this name already exists.',
  unknown_price_list: 'That price list does not exist or is switched off.',
  unit_in_use_by_price_list: 'This item has prices in a price list, so its unit cannot change.',
  // Customer accounts (ADR-033, issue #196).
  customer_required: 'A credit sale needs a customer from the list. Choose the customer, or add them first.',
  credit_limit_exceeded: 'This sale takes the buyer above their credit limit.',
  payment_exceeds_allocatable: 'That is more than this buyer owes on the credit sales you can take payment for.',
  allocations_must_equal_amount: 'The amounts given to the sales must add up to the payment.',
  sale_historical: 'An imported sale cannot be paid from here. Record this payment with your accountant.',
  receipt_reversed: 'This payment has already been reversed.',
  payment_not_reversible: 'A sale no longer holds this payment, so it cannot be reversed.',
  too_many_sales: 'This payment would settle too many sales at once. Record it in parts.',
  statement_too_long: 'That range has too many lines. Choose a shorter range.',
  // Optional documents (ADR-033, issue #197).
  quotation_expired: 'This quotation is past its last day. Make a new one with today\'s prices.',
  quotation_not_open: 'This quotation was already made into a sale or cancelled. Reload the page.',
  transfer_not_active: 'This stock move was cancelled, so it takes no new document.',
  documents_busy: 'Too many documents are being made right now. Wait a moment and try again.',
  // Customer returns (ADR-033, issue #201).
  return_exceeds_sold: 'More of this item is being returned than was sold and not yet returned.',
  sale_has_returns: 'This sale has returns against it. Void the returns first.',
  account_refund_unavailable: 'There is no balance to take this off. Refund it in money instead.',
  account_refund_historical: 'This is an imported credit sale. Refund it in money instead.',
  refund_remainder_method_required: 'Choose how the part that is not taken off the balance is paid out.',
  refund_exceeds_paid: 'More money would go back than was paid on this sale. Take the rest off what the buyer owes.',
  return_voided: 'This return has already been voided.',
  return_stock_moved: 'The goods this return put back are no longer all in stock, so it cannot be voided.',
  quantity_not_representable: 'That quantity does not convert exactly to the item\'s base unit. Change it.',
  approval_already_pending: 'A return of this sale is already waiting for a second administrator.',
  // Optional purchasing (ADR-033, issue #198). JUSTIFICATION-A3: one table entry per new server code, nothing existing carries them.
  order_not_draft: 'This order has been sent already, so it can no longer be changed. Cancel it and make a new one if it is wrong.',
  order_not_sent: 'This order has not been sent yet. Send it before goods are received against it.',
  order_closed: 'This order is closed (everything arrived, or it was cancelled), so nothing more can be received against it.',
  receipt_exceeds_order: 'More is being received than was ordered, beyond what the business allows. Lower the quantity, or ask an administrator to raise the allowance.',
  blind_receipt_over_limit: 'A delivery this big needs an order, which a second person approves. Raise an order and receive against it.',
  receipt_value_exceeds_order: 'With this delivery more would have been received than the order was approved for, because a price is higher than ordered. Check the prices, or raise and approve a new order.',
  before_order: 'A delivery cannot be dated before its order.',
  bill_before_receipt: 'The invoice cannot be dated before the goods it bills were received.',
  receipt_billed: 'This delivery is already on a supplier invoice. Void the invoice first, then void the delivery.',
  receipt_voided: 'This delivery has been voided already.',
  receipt_stock_moved: 'Some of these goods have already been sold, used or moved, so the delivery cannot be voided.',
  bill_exceeds_received: 'The invoice bills more than was received and not yet invoiced. Lower the quantity, or receive the goods first.',
  bill_total_mismatch: 'The invoice total does not match its lines. Check the quantities and prices against the paper invoice.',
  variance_reason_required: 'The invoice differs from the goods received by more than the allowed amount. Say why it differs, in at least 5 characters.',
  duplicate_supplier_bill: 'This supplier already has an invoice with this number.',
  bill_paid: 'This invoice has been paid, in part or in full. Reverse the payment first, then void the invoice.',
  bill_voided: 'This invoice has been voided already.',
  payment_exceeds_open: 'That is more than this supplier is owed. Lower the amount.',
  allocation_exceeds_open: 'One of the amounts is more than is still owed on that invoice. Lower it.',
  allocation_total_mismatch: 'The amounts given to the invoices must add up to the payment.',
  payment_reversed: 'This payment has been reversed already.',
  // Landed costs and supplier credit notes (issue #199). JUSTIFICATION-A3: one table entry per new server code, nothing existing carries them.
  landed_total_mismatch: 'The invoice total you typed is not the sum of the charges. Check both against the paper invoice.',
  allocation_basis_zero: 'These goods cannot be shared by that method, because it adds up to nothing. Choose another way to spread the charges.',
  line_returned: 'Some of these goods were already sent back to the supplier, so the charges cannot be put on them. Take that line off.',
  landed_cost_voided: 'This landed cost has been voided already.',
  landed_cost_stock_moved: 'The goods it added to stock have moved on since, so it cannot be voided.',
  landed_cost_credited: 'A supplier credit note stands against its invoice. Void the credit note first.',
  bill_is_landed_cost: 'This is the invoice of a landed cost. Void the landed cost instead.',
  bill_credited: 'A supplier credit note stands against this invoice. Void the credit note first.',
  receipt_costed: 'A landed cost already covers this delivery, so its hold cannot be released by hand.',
  receipt_not_costed: 'This delivery is held until its landed cost is in, so its goods cannot be moved out yet. Add the landed cost, or release the hold.',
  not_held: 'This delivery is not held, so there is nothing to release.',
  credit_total_mismatch: 'The credit total you typed is not the sum of the lines. Check both against the supplier\'s credit note.',
  credit_exceeds_billed: 'More is sent back than the invoice billed and earlier credit notes have not returned. Lower the quantity.',
  credit_exceeds_billed_value: 'The credit for a line is more than the invoice charged for it. Lower the amount.',
  credit_exceeds_on_hand: 'Only part of that delivery is still in the branch, because the rest was sold, used or moved. Lower the quantity.',
  credit_exceeds_open: 'The credit is more than the invoice still has to pay. Lower the amount.',
  duplicate_supplier_credit: 'This supplier already has a credit note with this number.',
  credit_voided: 'This credit note has been voided already.',
  credit_stock_moved: 'The stock this credit note changed has moved on since, so it can no longer be voided.',
  retail_chart_missing: 'The accounts needed for this are not set up for this business. Ask your administrator or support.',
  amount_out_of_range: 'That amount is too large or too small. Check it.',
  sale_voided: 'This sale has already been voided.',
  // Payments on a credit sale and voiding a sale (issues #326, #327).
  sale_not_payable: 'Only a credit sale that is not voided takes payments.',
  sale_has_payments: 'This credit sale has had a payment or a return. A sale with payments cannot be voided.',
  stocktake_committed: 'This stock count has already been committed.',
  stocktake_discarded: 'This stock count was cancelled. Count the items again.',
  transfer_voided: 'This move has already been undone.',
  received_exceeds_dispatched: 'More arrived than was sent for one of the items. Enter only what was sent.',
  transfer_not_in_transit: 'This stock is not on its way any more: it was received or taken back already. Reload the page.',
  transfer_in_transit: 'This stock is still on its way. Take it back, or wait for the other branch to receive it.',
  transfer_cancelled: 'This stock was taken back before it was received, so there is nothing to undo.',
  transfers_in_transit: 'Some stock is still on its way. Receive it or take it back before switching this off.',
  payment_exceeds_balance: 'That is more than the buyer still owes on this sale. Lower the amount.',
  version_conflict: 'This was changed by someone else a moment ago. Go back, open it again and repeat your change.',
  precondition_required: 'This could not be saved safely. Reload the page and try again.',
  price_unchanged: 'The new prices are the same as the current ones. Change at least one.',
  duplicate_product_code: 'An item with this code already exists. Codes ignore capital letters and spaces at the ends. Use another code.',
  item_code_made_by_app: 'The app makes the item code from the category, and an item keeps it. Reload the page and save again.',
  duplicate_category: 'A category with this name already exists.',
  duplicate_category_prefix: 'Another category already uses this code start. Choose other letters.',
  duplicate_unit: 'A unit with this name already exists.',
  duplicate_supplier: 'A supplier with this name already exists.',
  inactive_category: 'This category is switched off. Choose another one, or switch it on first.',
  inactive_unit: 'This unit is switched off. Choose another one, or switch it on first.',
  inactive_supplier: 'This supplier is switched off. Choose another one, or switch it on first.',
  // The cash book (ADR-022).
  suggestion_changed: 'Sales changed since you opened this page, so the suggested savings changed. Check the new figures and save again.',
  savings_exists: 'Savings are already recorded for this shop on that day. If that record is wrong, void it first.',
  reason_required: 'Give a reason of at least 5 characters for changing the amount.',
  suggestion_token_required: 'This could not be saved safely. Reload the page and try again.',
  amount_requires_profit_access: 'With your access the standard amount is recorded for you. Reload the page and save again without an amount.',
  cash_record_voided: 'This record has already been voided.',
  explanation_required: 'This expense item needs an explanation. Write what the money was for.',
  item_not_in_category: 'That item does not belong to the chosen category. Choose the item again.',
  category_inactive: 'That category is switched off. Choose another one.',
  duplicate_item: 'There is already an item with that name in this category.',
  duplicate_party: 'There is already someone with that name in the list.',
  account_not_expense: 'That account cannot be used for expenses.',
  party_kind_not_allowed: 'An advance can only be given to the owner, staff or a related company.',
  repayment_exceeds_balance: 'That repayment is more than the advance still owes.',
  advance_settled: 'This advance is fully repaid, so nothing more can be repaid on it.',
  advance_has_repayments: 'This advance has repayments. Void them first, then void the advance.',
  token_expired: 'Your session has ended. Sign in again.',
  unauthenticated: 'Your session has ended. Sign in again.',
};

/** What a person reads while a deploy switches the server over (issue #496). */
export const APP_UPDATING = 'The app is updating. Nothing was saved yet. Try again in a minute.';
/** The same, for a write with an Idempotency-Key that may have reached the server (a reset, a dropped connection or a 409 in progress): the key makes a retry safe. */
export const APP_UPDATING_MAYBE_SAVED = 'The app is updating, so this may not have been saved. Trying again in a minute is safe.';
/** The same, for a write without a key that may have reached the server: it may have been saved, so check before saving again. */
export const APP_UPDATING_CHECK = 'The app is updating, so this may have been saved. Check before you try again.';

const BY_STATUS: Record<number, string> = {
  401: 'Your session has ended. Sign in again.',
  403: BY_CODE['permission_denied'] ?? '',
  404: 'That could not be found.',
  428: 'This could not be saved safely. Reload the page and try again.',
  429: 'Too many requests. Wait a moment and try again.',
  // A bare proxy error during a deploy (issue #496, ADR-083): it may have reached the server, so never "nothing was saved".
  // The API client already words these from what they prove; this is the fallback when no body came through.
  502: APP_UPDATING_CHECK,
  503: APP_UPDATING_CHECK,
  504: APP_UPDATING_CHECK,
};

/** The message to show a person for a failed retail call. */
export function retailMessage(problem: RetailProblem | undefined, status: number): string {
  const p = problem ?? {};
  // Issue #257: only the 404 is a branch outside the caller's scope (trade-capabilities 2.2). A closed branch or a transfer's
  // destination is a 422 whose field message the server words for its own case, shown below.
  if (p.code === 'unknown_branch' && status === 404) return 'That branch is not one you work in. Choose another branch in the Branch box.';
  const known = p.code ? BY_CODE[p.code] : undefined;
  if (known) return known;
  if (p.code === 'validation_failed') {
    const fields = (p.errors ?? []).map((e) => (e.code === 'future_date' ? 'The date cannot be in the future.' : e.message)).filter((m): m is string => !!m);
    if (fields.length > 0) return fields.join(' ');
  }
  // An unknown code: the server's message is the best text there is.
  if (p.detail) return p.detail;
  if (p.title) return p.title;
  return BY_STATUS[status] ?? (status >= 500 ? 'The server had a problem. Try again in a moment.' : 'The request failed.');
}
