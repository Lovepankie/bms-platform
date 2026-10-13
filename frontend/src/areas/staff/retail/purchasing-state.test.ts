import { describe, expect, it } from 'vitest';
import type { Product, PurchaseOrder, PurchaseOrderLine } from '../../../api/retail';
import {
  blankOrder, blankReceipt, blindLineFor, bpToPercentText, buildOrderRequest, buildReceiptRequest, canCancelOrder, canEditOrder, canReceiveOrder, canSendOrder,
  defaultReceiveQty, formFromOrder, orderLineFor, orderLineProblem, orderLineTotal, orderProblem, orderStatusWords, orderTotal, overOrdered, percentTextToBp,
  receiptProblem, receiveFormFor, sendOutcomeWords, receiveLineProblem, receiveTotal, stillToComeMilli, withOrderUnit, withReceiveUnit, type OrderForm, type ReceiveForm,
} from './purchasing-state';

// The rules of the purchase order form, the goods received form and the tolerance settings (ADR-033, issue #198).
// Fabricated items, quantities and amounts.

const carton = { id: 'k1', unit_id: 'u-carton', unit: 'carton', factor: '12.000', active: true };
const product = (over: Partial<Product> = {}): Product => ({ id: 'p1', code: 'P003', description: 'LED bulb 9W screw', unit: 'piece', sell_minor: 6000, cost_minor: 3500, ...over });
const line = (over: Partial<PurchaseOrderLine> = {}): PurchaseOrderLine => ({
  id: 'ol1', line_no: 1, product_id: 'p1', code: 'P003', description: 'LED bulb 9W screw', unit: 'piece', qty: '10.000', received_qty: '0.000', unit_qty: '10.000', unit_cost_minor: 3500, cost_minor: 3500, line_total_minor: 35000, ...over,
});
const order = (over: Partial<PurchaseOrder> = {}): PurchaseOrder => ({
  id: 'o1', order_no: 'PO-0001', status: 'sent', supplier_id: 's1', branch_id: 'b1', lines: [line()], ...over,
});

describe('order lines', () => {
  it('totals quantity times the cost of one unit, rounded half up once', () => {
    const l = { ...orderLineFor(product(), false), qty: '10', cost: '3500' };
    expect(orderLineTotal(l)).toBe(35000);
    // 2.5 x 1,001 = 2,502.5, which rounds up.
    expect(orderLineTotal({ ...l, qty: '2.5', cost: '1001' })).toBe(2503);
    expect(orderLineTotal({ ...l, qty: '0.001', cost: '499' })).toBe(0);
    expect(orderLineTotal({ ...l, qty: '0.001', cost: '500' })).toBe(1);
  });

  it('adds the lines of an order, and counts a line that is not valid yet as nothing', () => {
    const a = { ...orderLineFor(product(), false), qty: '4', cost: '3500' };
    const b = { ...orderLineFor(product({ id: 'p2' }), false), qty: 'x', cost: '100' };
    expect(orderTotal([a, b, { ...a, qty: '1', cost: '250' }])).toBe(14250);
  });

  it('suggests the cost the session can see, and none when it cannot', () => {
    expect(orderLineFor(product(), false).cost).toBe('3500');
    expect(orderLineFor(product({ cost_minor: undefined }), false).cost).toBe('');
  });

  it('offers packs only while the units capability is on, and a pack costs its factor times a piece', () => {
    expect(orderLineFor(product({ packs: [carton] }), false).packs).toEqual([]);
    const l = orderLineFor(product({ packs: [carton] }), true);
    expect(l.packs).toHaveLength(1);
    const inCartons = withOrderUnit(l, 'u-carton');
    expect(inCartons).toMatchObject({ unitId: 'u-carton', unitLabel: 'carton', cost: '42000' });
    expect(withOrderUnit(inCartons, '')).toMatchObject({ unitId: undefined, unitLabel: 'piece', cost: '3500' });
  });

  it('says what is wrong with a line in plain words', () => {
    const l = orderLineFor(product(), false);
    expect(orderLineProblem(l)).toMatch(/how many/i);
    expect(orderLineProblem({ ...l, qty: '1.2345' })).toMatch(/how many/i);
    expect(orderLineProblem({ ...l, qty: '2', cost: 'abc' })).toMatch(/costs/i);
    expect(orderLineProblem({ ...l, qty: '2' })).toBeNull();
  });
});

