// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { CreditControlView } from './credit-control';
import { SalesAnalysisView } from './sales-analysis';
import { TransferSummary } from './transfer';

// Cross links on the retail report pages (issue #336): a buyer opens their account, a sales total opens All sales, and the
// branches of a stock move open Stock. Fabricated names and figures.

afterEach(cleanup);

const BRANCH = '00000000-0000-4000-8000-0000000000b1';

function show(ui: ReactElement, capabilities: string[] = []) {
  const me = { ...mockMe('admin'), module_capabilities: { retail: capabilities }, branches: [{ id: BRANCH, name: 'Test Branch A', code: 'BR1' }] };
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient()}>
        <StaffContext.Provider value={{ me, branch: BRANCH }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

describe('links on the retail report pages', () => {
  it('opens the account of an overdue buyer and the credit sales from the owed total', () => {
    const data = {
      as_of: '2026-04-01', buyer_count: 1, buyers: [], totals: { owed_minor: 5000 },
      overdue: [{ sale_id: 's1', sale_no: 'RS-0001', buyer_name: 'Test Buyer 01', customer_id: 'c1', branch_id: BRANCH, due_date: '2026-03-01', days_overdue: 31, outstanding_minor: 5000 }],
    };
    show(<CreditControlView data={data} nameOf={() => 'Test Branch A'} sort="amount" accounts />, ['customer_accounts']);
    expect(screen.getByRole('link', { name: 'Open the account of Test Buyer 01' }).getAttribute('href')).toBe('/staff/retail/accounts/c1');
    expect(screen.getByRole('link', { name: 'See the credit sales' }).getAttribute('href')).toBe('/staff/retail/credit-sales');
  });

  it('opens All sales from the sales total and Stock from an item', () => {
    const data = {
      group: 'day', totals: { sales_minor: 90000, sale_count: 3 },
      by_product: [{ id: 'p1', label: 'Test Item 01', code: 'TI-01', qty: '2', sales_minor: 90000 }],
    };
    show(<SalesAnalysisView data={data} />);
    const sales = screen.getAllByRole('link').filter((a) => a.getAttribute('href') === '/staff/retail/sales');
    expect(sales.length).toBe(2);
    expect(screen.getAllByRole('link', { name: 'Test Item 01' })[0]?.getAttribute('href')).toBe('/staff/retail/stock');
  });

  it('opens Stock from both branches of a stock move', () => {
    show(<TransferSummary transfer={{ id: 't1', from_branch_id: BRANCH, to_branch_id: BRANCH, transfer_date: '2026-04-01', status: 'completed', lines: [] }} />);
    const links = screen.getAllByRole('link', { name: /Test Branch A/ });
    expect(links.length).toBe(2);
    for (const a of links) expect(a.getAttribute('href')).toBe('/staff/retail/stock');
  });
});
