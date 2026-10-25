import { describe, expect, it } from 'vitest';
import { businessToday, type SupplierOpenItem, type UnbilledReceiptLine } from '../../../api/retail';
import {
  AGEING_COLUMNS, allocatedMinor, billLineProblem, billLinesFrom, billLinesTotal, billLineTotal, billProblem, blankBill, blankPayment, buildBillRequest,
  buildPaymentRequest, invoiceSentence, itemAmountProblem, itemKey, lateWords, matchKey, matchReady, methodWords, paymentAmount, paymentProblem, remainingMinor, ticked, varianceWords,
  type BillForm, type PaymentForm,
} from './payables-state';

// The rules of the supplier invoice form, the supplier payment form and the payables report (ADR-033, issue #198).
// Fabricated suppliers, invoices and amounts.

const unbilled = (over: Partial<UnbilledReceiptLine> = {}): UnbilledReceiptLine => ({
  receipt_id: 'r1', receipt_line_id: 'rl1', receipt_no: 'GR-0001', order_no: 'PO-0001', code: 'P003', description: 'LED bulb 9W screw', unit: 'piece', unbilled_qty: '10.000', unit_cost_minor: 3500, unbilled_minor: 35000, ...over,
});

const form = (over: Partial<BillForm> = {}): BillForm => {
  const base = { ...blankBill('2026-10-12'), supplierId: 's1', supplierRef: 'INV-77', dueDate: '2026-11-11', total: '35000', lines: billLinesFrom([unbilled(), unbilled({ receipt_line_id: 'rl2', unbilled_qty: '4.500', unit_cost_minor: 1001, code: 'P004' })]) };
  base.lines = base.lines.map((l, i) => (i === 0 ? { ...l, ticked: true } : l));
  return { ...base, ...over };
};

describe('lines to bill', () => {
  it('start unticked, with the unbilled quantity and the cost the goods were received at', () => {
    const [first, second] = billLinesFrom([unbilled(), unbilled({ receipt_line_id: 'rl2', unbilled_qty: '4.500', unit_cost_minor: 1001 })]);
    expect(first).toMatchObject({ receiptLineId: 'rl1', ticked: false, qty: '10', price: '3500', receiptNo: 'GR-0001', orderNo: 'PO-0001' });
    expect(second).toMatchObject({ qty: '4.5', price: '1001' });
  });

  it('totals the ticked lines only, each rounded half up once', () => {
    const f = form();
    expect(billLineTotal(f.lines[0]!)).toBe(35000);
    expect(billLineTotal(f.lines[1]!)).toBe(4505);
    expect(billLinesTotal(f)).toBe(35000);
    expect(billLinesTotal({ ...f, lines: f.lines.map((l) => ({ ...l, ticked: true })) })).toBe(39505);
    expect(ticked(f)).toHaveLength(1);
    expect(billLineTotal({ ...f.lines[0]!, qty: 'x' })).toBe(0);
  });

  it('refuses more than is unbilled, and a quantity or price that is not a number', () => {
    const l = form().lines[0]!;
    expect(billLineProblem(l)).toBeNull();
    expect(billLineProblem({ ...l, qty: '10.001' })).toMatch(/at most 10 piece/i);
    expect(billLineProblem({ ...l, qty: '0' })).toMatch(/how many/i);
    expect(billLineProblem({ ...l, price: '' })).toMatch(/price/i);
  });
});

