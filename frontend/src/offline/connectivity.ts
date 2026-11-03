// Whether the app can reach the server (issue #458, ADR-067 decision 4): the browser's online and offline events, confirmed
// by a cheap ping of GET /healthz every 30 seconds while the page is open. The browser saying offline is believed at once;
// the browser saying online is believed once a ping answers. Issue #769: a ping with no answer within PING_TIMEOUT_MS, twice in
// a row, counts as offline, since on a weak mobile connection a slow answer means a sale would hang.

export interface Connectivity {
  online(): boolean;
  subscribe(listener: () => void): () => void;
  /** Pings now and returns the new state. */
  check(): Promise<boolean>;
  start(): void;
  stop(): void;
}

export const PING_EVERY_MS = 30_000;

/** A ping slower than this counts as offline (issue #769). */
export const PING_TIMEOUT_MS = 3_000;

/** A sale sent online with no answer within this goes to the outbox (issue #769). */
export const SALE_ANSWER_MS = 8_000;

/** No answer within `ms` (issue #769): the request may still arrive, so what it did is not known. */
export class NoAnswer extends Error {}

/** `asked`, or a NoAnswer rejection once `ms` pass without its answer; a later answer is ignored. */
export function answerWithin<T>(asked: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const late = new Promise<never>((_, reject) => (timer = setTimeout(() => reject(new NoAnswer('The server did not answer in time.')), ms)));
  return Promise.race([asked, late]).finally(() => clearTimeout(timer));
}

export interface ConnectivityDeps {
  /** True when the server answered. */
  ping(): Promise<boolean>;
  navigatorOnline(): boolean;
  addListener(type: 'online' | 'offline', fn: () => void): () => void;
  every(fn: () => void, ms: number): () => void;
}

export function createConnectivity(deps: ConnectivityDeps): Connectivity {
  let state = deps.navigatorOnline();
  const listeners = new Set<() => void>();
  const stops: (() => void)[] = [];
  const set = (next: boolean) => {
    if (next === state) return;
    state = next;
    listeners.forEach((l) => l());
  };
  const check = async () => {
    if (!deps.navigatorOnline()) {
      set(false);
      return false;
    }
    const ping = () => answerWithin(deps.ping(), PING_TIMEOUT_MS);
    let ok = false;
    try {
      // While online, one slow ping (a cold connection) is pinged again at once: two slow in a row are offline, so the
      // state does not flap on a slow but working link.
      ok = await ping().catch((e: unknown) => (e instanceof NoAnswer && state ? ping() : Promise.reject(e)));
    } catch {
      ok = false;
    }
    set(ok);
    return ok;
  };
  return {
    online: () => state,
    subscribe: (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    check,
    start: () => {
      if (stops.length > 0) return;
      stops.push(deps.addListener('offline', () => set(false)));
      stops.push(deps.addListener('online', () => void check()));
      stops.push(deps.every(() => void check(), PING_EVERY_MS));
      void check();
    },
    stop: () => stops.splice(0).forEach((s) => s()),
  };
}

/** The browser's own connectivity: window events, navigator.onLine and a fetch of /healthz with no cache, cut off when too slow. */
export function browserConnectivity(): Connectivity {
  return createConnectivity({
    ping: async () => {
      const signal = typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function' ? AbortSignal.timeout(PING_TIMEOUT_MS) : undefined;
      try {
        const response = await fetch('/healthz', { cache: 'no-store', signal });
        return response.ok;
      } catch (e) {
        // The fetch cut off by its own 3 s limit is a slow ping, not a failed one, so it is pinged again (issue #769).
        const name = (e as { name?: string } | null)?.name;
        if (name === 'TimeoutError' || name === 'AbortError') throw new NoAnswer('The server did not answer in time.');
        throw e;
      }
    },
    navigatorOnline: () => (typeof navigator === 'undefined' ? true : navigator.onLine !== false),
    addListener: (type, fn) => {
      window.addEventListener(type, fn);
      return () => window.removeEventListener(type, fn);
    },
    every: (fn, ms) => {
      const id = window.setInterval(fn, ms);
      return () => window.clearInterval(id);
    },
  });
}
