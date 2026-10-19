import { describe, expect, it } from 'vitest';
import type { Sale } from '../../../api/retail';
import { RetailError } from '../../../api/retail';
import {
  MIN_REASON, keyReused, payFailureWords, saleMovedOn, blankPay, buildPayRequest, fullPay, importedAndOwing, owedMinor, paidWords, payProblem, takesPayment, typedAmount, voidBlocked, voidReasonOf, type PayForm,
} from './sale-actions-state';

// The pure rules behind the record-payment and void forms (issues #326 and #327). Fabricated figures.

const credit: Sale = { id: 's1', sale_no: 'S-000001', branch_id: 'b1', sale_date: '2026-10-10', payment_method: 'credit', status: 'completed', total_minor: 12000, paid_minor: 2000, balance_minor: 10000 };
const today = '2026-10-19';

describe('who can be paid', () => {
  it('takes a payment on a live credit sale that still owes', () => {
    expect(takesPayment(credit)).toBe(true);
    expect(takesPayment({ ...credit, balance_minor: 0 })).toBe(false);
    expect(takesPayment({ ...credit, payment_method: 'cash' })).toBe(false);
    expect(takesPayment({ ...credit, status: 'voided' })).toBe(false);
  });

  it('keeps the action off an imported sale and says why', () => {
    const imported = { ...credit, historical: true };
    expect(takesPayment(imported)).toBe(false);
    expect(importedAndOwing(imported)).toBe(true);
    expect(importedAndOwing(credit)).toBe(false);
    expect(importedAndOwing({ ...imported, balance_minor: 0 })).toBe(false);
  });

  it('never reads a negative balance as owed', () => {
    expect(owedMinor({ balance_minor: -5 })).toBe(0);
    expect(owedMinor({})).toBe(0);
  });
});

describe('the payment form', () => {
  const form = (over: Partial<PayForm> = {}): PayForm => ({ ...blankPay(credit, today), ...over });

  it('starts with the whole balance, in cash, today', () => {
    expect(blankPay(credit, today)).toEqual({ amount: '10000', method: 'cash', paidOn: today });
    expect(typedAmount(1234567)).toBe('1234567');
  });

  it('accepts the balance, a part of it, and any of the three ways to pay', () => {
    expect(payProblem(form(), credit, today)).toBeNull();
    expect(payProblem(form({ amount: '2,500', method: 'mobile_money' }), credit, today)).toBeNull();
    expect(payProblem(form({ method: 'bank' }), credit, today)).toBeNull();
  });

  it('refuses no amount, zero, words, and more than is owed', () => {
    expect(payProblem(form({ amount: '' }), credit, today)).toMatch(/enter the amount/i);
    expect(payProblem(form({ amount: '0' }), credit, today)).toMatch(/enter the amount/i);
    expect(payProblem(form({ amount: 'abc' }), credit, today)).toMatch(/enter the amount/i);
    expect(payProblem(form({ amount: '10001' }), credit, today)).toMatch(/owes only UGX 10,000/);
  });

  it('keeps the date between the sale and today', () => {
    expect(payProblem(form({ paidOn: '' }), credit, today)).toMatch(/choose the date/i);
    expect(payProblem(form({ paidOn: '2026-10-20' }), credit, today)).toMatch(/not be in the future/i);
    expect(payProblem(form({ paidOn: '2026-10-09' }), credit, today)).toMatch(/not be before the sale/i);
    expect(payProblem(form({ paidOn: '2026-10-10' }), credit, today)).toBeNull();
  });

  it('builds the request the API takes', () => {
    expect(buildPayRequest(form({ amount: '2,500', method: 'bank', paidOn: '2026-10-15' }))).toEqual({ amount_minor: 2500, method: 'bank', paid_on: '2026-10-15' });
    expect(() => buildPayRequest(form({ amount: 'x' }))).toThrow();
  });

  it('marks as fully paid with the method and date already chosen', () => {
    expect(fullPay(form({ amount: '5', method: 'bank' }), credit)).toEqual({ amount: '10000', method: 'bank', paidOn: today });
  });

  it('says what happened in words', () => {
    expect(paidWords(10000, 0)).toBe('Recorded UGX 10,000. This sale is now paid in full.');
    expect(paidWords(2500, 7500)).toBe('Recorded UGX 2,500. Still owes UGX 7,500.');
  });
});

describe('voiding', () => {
  it('can void a completed sale, and says why not for the others', () => {
    expect(voidBlocked({ ...credit, paid_minor: 0 })).toBeNull();
    expect(voidBlocked({ status: 'completed', payment_method: 'cash', paid_minor: 5000 })).toBeNull();
    expect(voidBlocked(credit)).toMatch(/had a payment or a return/);
    expect(voidBlocked({ ...credit, paid_minor: 0, historical: true })).toMatch(/imported from the old records/);
    expect(voidBlocked({ ...credit, status: 'voided' })).toMatch(/already voided/);
  });

  it('needs a reason of five characters', () => {
    expect(MIN_REASON).toBe(5);
    expect(voidReasonOf('oops')).toBeNull();
    expect(voidReasonOf('   ')).toBeNull();
    expect(voidReasonOf('  Rang up twice ')).toBe('Rang up twice');
  });
});

describe('refusals that need more than the plain words', () => {
  const refusal = (code: string) => new RetailError('x', 422, code);

  it('knows when the sale moved on and when the key was reused', () => {
    for (const code of ['payment_exceeds_balance', 'sale_not_payable', 'sale_voided']) expect(saleMovedOn(refusal(code))).toBe(true);
    expect(saleMovedOn(refusal('permission_denied'))).toBe(false);
    expect(saleMovedOn(new Error('network'))).toBe(false);
    expect(keyReused(refusal('idempotency_key_reused'))).toBe(true);
  });

  it('words them so the person is never left repeating the same refusal', () => {
    expect(String(payFailureWords(refusal('payment_exceeds_balance'), true))).toMatch(/balance has changed; check the new amount/);
    expect(payFailureWords(refusal('payment_exceeds_balance'), false)).toBeInstanceOf(RetailError);
    expect(String(payFailureWords(refusal('sale_not_payable'), false))).toMatch(/can no longer take a payment/);
    expect(String(payFailureWords(refusal('idempotency_key_reused'), false))).toMatch(/form has been reset/);
    const other = new Error('boom');
    expect(payFailureWords(other, true)).toBe(other);
  });
});
