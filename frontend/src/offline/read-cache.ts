import { hydrate, onlineManager, type DehydratedState, type Query, type QueryClient, type QueryKey } from '@tanstack/react-query';
import { droppedConnection, type Me } from '../api/client';
import { businessToday, businessZone, daysBefore } from '../api/retail';
import type { Connectivity } from './connectivity';
import type { OfflineStore } from './store';

// Reading offline (issue #763, ADR-116, building on ADR-067): the figures last loaded on the screens a shop reads every day
// are kept in the device store beside the catalogue copy, per business host and person, so the app opened with no network
// still shows them, read only, with the time they are from. Kept: the Home and Retail summaries, sales of the last 30 days
// (and older credit sales still owing), stock, customers and what they owe, past usage and damage, the open orders count, and
// the reports already made (Download my data, and the report runs other than lending ones). Never kept: lending, the profile,
// tokens or anything of sign-in, any read not named below, and anything the app wrote into the cache itself (an optimistic
// change, a sale not yet sent): only answers fetched from the server by this person. The copy carries what the person was
// allowed to see when it was made, and is thrown away when that changes. It is capped in size, newest reads first, and goes at
// sign-out, with the reads it put back.

/** One kept read, in the shape React Query hydrates: its key, hash, kind and a success state with the data. */
export type ReadEntry = DehydratedState['queries'][number];

/** The kept reads of one person on one business, newest first, within the size cap. */
export interface ReadCache {
  /** When the copy was last written (epoch milliseconds). */
  savedAt: number;
  /** What the person was allowed to see when the copy was made ({@link accessOf}); a copy made under other rights is not used. */
  access?: string;
  entries: ReadEntry[];
}

/** The most the copy may take, as JSON characters: a few hundred sales and the stock of a shop fit many times over. */
export const READ_CACHE_MAX_BYTES = 2_000_000;
/** Sales older than this are dropped from the copy, unless still owing; any read older than this is dropped too. */
export const KEEP_DAYS = 30;
/** Reads arriving together (a page opening) are written once, this long after the first. */
export const SAVE_AFTER_MS = 2_000;
const DAY_MS = 86_400_000;

/** The second part of the retail query keys kept: the screens of the issue and the reads those screens wait on. */
const RETAIL_READS = new Set([
  'home', // Home summary
  'dashboard', // Retail summary
  'home-cash', // its cash book cards
  'own-sales', // a seller's own figures
  'sales', // Sales history and Credit sales
  'sale', // a sale opened from the list
  'stock', // My stock, one branch and every branch
  'categories', // the stock page's category chips
  'branch-roles', // which branches are stores and which are shops
  'accounts', // Customer accounts and what each owes
  'customers', // the customers who buy on credit
  'customer-ageing', // what customers owe, by how late
  'usage-history', // Past usage and damage
]);

/** Whether a read is kept for offline reading. Lending, the profile and every other read never are. */
export function keptForReading(key: QueryKey): boolean {
  const [area, part, third] = key;
  if (area === 'retail') {
    // The orders board is the open orders count on Home for a session that cannot read the Home summary.
    return typeof part === 'string' && (RETAIL_READS.has(part) || (part === 'orders' && third === 'board') || (part === 'catalogue' && third === 'buyers'));
  }
  // The reports already made: Download my data (every business), and the report runs of the Reports page.
  return area === 'data-export' || (area === 'reports' && (part === 'runs' || part === 'catalogue'));
}

/**
 * What a person may see, as one string: their permissions with the branches each covers, their branches, modules and
 * capabilities. A kept copy made under another value (a manager made a seller, a branch taken away) is thrown away.
 */
export function accessOf(me: Pick<Me, 'permissions' | 'permission_scopes' | 'all_branches' | 'branches' | 'modules' | 'module_capabilities'>): string {
  const sorted = (list: readonly unknown[] | undefined | null) => [...(list ?? [])].map(String).sort();
  const scopes = Object.entries(me.permission_scopes ?? {})
    .map(([p, s]) => `${p}:${(s as { all_branches?: boolean })?.all_branches ? '*' : sorted((s as { branch_ids?: string[] })?.branch_ids).join(',')}`)
    .sort();
  const caps = Object.entries(me.module_capabilities ?? {}).map(([m, c]) => `${m}:${sorted(c as string[]).join(',')}`).sort();
  return JSON.stringify([sorted(me.permissions), scopes, me.all_branches === true, sorted((me.branches ?? []).map((b) => b.id)), sorted(me.modules), caps]);
}

