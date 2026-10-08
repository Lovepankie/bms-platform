// Plain-words messages for the retail API's problem details (chapter 7 section 7.7). The server's
// `code` is the stable identifier; a code not listed here (for example one added after this file)
// shows the server's own message, so a new refusal is never hidden behind a vague text.

export interface RetailProblem {
  code?: string;
  detail?: string;
  title?: string;
  errors?: { field?: string; code?: string; message?: string }[];
}

const BY_CODE: Record<string, string> = {
  insufficient_stock: 'There is not enough stock at this branch for one of the items. Lower the quantity, or restock first.',
  price_below_cost: 'One of the prices is not above what the item cost. Raise the price.',
  idempotency_key_reused: 'This form was already used for a different entry. Reload the page and enter it again.',
  idempotency_in_progress: 'This is still being saved. Wait a moment and check Stock before trying again.',
  idempotency_key_missing: 'This could not be saved safely. Reload the page and try again.',
  permission_denied: 'You do not have permission to do this.',
  module_not_enabled: 'Retail is not switched on for this business.',
  branch_required: 'Choose one branch in the Branch box at the top of the page first.',
  sale_voided: 'This sale has already been cancelled.',
  stocktake_committed: 'This stock-take has already been committed.',
  transfer_voided: 'This transfer has already been cancelled.',
  payment_exceeds_balance: 'That payment is more than the buyer still owes.',
  version_conflict: 'This was changed by someone else a moment ago. Go back, open it again and repeat your change.',
  precondition_required: 'This could not be saved safely. Reload the page and try again.',
  price_unchanged: 'The new prices are the same as the current ones. Change at least one.',
  duplicate_product_code: 'An item with this code already exists. Codes ignore capital letters and spaces at the ends. Use another code.',
  duplicate_category: 'A category with this name already exists.',
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

const BY_STATUS: Record<number, string> = {
  401: 'Your session has ended. Sign in again.',
  403: BY_CODE['permission_denied'] ?? '',
  404: 'That could not be found.',
  428: 'This could not be saved safely. Reload the page and try again.',
  429: 'Too many requests. Wait a moment and try again.',
};

/** The message to show a person for a failed retail call. */
export function retailMessage(problem: RetailProblem | undefined, status: number): string {
  const p = problem ?? {};
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
