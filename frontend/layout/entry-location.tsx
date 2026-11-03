import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { Sale } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { LocationAsk, ShopLocationsCard, useWhereNow } from '../src/areas/staff/retail/entry-location';
import { SaleDetail } from '../src/areas/staff/retail/sales';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page entry-location.layout.test.ts lays out in a real browser (issue #826, ADR-122): the one-time question above the
// sale form, the owner's Shop locations in Business set-up, and a sale with how far from the shop it was made. The lists are
// in the query cache, so nothing is fetched. Every name and figure is invented.

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const sale = { id: 's1', sale_no: 'S-0001', branch_id: branch, sale_date: '2026-11-03', payment_method: 'cash', status: 'completed', total_minor: 1500, lines: [] } as Sale;
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'shop-locations'], [
  { branch_id: branch, name: 'Test Branch A with a long name for the phone', has_location: true, set_at: '2026-11-03T08:00:00Z' },
  { branch_id: 'b2', name: 'Test Branch B', has_location: false },
]);
client.setQueryData(['retail', 'entry-distances', 'sale', ['s1']], [
  { id: 's1', state: 'located', distance_m: 3200, accuracy_m: 30, words: '3.2 km from the shop' },
]);

function Ask() {
  return <LocationAsk where={useWhereNow()} />;
}

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/setup')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><main>
          <Ask />
          <ShopLocationsCard />
          <SaleDetail sale={sale} today="2026-11-03" />
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
