import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { businessToday, type SavingsGroups, type SavingsSuggestion } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { SALES_STAFF_KEY, SavingsByShop, SavingsForm } from '../src/areas/staff/retail/savings';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page savings.layout.test.ts lays out in a real browser (issue #768, ADR-115): the savings form as a salesperson sees it
// when the owner turned on "Salespeople can see and change the savings amount": the suggestion, with no profit, no expenses and
// not the most the day allows. The suggestion is in the query cache, so nothing is fetched. Every figure is invented.

// With `?sales` (issue #799): a seller with the Sales role alone under the owner's switch, with every month's savings grouped
// by shop, year, month and person below the form (issue #827).
const sales = new URLSearchParams(location.search).has('sales');
const me = {
  ...mockMe(sales ? 'sales' : 'cashier'), tours: { 'tours-off': { status: 'dismissed', version: 1 } }, salespeople_see_savings_amount: sales,
} as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const date = businessToday();
const suggestion: SavingsSuggestion = {
  branch_id: branch, business_date: date, total_sold_minor: 1250000, suggestion_token: 'tok-1', suggested_minor: 240000,
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'savings-suggestion', branch, date, ...(sales ? [SALES_STAFF_KEY] : [])], suggestion);
// Issue #827: this month and last, by shop, year, month and person, with the amount saved at each level.
const groups: SavingsGroups = {
  total_minor: 1600000,
  shops: [{
    branch_id: branch, total_minor: 1600000, years: [{
      year: 2026, total_minor: 1600000, months: [
        { month: 11, total_minor: 1350000, people: [
          { user_id: 'u-1', name: 'Test Seller 01', total_minor: 200000, records: [{ id: 'sv-3', business_date: '2026-11-03', amount_minor: 200000 }] },
          { user_id: 'u-2', name: 'Test Till 01', total_minor: 1150000, records: [{ id: 'sv-2', business_date: '2026-11-01', amount_minor: 1150000 }] },
        ] },
        { month: 10, total_minor: 250000, people: [
          { user_id: 'u-1', name: 'Test Seller 01', total_minor: 250000, records: [{ id: 'sv-1', business_date: '2026-10-31', amount_minor: 250000 }] },
        ] },
      ],
    }],
  }],
};
client.setQueryData(['retail', 'savings-groups', [branch], SALES_STAFF_KEY], groups);

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/savings')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><main>
          <SavingsForm branchId={branch} />
          {sales && <SavingsByShop />}
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
