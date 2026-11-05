// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useEffect, useState, type ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { retail } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { CreditNoteView } from './credit-note';
import { ToStock } from './analytics-ui';
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
