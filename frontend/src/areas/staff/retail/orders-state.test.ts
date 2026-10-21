import { describe, expect, it } from 'vitest';
import type { Product } from '../../../api/retail';
import type { Order } from '../../../api/retail-orders';
import {
  addItem, blankDeliver, blankOrder, keptWords, payChoicesFor, buildDeliverRequest, buildItemsRequest, buildOrderRequest, deliverFigures, deliverProblem, feeOf, itemsDraft,
  itemsMayChange, itemsProblem, lookupKey, nextSteps, orderProblem, sourceLabel, stepOf, withItemsQty,
} from './orders-state';
import { canWriteOff, writeOffAmount, writeOffWarning } from './sale-actions-state';
import { newLine } from './sale-state';

// The rules of the customer order screens (issue #385). Names, phones and amounts are fabricated.

const romper: Product = { id: 'p1', code: 'ROMPER', description: 'Test Romper', unit: 'piece', sell_minor: 10000, active: true };
const TODAY = '2026-10-21';
const order = { items_total_minor: 20000, delivery_fee_minor: 5000 };

describe('taking an order', () => {
  it('needs a name and an item, and a fee in whole shillings', () => {
    const d = blankOrder('b1', TODAY);
    expect(orderProblem(d, TODAY)).toMatch(/name/i);
    expect(orderProblem({ ...d, name: 'Test Customer 01' }, TODAY)).toMatch(/at least one item/i);
    const ok = { ...d, name: 'Test Customer 01', lines: [newLine(romper)] };
    expect(orderProblem(ok, TODAY)).toBeNull();
    expect(orderProblem({ ...ok, fee: '2,500' }, TODAY)).toBeNull();
    expect(orderProblem({ ...ok, fee: '25.5' }, TODAY)).toMatch(/whole number/i);
    expect(orderProblem({ ...ok, deliveryDate: '2026-10-20' }, TODAY)).toMatch(/before today/i);
  });

  it('sends the source, the customer, the place and the fee, and a price only when it was changed', () => {
    const d = { ...blankOrder('b1', TODAY), source: 'instagram' as const, name: ' Test Customer 01 ', phone: '0700 000 011', place: 'Test Place', fee: '3000', lines: [newLine(romper), { ...newLine({ ...romper, id: 'p2' }), price: '9000' }] };
    const r = buildOrderRequest(d);
    expect(r).toMatchObject({ branch_id: 'b1', source: 'instagram', customer_name: 'Test Customer 01', customer_phone: '0700 000 011', delivery_place: 'Test Place', delivery_date: TODAY, delivery_fee_minor: 3000 });
    expect(r.lines[0]).toEqual({ product_id: 'p1', qty: '1.000' });
    expect(r.lines[1]?.unit_price_minor).toBe(9000);
    expect(buildOrderRequest({ ...d, place: '' })).not.toHaveProperty('delivery_place');
  });

  it('looks a customer up only with nine digits or more', () => {
    expect(lookupKey('0700 00')).toBeNull();
    expect(lookupKey('+256 700 000 011')).toBe('256700000011');
    expect(feeOf('')).toBe(0);
  });
});

describe('the steps', () => {
  it('offers the next steps in the order a shop does them', () => {
    expect(nextSteps({ status: 'ordered' })).toEqual(['pack', 'send', 'deliver', 'cancel']);
    expect(nextSteps({ status: 'packed' })).toEqual(['send', 'deliver', 'cancel']);
    expect(nextSteps({ status: 'out_for_delivery' })).toEqual(['deliver', 'cancel']);
    expect(nextSteps({ status: 'delivered' })).toEqual([]);
    expect(stepOf('out_for_delivery').label).toBe('Out for delivery');
    expect(sourceLabel('walk_in')).toBe('Walk-in');
  });
});

describe('delivered and paid', () => {
  it('takes everything at the door when paid in full', () => {
    const f = { ...blankDeliver(), choice: 'mobile_money' as const };
    expect(deliverFigures(order, f)).toEqual({ now: 25000, later: 0 });
    expect(deliverProblem(order, f)).toBeNull();
    expect(buildDeliverRequest(order, f)).toEqual({ payment_method: 'mobile_money' });
  });

  it('takes at least the fee on a part payment and leaves the rest on credit', () => {
    const f = { ...blankDeliver(), choice: 'part' as const, paidNow: '3000' };
    expect(deliverProblem(order, f)).toMatch(/at least the fee/i);
    const ok = { ...f, paidNow: '8000', paidNowMethod: 'mobile_money' as const, dueDate: '2026-10-28' };
    expect(deliverFigures(order, ok)).toEqual({ now: 8000, later: 17000 });
    expect(deliverProblem(order, ok)).toBeNull();
    expect(buildDeliverRequest(order, ok)).toEqual({ payment_method: 'credit', paid_now_minor: 8000, paid_now_method: 'mobile_money', due_date: '2026-10-28' });
    expect(deliverProblem(order, { ...f, paidNow: '25000' })).toMatch(/everything/i);
  });

  it('puts all the items on credit and takes the fee now', () => {
    const f = { ...blankDeliver(), choice: 'credit' as const };
    expect(deliverFigures(order, f)).toEqual({ now: 5000, later: 20000 });
    expect(buildDeliverRequest(order, f)).toEqual({ payment_method: 'credit', paid_now_minor: 5000, paid_now_method: 'cash' });
    expect(buildDeliverRequest({ delivery_fee_minor: 0 }, f)).toEqual({ payment_method: 'credit' });
  });
});

