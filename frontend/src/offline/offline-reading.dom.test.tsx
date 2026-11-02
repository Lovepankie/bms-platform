// @vitest-environment jsdom
import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api/client';
import { mockMe } from '../api/retail-mock';
import { StaffContext } from '../areas/staff/context';
import { OfflineProvider, useOfflineSupport, type Offline, type OfflineServices } from './offline-context';
import { testConnectivity } from './test-kit';
import { accessOf, keepReads, restore } from './read-cache';
import { memoryStore, scopeOf, type OfflineStore } from './store';

// Reading offline (issue #763, NFR-OFF-09, ADR-116): each screen a shop reads every day is opened once online, its figures
// are kept in the device store, then the app is opened again with no network and a new query cache: the screen shows the
// kept figures, read only, with no error of its own (the banner under the bar is the one offline notice). Every name and
// amount is invented.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return {
    ...real,
    Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a>,
    useNavigate: () => () => Promise.resolve(),
    useRouterState: () => ({ location: { pathname: '/staff/retail' } }),
  };
});

// The owner by default; the seller case below signs in as a seller who sees only their own sales, in two branches.
let me = mockMe('admin');
const branch = me.branches?.[0]?.id ?? '';
const SCOPE = scopeOf('shop-one.example.test', me.user_id ?? '');
const TODAY = new Date().toISOString().slice(0, 10);

/** The fabricated answers of the server, by path; anything else answers an empty list. */
type Routes = Record<string, unknown>;
let online = true;
let routes: Routes = {};

function serve() {
  const answer = (path: string) => {
    if (!online) return Promise.reject(new TypeError('Failed to fetch'));
    const data = path in routes ? routes[path] : { items: [] };
    return Promise.resolve({ data, response: new Response(null, { status: 200 }) });
  };
  vi.spyOn(api, 'GET').mockImplementation(((path: string) => answer(path)) as never);
}

const offlineSupport = (reachable: boolean) =>
  ({ connectivity: { online: () => reachable, subscribe: () => () => undefined }, engine: { subscribe: () => () => undefined, snapshot: () => ({ items: [], syncing: false, needsSignIn: false }) } }) as unknown as Offline;

function show(client: QueryClient, ui: ReactElement, reachable: boolean) {
  return render(
    <QueryClientProvider client={client}>
      <OfflineProvider value={offlineSupport(reachable)}>
        <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined, signOut: () => undefined, signingOut: false } as never}>{ui}</StaffContext.Provider>
      </OfflineProvider>
    </QueryClientProvider>,
  );
}

const newClient = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });

/** Opens the screen online until `seen` shows, keeps its reads, then opens it offline from the kept copy alone. */
async function readOffline(ui: ReactElement, seen: string, store: OfflineStore = memoryStore()) {
  online = true;
  onlineManager.setOnline(true);
  const first = newClient();
  const keeper = keepReads(first, store, SCOPE, accessOf(me), () => Date.now(), 0);
  show(first, ui, true);
  await screen.findAllByText(seen, { exact: false });
  await waitFor(async () => expect((await store.readCache(SCOPE))?.entries.length ?? 0).toBeGreaterThan(0));
  keeper.stop();
  cleanup();

  online = false;
  onlineManager.setOnline(false);
  const second = newClient();
  restore(second, await store.readCache(SCOPE), Date.now(), accessOf(me));
  show(second, ui, false);
  expect((await screen.findAllByText(seen, { exact: false })).length).toBeGreaterThan(0);
  expect(screen.queryByRole('alert')).toBeNull();
  return store;
}

beforeEach(() => {
  me = mockMe('admin');
  online = true;
  routes = {};
  serve();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  onlineManager.setOnline(true);
});

