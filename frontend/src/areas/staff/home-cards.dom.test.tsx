// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen, within } from '@testing-library/react';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../api/client';
import { mockMe } from '../../api/retail-mock';
import { router as appRouter } from '../../app/router';
import { StaffContext } from './context';
import { HomeCards, LENDING_APPROVALS } from './home-cards';

// Home's card per module (issue #471): Shop and Lending for a business with both, only its own card for a business with one, and
// each figure only for the permissions its endpoint requires. Every figure and name is fabricated.

const calls = { dashboard: vi.fn(), board: vi.fn(), summary: vi.fn(), approvals: vi.fn() };

vi.mock('../../api/retail-analytics', async (original) => {
  const real = await original<typeof import('../../api/retail-analytics')>();
  return { ...real, retailAnalytics: { ...real.retailAnalytics, dashboard: (...a: unknown[]) => calls.dashboard(...a) } };
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

beforeEach(() => {
  calls.dashboard.mockResolvedValue({ today: { sales_minor: 125_000, sale_count: 7 }, stock: { low_stock: 3 } });
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
    expect(calls.dashboard).toHaveBeenCalledWith(expect.objectContaining({ period: 'today' }));
    const order = Array.from(document.querySelectorAll('.home-card')).map((c) => c.getAttribute('aria-labelledby'));
    expect(order).toEqual(['home-shop', 'home-lending']);
  });

  it('shows only the Lending card to a lending business, and asks nothing of retail', async () => {
    show(lender);
    expect(await screen.findByRole('article', { name: 'Lending' })).toBeTruthy();
    expect(card('Shop')).toBeNull();
    expect(calls.dashboard).not.toHaveBeenCalled();
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
    expect(calls.dashboard).not.toHaveBeenCalled();
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
    expect(calls.dashboard).not.toHaveBeenCalled();
    expect(calls.board).not.toHaveBeenCalled();
  });

  it('shows only the open orders to a session with retail.order.manage alone', async () => {
    show({ ...admin, permissions: ['retail.order.manage'], permission_scopes: undefined } as unknown as Me);
    const shop = await screen.findByRole('article', { name: 'Shop' });
    expect(await within(shop).findByText('4')).toBeTruthy();
    expect(within(shop).queryByText('Sales today')).toBeNull();
    expect(within(shop).queryByText('Low on stock')).toBeNull();
    expect(calls.dashboard).not.toHaveBeenCalled();
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
    show(partial, b as string);
    const shop = await screen.findByRole('article', { name: 'Shop' });
    expect(await within(shop).findByText('4')).toBeTruthy();
    expect(within(shop).queryByText('Sales today')).toBeNull();
    expect(calls.dashboard).not.toHaveBeenCalled();
    cleanup();
    show(partial, a as string);
    expect(await screen.findByText(/7 sales/)).toBeTruthy();
    expect(calls.dashboard).toHaveBeenCalledWith(expect.objectContaining({ branchId: a }));
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

  it('stacks the cards in one column on a 360px phone: two need at least 576px', () => {
    const css = readFileSync(join(process.cwd(), 'src/app/ui/surfaces.css'), 'utf8');
    const rule = /\.home-cards \{[^}]*grid-template-columns: repeat\(auto-fit, minmax\(min\(100%, (\d+)px\), 1fr\)\)/.exec(css);
    expect(rule).not.toBeNull();
    expect(2 * Number(rule?.[1]) + 16).toBeGreaterThan(360 - 2 * 16);
  });
});