describe('fees on the account and stock kept aside', () => {
  it('offers the account only to a credit buyer with a fee, and sends the fee on the account', () => {
    expect(payChoicesFor({ delivery_fee_minor: 5000 }).map((c) => c.value)).not.toContain('account');
    expect(payChoicesFor({ customer_id: 'c1', delivery_fee_minor: 5000 }).map((c) => c.value)).toContain('account');
    const f = { ...blankDeliver(), choice: 'account' as const };
    expect(deliverFigures(order, f)).toEqual({ now: 0, later: 25000 });
    expect(buildDeliverRequest(order, f)).toEqual({ payment_method: 'credit', fee_on_account: true });
    expect(deliverProblem(order, { ...f, paidNow: '21000' })).toMatch(/more than the items/i);
  });

  it('says what is free and what is kept for orders', () => {
    expect(keptWords('10.000', undefined)).toBeNull();
    expect(keptWords('10.000', '2.000')).toBe('8 free, 2 kept for orders');
    expect(keptWords('1.000', '2.000')).toBe('0 free, 2 kept for orders');
  });
});

describe('writing off a sale with a fee on the account', () => {
  it('counts the fee in what is cleared, so a fee alone can be written off', () => {
    const sale = { payment_method: 'credit', status: 'completed', balance_minor: 0, delivery_fee_owed_minor: 2000 } as const;
    expect(writeOffAmount(sale)).toBe(2000);
    expect(canWriteOff(sale)).toBe(true);
    expect(writeOffWarning(12000, 2000)).toContain('of which UGX 2,000 is the delivery fee');
  });
});

describe('changing the items of an order (issue #393)', () => {
  const taken: Pick<Order, 'status' | 'lines'> = {
    status: 'ordered',
    lines: [{ line_no: 1, product_id: 'p1', code: 'ROMPER', description: 'Test Romper', qty: '2', unit: 'piece', unit_price_minor: 9000, line_total_minor: 18000, base_qty: '2' }],
  };

  it('is offered while the order is ordered or packed, not once it is out', () => {
    expect(itemsMayChange({ status: 'ordered' })).toBe(true);
    expect(itemsMayChange({ status: 'packed' })).toBe(true);
    expect(itemsMayChange({ status: 'out_for_delivery' })).toBe(false);
    expect(itemsMayChange({ status: 'delivered' })).toBe(false);
    expect(itemsMayChange({ status: 'cancelled' })).toBe(false);
  });

  it('starts from the items on the order at the price agreed, once every product is read', () => {
    expect(itemsDraft(taken, {})).toBeNull();
    const lines = itemsDraft(taken, { p1: romper });
    expect(lines).toEqual([{ product: romper, qty: '2', price: '9000' }]);
  });

  it('keeps the price agreed on a line left as it was; a new quantity or a new item is priced again unless a price was typed', () => {
    const lines = itemsDraft(taken, { p1: romper }) ?? [];
    expect(buildItemsRequest(taken, lines, '').lines[0]).toEqual({ product_id: 'p1', qty: '2.000', unit_price_minor: 9000 });
    const r = buildItemsRequest(taken, [{ ...lines[0]!, qty: '3' }, newLine({ ...romper, id: 'p2' })], ' Bigger size ');
    expect(r.lines[0]).toEqual({ product_id: 'p1', qty: '3.000' });
    expect(r.lines[1]).toEqual({ product_id: 'p2', qty: '1.000' });
    expect(r.note).toBe('Bigger size');
    expect(buildItemsRequest(taken, lines, '')).not.toHaveProperty('note');
    const typed = buildItemsRequest(taken, [{ ...lines[0]!, qty: '3', price: '8500', reason: 'Test regular customer' }], '');
    expect(typed.lines[0]).toEqual({ product_id: 'p1', qty: '3.000', unit_price_minor: 8500, price_override_reason: 'Test regular customer' });
  });

  it('shows today\'s price when the quantity of an item on the order changes, and the price agreed when it is back', () => {
    const line = (itemsDraft(taken, { p1: romper }) ?? [])[0]!;
    const bigger = withItemsQty(taken, line, '3');
    expect(bigger.price).toBe('10000');
    expect(withItemsQty(taken, bigger, '2').price).toBe('9000');
    expect(withItemsQty(taken, { ...line, price: '8000' }, '3').price).toBe('8000');
  });

  it('adds an item from the picker once per item and unit, so a second unit of the same item is its own line', () => {
    const carton = { ...romper, packs: [{ unit_id: 'u-carton', unit: 'carton', factor: '12', active: true }] } as Product;
    const inCartons = [{ ...newLine(carton), unitId: 'u-carton' }];
    expect(addItem(inCartons, carton)).toHaveLength(2);
    expect(addItem(addItem(inCartons, carton), carton)).toHaveLength(2);
    expect(addItem([newLine(romper)], romper)).toHaveLength(1);
  });

  it('needs at least one item and a valid quantity on each', () => {
    expect(itemsProblem([])).toMatch(/cancel the order/i);
    expect(itemsProblem([{ ...newLine(romper), qty: 'two' }])).toMatch(/quantity/i);
    expect(itemsProblem([newLine(romper)])).toBeNull();
  });
});
