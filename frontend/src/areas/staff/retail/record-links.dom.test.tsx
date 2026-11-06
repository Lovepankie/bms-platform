// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useEffect, useState, type ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { businessToday, retail, type AccountPayment, type Statement, type Valuation } from '../../../api/retail';
import { ValuationTable } from './profit';
import { ReceiptList, StatementView } from './account';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { CreditNoteView } from './credit-note';
import { ToItems, ToStock, ToUsage } from './analytics-ui';
import { UsageHistoryPage } from './usage-history';
import { ProductsPage } from './catalogue-products';
import { AllSales, CreditSales } from './sales';
import { SaleLink } from './screen-link';
import { StockPage } from './stock';

// Screens that open one record have a stable address (issue #336), so a sale number, an item or a branch name elsewhere opens
// that record: `/staff/retail/sales?sale=<id>`, `/staff/retail/stock?item=<id>` and `/staff/retail/stock?branch=<id>`. The data is
// the fabricated retail mock; every name and figure is made up.

vi.setConfig({ testTimeout: 20000 });
// A navigation loads the destination's lazy route module first, which under the whole suite's load can take longer than the
// default second, so the waits here allow ten (a full run once timed out at Back to the list).
configure({ asyncUtilTimeout: 10000 });

const BRANCH_A = '00000000-0000-4000-8000-0000000000a1';
const BRANCH_B = '00000000-0000-4000-8000-0000000000a2';
const SALE_2 = '5eed0000-0000-4000-8000-000000000002';
const ITEM_3 = '00000000-0000-4000-8000-0000000b0003';

beforeEach(() => {
  Object.assign(retail, createMockRetail());
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function show(ui: ReactElement, url: string, opts: { me?: Me; branch?: string; chooseBranch?: (b: string) => void } = {}) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [url] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me: opts.me ?? mockMe('admin'), branch: opts.branch ?? BRANCH_A, chooseBranch: opts.chooseBranch }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  return router;
}

describe('a category opens its items (#798)', () => {
  const CABLES = '00000000-0000-4000-8000-0000000c0001';
  const LIGHTING = '00000000-0000-4000-8000-0000000c0002';
  const FITTINGS = '00000000-0000-4000-8000-0000000c0003';
  // Where Items remembers the chip the person chose (phone-find-state.ts).
  const SAVED = 'bms.findChip.items';
  afterEach(() => localStorage.removeItem(SAVED));

  it('links a category name to Items with the category in the address', () => {
    show(<ToItems category={LIGHTING}>Lighting</ToItems>, '/staff');
    expect(screen.getByRole('link', { name: 'Lighting' }).getAttribute('href')).toBe(`/staff/retail/catalogue/products?category=${LIGHTING}`);
  });

  it('opens Items on that category and leaves the address', async () => {
    const router = show(<ProductsPage />, `/staff/retail/catalogue/products?category=${LIGHTING}`);
    await waitFor(() => expect(screen.getByRole('button', { name: /^Lighting/, pressed: true })).toBeTruthy());
    await waitFor(() => expect(router.state.location.search).toEqual({}));
    expect(screen.queryByTestId('category-not-found')).toBeNull();
  });

  it('says so when the category in the address is not in the list, and shows every category', async () => {
    const list = vi.spyOn(retail, 'listCatalogue');
    localStorage.setItem(SAVED, CABLES);
    show(<ProductsPage />, '/staff/retail/catalogue/products?category=00000000-0000-4000-8000-0000000c00ff');
    expect(await screen.findByTestId('category-not-found')).toBeTruthy();
    // The unknown id is dropped (useChosenChip's knownChip): the list asks for no category, so items of every category show.
    await waitFor(() => expect(list).toHaveBeenCalled());
    for (const [q] of list.mock.calls) expect(q.categoryId ?? '').toBe('');
    expect(await screen.findAllByText(/2\.5mm twin cable 100m roll/)).not.toHaveLength(0);
    expect(screen.getAllByText(/Solar panel 100W/)).not.toHaveLength(0);
    expect(screen.getByRole('button', { name: /^All/, pressed: true })).toBeTruthy();
    // The saved chip is the person's own: a link that names a missing category leaves it alone.
    expect(localStorage.getItem(SAVED)).toBe(CABLES);
  });

  it('chooses the category for that visit only: the saved Items chip is unchanged, and a tap still saves (Lead, #862)', async () => {
    localStorage.setItem(SAVED, CABLES);
    const list = vi.spyOn(retail, 'listCatalogue');
    show(<ProductsPage />, `/staff/retail/catalogue/products?category=${LIGHTING}`);
    await waitFor(() => expect(screen.getByRole('button', { name: /^Lighting/, pressed: true })).toBeTruthy());
    expect(localStorage.getItem(SAVED)).toBe(CABLES);
    // The saved chip's list is never asked for on the way in: only Lighting's.
    await waitFor(() => expect(list).toHaveBeenCalled());
    expect(list.mock.calls.map(([q]) => q.categoryId)).not.toContain(CABLES);
    fireEvent.click(screen.getByRole('button', { name: /^Fittings/ }));
    expect(localStorage.getItem(SAVED)).toBe(FITTINGS);
  });

  it('is the category row of Stock value, by its id', () => {
    const valuation = { as_of: '2026-10-05', currency: 'UGX', rows: [], categories: [{ category_id: LIGHTING, category: 'Lighting', expected_sales_minor: 12000 }], expected_sales_minor: 12000 } as Valuation;
    show(<ValuationTable valuation={valuation} />, '/staff');
    expect(screen.getByRole('link', { name: 'Lighting' }).getAttribute('href')).toBe(`/staff/retail/catalogue/products?category=${LIGHTING}`);
  });
});