const lendingReport = (r: unknown) => {
  const row = r as { report_key?: unknown; key?: unknown } | null;
  const key = row?.report_key ?? row?.key;
  return typeof key === 'string' && key.startsWith('lending.');
};

/** A list as the API answers it (a bare list, `{items}` or a paged `{pages: [{items}]}`), with `keep` applied to its rows. */
// The rows are whatever the list holds (`any`); `keep` reads the few fields it needs.
function rowsKept(data: unknown, keep: (row: any) => boolean): unknown {
  if (Array.isArray(data)) return data.filter(keep);
  if (data === null || typeof data !== 'object') return data;
  const d = data as { items?: unknown; pages?: unknown };
  if (Array.isArray(d.pages)) return { ...d, pages: d.pages.map((p) => rowsKept(p, keep)) };
  if (Array.isArray(d.items)) return { ...d, items: d.items.filter(keep) };
  return data;
}

interface SaleLike {
  sale_date?: string;
  balance_minor?: number;
}

/**
 * The data as kept, or undefined when it is not kept at all: lending reports left out of the reports lists, sales cut to the
 * last 30 days (credit still owing stays), a single sale older than that dropped unless still owing.
 */
export function trimmed(key: QueryKey, data: unknown, now: number): unknown {
  const [area, part] = key;
  // A lending report is never kept: a lending run or catalogue entry read on its own is dropped whole, and lists lose them.
  if (area === 'reports') return lendingReport(data) ? undefined : rowsKept(data, (r) => !lendingReport(r));
  if (area !== 'retail' || (part !== 'sales' && part !== 'sale')) return data;
  const from = daysBefore(businessToday(new Date(now)), KEEP_DAYS);
  // Only sales carry a sale date; the groups of the sales chain (their totals) are kept whole.
  const recent = (s: SaleLike) => s.sale_date === undefined || s.sale_date >= from || (s.balance_minor ?? 0) > 0;
  if (part === 'sale') return data !== null && typeof data === 'object' && !recent(data as SaleLike) ? undefined : data;
  return rowsKept(data, recent);
}

/** A kept read of a query just fetched from the server, or null when it is not kept. */
export function entryOf(query: Query, now: number): ReadEntry | null {
  if (!keptForReading(query.queryKey) || query.state.data === undefined) return null;
  const data = trimmed(query.queryKey, query.state.data, now);
  if (data === undefined) return null;
  return {
    queryKey: query.queryKey,
    queryHash: query.queryHash,
    ...(query.queryType === 'infinite' && { queryType: 'infinite' as const }),
    dehydratedAt: now,
    state: {
      data,
      dataUpdatedAt: query.state.dataUpdatedAt,
      dataUpdateCount: 1,
      error: null,
      errorUpdateCount: 0,
      errorUpdatedAt: 0,
      fetchFailureCount: 0,
      fetchFailureReason: null,
      fetchMeta: null,
      isInvalidated: false,
      status: 'success',
      fetchStatus: 'idle',
    },
  };
}

/** The kept reads with the fresh ones in, newest first, those older than 30 days left out, cut to the size cap. */
export function merged(before: ReadEntry[], fresh: ReadEntry[], now: number, maxBytes = READ_CACHE_MAX_BYTES, size: (e: ReadEntry) => number = (e) => JSON.stringify(e).length): ReadEntry[] {
  const byHash = new Map(before.map((e) => [e.queryHash, e]));
  for (const e of fresh) {
    const old = byHash.get(e.queryHash);
    if (!old || old.state.dataUpdatedAt <= e.state.dataUpdatedAt) byHash.set(e.queryHash, e);
  }
  const newest = [...byHash.values()]
    .filter((e) => keptForReading(e.queryKey) && now - e.state.dataUpdatedAt <= KEEP_DAYS * DAY_MS)
    .sort((a, b) => b.state.dataUpdatedAt - a.state.dataUpdatedAt);
  const kept: ReadEntry[] = [];
  let bytes = 0;
  for (const e of newest) {
    const n = size(e);
    if (bytes + n > maxBytes) continue;
    bytes += n;
    kept.push(e);
  }
  return kept;
}

