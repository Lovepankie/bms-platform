import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { businessToday, daysBefore, retail, type Product, type StockMovement, type UsageSummaryQuery } from '../src/api/retail';
import { summariseUsage } from '../src/api/retail-grouping-mock';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { UsageHistoryPage } from '../src/areas/staff/retail/usage-history';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page usage-history.layout.test.ts lays out in a real browser (issue #145): Past usage and damage on a phone, with long item
// names and codes, a long reason, the value at buying price, and the item search with long options in its list. The answers are
// put in the query cache, so the page asks no server. ?font= scales the root font size as a phone's large text setting does,
// ?empty=1 draws the empty list with its one action. Every name is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const today = businessToday();
const at = `${today}T09:00:00Z`;
const rows: StockMovement[] = query.get('empty') === '1' ? [] : [
  { id: 'm1', at, branch_id: branch, product_id: 'p1', code: 'EXT-CABLE-4WAY-10M-HEAVY', description: 'Test extension cable four way ten metre heavy duty with surge guard', unit: 'piece', kind: 'damage', qty: '-12.000', note: 'Dropped from the top shelf while moving the boxes to the back store before the delivery came in', unit_cost_minor: 125000000, line_cost_minor: 1500000000 },
  { id: 'm2', at, branch_id: branch, product_id: 'p2', code: 'PA-05', description: 'Test paint', unit: 'litre', kind: 'usage', qty: '-0.500', note: 'Shop door', unit_cost_minor: 1500, line_cost_minor: 750 },
];
const products: Product[] = [
  { id: 'p1', code: 'EXT-CABLE-4WAY-10M-HEAVY', description: 'Test extension cable four way ten metre heavy duty with surge guard' } as Product,
];
// ?grouped=1 (issue #816): the Grouped view by shop, month, category and item, its groups summed by the mock's rules from the rows
// above and one more month at the second shop; ?seller=1 for a seller without retail.profit.read, so no cost is sent.
if (query.get('grouped') === '1') {
  try { localStorage.setItem(`bms.listView.usage.${me.user_id ?? 'staff'}`, 'grouped'); } catch { /* the flat list then */ }
  const seller = query.get('seller') === '1';
  const other = me.branches?.[1]?.id ?? '';
  const all: StockMovement[] = [...rows, { id: 'm3', at: `${daysBefore(today, 25)}T09:00:00Z`, branch_id: other, product_id: 'p2', code: 'PA-05', description: 'Test paint', unit: 'litre', kind: 'damage', qty: '-3.000', note: 'Tin fell', unit_cost_minor: 1500, line_cost_minor: 4500 }];
  const shown = seller ? all.map(({ unit_cost_minor: _u, line_cost_minor: _l, ...m }) => m) : all;
  Object.assign(retail, {
    usageSummary: (q: UsageSummaryQuery) => Promise.resolve(summariseUsage(shown, q, {
      cost: !seller,
      branchName: (id) => (me.branches ?? []).find((b) => b.id === id)?.name ?? 'Shop',
      product: (id) => ({ name: id === 'p1' ? rows[0]?.description ?? '' : 'Test paint', categoryId: id === 'p1' ? 'c1' : 'c2', categoryName: id === 'p1' ? 'Test cables and extension leads for the back store' : 'Test paint', unit: id === 'p1' ? 'piece' : 'litre' }),
      day: (m) => businessToday(new Date(m.at ?? '')),
    })),
    usageHistory: (q: { productId?: string; branchId?: string }) => Promise.resolve({ items: shown.filter((m) => (!q.productId || m.product_id === q.productId) && (!q.branchId || m.branch_id === q.branchId)) }),
  });
}
// ?missing=1 (issue #863): opened from Found missing on Stock health, the short lines of a stock count, with a long item name
// and code; ?seller=1 again leaves out the cost.
const missing = query.get('missing') === '1';
const short: StockMovement[] = [
  { id: 'm4', at, branch_id: branch, product_id: 'p1', code: 'EXT-CABLE-4WAY-10M-HEAVY', description: 'Test extension cable four way ten metre heavy duty with surge guard', unit: 'piece', kind: 'adjustment', qty: '-1250.000', source_type: 'retail.stocktake', source_id: 's1', historical: false, ...(query.get('seller') === '1' ? {} : { unit_cost_minor: 125000000, line_cost_minor: 156250000000 }) },
];
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'usage-history', daysBefore(today, 30), today, branch, '', 'both'], { pages: [{ items: rows }], pageParams: [''] });
client.setQueryData(['retail', 'usage-history', daysBefore(today, 30), today, branch, '', 'missing'], { pages: [{ items: short }], pageParams: [''] });
client.setQueryData(['retail', 'products', 'all', 'Test'], products);
const router = layoutRouter(missing ? '/staff/retail/usage-history?kind=missing' : '/staff/retail/usage-history');

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><div className="nav-layout"><div className="nav-layout-main">
          <UsageHistoryPage />
        </div></div></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
