import { QueryClient, onlineManager, type QueryKey } from '@tanstack/react-query';
import { afterEach, describe, expect, it } from 'vitest';
import { markDropped } from '../api/client';
import { RetailError, setBusinessZone } from '../api/retail';
import { mockMe } from '../api/retail-mock';
import { KEEP_DAYS, accessOf, figuresTime, followConnectivity, keepReads, keptForReading, merged, restore, trimmed, type ReadEntry } from './read-cache';
import { memoryStore, scopeOf } from './store';
import { testConnectivity } from './test-kit';

// The figures kept for reading offline (issue #763, NFR-OFF-09, ADR-116). Every name, id and amount is invented.

const NOW = Date.parse('2026-11-02T12:00:00Z');
const DAY = 86_400_000;
const SHOP = scopeOf('shop-one.example.test', 'u1');
const ADMIN = accessOf(mockMe('admin'));

const entry = (key: unknown[], data: unknown, at: number): ReadEntry =>
  ({ queryKey: key, queryHash: JSON.stringify(key), state: { data, dataUpdatedAt: at, status: 'success' } }) as unknown as ReadEntry;

/** A read answered by the server (a fetch), as a screen makes it. */
const fetched = (client: QueryClient, queryKey: QueryKey, data: unknown) => client.fetchQuery({ queryKey, queryFn: async () => data });
const failed = (client: QueryClient, queryKey: QueryKey, error: unknown) => client.fetchQuery({ queryKey, queryFn: () => Promise.reject(error), retry: false }).catch(() => undefined);
const settle = () => new Promise((r) => setTimeout(r, 20));

afterEach(() => onlineManager.setOnline(true));

describe('what is kept', () => {
  it('keeps the screens a shop reads every day and never lending, the profile or settings', () => {
    for (const key of [
      ['retail', 'home', 'all'],
      ['retail', 'dashboard', 'all', '7d', '', ''],
      ['retail', 'sales', { method: 'cash' }],
      ['retail', 'sales', 'summary', 'branch', {}, {}],
      ['retail', 'stock', 'b1', '', '', false, '', false],
      ['retail', 'catalogue', 'buyers', ''],
      ['retail', 'accounts', ''],
      ['retail', 'orders', 'board', 'all'],
      ['retail', 'usage-history', '2026-10-01', '2026-11-02', 'all', ''],
      ['data-export'],
      ['reports', 'runs'],
    ]) {
      expect(keptForReading(key), JSON.stringify(key)).toBe(true);
    }
    for (const key of [['lending', 'home-summary', 'all'], ['me', 'token'], ['settings'], ['members', 'b1', ''], ['retail', 'catalogue', 'suppliers', ''], ['retail', 'products', 'b1', '']]) {
      expect(keptForReading(key), JSON.stringify(key)).toBe(false);
    }
  });

  it('keeps the sales of the last 30 days and older credit sales still owing, and every group of the sales chain', () => {
    setBusinessZone('Africa/Kampala');
    const page = { items: [{ id: 'new', sale_date: '2026-10-30', balance_minor: 0 }, { id: 'old', sale_date: '2026-09-01', balance_minor: 0 }, { id: 'owing', sale_date: '2026-08-01', balance_minor: 5000 }] };
    const kept = trimmed(['retail', 'sales', {}], { pages: [page], pageParams: [null] }, NOW) as { pages: { items: { id: string }[] }[] };
    expect(kept.pages[0]?.items.map((s) => s.id)).toEqual(['new', 'owing']);
    const groups = { pages: [{ items: [{ key: 'b1', sales_count: 3 }] }], pageParams: [null] };
    expect(trimmed(['retail', 'sales', 'summary', 'branch', {}, {}], groups, NOW)).toEqual(groups);
    // A sale opened from the list: kept while recent or owing, dropped otherwise.
    expect(trimmed(['retail', 'sale', 's1'], { sale_date: '2026-09-01', balance_minor: 0 }, NOW)).toBeUndefined();
    expect(trimmed(['retail', 'sale', 's2'], { sale_date: '2026-09-01', balance_minor: 100 }, NOW)).toEqual({ sale_date: '2026-09-01', balance_minor: 100 });
  });

  it('leaves lending reports out of the reports lists, in every shape a list comes in', () => {
    // GET /report-runs and GET /reports answer bare lists (schema.d.ts: ReportRun[]).
    const run = (id: string, key: string) => ({ id, report_key: key, title: `Test ${id}`, status: 'ready' });
    expect(trimmed(['reports', 'runs'], [run('r1', 'retail.test'), run('r2', 'lending.par')], NOW)).toEqual([run('r1', 'retail.test')]);
    expect(trimmed(['reports', 'catalogue'], [{ key: 'lending.par' }, { key: 'retail.test' }], NOW)).toEqual([{ key: 'retail.test' }]);
    expect(trimmed(['reports', 'runs'], { items: [run('r1', 'retail.test'), run('r2', 'lending.par')] }, NOW)).toEqual({ items: [run('r1', 'retail.test')] });
    expect(trimmed(['reports', 'runs'], { pages: [{ items: [run('r2', 'lending.par')] }], pageParams: [null] }, NOW)).toEqual({ pages: [{ items: [] }], pageParams: [null] });
    // A single run or catalogue entry read on its own: a lending one is not kept at all.
    expect(trimmed(['reports', 'runs', 'r2'], run('r2', 'lending.par'), NOW)).toBeUndefined();
    expect(trimmed(['reports', 'catalogue', 'lending.par'], { key: 'lending.par' }, NOW)).toBeUndefined();
    expect(trimmed(['reports', 'runs', 'r1'], run('r1', 'retail.test'), NOW)).toEqual(run('r1', 'retail.test'));
  });
});

