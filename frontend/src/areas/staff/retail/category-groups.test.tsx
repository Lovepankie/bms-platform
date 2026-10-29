import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToString as render } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AllBranchesStock, StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { OPEN_UP_TO, UNCATEGORISED, defaultOpen, groupByCategory, groupSummary, readGrouped, sameUnitQty, writeGrouped } from './category-groups';
import { AllBranchesList, AllBranchesTable, StockTable } from './stock';

// The grouping of item lists by category: the pure part (order, counts, subtotals, the uncategorised
// group, when groups start open, the remembered switch) and the markup of the stock lists in both
// modes. Static markup, as the other screen tests are (the test setup has no DOM).

const html = (node: React.ReactElement) => render(node).replaceAll('<!-- -->', '');
const page = (node: React.ReactElement) =>
  html(
    <QueryClientProvider client={new QueryClient()}>
      <StaffContext.Provider value={{ me: mockMe('admin'), branch: null }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );

type Item = { id: string; category?: string | null; qty?: string; unit?: string };
const items: Item[] = [
  { id: 'a', category: 'Pipes', qty: '3', unit: 'piece' },
  { id: 'b', category: 'bulbs', qty: '1.5', unit: 'piece' },
  { id: 'c', category: undefined, qty: '2', unit: 'piece' },
  { id: 'd', category: 'Pipes', qty: '4', unit: 'piece' },
  { id: 'e', category: '  ', qty: '1', unit: 'roll' },
  { id: 'f', category: 'Cable', qty: '9', unit: 'roll' },
];

describe('groupByCategory', () => {
  const groups = groupByCategory(items, (i) => i.category);

  it('orders the categories A to Z without regard to case and puts uncategorised last', () => {
    expect(groups.map((g) => g.label)).toEqual(['bulbs', 'Cable', 'Pipes', UNCATEGORISED]);
  });

  it('keeps the items of a group in the order they came and counts them', () => {
    expect(groups.find((g) => g.label === 'Pipes')?.items.map((i) => i.id)).toEqual(['a', 'd']);
    expect(groups.map((g) => g.items.length)).toEqual([1, 1, 2, 2]);
  });

  it('puts both a missing and a blank category in the uncategorised group', () => {
    expect(groups[3]?.items.map((i) => i.id)).toEqual(['c', 'e']);
  });

  it('writes a subtotal per group when asked, and none otherwise', () => {
    expect(groups.every((g) => g.subtotal === null)).toBe(true);
    const withTotals = groupByCategory(items, (i) => i.category, (rows) => sameUnitQty(rows));
    expect(withTotals.map((g) => g.subtotal)).toEqual(['1.5 pieces', '9 rolls', '7 pieces', null]);
  });

  it('is empty for no items and never changes its input', () => {
    expect(groupByCategory([], () => 'x')).toEqual([]);
    expect(items.map((i) => i.id)).toEqual(['a', 'b', 'c', 'd', 'e', 'f']);
  });

  it('numbers sort naturally', () => {
    const rows = [{ c: 'Size 10' }, { c: 'Size 9' }];
    expect(groupByCategory(rows, (r) => r.c).map((g) => g.label)).toEqual(['Size 9', 'Size 10']);
  });
});

describe('groupSummary', () => {
  it('says the count with its noun and the subtotal', () => {
    expect(groupSummary({ items: [1], subtotal: null })).toBe('1 item');
    expect(groupSummary({ items: [1, 2], subtotal: '5 piece, UGX 10,000 at price' })).toBe('2 items, 5 piece, UGX 10,000 at price');
  });
});

describe('sameUnitQty', () => {
  it('adds quantities of one unit exactly, and gives nothing for mixed or missing units', () => {
    expect(sameUnitQty([{ qty: '0.100', unit: 'kg' }, { qty: '0.200', unit: 'kg' }])).toBe('0.3 kilograms');
    expect(sameUnitQty([{ qty: '1', unit: 'kg' }, { qty: '1', unit: 'piece' }])).toBeNull();
    expect(sameUnitQty([{ qty: '1' }])).toBeNull();
    expect(sameUnitQty([])).toBeNull();
  });
});

describe('when groups start open', () => {
  it('opens up to 30 items and closes above that', () => {
    expect(OPEN_UP_TO).toBe(30);
    expect(defaultOpen(30, false)).toBe(true);
    expect(defaultOpen(31, false)).toBe(false);
  });

  it('opens a long list that a search narrowed, so a match inside a closed group shows', () => {
    expect(defaultOpen(500, true)).toBe(true);
  });
});

describe('the remembered switch', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('is on when nothing is stored, and remembers off and on per screen', () => {
    const store = new Map<string, string>();
    vi.stubGlobal('localStorage', { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v) });
    expect(readGrouped('stock')).toBe(true);
    writeGrouped('stock', false);
    expect(readGrouped('stock')).toBe(false);
    expect(readGrouped('items')).toBe(true);
    writeGrouped('stock', true);
    expect(readGrouped('stock')).toBe(true);
  });

  it('survives storage that throws', () => {
    vi.stubGlobal('localStorage', { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); } });
    expect(readGrouped('stock')).toBe(true);
    expect(() => writeGrouped('stock', false)).not.toThrow();
  });
});

