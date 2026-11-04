// @vitest-environment jsdom
import { QueryClient } from '@tanstack/react-query';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../api/client';
import { mockMe } from '../api/retail-mock';
import { setAccessToken } from '../auth/session';
import { ENTRY_PERMISSIONS, forgetReads, othersOnPhone, summaries, useOfflineSupport, type OfflineServices } from './offline-context';
import { accessOf } from './read-cache';
import { provisionalNumber, type OutboxItem } from './outbox';
import { memoryStore, scopeOf, type OfflineStore } from './store';
import { testConnectivity } from './test-kit';

// The offline support of the signed-in person (issue #458, ADR-067 decision 9): on a shared phone, the sales another person
// left queued here are counted for the notice and, while online, told to the server in short so managers see them waiting
// on a phone. Fabricated people, ids and amounts.

const HOST = 'test.example';

afterEach(cleanup);

function item(id: string, userId: string, patch: Partial<OutboxItem> = {}): OutboxItem {
  return {
    id,
    scope: scopeOf(HOST, userId),
    key: `key-${id}`,
    request: { payment_method: 'cash', lines: [] },
    occurredAt: '2026-10-22T08:00:00.000Z',
    branchId: 'b1',
    branchName: 'Test Branch A',
    method: 'cash',
    totalMinor: 12000,
    lines: [],
    provisionalNo: provisionalNumber(`${id}0000000`),
    state: 'queued',
    attempts: 0,
    nextAttemptAt: 0,
    ...patch,
  };
}

function services(store: OfflineStore, reportWaiting: OfflineServices['reportWaiting']): () => OfflineServices {
  const svc: OfflineServices = {
    store,
    connectivity: testConnectivity(true),
    host: HOST,
    now: () => new Date('2026-10-22T09:00:00Z'),
    source: { products: async () => ({ items: [] }), customers: async () => [] },
    reportWaiting,
    engineDeps: (s, scope) => ({
      store: s,
      scope,
      send: async () => ({ status: 503, body: {} }),
      refreshSession: async () => false,
      report: async (i) => ({ id: i.id, client_sale_id: i.id, status: 'open' }),
      decisions: async () => [],
      now: () => 0,
      later: () => () => {},
    }),
  };
  return () => svc;
}

