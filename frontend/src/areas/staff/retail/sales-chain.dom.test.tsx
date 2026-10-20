// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, configure, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Sale, SalesSummary, SalesSummaryGroup, SalesSummaryQuery } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { ALL_BRANCHES } from '../../../auth/branch';
import { StaffContext } from '../context';
import { DEFAULT_CHAIN, chainUser, readChain, writeChain } from './sales-chain-state';

// The sales lists grouped by a chain of levels (issue #341): the default order opens shop, year, month, salesperson,
// day, category and item, each level asks the server with what was opened above it, the path shows as a breadcrumb,
// and the groups stay open after a sale is opened and closed. Fabricated names and amounts.

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });

const summary = vi.fn<(q: SalesSummaryQuery) => Promise<SalesSummary>>();
const listSales = vi.fn();
const getSale = vi.fn();
vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: { salesSummary: (q: SalesSummaryQuery) => summary(q), listSales: (q: unknown) => listSales(q), getSale: (id: string) => getSale(id), listProducts: async () => [] },
  };
});

const { AllSales, CreditSales } = await import('./sales');

const me = mockMe('admin');
const none = { count: 0, total_minor: 0 };
const figures = { sales_count: 3, total_minor: 9000, voided_count: 1, profit_minor: 2500, by_method: { cash: { count: 2, total_minor: 6000 }, mobile_money: none, bank: none, credit: { count: 1, total_minor: 3000 } } };
const group = (key: string, label: string, over: Partial<SalesSummaryGroup> = {}): SalesSummaryGroup => ({ key, label, ...figures, ...over });

/** One path through the data: Gayaza > 2026 > October > Test Seller > Monday 19 October > Test Category > Test Product. */
const LEVELS: Record<string, SalesSummaryGroup[]> = {
  branch: [group('b1', 'Test Branch Gayaza'), group('b2', 'Test Branch Wampewo', { total_minor: 4000, sales_count: 1, voided_count: 0 })],
  year: [group('2026', '2026', { period: '2026', from: '2026-01-01', to: '2026-12-31' })],
  month: [group('2026-10', '2026-10', { period: '2026-10', from: '2026-10-01', to: '2026-10-31' })],
  seller: [group('u1', 'Test Seller One')],
  day: [group('2026-10-19', '2026-10-19', { period: '2026-10-19', from: '2026-10-19', to: '2026-10-19' })],
  category: [group('c1', 'Test Category Cables')],
  item: [group('p1', 'Test Product Cable 2m', { quantity: '4', total_minor: 8000, profit_minor: 2000 })],
};
const answer = async (q: SalesSummaryQuery): Promise<SalesSummary> => {
  const chain = q.chain ?? [q.level];
  const next = chain[chain.indexOf(q.level) + 1];
  return { group_by: q.level, levels: chain, ...(next ? { next_level: next } : {}), currency: 'UGX', has_more: false, items: LEVELS[q.level] ?? [] };
};

const sale = (id: string, buyer: string, over: Partial<Sale> = {}): Sale => ({
  id, sale_no: `S-${id}`, branch_id: me.branches?.[0]?.id, sale_date: '2026-10-19', payment_method: 'credit', status: 'completed', buyer_name: buyer,
  total_minor: 3000, paid_minor: 0, balance_minor: 3000, due_date: '2026-10-19', historical: false, ...over,
});

function show(node: React.ReactElement, branch: string = ALL_BRANCHES, who: Me = me) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: who, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const head = (name: RegExp | string) => screen.findByRole('button', { name });

beforeEach(() => {
  localStorage.clear();
  summary.mockImplementation(answer);
  listSales.mockImplementation(async () => ({ items: [sale('one', 'Test Buyer One')] }));
  getSale.mockImplementation(async () => sale('one', 'Test Buyer One'));
});
afterEach(() => {
  cleanup();
  summary.mockReset();
  listSales.mockReset();
  getSale.mockReset();
});

