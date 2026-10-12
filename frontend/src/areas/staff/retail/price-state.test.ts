import { describe, expect, it } from 'vitest';
import type { Product, ResolvedLine, SaleLine } from '../../../api/retail';
import { formatMinor } from '../../../components/money';
import { MIN_REASON, hasNoPrice, isOverride, pricedLines, reasonOk, receiptNote, sourceNote } from './price-state';
import { buildSaleRequest, draftProblem, newLine, resolveRequestOf, type Draft } from './sale-state';
import { belowCostHint, buildItemRequest, emptyItemForm, fromQty, itemProblem, unitChoices, withProduct } from './price-list-state';

// Price lists on the sale form and in the editor (ADR-033, issue #195). The server is the authority; these are the
// early hints and the request shapes. Money is integer minor units and nothing is floating point. All data is invented.

const pen: Product = { id: 'p1', code: 'T-PEN', description: 'Test pen', unit: 'piece', unit_id: 'u-piece', sell_minor: 100, cost_minor: 83, active: true, qty: '100.000' };
const caseOfPens: Product = { ...pen, packs: [{ id: 'k1', product_id: 'p1', unit_id: 'u-carton', unit: 'carton', factor: '12.000', active: true, version: 1 }] };
const money = (m: number) => formatMinor(m, 'UGX');

const draft = (over: Partial<Draft> = {}): Draft => ({
  branchId: 'b1', method: 'cash', customerId: '', newBuyerName: '', newBuyerContact: '', dueDate: '', lines: [newLine(pen)], ...over,
});
const r = (over: Partial<ResolvedLine> = {}): ResolvedLine => ({ product_id: 'p1', qty: '1.000', unit_price_minor: 90, line_total_minor: 90, price_source: 'list', price_list_id: 'l1', break_min_qty: '0.000', ...over });

describe('the resolved price goes on the lines the seller did not type', () => {
  it('replaces the default price with the resolved one', () => {
    expect(pricedLines([newLine(pen)], [r({ unit_price_minor: 80 })])[0]?.price).toBe('80');
  });

  it('keeps a price the seller typed, and everything without a resolution', () => {
    const typed = { ...newLine(pen), price: '85', typed: true };
    expect(pricedLines([typed], [r({ unit_price_minor: 80 })])[0]?.price).toBe('85');
    expect(pricedLines([newLine(pen)], undefined)[0]?.price).toBe('100');
    expect(pricedLines([newLine(pen)], [])[0]?.price).toBe('100');
  });
});

describe('an override is a typed price that differs from the resolved one', () => {
  it('is one only when typed and different', () => {
    expect(isOverride({ price: '85', typed: true }, r())).toBe(true);
    expect(isOverride({ price: '90', typed: true }, r())).toBe(false);
    expect(isOverride({ price: '85' }, r())).toBe(false);
    expect(isOverride({ price: '85', typed: true }, undefined)).toBe(false);
    expect(isOverride({ price: 'abc', typed: true }, r())).toBe(false);
  });

  it('needs a reason of at least five characters, as the server does', () => {
    expect(MIN_REASON).toBe(5);
    expect(reasonOk('Loyal')).toBe(true);
    expect(reasonOk('  ok  ')).toBe(false);
    expect(reasonOk(undefined)).toBe(false);
  });
});

describe('what the form says about the price', () => {
  it('names the list and the quantity break', () => {
    expect(sourceNote(r(), 'Wholesale')).toBe('Price list Wholesale');
    expect(sourceNote(r({ break_min_qty: '10.000' }), 'Wholesale')).toBe('Price list Wholesale, from 10');
    expect(sourceNote(r({ price_source: 'retail', price_list_id: undefined, break_min_qty: undefined }), 'Wholesale')).toBe('Retail price');
    expect(sourceNote(undefined, 'Wholesale')).toBe('');
  });

  it('says plainly when a strict list has no price', () => {
    const none = r({ problem: 'no_price_in_list', price_source: 'retail' });
    expect(hasNoPrice(none)).toBe(true);
    expect(sourceNote(none, 'Strict list')).toContain('has no price');
  });

  it('tells a saved sale line its story, and an old line nothing', () => {
    const line = (price: SaleLine['price']): SaleLine => ({ product_id: 'p1', description: 'Test pen', qty: '1.000', unit_price_minor: 85, line_total_minor: 85, ...(price ? { price } : {}) });
    expect(receiptNote(line(undefined), money)).toBe('');
    expect(receiptNote(line({ source: 'retail', resolved_unit_price_minor: 100 }), money)).toBe('Retail price');
    expect(receiptNote(line({ source: 'list', price_list: 'Wholesale', break_min_qty: '10.000', resolved_unit_price_minor: 80 }), money)).toBe('Price list Wholesale, from 10');
    expect(receiptNote(line({ source: 'override', price_list: 'Wholesale', break_min_qty: '0.000', resolved_unit_price_minor: 90, override_reason: 'Loyal buyer' }), money)).toBe(
      'Price changed from UGX 90 (Price list Wholesale): Loyal buyer',
    );
  });
});

