// Whether the app can reach the server (issue #458, ADR-067 decision 4): the browser's online and offline events, confirmed
// by a cheap ping of GET /healthz every 30 seconds while the page is open. The browser saying offline is believed at once;
// the browser saying online is believed once a ping answers.

export interface Connectivity {
  online(): boolean;
  subscribe(listener: () => void): () => void;
  /** Pings now and returns the new state. */
  check(): Promise<boolean>;
  start(): void;
  stop(): void;
}

export const PING_EVERY_MS = 30_000;

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
    let ok = false;
    try {
      ok = await deps.ping();
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

/** The browser's own connectivity: window events, navigator.onLine and a fetch of /healthz with no cache. */
export function browserConnectivity(): Connectivity {
  return createConnectivity({
    ping: async () => {
      const response = await fetch('/healthz', { cache: 'no-store' });
      return response.ok;
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
