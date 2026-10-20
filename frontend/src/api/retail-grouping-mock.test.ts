import { describe, expect, it } from 'vitest';
import { summarisePurchases, summariseStock } from './retail-grouping-mock';
import { NO_SUPPLIER, type Purchase } from './retail';

// The mock of the grouped restocks and stock summaries keeps the server's rules (issue #350). Fabricated data.

const line = (product: string, branch: string, qty: string, cost: number) => ({ product_id: product, cost_minor: cost, qty_by_branch: [{ branch_id: branch, qty }] });
const buy = (id: string, on: string, supplier: string | undefined, status: 'posted' | 'voided', lines: ReturnType<typeof line>[]): Purchase => ({ id, purchased_on: on, ...(supplier ? { supplier_id: supplier } : {}), status, lines } as Purchase);
const purchases = [
  buy('r1', '2026-10-05', 's1', 'posted', [line('p1', 'b1', '10', 1000)]),
  buy('r2', '2026-09-20', 's1', 'posted', [line('p2', 'b2', '5', 2000)]),
  buy('r3', '2026-10-05', undefined, 'voided', [line('p1', 'b1', '4', 1000)]),
];
const ctx = (cost = true) => ({
  cost,
  branchName: (id?: string) => `Test Branch ${id}`,
  supplierName: (id?: string) => `Test Supplier ${id}`,
  product: (id?: string) => (id ? { name: `Test Product ${id}`, categoryId: id === 'p1' ? 'c1' : 'c2', categoryName: id === 'p1' ? 'Cables' : 'Fittings', unit: 'roll' } : undefined),
});

describe('the restocks summary', () => {
  it('counts a voided restock apart and sums the rest at cost', () => {
    const r = summarisePurchases(purchases, { level: 'year', chain: ['year', 'month'] }, ctx());
    expect(r.items?.[0]).toMatchObject({ key: '2026', purchases_count: 2, voided_count: 1, total_minor: 20000 });
    expect(r.next_level).toBe('month');
  });

  it('leaves the cost out without the cost permission', () => {
    const r = summarisePurchases(purchases, { level: 'year', chain: ['year'] }, ctx(false));
    expect(r.items?.[0]?.total_minor).toBeUndefined();
  });

  it('groups by supplier with the nil key for none, and by item with the quantity and unit', () => {
    const s = summarisePurchases(purchases, { level: 'supplier', chain: ['supplier'] }, ctx());
    expect(s.items?.find((g) => g.key === NO_SUPPLIER)).toMatchObject({ label: 'No supplier', purchases_count: 0, voided_count: 1 });
    const i = summarisePurchases(purchases, { level: 'item', chain: ['item'], supplierId: 's1', branchId: 'b1' }, ctx());
    expect(i.items).toHaveLength(1);
    expect(i.items?.[0]).toMatchObject({ key: 'p1', quantity: '10', unit: 'roll' });
  });

  it('bounds the days and continues with next_to', () => {
    const many = Array.from({ length: 40 }, (_, n) => buy(`r${n}`, `2026-09-${String((n % 28) + 1).padStart(2, '0')}`, 's1', 'posted', [line('p1', 'b1', '1', 100)]));
    const r = summarisePurchases(many, { level: 'day', chain: ['day'] }, ctx());
    expect(r.items).toHaveLength(28);
    expect(r.has_more).toBe(false);
  });
});

describe('the stock summary', () => {
  const held: Record<string, number> = { 'b1|p1': 10000, 'b2|p2': 5000 };
  const stock = (extra = {}) => ({
    cost: true,
    branches: [{ id: 'b1', name: 'Test Branch One' }, { id: 'b2', name: 'Test Branch Two' }],
    products: [
      { id: 'p1', category: 'Cables', categoryId: 'c1', sellMinor: 1500, costMinor: 1000 },
      { id: 'p2', category: 'Fittings', categoryId: 'c2', sellMinor: 3000, costMinor: 2000 },
    ],
    balance: (b: string, p: string) => held[`${b}|${p}`] ?? 0,
    keepQty: () => true,
    ...extra,
  });

  it('counts the items and their value per shop, with cost only when allowed', () => {
    const r = summariseStock({ level: 'branch', chain: ['branch', 'category'] }, stock());
    expect(r.items?.[0]).toMatchObject({ key: 'b1', items_count: 2, value_minor: 15000, cost_minor: 10000 });
    expect(r.next_level).toBe('category');
    expect(summariseStock({ level: 'branch', chain: ['branch'] }, stock({ cost: false })).items?.[0]?.cost_minor).toBeUndefined();
  });

  it('narrows a category under one shop, and by stock level', () => {
    const r = summariseStock({ level: 'category', chain: ['branch', 'category'], branchId: 'b1' }, stock({ keepQty: (m: number) => m <= 0 }));
    expect(r.items).toHaveLength(1);
    expect(r.items?.[0]).toMatchObject({ label: 'Fittings', items_count: 1, value_minor: 0 });
  });
});
