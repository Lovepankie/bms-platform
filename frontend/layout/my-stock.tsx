import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { StockRow } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { router as appRouter } from '../src/app/router';
import { useBranchSwitch } from '../src/areas/staff/branch-picker';
import { StaffContext } from '../src/areas/staff/context';
import { StockTable } from '../src/areas/staff/retail/stock';
import { Gate } from '../src/areas/staff/retail/ui';
import { StaffTabBar } from '../src/areas/staff/tabs';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The page my-stock.layout.test.ts lays out in a real browser (issue #680): My stock on a phone as a card per item, under the shop
// label that switches shop, and the toast a switch shows. ?font= scales the root font size as a phone's large text setting does,
// ?theme=dark draws the dark theme. Every name is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;
if (query.get('theme') === 'dark') document.documentElement.dataset.theme = 'dark';

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const rows: StockRow[] = [
  { product_id: 'p1', code: 'EX-1', description: 'Test extension cable 4 way', category: 'Extensions', unit: 'piece', qty: '8.000', negative: false, sell_minor: 8000, cost_minor: 6000 },
  { product_id: 'p2', code: 'BL-1', description: 'Test LED bulb 9W screw, warm white', category: 'Bulbs', unit: 'piece', qty: '1127.000', negative: false, sell_minor: 4500, cost_minor: 3200, standard_pack: { unit: 'Box', factor: '10.000' } },
  { product_id: 'p3', code: 'BL-2', description: 'Test tube light 4 ft', category: 'Bulbs', unit: 'piece', qty: '-2.000', negative: true, sell_minor: 15000, cost_minor: 11000 },
];
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail/stock'] }) });

function Page() {
  const [branch, setBranch] = useState(me.branches?.[0]?.id ?? '');
  const { choose, toast } = useBranchSwitch(me, branch, (selection) => {
    setBranch(selection);
    return true;
  });
  return (
    <StaffContext.Provider value={{ me, branch, chooseBranch: choose }}>
      <div className="app-shell"><div className="app-content"><div className="nav-layout">
        <div className="nav-layout-main">
          <Gate screen="stock" title="My stock"><StockTable rows={rows} showCost correctable={{ branchId: branch }} /></Gate>
          {toast}
        </div>
        <StaffTabBar me={me} />
      </div></div></div>
    </StaffContext.Provider>
  );
}

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <Page />
    </QueryClientProvider>
  </RouterContextProvider>,
);