describe('the size cap', () => {
  it('keeps the newest reads first, within the cap, and drops reads older than 30 days', () => {
    const big = 'x'.repeat(400);
    const before = [entry(['retail', 'home', 'b1'], big, NOW - 3 * DAY), entry(['retail', 'stock', 'b1'], big, NOW - (KEEP_DAYS + 1) * DAY)];
    const fresh = [entry(['retail', 'home', 'b2'], big, NOW - DAY), entry(['retail', 'sales', {}], big, NOW)];
    const kept = merged(before, fresh, NOW, 1200);
    expect(kept.map((e) => e.queryKey)).toEqual([['retail', 'sales', {}], ['retail', 'home', 'b2']]);
    expect(JSON.stringify(kept).length).toBeLessThanOrEqual(1200);
  });

  it('replaces a kept read with a newer load of the same screen', () => {
    const kept = merged([entry(['retail', 'home', 'b1'], 'old', NOW - DAY)], [entry(['retail', 'home', 'b1'], 'new', NOW)], NOW);
    expect(kept.map((e) => e.state.data)).toEqual(['new']);
  });
});

describe('keeping and restoring', () => {
  it('writes what the server answered to this person on this business, and another business on the same phone never sees it', async () => {
    const store = memoryStore();
    const client = new QueryClient();
    const keeper = keepReads(client, store, SHOP, ADMIN, () => Date.now(), 0);
    await fetched(client, ['retail', 'home', 'all'], { title: 'Test figures of shop one' });
    await fetched(client, ['lending', 'home-summary', 'all'], { title: 'Test lending figures' });
    await expect.poll(async () => (await store.readCache(SHOP))?.entries.length ?? 0).toBe(1);
    keeper.stop();
    expect(JSON.stringify(await store.readCache(SHOP))).not.toContain('lending');

    const other = new QueryClient();
    restore(other, await store.readCache(scopeOf('shop-two.example.test', 'u1')), Date.now(), ADMIN);
    restore(other, await store.readCache(scopeOf('shop-one.example.test', 'u2')), Date.now(), ADMIN);
    expect(other.getQueryCache().getAll()).toHaveLength(0);

    const again = new QueryClient();
    restore(again, await store.readCache(SHOP), Date.now(), ADMIN);
    expect(again.getQueryData(['retail', 'home', 'all'])).toEqual({ title: 'Test figures of shop one' });
  });

  it('never keeps what the app wrote into the cache itself, such as a sale not yet sent', async () => {
    const store = memoryStore();
    const client = new QueryClient();
    const keeper = keepReads(client, store, SHOP, ADMIN, () => Date.now(), 0);
    await fetched(client, ['retail', 'stock', 'b1'], { items: [{ code: 'TB-01', on_hand: '12.000' }] });
    client.setQueryData(['retail', 'stock', 'b1'], { items: [{ code: 'TB-01', on_hand: '11.000' }] });
    client.setQueryData(['retail', 'sale', 'off-1'], { id: 'off-1', sale_date: '2026-11-02' });
    await settle();
    keeper.stop();
    const kept = JSON.stringify(await store.readCache(SHOP));
    expect(kept).toContain('12.000');
    expect(kept).not.toContain('11.000');
    expect(kept).not.toContain('off-1');
  });

  it('throws away a copy made under other rights: a manager made a seller, or a branch taken away', async () => {
    const store = memoryStore();
    const client = new QueryClient();
    const keeper = keepReads(client, store, SHOP, ADMIN, () => Date.now(), 0);
    await fetched(client, ['retail', 'home', 'all'], { title: 'Test shop wide totals' });
    await expect.poll(async () => (await store.readCache(SHOP)) !== null).toBe(true);
    keeper.stop();
    const admin = mockMe('admin');
    const seller = accessOf(mockMe('sales'));
    const fewerBranches = accessOf({ ...admin, branches: (admin.branches ?? []).slice(0, 1) });
    expect(seller).not.toBe(ADMIN);
    expect(fewerBranches).not.toBe(ADMIN);
    for (const now of [seller, fewerBranches]) {
      const next = new QueryClient();
      expect(restore(next, await store.readCache(SHOP), Date.now(), now)).toEqual([]);
      expect(next.getQueryData(['retail', 'home', 'all'])).toBeUndefined();
    }
  });

  it('never puts back figures older than what the screen already holds', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'home', 'all'], 'fresh', { updatedAt: NOW });
    restore(client, { savedAt: NOW, access: ADMIN, entries: [entry(['retail', 'home', 'all'], 'kept', NOW - DAY)] }, NOW, ADMIN);
    expect(client.getQueryData(['retail', 'home', 'all'])).toBe('fresh');
  });

  it('at sign-out removes the copy and the kept reads in memory, so the next person in the same tab never gets them', async () => {
    const store = memoryStore();
    const client = new QueryClient();
    const first = keepReads(client, store, SHOP, ADMIN, () => Date.now(), 0);
    await fetched(client, ['retail', 'customers'], [{ name: 'Test Customer of person one' }]);
    await expect.poll(async () => (await store.readCache(SHOP)) !== null).toBe(true);
    await first.forget();
    expect(await store.readCache(SHOP)).toBeNull();
    expect(client.getQueryData(['retail', 'customers'])).toBeUndefined();

    // Person two signs in on the same tab and the same query cache; a read the first person left would not be theirs either.
    client.setQueryData(['retail', 'stock', 'b1'], { items: ['Test item of person one'] });
    const next = scopeOf('shop-one.example.test', 'u2');
    const second = keepReads(client, store, next, ADMIN, () => Date.now(), 0);
    await fetched(client, ['retail', 'home', 'all'], { title: 'Test figures of person two' });
    await expect.poll(async () => (await store.readCache(next)) !== null).toBe(true);
    second.stop();
    const theirs = JSON.stringify(await store.readCache(next));
    expect(theirs).toContain('person two');
    expect(theirs).not.toContain('person one');
    expect(await store.readCache(SHOP)).toBeNull();
  });

  it('at sign-out clears memory first, even when the device store fails', async () => {
    const store = { ...memoryStore(), deleteReadCache: () => Promise.reject(new DOMException('Quota', 'QuotaExceededError')) };
    const client = new QueryClient();
    const keeper = keepReads(client, store, SHOP, ADMIN, () => Date.now(), 0);
    await fetched(client, ['retail', 'customers'], [{ name: 'Test Customer 02' }]);
    await expect(keeper.forget()).rejects.toThrow();
    expect(client.getQueryData(['retail', 'customers'])).toBeUndefined();
  });
});