describe('the order form', () => {
  const filled = (): OrderForm => ({ ...blankOrder('b1'), supplierId: 's1', lines: [{ ...orderLineFor(product(), false), qty: '10', cost: '3500' }] });

  it('needs a supplier and at least one valid item', () => {
    expect(orderProblem(blankOrder())).toMatch(/supplier/i);
    expect(orderProblem({ ...blankOrder(), supplierId: 's1' })).toMatch(/at least one item/i);
    expect(orderProblem({ ...filled(), lines: [{ ...filled().lines[0]!, qty: '' }] })).toMatch(/check the quantity/i);
    expect(orderProblem(filled())).toBeNull();
  });

  it('builds the request with wire quantities, leaves out what is blank and names the pack only when one is chosen', () => {
    expect(buildOrderRequest(filled())).toEqual({ supplier_id: 's1', branch_id: 'b1', lines: [{ product_id: 'p1', qty: '10.000', cost_minor: 3500 }] });
    const full = { ...filled(), expectedOn: '2026-11-01', note: ' Deliver early ', lines: [{ ...filled().lines[0]!, unitId: 'u-carton', qty: '2.5' }] };
    expect(buildOrderRequest(full)).toMatchObject({ expected_on: '2026-11-01', note: 'Deliver early', lines: [{ qty: '2.500', unit_id: 'u-carton' }] });
  });

  it('is filled back from a draft order in the unit each line was entered in', () => {
    const o = order({ status: 'draft', expected_on: '2026-11-01', note: 'Soon', lines: [line({ unit: 'carton', unit_id: 'u-carton', unit_factor: '12.000', qty: '24.000', unit_qty: '2.000', unit_cost_minor: 42000 }), line({ id: 'ol2', unit_id: 'u-piece', unit_factor: '1.000' })] });
    const form = formFromOrder(o);
    expect(form).toMatchObject({ supplierId: 's1', branchId: 'b1', expectedOn: '2026-11-01', note: 'Soon' });
    expect(form.lines[0]).toMatchObject({ unitId: 'u-carton', qty: '2', cost: '42000', unitLabel: 'carton' });
    // The base unit is sent as no unit at all.
    expect(form.lines[1]?.unitId).toBeUndefined();
    expect(buildOrderRequest(form).lines[0]).toEqual({ product_id: 'p1', qty: '2.000', cost_minor: 42000, unit_id: 'u-carton' });
  });
});

describe('what may be done to an order', () => {
  it('follows its status', () => {
    const at = (status: PurchaseOrder['status']) => order({ status });
    expect([canEditOrder(at('draft')), canSendOrder(at('draft')), canReceiveOrder(at('draft')), canCancelOrder(at('draft'))]).toEqual([true, true, false, true]);
    expect([canEditOrder(at('sent')), canSendOrder(at('sent')), canReceiveOrder(at('sent')), canCancelOrder(at('sent'))]).toEqual([false, false, true, true]);
    expect([canReceiveOrder(at('partly_received')), canCancelOrder(at('partly_received'))]).toEqual([true, true]);
    for (const done of ['received', 'cancelled'] as const) expect([canEditOrder(at(done)), canReceiveOrder(at(done)), canCancelOrder(at(done))]).toEqual([false, false, false]);
  });

  it('says the status in plain words', () => {
    expect(orderStatusWords('partly_received')).toBe('Partly received');
    expect(orderStatusWords('cancelled')).toBe('Cancelled');
    expect(orderStatusWords('something_new')).toBe('something_new');
  });
});

describe('receiving against an order', () => {
  it('fills each line with what is still to come, in the unit it was ordered in', () => {
    expect(stillToComeMilli(line({ received_qty: '4.000' }))).toBe(6000);
    expect(defaultReceiveQty(line({ received_qty: '4.000' }))).toBe('6.000');
    // 3 cartons of 12 ordered, 12 received: 2 cartons left.
    expect(defaultReceiveQty(line({ unit_id: 'u-carton', unit_factor: '12.000', qty: '36.000', received_qty: '12.000' }))).toBe('2.000');
    // 13 pieces left do not make whole thousandths of a carton: the person types it.
    expect(defaultReceiveQty(line({ unit_id: 'u-carton', unit_factor: '7.000', qty: '20.000', received_qty: '0.000' }))).toBe('');
    expect(defaultReceiveQty(line({ received_qty: '10.000' }))).toBe('');
  });

  it('has a line only for what is still to come, with the order cost and the supplier', () => {
    const o = order({ status: 'partly_received', lines: [line({ received_qty: '4.000', unit_cost_minor: 3400 }), line({ id: 'ol2', received_qty: '10.000' })] });
    const form = receiveFormFor(o, 'b1');
    expect(form.supplierId).toBe('s1');
    expect(form.lines).toHaveLength(1);
    expect(form.lines[0]).toMatchObject({ orderLineId: 'ol1', qty: '6', cost: '3400', orderedText: '10', stillToCome: '6' });
  });

  it('builds a request against the order lines, with only the lines that arrive and a new price only when typed', () => {
    const form: ReceiveForm = { ...receiveFormFor(order(), 'b1'), receivedOn: '2026-10-12', deliveryRef: ' DN-77 ' };
    form.lines = [{ ...form.lines[0]!, qty: '6', sell: '' }, { ...form.lines[0]!, key: 'x', orderLineId: 'ol2', qty: '' }];
    expect(buildReceiptRequest(form, 'o1')).toEqual({
      order_id: 'o1', branch_id: 'b1', received_on: '2026-10-12', delivery_ref: 'DN-77', lines: [{ order_line_id: 'ol1', qty: '6.000', cost_minor: 3500 }],
    });
    form.lines = [{ ...form.lines[0]!, sell: '4200' }];
    expect(buildReceiptRequest(form, 'o1').lines[0]).toMatchObject({ sell_minor: 4200 });
  });

  it('notices more than is still to come, in the unit of the line', () => {
    const o = order({ lines: [line({ unit_id: 'u-carton', unit_factor: '12.000', qty: '36.000', received_qty: '12.000' })] });
    const form = receiveFormFor(o, 'b1');
    const at = (qty: string) => overOrdered({ ...form.lines[0]!, qty }, o);
    expect([at('2'), at('2.5'), at('3')]).toEqual([false, true, true]);
    expect(overOrdered({ ...form.lines[0]!, qty: '' }, o)).toBe(false);
  });
});

