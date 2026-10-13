import { describe, expect, it } from 'vitest';
import type { Sale, SaleLine, SaleReturn } from '../../../api/retail';
import {
  accountRefundAllowed, backOf, blankReturn, blankRow, buildReturnRequest, creditFor, defaultRefund, plannedLines, refundPlan, remainingMilli, returnProblem, rowProblem, settledWords, shareOf,
  type ReturnForm,
} from './returns-state';

// Customer returns, the pure rules (ADR-033, issue #201): the credit of a line is its share of what it was charged, the last
// piece takes the remainder so the pieces add up exactly, a pack converts exactly, and the refund follows the sale. Fabricated.

// Two cartons of twelve sold at 1,000 each: 24 pieces, 2,000 in all.
const carton: SaleLine = {
  id: 'l1', line_no: 1, product_id: 'p1', description: 'Test Item 01', qty: '24.000', unit_price_minor: 83, line_total_minor: 2000,
  sale_unit_id: 'u-carton', sale_unit: 'carton', sale_qty: '2.000', sale_unit_price_minor: 1000, unit_factor: '12.000',
};
const piece: SaleLine = { id: 'l2', line_no: 2, product_id: 'p2', description: 'Test Item 02', qty: '10.000', unit_price_minor: 100, line_total_minor: 1000 };

const sale = (over: Partial<Sale> = {}): Sale => ({
  id: 's1', sale_no: 'RS00000001', branch_id: 'b1', payment_method: 'cash', status: 'completed', total_minor: 3000, paid_minor: 3000, balance_minor: 0, lines: [carton, piece], ...over,
});

const form = (rows: ReturnForm['rows'], over: Partial<ReturnForm> = {}): ReturnForm => ({ ...blankReturn(), rows, ...over });
const row = (qty: string, over: Partial<ReturnType<typeof blankRow>> = {}) => ({ ...blankRow('b1'), qty, ...over });
const none = backOf([]);

const note = (lines: { id: string; qty: string; total: number }[], over: Partial<SaleReturn> = {}): SaleReturn => ({
  id: 'r1', status: 'completed', refund_minor: 0, account_credit_minor: 0,
  lines: lines.map((l) => ({ sale_line_id: l.id, qty: l.qty, line_total_minor: l.total })), ...over,
}) as SaleReturn;

describe('the credit of a line', () => {
  it('is its share of what was charged, rounded half up once', () => {
    expect(shareOf(2000, 12000, 24000)).toBe(1000);
    expect(shareOf(2000, 5000, 24000)).toBe(417);
    expect(shareOf(1, 1, 2)).toBe(1);
  });

  it('adds up to the line exactly: a carton, five pieces, and the rest', () => {
    const first = creditFor(carton, 12000, none);
    expect(first).toBe(1000);
    const afterFirst = backOf([note([{ id: 'l1', qty: '12.000', total: first }])]);
    const second = creditFor(carton, 5000, afterFirst);
    expect(second).toBe(417);
    const afterSecond = backOf([note([{ id: 'l1', qty: '12.000', total: first }]), note([{ id: 'l1', qty: '5.000', total: second }])]);
    expect(remainingMilli(carton, afterSecond)).toBe(7000);
    expect(first + second + creditFor(carton, 7000, afterSecond)).toBe(2000);
  });

  it('leaves out returns that were voided', () => {
    const voided = backOf([note([{ id: 'l1', qty: '12.000', total: 1000 }], { status: 'voided' })]);
    expect(remainingMilli(carton, voided)).toBe(24000);
  });
});

