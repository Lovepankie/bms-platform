import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { ReceiptLink, Sale, SaleReceipt } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { CashCustomerName } from '../src/areas/staff/retail/cash-customer';
import { receiptKey, ShareReceipt } from '../src/areas/staff/retail/receipt-share';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page receipt-name.layout.test.ts lays out in a real browser (issue #760): on the sale form, Add customer name and, once
// tapped, the name and phone for the receipt; then Send the receipt with a typed number, whose WhatsApp message carries the
// link to the receipt PDF. The facts and the link are put in the query cache, so nothing is asked of a server. ?open=1 opens
// the name, ?font= scales the root font size as a phone's large text setting does, ?theme=dark draws the dark theme. Every
// name, number and link is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;
if (query.get('theme') === 'dark') document.documentElement.dataset.theme = 'dark';

const me = { ...mockMe('sales'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const sale: Sale = {
  id: 's1', sale_no: 'RS00000071', branch_id: branch, sale_date: '2026-11-02', payment_method: 'cash', status: 'completed', version: 1,
  buyer_name: 'Test Customer 71 With A Rather Long Family Name', total_minor: 1250000, paid_minor: 1250000, balance_minor: 0, lines: [], created_by: me.user_id,
};
const facts: SaleReceipt = {
  sale_id: 's1', receipt_no: 'RS00000071', business_name: 'Test Hardware and Electricals', buyer_name: sale.buyer_name, customer_has_contact: false,
  payment_method: 'cash', currency: 'UGX', total_minor: 1250000, paid_minor: 1250000, balance_minor: 0, returned_minor: 0, net_minor: 1250000,
  credit_notes: [], sale_version: 1,
};
const link: ReceiptLink = { url: 'https://example.test/abcdefghijklmnopqrstuvwxyz', link: 'example.test/abcdefghijklmnopqrstuvwxyz', expires_at: '2027-01-31T09:00:00Z' };
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'sale-receipt', receiptKey(sale)], facts);
// ?nolink=1 leaves the link out, so the tap makes it (and, with no server here, fails to): the path a phone takes.
if (query.get('nolink') !== '1') client.setQueryData(['retail', 'sale-receipt-link', sale.id], link);
const router = layoutRouter('/staff/retail/sell');

function Page() {
  const [names, setNames] = useState<{ cashName?: string; cashPhone?: string }>(
    query.get('open') === '1' ? { cashName: 'Test Customer 71', cashPhone: '0700 000 071' } : {},
  );
  return (
    <StaffContext.Provider value={{ me, branch }}>
      <div className="app-shell"><div className="app-content"><div className="nav-layout">
        <div className="nav-layout-main">
          <section className="rt-card stack" aria-label="Sale form part">
            <CashCustomerName name={names.cashName ?? ''} phone={names.cashPhone ?? ''} onChange={(patch) => setNames((n) => ({ ...n, ...patch }))} />
          </section>
          <ShareReceipt sale={sale} madeHere />
        </div>
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