describe('a blind receipt', () => {
  it('needs the supplier, a place for the goods, and a quantity and cost for each line that arrives', () => {
    const blank = blankReceipt('b1');
    expect(receiptProblem(blank, true)).toMatch(/supplier/i);
    const withSupplier = { ...blank, supplierId: 's1' };
    expect(receiptProblem(withSupplier, true)).toMatch(/at least one item/i);
    expect(receiptProblem({ ...withSupplier, branchId: '' }, true)).toMatch(/where the goods go/i);
    const l = blindLineFor(product(), false, 'k');
    expect(receiptProblem({ ...withSupplier, lines: [{ ...l, qty: '5' }] }, true)).toBeNull();
    expect(receiptProblem({ ...withSupplier, lines: [{ ...l, qty: '5', cost: 'x' }] }, true)).toMatch(/marked in red/i);
    expect(receiptProblem({ ...withSupplier, lines: [{ ...l, qty: '5' }] }, false)).toBeNull();
  });

  it('names the item and the pack, not an order line', () => {
    const l = withReceiveUnit({ ...blindLineFor(product({ packs: [carton] }), true, 'k'), qty: '3', cost: '42000', sell: '4500' }, 'u-carton');
    expect(l.unitLabel).toBe('carton');
    const request = buildReceiptRequest({ ...blankReceipt('b1'), supplierId: 's1', lines: [l] });
    expect(request).toMatchObject({ supplier_id: 's1', lines: [{ product_id: 'p1', unit_id: 'u-carton', qty: '3.000', cost_minor: 42000, sell_minor: 4500 }] });
    expect(request.lines[0]).not.toHaveProperty('order_line_id');
    expect(request).not.toHaveProperty('order_id');
  });

  it('refuses a pack quantity that does not convert exactly, and totals what arrives', () => {
    const l = withReceiveUnit({ ...blindLineFor(product({ packs: [{ ...carton, factor: '0.003' }] }), true, 'k'), qty: '0.5', cost: '10' }, 'u-carton');
    expect(receiveLineProblem(l)).toMatch(/convert exactly/i);
    const ok = { ...blindLineFor(product(), false, 'k'), qty: '4', cost: '3500' };
    expect(receiveLineProblem(ok)).toBeNull();
    expect(receiveLineProblem({ ...ok, qty: '' })).toBeNull();
    expect(receiveTotal([ok, { ...ok, qty: '' }, { ...ok, qty: '1.5', cost: '1001' }])).toBe(14000 + 1502);
  });
});

describe('tolerances in percent', () => {
  it('shows basis points as a percentage with two places', () => {
    expect([0, 25, 100, 150, 10000].map(bpToPercentText)).toEqual(['0.00', '0.25', '1.00', '1.50', '100.00']);
    expect(bpToPercentText(undefined)).toBe('0.00');
  });

  it('reads a percentage as basis points, and refuses what is not one', () => {
    expect([ '0', '1', '1.5', '1.25', '0.05', ' 2 ', '100'].map(percentTextToBp)).toEqual([0, 100, 150, 125, 5, 200, 10000]);
    for (const bad of ['', '-1', '1.234', 'abc', '100.01', '101', '1,5']) expect(percentTextToBp(bad), bad).toBeNull();
  });

  it('round trips', () => {
    for (const bp of [0, 1, 99, 100, 250, 9999]) expect(percentTextToBp(bpToPercentText(bp))).toBe(bp);
  });
});

describe('sending an order', () => {
  it('says sent when it went, and that it waits for a second person on a 202', () => {
    expect(sendOutcomeWords({ executed: true, order: order({ order_no: 'PO-0007' }) })).toBe('Sent PO-0007. You can now receive goods against it.');
    const waiting = sendOutcomeWords({ executed: false, approval_id: 'a1' });
    expect(waiting).toMatch(/second person/);
    expect(waiting).toMatch(/stays a draft/);
    expect(waiting).toMatch(/Approvals/);
  });
});
