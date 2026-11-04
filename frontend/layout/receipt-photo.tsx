import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { Sale } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { ReceiptPhoto, ReceiptPhotoChoice } from '../src/areas/staff/retail/receipt-photo';
import { SaleDetail } from '../src/areas/staff/retail/sales';
import { OfflineProvider } from '../src/offline/offline-context';
import { testOffline } from '../src/offline/test-kit';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page receipt-photo.layout.test.ts lays out in a real browser (issue #819, ADR-124): the form's Add receipt photo with a
// photo chosen, the same button offline giving way to its words, a sale with its receipt photo and an expense row that offers
// to add one. The photo is a small fabricated picture held in the query cache, so nothing is fetched.

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const sale = { id: 's1', sale_no: 'S-0001', branch_id: branch, sale_date: '2026-11-03', payment_method: 'cash', status: 'completed', total_minor: 1500, lines: [], has_receipt_photo: true } as Sale;

// A 2 by 2 grey PNG.
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAAAAABX3VL4AAAAEUlEQVR4nGPg4uLi4uJiYAAAAeQAMexBLGcAAAAASUVORK5CYII=';
const bytes = Uint8Array.from(atob(PNG), (c) => c.charCodeAt(0));
const picture = new File([bytes], 'receipt with a long name from the phone camera.png', { type: 'image/png' });

const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'receipt-photo', 'sale', 's1'], new Blob([bytes], { type: 'image/png' }));
client.setQueryData(['retail', 'entry-distances', 'sale', ['s1']], []);

function Chosen() {
  const [file, setFile] = useState<File | null>(picture);
  return <ReceiptPhotoChoice id="chosen-receipt" file={file} onChange={setFile} />;
}

function Offline() {
  const [file, setFile] = useState<File | null>(null);
  return (
    <OfflineProvider value={testOffline(me, { online: false })}>
      <ReceiptPhotoChoice id="offline-receipt" file={file} onChange={setFile} />
    </OfflineProvider>
  );
}

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/expenses')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><main>
          <Chosen />
          <Offline />
          <SaleDetail sale={sale} today="2026-11-03" />
          <div data-testid="expense-row"><ReceiptPhoto kind="expense" recordId="e1" has={false} canAdd /></div>
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
