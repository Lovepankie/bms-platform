import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { ExpenseCategory } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { ExpenseForm } from '../src/areas/staff/retail/expenses';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page expense-item.layout.test.ts lays out in a real browser (issue #802, ADR-118): the expense form of a salesperson who
// records expenses, with "Add a new item" under the Item picker. The lists are in the query cache, so nothing is fetched.
// Every name is invented.

const me = { ...mockMe('cashier'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const categories: ExpenseCategory[] = [
  { id: 'c1', name: 'Transport', active: true, version: 1, items: [{ id: 'i1', name: 'Fuel', requires_explanation: false, active: true, version: 1 }] },
  { id: 'c2', name: 'Cleaning', active: true, version: 1, items: [] },
];
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'expense-categories', true], categories);
client.setQueryData(['retail', 'cash-parties'], []);

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/expenses')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><main>
          <ExpenseForm branchId={branch} />
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