const row = (n: number, category: string | undefined, qty = '2'): StockRow => ({ product_id: `p${n}`, description: `Test item ${n}`, category, unit: 'piece', qty, sell_minor: 1000 });

describe('the stock list in groups', () => {
  it('shows a header per category with the count, the quantity and the value, and the rows inside', () => {
    const out = page(<StockTable showCost={false} rows={[row(1, 'Pipes'), row(2, 'Pipes', '3'), row(3, 'Bulbs'), row(4, undefined)]} />);
    expect(out).toContain('Group by category');
    expect(out).toContain('Expand all');
    expect(out).toContain('Collapse all');
    expect(out.indexOf('Bulbs')).toBeLessThan(out.indexOf('Pipes'));
    expect(out.indexOf('Pipes')).toBeLessThan(out.indexOf(UNCATEGORISED));
    expect(out).toContain('2 items, 5 pieces, worth UGX 5,000 at selling price');
    expect(out).toContain('Test item 1');
    expect(out).not.toContain('<th>Category</th>');
  });

  it('gives each header a button with aria-expanded and aria-controls pointing at its rows', () => {
    const out = page(<StockTable showCost={false} rows={[row(1, 'Pipes'), row(2, 'Bulbs')]} />);
    const buttons = [...out.matchAll(/<button type="button" class="cat-head" aria-expanded="(true|false)" aria-controls="([^"]+)"/g)];
    expect(buttons).toHaveLength(2);
    for (const b of buttons) expect(out).toContain(`id="${b[2]}"`);
    expect(buttons.every((b) => b[1] === 'true')).toBe(true);
  });

  it('starts open at 30 items and closed at 31, with the rows left out of a closed group', () => {
    const many = (n: number) => Array.from({ length: n }, (_, i) => row(i, i % 2 ? 'Pipes' : 'Bulbs'));
    const thirty = page(<StockTable showCost={false} rows={many(30)} />);
    expect(thirty).toContain('aria-expanded="true"');
    expect(thirty).toContain('Test item 29');
    const thirtyOne = page(<StockTable showCost={false} rows={many(31)} />);
    expect(thirtyOne).toContain('aria-expanded="false"');
    expect(thirtyOne).not.toContain('aria-expanded="true"');
    expect(thirtyOne).not.toContain('Test item 30');
    expect(thirtyOne).toContain('16 items');
  });

  it('opens every group of a long list while a search is on', () => {
    const many = Array.from({ length: 40 }, (_, i) => row(i, i % 2 ? 'Pipes' : 'Bulbs'));
    const out = page(<StockTable showCost={false} rows={many} searching filterKey="pipe" />);
    expect(out).not.toContain('aria-expanded="false"');
    expect(out).toContain('Test item 39');
  });

  it('shows the cost column in grouped mode too', () => {
    const out = page(<StockTable showCost rows={[{ ...row(1, 'Pipes'), cost_minor: 700 }]} />);
    expect(out).toContain('<th class="num">Average buying price</th>');
    expect(out).toContain('UGX 700');
  });
});

describe('the all-branches stock in groups', () => {
  const data: AllBranchesStock = {
    branches: [{ id: 'b1', code: 'BR1', name: 'Test Branch A' }],
    items: [
      { product_id: 'p1', description: 'Test item 1', category: 'Pipes', unit: 'piece', total_qty: '4', sell_minor: 1000, balances: [{ branch_id: 'b1', qty: '4' }] },
      { product_id: 'p2', description: 'Test item 2', category: 'Bulbs', unit: 'piece', total_qty: '1', sell_minor: 500, balances: [{ branch_id: 'b1', qty: '1' }] },
    ],
  };

  it('groups the table and the phone cards by category', () => {
    for (const out of [page(<AllBranchesTable data={data} showCost={false} />), page(<AllBranchesList data={data} showCost={false} />)]) {
      expect(out.indexOf('Bulbs')).toBeLessThan(out.indexOf('Pipes'));
      expect(out).toContain('1 item, 4 pieces, worth UGX 4,000 at selling price');
      expect(out).toContain('Test item 2');
    }
  });
});
