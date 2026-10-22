// @vitest-environment jsdom
import { renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Me } from '../api/client';
import { mockMe } from '../api/retail-mock';
import { othersOnPhone, summaries, useOfflineSupport, type OfflineServices } from './offline-context';
import { provisionalNumber, type OutboxItem } from './outbox';
import { memoryStore, scopeOf, type OfflineStore } from './store';
import { testConnectivity } from './test-kit';

// The offline support of the signed-in person (issue #458, ADR-067 decision 9): on a shared phone, the sales another person
// left queued here are counted for the notice and, while online, told to the server in short so managers see them waiting
// on a phone. Fabricated people, ids and amounts.

const HOST = 'test.example';

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

  it('picks only the other people of this business, and only what still waits', () => {
    const mine = scopeOf(HOST, 'me');
    const all = [item('a', 'me'), item('b', 'them'), item('c', 'them', { state: 'discarded' }), item('d', 'them', { scope: 'x.example|them' })];
    expect(othersOnPhone(all, HOST, mine).map((i) => i.id)).toEqual(['b']);
    expect(summaries([item('b', 'them')])[0]?.made_by).toBe('them');
  });
});