describe('a used or damaged count on Stock health opens the records behind it (#798)', () => {
  it('links to Past usage and damage with the branch, the days and the kind', () => {
    show(<ToUsage branch={BRANCH_B} from="2026-10-01" to="2026-10-31" kind="damaged">2 reports</ToUsage>, '/staff');
    expect(screen.getByRole('link', { name: '2 reports' }).getAttribute('href')).toBe(`/staff/retail/usage-history?branch=${BRANCH_B}&from=2026-10-01&to=2026-10-31&kind=damaged`);
  });

  it('is plain text for a session that lacks the stock screen, the gate of Past usage and damage', () => {
    const me = { ...mockMe('admin'), permissions: ['retail.sale.read'] } as Me;
    show(<ToUsage branch={BRANCH_B} kind="used">2 reports</ToUsage>, '/staff', { me });
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText('2 reports')).toBeTruthy();
    cleanup();
    show(<UsageHistoryPage />, '/staff/retail/usage-history', { me });
    expect(screen.getByText(/You do not have access/)).toBeTruthy();
  });

  it('is plain text for a count of none, which has nothing behind it', () => {
    show(<ToUsage branch={BRANCH_B} kind="damaged" count={0}>0 reports</ToUsage>, '/staff');
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('follows a new address while the page stays open', async () => {
    const router = show(<UsageHistoryPage />, '/staff/retail/usage-history?from=2026-10-01&to=2026-10-31&kind=used');
    expect((await screen.findByTestId('one-kind')).textContent).toContain('used stock only');
    await router.navigate({ to: '/staff/retail/usage-history', search: { from: '2026-09-01', to: '2026-09-30', kind: 'damaged' } as never });
    await waitFor(() => expect(screen.getByTestId('one-kind').textContent).toContain('damaged stock only'));
  });

  it('keeps the days chosen in the address, so a reload keeps them', async () => {
    const router = show(<UsageHistoryPage />, '/staff/retail/usage-history?from=2026-10-01&to=2026-10-31&kind=used');
    await screen.findByTestId('one-kind');
    fireEvent.click(screen.getByRole('radio', { name: 'Today' }));
    const today = businessToday();
    await waitFor(() => expect(router.state.location.search).toEqual({ from: today, to: today, kind: 'used' }));
  });

  it('opens Past usage and damage on those days and that kind, switched to that branch', async () => {
    const chooseBranch = vi.fn();
    const history = vi.spyOn(retail, 'usageHistory');
    const router = show(<UsageHistoryPage />, `/staff/retail/usage-history?branch=${BRANCH_B}&from=2026-10-01&to=2026-10-31&kind=damaged`, { chooseBranch });
    await waitFor(() => expect(chooseBranch).toHaveBeenCalledWith(BRANCH_B));
    await waitFor(() => expect(router.state.location.search).toEqual({ from: '2026-10-01', to: '2026-10-31', kind: 'damaged' }));
    expect((await screen.findByTestId('one-kind')).textContent).toContain('Showing damaged stock only.');
    await waitFor(() => expect(history).toHaveBeenCalledWith(expect.objectContaining({ kind: 'damage' })));
    fireEvent.click(screen.getByRole('button', { name: 'Show used and damaged' }));
    await waitFor(() => expect(screen.queryByTestId('one-kind')).toBeNull());
    await waitFor(() => expect(history).toHaveBeenLastCalledWith(expect.not.objectContaining({ kind: expect.anything() })));
  });
});

describe('a sale number opens that sale (#336)', () => {
  it('links to All sales with the sale in the address', () => {
    show(<SaleLink saleId={SALE_2}>S-900002</SaleLink>, '/staff');
    expect(screen.getByRole('link', { name: 'S-900002' }).getAttribute('href')).toBe(`/staff/retail/sales?sale=${SALE_2}`);
  });

  it('is plain text for a session that may not read sales', () => {
    const me = { ...mockMe('admin'), permissions: ['retail.stock.read'] } as Me;
    show(<SaleLink saleId={SALE_2}>S-900002</SaleLink>, '/staff', { me });
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText('S-900002')).toBeTruthy();
  });

  it('is the sale row of a credit note', () => {
    show(<CreditNoteView note={{ id: 'r1', credit_note_no: 'CN-HQ-000001', sale_id: SALE_2, sale_no: 'S-900002', returned_on: '2026-10-01', lines: [] }} nameOf={() => 'Test Branch A'} />, '/staff');
    expect(screen.getByRole('link', { name: 'S-900002' }).getAttribute('href')).toBe(`/staff/retail/sales?sale=${SALE_2}`);
  });

  it('is each sale an account payment paid, under its month (#798)', () => {
    const receipt = { id: 'p1', receipt_no: 'AP-000001', status: 'posted', amount_minor: 5000, method: 'cash', paid_on: '2026-10-03', branch_id: BRANCH_A, allocations: [{ sale_id: SALE_2, sale_no: 'S-900002', amount_minor: 5000 }] } as AccountPayment;
    show(<ReceiptList items={[receipt]} canReverse={() => false} nameOf={() => 'Test Branch A'} onReverse={() => undefined} />, '/staff');
    expect(screen.getByRole('button', { name: /October 2026/ }).textContent).toContain('1 payment');
    expect(screen.getByRole('link', { name: 'S-900002' }).getAttribute('href')).toBe(`/staff/retail/sales?sale=${SALE_2}`);
  });

  it('is each sale brought in from the old records on a statement (#798)', () => {
    const statement = { from: '2026-10-01', to: '2026-10-31', lines: [], before_system_start: { sales: [{ sale_id: SALE_2, sale_no: 'S-900002', sale_date: '2026-01-05', total_minor: 9000 }] } } as Statement;
    show(<StatementView statement={statement} />, '/staff');
    expect(screen.getByRole('link', { name: 'S-900002' }).getAttribute('href')).toBe(`/staff/retail/sales?sale=${SALE_2}`);
  });

  it('opens the sale on All sales from the address, whichever branch is in use, and Back to the list clears it', async () => {
    const router = show(<AllSales />, `/staff/retail/sales?sale=${SALE_2}`);
    expect(await screen.findByRole('heading', { name: /Sale S-900002/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Back to the list' }));
    await waitFor(() => expect(router.state.location.search).toEqual({}));
    await waitFor(() => expect(screen.queryByRole('heading', { name: /Sale S-900002/ })).toBeNull());
  });
});

// JUSTIFICATION-A3: new test cases the review of #797 asked for; no existing test covers these paths.
describe('a sale from the address on load (review of #797)', () => {
  it('stays open when the session\'s branch settles after the first render', async () => {
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [`/staff/retail/sales?sale=${SALE_2}`] }) });
    // The staff layout starts with no branch and sets it once the profile and the stored choice are read.
    function Settling() {
      const [branch, setBranch] = useState<string | null>(null);
      useEffect(() => { const t = setTimeout(() => setBranch(BRANCH_A), 50); return () => clearTimeout(t); }, []);
      return <StaffContext.Provider value={{ me: mockMe('admin'), branch }}><AllSales /></StaffContext.Provider>;
    }
    render(
      <RouterContextProvider router={router}>
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Settling /></QueryClientProvider>
      </RouterContextProvider>,
    );
    expect(await screen.findByRole('heading', { name: /Sale S-900002/ })).toBeTruthy();
    await new Promise((r) => setTimeout(r, 200));
    expect(screen.getByRole('heading', { name: /Sale S-900002/ })).toBeTruthy();
    expect(router.state.location.search).toEqual({ sale: SALE_2 });
  });

  it('opens on Credit sales from the address too', async () => {
    show(<CreditSales />, `/staff/retail/credit-sales?sale=${SALE_2}`);
    expect(await screen.findByRole('heading', { name: /Sale S-900002/ })).toBeTruthy();
  });
});

