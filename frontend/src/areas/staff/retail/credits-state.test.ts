import { describe, expect, it } from 'vitest';
import type { SupplierBill, SupplierCredit } from '../../../api/retail';
import {
  blankCredit, buildCreditRequest, canCredit, creditLineProblem, creditLinesFrom, creditProblem, creditTotal, creditedByLine, leftMinor, leftQtyMilli, lineCredit, usedLines,
  type CreditForm,
} from './credits-state';

// The rules of the supplier credit note form (issue #199): what an invoice line still allows, the credit of a line and of the note,
// the typed total and the limit of what the invoice has left to pay. Fabricated suppliers, invoices and amounts.

const bill = (over: Partial<SupplierBill> = {}): SupplierBill => ({
  id: 'b1', bill_no: 'SB-000001', status: 'posted', kind: 'goods', supplier_id: 's1', total_minor: 99000, open_minor: 99000,
  lines: [
    { id: 'bl1', receipt_no: 'GR-000001', code: 'P003', description: 'Test bulb', unit: 'piece', unit_qty: '10.000', qty: '10.000', unit_price_minor: 3500, line_total_minor: 35000 },
    { id: 'bl2', receipt_no: 'GR-000001', code: 'P004', description: 'Test cable', unit: 'roll', unit_qty: '2.000', qty: '2.000', unit_price_minor: 32000, line_total_minor: 64000 },
  ],
  ...over,
});

const earlier = (over: Partial<SupplierCredit> = {}): SupplierCredit => ({
  id: 'c1', status: 'posted', kind: 'goods', lines: [{ bill_line_id: 'bl1', unit_qty: '4.000', qty: '4.000', line_total_minor: 14000 }], ...over,
});

function filled(over: Partial<CreditForm> = {}): CreditForm {
  const lines = creditLinesFrom(bill(), []).map((l, i) => (i === 0 ? { ...l, qty: '2' } : l));
  return { ...blankCredit('2026-10-12', 'b1'), supplierRef: 'CN-1', reason: 'Damaged on arrival', total: '7000', lines, ...over };
}

describe('what an invoice line still allows', () => {
  it('starts from the billed quantity and price, with nothing used', () => {
    const [bulb] = creditLinesFrom(bill(), []);
    expect(bulb).toMatchObject({ billLineId: 'bl1', billedQty: '10.000', creditedMilli: 0, unitPriceMinor: 3500, lineTotalMinor: 35000, qty: '', unitCredit: '3500', amount: '' });
    expect(leftQtyMilli(bulb!)).toBe(10000);
    expect(leftMinor(bulb!)).toBe(35000);
  });

  it('takes off what credit notes that stand have already taken, and ignores a voided one', () => {
    const taken = creditedByLine([earlier(), earlier({ id: 'c2', status: 'voided' }), earlier({ id: 'c3', kind: 'price', lines: [{ bill_line_id: 'bl1', line_total_minor: 1000 }] })]);
    expect(taken['bl1']).toEqual({ qtyMilli: 4000, minor: 15000 });
    const [bulb] = creditLinesFrom(bill(), [earlier()]);
    expect(leftQtyMilli(bulb!)).toBe(6000);
    expect(leftMinor(bulb!)).toBe(21000);
  });

  it('is offered only for a goods invoice that stands and still has something to pay', () => {
    expect(canCredit(bill())).toBe(true);
    expect(canCredit(bill({ kind: 'landed_cost' }))).toBe(false);
    expect(canCredit(bill({ status: 'voided' }))).toBe(false);
    expect(canCredit(bill({ open_minor: 0 }))).toBe(false);
  });
});

describe('the credit of a line and of the note', () => {
  it('is the quantity sent back times the credit for one, rounded half up once', () => {
    const [bulb] = creditLinesFrom(bill(), []);
    expect(lineCredit({ ...bulb!, qty: '2' }, 'goods')).toBe(7000);
    expect(lineCredit({ ...bulb!, qty: '2.5', unitCredit: '1001' }, 'goods')).toBe(2503);
    expect(lineCredit({ ...bulb!, qty: 'x' }, 'goods')).toBe(0);
    expect(lineCredit({ ...bulb!, amount: '1,200' }, 'price')).toBe(1200);
  });

  it('adds only the lines used', () => {
    const f = filled();
    expect(usedLines(f)).toHaveLength(1);
    expect(creditTotal(f)).toBe(7000);
    expect(creditTotal({ ...f, lines: f.lines.map((l) => ({ ...l, qty: '1' })) })).toBe(3500 + 32000);
  });
});

