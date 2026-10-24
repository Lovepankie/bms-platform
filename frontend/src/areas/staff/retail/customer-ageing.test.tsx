import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { AgeingCustomer, CustomerAgeing } from '../../../api/retail-analytics';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { CustomerAgeingView, matchingBuyers } from './customer-ageing';
import { canUse, screensFor } from './permissions';

// Customer ageing on its screen (ADR-033, issue #196): the report the server builds from the same sales and buckets as Credit
// control, drawn per buyer. Names and amounts are fabricated.

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
const branch = mockMe('admin').branches?.[0]?.id ?? '';
const on = (me: ReturnType<typeof mockMe>) => ({ ...me, module_capabilities: { retail: ['customer_accounts'] } });

const rows: AgeingCustomer[] = [
  { customer_id: 'c1', name: 'Test Buyer 01', sale_count: 2, credit_limit_minor: 5000, terms_days: 30, ageing: { owed_minor: 6000, not_due_minor: 1000, over90_minor: 5000 }, oldest_overdue_days: 120, over_limit: true },
  { customer_id: 'c2', name: 'Test Bûyer 02', sale_count: 1, ageing: { owed_minor: 800, days1_to30_minor: 800 }, oldest_overdue_days: 12 },
];
const data: CustomerAgeing = { as_of: '2026-10-08', currency: 'UGX', top: 20, customer_count: 2, totals: { owed_minor: 6800, not_due_minor: 1000, days1_to30_minor: 800, over90_minor: 5000 }, customers: rows };

const view = (report: CustomerAgeing, accounts = true, shown?: readonly AgeingCustomer[]) =>
  renderToString(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient()}>
        <StaffContext.Provider value={{ me: on(mockMe('admin')), branch }}>
          <CustomerAgeingView data={report} accounts={accounts} {...(shown ? { rows: shown } : {})} />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );

describe('the customer ageing report', () => {
  it('lists each buyer with what they owe, the limit, the terms and the days late', () => {
    const html = view(data);
    expect(html).toContain('Owed by customers with a record UGX 6,800');
    expect(html).toContain('2 buyers owe this, as of 2026-10-08');
    expect(html).toContain('Test Buyer 01');
    expect(html).toContain('UGX 6,000');
    expect(html).toContain('UGX 5,000');
    expect(html).toContain('Payment is due 30 days after the sale');
    expect(html).toContain('Over 90 days late');
    expect(html.indexOf('Test Buyer 01')).toBeLessThan(html.indexOf('Test Bûyer 02'));
  });

  it('marks the buyer the server says is above the limit, and no other', () => {
    const html = view(data);
    expect(html.match(/Above the credit limit/g)).toHaveLength(1);
    // A report over some branches only carries no over_limit, so nobody is marked.
    const scoped: CustomerAgeing = { ...data, customers: rows.map(({ over_limit: _gone, ...r }) => r) };
    expect(view(scoped)).not.toContain('Above the credit limit');
  });

  it('links to the account of each buyer for whoever may open it', () => {
    expect(view(data)).toContain('aria-label="Open the account of Test Buyer 01"');
    expect(view(data, false)).not.toContain('Open the account');
  });

  it('says when nobody owes anything, and when the Top number hides some buyers', () => {
    expect(view({ ...data, customers: [], customer_count: 0, totals: { owed_minor: 0 } })).toContain('Nobody owes anything');
    expect(view({ ...data, customer_count: 5 })).toContain('Showing 2 of 5');
  });
});

describe('finding a buyer in the list', () => {
  it('matches every word of the name, ignoring case and accents', () => {
    expect(matchingBuyers(rows, '').map((r) => r.customer_id)).toEqual(['c1', 'c2']);
    expect(matchingBuyers(rows, 'buyer 02').map((r) => r.customer_id)).toEqual(['c2']);
    expect(matchingBuyers(rows, 'BUYER').map((r) => r.customer_id)).toEqual(['c1', 'c2']);
    expect(matchingBuyers(rows, 'buyer 03')).toEqual([]);
  });
});

describe('who is offered the screen', () => {
  it('is the administrator\'s tile, and only while customer accounts are on', () => {
    expect(canUse(on(mockMe('admin')), 'customerAgeing')).toBe(true);
    expect(canUse(mockMe('admin'), 'customerAgeing')).toBe(false);
    expect(canUse(on(mockMe('sales')), 'customerAgeing')).toBe(false);
    expect(screensFor(on(mockMe('admin'))).map((s) => s.screen)).toContain('customerAgeing');
    expect(screensFor(mockMe('admin')).map((s) => s.screen)).not.toContain('customerAgeing');
  });
});