/** Whether a kept copy may be shown to a person with these rights now. */
export function usable(cache: ReadCache | null, access: string): cache is ReadCache {
  return cache !== null && cache.access === access;
}

/**
 * Puts the kept reads in the query cache, kept there for the session, unless the cache already holds something newer.
 * Returns the reads put back; nothing when the copy was made under other rights.
 */
export function restore(client: QueryClient, cache: ReadCache | null, now: number, access: string): ReadEntry[] {
  if (!usable(cache, access)) return [];
  const entries = cache.entries.filter((e) => keptForReading(e.queryKey) && now - e.state.dataUpdatedAt <= KEEP_DAYS * DAY_MS);
  if (entries.length > 0) hydrate(client, { mutations: [], queries: entries }, { defaultOptions: { queries: { gcTime: Infinity } } });
  return entries;
}

export interface ReadKeeper {
  /** Takes in the reads put back from this person's own copy, so they stay kept even when no screen reads them again. */
  adopt(entries: ReadEntry[]): void;
  /** Stops writing (the person, the business or their rights changed, or the layout left). */
  stop(): void;
  /** Settles once a write under way has finished (after `stop`, nothing further is written). */
  settled(): Promise<void>;
  /** At sign-out: stops writing, waits for a write under way, removes the copy and the kept reads in memory. */
  forget(): Promise<void>;
  /**
   * Removes the copy only while it is still one made under other rights, in turn with this keeper's writes, so a fresh copy
   * the keeper has just written is never removed in its place (review of #784).
   */
  dropOther(): Promise<void>;
}

/**
 * Writes this person's kept reads to the device store a moment after any of them is fetched, merged with what was kept
 * before. Only answers fetched from the server while this keeper runs, and the reads it put back, are written: never data
 * the app set in the cache itself, and never a read another person left in memory. A kept copy made under other rights is
 * replaced only once `confirm` (the ping of the server) says the network answers (ADR-116 decision 3): until then the rights
 * may come from a saved profile that lags the copy, so nothing is written over it.
 */
export function keepReads(
  client: QueryClient,
  store: OfflineStore,
  scope: string,
  access: string,
  now: () => number = () => Date.now(),
  delayMs = SAVE_AFTER_MS,
  confirm: () => Promise<boolean> = () => Promise.resolve(false),
): ReadKeeper {
  // Each kept read with its size, and the answer it was made from: a poll that brings the same answer (React Query keeps the
  // same object) only moves the time, without measuring the read again.
  const mine = new Map<string, { entry: ReadEntry; size: number; raw?: unknown }>();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;
  let writing: Promise<void> = Promise.resolve();
  // Whether a ping has confirmed the network, so a copy made under other rights may be replaced. Asked by each save that
  // meets such a copy until one ping answers; after that it is not asked again.
  let confirmed = false;
  const take = (entry: ReadEntry, raw?: unknown) => {
    const before = mine.get(entry.queryHash);
    const size = before && raw !== undefined && before.raw === raw ? before.size : JSON.stringify(entry).length;
    mine.set(entry.queryHash, { entry, size, raw });
  };
  const save = () => {
    timer = undefined;
    writing = writing
      .then(async () => {
        if (stopped) return;
        const before = await store.readCache(scope);
        if (before !== null && !usable(before, access) && !confirmed) {
          confirmed = await confirm().catch(() => false);
          // The ping can take seconds: a keeper stopped meanwhile (sign-out, the layout left) writes nothing.
          if (!confirmed || stopped) return;
        }
        const kept = usable(before, access) ? before.entries : [];
        const sizes = new Map([...mine.values()].map((m) => [m.entry, m.size]));
        const entries = merged(kept, [...mine.values()].map((m) => m.entry), now(), READ_CACHE_MAX_BYTES, (e) => sizes.get(e) ?? JSON.stringify(e).length);
        if (!stopped) await store.putReadCache(scope, { savedAt: now(), access, entries });
      })
      // A full disk keeps the last copy; the next load tries again.
      .catch(() => undefined);
  };
  const off = client.getQueryCache().subscribe((event) => {
    if (stopped || event.type !== 'updated' || event.action.type !== 'success' || event.action.manual) return;
    const entry = entryOf(event.query, now());
    if (entry) take(entry, event.query.state.data);
    else if (keptForReading(event.query.queryKey)) mine.delete(event.query.queryHash);
    else return;
    timer ??= setTimeout(save, delayMs);
  });
  const stop = () => {
    stopped = true;
    off();
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
  };
  return {
    adopt: (entries) => entries.forEach((e) => take(e)),
    stop,
    settled: () => writing,
    forget: async () => {
      stop();
      // Memory first: the next person in this tab never gets these reads, even when the device store then fails.
      forgetInMemory(client);
      await writing;
      await store.deleteReadCache(scope);
    },
    dropOther: () => {
      const drop = writing.then(async () => {
        if (stopped) return;
        const now = await store.readCache(scope);
        if (now !== null && !usable(now, access) && !stopped) await store.deleteReadCache(scope);
      });
      writing = drop.catch(() => undefined);
      return drop;
    },
  };
}

