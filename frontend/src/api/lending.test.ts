import { describe, expect, it } from 'vitest';
import { lendingMessage } from './lending';

describe('lending problem details in plain words', () => {
  it.each([
    ['invalid_status_transition', 409, /not in a state/i],
    ['value_date_in_future', 422, /today or earlier/i],
    ['before_disbursement', 422, /before the loan was disbursed/i],
    ['before_last_repayment', 422, /latest repayment/i],
    ['payment_method_unmapped', 422, /not set up/i],
    ['approval_already_pending', 409, /already waiting for a checker/i],
    ['already_reversed', 409, /already been reversed/i],
    ['module_not_enabled', 404, /lending is not switched on/i],
  ])('maps %s', (code, status, text) => {
    const message = lendingMessage({ code, detail: 'Loan 00000000-0000-4000-8000-000000000001 refused' }, status);
    expect(message).toMatch(text);
    expect(message).not.toMatch(/00000000-0000/);
  });

  it('shares the retail messages for common codes', () => {
    expect(lendingMessage({ code: 'idempotency_key_reused' }, 422)).toMatch(/different entry/i);
    expect(lendingMessage({ code: 'permission_denied' }, 403)).toMatch(/do not have permission/i);
  });

  it('joins the field problems of a validation failure', () => {
    const problem = { code: 'validation_failed', errors: [{ field: 'amount_minor', message: 'Must be above zero.' }, { field: 'value_date', message: 'Is required.' }] };
    expect(lendingMessage(problem, 422)).toBe('Must be above zero. Is required.');
  });

  it('shows the server message for a code it does not know', () => {
    expect(lendingMessage({ code: 'some_new_rule', detail: 'The server says no.' }, 422)).toBe('The server says no.');
  });
});
