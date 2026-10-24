// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { DaysList } from './cash-summary';
import { SuppliersPage } from './catalogue-people';
import { CustomerGroups } from './customer-groups';

// The links of the cash book, supplier and customer group pages (issue #336): a figure opens the rows behind it, a supplier its open
// items, a group its buyers. Fabricated names and amounts; the query cache is filled first so no request leaves the test.

afterEach(cleanup);

const me: Me = { ...mockMe('admin'), module_capabilities: { retail: ['purchasing', 'price_lists'] } };

function show(node: ReactElement, seed: (client: QueryClient) => void = () => undefined) {
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } });
  seed(client);
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? null }}>{node}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const href = (name: string) => screen.getByRole('link', { name }).getAttribute('href');

describe('links of the cash summary', () => {
  it('opens the report behind the expenses figure and the page behind the withdrawals figure', () => {
    show(<DaysList rows={[{ branch_id: me.branches?.[0]?.id, business_date: '2026-04-01', expenses_minor: 12000, withdrawals_in_minor: 5000 }]} links={{ expenses: '/staff/retail/expenses-report', withdrawals: '/staff/retail/withdrawals' }} />);
    expect(href('Expenses: see the rows')).toBe('/staff/retail/expenses-report');
    expect(href('Withdrawals from the bank: see the rows')).toBe('/staff/retail/withdrawals');
  });

  it('draws a figure as plain text when no link is given', () => {
    show(<DaysList rows={[{ branch_id: me.branches?.[0]?.id, business_date: '2026-04-01', expenses_minor: 12000 }]} />);
    expect(screen.queryByRole('link')).toBeNull();
  });
});

describe('a supplier on the Suppliers page', () => {
  it('opens its open items, ready to pay', () => {
    show(<SuppliersPage />, (client) =>
      client.setQueryData(['retail', 'catalogue', 'suppliers', ''], { pages: [{ items: [{ id: 's-1', name: 'Test Supplier 01', active: true, version: 1 }] }], pageParams: [''] }),
    );
    expect(href('Open items of Test Supplier 01')).toBe('/staff/retail/supplier-payments/new?supplier=s-1');
  });
});

describe('a customer group', () => {
  it('opens the credit buyers and its price list', () => {
    show(<CustomerGroups />, (client) =>
      client.setQueryData(['retail', 'customer-groups'], [{ id: 'g-1', name: 'Test Group 01', price_list: 'Test List 01', price_list_id: 'l-1', customer_count: 2, active: true, version: 1 }]),
    );
    expect(href('See the customers who owe in Test Group 01')).toBe('/staff/retail/catalogue/buyers');
    expect(href('See the price list Test List 01')).toBe('/staff/retail/price-lists');
  });
});
