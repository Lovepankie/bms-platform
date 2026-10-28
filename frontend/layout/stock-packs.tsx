import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { AllBranchesStock, Product, StockRow } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { router as appRouter } from '../src/app/router';
import { StaffContext } from '../src/areas/staff/context';
import { AllBranchesList, StockTable } from '../src/areas/staff/retail/stock';
import { StocktakePage } from '../src/areas/staff/retail/stocktake';
import { TransferForm } from '../src/areas/staff/retail/transfer';
import { Gate } from '../src/areas/staff/retail/ui';
import { StaffTabBar } from '../src/areas/staff/tabs';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The page stock-packs.layout.test.ts lays out in a real browser (issue #650): ?screen=stock, all, count or move shows Stock (one shop, or All branches on a phone), Count
// stock or Move stock with items kept in cartons and pieces, inside the app shell and the staff layout, under the app's own stylesheets.
// ?font= scales the root font size as a phone's large text setting does. Every name is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const to = me.branches?.[1]?.id ?? '';
const carton = { unit: 'Half carton', factor: '24.000' };
const rows: StockRow[] = [
  { product_id: 'p1', code: 'TS-1', description: 'Test bathing soap, family size', category: 'Soap', unit: 'Piece', qty: '1127.000', negative: false, sell_minor: 1500, standard_pack: carton },
  { product_id: 'p2', code: 'TS-2', description: 'Test juice', category: 'Drinks', unit: 'Bottle', qty: '-27.000', negative: true, sell_minor: 2500, standard_pack: { unit: 'Crate', factor: '24.000' } },
  { product_id: 'p3', code: 'TS-3', description: 'Test cable', category: 'Cables', unit: 'Metre', qty: '40.000', negative: false, sell_minor: 900 },
];
const soap: Product = {
  id: 'p1', code: 'TS-1', description: 'Test bathing soap, family size', unit: 'Piece', sell_minor: 1500, active: true, qty: '1127.000',
  packs: [{ id: 'k1', product_id: 'p1', unit_id: 'u1', unit: 'Half carton', factor: '24.000', active: true, purpose: 'both', version: 1, standard: true }],
};
const screen = query.get('screen') ?? 'stock';
const all: AllBranchesStock = {
  branches: [{ id: 'b1', code: 'HQ', name: 'Test Main Shop' }, { id: 'b2', code: 'ST', name: 'Test Store Kampala Road' }],
  items: rows.map((r) => ({ ...r, total_qty: r.qty, balances: [{ branch_id: 'b1', qty: r.qty, negative: r.negative }, { branch_id: 'b2', qty: '0.000', negative: false }] })),
};
sessionStorage.setItem(`retail-draft:transfer:${me.user_id ?? ''}:${branch}`, JSON.stringify({
  key: 'layout-650', draft: { toBranchId: to, transferDate: '2026-10-23', note: '', lines: [{ product: soap, qty: '23', packs: '46' }] },
}));
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'stock', branch, '', false], rows);
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail/stock'] }) });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><div className="nav-layout">
          <div className="nav-layout-main">
            {screen === 'stock' && <Gate screen="stock" title="Stock"><StockTable rows={rows} showCost={false} /></Gate>}
            {screen === 'all' && <Gate screen="stock" title="Stock"><AllBranchesList data={all} showCost={false} /></Gate>}
            {screen === 'count' && <StocktakePage />}
            {screen === 'move' && <Gate screen="transfer" title="Move stock"><TransferForm fromBranchId={branch} /></Gate>}
          </div>
          <StaffTabBar me={me} />
        </div></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
