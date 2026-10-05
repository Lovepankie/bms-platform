import { describe, expect, it } from 'vitest';
import type { Product } from '../../../api/retail';
import { newIdempotencyKey } from './idempotency';
import { buildSaleRequest, draftProblem, draftTotal, newLine, type Draft } from './sale-state';

const bulb: Product = { id: 'p1', code: 'P003', description: 'LED bulb 9W screw', unit: 'piece', sellMinor: 6000, active: true };
const cable: Product = { id: 'p2', code: 'P001', description: '2.5mm twin cable 100m roll', unit: 'roll', sellMinor: 230000, active: true };

const draft = (over: Partial<Draft> = {}): Draft => ({
  branchId: 'b1', method: 'cash', customerId: '', newBuyerName: '', newBuyerContact: '', dueDate: '', lines: [newLine(bulb)], ...over,
});

describe('sale form', () => {
  it('defaults the unit price to the sell price and totals the lines', () => {
    const lines = [newLine(bulb), { ...newLine(cable), qty: '2.5' }];
    expect(lines[0]?.price).toBe('6000');
    expect(draftTotal(lines)).toBe(6000 + 575000);
  });

  it('ignores invalid lines in the total and blocks saving', () => {
    const bad = { ...newLine(bulb), qty: 'x' };
    expect(draftTotal([bad])).toBe(0);
    expect(draftProblem(draft({ lines: [bad] }))).toMatch(/quantity/i);
    expect(draftProblem(draft({ lines: [] }))).toMatch(/at least one/i);
    expect(draftProblem(draft())).toBeNull();
  });

  it('sends a unit price only when it differs from the sell price', () => {
    const same = buildSaleRequest(draft());
    expect(same.lines[0]).toEqual({ productId: 'p1', qty: '1.000' });
    const changed = buildSaleRequest(draft({ lines: [{ ...newLine(bulb), price: '5500' }] }));
    expect(changed.lines[0]?.unitPriceMinor).toBe(5500);
  });

  it('needs a buyer and a due date for credit, and sends them', () => {
    expect(draftProblem(draft({ method: 'credit' }))).toMatch(/buyer/i);
    expect(draftProblem(draft({ method: 'credit', newBuyerName: 'Test Buyer 02' }))).toMatch(/date/i);
    const ok = draft({ method: 'credit', newBuyerName: ' Test Buyer 02 ', dueDate: '2026-11-01' });
    expect(draftProblem(ok)).toBeNull();
    expect(buildSaleRequest(ok)).toMatchObject({ paymentMethod: 'credit', buyerName: 'Test Buyer 02', dueDate: '2026-11-01' });
    expect(buildSaleRequest(draft({ method: 'credit', customerId: 'c1', dueDate: '2026-11-01' }))).toMatchObject({ customerId: 'c1' });
  });

  it('sends no buyer or due date for a cash sale', () => {
    const req = buildSaleRequest(draft({ customerId: 'c1', dueDate: '2026-11-01' }));
    expect(req).not.toHaveProperty('customerId');
    expect(req).not.toHaveProperty('dueDate');
  });

  it('makes a different idempotency key for each form open', () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
    expect(newIdempotencyKey()).toMatch(/^[0-9a-f-]{36}$/);
  });
});
