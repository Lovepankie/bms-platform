import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { PaymentIntent } from '../src/api/payments';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { CollectStatus, type CollectTarget } from '../src/areas/staff/lending/collect';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The panel collect-status.layout.test.ts lays out in a real browser (issue #231): a payment link waiting to be paid on a phone,
// with its QR code, the open and copy actions and the new "Send the link by text message" button, and a large amount. The test
// answers the API calls itself, so the page asks no server. ?font= scales the root font size as a phone's large text setting
// does. Every number and link is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const intent: PaymentIntent = {
  id: '22222222-2222-4222-8222-222222222231',
  status: 'pending',
  amount_minor: 123_456_789,
  currency: 'UGX',
  payer_phone: '********0231',
  redirect_url: 'https://cybqa.pesapal.com/pesapaliframe/PesapalIframe3/Index?OrderTrackingId=00000000-0000-4000-8000-000000000231',
  flags: [],
};
const target: CollectTarget = {
  kind: 'loan',
  id: '11111111-1111-4111-8111-111111111231',
  currency: 'UGX',
  suggested: null,
  amountHint: '',
  intro: 'the loan',
  start: async () => intent,
  invalidate: [],
  receiptKey: ['layout', 'receipt'],
  receipts: async () => [],
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });

createRoot(document.getElementById('root') as HTMLElement).render(
  <QueryClientProvider client={client}>
    <StaffContext.Provider value={{ me, branch: null }}>
      <div className="app-shell"><div className="app-content"><div className="nav-layout"><div className="nav-layout-main">
        <CollectStatus target={target} initial={intent} onNew={() => {}} />
      </div></div></div></div>
    </StaffContext.Provider>
  </QueryClientProvider>,
);