describe('a row of the form', () => {
  it('waits for nothing when it is empty, and refuses more than can still come back', () => {
    expect(rowProblem(piece, row(''), none)).toBeNull();
    expect(rowProblem(piece, row('10'), none)).toBeNull();
    expect(rowProblem(piece, row('10.001'), none)).toMatch(/at most 10/i);
    expect(rowProblem(piece, row('1.2345'), none)).toMatch(/three decimal/i);
    expect(rowProblem(piece, row('2'), backOf([note([{ id: 'l2', qty: '9.000', total: 900 }])]))).toMatch(/at most 1 can/i);
    expect(rowProblem(piece, row('1'), backOf([note([{ id: 'l2', qty: '10.000', total: 1000 }])]))).toMatch(/come back already/i);
  });

  it('converts a pack exactly into the item\'s own unit and refuses a quantity that does not', () => {
    expect(rowProblem(carton, row('2'), none)).toBeNull();
    expect(rowProblem(carton, row('2.001'), none)).toMatch(/at most 24/i);
    expect(rowProblem(carton, row('5', { entered: 'base' }), none)).toBeNull();
    const odd: SaleLine = { ...carton, unit_factor: '1.500', qty: '3.000', sale_qty: '2.000' };
    expect(rowProblem(odd, row('1.001'), none)).toMatch(/convert exactly/i);
  });

  it('asks where restocked goods go, and nothing for goods written off', () => {
    expect(rowProblem(piece, row('1', { restockBranchId: '' }), none)).toMatch(/where/i);
    expect(rowProblem(piece, row('1', { restockBranchId: '', disposition: 'write_off' }), none)).toBeNull();
  });
});

describe('where the credit goes', () => {
  const credit = { payment_method: 'credit', total_minor: 1000, paid_minor: 0 };

  it('takes an account refund off the open balance and asks how the rest is paid out', () => {
    expect(refundPlan(credit, 400, { refund: 'account', moneyMethod: '' }, none)).toMatchObject({ accountMinor: 400, moneyMinor: 0, needsMoneyMethod: false, problem: null });
    const part = { ...credit, paid_minor: 800 };
    expect(refundPlan(part, 500, { refund: 'account', moneyMethod: '' }, none)).toMatchObject({ accountMinor: 200, moneyMinor: 300, needsMoneyMethod: true, problem: null });
  });

  it('refuses an account refund where nothing is owed, for a cash sale and for an imported sale', () => {
    expect(refundPlan({ ...credit, paid_minor: 1000 }, 100, { refund: 'account', moneyMethod: '' }, none).problem).toMatch(/no balance/i);
    expect(refundPlan({ payment_method: 'cash', total_minor: 500, paid_minor: 500 }, 100, { refund: 'account', moneyMethod: '' }, none).problem).toMatch(/no balance/i);
    expect(refundPlan({ ...credit, historical: true }, 100, { refund: 'account', moneyMethod: '' }, none).problem).toMatch(/imported/i);
  });

  it('pays money back to a credit buyer only up to the money that came in', () => {
    const paid = { ...credit, paid_minor: 300 };
    expect(refundPlan(paid, 300, { refund: 'cash', moneyMethod: '' }, none).problem).toBeNull();
    expect(refundPlan(paid, 301, { refund: 'cash', moneyMethod: '' }, none).problem).toMatch(/only UGX 300/i);
    expect(refundPlan(credit, 1, { refund: 'cash', moneyMethod: '' }, none).problem).toMatch(/only UGX 0/i);
    const refunded = backOf([note([{ id: 'l2', qty: '3.000', total: 300 }], { refund_minor: 300 })]);
    expect(refundPlan(paid, 1, { refund: 'cash', moneyMethod: '' }, refunded).problem).not.toBeNull();
  });

  it('starts a credit sale that owes on the account and every other sale on cash', () => {
    expect(defaultRefund({ payment_method: 'credit', balance_minor: 100 })).toBe('account');
    expect(defaultRefund({ payment_method: 'credit', balance_minor: 0 })).toBe('cash');
    expect(defaultRefund({ payment_method: 'credit', balance_minor: 100, historical: true })).toBe('cash');
    expect(defaultRefund({ payment_method: 'cash', balance_minor: 0 })).toBe('cash');
  });
});

