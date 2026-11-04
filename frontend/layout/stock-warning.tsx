import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { Product } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { SaleLineCard } from '../src/areas/staff/retail/sale';
import { ProductPicker } from '../src/areas/staff/retail/ui';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page stock-warning.layout.test.ts lays out in a real browser (issue #815): the item picker of Record sale with an item out
// of stock, one at its low stock level and one above it, and the same three as sale lines. The matches are put in the query
// cache under the term the test types, so nothing is asked of a server. ?font= scales the root font size as a phone's large
// text setting does, ?theme=dark draws the dark theme. Every item is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;
if (query.get('theme') === 'dark') document.documentElement.dataset.theme = 'dark';

const me = { ...mockMe('sales'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const item = (code: string, description: string, qty: string): Product => ({
  id: `id-${code}`, code, description, unit: 'piece', category: 'Test Lighting', sell_minor: 1000, active: true, qty, low_stock_level: '10.000',
});
const items = [
  item('W0', 'Test Energy Saving Bulb With A Long Name 9W', '0.000'),
  item('W10', 'Test Bulb Level', '10.000'),
  item('W11', 'Test Bulb Plenty', '11.000'),
];
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'products', branch, 'test'], items);
const router = layoutRouter('/staff/retail/sell');
const ignore = () => undefined;

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><div className="nav-layout">
          <div className="nav-layout-main rt">
            <ProductPicker id="sale-search" branchId={branch} showPrice warnStock onAdd={ignore} />
            <h2>Items</h2>
            {items.map((p, i) => (
              <SaleLineCard key={p.id} line={{ product: p, qty: '1', price: '1000' }} index={i} unitsOn={false} warnStock
                onQty={ignore} onPrice={ignore} onUnit={ignore} onRemove={ignore} />
            ))}
          </div>
        </div></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
