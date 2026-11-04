import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { businessToday } from '../src/api/retail';
import type { DamageShare } from '../src/api/retail-analytics';
import { mockMe } from '../src/api/retail-mock';
import { presetRange } from '../src/app/ui/PeriodPicker';
import { ALL_BRANCHES } from '../src/auth/branch';
import { StaffContext } from '../src/areas/staff/context';
import { DamageShareSection } from '../src/areas/staff/retail/damage-share';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page damage-share.layout.test.ts lays out in a real browser (issue #822, ADR-127): Stock health's damage and use
// section for an owner, with its period picker and a shop of long item names. The report is in the query cache, so nothing is
// fetched. Every name and figure is invented.

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const days = presetRange('month', businessToday());
const data: DamageShare = {
  from: days.from,
  to: days.to,
  branches: [
    {
      branch_id: branch, base_qty: '12500.000', damaged_qty: '600.000', used_qty: '250.000', damaged_pct: 5, used_pct: 2,
      items: [
        { product_id: 'p1', code: 'TI-01', description: 'Test Item 01 with a long name that wraps on the phone', unit: 'piece', opening_qty: '4000.000', received_qty: '1000.000', base_qty: '5000.000', damaged_qty: '500.000', used_qty: '200.000', damaged_pct: 10, used_pct: 4 },
        { product_id: 'p2', code: 'TI-02', description: 'Test Item 02', unit: 'kilogram', opening_qty: '0.000', received_qty: '0.000', base_qty: '0.000', damaged_qty: '1.500', used_qty: '0.000' },
      ],
    },
  ],
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'damage-share', 'all', days.from, days.to], data);
const nameOf = () => 'Test Branch A with a long name for the phone';

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/stock-health')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: ALL_BRANCHES }}>
        <div className="app-shell"><div className="app-content"><main>
          <DamageShareSection nameOf={nameOf} />
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