describe('the form as a whole', () => {
  it('waits for a quantity, then for the way the rest is paid out', () => {
    const s = sale({ payment_method: 'credit', total_minor: 3000, paid_minor: 2900, balance_minor: 100 });
    expect(returnProblem(s, form({}), none)).toMatch(/enter the quantity/i);
    const f = form({ l2: row('5') }, { refund: 'account' });
    expect(returnProblem(s, f, none)).toMatch(/choose how the other UGX 400/i);
    expect(returnProblem(s, { ...f, moneyMethod: 'cash' }, none)).toBeNull();
  });

  it('sends only the rows that are coming back, in the unit they were counted in', () => {
    const f = form({ l1: row('1', { restockBranchId: 'b2' }), l2: row('3', { reason: 'wrong_item', disposition: 'write_off', restockBranchId: '' }), l3: row('') }, { note: ' Test note ' });
    const request = buildReturnRequest(sale(), f, none);
    expect(request).toEqual({
      lines: [
        { sale_line_id: 'l1', qty: '1.000', unit_id: 'u-carton', reason: 'defective', disposition: 'restock', restock_branch_id: 'b2' },
        { sale_line_id: 'l2', qty: '3.000', reason: 'wrong_item', disposition: 'write_off' },
      ],
      refund_method: 'cash',
      note: 'Test note',
    });
    expect(plannedLines(sale(), f, none).map((p) => p.creditMinor)).toEqual([1000, 300]);
  });

  it('names no branch for goods put back at the sale\'s own branch and sends money_method only when it is needed', () => {
    const s = sale({ payment_method: 'credit', total_minor: 3000, paid_minor: 2900, balance_minor: 100 });
    const request = buildReturnRequest(s, { ...form({ l2: row('5') }, { refund: 'account' }), moneyMethod: 'mobile_money' }, none);
    expect(request.lines[0]).not.toHaveProperty('restock_branch_id');
    expect(request).toMatchObject({ refund_method: 'account', money_method: 'mobile_money' });
    const plain = buildReturnRequest(sale(), { ...form({ l2: row('1') }), moneyMethod: 'bank' }, none);
    expect(plain).not.toHaveProperty('money_method');
  });
});

describe('the account option and the till', () => {
  it('is offered only where the server allows it: a credit sale, not imported, still owing', () => {
    expect(accountRefundAllowed({ payment_method: 'credit', balance_minor: 100 })).toBe(true);
    expect(accountRefundAllowed({ payment_method: 'credit', balance_minor: 0 })).toBe(false);
    expect(accountRefundAllowed({ payment_method: 'credit', balance_minor: 100, historical: true })).toBe(false);
    expect(accountRefundAllowed({ payment_method: 'cash', balance_minor: 0 })).toBe(false);
  });

  it('sends the till money is paid out of only when something is paid out in money and a till was chosen', () => {
    const s = sale();
    const f = { ...form({ l2: row('2') }), refundBranchId: 'b2' };
    expect(buildReturnRequest(s, f, none)).toMatchObject({ refund_method: 'cash', refund_branch_id: 'b2' });
    expect(buildReturnRequest(s, { ...f, refundBranchId: '' }, none)).not.toHaveProperty('refund_branch_id');
    const owing = sale({ payment_method: 'credit', total_minor: 3000, paid_minor: 0, balance_minor: 3000 });
    expect(buildReturnRequest(owing, { ...form({ l2: row('2') }, { refund: 'account' }), refundBranchId: 'b2' }, none)).not.toHaveProperty('refund_branch_id');
  });
});

describe('words', () => {
  it('says how a credit note was settled', () => {
    expect(settledWords({ account_credit_minor: 200, refund_minor: 300, refund_method: 'mobile_money' })).toBe('UGX 200 taken off what the buyer owes and UGX 300 paid back by mobile money');
    expect(settledWords({ account_credit_minor: 0, refund_minor: 0 })).toBe('Nothing to pay');
  });
});