describe('an item or a branch opens its stock (#336)', () => {
  it('opens the item at the branch of the row it came from, keeping the item once the branch is switched', async () => {
    const chooseBranch = vi.fn();
    const router = show(<StockPage />, `/staff/retail/stock?item=${ITEM_3}&branch=${BRANCH_B}`, { chooseBranch });
    await waitFor(() => expect(chooseBranch).toHaveBeenCalledWith(BRANCH_B));
    await waitFor(() => expect(router.state.location.search).toEqual({ item: ITEM_3 }));
    expect(await screen.findByTestId('one-item')).toBeTruthy();
  });

  it('leaves the one item when the person types a search', async () => {
    const router = show(<StockPage />, `/staff/retail/stock?item=${ITEM_3}`);
    await screen.findByTestId('one-item');
    fireEvent.change(screen.getByLabelText('Search by name, code, barcode or category'), { target: { value: 'cable' } });
    await waitFor(() => expect(router.state.location.search).toEqual({}));
    await waitFor(() => expect(screen.queryByTestId('one-item')).toBeNull());
  });

  it('is plain text on a report for a session that may not read stock', () => {
    const me = { ...mockMe('admin'), permissions: ['retail.sale.read'] } as Me;
    show(<ToStock item={ITEM_3} branch={BRANCH_B}>LED bulb 9W screw</ToStock>, '/staff', { me });
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText('LED bulb 9W screw')).toBeTruthy();
  });

  it('links an item of one branch to that item at that branch', () => {
    show(<ToStock item={ITEM_3} branch={BRANCH_B}>LED bulb 9W screw</ToStock>, '/staff');
    expect(screen.getByRole('link', { name: 'LED bulb 9W screw' }).getAttribute('href')).toBe(`/staff/retail/stock?item=${ITEM_3}&branch=${BRANCH_B}`);
  });

  it('shows only the item from the address, with a way back to every item', async () => {
    show(<StockPage />, `/staff/retail/stock?item=${ITEM_3}`);
    const note = await screen.findByTestId('one-item');
    await waitFor(() => expect(note.textContent).toContain('LED bulb 9W screw'));
    expect(await screen.findByText('LED bulb 9W screw')).toBeTruthy();
    expect(screen.queryByText('LED bulb 15W screw')).toBeNull();
    expect(screen.getByRole('button', { name: 'Show every item' })).toBeTruthy();
  });

  it('switches to the branch from the address when the person works there, then drops it from the address', async () => {
    const chooseBranch = vi.fn();
    const router = show(<StockPage />, `/staff/retail/stock?branch=${BRANCH_B}`, { chooseBranch });
    await waitFor(() => expect(chooseBranch).toHaveBeenCalledWith(BRANCH_B));
    await waitFor(() => expect(router.state.location.search).toEqual({}));
  });

  it('says so, and switches nothing, for a branch the person does not work at', async () => {
    const chooseBranch = vi.fn();
    show(<StockPage />, '/staff/retail/stock?branch=00000000-0000-4000-8000-0000000000ff', { chooseBranch });
    expect(await screen.findByTestId('branch-not-yours')).toBeTruthy();
    expect(chooseBranch).not.toHaveBeenCalled();
  });
});
