import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { businessToday, type BankingExpected } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { BankingForm } from '../src/areas/staff/retail/banking';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page banking.layout.test.ts lays out in a real browser (issue #814, ADR-115 decision 11): the Banking form as a person
// with the Till role and no profit access sees it while the owner's savings switch is on: the cash expected, the savings set
// aside and Expected to bank, the amount filled. With `?off` the switch is off and the answer carries the cash expected only.
// The answer is in the query cache, so nothing is fetched. Every figure is invented.

const switchOn = !new URLSearchParams(location.search).has('off');
const me = { ...mockMe('cashier'), tours: { 'tours-off': { status: 'dismissed', version: 1 } }, salespeople_see_savings_amount: switchOn } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const date = businessToday();
const expected: BankingExpected = {
  branch_id: branch, business_date: date, cash_expected_minor: 1250000, banked_so_far_minor: 0,
  ...(switchOn ? { savings_minor: 240000, savings_voids_minor: 0, expected_after_savings_minor: 1010000 } : {}),
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'banking-expected', branch, date], expected);

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/banking')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><main>
          <BankingForm branchId={branch} />
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
