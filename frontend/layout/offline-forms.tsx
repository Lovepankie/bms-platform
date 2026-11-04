import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { businessToday, type ExpenseCategory } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { setAccessToken } from '../src/auth/session';
import { StaffContext } from '../src/areas/staff/context';
import { ExpenseForm } from '../src/areas/staff/retail/expenses';
import { offlineEntry } from '../src/offline/entries';
import { OfflineProvider } from '../src/offline/offline-context';
import { SyncStatus } from '../src/offline/sync-status';
import { testOffline } from '../src/offline/test-kit';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page offline-forms.layout.test.ts opens in a real browser (issue #812): the expense form of
// an administrator, `?mode=offline` with the phone offline (the offline note, then Saved on this phone), `?mode=online` with the
// real API client posting to /api (the test answers it through a throttled network), and `?mode=sheet` with the sync sheet
// holding a refused expense and one waiting. The lists are in the query cache. Every name and amount is invented.

const mode = new URLSearchParams(window.location.search).get('mode') ?? 'offline';
const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const categories: ExpenseCategory[] = [
  { id: 'c1', name: 'Transport', active: true, version: 1, items: [{ id: 'i1', name: 'Fuel', requires_explanation: false, active: true, version: 1 }] },
];
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'expense-categories', true], categories);
client.setQueryData(['retail', 'cash-parties'], []);
setAccessToken('layout-token');
sessionStorage.setItem(`retail-draft:expense:${me.user_id ?? ''}:${branch}`, JSON.stringify({
  key: '00000000-0000-4000-8000-000000000812',
  // Today's date: an earlier day would ask the owner why (ADR-120) and hold the save.
  draft: { date: businessToday(), categoryId: 'c1', itemId: 'i1', partyId: '', amount: '20000', explanation: '' },
}));

const offline = testOffline(me, {
  online: mode !== 'offline',
  deps: {
    send: async (item) => {
      const r = await fetch(item.entry?.path ?? '', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': item.key }, body: JSON.stringify(item.entry?.body) });
      return { status: r.status, body: await r.json().catch(() => ({})) };
    },
  },
});

if (mode === 'sheet' && offline) {
  const at = new Date();
  const made = (key: string, minutes: number, detail: string) => offlineEntry({
    kind: 'expense', key, scope: offline.scope, branchId: branch, branchName: 'Test Shop A', body: { branch_id: branch, amount_minor: 20000 },
    detail, amountMinor: 20000, now: new Date(at.getTime() - minutes * 60_000),
    fix: { to: '/staff/retail/expenses', draftName: `expense:${me.user_id ?? ''}:${branch}`, draft: {} },
  });
  const refused = { ...made('k-refused', 30, 'Transport, Fuel'), state: 'failed' as const, attempts: 1, error: { code: 'validation_failed', message: 'This item is switched off. Choose another one.' } };
  void offline.store.putItem(refused).then(() => offline.store.putItem(made('k-waiting', 10, 'Cleaning, Soap'))).then(() => offline.engine.load());
}

function Page() {
  const [saved, setSaved] = useState(false);
  return (
    <div className="app-shell"><div className="app-content"><main>
      {mode === 'sheet' ? <SyncStatus /> : saved ? <p data-testid="saved-online">Expense saved</p> : <ExpenseForm branchId={branch} onSaved={() => setSaved(true)} />}
    </main></div></div>
  );
}

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/expenses')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <OfflineProvider value={offline}>
          <Page />
        </OfflineProvider>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
