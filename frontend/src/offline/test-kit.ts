import type { Me } from '../api/client';
import type { Connectivity } from './connectivity';
import { isAdmin, sellsOffline, type Offline } from './offline-context';
import { memoryStore, scopeOf, type Catalogue, type OfflineStore } from './store';
import { SyncEngine, type SyncDeps } from './sync-engine';

// Tests only: the offline support of a person, on a memory store, with a connection the test switches and a send it mocks.

export interface TestConnectivity extends Connectivity {
  set(online: boolean): void;
}

export function testConnectivity(initial = true): TestConnectivity {
  let state = initial;
  const listeners = new Set<() => void>();
  return {
    online: () => state,
    subscribe: (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    check: async () => state,
    start: () => {},
    stop: () => {},
    set: (next) => {
      state = next;
      listeners.forEach((l) => l());
    },
  };
}

export function testOffline(
  me: Me,
  options: {
    online?: boolean;
    catalogue?: Catalogue | null;
    deps?: Partial<SyncDeps>;
    store?: OfflineStore;
    connectivity?: TestConnectivity;
    othersWaiting?: number;
  } = {},
): (Offline & { connectivity: TestConnectivity }) | null {
  if (!sellsOffline(me)) return null;
  const store = options.store ?? memoryStore();
  const scope = scopeOf('test.example', me.user_id ?? 'nobody');
  const connectivity = options.connectivity ?? testConnectivity(options.online ?? true);
  const engine = new SyncEngine({
    store,
    scope,
    send: async () => ({ status: 503, body: {} }),
    refreshSession: async () => false,
    report: async (item) => ({ id: `f-${item.id}`, client_sale_id: item.id, status: 'open' }),
    decisions: async () => [],
    now: () => Date.now(),
    later: () => () => {},
    ...options.deps,
  });
  return {
    scope,
    userId: me.user_id ?? 'nobody',
    store,
    engine,
    connectivity,
    catalogue: options.catalogue ?? null,
    manager: (me.permissions ?? []).includes('retail.offline.resolve'),
    admin: isAdmin(me),
    othersWaiting: options.othersWaiting ?? 0,
    refresh: async () => {},
    forget: async () => {
      await store.deleteCatalogue(scope);
      await store.deleteProfile(scope);
    },
  };
}