describe('the default chain', () => {
  it('opens from the shop down to the items, each level carrying what was opened above it, with the totals at every step', async () => {
    show(<AllSales />);
    const gayaza = await head(/^Test Branch Gayaza /);
    expect(gayaza.textContent).toContain('UGX 9,000');
    expect(gayaza.textContent).toContain('3 sales, profit UGX 2,500, 1 voided (not counted)');
    expect(gayaza.textContent).toContain('Cash 2, UGX 6,000');
    expect(await head(/^Test Branch Wampewo /)).toBeTruthy();
    expect(summary).toHaveBeenCalledWith(expect.objectContaining({ level: 'branch', chain: [...DEFAULT_CHAIN] }));

    fireEvent.click(gayaza);
    fireEvent.click(await head(/^2026 /));
    fireEvent.click(await head(/^October 2026 /));
    fireEvent.click(await head(/^Test Seller One /));
    fireEvent.click(await head(/^Saturday 19 October 2026 |^[A-Z][a-z]+ 19 October 2026 /));
    fireEvent.click(await head(/^Test Category Cables /));

    const item = (await screen.findByText('Test Product Cable 2m')).closest('li') as HTMLElement;
    expect(item.textContent).toContain('4 sold');
    expect(item.textContent).toContain('UGX 8,000');
    expect(item.textContent).toContain('profit UGX 2,000');
    // The category and the item sum their own lines, and say so: "in 3 sales", not "3 sales".
    expect((await head(/^Test Category Cables /)).textContent).toContain('in 3 sales');
    expect(gayaza.textContent).not.toContain('in 3 sales');
    expect(summary).toHaveBeenLastCalledWith(expect.objectContaining({
      level: 'item', branchId: 'b1', sellerId: 'u1', categoryId: 'c1', from: '2026-10-19', to: '2026-10-19',
    }));
  });

  it('reaches the sales and Record payment on the default chain through "Show the sales of this day"', async () => {
    show(<AllSales />);
    fireEvent.click(await head(/^Test Branch Gayaza /));
    fireEvent.click(await head(/^2026 /));
    fireEvent.click(await head(/^October 2026 /));
    fireEvent.click(await head(/^Test Seller One /));
    fireEvent.click(await head(/^[A-Z][a-z]+ 19 October 2026 /));
    expect(screen.queryByText('Test Buyer One')).toBeNull();
    fireEvent.click(await screen.findByRole('button', { name: 'Show the sales of this day' }));
    await screen.findByText('Test Buyer One');
    expect(screen.getByRole('button', { name: 'Record payment' })).toBeTruthy();
    expect(listSales).toHaveBeenLastCalledWith(expect.objectContaining({ branchId: 'b1', sellerId: 'u1', from: '2026-10-19', to: '2026-10-19' }));
  });

  it('shows the open path as a breadcrumb and a crumb shuts what is inside it', async () => {
    show(<AllSales />);
    fireEvent.click(await head(/^Test Branch Gayaza /));
    fireEvent.click(await head(/^2026 /));
    fireEvent.click(await head(/^October 2026 /));
    const trail = within(await screen.findByRole('navigation', { name: 'Where you are' }));
    expect(trail.getAllByRole('listitem').map((li) => li.textContent)).toEqual(['All sales', 'Test Branch Gayaza', '2026', 'October 2026']);
    expect(trail.getByText('October 2026').getAttribute('aria-current')).toBe('page');
    fireEvent.click(trail.getByRole('button', { name: 'Test Branch Gayaza' }));
    expect(screen.queryByRole('button', { name: /^October 2026 / })).toBeNull();
    expect((await head(/^2026 /)).getAttribute('aria-expanded')).toBe('false');
    expect((await head(/^Test Branch Gayaza /)).getAttribute('aria-expanded')).toBe('true');
    expect(within(await screen.findByRole('navigation', { name: 'Where you are' })).getAllByRole('listitem')).toHaveLength(2);
  });

  it('keeps the groups open after a sale is opened and closed, and the sale rows keep Record payment', async () => {
    writeChain(chainUser(me), 'all', ['year', 'day']);
    show(<AllSales />);
    fireEvent.click(await head(/^2026 /));
    fireEvent.click(await head(/^[A-Z][a-z]+ 19 October 2026 /));
    await screen.findByText('Test Buyer One');
    expect(screen.getByRole('button', { name: 'Record payment' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /^Open the sale/ }));
    await screen.findByRole('button', { name: /Back|Close/i });
    expect(screen.queryByRole('button', { name: /^2026 / })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /Back|Close/i }));
    expect((await head(/^2026 /)).getAttribute('aria-expanded')).toBe('true');
    expect((await head(/^[A-Z][a-z]+ 19 October 2026 /)).getAttribute('aria-expanded')).toBe('true');
    expect(await screen.findByText('Test Buyer One')).toBeTruthy();
  });

  it('a day that opens onto categories shows its sales on request', async () => {
    writeChain(chainUser(me), 'all', ['year', 'day', 'category']);
    show(<AllSales />);
    fireEvent.click(await head(/^2026 /));
    fireEvent.click(await head(/^[A-Z][a-z]+ 19 October 2026 /));
    expect(await head(/^Test Category Cables /)).toBeTruthy();
    expect(screen.queryByText('Test Buyer One')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Show the sales of this day' }));
    expect(await screen.findByText('Test Buyer One')).toBeTruthy();
    expect(listSales).toHaveBeenLastCalledWith(expect.objectContaining({ from: '2026-10-19', to: '2026-10-19' }));
    fireEvent.click(screen.getByRole('button', { name: 'Hide the sales of this day' }));
    expect(screen.queryByText('Test Buyer One')).toBeNull();
  });

  it('the last level before the sales passes the person and the category to the list', async () => {
    writeChain(chainUser(me), 'all', ['seller', 'category']);
    show(<AllSales />);
    fireEvent.click(await head(/^Test Seller One /));
    fireEvent.click(await head(/^Test Category Cables /));
    await screen.findByText('Test Buyer One');
    expect(listSales).toHaveBeenLastCalledWith(expect.objectContaining({ sellerId: 'u1', categoryId: 'c1' }));
  });

  it('leaves the shop level out when one shop is chosen at the top of the page', async () => {
    show(<AllSales />, mockMe('admin').branches?.[0]?.id ?? '');
    expect(await head(/^2026 /)).toBeTruthy();
    expect(summary).toHaveBeenCalledWith(expect.objectContaining({ level: 'year', chain: ['year', 'month', 'seller', 'day', 'category', 'item'] }));
    expect(screen.queryByRole('button', { name: /^Test Branch / })).toBeNull();
  });
});

