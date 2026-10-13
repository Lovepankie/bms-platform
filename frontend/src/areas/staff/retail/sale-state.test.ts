import { describe, expect, it } from 'vitest';
import type { Product } from '../../../api/retail';
import { dropDraft, loadDraft, newIdempotencyKey, saveDraft, type DraftStore } from './idempotency';
import { buildSaleRequest, draftProblem, draftTotal, lineHint, newLine, type Draft } from './sale-state';

const bulb: Product = { id: 'p1', code: 'P003', description: 'LED bulb 9W screw', unit: 'piece', sell_minor: 6000, active: true };
const cable: Product = { id: 'p2', code: 'P001', description: '2.5mm twin cable 100m roll', unit: 'roll', sell_minor: 230000, active: true };

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
    expect(same.lines[0]).toEqual({ product_id: 'p1', qty: '1.000' });
    const changed = buildSaleRequest(draft({ lines: [{ ...newLine(bulb), price: '5500' }] }));
    expect(changed.lines[0]?.unit_price_minor).toBe(5500);
  });

  it('needs a buyer and a due date for credit, and sends them', () => {
    expect(draftProblem(draft({ method: 'credit' }))).toMatch(/buyer/i);
    expect(draftProblem(draft({ method: 'credit', newBuyerName: 'Test Buyer 02' }))).toMatch(/date/i);
    const ok = draft({ method: 'credit', newBuyerName: ' Test Buyer 02 ', dueDate: '2026-11-01' });
    expect(draftProblem(ok)).toBeNull();
    expect(buildSaleRequest(ok)).toMatchObject({ payment_method: 'credit', buyer_name: 'Test Buyer 02', due_date: '2026-11-01' });
    expect(buildSaleRequest(draft({ method: 'credit', customerId: 'c1', dueDate: '2026-11-01' }))).toMatchObject({ customer_id: 'c1' });
  });

  it('sends no buyer or due date for a cash sale', () => {
    const req = buildSaleRequest(draft({ customerId: 'c1', dueDate: '2026-11-01' }));
    expect(req).not.toHaveProperty('customer_id');
    expect(req).not.toHaveProperty('due_date');
  });

  it('refuses a quantity above the branch stock as an early hint', () => {
    const stocked = { ...bulb, qty: '3.000' };
    expect(lineHint({ ...newLine(stocked), qty: '3' })).toBeNull();
    expect(lineHint({ ...newLine(stocked), qty: '3.5' })).toMatch(/only 3 piece in stock/i);
    expect(draftProblem(draft({ lines: [{ ...newLine(stocked), qty: '4' }] }))).toMatch(/marked above/i);
    expect(lineHint({ ...newLine({ ...bulb, qty: '-2.000' }), qty: '1' })).toMatch(/only 0 piece/i);
    expect(lineHint(newLine(bulb))).toBeNull();
  });

  it('refuses a price not above cost only when the cost is known (retail.profit.read)', () => {
    const known = { ...bulb, cost_minor: 3500 };
    expect(lineHint({ ...newLine(known), price: '3501' })).toBeNull();
    expect(lineHint({ ...newLine(known), price: '3500' })).toMatch(/above what the item cost/i);
    expect(lineHint({ ...newLine(known), price: '100' })).toMatch(/above what the item cost/i);
    // Without the cost the form cannot know: the server refuses with price_below_cost.
    expect(lineHint({ ...newLine(bulb), price: '100' })).toBeNull();
  });

  it('makes a different idempotency key for each form open', () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
    expect(newIdempotencyKey()).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('keeps the key with the draft across a reload until the sale is saved or cleared (#77)', () => {
    const items = new Map<string, string>();
    const store: DraftStore = {
      getItem: (k) => items.get(k) ?? null,
      setItem: (k, v) => void items.set(k, v),
      removeItem: (k) => void items.delete(k),
    };
    const opened = loadDraft(store, 'sale:u1:b1', draft({ lines: [] }));
    saveDraft(store, 'sale:u1:b1', { key: opened.key, draft: draft() });
    const reloaded = loadDraft(store, 'sale:u1:b1', draft({ lines: [] }));
    expect(reloaded.key).toBe(opened.key);
    expect(buildSaleRequest(reloaded.draft)).toEqual(buildSaleRequest(draft()));
    expect(loadDraft(store, 'sale:u1:b2', draft()).key).not.toBe(opened.key);
    dropDraft(store, 'sale:u1:b1');
    expect(loadDraft(store, 'sale:u1:b1', draft()).key).not.toBe(opened.key);
    items.set('retail-draft:sale:u1:b1', '{not json');
    expect(loadDraft(store, 'sale:u1:b1', draft()).draft.lines).toHaveLength(1);
    expect(loadDraft(null, 'sale:u1:b1', draft()).key).toMatch(/^[0-9a-f-]{36}$/);
  });
});

describe('a credit sale with customer accounts on (ADR-033, issue #196)', () => {
  const accounts = { termsDays: undefined as number | undefined };
  const credit = (over: Partial<Draft> = {}): Draft => draft({ method: 'credit', customerId: 'c1', dueDate: '2026-11-01', ...over });

  it('is exactly the old form while the feature is off', () => {
    const typed = draft({ method: 'credit', newBuyerName: ' Test Buyer 02 ', dueDate: '2026-11-01' });
    expect(draftProblem(typed)).toBeNull();
    expect(buildSaleRequest(typed)).toMatchObject({ buyer_name: 'Test Buyer 02', due_date: '2026-11-01' });
    expect(buildSaleRequest(typed)).not.toHaveProperty('credit_override_reason');
  });

  it('names a buyer from the list: a typed name is not enough', () => {
    expect(draftProblem(draft({ method: 'credit', newBuyerName: 'Test Buyer 02', dueDate: '2026-11-01' }), undefined, accounts)).toMatch(/from the list/i);
    expect(draftProblem(credit(), undefined, accounts)).toBeNull();
  });

  it('asks for a date only when the buyer has no terms to give one', () => {
    expect(draftProblem(credit({ dueDate: '' }), undefined, accounts)).toMatch(/date/i);
    expect(draftProblem(credit({ dueDate: '' }), undefined, { termsDays: 30 })).toBeNull();
    expect(draftProblem(credit({ dueDate: '' }), undefined, { termsDays: 0 })).toBeNull();
  });

  it('leaves the due date out when none is typed, so the server dates it from the terms', () => {
    expect(buildSaleRequest(credit({ dueDate: '' }), undefined, { termsDays: 30 })).not.toHaveProperty('due_date');
    expect(buildSaleRequest(credit(), undefined, { termsDays: 30 })).toMatchObject({ due_date: '2026-11-01' });
  });

  it('sends the reason for going above the limit only when one is typed, and never for a cash sale', () => {
    expect(buildSaleRequest(credit(), undefined, accounts)).not.toHaveProperty('credit_override_reason');
    expect(buildSaleRequest(credit({ creditReason: '  Standing arrangement ' }), undefined, accounts)).toMatchObject({ credit_override_reason: 'Standing arrangement' });
    expect(buildSaleRequest(draft({ creditReason: 'Standing arrangement' }), undefined, accounts)).not.toHaveProperty('credit_override_reason');
  });
});
