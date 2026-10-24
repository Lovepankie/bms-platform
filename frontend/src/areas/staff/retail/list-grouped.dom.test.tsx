// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, configure, fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AllBranchesStock, Purchase, PurchasesSummary, PurchasesSummaryGroup, PurchasesSummaryQuery, StockRow, StockSummary, StockSummaryGroup, StockSummaryQuery } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { ALL_BRANCHES } from '../../../auth/branch';
import { StaffContext } from '../context';
import { writeChainOf, writeView } from './list-chain-state';

// The stock list and the restocks list grouped by a chain (issue #350): flat until the person chooses Grouped, one request per level with
// what was opened above it, the figures the server summed (cost only when it sent it), and the screens' own rows under the last level, so
// Correct on a stock row and Correct this restock still work inside the groups. Fabricated names and amounts.

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });

const stockSummary = vi.fn<(q: StockSummaryQuery) => Promise<StockSummary>>();
const purchasesSummary = vi.fn<(q: PurchasesSummaryQuery) => Promise<PurchasesSummary>>();
const listStock = vi.fn();
const listStockAllBranches = vi.fn();
const listPurchases = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, useNavigate: () => vi.fn(), Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      stockSummary: (q: StockSummaryQuery) => stockSummary(q),
      purchasesSummary: (q: PurchasesSummaryQuery) => purchasesSummary(q),
      listStock: (q: unknown) => listStock(q),
      listStockAllBranches: (q: unknown) => listStockAllBranches(q),
      listPurchases: (q: unknown) => listPurchases(q),
      listCategories: () => Promise.resolve([{ id: 'c1', name: 'Test Category Cables', active: true }]),
      listSuppliers: () => Promise.resolve([{ id: 's1', name: 'Test Supplier 01', active: true, version: 1 }]),
    },
  };
});

const { StockPage } = await import('./stock');
const { RestocksPage } = await import('./restocks');

const me = mockMe('admin');
const hq = me.branches?.[0]?.id ?? '';
const user = me.user_id ?? 'staff';

function show(node: ReactElement, branch: string = ALL_BRANCHES) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

beforeEach(() => localStorage.clear());
afterEach(() => {
  cleanup();
  for (const f of [stockSummary, purchasesSummary, listStock, listStockAllBranches, listPurchases]) f.mockReset();
});

const head = (name: RegExp | string) => screen.findByRole('button', { name });

// ---- stock -------------------------------------------------------------------------------------

const shop = (over: Partial<StockSummaryGroup> = {}): StockSummaryGroup => ({ key: hq, label: 'Test Branch Gayaza', items_count: 2, negative_count: 1, value_minor: 15000, cost_minor: 10000, ...over });
const category = (over: Partial<StockSummaryGroup> = {}): StockSummaryGroup => ({ key: 'c1', label: 'Test Category Cables', items_count: 1, negative_count: 0, value_minor: 15000, cost_minor: 10000, ...over });
const stockAnswer = (cost = true) => async (q: StockSummaryQuery): Promise<StockSummary> => {
  const next = q.chain[q.chain.indexOf(q.level) + 1];
  const strip = (g: StockSummaryGroup): StockSummaryGroup => (cost ? g : { ...g, cost_minor: undefined });
  return { group_by: q.level, levels: q.chain, ...(next ? { next_level: next } : {}), currency: 'UGX', has_more: false, items: (q.level === 'branch' ? [shop()] : [category()]).map(strip) };
};
const row: StockRow = { product_id: 'p1', code: 'CB-25', description: 'Test Product Cable 2.5mm', category: 'Test Category Cables', unit: 'roll', qty: '10.000', negative: false, sell_minor: 1500, cost_minor: 1000 };
const all: AllBranchesStock = { branches: [{ id: hq, name: 'Test Branch Gayaza' }], items: [] };

