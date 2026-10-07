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
    ['sale_voided', 409, /cancelled/i],
    ['version_conflict', 409, /changed by someone else/i],
    ['duplicate_product_code', 409, /code already exists/i],
    ['inactive_category', 422, /switched off/i],
    ['stocktake_committed', 409, /already been committed/i],
    ['token_expired', 401, /sign in/i],
  ])('maps %s', (code, status, text) => {
    const message = retailMessage({ code, detail: 'Product 00000000-0000-4000-8000-000000000001 has 2 in stock' }, status);
    expect(message).toMatch(text);
    // The server's detail names an internal id: the mapped message must not show it.
    expect(message).not.toMatch(/00000000-0000/);
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
    expect(retailMessage(undefined, 503)).toMatch(/server had a problem/i);
    expect(retailMessage({}, 418)).toBe('The request failed.');
  });
});
