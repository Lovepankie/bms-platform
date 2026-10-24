// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../api/client';
import { mockMe } from '../../api/retail-mock';
import { router as appRouter } from '../../app/router';
import { StaffContext } from './context';
import { HomeCards, LENDING_APPROVALS, homeParts, homeSummaryQuery } from './home-cards';
import { STAFF_SECTIONS } from './menu';

// Home's card per module (issue #471): Shop and Lending for a business with both, only its own card for a business with one, and
// each figure only for the permissions its endpoint requires; the shop's summary cards after the Shop card (issue #485), all read
// from the one Home summary. Every figure and name is fabricated.

const calls = { home: vi.fn(), board: vi.fn(), summary: vi.fn(), approvals: vi.fn() };

vi.mock('../../api/retail-analytics', async (original) => {
  const real = await original<typeof import('../../api/retail-analytics')>();
  return { ...real, retailAnalytics: { ...real.retailAnalytics, home: (...a: unknown[]) => calls.home(...a) } };
});
vi.mock('../../api/retail-orders', async (original) => {
  const real = await original<typeof import('../../api/retail-orders')>();
  return { ...real, retailOrders: { ...real.retailOrders, board: (...a: unknown[]) => calls.board(...a) } };
});
vi.mock('../../api/lending', async (original) => {
  const real = await original<typeof import('../../api/lending')>();
  return { ...real, lending: { ...real.lending, homeSummary: (...a: unknown[]) => calls.summary(...a) } };
});
vi.mock('../../api/client', async (original) => ({
  ...(await original<typeof import('../../api/client')>()),
  listPendingApprovals: (...a: unknown[]) => calls.approvals(...a),
}));

const LENDING = ['lending.loans.read', 'lending.members.read', 'core.approvals.read'];
const admin = mockMe('admin');
const both = { ...admin, permissions: [...(admin.permissions ?? []), ...LENDING], modules: ['lending', 'retail'] } as unknown as Me;
const lender = { ...both, permissions: LENDING, modules: ['lending'] } as unknown as Me;

function show(me: Me, branch: string | null = me.branches?.[0]?.id ?? null) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch }}>
          <HomeCards />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const BACKEND = join(process.cwd(), '../backend/src/main/');

/** A seeded role's permissions, read from the grants of the migrations, as a new tenant gets them. */
function seeded(role: string): string[] {
  const dir = join(BACKEND, 'resources/db/migration');
  const grant = new RegExp(`\\('${role}',\\s*'([a-z_.]+)'\\)`, 'g');
  const found = new Set<string>();
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.sql'))) {
    for (const m of readFileSync(join(dir, file), 'utf8').matchAll(grant)) found.add(m[1] as string);
  }
  return [...found];
}

/** The TYPE of every approval action the backend's lending packages register. */
function lendingActionTypes(): string[] {
  const types: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (entry.name.endsWith('.java')) {
        const java = readFileSync(path, 'utf8');
        const type = /static final String TYPE = "([a-z_]+)"/.exec(java);
        if (/implements\s+[\w.,\s]*ApprovalAction/.test(java) && type) types.push(type[1] as string);
      }
    }
  };
  walk(join(BACKEND, 'java/com/rincoltech/bms/lending'));
  return types;
}

const card = (name: string) => screen.queryByRole('article', { name });

