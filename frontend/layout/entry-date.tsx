import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { WithdrawalForm } from '../src/areas/staff/retail/withdrawals';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page entry-date.layout.test.ts lays out in a real browser (issue #811, ADR-120): the withdrawal form of the owner, who
// alone sees the date box and, for an earlier day, the reason under it. Nothing is fetched. Every name is invented.

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/withdrawals')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><main>
          <WithdrawalForm branchId={branch} />
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