describe('offline', () => {
  it('pauses reads while the offline support says offline, and quiets only kept reads the network dropped', async () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'stock', 'b1'], { items: ['Test item'] });
    // A dropped connection as the API client marks it; and a TypeError of the app's own code, which stays on screen.
    const dropped = markDropped(new TypeError('Failed to fetch'));
    await failed(client, ['retail', 'stock', 'b1'], dropped);
    await failed(client, ['retail', 'sales', {}], dropped);
    await failed(client, ['lending', 'home-summary', 'all'], dropped);
    await failed(client, ['retail', 'customers'], new TypeError("Cannot read properties of undefined (reading 'items')"));
    // The app's own error for an answer the server sent: a refusal and a server error.
    await failed(client, ['retail', 'sale', 's-9'], new RetailError('Not found', 404, 'not_found'));
    await failed(client, ['retail', 'home', 'all'], new RetailError('Server error', 500));
    const connectivity = testConnectivity(true);
    const undo = followConnectivity(client, connectivity);
    connectivity.set(false);
    expect(onlineManager.isOnline()).toBe(false);
    const status = (key: QueryKey) => client.getQueryState(key)?.status;
    expect(status(['retail', 'stock', 'b1'])).toBe('success');
    expect(client.getQueryData(['retail', 'stock', 'b1'])).toEqual({ items: ['Test item'] });
    expect(status(['retail', 'sales', {}])).toBe('pending');
    expect(status(['lending', 'home-summary', 'all'])).toBe('error');
    expect(status(['retail', 'customers'])).toBe('error');
    expect(status(['retail', 'sale', 's-9'])).toBe('error');
    expect(status(['retail', 'home', 'all'])).toBe('error');
    connectivity.set(true);
    expect(onlineManager.isOnline()).toBe(true);
    undo();
  });

  it('quiets a kept read that fails for a dropped connection while already offline, as it fails', async () => {
    const client = new QueryClient();
    const connectivity = testConnectivity(false);
    const undo = followConnectivity(client, connectivity);
    // A read that does not pause offline (networkMode always) and fails between two pings.
    await client.fetchQuery({ queryKey: ['retail', 'usage-history'], queryFn: () => Promise.reject(markDropped(new TypeError('Failed to fetch'))), retry: false, networkMode: 'always' }).catch(() => undefined);
    expect(client.getQueryState(['retail', 'usage-history'])?.status).toBe('pending');
    undo();
  });

  it('says the time of the figures in the business time zone, today only for this year', () => {
    expect(figuresTime(Date.parse('2026-11-02T11:05:00Z'), NOW, 'Africa/Kampala')).toBe('today at 14:05');
    expect(figuresTime(Date.parse('2026-10-31T06:30:00Z'), NOW, 'Africa/Kampala')).toBe('31 Oct at 09:30');
    expect(figuresTime(Date.parse('2025-11-02T11:05:00Z'), NOW, 'Africa/Kampala')).toBe('2 Nov at 14:05');
  });
});