const HOME = {
  date: '2026-10-23',
  currency: 'UGX',
  week_from: '2026-10-19',
  sales_today: { sales_minor: 125_000, sale_count: 7 },
  takings: {
    total_minor: 98_000,
    methods: [
      { method: 'cash', amount_minor: 60_000, sales_minor: 50_000, payments_minor: 10_000, fees_minor: 0, out_minor: 0 },
      { method: 'mobile_money', amount_minor: 35_000, sales_minor: 35_000, payments_minor: 0, fees_minor: 0, out_minor: 0 },
      { method: 'bank', amount_minor: 3_000, sales_minor: 0, payments_minor: 3_000, fees_minor: 0, out_minor: 0 },
    ],
  },
  credit_owed: { owed_minor: 450_000, customers: 3, sales: 5, oldest_due_date: '2026-10-02' },
  top_items: {
    today: [
      { item_id: 'i1', code: 'T-1', label: 'Test Sugar 1kg', unit: 'bag', qty: '4.000', sales_minor: 24_000 },
      { item_id: 'i2', code: 'T-2', label: 'Test Soap', unit: 'bar', qty: '1.500', sales_minor: 3_000 },
    ],
    week: [{ item_id: 'i3', code: 'T-3', label: 'Test Rice 5kg', unit: 'bag', qty: '12.000', sales_minor: 300_000 }],
  },
  branches: [
    { branch_id: 'b1', name: 'Test Shop One', sales_minor: 100_000, sale_count: 5 },
    { branch_id: 'b2', name: 'Test Shop Two', sales_minor: 0, sale_count: 0 },
  ],
  low_stock: 3,
  open_orders: 4,
};

/** What the server answers a session without retail.sale.all_sellers: no money parts. */
const NO_MONEY = { date: HOME.date, currency: 'UGX', week_from: HOME.week_from, low_stock: 3, open_orders: 4 };

