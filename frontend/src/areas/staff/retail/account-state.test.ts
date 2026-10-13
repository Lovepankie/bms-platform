import { describe, expect, it } from 'vitest';
import { RetailError, type OpenSale } from '../../../api/retail';
import {
  MIN_REASON, accountFormOf, accountProblem, accountRequest, allocatableMinor, buildPaymentRequest, defaultStatementRange, exceededBy, isLimitRefusal, kindWords, limitWords,
  oldestFirst, payableSales, paymentProblem, reasonOf, reversalKeys, termsWords, type PaymentForm,
} from './account-state';

// Customer accounts, the pure rules (ADR-033, issue #196). Fabricated sales and amounts; the server decides all of it again.

const sale = (id: string, date: string, balance: number, over: Partial<OpenSale> = {}): OpenSale => ({
  sale_id: id, sale_no: `RS-${id}`, branch_id: 'b1', sale_date: date, total_minor: balance, paid_minor: 0, balance_minor: balance, ...over,
});
const open: OpenSale[] = [
  sale('a', '2026-03-05', 500),
  sale('old', '2025-11-01', 700, { historical: true }),
  sale('b', '2026-01-05', 1000),
  sale('c', '2026-02-05', 800),
];
const money = (n: number) => `UGX ${n}`;

const form = (over: Partial<PaymentForm> = {}): PaymentForm => ({ branchId: 'b1', amount: '1500', method: 'cash', paidOn: '2026-04-01', manual: false, rows: [], ...over });

describe('what a payment on account pays', () => {
  it('leaves imported sales out, as the server does', () => {
    expect(payableSales(open).map((s) => s.sale_id)).toEqual(['a', 'b', 'c']);
    expect(allocatableMinor(open)).toBe(2300);
  });

  it('pays the oldest sale first by sale date and puts the rest on the next', () => {
    const placed = oldestFirst(open, 1500, '2026-04-01');
    expect(placed.parts).toEqual([
      { saleId: 'b', saleNo: 'RS-b', amountMinor: 1000 },
      { saleId: 'c', saleNo: 'RS-c', amountMinor: 500 },
    ]);
    expect(placed.leftMinor).toBe(0);
  });

  it('skips a sale dated after the payment and reports what could not be placed', () => {
    const placed = oldestFirst(open, 2000, '2026-02-10');
    expect(placed.parts.map((p) => p.saleId)).toEqual(['b', 'c']);
    expect(placed.leftMinor).toBe(200);
  });

  it('refuses an amount above what can be paid and an empty or future payment', () => {
    expect(paymentProblem(form({ amount: '2301' }), open, '2026-04-01')).toMatch(/at most/i);
    expect(paymentProblem(form({ amount: '' }), open, '2026-04-01')).toMatch(/amount/i);
    expect(paymentProblem(form({ amount: '0' }), open, '2026-04-01')).toMatch(/amount/i);
    expect(paymentProblem(form({ paidOn: '2026-04-02' }), open, '2026-04-01')).toMatch(/future/i);
    expect(paymentProblem(form({ branchId: '' }), open, '2026-04-01')).toMatch(/branch/i);
    expect(paymentProblem(form(), [], '2026-04-01')).toMatch(/owes nothing/i);
    expect(paymentProblem(form(), [sale('old', '2025-11-01', 700, { historical: true })], '2026-04-01')).toMatch(/owes nothing/i);
    expect(paymentProblem(form(), open, '2026-04-01')).toBeNull();
  });

  it('checks a chosen split line by line and as a whole', () => {
    const rows = (...r: [string, string][]) => r.map(([saleId, text]) => ({ saleId, text }));
    const manual = (r: [string, string][], amount = '900') => form({ manual: true, amount, rows: rows(...r) });
    expect(paymentProblem(manual([['c', '600'], ['b', '300']]), open, '2026-04-01')).toBeNull();
    expect(paymentProblem(manual([['c', '600'], ['b', '200']]), open, '2026-04-01')).toMatch(/add up/i);
    expect(paymentProblem(manual([['c', '900']]), open, '2026-04-01')).toMatch(/owes only/i);
    expect(paymentProblem(manual([['old', '900']]), open, '2026-04-01')).toMatch(/check the amounts/i);
    expect(paymentProblem(manual([]), open, '2026-04-01')).toMatch(/at least one/i);
    expect(paymentProblem({ ...manual([['a', '400']], '400'), paidOn: '2026-03-01' }, open, '2026-04-01')).toMatch(/dated after/i);
  });

  it('sends no allocations for oldest first and the chosen ones otherwise', () => {
    expect(buildPaymentRequest(form())).toEqual({ branch_id: 'b1', amount_minor: 1500, method: 'cash', paid_on: '2026-04-01' });
    const manual = buildPaymentRequest(form({ manual: true, amount: '900', rows: [{ saleId: 'c', text: '600' }, { saleId: 'b', text: '300' }, { saleId: 'a', text: '' }] }));
    expect(manual.allocations).toEqual([{ sale_id: 'c', amount_minor: 600 }, { sale_id: 'b', amount_minor: 300 }]);
  });
});

