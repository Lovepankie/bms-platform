// Plain-words messages for the retail API's problem details (chapter 7 section 7.7). The server's
// `code` is the stable identifier; a code not listed here (for example one added after this file)
// shows the server's own message, so a new refusal is never hidden behind a vague text.

export interface RetailProblem {
  code?: string;
  detail?: string;
  title?: string;
  errors?: { field?: string; message?: string }[];
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
  token_expired: 'Your session has ended. Sign in again.',
  unauthenticated: 'Your session has ended. Sign in again.',
};

const BY_STATUS: Record<number, string> = {
  401: 'Your session has ended. Sign in again.',
  403: BY_CODE['permission_denied'] ?? '',
  404: 'That could not be found.',
  429: 'Too many requests. Wait a moment and try again.',
};

/** The message to show a person for a failed retail call. */
export function retailMessage(problem: RetailProblem | undefined, status: number): string {
  const p = problem ?? {};
  const known = p.code ? BY_CODE[p.code] : undefined;
  if (known) return known;
  if (p.code === 'validation_failed') {
    const fields = (p.errors ?? []).map((e) => e.message).filter((m): m is string => !!m);
    if (fields.length > 0) return fields.join(' ');
  }
  // An unknown code: the server's message is the best text there is.
  if (p.detail) return p.detail;
  if (p.title) return p.title;
  return BY_STATUS[status] ?? (status >= 500 ? 'The server had a problem. Try again in a moment.' : 'The request failed.');
}