describe('the sale request with price lists on', () => {
  const pricing = (resolved: ResolvedLine[]) => ({ resolved, checking: false });

  it('sends no price at all for a resolved price: the server resolves it again', () => {
    const lines = pricedLines(draft().lines, [r({ unit_price_minor: 80 })]);
    const req = buildSaleRequest(draft({ lines }), pricing([r({ unit_price_minor: 80 })]));
    expect(req.lines[0]).toEqual({ product_id: 'p1', qty: '1.000' });
  });

  it('sends an override with its reason, and the customer on a cash sale', () => {
    const typed = [{ ...newLine(pen), price: '85', typed: true, reason: ' Loyal buyer ' }];
    const req = buildSaleRequest(draft({ customerId: 'c1', priceListId: 'l2', lines: typed }), pricing([r()]));
    expect(req.customer_id).toBe('c1');
    expect(req.price_list_id).toBe('l2');
    expect(req.lines[0]).toEqual({ product_id: 'p1', qty: '1.000', unit_price_minor: 85, price_override_reason: 'Loyal buyer' });
  });

  it('is unchanged with price lists off: only a credit sale names a customer, and the default price is the sell price', () => {
    const req = buildSaleRequest(draft({ customerId: 'c1', priceListId: 'l2', lines: [{ ...newLine(pen), price: '85', typed: true, reason: 'Loyal buyer' }] }));
    expect(req).not.toHaveProperty('customer_id');
    expect(req).not.toHaveProperty('price_list_id');
    expect(req.lines[0]).toEqual({ product_id: 'p1', qty: '1.000', unit_price_minor: 85 });
    expect(buildSaleRequest(draft({ method: 'credit', customerId: 'c1', dueDate: '2026-11-01' }))).toHaveProperty('customer_id', 'c1');
  });

  it('waits for the prices, then refuses what the server would', () => {
    expect(draftProblem(draft(), { checking: true })).toMatch(/checking/i);
    expect(draftProblem(draft(), { checking: false })).toMatch(/could not be checked/i);
    expect(draftProblem(draft(), pricing([r({ problem: 'no_price_in_list' })]))).toMatch(/no price/i);
    const typed = draft({ lines: [{ ...newLine(pen), price: '85', typed: true }] });
    expect(draftProblem(typed, pricing([r()]))).toMatch(/reason/i);
    const reasoned = draft({ lines: [{ ...newLine(pen), price: '85', typed: true, reason: 'Loyal buyer' }] });
    expect(draftProblem(reasoned, pricing([r()]))).toBeNull();
    expect(draftProblem(draft(), pricing([r()]))).toBeNull();
  });

  it('asks the server nothing until every quantity can be read, and names the pack', () => {
    expect(resolveRequestOf(draft({ lines: [] }))).toBeNull();
    expect(resolveRequestOf(draft({ lines: [{ ...newLine(pen), qty: 'x' }] }))).toBeNull();
    expect(resolveRequestOf(draft({ customerId: 'c1', lines: [{ ...newLine(caseOfPens), unitId: 'u-carton', qty: '2' }] }))).toEqual({
      branch_id: 'b1', customer_id: 'c1', lines: [{ product_id: 'p1', qty: '2.000', unit_id: 'u-carton' }],
    });
  });
});

describe('the price list editor', () => {
  const form = (over = {}) => ({ ...withProduct(emptyItemForm('2026-10-12'), pen), price: '90', ...over });

  it('needs an item, a price and a date that is not in the past', () => {
    expect(itemProblem(emptyItemForm('2026-10-12'), '2026-10-12')).toMatch(/choose an item/i);
    expect(itemProblem(form({ price: '' }), '2026-10-12')).toMatch(/price/i);
    expect(itemProblem(form({ validFrom: '2026-10-11' }), '2026-10-12')).toMatch(/past/i);
    expect(itemProblem(form({ validTo: '2026-10-11' }), '2026-10-12')).toMatch(/last day/i);
    expect(itemProblem(form({ minQty: '1.2345' }), '2026-10-12')).toMatch(/three decimals/i);
    expect(itemProblem(form(), '2026-10-12')).toBeNull();
    expect(itemProblem(form({ validFrom: '2026-10-13', validTo: '2026-10-13', minQty: '10' }), '2026-10-12')).toBeNull();
  });

  it('builds the request, with no break meaning any quantity and no end meaning open ended', () => {
    expect(buildItemRequest(form())).toEqual({ product_id: 'p1', unit_id: 'u-piece', min_qty: '0.000', price_minor: 90, valid_from: '2026-10-12' });
    expect(buildItemRequest(form({ minQty: '10', price: '1,500', validTo: '2026-12-31' }))).toEqual({
      product_id: 'p1', unit_id: 'u-piece', min_qty: '10.000', price_minor: 1500, valid_from: '2026-10-12', valid_to: '2026-12-31',
    });
  });

  it('offers the base unit and the active packs', () => {
    expect(unitChoices(pen).map((u) => u.label)).toEqual(['piece']);
    expect(unitChoices(caseOfPens).map((u) => u.id)).toEqual(['u-piece', 'u-carton']);
  });

  it('warns when a price is not above cost, judged on the unit, and says nothing without the cost', () => {
    expect(belowCostHint(form({ price: '83' }))).toBe(true);
    expect(belowCostHint(form({ price: '84' }))).toBe(false);
    // A carton of twelve costs 996: 996 is not above it, 997 is.
    const pack = (price: string) => ({ ...withProduct(emptyItemForm('2026-10-12'), caseOfPens), unitId: 'u-carton', price });
    expect(belowCostHint(pack('996'))).toBe(true);
    expect(belowCostHint(pack('997'))).toBe(false);
    const blind = { ...form({ price: '10' }), product: { ...pen, cost_minor: undefined } };
    expect(belowCostHint(blind)).toBeNull();
  });

  it('says what a quantity break means', () => {
    expect(fromQty('0.000')).toBe('Any quantity');
    expect(fromQty('10.000')).toBe('From 10');
    expect(fromQty('12.500')).toBe('From 12.5');
  });
});
