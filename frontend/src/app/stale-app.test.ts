import { afterEach, describe, expect, it, vi } from 'vitest';
import { isStaleAppError, loadFreshApp, OFFLINE_MESSAGE, reloadOnce } from './stale-app';

// #491: a phone still on the app from before a deploy tapped a Home card and got the raw
// "Importing a module script failed." The app now loads itself again, once a minute at most,
// and never reloads when it cannot count the reloads.

const memory = () => {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) } as unknown as Storage;
};
const blocked = {
  getItem: () => {
    throw new DOMException('denied', 'SecurityError');
  },
  setItem: () => {
    throw new DOMException('denied', 'SecurityError');
  },
} as unknown as Storage;
const forgetful = { getItem: () => null, setItem: () => undefined } as unknown as Storage;

describe('stale app after a deploy', () => {
  it('knows the browsers\' words for a screen file that is gone', () => {
    expect(isStaleAppError(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(isStaleAppError(new TypeError('Failed to fetch dynamically imported module: https://x/assets/stock-abc.js'))).toBe(true);
    expect(isStaleAppError(new Error('error loading dynamically imported module'))).toBe(true);
    expect(isStaleAppError(new TypeError("'text/html' is not a valid JavaScript MIME type."))).toBe(true);
    expect(isStaleAppError(new Error('Request failed with status 500'))).toBe(false);
    expect(isStaleAppError(undefined)).toBe(false);
  });

  it('reloads once, then not again within a minute', () => {
    const storage = memory();
    let reloads = 0;
    const reload = () => void reloads++;
    expect(reloadOnce(1_000, storage, reload)).toBe(true);
    expect(reloadOnce(30_000, storage, reload)).toBe(false);
    expect(reloadOnce(62_000, storage, reload)).toBe(true);
    expect(reloads).toBe(2);
  });

  it('never reloads when the browser refuses storage, so it cannot loop', () => {
    let reloads = 0;
    const reload = () => void reloads++;
    expect(reloadOnce(1_000, null, reload)).toBe(false);
    expect(reloadOnce(1_000, blocked, reload)).toBe(false);
    expect(reloadOnce(1_000, forgetful, reload)).toBe(false);
    expect(reloads).toBe(0);
  });
});

describe('loading the fresh app', () => {
  afterEach(() => vi.unstubAllGlobals());

  // A phone with one registration. `next` is the new worker update() finds, if any; firing `controllerchange` is it
  // taking over. The cache and unregister spies prove nothing is deleted and the worker stays (push notices, ADR-050).
  const phone = ({ onLine = true, next = undefined as 'installing' | 'waiting' | undefined, update = async () => undefined } = {}) => {
    const listeners: Array<() => void> = [];
    const reg = {
      installing: null as object | null,
      waiting: null as object | null,
      unregister: vi.fn(async () => true),
      update: vi.fn(async () => {
        await update();
        if (next) reg[next] = {};
      }),
    };
    const del = vi.fn(async () => true);
    vi.stubGlobal('navigator', {
      onLine,
      serviceWorker: {
        getRegistrations: async () => [reg],
        addEventListener: (type: string, fn: () => void) => type === 'controllerchange' && listeners.push(fn),
      },
    });
    vi.stubGlobal('caches', { keys: async () => ['workbox-precache-v2-https://shop.example/'], delete: del });
    return { reg, del, takeOver: () => listeners.splice(0).forEach((fn) => fn()), listening: () => listeners.length };
  };

  it('offline, reloads nothing and says it will update when back online', async () => {
    const { reg, del } = phone({ onLine: false });
    const reload = vi.fn();
    expect(await loadFreshApp(reload)).toBe('offline');
    expect(reload).not.toHaveBeenCalled();
    expect(reg.update).not.toHaveBeenCalled();
    expect(del).not.toHaveBeenCalled();
    expect(OFFLINE_MESSAGE).toBe('You are offline. The app will update when you are back online.');
    expect(reloadOnce(1_000, memory(), reload)).toBe(false);
  });

  it('reloads at once when no new worker is found', async () => {
    const { reg } = phone();
    const reload = vi.fn();
    expect(await loadFreshApp(reload)).toBe('reloaded');
    expect(reg.update).toHaveBeenCalled();
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it.each(['installing', 'waiting'] as const)('with a new worker %s, reloads only after it takes over', async (next) => {
    const { reg, del, takeOver, listening } = phone({ next });
    const reload = vi.fn();
    const done = loadFreshApp(reload, 60_000);
    await vi.waitFor(() => expect(listening()).toBe(1));
    expect(reload).not.toHaveBeenCalled();
    takeOver();
    await done;
    expect(reload).toHaveBeenCalledTimes(1);
    expect(reg.unregister).not.toHaveBeenCalled();
    expect(del).not.toHaveBeenCalled();
  });

  it('reloads anyway after the time limit when the update is slow or the worker never takes over', async () => {
    const slow = phone({ update: () => new Promise<undefined>(() => undefined) });
    const reload = vi.fn();
    await loadFreshApp(reload, 20);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(slow.reg.unregister).not.toHaveBeenCalled();
    expect(slow.del).not.toHaveBeenCalled();

    phone({ next: 'installing' });
    const stuck = vi.fn();
    await loadFreshApp(stuck, 20);
    expect(stuck).toHaveBeenCalledTimes(1);
  });

  it('still reloads when the browser refuses', async () => {
    vi.stubGlobal('navigator', { serviceWorker: { getRegistrations: async () => { throw new Error('denied'); } } });
    const reload = vi.fn();
    await loadFreshApp(reload);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('reloads where there is no service worker at all', async () => {
    vi.stubGlobal('navigator', {});
    const reload = vi.fn();
    await loadFreshApp(reload);
    expect(reload).toHaveBeenCalledTimes(1);
  });
});