describe('the limits of a line', () => {
  it('refuses more than the invoice billed less earlier credit notes, and says the figures', () => {
    const [bulb] = creditLinesFrom(bill(), [earlier()]);
    expect(creditLineProblem({ ...bulb!, qty: '6' }, 'goods')).toBeNull();
    expect(creditLineProblem({ ...bulb!, qty: '6.001' }, 'goods')).toMatch(/at most 6 piece.*billed 10.*took 4/i);
    expect(creditLineProblem({ ...bulb!, qty: '0' }, 'goods')).toMatch(/how many/i);
  });

  it('refuses a credit for one that is not an amount, or is more than the invoice charged', () => {
    const [bulb] = creditLinesFrom(bill(), []);
    expect(creditLineProblem({ ...bulb!, qty: '1', unitCredit: '' }, 'goods')).toMatch(/credit for one/i);
    expect(creditLineProblem({ ...bulb!, qty: '1', unitCredit: '3501' }, 'goods')).toMatch(/cannot be more than the invoice charged, UGX 3,500/);
    expect(creditLineProblem({ ...bulb!, qty: '1', unitCredit: '3000' }, 'goods')).toBeNull();
  });

  it('limits a price credit to what is left of the line', () => {
    const [bulb] = creditLinesFrom(bill(), [earlier()]);
    expect(creditLineProblem({ ...bulb!, amount: '21000' }, 'price')).toBeNull();
    expect(creditLineProblem({ ...bulb!, amount: '21001' }, 'price')).toMatch(/at most UGX 21,000/i);
    expect(creditLineProblem({ ...bulb!, amount: '0' }, 'price')).toMatch(/above zero/i);
    expect(creditLineProblem({ ...bulb!, amount: 'x' }, 'price')).toMatch(/above zero/i);
  });
});

describe('the form', () => {
  it('can be saved when everything is filled in and the typed total is the sum of the lines', () => {
    expect(creditProblem(filled(), 99000)).toBeNull();
  });

  it('asks for the invoice, the credit note number, the date, a reason of five characters and a line, in that order', () => {
    expect(creditProblem(filled({ billId: '' }), 99000)).toMatch(/choose the supplier invoice/i);
    expect(creditProblem(filled({ supplierRef: '' }), 99000)).toMatch(/supplier's credit note/i);
    expect(creditProblem(filled({ creditDate: '' }), 99000)).toMatch(/date/i);
    expect(creditProblem(filled({ reason: 'abc' }), 99000)).toMatch(/at least 5 characters/i);
    expect(creditProblem(filled({ lines: filled().lines.map((l) => ({ ...l, qty: '' })) }), 99000)).toMatch(/at least one item/i);
    expect(creditProblem(filled({ kind: 'price' }), 99000)).toMatch(/at least one line/i);
  });

  it('stops a typed total that is not the sum of the lines, and says both', () => {
    expect(creditProblem(filled({ total: '6000' }), 99000)).toMatch(/UGX 6,000 but the lines come to UGX 7,000/);
    expect(creditProblem(filled({ total: '' }), 99000)).toMatch(/credit total/i);
  });

  it('stops a credit above what the invoice still has to pay', () => {
    expect(creditProblem(filled(), 7000)).toBeNull();
    expect(creditProblem(filled(), 6999)).toMatch(/only UGX 6,999 left to pay/);
  });

  it('flags a line over its limit', () => {
    const f = filled();
    expect(creditProblem({ ...f, lines: f.lines.map((l, i) => (i === 0 ? { ...l, qty: '11' } : l)) }, 99000)).toMatch(/marked in red/i);
  });
});

describe('the request', () => {
  it('names the invoice, the kind, the credit note number, the reason and the total, and a quantity for each goods line', () => {
    expect(buildCreditRequest(filled())).toEqual({
      bill_id: 'b1', kind: 'goods', supplier_ref: 'CN-1', credit_date: '2026-10-12', total_minor: 7000, reason: 'Damaged on arrival',
      lines: [{ bill_line_id: 'bl1', qty: '2.000' }],
    });
  });

  it('names the credit for one only when it differs from the billed price', () => {
    const f = filled({ total: '6000' });
    const lines = f.lines.map((l, i) => (i === 0 ? { ...l, unitCredit: '3000' } : l));
    expect(buildCreditRequest({ ...f, lines }).lines).toEqual([{ bill_line_id: 'bl1', qty: '2.000', unit_credit_minor: 3000 }]);
  });

  it('sends an amount for a price credit, and a note only when typed', () => {
    const f = filled({ kind: 'price', total: '1200', note: ' Wrong price ' });
    const lines = f.lines.map((l, i) => (i === 0 ? { ...l, amount: '1200' } : l));
    expect(buildCreditRequest({ ...f, lines })).toMatchObject({ kind: 'price', note: 'Wrong price', total_minor: 1200, lines: [{ bill_line_id: 'bl1', amount_minor: 1200 }] });
    expect(buildCreditRequest(filled())).not.toHaveProperty('note');
  });
});