describe('a shared phone', () => {
  it("counts another person's waiting sales and tells the server in short, never sending them", async () => {
    const store = memoryStore();
    await store.putItem(item('a', 'first-person'));
    await store.putItem(item('b', 'first-person', { state: 'sent' }));
    await store.putItem(item('c', 'first-person', { scope: scopeOf('other.example', 'first-person') }));
    const reportWaiting = vi.fn(async () => {});
    const me: Me = { ...mockMe('sales'), user_id: 'second-person' };
    const make = services(store, reportWaiting);
    const { result } = renderHook(() => useOfflineSupport(me, true, make));
    await waitFor(() => expect(result.current?.othersWaiting).toBe(1));
    await waitFor(() => expect(reportWaiting).toHaveBeenCalled());
    expect(reportWaiting.mock.calls.at(-1)).toEqual([
      [{ client_sale_id: 'a', branch_id: 'b1', made_by: 'first-person', occurred_at: '2026-10-22T08:00:00.000Z', total_minor: 12000 }],
    ]);
    expect(result.current?.engine.snapshot().items).toEqual([]);
  });

  it('asks the browser to keep the outbox when offline selling is mounted', async () => {
    const persist = vi.fn(async () => true);
    Object.defineProperty(navigator, 'storage', { value: { persist }, configurable: true });
    try {
      const make = services(memoryStore(), async () => {});
      const me: Me = { ...mockMe('sales'), user_id: 'u1' };
      renderHook(() => useOfflineSupport(me, true, make));
      await waitFor(() => expect(persist).toHaveBeenCalled());
    } finally {
      Reflect.deleteProperty(navigator, 'storage');
    }
  });

  it('goes on sending as soon as a new access token arrives', async () => {
    const store = memoryStore();
    const send = vi.fn(async (): Promise<{ status: number; body: unknown }> => ({ status: 401, body: {} }));
    const base = services(store, async () => {})();
    const svc: OfflineServices = { ...base, engineDeps: (s, scope) => ({ ...base.engineDeps(s, scope), send }) };
    const me: Me = { ...mockMe('sales'), user_id: 'u1' };
    const { result } = renderHook(() => useOfflineSupport(me, false, () => svc));
    await waitFor(() => expect(result.current).not.toBeNull());
    const engine = result.current!.engine;
    await engine.queue(item('a', 'u1'));
    await engine.sync();
    expect(engine.snapshot().needsSignIn).toBe(true);
    send.mockImplementation(async () => ({ status: 201, body: { id: 's1', sale_no: 'RS1' } }));
    act(() => setAccessToken('fresh-token'));
    await waitFor(() => expect(engine.snapshot().items[0]?.state).toBe('sent'));
    act(() => setAccessToken(null));
  });

  it('forgets the catalogue and the saved profile at sign-out, and keeps the outbox', async () => {
    const store = memoryStore();
    const me: Me = { ...mockMe('sales'), user_id: 'u1' };
    const scope = scopeOf(HOST, 'u1');
    await store.putProfile(scope, me);
    await store.putCatalogue(scope, { pricesAsOf: 'x', products: {}, customers: [] });
    await store.putItem(item('a', 'u1'));
    const make = services(store, async () => {});
    const { result } = renderHook(() => useOfflineSupport(me, false, make));
    await waitFor(() => expect(result.current).not.toBeNull());
    await act(() => result.current!.forget());
    expect(await store.profile(scope)).toBeNull();
    expect(await store.catalogue(scope)).toBeNull();
    expect(await store.lastPerson(HOST)).toBeNull();
    expect(await store.outbox(scope)).toHaveLength(1);
  });

  // JUSTIFICATION-A3: a new test case for issue #763 (scope isolation and sign-out of the kept figures) beside the sign-out test it extends.
  it('brings back only this person on this business their kept figures, and forgets them at sign-out (issue #763)', async () => {
    const store = memoryStore();
    const me: Me = { ...mockMe('sales'), user_id: 'u1' };
    const kept = (title: string) => ({
      savedAt: 1,
      access: accessOf(me),
      entries: [{ queryKey: ['retail', 'home', 'all'], queryHash: '["retail","home","all"]', state: { data: { title }, dataUpdatedAt: Date.now() - 60_000, status: 'success' } }],
    }) as never;
    // The same phone also holds another business (another host) and another person of this business.
    await store.putReadCache(scopeOf(HOST, 'u1'), kept('Test figures of this business'));
    await store.putReadCache(scopeOf('other-shop.example', 'u1'), kept('Test figures of another business'));
    await store.putReadCache(scopeOf(HOST, 'u2'), kept('Test figures of another person'));
    const client = new QueryClient();
    const { result } = renderHook(() => useOfflineSupport(me, false, services(store, async () => {}), client));
    await waitFor(() => expect(client.getQueryData(['retail', 'home', 'all'])).toEqual({ title: 'Test figures of this business' }));

    await act(() => result.current!.forget());
    expect(await store.readCache(scopeOf(HOST, 'u1'))).toBeNull();
    // The kept reads left memory with the copy.
    expect(client.getQueryData(['retail', 'home', 'all'])).toBeUndefined();
    // Nothing loaded after sign-out is written back.
    client.setQueryData(['retail', 'home', 'all'], { title: 'Test figures after sign-out' });
    await new Promise((r) => setTimeout(r, 50));
    expect(await store.readCache(scopeOf(HOST, 'u1'))).toBeNull();
    // The other business and the other person keep theirs.
    expect(JSON.stringify(await store.readCache(scopeOf('other-shop.example', 'u1')))).toContain('another business');
    expect(JSON.stringify(await store.readCache(scopeOf(HOST, 'u2')))).toContain('another person');
  });

  // JUSTIFICATION-A3: a new test case for the #765 review (points 1 and 3: rights changed, selling taken away) beside the case it extends.
  it('throws away a copy made under other rights, and the copy of a person who no longer sells (issue #763)', async () => {
    const store = memoryStore();
    const seller: Me = { ...mockMe('sales'), user_id: 'u1' };
    const copy = (access: string) =>
      ({ savedAt: 1, access, entries: [{ queryKey: ['retail', 'home', 'all'], queryHash: '["retail","home","all"]', state: { data: { title: 'Test shop wide totals' }, dataUpdatedAt: Date.now(), status: 'success' } }] }) as never;
    await store.putReadCache(scopeOf(HOST, 'u1'), copy(accessOf(mockMe('admin'))));
    const client = new QueryClient();
    renderHook(() => useOfflineSupport(seller, false, services(store, async () => {}), client));
    await waitFor(async () => expect(await store.readCache(scopeOf(HOST, 'u1'))).toBeNull());
    expect(client.getQueryData(['retail', 'home', 'all'])).toBeUndefined();
    cleanup();

    // Issue #812: the daily forms kept offline give offline support too, so the watcher holds none of them either.
    const watcher: Me = { ...seller, permissions: (seller.permissions ?? []).filter((p) => p !== 'retail.sale.create' && !ENTRY_PERMISSIONS.includes(p)) };
    await store.putReadCache(scopeOf(HOST, 'u1'), copy(accessOf(watcher)));
    const { result } = renderHook(() => useOfflineSupport(watcher, false, services(store, async () => {}), client));
    expect(result.current).toBeNull();
    await waitFor(async () => expect(await store.readCache(scopeOf(HOST, 'u1'))).toBeNull());
  });

  // JUSTIFICATION-A3: new cases for the second #765 review (rights changed mid-session, opened offline, sign-out without offline support).
  it('drops from memory what was read under the old rights when the rights change in the session (issue #763)', async () => {
    const store = memoryStore();
    const manager: Me = { ...mockMe('admin'), user_id: 'u1' };
    const client = new QueryClient();
    const make = services(store, async () => {});
    const { rerender } = renderHook(({ who }) => useOfflineSupport(who, false, make, client), { initialProps: { who: manager } });
    await waitFor(() => expect(client.getQueryCache().getAll().length).toBeGreaterThanOrEqual(0));
    await client.fetchQuery({ queryKey: ['retail', 'home', 'all'], queryFn: async () => ({ title: 'Test shop wide totals' }) });
    // The profile refresh brings the rights of a seller.
    rerender({ who: { ...mockMe('sales'), user_id: 'u1' } });
    await waitFor(() => expect(client.getQueryData(['retail', 'home', 'all'])).toBeUndefined());
  });

  // JUSTIFICATION-A3: new cases for the third #765 review (selling taken away mid-session, Wi-Fi with no internet on open).
  it('drops from memory what was read before selling was taken away, when the person no longer has offline support (issue #763)', async () => {
    const store = memoryStore();
    const seller: Me = { ...mockMe('sales'), user_id: 'u1' };
    const client = new QueryClient();
    const make = services(store, async () => {});
    const { rerender, result } = renderHook(({ who }) => useOfflineSupport(who, false, make, client), { initialProps: { who: seller } });
    await waitFor(() => expect(result.current).not.toBeNull());
    await client.fetchQuery({ queryKey: ['retail', 'stock', 'b1'], queryFn: async () => ({ items: ['Test item of a branch taken away'] }) });
    rerender({ who: { ...seller, permissions: (seller.permissions ?? []).filter((p) => p !== 'retail.sale.create' && !ENTRY_PERMISSIONS.includes(p)) } });
    expect(result.current).toBeNull();
    await waitFor(() => expect(client.getQueryData(['retail', 'stock', 'b1'])).toBeUndefined());
  });

  it('keeps a copy the profile does not match while a ping has not confirmed the server answers (issue #763)', async () => {
    const store = memoryStore();
    const seller: Me = { ...mockMe('sales'), user_id: 'u1' };
    const base = services(store, async () => {});
    // Wi-Fi with no internet: the browser says online, the ping does not answer.
    const wifiOnly = () => ({ ...base(), connectivity: { ...testConnectivity(true), check: async () => false } });
    const stale = { savedAt: 1, access: accessOf(mockMe('admin')), entries: [] };
    await store.putReadCache(scopeOf(HOST, 'u1'), stale);
    renderHook(() => useOfflineSupport(seller, false, wifiOnly, new QueryClient()));
    await new Promise((r) => setTimeout(r, 50));
    expect(await store.readCache(scopeOf(HOST, 'u1'))).toEqual(stale);
  });

  // JUSTIFICATION-A3: a new test case the #774 follow-up asks for (the restore chain against sign-out and unmount).
  it('never puts back a copy still being read when the person signs out, or when the layout leaves (issue #774)', async () => {
    const seller: Me = { ...mockMe('sales'), user_id: 'u1' };
    const copy = { savedAt: 1, access: accessOf(seller), entries: [{ queryKey: ['retail', 'home', 'all'], queryHash: '["retail","home","all"]', state: { data: { title: 'Test kept figures' }, dataUpdatedAt: Date.now(), status: 'success' } }] } as never;
    // A slow device store: the copy is read only when the test lets it go.
    const slow = () => {
      const store = memoryStore();
      let release: () => void = () => undefined;
      const gate = new Promise<void>((r) => (release = r));
      const readCache = (scope: string) => gate.then(() => (scope === scopeOf(HOST, 'u1') ? copy : null));
      return { store: { ...store, readCache }, release: () => release() };
    };

    const signOut = slow();
    const first = new QueryClient();
    const { result } = renderHook(() => useOfflineSupport(seller, false, services(signOut.store, async () => {}), first));
    await waitFor(() => expect(result.current).not.toBeNull());
    await act(() => result.current!.forget());
    signOut.release();
    await new Promise((r) => setTimeout(r, 50));
    expect(first.getQueryData(['retail', 'home', 'all'])).toBeUndefined();
    cleanup();

    const unmount = slow();
    const second = new QueryClient();
    const mounted = renderHook(() => useOfflineSupport(seller, false, services(unmount.store, async () => {}), second));
    await waitFor(() => expect(mounted.result.current).not.toBeNull());
    mounted.unmount();
    unmount.release();
    await new Promise((r) => setTimeout(r, 50));
    expect(second.getQueryData(['retail', 'home', 'all'])).toBeUndefined();
  });

  // JUSTIFICATION-A3: the test the review of #784 asks for (the restore's removal against the keeper's fresh write).
  it("never removes the fresh copy the keeper wrote while the restore's ping was slow (review of #784)", async () => {
    const store = memoryStore();
    const seller: Me = { ...mockMe('sales'), user_id: 'u1' };
    await store.putReadCache(scopeOf(HOST, 'u1'), { savedAt: 1, access: accessOf(mockMe('admin')), entries: [] });
    // The restore's ping is slow on a cold link; the keeper's own ping, later, answers at once.
    let answer: (ok: boolean) => void = () => undefined;
    let pings = 0;
    const base = services(store, async () => {});
    const slowFirst = () => ({ ...base(), connectivity: { ...testConnectivity(true), check: () => (pings++ === 0 ? new Promise<boolean>((r) => (answer = r)) : Promise.resolve(true)) } });
    const client = new QueryClient();
    renderHook(() => useOfflineSupport(seller, false, slowFirst, client));
    await waitFor(() => expect(pings).toBe(1));
    await client.fetchQuery({ queryKey: ['retail', 'home', 'all'], queryFn: async () => ({ title: 'Test fresh figures' }) });
    await waitFor(async () => expect((await store.readCache(scopeOf(HOST, 'u1')))?.access).toBe(accessOf(seller)), { timeout: 5000 });
    answer(true);
    await new Promise((r) => setTimeout(r, 50));
    expect(JSON.stringify(await store.readCache(scopeOf(HOST, 'u1')))).toContain('Test fresh figures');
  });

  it('opened offline, keeps a copy the saved profile does not match, and shows a copy it does (issue #763)', async () => {
    const store = memoryStore();
    const seller: Me = { ...mockMe('sales'), user_id: 'u1' };
    const copy = (access: string, title: string) =>
      ({ savedAt: 1, access, entries: [{ queryKey: ['retail', 'home', 'all'], queryHash: '["retail","home","all"]', state: { data: { title }, dataUpdatedAt: Date.now(), status: 'success' } }] }) as never;
    const online = services(store, async () => {});
    const offline = () => ({ ...online(), connectivity: testConnectivity(false) });
    await store.putReadCache(scopeOf(HOST, 'u1'), copy(accessOf(mockMe('admin')), 'Test figures under other rights'));
    const first = new QueryClient();
    renderHook(() => useOfflineSupport(seller, false, offline, first));
    await new Promise((r) => setTimeout(r, 50));
    expect(JSON.stringify(await store.readCache(scopeOf(HOST, 'u1')))).toContain('other rights');
    expect(first.getQueryData(['retail', 'home', 'all'])).toBeUndefined();
    cleanup();

    await store.putReadCache(scopeOf(HOST, 'u1'), copy(accessOf(seller), 'Test figures of the seller'));
    const second = new QueryClient();
    renderHook(() => useOfflineSupport(seller, false, offline, second));
    await waitFor(() => expect(second.getQueryData(['retail', 'home', 'all'])).toEqual({ title: 'Test figures of the seller' }));
  });

  it('signs out a person with no offline support and still clears their kept figures, also when the store fails (issue #763)', async () => {
    const store = memoryStore();
    const client = new QueryClient();
    await store.putReadCache(scopeOf(HOST, 'u1'), { savedAt: 1, access: 'x', entries: [] });
    client.setQueryData(['retail', 'customers'], [{ name: 'Test Customer 03' }]);
    await forgetReads('u1', client, store, HOST);
    expect(await store.readCache(scopeOf(HOST, 'u1'))).toBeNull();
    expect(client.getQueryData(['retail', 'customers'])).toBeUndefined();
    client.setQueryData(['retail', 'customers'], [{ name: 'Test Customer 04' }]);
    await forgetReads('u1', client, { ...store, deleteReadCache: () => Promise.reject(new Error('blocked')) }, HOST);
    expect(client.getQueryData(['retail', 'customers'])).toBeUndefined();
  });

  it('picks only the other people of this business, and only what still waits', () => {
    const mine = scopeOf(HOST, 'me');
    const all = [item('a', 'me'), item('b', 'them'), item('c', 'them', { state: 'discarded' }), item('d', 'them', { scope: 'x.example|them' })];
    expect(othersOnPhone(all, HOST, mine).map((i) => i.id)).toEqual(['b']);
    expect(summaries([item('b', 'them')])[0]?.made_by).toBe('them');
  });
});
