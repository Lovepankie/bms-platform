import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { businessToday, retail, type Product, type Purchase } from '../src/api/retail';
import { createMockRetail, mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { RestockForm } from '../src/areas/staff/retail/restock';
import { RestockCard } from '../src/areas/staff/retail/restocks';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page delivery-worth.layout.test.ts lays out in a real browser (issue #821): Buy stock of the owner with two lines and,
// under them, the total cost, what the delivery should sell for and the expected profit; then a Past purchases row with the
// same three. The amounts are large on purpose, to test the width. Nothing is fetched: the retail adapter is the mock. Every
// name and amount is invented.

Object.assign(retail, createMockRetail());
const me = { ...mockMe('admin'), module_capabilities: { retail: [] }, tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const flour: Product = { id: 'p1', code: 'T-FLOUR', description: 'Test maize flour in the large sacks for the shop', unit: 'sack', cost_minor: 9_876_500, sell_minor: 12_345_000, active: true };
const oil: Product = { id: 'p2', code: 'T-OIL', description: 'Test cooking oil', unit: 'tin', cost_minor: 456_000, sell_minor: 599_000, active: true };
const draft = {
  supplierId: '', purchasedOn: businessToday(), method: 'cash' as const,
  lines: [
    { product: flour, cost: '9876500', sell: '12345000', qtyByBranch: { [branch]: '75' } },
    { product: oil, cost: '456000', sell: '', qtyByBranch: { [branch]: '120' } },
  ],
};
const purchase: Purchase = {
  id: 'pu1', purchase_no: 'RP00000821', purchased_on: '2026-10-30', payment_method: 'mobile_money', currency: 'UGX', total_minor: 795_457_500, status: 'posted',
  lines: [{ id: 'l1', description: flour.description, qty_total: '75.000', qty_by_branch: [{ branch_id: branch, qty: '75.000' }], base_unit: 'sack' }],
  sell_worth_minor: 997_755_000, expected_profit_minor: 202_297_500,
};
sessionStorage.clear();
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'suppliers'], []);

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/restock')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content">
          <main className="rt" data-testid="form"><RestockForm initial={draft} draftName="layout-delivery-worth" /></main>
          <main className="rt" data-testid="list"><ul style={{ listStyle: 'none', padding: 0 }}><RestockCard purchase={purchase} supplier="Test Supplier 01 with a long name" onDone={() => undefined} /></ul></main>
        </div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
