// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { StockMovement, UsageSummaryQuery } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { summariseUsage } from '../../../api/retail-grouping-mock';
import { ALL_BRANCHES } from '../../../auth/branch';
import { StaffContext } from '../context';

// Past usage and damage grouped by shop, month, category and item with totals (issue #816, as the owner's old app did): the
// groups are the server's sums (GET /retail/usage/summary, here the mock's same rules over the rows), the cost only when the
// server sends it (retail.profit.read), the count and the quantity for everyone who may read the list. A fabricated set across
// two shops and two months; items and costs are fabricated.

vi.setConfig({ testTimeout: 20000 });

const summary = vi.fn();
const history = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail, usageSummary: (q: unknown) => summary(q), usageHistory: (q: unknown) => history(q),
      listProducts: () => Promise.resolve([{ id: 'p1', code: 'BU-01', description: 'Test bulb' }]),
    },
  };
});

const { UsageHistoryPage } = await import('./usage-history');

const admin = mockMe('admin');
const seller = mockMe('sales');
const a = admin.branches?.[0]?.id ?? '';
const b = admin.branches?.[1]?.id ?? '';

const move = (id: string, day: string, branch: string, product: 'p1' | 'p2', kind: 'usage' | 'damage', qty: string, cost: number): StockMovement => ({
  id, at: `${day}T09:00:00Z`, branch_id: branch, product_id: product, code: product === 'p1' ? 'BU-01' : 'PA-05', description: product === 'p1' ? 'Test bulb' : 'Test paint',
  unit: product === 'p1' ? 'piece' : 'litre', kind, qty: `-${qty}`, note: 'Test reason', unit_cost_minor: cost, line_cost_minor: Math.round(Number(qty) * cost),
});
// Head office: October 3,000 + 750, September 1,500. Second shop: October 6,000, September 3,000.
const rows = [
  move('m1', '2026-10-10', a, 'p1', 'usage', '2', 1500),
  move('m2', '2026-10-12', a, 'p2', 'damage', '0.5', 1500),
  move('m3', '2026-09-25', a, 'p1', 'usage', '1', 1500),
  move('m4', '2026-10-03', b, 'p1', 'usage', '4', 1500),
  move('m5', '2026-09-28', b, 'p2', 'damage', '2', 1500),
];

function arrange(withCost: boolean) {
  const shown = withCost ? rows : rows.map(({ unit_cost_minor: _u, line_cost_minor: _l, ...m }) => m);
  summary.mockImplementation((q: UsageSummaryQuery) => Promise.resolve(summariseUsage(shown, q, {
    cost: withCost,
    branchName: (id) => (id === a ? 'Test Branch A' : 'Test Branch B'),
    product: (id) => (id === 'p1' ? { name: 'Test bulb', categoryId: 'c1', categoryName: 'Test lights', unit: 'piece' } : { name: 'Test paint', categoryId: 'c2', categoryName: 'Test paints', unit: 'litre' }),
    day: (m) => (m.at ?? '').slice(0, 10),
  })));
  history.mockImplementation((q: { productId?: string; branchId?: string; from: string; to: string }) => Promise.resolve({
    items: shown.filter((m) => (!q.productId || m.product_id === q.productId) && (!q.branchId || m.branch_id === q.branchId) && (m.at ?? '') >= q.from && (m.at ?? '').slice(0, 10) <= q.to),
  }));
}

function show(me: typeof admin): ReactElement {
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: ALL_BRANCHES }}><UsageHistoryPage /></StaffContext.Provider>
    </QueryClientProvider>
  );
}

const head = async (title: RegExp, scope: HTMLElement = document.body) => {
  await waitFor(() => expect(within(scope).getAllByRole('button', { name: title }).some((el) => el.classList.contains('rt-group-head'))).toBe(true));
  return within(scope).getAllByRole('button', { name: title }).find((el) => el.classList.contains('rt-group-head')) as HTMLElement;
};
const bodyOf = (button: HTMLElement) => button.closest('li.rt-group') as HTMLElement;

beforeAll(() => {
  // Only the date: the last 30 days then reach back to 20 September, so both months are in the list.
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-20T09:00:00Z'));
});

afterAll(() => {
  vi.useRealTimers();
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  summary.mockReset();
  history.mockReset();
});