describe('the stock list, grouped', () => {
  beforeEach(() => {
    stockSummary.mockImplementation(stockAnswer());
    listStock.mockResolvedValue([row]);
    listStockAllBranches.mockResolvedValue(all);
  });

  it('is the flat list until Grouped is chosen, and asks for no summary', async () => {
    show(<StockPage />);
    expect(await screen.findByRole('radio', { name: 'Flat list' })).toHaveProperty('ariaChecked', 'true');
    expect(listStockAllBranches).toHaveBeenCalled();
    expect(stockSummary).not.toHaveBeenCalled();
  });

  it('opens from the shop to the category to the items, each level asked with what was opened above it', async () => {
    show(<StockPage />);
    const grouped = await screen.findByRole('radio', { name: 'Grouped' });
    listStockAllBranches.mockClear();
    fireEvent.click(grouped);
    const gayaza = await head(/Test Branch Gayaza/);
    expect(gayaza.textContent).toContain('2 items');
    expect(gayaza.textContent).toContain('at price');
    expect(gayaza.textContent).toContain('1 negative');
    expect(gayaza.textContent).toContain('at cost');
    expect(stockSummary.mock.calls[0]?.[0]).toMatchObject({ level: 'branch', chain: ['branch', 'category'] });
    expect(listStockAllBranches).not.toHaveBeenCalled();

    fireEvent.click(gayaza);
    fireEvent.click(await head(/Test Category Cables/));
    expect(stockSummary).toHaveBeenCalledWith(expect.objectContaining({ level: 'category', branchId: hq }));
    // The items of the opened category are the stock table of that shop, with its Correct button.
    expect(await screen.findByText('Test Product Cable 2.5mm')).toBeTruthy();
    expect(listStock).toHaveBeenCalledWith(expect.objectContaining({ branchId: hq, categoryId: 'c1' }));
    expect(screen.getByRole('button', { name: 'Correct Test Product Cable 2.5mm' })).toBeTruthy();
    const trail = screen.getByRole('navigation', { name: 'Where you are' });
    expect(within(trail).getByText('Test Category Cables')).toBeTruthy();
  });

  it('shows no cost when the server sent none', async () => {
    stockSummary.mockImplementation(stockAnswer(false));
    show(<StockPage />);
    fireEvent.click(await screen.findByRole('radio', { name: 'Grouped' }));
    const gayaza = await head(/Test Branch Gayaza/);
    expect(gayaza.textContent).not.toContain('at cost');
  });

  it('with one shop chosen at the top leaves the shop level out, and remembers the choice for the person', async () => {
    show(<StockPage />, hq);
    fireEvent.click(await screen.findByRole('radio', { name: 'Grouped' }));
    await head(/Test Category Cables/);
    expect(stockSummary.mock.calls[0]?.[0]).toMatchObject({ level: 'category', chain: ['category'], branchId: hq });
    expect(localStorage.getItem(`bms.listView.stock.${user}`)).toBe('grouped');
  });

  it('keeps a remembered chain and view, and Group by moves a level', async () => {
    writeView(user, 'stock', 'grouped');
    writeChainOf(user, 'stock', ['category', 'branch']);
    show(<StockPage />);
    await head(/Test Category Cables/);
    expect(stockSummary.mock.calls[0]?.[0]).toMatchObject({ level: 'category', chain: ['category', 'branch'] });
    fireEvent.click(screen.getByRole('button', { name: 'Move Product category down' }));
    await head(/Test Branch Gayaza/);
    expect(JSON.parse(localStorage.getItem(`bms.listChain.stock.${user}`) ?? '[]')).toEqual(['branch', 'category']);
  });
});

// ---- restocks ----------------------------------------------------------------------------------

const year = (over: Partial<PurchasesSummaryGroup> = {}): PurchasesSummaryGroup => ({ key: '2026', label: '2026', period: '2026', from: '2026-01-01', to: '2026-12-31', purchases_count: 2, voided_count: 1, total_minor: 20000, ...over });
const supplier = (over: Partial<PurchasesSummaryGroup> = {}): PurchasesSummaryGroup => ({ key: 's1', label: 'Test Supplier 01', purchases_count: 2, voided_count: 0, total_minor: 20000, ...over });
const restock: Purchase = {
  id: 'r1', purchase_no: 'RP00000001', supplier_id: 's1', purchased_on: '2026-10-05', payment_method: 'cash', currency: 'UGX', total_minor: 20000, status: 'posted',
  lines: [{ id: 'l1', line_no: 1, product_id: 'p1', code: 'CB-25', description: 'Test Product Cable 2.5mm', cost_minor: 1000, sell_minor: 1500, qty_total: '20.000', line_total_minor: 20000, base_unit: 'roll', qty_by_branch: [{ branch_id: hq, qty: '20.000' }] }],
};
const purchaseAnswer = (cost = true) => async (q: PurchasesSummaryQuery): Promise<PurchasesSummary> => {
  const next = q.chain[q.chain.indexOf(q.level) + 1];
  const g = q.level === 'supplier' ? supplier() : q.level === 'year' ? year() : { key: hq, label: 'Test Branch Gayaza', purchases_count: 2, voided_count: 0, total_minor: 20000 };
  return { group_by: q.level, levels: q.chain, ...(next ? { next_level: next } : {}), currency: 'UGX', has_more: false, items: [cost ? g : { ...g, total_minor: undefined }] };
};

