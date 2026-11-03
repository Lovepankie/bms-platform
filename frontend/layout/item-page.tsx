import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { ItemStock } from '../src/api/item-page';
import { businessToday, type StockMovement } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { ItemPageView, movementsKey } from '../src/areas/staff/retail/item-page';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page item-page.layout.test.ts lays out in a real browser (issue #813): one item on a phone, with long shop names, a long
// item name, large quantities, buying figures and profit, and the shop and day filters. The answers are put in the query cache,
// so the page asks no server. ?font= scales the root font size as a phone's large text setting does; ?seller=1 draws the page
// as a seller's answer comes, without buying figures. Every name and figure is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;
const cost = query.get('seller') !== '1';

const me = { ...mockMe(cost ? 'admin' : 'sales'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const today = businessToday();
const at = `${today}T09:00:00Z`;
const money = (line: number, profit?: number) => (cost ? { unit_cost_minor: 125000, line_cost_minor: line, ...(profit !== undefined ? { profit_minor: profit } : {}) } : {});
const item: ItemStock = {
  product_id: 'p1', code: 'EXT-CABLE-4WAY-10M-HEAVY', description: 'Test extension cable four way ten metre heavy duty with surge guard', unit: 'piece', category: 'Test electrical fittings and cables', active: true, total_qty: '1234567.500',
  shops: [
    { branch_id: 'b1', code: 'HQ', name: 'Test Main Shop on the Long Market Street Opposite the Bus Park', closed: false, qty: '1234560.000', negative: false, ...(cost ? { avg_cost_minor: 125000000 } : {}) },
    { branch_id: 'b2', code: 'ST', name: 'Test Back Store', closed: false, qty: '7.500', negative: false, ...(cost ? { avg_cost_minor: 125000 } : {}) },
    { branch_id: 'b3', code: 'OLD', name: 'Test Old Kiosk', closed: true, qty: '-1.000', negative: true },
  ],
};
const rows: StockMovement[] = [
  { id: 'm1', at, branch_id: 'b1', product_id: 'p1', unit: 'piece', kind: 'sale', qty: '-1200.000', balance_after: '1234560.000', source_type: 'retail.sale', historical: false, ...money(150000000, 98000000) },
  { id: 'm2', at, branch_id: 'b2', product_id: 'p1', unit: 'piece', kind: 'transfer_in', qty: '7.500', balance_after: '7.500', source_type: 'retail.transfer', historical: false, note: 'Moved for the weekend market with the other cables and the long boxes of lamps', ...money(937500) },
  { id: 'm3', at, branch_id: 'b1', product_id: 'p1', unit: 'piece', kind: 'purchase', qty: '1235767.500', balance_after: '1235767.500', source_type: 'retail.purchase', historical: false, ...money(154470937500) },
];
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(movementsKey('p1', '', '', ''),{ pages: [{ items: rows, next_cursor: 'more' }], pageParams: [''] });
const router = layoutRouter('/staff/retail/items/p1');

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><div className="nav-layout"><div className="nav-layout-main">
          <ItemPageView item={item} productId="p1" />
        </div></div></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
