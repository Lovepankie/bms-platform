// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter, defaultParseSearch } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { SlowMovers } from '../../../api/retail-analytics';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Slow movers on Stock health (issue #414): the section, its Move stock link, the money tied up only where the body
// carries it, the settings card, and the Move stock form filled in from the link. Fabricated names and figures.

const BRANCH_A = '00000000-0000-4000-8000-0000000000a1';
const BRANCH_B = '00000000-0000-4000-8000-0000000000a2';
const PRODUCT = '00000000-0000-4000-8000-000000000p01';

const change = vi.fn();
const findProducts = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      changeCapabilities: (...args: unknown[]) => change(...args),
      listProducts: (...args: unknown[]) => findProducts(...args),
    },
  };
});

const { SlowMoversSection, SlowMoverSetting } = await import('./slow-movers');
const { TransferForm, prefillOf } = await import('./transfer');

afterEach(() => {
  cleanup();
  change.mockReset();
  findProducts.mockReset();
  sessionStorage.clear();
});

function show(ui: ReactElement, me: Me = mockMe('admin')) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient()}>
        <StaffContext.Provider value={{ me, branch: BRANCH_A }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const nameOf = (id?: string) => (id === BRANCH_A ? 'Test Branch A' : 'Test Branch B');

const row = {
  branch_id: BRANCH_A, product_id: PRODUCT, code: 'TI-01', description: 'Test Item 01', unit: 'piece',
  qty_on_hand: '40.000', sold_last90: '3.000', last_sold_on: '2026-10-11', months_of_stock: '40.0', capped: false,
};

const withCost: SlowMovers = {
  days: 90, slow_months: 4, min_sold: 0, total: 2, value_at_cost_minor: 6000,
  items: [
    { ...row, value_at_cost_minor: 4000, better_at: { branch_id: BRANCH_B, sold_last90: '30.000', suggested_qty: '8.000' } },
    { ...row, branch_id: BRANCH_B, product_id: 'p2', code: 'TI-02', description: 'Test Item 02', months_of_stock: '120.0', capped: true, value_at_cost_minor: 2000 },
  ],
};

/** The body a caller without retail.profit.read gets: the server leaves every cost field out. */
const plain = JSON.parse(JSON.stringify(withCost, (key, value) => (/cost/.test(key) ? undefined : value))) as SlowMovers;

describe('slow movers section', () => {
  it('shows months to sell, last sold, money tied up and the total where the body carries them', () => {
    show(<SlowMoversSection data={withCost} nameOf={nameOf} />);
    expect(screen.getByRole('heading', { name: 'Slow movers: more than 4 months of stock' })).toBeTruthy();
    expect(screen.getByText('40.0')).toBeTruthy();
    expect(screen.getByText('over 120.0')).toBeTruthy();
    expect(screen.getAllByText('2026-10-11').length).toBeGreaterThan(0);
    expect(screen.getByText('Money tied up')).toBeTruthy();
    expect(screen.getByText('UGX 4,000')).toBeTruthy();
    expect(screen.getByText(/Money tied up in slow stock: UGX 6,000/)).toBeTruthy();
  });

  it('says a new item is paced over the days it has been in the branch', () => {
    const fresh: SlowMovers = { ...withCost, items: [{ ...row, days_counted: 30, months_of_stock: '49.0' }, { ...row, product_id: 'p3', days_counted: 90 }] };
    show(<SlowMoversSection data={fresh} nameOf={nameOf} />);
    expect(screen.getByText('49.0')).toBeTruthy();
    expect(screen.getAllByText('new, pace of 30 days')).toHaveLength(1);
  });

  it('never shows money tied up to a caller whose body has no cost', () => {
    show(<SlowMoversSection data={plain} nameOf={nameOf} />, mockMe('sales'));
    expect(document.body.textContent).not.toMatch(/tied up|cost/i);
    expect(document.body.textContent).not.toContain('4,000');
    expect(screen.getByText('40.0')).toBeTruthy();
  });

  it('says where the item sells better and links to Move stock filled in', () => {
    show(<SlowMoversSection data={withCost} nameOf={nameOf} />);
    expect(screen.getByText(/Sells better at Test Branch B \(30 sold in 90 days\)\. Move 8 piece there\./)).toBeTruthy();
    const href = screen.getByRole('link', { name: 'Move 8 Test Item 01' }).getAttribute('href') ?? '';
    expect(href.startsWith('/staff/retail/transfer?')).toBe(true);
    // Read back the way the router reads the address of the page it opens.
    expect(prefillOf(defaultParseSearch(href.slice(href.indexOf('?'))))).toEqual({ from: BRANCH_A, to: BRANCH_B, product: PRODUCT, code: 'TI-01', qty: '8' });
  });

  it('offers no Move link to someone who may not move stock, but still says where it sells better', () => {
    show(<SlowMoversSection data={plain} nameOf={nameOf} />, mockMe('sales'));
    expect(screen.getByText(/Sells better at Test Branch B/)).toBeTruthy();
    expect(screen.queryByRole('link', { name: /^Move/ })).toBeNull();
  });

  it('says so when nothing is slow, and names the 90 day minimum when one is set', () => {
    show(<SlowMoversSection data={{ days: 90, slow_months: 6, min_sold: 5, items: [], total: 0 }} nameOf={nameOf} />);
    expect(screen.getByText(/No slow movers/)).toBeTruthy();
    expect(screen.getByText(/more than 6 months, or fewer than 5 sold in 90 days/)).toBeTruthy();
  });
});

describe('slow mover settings', () => {
  const read = { capabilities: { profile: 'shop', slow_mover_months: 4, slow_mover_min_sold: 0 }, version: 7 } as never;

  it('saves both rules with the settings version', async () => {
    change.mockResolvedValue({ capabilities: { profile: 'shop', slow_mover_months: 6, slow_mover_min_sold: 2 }, version: 8 });
    show(<SlowMoverSetting read={read} />);
    const save = screen.getByRole('button', { name: 'Save' });
    expect((save as HTMLButtonElement).getAttribute('data-not-ready')).toBe('true');
    fireEvent.change(screen.getByLabelText(/more than this many months/), { target: { value: '6' } });
    fireEvent.change(screen.getByLabelText(/fewer than this many sold/), { target: { value: '2' } });
    fireEvent.click(save);
    await waitFor(() => expect(change).toHaveBeenCalledWith({ slow_mover_months: 6, slow_mover_min_sold: 2 }, 7));
  });

  it('refuses months outside 1 to 24 before anything is sent', () => {
    show(<SlowMoverSetting read={read} />);
    fireEvent.change(screen.getByLabelText(/more than this many months/), { target: { value: '25' } });
    expect(screen.getByRole('alert').textContent).toContain('1 to 24');
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).getAttribute('data-not-ready')).toBe('true');
  });
});

describe('Move stock filled in from Stock health', () => {
  it('reads only a complete prefill from the address', () => {
    expect(prefillOf({ to: BRANCH_B, product: PRODUCT, qty: 8 })).toEqual({ from: '', to: BRANCH_B, product: PRODUCT, code: '', qty: '8' });
    expect(prefillOf({ to: BRANCH_B })).toBeNull();
    expect(prefillOf({})).toBeNull();
  });

  it('chooses the branch and adds the item with the suggested quantity', async () => {
    findProducts.mockResolvedValue([{ id: PRODUCT, code: 'TI-01', description: 'Test Item 01', unit: 'piece', qty: '40.000' }]);
    show(<TransferForm fromBranchId={BRANCH_A} prefill={{ to: BRANCH_B, product: PRODUCT, code: 'TI-01', qty: '8' }} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Remove Test Item 01' })).toBeTruthy());
    expect((screen.getByLabelText('To branch') as HTMLSelectElement).value).toBe(BRANCH_B);
    expect((screen.getByLabelText('Quantity (piece)') as HTMLInputElement).value).toBe('8');
    expect(findProducts).toHaveBeenCalledWith({ query: 'TI-01', branchId: BRANCH_A });
  });
});
