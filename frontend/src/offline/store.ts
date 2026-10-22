import type { Me } from '../api/client';
import type { Customer, Product } from '../api/retail';
import type { OutboxItem } from './outbox';

// Where the device keeps what it needs to sell offline (issue #458, ADR-067 decisions 2, 3 and 9): IndexedDB database
// `bms-offline`, two stores. `outbox` holds the queued sales, keyed by client sale id with an index on the scope (the business
// host and the person). `kv` holds, per scope, the catalogue copy and the profile, and per host the last person signed in.
// Nothing of lending is ever written here. Tests use the memory store, which behaves the same.

/** What the sale screen needs with no network, for one person on one business. */
export interface Catalogue {
  /** When the copy was last refreshed in full (ISO): the "prices as of" time. */
  pricesAsOf: string;
  /** The active items of each branch the person may sell at, with their packs and stock when refreshed. */
  products: Record<string, Product[]>;
  customers: Customer[];
}

export interface OfflineStore {
  outbox(scope: string): Promise<OutboxItem[]>;
  putItem(item: OutboxItem): Promise<void>;
  deleteItem(id: string): Promise<void>;
  catalogue(scope: string): Promise<Catalogue | null>;
  putCatalogue(scope: string, catalogue: Catalogue): Promise<void>;
  deleteCatalogue(scope: string): Promise<void>;
  profile(scope: string): Promise<Me | null>;
  putProfile(scope: string, me: Me): Promise<void>;
  /** The person last signed in on this host, for opening the app with no network; null after a sign-out. */
  lastPerson(host: string): Promise<string | null>;
  setLastPerson(host: string, userId: string | null): Promise<void>;
}

/** The scope of a person on a business: the host names the business (ADR-018), the user id the person. */
export function scopeOf(host: string, userId: string): string {
  return `${host}|${userId}`;
}

export function memoryStore(): OfflineStore {
  const items = new Map<string, OutboxItem>();
  const kv = new Map<string, unknown>();
  const copy = <T,>(v: T): T => (v === undefined ? v : (JSON.parse(JSON.stringify(v)) as T));
  return {
    outbox: async (scope) => [...items.values()].filter((i) => i.scope === scope).map(copy),
    putItem: async (item) => void items.set(item.id, copy(item)),
    deleteItem: async (id) => void items.delete(id),
    catalogue: async (scope) => copy((kv.get(`catalogue:${scope}`) as Catalogue | undefined) ?? null),
    putCatalogue: async (scope, c) => void kv.set(`catalogue:${scope}`, copy(c)),
    deleteCatalogue: async (scope) => void kv.delete(`catalogue:${scope}`),
    profile: async (scope) => copy((kv.get(`profile:${scope}`) as Me | undefined) ?? null),
    putProfile: async (scope, me) => void kv.set(`profile:${scope}`, copy(me)),
    lastPerson: async (host) => (kv.get(`last:${host}`) as string | undefined) ?? null,
    setLastPerson: async (host, userId) => void (userId ? kv.set(`last:${host}`, userId) : kv.delete(`last:${host}`)),
  };
}

const DB = 'bms-offline';

function request<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

function open(): Promise<IDBDatabase> {
  const r = indexedDB.open(DB, 1);
  r.onupgradeneeded = () => {
    const db = r.result;
    if (!db.objectStoreNames.contains('outbox')) db.createObjectStore('outbox', { keyPath: 'id' }).createIndex('scope', 'scope');
    if (!db.objectStoreNames.contains('kv')) db.createObjectStore('kv');
  };
  return request(r);
}

export function indexedDbStore(): OfflineStore {
  let db: Promise<IDBDatabase> | null = null;
  const tx = async <T,>(name: 'outbox' | 'kv', mode: IDBTransactionMode, work: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> => {
    db ??= open();
    const store = (await db).transaction(name, mode).objectStore(name);
    return request(work(store));
  };
  const get = async <T,>(key: string): Promise<T | null> => ((await tx('kv', 'readonly', (s) => s.get(key))) as T | undefined) ?? null;
  const put = async (key: string, value: unknown) => void (await tx('kv', 'readwrite', (s) => s.put(value, key)));
  const del = async (key: string) => void (await tx('kv', 'readwrite', (s) => s.delete(key)));
  return {
    outbox: async (scope) => (await tx('outbox', 'readonly', (s) => s.index('scope').getAll(scope))) as OutboxItem[],
    putItem: async (item) => void (await tx('outbox', 'readwrite', (s) => s.put(item))),
    deleteItem: async (id) => void (await tx('outbox', 'readwrite', (s) => s.delete(id))),
    catalogue: (scope) => get<Catalogue>(`catalogue:${scope}`),
    putCatalogue: (scope, c) => put(`catalogue:${scope}`, c),
    deleteCatalogue: (scope) => del(`catalogue:${scope}`),
    profile: (scope) => get<Me>(`profile:${scope}`),
    putProfile: (scope, me) => put(`profile:${scope}`, me),
    lastPerson: (host) => get<string>(`last:${host}`),
    setLastPerson: (host, userId) => (userId ? put(`last:${host}`, userId) : del(`last:${host}`)),
  };
}

/** IndexedDB where the browser has it, otherwise memory (the outbox then lasts as long as the page). */
export function deviceStore(): OfflineStore {
  return typeof indexedDB === 'undefined' ? memoryStore() : indexedDbStore();
}