describe('the past restocks, grouped', () => {
  beforeEach(() => {
    purchasesSummary.mockImplementation(purchaseAnswer());
    listPurchases.mockResolvedValue({ items: [restock] });
  });

  it('is the flat list of restocks until Grouped is chosen', async () => {
    show(<RestocksPage />);
    expect(await screen.findByText('RP00000001')).toBeTruthy();
    expect(purchasesSummary).not.toHaveBeenCalled();
  });

  it('groups by the chain with the counts, the voided apart and the cost the server sent', async () => {
    writeChainOf(user, 'restocks', ['year', 'supplier']);
    show(<RestocksPage />);
    const grouped = await screen.findByRole('radio', { name: 'Grouped' });
    listPurchases.mockClear();
    fireEvent.click(grouped);
    const y = await head(/2026/);
    expect(y.textContent).toContain('2 restocks');
    expect(y.textContent).toContain('1 voided (not counted)');
    expect(y.textContent).toContain('20,000');
    expect(purchasesSummary.mock.calls.map((c) => c[0]).find((q) => q.level === 'year')).toMatchObject({ level: 'year', chain: ['year', 'supplier'] });
    expect(listPurchases).not.toHaveBeenCalled();
  });

  it('leaves the cost out when the server sent none', async () => {
    writeChainOf(user, 'restocks', ['year']);
    purchasesSummary.mockImplementation(purchaseAnswer(false));
    show(<RestocksPage />);
    fireEvent.click(await screen.findByRole('radio', { name: 'Grouped' }));
    const y = await head(/2026/);
    expect(y.textContent).not.toContain('20,000');
  });

  it('lists the restocks of the last opened group with the cards that correct them', async () => {
    writeChainOf(user, 'restocks', ['supplier']);
    show(<RestocksPage />);
    fireEvent.click(await screen.findByRole('radio', { name: 'Grouped' }));
    fireEvent.click(await head(/Test Supplier 01/));
    expect(await screen.findByText('RP00000001')).toBeTruthy();
    expect(listPurchases).toHaveBeenCalledWith(expect.objectContaining({ supplierId: 's1' }));
    fireEvent.click(screen.getByRole('button', { name: 'Correct restock RP00000001' }));
    expect(screen.getByRole('form', { name: 'Correct this restock' })).toBeTruthy();
  });

  it('passes what was opened above to the next level and to the restocks', async () => {
    writeChainOf(user, 'restocks', ['year', 'supplier']);
    show(<RestocksPage />);
    fireEvent.click(await screen.findByRole('radio', { name: 'Grouped' }));
    fireEvent.click(await head(/2026/));
    fireEvent.click(await head(/Test Supplier 01/));
    // The group's year is narrowed by the page's own dates, so a restock outside them is never asked for.
    const asked = purchasesSummary.mock.calls.map((c) => c[0]).find((q) => q.level === 'supplier');
    expect(asked?.from?.slice(0, 4)).toBe('2026');
    expect(asked?.to?.slice(0, 4)).toBe('2026');
    expect((asked?.from ?? '') >= '2026-01-01' && (asked?.to ?? '') <= '2026-12-31').toBe(true);
    await screen.findByText('RP00000001');
    expect(listPurchases).toHaveBeenCalledWith(expect.objectContaining({ supplierId: 's1', from: asked?.from, to: asked?.to }));
  });
});
