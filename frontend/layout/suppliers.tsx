import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { Supplier } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { SupplierForm, SupplierPage, SuppliersPage } from '../src/areas/staff/retail/suppliers';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page suppliers.layout.test.ts lays out in a real browser (issue #820): the searchable supplier list, one supplier's page
// with its details, the ways to reach them and its deliveries, and the supplier form open with every field. Nothing is fetched:
// the query cache is filled first. Every name, number and address is invented.

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const supplier: Supplier = {
  id: 's1', name: 'Test Supplier 01 with a long name for the phone', contact: '+256700000101', active: true, version: 3,
  created_at: '2026-02-01T08:00:00Z', last_delivery_on: '2026-10-30',
  details: {
    email: 'orders.and.accounts@supplier-test-with-a-long-name.example', address: 'Plot 1, Test Street, Test Town, near the old market',
    country: 'Uganda', website: 'www.supplier-test-with-a-long-name.example/shop', payment_terms: '30 days after the delivery',
    bank_account: '0000111122223333', supplier_type: 'Wholesaler', notes: 'Delivers on Mondays and Thursdays before noon.',
  },
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'catalogue', 'suppliers', ''], [supplier, { id: 's2', name: 'Test Supplier 02', active: false, version: 1 }]);
client.setQueryData(['retail', 'suppliers', 'one', 's1'], supplier);
client.setQueryData(['retail', 'suppliers', 'types'], ['Wholesaler', 'Manufacturer']);
client.setQueryData(['retail', 'suppliers', 'one', 's1', 'deliveries'], {
  pages: [{
    items: [
      { id: 'p2', purchase_no: 'RP00000002', purchased_on: '2026-10-30', voided: false, lines: 12 },
      { id: 'p1', purchase_no: 'RP00000001', purchased_on: '2026-10-01', voided: true, lines: 1 },
    ],
    next_cursor: 'c1',
  }],
  pageParams: [''],
});

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/catalogue/suppliers')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content">
          <div data-testid="list"><SuppliersPage /></div>
          <div data-testid="page"><SupplierPage supplierId="s1" /></div>
          <main className="rt" data-testid="form">
            <SupplierForm supplier={supplier} showBank busy={false} saveLabel="Save" onSave={() => undefined} onCancel={() => undefined} />
          </main>
        </div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