describe('the invoice request', () => {
  it('names the receipt lines, the quantity and the supplier\'s price for each, and the printed total', () => {
    expect(buildBillRequest(form())).toEqual({
      supplier_id: 's1', supplier_ref: 'INV-77', bill_date: '2026-10-12', due_date: '2026-11-11', total_minor: 35000,
      lines: [{ receipt_line_id: 'rl1', qty: '10.000', unit_price_minor: 3500 }],
    });
  });

  it('adds a reason and a note only when typed, and trims them', () => {
    const request = buildBillRequest(form({ varianceReason: '  Price rose  ', note: ' Late ' }));
    expect(request).toMatchObject({ variance_reason: 'Price rose', note: 'Late' });
    expect(buildBillRequest(form())).not.toHaveProperty('variance_reason');
  });

  it('uses the sum of the lines for a match before the printed total is typed, but never for saving', () => {
    const f = form({ total: '' });
    expect(buildBillRequest(f, true).total_minor).toBe(35000);
    expect(buildBillRequest(f).total_minor).toBe(0);
  });

  it('is ready to match once a supplier, an invoice number, both dates and a valid ticked line are there', () => {
    expect(matchReady(form())).toBe(true);
    expect(matchReady(form({ supplierRef: ' ' }))).toBe(false);
    expect(matchReady(form({ dueDate: '' }))).toBe(false);
    expect(matchReady(form({ lines: form().lines.map((l) => ({ ...l, ticked: false })) }))).toBe(false);
    expect(matchReady(form({ lines: form().lines.map((l, i) => (i === 0 ? { ...l, qty: '99' } : l)) }))).toBe(false);
  });

  it('keeps one key while only the reason or the note changes, and a new one when a price or quantity does', () => {
    const f = form();
    expect(matchKey({ ...f, varianceReason: 'because', note: 'x' })).toBe(matchKey(f));
    expect(matchKey({ ...f, lines: f.lines.map((l, i) => (i === 0 ? { ...l, price: '3600' } : l)) })).not.toBe(matchKey(f));
  });
});

describe('when the invoice can be saved', () => {
  it('needs everything the match needs, and the printed total', () => {
    expect(billProblem(form(), undefined)).toBeNull();
    expect(billProblem(form({ supplierId: '' }), undefined)).toMatch(/supplier/i);
    expect(billProblem(form({ supplierRef: '' }), undefined)).toMatch(/invoice number/i);
    expect(billProblem(form({ lines: form().lines.map((l) => ({ ...l, ticked: false })) }), undefined)).toMatch(/tick/i);
    expect(billProblem(form({ total: '' }), undefined)).toMatch(/total/i);
  });

  it('stops at a total that does not match the lines, as the server found it', () => {
    expect(billProblem(form(), { total_mismatch: true })).toMatch(/not the sum of the lines/i);
  });

  it('asks why the invoice differs, in five characters, when the server says a reason is needed', () => {
    const match = { reason_required: true, variance_minor: 700 };
    expect(billProblem(form(), match)).toMatch(/say why/i);
    expect(billProblem(form({ varianceReason: 'abcd' }), match)).toMatch(/say why/i);
    expect(billProblem(form({ varianceReason: 'Price rose' }), match)).toBeNull();
    expect(billProblem(form(), { reason_required: false })).toBeNull();
  });

  it('says the difference in words', () => {
    expect(varianceWords(0)).toMatch(/same as/i);
    expect(varianceWords(700)).toBe('UGX 700 more than the goods were received at');
    expect(varianceWords(-1200)).toBe('UGX 1,200 less than the goods were received at');
    expect(invoiceSentence(0)).toBe('The invoice is the same as what the goods were received at.');
    expect(invoiceSentence(700)).toBe('The invoice is UGX 700 more than what the goods were received at.');
    expect(invoiceSentence(-1200)).toBe('The invoice is UGX 1,200 less than what the goods were received at.');
  });
});

const item = (over: Partial<SupplierOpenItem> = {}): SupplierOpenItem => ({ id: 'b1', kind: 'bill', number: 'SB-0001', supplier_ref: 'INV-77', open_minor: 30000, total_minor: 35000, paid_minor: 5000, due_date: '2026-10-01', days_past_due: 11, ...over });
const items = [item(), item({ id: 'r9', kind: 'restock', number: 'P-000009', supplier_ref: undefined, open_minor: 12000, days_past_due: 0 })];

const pay = (over: Partial<PaymentForm> = {}): PaymentForm => ({ ...blankPayment('s1', 'b1'), amount: '20000', ...over });

