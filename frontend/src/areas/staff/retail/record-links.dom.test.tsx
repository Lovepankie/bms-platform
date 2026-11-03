// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { retail } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { CreditNoteView } from './credit-note';
import { AllSales } from './sales';
import { SaleLink } from './screen-link';
import { StockPage } from './stock';

// Screens that open one record have a stable address (issue #336), so a sale number, an item or a branch name elsewhere opens
// that record: `/staff/retail/sales?sale=<id>`, `/staff/retail/stock?item=<id>` and `/staff/retail/stock?branch=<id>`. The data is
// the fabricated retail mock; every name and figure is made up.

vi.setConfig({ testTimeout: 20000 });

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

describe('an item or a branch opens its stock (#336)', () => {
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