describe('a chain without a Day level, or ending in Item, can still reach a sale', () => {
  it('offers the sales on any group when the chain has no day', async () => {
    writeChain(chainUser(me), 'all', ['branch', 'year', 'month', 'seller', 'category', 'item']);
    show(<AllSales />);
    fireEvent.click(await head(/^Test Branch Gayaza /));
    fireEvent.click(await head(/^2026 /));
    fireEvent.click(await head(/^October 2026 /));
    fireEvent.click(await head(/^Test Seller One /));
    // Every open group offers it (there is no day); the first in the page is the deepest, the salesperson's.
    const offers = await screen.findAllByRole('button', { name: 'Show the sales here' });
    expect(offers).toHaveLength(4);
    fireEvent.click(offers[0] as HTMLElement);
    await screen.findByText('Test Buyer One');
    expect(screen.getByRole('button', { name: 'Record payment' })).toBeTruthy();
    expect(listSales).toHaveBeenLastCalledWith(expect.objectContaining({ branchId: 'b1', sellerId: 'u1', from: '2026-10-01' }));
  });

  it('offers the sales on the level above a last Item, and an Item row opens its sales, saying they are whole sales', async () => {
    writeChain(chainUser(me), 'all', ['year', 'day', 'category', 'item']);
    show(<AllSales />);
    fireEvent.click(await head(/^2026 /));
    fireEvent.click(await head(/^[A-Z][a-z]+ 19 October 2026 /));
    fireEvent.click(await head(/^Test Category Cables /));
    fireEvent.click(await screen.findByRole('button', { name: 'Show the sales here' }));
    await screen.findByText('Test Buyer One');
    expect(screen.getByText('Whole sales that include this category.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Hide the sales here' }));

    fireEvent.click(await head(/^Test Product Cable 2m /));
    await screen.findByText('Test Buyer One');
    expect(screen.getByText('Whole sales that include this item.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Record payment' })).toBeTruthy();
    expect(listSales).toHaveBeenLastCalledWith(expect.objectContaining({ productId: 'p1', categoryId: 'c1', from: '2026-10-19', to: '2026-10-19' }));
  });

  it('says whole sales under a category that is the last level', async () => {
    writeChain(chainUser(me), 'all', ['category']);
    show(<AllSales />);
    fireEvent.click(await head(/^Test Category Cables /));
    await screen.findByText('Test Buyer One');
    expect(screen.getByText('Whole sales that include this category.')).toBeTruthy();
  });
});

