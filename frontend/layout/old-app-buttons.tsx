import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { Expense } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { NamedRow } from '../src/areas/staff/retail/catalogue-lists';
import { ExpenseRowActions } from '../src/areas/staff/retail/expenses';
import { VoidFormView } from '../src/areas/staff/retail/sale-actions';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page old-app-buttons.layout.test.ts lays out in a real browser (issue #830): the old app's Delete and Edit buttons and the
// questions they ask first, on a sale, an expense and a category. Nothing is fetched. Every name and figure is invented.

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const expense: Expense = { id: 'e1', branch_id: branch, business_date: '2026-11-02', category_id: 'c1', category_name: 'Transport', item_id: 'i1', item_name: 'Fuel', amount_minor: 7000 };
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/expenses')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><main>
          <section aria-label="Sale">
            <VoidFormView branchName="Test Branch A with a long shop name" onVoid={() => undefined} onCancel={() => undefined} />
          </section>
          <section aria-label="Expense">
            <ExpenseRowActions row={expense} />
          </section>
          <ul style={{ listStyle: 'none', padding: 0 }} aria-label="Categories">
            <NamedRow row={{ id: 'c9', name: 'Test lamps and torches', active: true, version: 1, product_count: 2 }} noun="category" onRename={() => undefined} onToggle={() => undefined} />
          </ul>
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
