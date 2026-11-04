import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { Advance, Sale } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { AccountRow } from '../src/areas/staff/retail/accounts';
import { AdvanceDetailView } from '../src/areas/staff/retail/advances';
import { PersonRow } from '../src/areas/staff/retail/catalogue-people';
import { SaleDetail } from '../src/areas/staff/retail/sales';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page contact-links.layout.test.ts lays out in a real browser (issue #817): Call, Text and WhatsApp on a credit sale, a
// supplier, a customer who owes and an advance. Nothing is fetched. Every name and number is invented.

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const NUMBER = '0700 000 017';
const sale: Sale = { id: 's1', sale_no: 'S-000001', branch_id: branch, sale_date: '2026-10-10', payment_method: 'credit', status: 'completed', buyer_name: 'Test Buyer 01 with a long name', buyer_contact: NUMBER, total_minor: 12000, paid_minor: 2000, balance_minor: 10000, lines: [] };
const advance: Advance = { id: 'a1', advance_no: 'ADV-000001', branch_id: branch, business_date: '2026-10-10', party_id: 'p1', party_name: 'Test Owner 01', principal_minor: 500000, repaid_minor: 0, balance_minor: 500000, voided: false };
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/suppliers')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><main className="rt">
          <div data-testid="sale"><SaleDetail sale={sale} today="2026-10-20" /></div>
          <ul style={{ listStyle: 'none', padding: 0 }} aria-label="Suppliers">
            <PersonRow row={{ id: 'v1', name: 'Test Supplier 01', contact: NUMBER, active: true, version: 1 }} onSave={() => undefined} />
          </ul>
          <ul style={{ listStyle: 'none', padding: 0 }} aria-label="Customers who owe">
            <AccountRow row={{ id: 'c1', name: 'Test Buyer 02', contact: NUMBER, balance_minor: 5000 }} />
          </ul>
          <AdvanceDetailView advance={advance} contact={NUMBER} />
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