describe('what a person sees and what is stored', () => {
  it('draws a row once when the server repeats it between pages, and says nothing about profit it did not send', async () => {
    summary.mockImplementation(async (q) => {
      const base = await answer(q);
      const bare = (g: SalesSummaryGroup): SalesSummaryGroup => {
        const { profit_minor: _hidden, ...rest } = g;
        return rest;
      };
      if (q.level !== 'branch') return { ...base, items: base.items?.map(bare) };
      return q.offset
        ? { ...base, items: [bare(group('b1', 'Test Branch Gayaza')), bare(group('b3', 'Test Branch Third'))] }
        : { ...base, has_more: true, next_offset: 50, items: base.items?.map(bare) };
    });
    show(<AllSales />);
    await head(/^Test Branch Gayaza /);
    fireEvent.click(screen.getByRole('button', { name: 'Show more shops' }));
    expect(await head(/^Test Branch Third /)).toBeTruthy();
    expect(screen.getAllByRole('button', { name: /^Test Branch Gayaza / })).toHaveLength(1);
    expect(document.body.textContent?.toLowerCase()).not.toContain('profit');
  });

  it('does not offer the salesperson level to someone who may not read profit, and never asks for it', async () => {
    const cashier = mockMe('sales');
    writeChain(chainUser(cashier), 'all', [...DEFAULT_CHAIN]);
    show(<AllSales />, ALL_BRANCHES, cashier);
    expect(await head(/^Test Branch Gayaza /)).toBeTruthy();
    const control = screen.getByText('Group by').closest('details') as HTMLElement;
    expect(control.textContent).not.toContain('Salesperson');
    expect(summary).toHaveBeenCalledWith(expect.objectContaining({ level: 'branch', chain: ['branch', 'year', 'month', 'day', 'category', 'item'] }));
    expect(summary.mock.calls.every(([q]) => !(q.chain ?? []).includes('seller'))).toBe(true);
    fireEvent.click(within(control).getByRole('button', { name: 'Remove Item' }));
    expect(within(control).queryByRole('button', { name: 'Add Salesperson' })).toBeNull();
    expect(within(control).getByRole('button', { name: 'Add Item' })).toBeTruthy();
  });

  it('offers the salesperson level only where profit read covers every branch in view', async () => {
    const first = me.branches?.[0]?.id ?? '';
    const partial: Me = { ...me, permission_scopes: { ...me.permission_scopes, 'retail.profit.read': { all_branches: false, branch_ids: [first] } } };
    writeChain(chainUser(partial), 'all', [...DEFAULT_CHAIN]);
    show(<AllSales />, ALL_BRANCHES, partial);
    await head(/^Test Branch Gayaza /);
    expect((screen.getByText('Group by').closest('details') as HTMLElement).textContent).not.toContain('Salesperson');
    expect(summary.mock.calls.every(([q]) => !(q.chain ?? []).includes('seller'))).toBe(true);
    cleanup();
    show(<AllSales />, first, partial);
    await head(/^2026 /);
    expect((screen.getByText('Group by').closest('details') as HTMLElement).textContent).toContain('Salesperson');
  });

  it('keeps the Group by under the user id and stores no email or phone number', async () => {
    const ann: Me = { ...me, user_id: 'user-id-ann', email: 'ann@example.test', phone_e164: '+256700000001' };
    show(<AllSales />, ALL_BRANCHES, ann);
    await head(/^Test Branch Gayaza /);
    const control = screen.getByText('Group by').closest('details') as HTMLElement;
    fireEvent.click(within(control).getByRole('button', { name: 'Remove Item' }));
    const stored = Array.from({ length: localStorage.length }, (_, i) => localStorage.key(i) ?? '');
    expect(stored.some((k) => k.includes('user-id-ann'))).toBe(true);
    const everything = stored.join('|') + stored.map((k) => localStorage.getItem(k)).join('|');
    expect(everything).not.toContain('ann@example.test');
    expect(everything).not.toContain('+256700000001');
    expect(chainUser({})).toBe('staff');
  });
});

