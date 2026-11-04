import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { businessToday, daysBefore, type Expense } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { ALL_BRANCHES } from '../src/auth/branch';
import { StaffContext } from '../src/areas/staff/context';
import { ExpenseRecords } from '../src/areas/staff/retail/expenses';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page cash-totals.layout.test.ts lays out in a real browser (issue #816): the expense records of every shop grouped by shop,
// year, month, category and day with a total at every level, as the owner's old app did, across two shops and two months, with a
// long category name and a large amount. The list is in the query cache, so nothing is fetched. Every name and amount is invented.

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const [a, b] = (me.branches ?? []).map((x) => x.id ?? '');
const today = businessToday();
const earlier = daysBefore(today, 28);
const long = { category_id: 'c1', category_name: 'Test transport, fuel and delivery costs for the back store and the second shop' };
const rent = { category_id: 'c2', category_name: 'Test rent' };
const rows: Expense[] = [
  { id: 'e1', branch_id: a, business_date: today, amount_minor: 987654321, ...long, item_name: 'Test item 01', by_name: 'Test User 01' },
  { id: 'e2', branch_id: a, business_date: today, amount_minor: 2000, ...rent, item_name: 'Test item 02', by_name: 'Test User 01' },
  { id: 'e3', branch_id: a, business_date: earlier, amount_minor: 3000, ...long, item_name: 'Test item 03', voided: true, void_reason: 'Entered twice' },
  { id: 'e4', branch_id: b, business_date: earlier, amount_minor: 4000, ...rent, item_name: 'Test item 04' },
];
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'expenses', undefined, daysBefore(today, 30), today], rows);

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/expenses')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: ALL_BRANCHES }}>
        <div className="app-shell"><div className="app-content"><main>
          <ExpenseRecords />
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