describe('Past usage and damage grouped (issue #816)', () => {
  it('totals each shop, month, category and item of a fabricated set across two shops and two months', async () => {
    arrange(true);
    render(show(admin));
    fireEvent.click(screen.getByRole('radio', { name: 'Grouped' }));
    const shopA = await head(/^Test Branch A/);
    expect(shopA.textContent).toContain('UGX 5,250');
    expect(shopA.textContent).toContain('3 records: 2 used, 1 damaged, at buying price');
    expect((await head(/^Test Branch B/)).textContent).toContain('UGX 9,000');
    expect(summary.mock.calls[0]?.[0]).toMatchObject({ level: 'branch', chain: ['branch', 'month', 'category', 'item'], from: '2026-09-20', to: '2026-10-20' });

    fireEvent.click(shopA);
    const october = await head(/^October 2026/, bodyOf(shopA));
    expect(october.textContent).toContain('UGX 3,750');
    expect((await head(/^September 2026/, bodyOf(shopA))).textContent).toContain('UGX 1,500');
    fireEvent.click(october);
    const lights = await head(/^Test lights/, bodyOf(october));
    expect(lights.textContent).toContain('UGX 3,000');
    expect((await head(/^Test paints/, bodyOf(october))).textContent).toContain('UGX 750');
    fireEvent.click(lights);
    const bulb = await head(/^Test bulb/, bodyOf(lights));
    expect(bulb.textContent).toContain('2 piece taken out, 1 record: 1 used');
    expect(bulb.textContent).toContain('UGX 3,000');
    // The item opens onto the list's own rows, read with the shop, the month and the item opened above.
    fireEvent.click(bulb);
    await waitFor(() => expect(within(bodyOf(bulb)).getAllByTestId('usage-row')).toHaveLength(1));
    expect(history).toHaveBeenLastCalledWith(expect.objectContaining({ branchId: a, productId: 'p1', from: '2026-10-01', to: '2026-10-20' }));
  });

  // JUSTIFICATION-A3: the lead's note on the review of 178bacf asked for this DOM test (chain ending at category, flat search).
  // Rows under a group carry every level opened above and the flat search, and a chain that ends at category (one stored
  // earlier) is never used: the levels are always shop, month, category and item.
  it('reads the rows with every level and the flat search, and never stops the chain at category', async () => {
    arrange(true);
    localStorage.setItem(`bms.listChain.usage.${admin.user_id}`, JSON.stringify(['branch', 'month', 'category']));
    render(show(admin));
    fireEvent.change(screen.getByLabelText('Item (type part of its name or code)'), { target: { value: 'bulb' } });
    fireEvent.change(await screen.findByLabelText('Choose the item'), { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('radio', { name: 'Grouped' }));
    const shopA = await head(/^Test Branch A/);
    // Only the bulb counts once the search chose it: 2 in October and 1 in September at head office.
    await waitFor(() => expect(shopA.textContent).toContain('UGX 4,500'));
    fireEvent.click(shopA);
    const october = await head(/^October 2026/, bodyOf(shopA));
    fireEvent.click(october);
    const lights = await head(/^Test lights/, bodyOf(october));
    expect(bodyOf(october).textContent).not.toContain('Test paints');
    fireEvent.click(lights);
    const bulb = await head(/^Test bulb/, bodyOf(lights));
    fireEvent.click(bulb);
    await waitFor(() => expect(within(bodyOf(bulb)).getAllByTestId('usage-row')).toHaveLength(1));
    for (const [q] of summary.mock.calls as [UsageSummaryQuery][]) {
      expect(q.chain).toEqual(['branch', 'month', 'category', 'item']);
    }
    expect(summary.mock.calls.at(-1)?.[0]).toMatchObject({ level: 'item', branchId: a, categoryId: 'c1', itemId: 'p1', from: '2026-10-01', to: '2026-10-20' });
    expect(history).toHaveBeenLastCalledWith(expect.objectContaining({ branchId: a, productId: 'p1', from: '2026-10-01', to: '2026-10-20' }));
  });

  it('shows a seller without the profit permission the counts and quantities but no cost total', async () => {
    arrange(false);
    render(show(seller));
    fireEvent.click(screen.getByRole('radio', { name: 'Grouped' }));
    const shopB = await head(/^Test Branch B/);
    expect(shopB.textContent).toContain('2 records: 1 used, 1 damaged');
    expect(shopB.textContent).not.toContain('UGX');
    fireEvent.click(shopB);
    const october = await head(/^October 2026/, bodyOf(shopB));
    fireEvent.click(october);
    const lights = await head(/^Test lights/, bodyOf(october));
    fireEvent.click(lights);
    expect((await head(/^Test bulb/, bodyOf(lights))).textContent).toContain('4 piece taken out');
    expect(document.querySelector('.rt-group-total')).toBeNull();
    expect(document.body.textContent).not.toContain('at buying price');
  });
});
