import { createContext, useContext, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { api, type Me } from '../api/client';
import { retail } from '../api/retail';
import { refreshSession } from '../auth/session';
import { REFRESH_EVERY_MS, refreshCatalogue, type CatalogueSource } from './catalogue';
import { browserConnectivity, type Connectivity } from './connectivity';
import { waiting, type OutboxItem } from './outbox';

/** A sale another person left waiting on this phone, in short, for the managers (ADR-067 decision 9). */
export interface WaitingSummary {
  client_sale_id: string;
  branch_id: string;
  made_by: string;
  occurred_at: string;
  total_minor: number;
}

/** The sales other people left waiting on this phone for this business, from every item on the device. */
export function othersOnPhone(all: OutboxItem[], host: string, scope: string): OutboxItem[] {
  return waiting(all.filter((i) => i.scope !== scope && i.scope.startsWith(`${host}|`)));
}

/** The short summary of each, for POST /retail/offline-waiting. */
export function summaries(items: OutboxItem[]): WaitingSummary[] {
  return items.map((i) => ({
    client_sale_id: i.id,
    branch_id: i.branchId,
    made_by: i.scope.slice(i.scope.indexOf('|') + 1),
    occurred_at: i.occurredAt,
    total_minor: i.totalMinor,
  }));
}
import { deviceStore, scopeOf, type Catalogue, type OfflineStore } from './store';
import { SyncEngine, type SyncDeps, type SyncSnapshot } from './sync-engine';

// Selling offline in the staff area (issue #458, ADR-067): one engine, one connectivity watch and one catalogue copy for the
// signed-in person on this business, made in the staff layout and given to the sale screen, the status dot and sign-out.
// Only a person who may sell in retail gets one; lending never does.

export interface Offline {
  scope: string;
  /** The signed-in person, whose own refused sales another manager decides. */
  userId: string;
  store: OfflineStore;
  engine: SyncEngine;
  connectivity: Connectivity;
  catalogue: Catalogue | null;
  /** The person holds retail.offline.resolve somewhere: the sheet shows other people's refused sales and the clashes. */
  manager: boolean;
  /** Sales other people left waiting on this phone for this business (a shared phone): sent when they sign in here again. */
  othersWaiting: number;
  /** Fetches the catalogue copy again (when online). */
  refresh(): Promise<void>;
  /** At sign-out: the catalogue copy and the profile pointer go; the person's outbox stays for their next sign-in here. */
  forget(): Promise<void>;
}

const OfflineContext = createContext<Offline | null>(null);

export function OfflineProvider({ value, children }: { value: Offline | null; children: ReactNode }) {
  return <OfflineContext.Provider value={value}>{children}</OfflineContext.Provider>;
}

export function useOffline(): Offline | null {
  return useContext(OfflineContext);
}

const ALWAYS = { online: () => true, subscribe: () => () => {} };

/** Whether the server can be reached; always true where no offline support is mounted. */
export function useOnline(): boolean {
  const c = useOffline()?.connectivity ?? ALWAYS;
  return useSyncExternalStore(c.subscribe, c.online, c.online);
}

const EMPTY: SyncSnapshot = { items: [], syncing: false, needsSignIn: false };
const NO_ENGINE = { subscribe: () => () => {}, snapshot: () => EMPTY };

export function useOutbox(): SyncSnapshot {
  const e = useOffline()?.engine ?? NO_ENGINE;
  return useSyncExternalStore(e.subscribe, e.snapshot, e.snapshot);
}

/** The person may sell in retail: the only staff work that runs offline. */
export function sellsOffline(me: Pick<Me, 'permissions' | 'modules'>): boolean {
  return (me.modules ?? []).includes('retail') && (me.permissions ?? []).includes('retail.sale.create');
}

/** What the engine and the catalogue use in the browser; tests pass their own. */
export interface OfflineServices {
  store: OfflineStore;
  connectivity: Connectivity;
  source: CatalogueSource;
  engineDeps(store: OfflineStore, scope: string): SyncDeps;
  /** Tells the server, in short, which sales other people left waiting on this phone. */
  reportWaiting(items: WaitingSummary[]): Promise<void>;
  host: string;
  now(): Date;
}

function bodyOf(item: OutboxItem) {
  return item.request;
}

export function browserServices(): OfflineServices {
  return {
    store: deviceStore(),
    connectivity: browserConnectivity(),
    host: window.location.host,
    now: () => new Date(),
    source: {
      products: async (branchId, cursor) => {
        const { data, error, response } = await api.GET('/api/v1/retail/products', {
          params: { query: { branch_id: branchId, active: true, limit: 200, cursor } },
        });
        if (error || !data || !response.ok) throw new Error('Could not load the items');
        return { ...data, serverTime: response.headers.get('Date') ?? undefined };
      },
      customers: () => retail.listCustomers(),
    },
    reportWaiting: async (items) => {
      const { error } = await api.POST('/api/v1/retail/offline-waiting', { body: { items } });
      if (error) throw new Error('Could not report the waiting sales');
    },
    engineDeps: (store, scope) => ({
      store,
      scope,
      now: () => Date.now(),
      later: (fn, ms) => {
        const id = window.setTimeout(fn, ms);
        return () => window.clearTimeout(id);
      },
      refreshSession,
      send: async (item) => {
        const { data, error, response } = await api.POST('/api/v1/retail/sales', {
          body: bodyOf(item),
          params: { header: { 'Idempotency-Key': item.key } },
        });
        return { status: response.status, body: data ?? error };
      },
      report: async (item) => {
        const { data, error } = await api.POST('/api/v1/retail/offline-failures', {
          body: {
            client_sale_id: item.id,
            branch_id: item.branchId,
            occurred_at: item.occurredAt,
            payment_method: item.method,
            total_minor: item.totalMinor,
            // The generated type of an open JSON object is narrower than any real body; the server takes the sale request as is.
            request: item.request as never,
            code: (item.error?.code ?? 'refused').slice(0, 60),
            message: (item.error?.message ?? 'Refused by the server.').slice(0, 500),
          },
        });
        if (error || !data?.id) throw new Error('Could not report the sale');
        return { id: data.id, client_sale_id: item.id, status: (data.status ?? 'open') as 'open' };
      },
      decisions: async (ids) => {
        const { data, error } = await api.GET('/api/v1/retail/offline-failures/mine', { params: { query: { client_sale_id: ids } } });
        if (error || !data) throw new Error('Could not read the decisions');
        return (data.items ?? []).map((f) => ({ id: f.id ?? '', client_sale_id: f.client_sale_id ?? '', status: (f.status ?? 'open') as 'open' }));
      },
    }),
  };
}

/**
 * The offline support of the signed-in person, or null when they do not sell. Loads their outbox, watches the connection,
 * refreshes the catalogue when online (at once, then every 15 minutes) and sends the outbox whenever the app comes online.
 */
export function useOfflineSupport(me: Me | undefined, signedIn: boolean, services: () => OfflineServices = browserServices): Offline | null {
  const userId = me?.user_id;
  const sells = me !== undefined && sellsOffline(me);
  const svc = useMemo(() => (sells && userId ? services() : null), [sells, userId, services]);
  const scope = svc && userId ? scopeOf(svc.host, userId) : null;
  const engine = useMemo(() => (svc && scope ? new SyncEngine(svc.engineDeps(svc.store, scope)) : null), [svc, scope]);
  const [catalogue, setCatalogue] = useState<Catalogue | null>(null);
  const [others, setOthers] = useState(0);
  // The latest profile, read when a refresh runs, so a new copy of the same profile does not restart the watch.
  const profile = useRef(me);
  profile.current = me;

  // Sales other people left on this phone: counted for the notice, and while online told to the server in short, so the
  // managers see them waiting on a phone (ADR-067 decision 9).
  const lookAtOthers = useMemo(
    () => async (online: boolean) => {
      if (!svc || !scope) return;
      try {
        const theirs = othersOnPhone(await svc.store.allOutbox(), svc.host, scope);
        setOthers(theirs.length);
        if (online && theirs.length > 0) await svc.reportWaiting(summaries(theirs).slice(0, 200));
      } catch {
        // Told on the next refresh.
      }
    },
    [svc, scope],
  );

  const refresh = useMemo(
    () => async () => {
      const current = profile.current;
      if (!svc || !scope || !current || !svc.connectivity.online()) return;
      try {
        setCatalogue(await refreshCatalogue(svc.source, current, scope, svc.store, svc.now()));
        await svc.store.setLastPerson(svc.host, current.user_id ?? null);
      } catch {
        // The last full copy stays; the next refresh tries again.
      }
      await lookAtOthers(true);
    },
    [svc, scope, lookAtOthers],
  );

  useEffect(() => {
    if (!svc || !scope || !engine) return;
    let live = true;
    void svc.store.catalogue(scope).then((c) => live && setCatalogue((now) => now ?? c));
    void lookAtOthers(false);
    void engine.load().then(() => {
      if (svc.connectivity.online()) void engine.sync();
    });
    svc.connectivity.start();
    const off = svc.connectivity.subscribe(() => {
      if (svc.connectivity.online()) {
        void engine.syncNow();
        void refresh();
      }
    });
    return () => {
      live = false;
      off();
      engine.stop();
      svc.connectivity.stop();
    };
  }, [svc, scope, engine, refresh, lookAtOthers]);

  useEffect(() => {
    if (!svc || !signedIn) return;
    engine?.resume();
    void refresh();
    const id = window.setInterval(() => void refresh(), REFRESH_EVERY_MS);
    return () => window.clearInterval(id);
  }, [svc, signedIn, engine, refresh]);

  const manager = (me?.permissions ?? []).includes('retail.offline.resolve');
  return useMemo(() => {
    if (!svc || !scope || !engine) return null;
    return {
      manager,
      othersWaiting: others,
      scope,
      userId: userId ?? '',
      store: svc.store,
      engine,
      connectivity: svc.connectivity,
      catalogue,
      refresh,
      forget: async () => {
        await svc.store.deleteCatalogue(scope);
        await svc.store.setLastPerson(svc.host, null);
      },
    };
  }, [svc, scope, engine, catalogue, refresh, manager, userId, others]);
}

/** The number of sales waiting on this device for the signed-in person (queued and failed). */
export function waitingNow(offline: Offline | null): number {
  return offline ? waiting(offline.engine.snapshot().items).length : 0;
}

/** The profile of the last person signed in on this business, for opening the app with no network; null when none. */
export async function lastProfile(store: OfflineStore = deviceStore(), host: string = window.location.host): Promise<Me | null> {
  try {
    const userId = await store.lastPerson(host);
    return userId ? await store.profile(scopeOf(host, userId)) : null;
  } catch {
    return null;
  }
}
