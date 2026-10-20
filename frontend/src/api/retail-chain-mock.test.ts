import { describe, expect, it } from 'vitest';
import type { Sale } from './retail';
import { summariseChain, type ChainContext } from './retail-chain-mock';

// The mock of the chained sales summary follows the server's rules (issue #341): lines under a category or an item,
// whole sales elsewhere, voided counted apart, profit only on request, bounded with next_offset. Fabricated sales.

const ctx: ChainContext = {
  profit: true,
  branchName: (id) => (id === 'b1' ? 'Test Branch A' : 'Test Branch B'),
  sellerName: (id) => `Test Seller ${id}`,
  product: (id) => (id === 'pa' ? { name: 'Test Product A', categoryId: 'ca', categoryName: 'Test Category A' } : { name: 'Test Product B', categoryId: 'cb', categoryName: 'Test Category B' }),
};
const line = (product: string, qty: string, total: number, cost: number) => ({ product_id: product, description: `Test Product ${product}`, qty, line_total_minor: total, line_cost_minor: cost });
const sale = (id: string, branch: string, seller: string, date: string, method: Sale['payment_method'], lines: ReturnType<typeof line>[], status: Sale['status'] = 'completed'): Sale => {
  const total = lines.reduce((n, l) => n + l.line_total_minor, 0);
  const cost = lines.reduce((n, l) => n + l.line_cost_minor, 0);
  return { id, branch_id: branch, created_by: seller, sale_date: date, payment_method: method, status, total_minor: total, profit_minor: total - cost, lines } as Sale;
};
const sales = [
  sale('s1', 'b1', 'u1', '2026-10-19', 'cash', [line('pa', '2', 4000, 3000), line('pb', '1', 300, 100)]),
  sale('s2', 'b1', 'u2', '2026-10-19', 'mobile_money', [line('pa', '1', 2000, 1500)]),
  sale('s3', 'b2', 'u1', '2026-10-18', 'cash', [line('pb', '3', 900, 300)]),
  sale('s4', 'b1', 'u1', '2026-10-19', 'cash', [line('pa', '5', 10_000, 7500)], 'voided'),
];
const ask = (level: Parameters<typeof summariseChain>[1]['level'], extra: Partial<Parameters<typeof summariseChain>[1]> = {}, rows = sales, c = ctx) =>
  summariseChain(rows, { level, chain: ['branch', 'year', 'seller', 'category', 'item'], ...extra }, c);

describe('summariseChain', () => {
  it('names the chain, the next level and the available levels', () => {
    const r = ask('seller');
    expect([r.group_by, r.next_level, r.levels]).toEqual(['seller', 'category', ['branch', 'year', 'seller', 'category', 'item']]);
    expect(r.available_levels).toHaveLength(7);
    expect(ask('item').next_level).toBeUndefined();
  });

  it('groups whole sales by shop, biggest first, with labels, profit and the voided count apart', () => {
    const r = ask('branch');
    expect(r.items?.map((g) => [g.key, g.label, g.sales_count, g.total_minor, g.profit_minor, g.voided_count])).toEqual([
      ['b1', 'Test Branch A', 2, 6300, 1700, 1], ['b2', 'Test Branch B', 1, 900, 600, 0],
    ]);
  });

  it('groups by salesperson and leaves profit out without the permission', () => {
    const r = ask('seller', {}, sales, { ...ctx, profit: false });
    expect(r.items?.map((g) => [g.label, g.total_minor])).toEqual([['Test Seller u1', 5200], ['Test Seller u2', 2000]]);
    expect(r.items?.every((g) => g.profit_minor === undefined)).toBe(true);
  });

  it('sums the lines of a category and of an item, with the quantity', () => {
    const cats = ask('category');
    expect(cats.items?.map((g) => [g.label, g.total_minor, g.sales_count, g.voided_count])).toEqual([['Test Category A', 6000, 2, 1], ['Test Category B', 1200, 2, 0]]);
    const items = ask('item', { categoryId: 'ca' });
    expect(items.items?.map((g) => [g.key, g.quantity, g.total_minor, g.profit_minor])).toEqual([['pa', '3', 6000, 1500]]);
    expect(ask('day', { itemId: 'pb' }).items?.map((g) => [g.key, g.total_minor])).toEqual([['2026-10-19', 300], ['2026-10-18', 900]]);
  });

  it('counts the sales of each payment method once, even with two lines', () => {
    const r = ask('category');
    expect(r.items?.[0]?.by_method?.cash).toEqual({ count: 1, total_minor: 4000 });
    expect(r.items?.[0]?.by_method?.mobile_money).toEqual({ count: 1, total_minor: 2000 });
  });

  it('keeps time levels as the sale list reads them', () => {
    const r = ask('year');
    expect(r.items?.[0]).toMatchObject({ key: '2026', label: '2026', period: '2026', total_minor: 7200, sales_count: 3 });
  });

  it('stops at the bound and gives the offset to continue from', () => {
    const many = Array.from({ length: 52 }, (_, i) => sale(`m${i}`, 'b1', `u${i}`, '2026-10-01', 'cash', [line('pa', '1', 100 + i, 50)]));
    const first = ask('seller', {}, many);
    expect(first.items).toHaveLength(50);
    expect([first.has_more, first.next_offset]).toEqual([true, 50]);
    const rest = ask('seller', { offset: 50 }, many);
    expect(rest.items).toHaveLength(2);
    expect([rest.has_more, rest.next_offset]).toEqual([false, undefined]);
  });
});