/** Removes every kept read from the query cache: at sign-out, and when the person's rights change (ADR-116 decisions 3 and 5). */
export function forgetInMemory(client: QueryClient): void {
  client.removeQueries({ predicate: (q) => keptForReading(q.queryKey) });
}

/**
 * React Query takes the app as offline when the offline support does (the browser's events and the ping of /healthz), so
 * reads pause instead of failing. Returns the undo, which puts back the browser's own online and offline events and state.
 */
export function followConnectivity(client: QueryClient, connectivity: Connectivity): () => void {
  // A kept read that fails for a dropped connection while already offline (one that does not pause, or fails between two
  // pings) is quieted as it fails, so the banner stays the page's one notice.
  const off = client.getQueryCache().subscribe((event) => {
    if (event.type === 'updated' && event.action.type === 'error' && !connectivity.online() && quietable(event.query)) quiet(event.query);
  });
  onlineManager.setEventListener((setOnline) => {
    const tell = () => {
      const online = connectivity.online();
      if (!online) showKeptFigures(client);
      setOnline(online);
    };
    tell();
    return connectivity.subscribe(tell);
  });
  return () => {
    off();
    onlineManager.setEventListener(browserEvents);
    // The browser's own state again, so a later sign-in never starts paused by the last person's ping.
    onlineManager.setOnline(typeof navigator === 'undefined' || navigator.onLine !== false);
  };
}

/**
 * Going offline, a kept read that failed because the network dropped (the API client marks it, `droppedConnection`) stops saying so,
 * the banner under the bar being the one notice: with figures it shows them again, without it waits as if loading, and both
 * are read again once back online. Any answer the server sent (a refusal, a 5xx) and every read not kept stay as they are.
 */
function showKeptFigures(client: QueryClient): void {
  client.getQueryCache().findAll({ predicate: quietable }).forEach(quiet);
}

const quietable = (q: Query) => keptForReading(q.queryKey) && q.state.status === 'error' && droppedConnection(q.state.error);

function quiet(q: Query): void {
  q.setState(q.state.data !== undefined ? { status: 'success', error: null, fetchStatus: 'idle' } : { status: 'pending', error: null, fetchStatus: 'idle' });
}

function browserEvents(setOnline: (online: boolean) => void): (() => void) | undefined {
  if (typeof window === 'undefined' || !window.addEventListener) return undefined;
  const on = () => setOnline(true);
  const off = () => setOnline(false);
  window.addEventListener('online', on);
  window.addEventListener('offline', off);
  return () => {
    window.removeEventListener('online', on);
    window.removeEventListener('offline', off);
  };
}

/** "today at 14:05", or "2 Nov at 14:05" for an earlier day, in the business's time zone. */
export function figuresTime(at: number, now: number, zone: string = businessZone()): string {
  const day = (t: number, year = false) =>
    new Intl.DateTimeFormat('en-GB', { timeZone: zone, day: 'numeric', month: 'short', ...(year && { year: 'numeric' }) }).format(new Date(t));
  const time = new Intl.DateTimeFormat('en-GB', { timeZone: zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(at));
  return `${day(at, true) === day(now, true) ? 'today' : day(at)} at ${time}`;
}
