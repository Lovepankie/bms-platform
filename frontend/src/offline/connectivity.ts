// Whether the app can reach the server (issue #458, ADR-067 decision 4): the browser's online and offline events, confirmed
// by a cheap ping of GET /healthz every 30 seconds while the page is open. The browser saying offline is believed at once;
// the browser saying online is believed once a ping answers. Issue #769: SLOW_PINGS_OFFLINE pings in a row with no answer
// within PING_TIMEOUT_MS count as offline, since on a weak mobile connection a slow answer means a sale would hang; one slow
// ping alone changes nothing, so the app does not flap on 2G.

export interface Connectivity {
  online(): boolean;
  subscribe(listener: () => void): () => void;
  /** Pings now and returns the new state. */
  check(): Promise<boolean>;
  start(): void;
  stop(): void;
}

export const PING_EVERY_MS = 30_000;

/** A ping slower than this is slow (issue #769). */
export const PING_TIMEOUT_MS = 3_000;

/** This many slow pings in a row count as offline, so one slow answer on 2G never flips the app offline (issue #769). */
export const SLOW_PINGS_OFFLINE = 2;

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
  let slow = 0;
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
    let ok = false;
    try {
      ok = await answerWithin(deps.ping(), PING_TIMEOUT_MS);
      slow = 0;
    } catch (e) {
      // One slow answer on a weak connection changes nothing; SLOW_PINGS_OFFLINE in a row count as offline.
      if (e instanceof NoAnswer && ++slow < SLOW_PINGS_OFFLINE) return state;
      if (!(e instanceof NoAnswer)) slow = 0;
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
      // An old WebView may have no AbortSignal at all; the ping is then cut off by answerWithin alone.
      const signal = typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function' ? AbortSignal.timeout(PING_TIMEOUT_MS) : undefined;
      try {
        const response = await fetch('/healthz', { cache: 'no-store', signal });
        return response.ok;
      } catch (e) {
        // Cut off for being slow is a slow ping, not a lost network.
        if (signal?.aborted) throw new NoAnswer('The server did not answer in time.');
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
