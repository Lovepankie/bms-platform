import { describe, expect, it } from 'vitest';
import { retailMessage } from './retail-errors';

describe('retail problem details in plain words', () => {
  it.each([
    ['insufficient_stock', 422, /not enough stock/i],
    ['price_below_cost', 422, /not above what the item cost/i],
    ['idempotency_key_reused', 422, /different entry/i],
    ['idempotency_in_progress', 409, /still being saved/i],
    ['idempotency_key_missing', 422, /reload/i],
    ['permission_denied', 403, /do not have permission/i],
    ['module_not_enabled', 404, /not switched on/i],
    ['branch_required', 422, /choose one branch/i],
    ['sale_voided', 409, /already been voided/i],
    ['sale_not_payable', 422, /not voided takes payments/i],
    ['sale_historical', 422, /with your accountant/i],
    ['sale_has_payments', 422, /payments cannot be voided/i],
    ['sale_has_returns', 422, /void the returns first/i],
    ['payment_exceeds_balance', 422, /more than the buyer still owes/i],
    ['version_conflict', 409, /changed by someone else/i],
    ['duplicate_product_code', 409, /code already exists/i],
    ['inactive_category', 422, /switched off/i],
    ['stocktake_committed', 409, /already been committed/i],
    ['stocktake_discarded', 409, /was cancelled/i],
    ['token_expired', 401, /sign in/i],
  ])('maps %s', (code, status, text) => {
    const message = retailMessage({ code, detail: 'Product 00000000-0000-4000-8000-000000000001 has 2 in stock' }, status);
    expect(message).toMatch(text);
    // The server's detail names an internal id: the mapped message must not show it.
    expect(message).not.toMatch(/00000000-0000/);
  });

  // JUSTIFICATION-A3: a table of the new purchasing codes, one row each; an extension of the test above, nothing weakened.
  it.each([
    ['capability_not_enabled', 404, /not switched on/i],
    ['order_not_draft', 409, /sent already/i],
    ['order_not_sent', 409, /not been sent/i],
    ['order_closed', 409, /closed/i],
    ['receipt_exceeds_order', 422, /more is being received than was ordered/i],
    ['quantity_not_representable', 422, /convert exactly/i],
    ['receipt_billed', 409, /void the invoice first/i],
    ['receipt_voided', 409, /voided already/i],
    ['receipt_stock_moved', 409, /sold, used or moved/i],
    ['bill_exceeds_received', 422, /more than was received/i],
    ['bill_total_mismatch', 422, /does not match its lines/i],
    ['variance_reason_required', 422, /say why it differs/i],
    ['duplicate_supplier_bill', 409, /already has an invoice/i],
    ['bill_paid', 409, /reverse the payment first/i],
    ['bill_voided', 409, /voided already/i],
    ['payment_exceeds_open', 422, /more than this supplier is owed/i],
    ['allocation_exceeds_open', 422, /still owed on that invoice/i],
    ['allocation_total_mismatch', 422, /add up to the payment/i],
    ['payment_reversed', 409, /reversed already/i],
    ['retail_chart_missing', 422, /not set up/i],
    ['amount_out_of_range', 422, /too large or too small/i],
    // Landed costs and supplier credit notes (issue #199).
    ['landed_total_mismatch', 422, /not the sum of the charges/i],
    ['allocation_basis_zero', 422, /adds up to nothing/i],
    ['line_returned', 422, /sent back to the supplier/i],
    ['landed_cost_voided', 409, /voided already/i],
    ['landed_cost_stock_moved', 409, /moved on/i],
    ['landed_cost_credited', 409, /void the credit note first/i],
    ['bill_is_landed_cost', 409, /void the landed cost instead/i],
    ['bill_credited', 409, /void the credit note first/i],
    ['receipt_costed', 409, /already covers this delivery/i],
    ['receipt_not_costed', 422, /held until its landed cost is in/i],
    ['not_held', 409, /nothing to release/i],
    ['credit_total_mismatch', 422, /not the sum of the lines/i],
    ['credit_exceeds_billed', 422, /more is sent back than the invoice billed/i],
    ['credit_exceeds_billed_value', 422, /more than the invoice charged/i],
    ['credit_exceeds_open', 422, /still has to pay/i],
    ['credit_exceeds_on_hand', 422, /still in the branch/i],
    ['duplicate_supplier_credit', 409, /already has a credit note/i],
    ['credit_voided', 409, /voided already/i],
    ['credit_stock_moved', 409, /moved on since/i],
    ['insufficient_stock', 422, /not enough stock/i],
  ])('maps the purchasing code %s', (code, status, text) => {
    expect(retailMessage({ code, detail: 'Bill 00000000-0000-4000-8000-000000000001 failed' }, status)).toMatch(text);
  });

  it('shows the server message for a code it does not know', () => {
    expect(retailMessage({ code: 'some_new_rule', detail: 'The server says no.' }, 422)).toBe('The server says no.');
    expect(retailMessage({ code: 'some_new_rule', title: 'Business rule failed' }, 422)).toBe('Business rule failed');
  });

  it('joins the field messages of a validation failure', () => {
    const problem = { code: 'validation_failed', detail: 'One or more fields are invalid.', errors: [{ field: 'qty', message: 'Must be above zero.' }, { field: 'x', message: 'Too long.' }] };
    expect(retailMessage(problem, 422)).toBe('Must be above zero. Too long.');
    expect(retailMessage({ code: 'validation_failed', detail: 'One or more fields are invalid.' }, 422)).toBe('One or more fields are invalid.');
  });

  it('falls back on the status when there is no body', () => {
    expect(retailMessage(undefined, 403)).toMatch(/do not have permission/i);
    expect(retailMessage(undefined, 401)).toMatch(/sign in/i);
    expect(retailMessage(undefined, 500)).toMatch(/server had a problem/i);
    // The proxy with no API behind it during a deploy (issue #496).
    expect(retailMessage(undefined, 503)).toBe('The app is updating, so this may have been saved. Check before you try again.');
    expect(retailMessage({}, 418)).toBe('The request failed.');
  });
});
