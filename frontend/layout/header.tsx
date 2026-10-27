import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { mockMe } from '../src/api/retail-mock';
import { router as appRouter } from '../src/app/router';
import { StaffContext } from '../src/areas/staff/context';
import { ASK_STATUS_KEY } from '../src/areas/staff/help/ask';
import { StaffBar } from '../src/areas/staff/route';
import { OfflineProvider, type Offline } from '../src/offline/offline-context';
import { TourProvider } from '../src/tour/host';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The page header.layout.test.ts lays out in a real browser (issue #603): the staff top bar of an owner on the sale page, with
// a long branch name, the Ask button (?ask=1: Ask BMS on, else How do I?) and its New dot, under the app's own stylesheets.
// ?font= scales the root font size as a phone's large text setting does. Since issue #605 the bar also holds the sync
// indicator of a person who sells offline (?sync= online, offline or syncing; ?waiting= the sales queued) and the business name
// over the branch (?branch=, ?business=).
// Every name is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

const owner = mockMe('admin');
const me = {
  ...owner,
  tours: { 'tours-off': { status: 'dismissed', version: 1 } },
  branches: (owner.branches ?? []).map((b, i) => (i === 0 ? { ...b, name: query.get('branch') ?? 'Nakawa Market Road Wholesale and Retail Shop', logo_url: null } : b)),
} as unknown as Me;
const branch = me.branches?.[0]?.id ?? null;
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(ASK_STATUS_KEY, { enabled: query.get('ask') === '1', revision: 'layout' });
// This page's host, 127.0.0.1, read as a business's own address, so the title shows the business name from its branding.
client.setQueryData(['app-config'], { tenantHostPattern: '{slug}.0.0.1', platformHost: 'localhost' });
client.setQueryData(['branding'], { display_name: query.get('business') ?? 'Kabalagala Hardware Traders', logo_url: null });
// A manager who sells offline always has the sync indicator: ?sync= is online (the default), offline or syncing, and ?waiting=
// the number of sales queued on the phone.
const sync = query.get('sync') ?? 'online';
const queued = Array.from({ length: Number(query.get('waiting') ?? '0') }, (_, i) => ({
  id: `00000000-0000-4000-8000-00000000050${i}`,
  scope: 'layout',
  key: `layout-${i}`,
  occurredAt: `2026-10-26T08:0${i}:00Z`,
  branchId: branch,
  branchName: 'Test Branch A',
  method: 'cash',
  totalMinor: 12000,
  lines: [],
  provisionalNo: `OFF-0000050${i}`,
  state: 'queued',
  attempts: 0,
  nextAttemptAt: 0,
}));
const snapshot = { items: queued, syncing: sync === 'syncing', needsSignIn: false };
const offline = {
  manager: true,
  connectivity: { online: () => sync !== 'offline', subscribe: () => () => undefined },
  engine: { subscribe: () => () => undefined, snapshot: () => snapshot },
} as unknown as Offline;
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail/sale'] }) });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <OfflineProvider value={offline}>
          <TourProvider me={me} saveProgress={false}>
            <div className="app-shell">
              <StaffBar me={me} branch={branch} onBranch={() => undefined} onSignOut={() => undefined} />
            </div>
          </TourProvider>
        </OfflineProvider>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