describe('a level that is slow, fails or is empty', () => {
  it('says Loading, then shows the groups', async () => {
    let release: (s: SalesSummary) => void = () => undefined;
    summary.mockImplementationOnce(() => new Promise((resolve) => { release = resolve; }));
    show(<AllSales />);
    expect((await screen.findAllByText('Loading')).length).toBeGreaterThan(0);
    release(await answer({ level: 'branch' }));
    expect(await head(/^Test Branch Gayaza /)).toBeTruthy();
  });

  it('shows the problem with Try again, and the retry works', async () => {
    summary.mockRejectedValueOnce(new Error('The server is busy.'));
    show(<AllSales />);
    expect((await screen.findByRole('alert')).textContent).toContain('The server is busy.');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await head(/^Test Branch Gayaza /)).toBeTruthy();
  });

  it('says so when there are no sales, and when a group below has none', async () => {
    summary.mockImplementationOnce(async (q) => ({ ...(await answer(q)), items: [] }));
    show(<AllSales />);
    expect(await screen.findByText('No sales found.')).toBeTruthy();
    cleanup();
    summary.mockImplementation(async (q) => (q.level === 'year' ? { ...(await answer(q)), items: [] } : answer(q)));
    show(<AllSales />);
    fireEvent.click(await head(/^Test Branch Gayaza /));
    expect(await screen.findByText('No sales here.')).toBeTruthy();
  });

  it('asks for more groups with the offset the server gave, and for earlier periods with the date', async () => {
    summary.mockImplementation(async (q) => {
      if (q.level === 'branch') {
        return q.offset ? { ...(await answer(q)), items: [group('b3', 'Test Branch Third')] } : { ...(await answer(q)), has_more: true, next_offset: 50 };
      }
      return answer(q);
    });
    show(<AllSales />);
    await head(/^Test Branch Gayaza /);
    fireEvent.click(screen.getByRole('button', { name: 'Show more shops' }));
    expect(await head(/^Test Branch Third /)).toBeTruthy();
    expect(summary).toHaveBeenCalledWith(expect.objectContaining({ level: 'branch', offset: 50 }));
  });
});

describe('Credit sales', () => {
  it('uses its own remembered chain and does not list the payment methods', async () => {
    writeChain(chainUser(me), 'credit', ['seller']);
    show(<CreditSales />);
    const seller = await head(/^Test Seller One /);
    expect(seller.textContent).not.toContain('Cash');
    fireEvent.click(seller);
    await screen.findByText('Test Buyer One');
    expect(screen.getByRole('button', { name: 'Record payment' })).toBeTruthy();
    expect(summary).toHaveBeenCalledWith(expect.objectContaining({ level: 'seller', paymentMethod: 'credit' }));
    expect(readChain(chainUser(me), 'all')).toEqual([...DEFAULT_CHAIN]);
  });
});

describe('the Group by control', () => {
  it('reorders, drops and restores levels, closes what was open, and remembers the choice', async () => {
    show(<AllSales />);
    fireEvent.click(await head(/^Test Branch Gayaza /));
    const control = screen.getByText('Group by').closest('details') as HTMLElement;
    expect(control.textContent).toContain('Shop, Year, Month, Salesperson, Day, Product category, Item');
    fireEvent.click(within(control).getByRole('button', { name: 'Move Salesperson up' }));
    fireEvent.click(within(control).getByRole('button', { name: 'Remove Item' }));
    expect(control.textContent).toContain('Shop, Year, Salesperson, Month, Day, Product category');
    expect(readChain(chainUser(me), 'all')).toEqual(['branch', 'year', 'seller', 'month', 'day', 'category']);
    expect((await head(/^Test Branch Gayaza /)).getAttribute('aria-expanded')).toBe('false');

    fireEvent.click(within(control).getByRole('button', { name: 'Add Item' }));
    expect(readChain(chainUser(me), 'all')).toEqual(['branch', 'year', 'seller', 'month', 'day', 'category', 'item']);
    fireEvent.click(within(control).getByRole('button', { name: 'Use the usual order' }));
    expect(readChain(chainUser(me), 'all')).toEqual([...DEFAULT_CHAIN]);
    expect(within(control).queryByRole('button', { name: 'Use the usual order' })).toBeNull();
  });

  it('starts from the remembered chain, and from the usual order when storage is blocked', async () => {
    writeChain(chainUser(me), 'all', ['year', 'item']);
    show(<AllSales />);
    expect(await head(/^2026 /)).toBeTruthy();
    expect(summary).toHaveBeenCalledWith(expect.objectContaining({ level: 'year', chain: ['year', 'item'] }));
    cleanup();
    summary.mockClear();
    vi.stubGlobal('localStorage', { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); } });
    try {
      show(<AllSales />);
      expect(await head(/^Test Branch Gayaza /)).toBeTruthy();
      fireEvent.click(within(screen.getByText('Group by').closest('details') as HTMLElement).getByRole('button', { name: 'Remove Item' }));
      expect((screen.getByText('Group by').closest('details') as HTMLElement).textContent).toContain('Shop, Year, Month, Salesperson, Day, Product category');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('cannot take the last level away', async () => {
    writeChain(chainUser(me), 'all', ['day']);
    show(<AllSales />);
    const control = (await screen.findByText('Group by')).closest('details') as HTMLElement;
    expect((within(control).getByRole('button', { name: 'Remove Day' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