beforeEach(() => {
  calls.home.mockResolvedValue(HOME);
  calls.board.mockResolvedValue({ ordered: 2, packed: 1, out_for_delivery: 1, delivered_today: 5 });
  calls.summary.mockResolvedValue({ currency: 'UGX', due_today_count: 4, due_today_minor: 340_000, in_arrears_count: 2, in_arrears_minor: 910_000 });
  // The inbox answers only the action types asked for, as the server does.
  calls.approvals.mockResolvedValue({ items: [{ action_type: 'loan_disbursement' }, { action_type: 'savings_withdrawal' }] });
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('the Home cards (issue #471)', () => {
  it('shows a Shop card and a Lending card for a business with both modules', async () => {
    show(both);
    const shop = await screen.findByRole('article', { name: 'Shop' });
    expect(await within(shop).findByText(/7 sales/)).toBeTruthy();
    expect(within(shop).getByText('3 items')).toBeTruthy();
    expect(within(shop).getByText('4')).toBeTruthy();
    const lend = card('Lending') as HTMLElement;
    expect(await within(lend).findByText('4 loans')).toBeTruthy();
    expect(lend.textContent).toContain('2 loans');
    expect(lend.textContent).toContain('910,000');
    // Two lending requests wait; the inbox is asked for the lending action types only.
    expect(within(lend).getByRole('link', { name: '2' }).getAttribute('href')).toBe('/staff/approvals');
    expect(calls.approvals).toHaveBeenCalledWith(expect.anything(), LENDING_APPROVALS);
    // One read for every shop figure, and none of the orders board.
    expect(calls.home).toHaveBeenCalledTimes(1);
    expect(calls.board).not.toHaveBeenCalled();
    const order = Array.from(document.querySelectorAll('.home-card')).map((c) => c.getAttribute('aria-labelledby'));
    // One branch is chosen in the header, so there is no line per shop.
    expect(order).toEqual(['home-shop', 'home-takings', 'home-credit', 'home-top', 'home-lending']);
    expect(calls.home).toHaveBeenCalledWith(both.branches?.[0]?.id, expect.anything());
  });

  it('shows only the Lending card to a lending business, and asks nothing of retail', async () => {
    show(lender);
    expect(await screen.findByRole('article', { name: 'Lending' })).toBeTruthy();
    expect(card('Shop')).toBeNull();
    expect(calls.home).not.toHaveBeenCalled();
    expect(calls.board).not.toHaveBeenCalled();
  });

  it('shows only the Shop card to a retail business, and asks nothing of lending', async () => {
    show(admin as unknown as Me);
    expect(await screen.findByRole('article', { name: 'Shop' })).toBeTruthy();
    expect(card('Lending')).toBeNull();
    expect(calls.summary).not.toHaveBeenCalled();
    expect(calls.approvals).not.toHaveBeenCalled();
  });

  it('leaves out each figure the session may not see', async () => {
    // A seller sees only their own sales (ADR-055), so no shop-wide total, no stock count and no orders board without the orders permission.
    const seller = { ...both, permissions: ['retail.sale.create', 'retail.sale.read', 'lending.loans.read'] } as unknown as Me;
    show(seller);
    const lend = await screen.findByRole('article', { name: 'Lending' });
    expect(within(lend).queryByText('Waiting for approval')).toBeNull();
    expect(await within(lend).findByText('In arrears')).toBeTruthy();
    expect(card('Shop')).toBeNull();
    expect(calls.home).not.toHaveBeenCalled();
    expect(calls.approvals).not.toHaveBeenCalled();
    cleanup();
    calls.summary.mockClear();
    const approver = { ...both, permissions: ['core.approvals.read'], modules: ['lending'] } as unknown as Me;
    show(approver);
    const only = await screen.findByRole('article', { name: 'Lending' });
    expect(within(only).queryByText('Due today')).toBeNull();
    expect(calls.summary).not.toHaveBeenCalled();
  });

  it('says so when a figure cannot be loaded, and keeps the others', async () => {
    calls.summary.mockRejectedValue(new Error('down'));
    show(both);
    const lend = await screen.findByRole('article', { name: 'Lending' });
    expect((await within(lend).findAllByText('Could not load')).length).toBe(2);
    expect(await within(lend).findByRole('link', { name: '2' })).toBeTruthy();
  });

  it('asks the inbox for exactly the approval actions the lending module registers', () => {
    const types = lendingActionTypes();
    expect(types.length).toBeGreaterThan(5);
    expect([...LENDING_APPROVALS].sort()).toEqual(types.sort());
  });

  it.each(['cashier', 'loan_officer'])('gives the seeded %s the whole Lending card and no Shop card', async (role) => {
    const permissions = seeded(role);
    expect(permissions).toContain('lending.loans.read');
    show({ ...both, permissions, permission_scopes: undefined } as unknown as Me);
    const lend = await screen.findByRole('article', { name: 'Lending' });
    expect(await within(lend).findByText('4 loans')).toBeTruthy();
    expect(within(lend).getByText('In arrears')).toBeTruthy();
    expect(await within(lend).findByRole('link', { name: '2' })).toBeTruthy();
    expect(card('Shop')).toBeNull();
    expect(calls.home).not.toHaveBeenCalled();
    expect(calls.board).not.toHaveBeenCalled();
  });

  it('shows only the open orders to a session with retail.order.manage alone', async () => {
    show({ ...admin, permissions: ['retail.order.manage'], permission_scopes: undefined } as unknown as Me);
    const shop = await screen.findByRole('article', { name: 'Shop' });
    expect(await within(shop).findByText('4')).toBeTruthy();
    expect(within(shop).queryByText('Sales today')).toBeNull();
    expect(within(shop).queryByText('Low on stock')).toBeNull();
    expect(calls.home).not.toHaveBeenCalled();
  });

  it('shows the sales but not the stock count to a session without retail.stock.read', async () => {
    show({ ...admin, permissions: ['retail.sale.read', 'retail.sale.all_sellers'], permission_scopes: undefined } as unknown as Me);
    const shop = await screen.findByRole('article', { name: 'Shop' });
    expect(await within(shop).findByText(/7 sales/)).toBeTruthy();
    expect(within(shop).queryByText('Low on stock')).toBeNull();
    expect(within(shop).queryByText('Open customer orders')).toBeNull();
  });

  it('asks for the shop total only in a branch where the session sees every seller (ADR-055)', async () => {
    const [a, b] = (admin.branches ?? []).map((x) => x.id as string);
    const scope = (ids: string[]) => ({ all_branches: false, branch_ids: ids });
    const partial = {
      ...admin,
      permissions: ['retail.sale.read', 'retail.sale.all_sellers', 'retail.order.manage'],
      permission_scopes: { 'retail.sale.read': scope([a as string, b as string]), 'retail.sale.all_sellers': scope([a as string]), 'retail.order.manage': scope([a as string, b as string]) },
    } as unknown as Me;
    // In branch b the summary is still read for the open orders, the server leaves the money out, and no money card is drawn.
    calls.home.mockResolvedValue(NO_MONEY);
    show(partial, b as string);
    const shop = await screen.findByRole('article', { name: 'Shop' });
    expect(await within(shop).findByText('4')).toBeTruthy();
    expect(within(shop).queryByText('Sales today')).toBeNull();
    expect(card('Takings today')).toBeNull();
    expect(calls.home).toHaveBeenCalledWith(b, expect.anything());
    cleanup();
    calls.home.mockResolvedValue(HOME);
    show(partial, a as string);
    expect(await screen.findByText(/7 sales/)).toBeTruthy();
    expect(calls.home).toHaveBeenCalledWith(a, expect.anything());
  });

  it('shows the stock count only in a branch where the session reads stock', async () => {
    const [a, b] = (admin.branches ?? []).map((x) => x.id as string);
    const scope = (ids: string[]) => ({ all_branches: false, branch_ids: ids });
    const partial = {
      ...admin,
      permissions: ['retail.sale.read', 'retail.sale.all_sellers', 'retail.stock.read'],
      permission_scopes: { 'retail.sale.read': scope([a as string, b as string]), 'retail.sale.all_sellers': scope([a as string, b as string]), 'retail.stock.read': scope([a as string]) },
    } as unknown as Me;
    show(partial, b as string);
    const shop = await screen.findByRole('article', { name: 'Shop' });
    expect(await within(shop).findByText(/7 sales/)).toBeTruthy();
    expect(within(shop).queryByText('Low on stock')).toBeNull();
    cleanup();
    show(partial, a as string);
    expect(await within(await screen.findByRole('article', { name: 'Shop' })).findByText('Low on stock')).toBeTruthy();
  });

  it('heads each card with the icon of its heading in the side menu, no two alike (issue #483)', async () => {
    show(both);
    await screen.findByRole('article', { name: 'Lending' });
    // The module cards; the shop's summary cards after Shop carry no icon of their own.
    const cards = screen.getAllByRole('article').filter((c) => !c.classList.contains('home-summary'));
    expect(cards.map((c) => c.querySelector('h2')?.textContent)).toEqual(STAFF_SECTIONS.map((s) => s.label));
    const drawn = cards.map((c) => c.querySelector('h2 svg')?.outerHTML);
    expect(drawn).toEqual(STAFF_SECTIONS.map((s) => renderToStaticMarkup(<>{s.icon}</>)));
    expect(new Set(drawn).size).toBe(cards.length);
  });

  it('stacks the cards in one column on a 360px phone: two need at least 576px', () => {
    const css = readFileSync(join(process.cwd(), 'src/app/ui/surfaces.css'), 'utf8');
    const rule = /\.home-cards \{[^}]*grid-template-columns: repeat\(auto-fit, minmax\(min\(100%, (\d+)px\), 1fr\)\)/.exec(css);
    expect(rule).not.toBeNull();
    expect(2 * Number(rule?.[1]) + 16).toBeGreaterThan(360 - 2 * 16);
  });
});

describe('the shop summary cards (issue #485)', () => {
  const text = (name: string) => (card(name) as HTMLElement).textContent ?? '';

  it('splits today\'s takings by cash, mobile money and any other way, with the total', async () => {
    show(both);
    const takings = await screen.findByRole('article', { name: 'Takings today' });
    expect(await within(takings).findByText('Cash')).toBeTruthy();
    expect(within(takings).getByText('Mobile money')).toBeTruthy();
    expect(within(takings).getByText('Bank')).toBeTruthy();
    expect(takings.textContent).toContain('60,000');
    expect(takings.textContent).toContain('35,000');
    expect(takings.textContent).toContain('98,000');
    // The admin may close the till, so the card opens the cash summary.
    expect(within(takings).getByRole('link').getAttribute('href')).toBe('/staff/retail/cash-summary');
  });

  it('shows what customers owe, how many, and the oldest due date, linking to credit control', async () => {
    show(both);
    const credit = await screen.findByRole('article', { name: 'Credit owed' });
    expect(await within(credit).findByText('3 customers')).toBeTruthy();
    expect(credit.textContent).toContain('450,000');
    expect(credit.textContent).toContain('2 Oct 2026');
    expect(within(credit).getByRole('link').getAttribute('href')).toBe('/staff/retail/credit-control');
  });

  it('lists the top items of today and switches to this week', async () => {
    show(both);
    const top = await screen.findByRole('article', { name: 'Top items' });
    expect(await within(top).findByText(/Test Sugar 1kg/)).toBeTruthy();
    expect(top.textContent).toContain('4 bag');
    expect(top.textContent).toContain('1.5 bar');
    expect(within(top).getByRole('button', { name: 'Today' }).getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(within(top).getByRole('button', { name: 'This week' }));
    expect(within(top).getByText(/Test Rice 5kg/)).toBeTruthy();
    expect(within(top).queryByText(/Test Sugar 1kg/)).toBeNull();
    expect(within(top).getByRole('button', { name: 'This week' }).getAttribute('aria-pressed')).toBe('true');
    expect(within(top).getByRole('link').getAttribute('href')).toBe('/staff/retail/sales-analysis');
    // The switch reads nothing more: both lists came in the one summary.
    expect(calls.home).toHaveBeenCalledTimes(1);
  });

  it('gives one line per shop over All branches to a session that sees more than one', async () => {
    show(both, 'all');
    const lines = await screen.findByRole('article', { name: 'Each shop today' });
    const items = await within(lines).findAllByRole('listitem');
    expect(items.map((i) => i.textContent)).toEqual([expect.stringContaining('Test Shop One'), expect.stringContaining('Test Shop Two')]);
    expect(items[0]?.textContent).toContain('100,000');
    expect(items[0]?.textContent).toContain('5 sales');
    expect(items[1]?.textContent).toContain('No sales yet');
    expect(within(lines).getByRole('link').getAttribute('href')).toBe('/staff/retail');
    expect(calls.home).toHaveBeenCalledWith(undefined, expect.anything());
  });

  it('draws no line per shop for a session that sees one branch, or when the server leaves the lines out', async () => {
    const [a] = (admin.branches ?? []).map((x) => x.id as string);
    const oneShop = {
      ...both,
      permission_scopes: { 'retail.sale.all_sellers': { all_branches: false, branch_ids: [a] }, 'retail.sale.read': { all_branches: false, branch_ids: [a] } },
    } as unknown as Me;
    show(oneShop, 'all');
    expect(await screen.findByRole('article', { name: 'Takings today' })).toBeTruthy();
    expect(card('Each shop today')).toBeNull();
    cleanup();
    calls.home.mockResolvedValue({ ...HOME, branches: undefined });
    show(both, 'all');
    await screen.findByText(/7 sales/);
    expect(card('Each shop today')).toBeNull();
  });

  it('shows no money card to a role without the money permission, and still the stock count and the orders', async () => {
    calls.home.mockResolvedValue(NO_MONEY);
    show({ ...admin, permissions: ['retail.sale.create', 'retail.sale.read', 'retail.stock.read', 'retail.order.manage'], permission_scopes: undefined } as unknown as Me);
    const shop = await screen.findByRole('article', { name: 'Shop' });
    expect(await within(shop).findByText('3 items')).toBeTruthy();
    expect(within(shop).getByText('4')).toBeTruthy();
    expect(within(shop).queryByText('Sales today')).toBeNull();
    for (const name of ['Takings today', 'Credit owed', 'Top items', 'Each shop today']) expect(card(name)).toBeNull();
    expect(calls.home).toHaveBeenCalledTimes(1);
    expect(calls.board).not.toHaveBeenCalled();
  });

  it('says plainly when nothing was taken, nobody owes and nothing sold', async () => {
    calls.home.mockResolvedValue({
      ...HOME,
      sales_today: { sales_minor: 0, sale_count: 0 },
      takings: { total_minor: 0, methods: [{ method: 'cash', amount_minor: 0, sales_minor: 0, payments_minor: 0, fees_minor: 0, out_minor: 0 }, { method: 'mobile_money', amount_minor: 0, sales_minor: 0, payments_minor: 0, fees_minor: 0, out_minor: 0 }] },
      credit_owed: { owed_minor: 0, customers: 0, sales: 0 },
      top_items: { today: [], week: [] },
    });
    show(both);
    expect(await screen.findByText('No money taken yet today.')).toBeTruthy();
    expect(text('Credit owed')).toContain('No customer owes you money.');
    expect(text('Top items')).toContain('Nothing sold yet today.');
    fireEvent.click(within(card('Top items') as HTMLElement).getByRole('button', { name: 'This week' }));
    expect(text('Top items')).toContain('Nothing sold yet this week.');
  });

  it('takes a card away when the server leaves its part out, rather than saying there is nothing', async () => {
    // The session's own view says it may see the money, yet the server answers without it (scopes changed since sign-in).
    calls.home.mockResolvedValue(NO_MONEY);
    show(both, 'all');
    const shop = await screen.findByRole('article', { name: 'Shop' });
    expect(await within(shop).findByText('3 items')).toBeTruthy();
    expect(within(shop).queryByText('Sales today')).toBeNull();
    for (const name of ['Takings today', 'Credit owed', 'Top items', 'Each shop today']) expect(card(name)).toBeNull();
    expect(screen.queryByText('No money taken yet today.')).toBeNull();
    expect(screen.queryByText('No customer owes you money.')).toBeNull();
    cleanup();
    // One part missing takes only its own card.
    calls.home.mockResolvedValue({ ...HOME, credit_owed: undefined });
    show(both);
    expect(await screen.findByRole('article', { name: 'Takings today' })).toBeTruthy();
    await screen.findByText('Cash');
    expect(card('Credit owed')).toBeNull();
  });

  it('draws no Takings card for a session that sees everybody\'s sales but not the cash summary', async () => {
    const supervisor = { ...admin, permissions: ['retail.sale.read', 'retail.sale.all_sellers', 'retail.stock.read'], permission_scopes: undefined } as unknown as Me;
    calls.home.mockResolvedValue({ ...HOME, takings: undefined });
    show(supervisor);
    expect(card('Takings today')).toBeNull();
    expect(await screen.findByRole('article', { name: 'Credit owed' })).toBeTruthy();
    expect(card('Takings today')).toBeNull();
  });

  it('does not say nothing was taken when money came in and went out again', async () => {
    calls.home.mockResolvedValue({
      ...HOME,
      takings: { total_minor: 0, methods: [{ method: 'cash', amount_minor: 0, sales_minor: 500, payments_minor: 0, fees_minor: 0, out_minor: 500 }, { method: 'mobile_money', amount_minor: 0, sales_minor: 0, payments_minor: 0, fees_minor: 0, out_minor: 0 }] },
    });
    show(both);
    const takings = await screen.findByRole('article', { name: 'Takings today' });
    expect(await within(takings).findByText('Cash')).toBeTruthy();
    expect(within(takings).queryByText('No money taken yet today.')).toBeNull();
  });

  it('says so in each card when the summary cannot be loaded', async () => {
    calls.home.mockRejectedValue(new Error('down'));
    show(both);
    const takings = await screen.findByRole('article', { name: 'Takings today' });
    expect(await within(takings).findByText('Could not load')).toBeTruthy();
    expect(text('Credit owed')).toContain('Could not load');
  });
});

describe('Home summary freshness', () => {
  it('has no freshness window, so Home reads again on every visit and shows a sale just recorded (#544)', () => {
    const me = mockMe('admin');
    expect(homeSummaryQuery(homeParts(me, null), null)).not.toHaveProperty('staleTime');
  });
});