describe('the limit and the terms', () => {
  it('says what the limit is and, for a caller who sees every branch, where the buyer stands', () => {
    expect(limitWords({}, money)).toBe('No credit limit.');
    expect(limitWords({ credit_limit_minor: 5000 }, money)).toBe('Credit limit UGX 5000.');
    expect(limitWords({ credit_limit_minor: 5000, exposure_minor: 3000, available_minor: 2000, over_limit: false }, money)).toContain('UGX 2000 still available');
    expect(limitWords({ credit_limit_minor: 5000, exposure_minor: 5600, available_minor: 0, over_limit: true }, money)).toContain('Over the limit by UGX 600');
  });

  it('words the terms, with none, a day count and the day of the sale', () => {
    expect(termsWords(undefined)).toBe('No payment terms.');
    expect(termsWords(0)).toContain('day of the sale');
    expect(termsWords(1)).toBe('Payment is due 1 day after the sale.');
    expect(termsWords(30)).toBe('Payment is due 30 days after the sale.');
  });

  it('reads the form back and refuses what the server would', () => {
    expect(accountFormOf({ credit_limit_minor: 5000, terms_days: 30 })).toEqual({ limit: '5000', terms: '30' });
    expect(accountFormOf({})).toEqual({ limit: '', terms: '' });
    expect(accountProblem({ limit: '', terms: '' })).toBeNull();
    expect(accountProblem({ limit: '5000', terms: '30' })).toBeNull();
    expect(accountProblem({ limit: 'abc', terms: '' })).toMatch(/limit/i);
    expect(accountProblem({ limit: '-5', terms: '' })).toMatch(/limit/i);
    expect(accountProblem({ limit: '', terms: '3.5' })).toMatch(/days/i);
    expect(accountProblem({ limit: '', terms: '366' })).toMatch(/365/);
    // Each field is a value or an explicit clear: the request never leaves one out, so an empty form cannot wipe by omission.
    expect(accountRequest({ limit: '5000', terms: '' })).toEqual({ credit_limit_minor: 5000, clear_terms_days: true });
    expect(accountRequest({ limit: '', terms: '0' })).toEqual({ clear_credit_limit: true, terms_days: 0 });
    expect(accountRequest({ limit: '', terms: '' })).toEqual({ clear_credit_limit: true, clear_terms_days: true });
    expect(accountRequest({ limit: '5000', terms: '30' })).toEqual({ credit_limit_minor: 5000, terms_days: 30 });
  });
});

describe('a credit sale refused for the limit', () => {
  const limit = new RetailError('over', 422, 'credit_limit_exceeded', { code: 'credit_limit_exceeded', exceeded_by_minor: 600 });
  const reason = new RetailError('reason', 422, 'override_reason_required', { code: 'override_reason_required', exceeded_by_minor: 600 });
  it('reads by how much from the problem, for the limit and for the missing reason alike', () => {
    expect(exceededBy(limit)).toBe(600);
    expect(exceededBy(reason)).toBe(600);
    expect(isLimitRefusal(limit)).toBe(true);
    expect(isLimitRefusal(reason)).toBe(false);
  });

  it('is nothing for any other failure', () => {
    expect(exceededBy(new RetailError('x', 422, 'price_below_cost'))).toBeNull();
    expect(exceededBy(new Error('x'))).toBeNull();
    expect(exceededBy(undefined)).toBeNull();
  });
});

describe('the statement', () => {
  it('names every line kind in words', () => {
    for (const k of ['sale', 'payment', 'account_payment', 'reversal', 'void']) expect(kindWords(k)).not.toBe(k);
    expect(kindWords(undefined)).toBe('');
  });

  it('starts 90 days back and ends on the business day, not the UTC day (22:30 UTC is already the next day in Kampala)', () => {
    expect(defaultStatementRange(new Date('2026-10-07T22:30:00Z'))).toEqual({ from: '2026-07-11', to: '2026-10-08' });
    expect(defaultStatementRange(new Date('2026-10-07T20:59:00Z'))).toEqual({ from: '2026-07-10', to: '2026-10-07' });
  });
});

describe('the reason for a reversal', () => {
  it('is the same five characters, once trimmed, as the server asks', () => {
    expect(MIN_REASON).toBe(5);
    expect(reasonOf('oops')).toBeNull();
    expect(reasonOf('  oops ')).toBeNull();
    expect(reasonOf('  Wrong buyer ')).toBe('Wrong buyer');
  });
});

describe('the Idempotency-Key of a reversal', () => {
  const counter = () => {
    let n = 0;
    return () => `key-${++n}`;
  };

  it('is one per receipt, so a failed reversal of one never lends its key to another', () => {
    const keys = reversalKeys(counter());
    const a = keys.keyFor('receipt-a', 'Wrong buyer');
    // The reversal of A failed; the cashier now reverses B: B must not send A's key.
    const b = keys.keyFor('receipt-b', 'Wrong buyer');
    expect(b).not.toBe(a);
  });

  it('repeats for a retry of the same receipt with the same reason, so a lost reply is replayed and not done twice', () => {
    const keys = reversalKeys(counter());
    const first = keys.keyFor('receipt-a', 'Wrong buyer');
    expect(keys.keyFor('receipt-a', 'Wrong buyer')).toBe(first);
    keys.keyFor('receipt-b', 'Wrong buyer');
    expect(keys.keyFor('receipt-a', 'Wrong buyer')).toBe(first);
  });

  it('changes when the reason is edited, because the request is no longer the same one', () => {
    const keys = reversalKeys(counter());
    const first = keys.keyFor('receipt-a', 'Wrong buyer');
    expect(keys.keyFor('receipt-a', 'Wrong buyer, entered twice')).not.toBe(first);
  });

  it('is forgotten once the reversal went through', () => {
    const keys = reversalKeys(counter());
    const first = keys.keyFor('receipt-a', 'Wrong buyer');
    keys.done('receipt-a');
    expect(keys.keyFor('receipt-a', 'Wrong buyer')).not.toBe(first);
  });
});
