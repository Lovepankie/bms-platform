import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { businessToday, type SavingsSuggestion } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { SavingsForm } from '../src/areas/staff/retail/savings';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page savings.layout.test.ts lays out in a real browser (issue #768, ADR-115): the savings form as a salesperson sees it
// when the owner turned on "Salespeople can see and change the savings amount": the suggestion, with no profit, no expenses and
// not the most the day allows. The suggestion is in the query cache, so nothing is fetched. Every figure is invented.

const me = { ...mockMe('cashier'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const date = businessToday();
const suggestion: SavingsSuggestion = {
  branch_id: branch, business_date: date, total_sold_minor: 1250000, suggestion_token: 'tok-1', suggested_minor: 240000,
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'savings-suggestion', branch, date], suggestion);

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/savings')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><main>
          <SavingsForm branchId={branch} />
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