describe('the payment', () => {
  it('starts as cash, oldest first, with nothing allocated', () => {
    expect(blankPayment('s1', 'b1')).toMatchObject({ method: 'cash', allocation: 'oldest_first', amounts: {} });
    expect(methodWords('mobile_money')).toBe('Mobile money');
  });

  it('keys an invoice and a credit restock apart', () => {
    expect(itemKey(items[0]!)).toBe('bill:b1');
    expect(itemKey(items[1]!)).toBe('restock:r9');
    expect(itemKey(item({ id: 'x', kind: undefined }))).toBe('bill:x');
  });

  it('reads the amount as a positive whole amount', () => {
    expect(paymentAmount(pay())).toBe(20000);
    for (const bad of ['', '0', '-5', 'abc', '1.5']) expect(paymentAmount(pay({ amount: bad })), bad).toBeNull();
  });

  it('pays oldest first when the amount is not more than is owed', () => {
    expect(paymentProblem(pay(), items, 42000)).toBeNull();
    expect(paymentProblem(pay({ amount: '42001' }), items, 42000)).toMatch(/owed UGX 42,000/);
    expect(paymentProblem(pay({ amount: '' }), items, 42000)).toMatch(/amount/i);
    expect(paymentProblem(pay({ supplierId: '' }), items, 42000)).toMatch(/supplier/i);
    expect(paymentProblem(pay({ branchId: '' }), items, 42000)).toMatch(/branch/i);
  });

  it('shows what is left to give to invoices when choosing, and needs it to be nothing', () => {
    const f = pay({ allocation: 'manual', amounts: { 'bill:b1': '15000' } });
    expect(allocatedMinor(f.amounts)).toBe(15000);
    expect(remainingMinor(f)).toBe(5000);
    expect(paymentProblem(f, items, 42000)).toMatch(/UGX 5,000 of the payment is not given/);
    const over = pay({ allocation: 'manual', amounts: { 'bill:b1': '15000', 'restock:r9': '6000' } });
    expect(remainingMinor(over)).toBe(-1000);
    expect(paymentProblem(over, items, 42000)).toMatch(/UGX 1,000 more is given/);
    const exact = pay({ allocation: 'manual', amounts: { 'bill:b1': '8000', 'restock:r9': '12000' } });
    expect(remainingMinor(exact)).toBe(0);
    expect(paymentProblem(exact, items, 42000)).toBeNull();
  });

  it('refuses more than is owed on one item, and counts a bad amount as none', () => {
    expect(itemAmountProblem(items[0]!, '30001')).toMatch(/at most UGX 30,000/i);
    expect(itemAmountProblem(items[0]!, '30000')).toBeNull();
    expect(itemAmountProblem(items[0]!, '')).toBeNull();
    expect(itemAmountProblem(items[0]!, 'x')).toMatch(/whole amount/i);
    expect(allocatedMinor({ a: 'x', b: '500' })).toBe(500);
    const bad = pay({ allocation: 'manual', amounts: { 'bill:b1': '30001' } });
    expect(paymentProblem(bad, items, 42000)).toMatch(/marked in red/i);
    expect(paymentProblem(pay({ allocation: 'manual' }), undefined, undefined)).toMatch(/wait/i);
  });

  it('builds an oldest first request with no allocations', () => {
    expect(buildPaymentRequest(pay({ reference: ' REF-1 ', note: '' }), items)).toEqual({
      supplier_id: 's1', branch_id: 'b1', method: 'cash', amount_minor: 20000, paid_on: businessToday(), allocation: 'oldest_first', reference: 'REF-1',
    });
  });

  it('builds a manual request naming an invoice by bill_id and a credit restock by purchase_id, skipping empty boxes', () => {
    const f = pay({ allocation: 'manual', method: 'bank', amounts: { 'bill:b1': '8000', 'restock:r9': '12000', 'bill:zz': '' } });
    expect(buildPaymentRequest(f, items)).toMatchObject({
      method: 'bank', allocation: 'manual', allocations: [{ bill_id: 'b1', amount_minor: 8000 }, { purchase_id: 'r9', amount_minor: 12000 }],
    });
    expect(buildPaymentRequest(f, items).allocations?.[0]).not.toHaveProperty('purchase_id');
  });
});

describe('words for the lists and the report', () => {
  it('says how late an item is', () => {
    expect([lateWords(-3), lateWords(0), lateWords(1), lateWords(45), lateWords(undefined)]).toEqual(['Not yet due', 'Not yet due', '1 day late', '45 days late', 'Not yet due']);
  });

  it('lists the five ageing columns in order from not due to over 90 days', () => {
    expect(AGEING_COLUMNS.map((c) => c.key)).toEqual(['not_due_minor', 'days1_to30_minor', 'days31_to60_minor', 'days61_to90_minor', 'over90_minor']);
  });
});