describe('reading offline from the kept figures', () => {
  it('shows the Home summary with no network', async () => {
    const { HomeCards } = await import('../areas/staff/home-cards');
    routes['/api/v1/retail/reports/home'] = { sales_today: { sales_minor: 4_200_000, sale_count: 7 } };
    await readOffline(<HomeCards />, '7 sales');
  });

  it('shows the Retail summary with no network', async () => {
    const { RetailDashboard } = await import('../areas/staff/retail/dashboard');
    routes['/api/v1/retail/reports/dashboard'] = { date: TODAY, currency: 'UGX', today: { sales_minor: 90_000, sale_count: 11, cash_minor: 90_000, credit_minor: 0 } };
    await readOffline(<RetailDashboard />, '11 sales');
  });

  it('shows the sales history with no network', async () => {
    const { AllSales } = await import('../areas/staff/retail/sales');
    routes['/api/v1/retail/sales/summary'] = { items: [{ key: branch, label: 'Test Shop Offline', sales_count: 3, total_minor: 150_000 }], has_more: false };
    await readOffline(<AllSales />, '3 sales');
  });

  it('shows the stock with no network', async () => {
    const { StockPage } = await import('../areas/staff/retail/stock');
    const row = { product_id: 'p-1', code: 'TB-01', description: 'Test bulb offline', unit: 'piece', on_hand: '12.000', branch_id: branch };
    routes['/api/v1/retail/stock'] = { items: [row] };
    routes['/api/v1/retail/stock/all-branches'] = { items: [{ ...row, branches: [{ branch_id: branch, on_hand: '12.000' }] }], branches: [] };
    routes['/api/v1/retail/categories'] = { items: [] };
    await readOffline(<StockPage />, 'Test bulb offline');
  });

  it('shows the customers who owe with no network', async () => {
    const { BuyersPage } = await import('../areas/staff/retail/catalogue-people');
    routes['/api/v1/retail/customers'] = { items: [{ id: 'c-1', name: 'Test Customer 01', contact: '+256700000001', balance_minor: 80_000, active: true }] };
    await readOffline(<BuyersPage />, 'Test Customer 01');
  });

  it('shows a seller the stock of their own branches with no network', async () => {
    me = mockMe('sales');
    const { StockPage } = await import('../areas/staff/retail/stock');
    const row = { product_id: 'p-2', code: 'TS-02', description: 'Test soap for the seller', unit: 'piece', on_hand: '4.000', branch_id: branch };
    routes['/api/v1/retail/stock'] = { items: [row] };
    routes['/api/v1/retail/stock/all-branches'] = { items: [{ ...row, branches: [{ branch_id: branch, on_hand: '4.000' }] }], branches: [] };
    routes['/api/v1/retail/categories'] = { items: [] };
    await readOffline(<StockPage />, 'Test soap for the seller');
  });

  it('shows past usage and damage with no network', async () => {
    const { UsageHistoryPage } = await import('../areas/staff/retail/usage-history');
    routes['/api/v1/retail/stock/movements'] = {
      items: [{ id: 'm-1', at: `${TODAY}T08:00:00Z`, branch_id: branch, product_id: 'p-1', code: 'TP-01', description: 'Test paint offline', unit: 'litre', kind: 'damage', qty: '-1.000', note: 'Tin fell' }],
    };
    await readOffline(<UsageHistoryPage />, 'Test paint offline');
  });

  it('shows the workbook already made (Download my data) with no network', async () => {
    const { DataExportCard } = await import('../areas/staff/data-export');
    const run = { id: 'r-1', report_key: 'core.data_export', title: 'Test workbook', format: 'xlsx', status: 'ready', params: {}, as_of: TODAY, snapshot_at: null, requested_at: `${TODAY}T07:00:00Z`, started_at: null, finished_at: `${TODAY}T07:01:00Z`, row_count: 12, size_bytes: 2048, document_id: 'd-1', expires_at: null, expired: false, cancel_requested: false, error: null };
    routes['/api/v1/data-export'] = { weekly_email: true, owner: true, last_export_at: `${TODAY}T07:01:00Z`, last_emailed_at: null, latest: run };
    await readOffline(<DataExportCard modules={['retail']} zone="Africa/Kampala" />, 'Your workbook is ready');
  });
});

// JUSTIFICATION-A3: the end to end case the third #765 review asked for, through the hook the staff layout uses.
describe('reading offline through the offline support of the staff layout', () => {
  it('opens the stock page with no network and shows the kept figures once the hook has put them back', async () => {
    me = mockMe('sales');
    const { StockPage } = await import('../areas/staff/retail/stock');
    const row = { product_id: 'p-3', code: 'TC-03', description: 'Test cable through the hook', unit: 'metre', on_hand: '30.000', branch_id: branch };
    routes['/api/v1/retail/stock'] = { items: [row] };
    routes['/api/v1/retail/stock/all-branches'] = { items: [{ ...row, branches: [{ branch_id: branch, on_hand: '30.000' }] }], branches: [] };
    routes['/api/v1/retail/categories'] = { items: [] };
    // Loaded once online, kept for this person on this business.
    const store = memoryStore();
    const first = newClient();
    const keeper = keepReads(first, store, SCOPE, accessOf(me), () => Date.now(), 0);
    show(first, <StockPage />, true);
    await screen.findAllByText('Test cable through the hook', { exact: false });
    await waitFor(async () => expect((await store.readCache(SCOPE))?.entries.length ?? 0).toBeGreaterThan(0));
    keeper.stop();
    cleanup();

    // Opened again with no network: the screen mounts paused, then the hook restores the copy.
    online = false;
    const connectivity = testConnectivity(false);
    const svc: OfflineServices = {
      store,
      connectivity,
      host: 'shop-one.example.test',
      now: () => new Date(),
      source: { products: async () => ({ items: [] }), customers: async () => [] },
      reportWaiting: async () => undefined,
      engineDeps: (s, scope) => ({ store: s, scope, send: async () => ({ status: 503, body: {} }), refreshSession: async () => false, report: async (i) => ({ id: i.id, client_sale_id: i.id, status: 'open' }), decisions: async () => [], now: () => 0, later: () => () => {} }),
    };
    const services = () => svc;
    const client = newClient();
    function Layout() {
      const offline = useOfflineSupport(me, false, services, client);
      return (
        <OfflineProvider value={offline}>
          <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined, signOut: () => undefined, signingOut: false } as never}>
            <StockPage />
          </StaffContext.Provider>
        </OfflineProvider>
      );
    }
    render(
      <QueryClientProvider client={client}>
        <Layout />
      </QueryClientProvider>,
    );
    expect((await screen.findAllByText('Test cable through the hook', { exact: false })).length).toBeGreaterThan(0);
    expect(onlineManager.isOnline()).toBe(false);
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
