import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { classify } from '../offline/outbox';
import { memoryStore } from '../offline/store';
import { clientBuildHeader, createUpdater, type UpdaterDeps } from './auto-update';
import { claimReload, reloadClaimState } from './stale-app';
import { setUnsavedWork } from './unsaved-work';

// #576, ADR-088: a phone on an old app updates by itself. It finds out from the server's build, reloads only at a safe moment
// (never under a cart, a typed form, a save on its way or typing), turns the next move between screens into a full load, keeps
// #543's one reload a minute and no reload offline, and leaves the offline sales outbox alone. Builds and times are invented.

const memory = () => {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) } as unknown as Storage;
};

function setUp(patch: Partial<UpdaterDeps> = {}, storage: Storage | null = memory()) {
  let clock = 1_000_000;
  const reloads: (string | undefined)[] = [];
  const update = vi.fn(async () => undefined);
  const state = { online: true, typing: false };
  const hidden: number[] = [];
  const later: { fn: () => void; ms: number }[] = [];
  const updater = createUpdater({
    build: 'sha-old0001',
    update,
    reload: (href) => void reloads.push(href),
    claim: () => claimReload(clock, storage),
    claimState: () => reloadClaimState(clock, storage),
    online: () => state.online,
    typing: () => state.typing,
    announce: (then) => then(),
    hide: () => void hidden.push(clock),
    later: (fn, ms) => void later.push({ fn, ms }),
    now: () => clock,
    ...patch,
  });
  return { updater, update, reloads, state, hidden, later, tick: (ms: number) => void (clock += ms) };
}

afterEach(() => {
  setUnsavedWork('cart', false);
  setUnsavedWork('form', false);
});

describe('finding out about a new build', () => {
  it('asks for the new worker when an answer names another build', () => {
    const { updater, update } = setUp();
    updater.serverBuild('sha-new0002');
    expect(update).toHaveBeenCalledTimes(1);
  });

  it('asks nothing when the builds match, the server has none, or this app has none (development)', () => {
    const { updater, update } = setUp();
    updater.serverBuild('sha-old0001');
    updater.serverBuild(null);
    updater.serverBuild('unknown');
    expect(update).not.toHaveBeenCalled();
    const dev = setUp({ build: '' });
    dev.updater.serverBuild('sha-new0002');
    expect(dev.update).not.toHaveBeenCalled();
  });

  it('asks once a minute at most, however many answers say so, and never offline', () => {
    const { updater, update, state, tick } = setUp();
    updater.serverBuild('sha-new0002');
    updater.serverBuild('sha-new0002');
    updater.check();
    expect(update).toHaveBeenCalledTimes(1);
    tick(60_000);
    updater.check();
    expect(update).toHaveBeenCalledTimes(2);
    tick(60_000);
    state.online = false;
    updater.check();
    expect(update).toHaveBeenCalledTimes(2);
  });

  it('sends its own build with its commit time, and nothing in development', () => {
    expect(clientBuildHeader('sha-old0001', '20261025120000')).toBe('sha-old0001; built=20261025120000');
    expect(clientBuildHeader('sha-old0001', '')).toBe('sha-old0001');
    expect(clientBuildHeader('', '20261025120000')).toBeNull();
  });
});

describe('applying it at a safe moment', () => {
  it('reloads at once when the new worker takes over and nothing is unsaved', () => {
    const { updater, reloads } = setUp();
    updater.workerChanged();
    expect(reloads).toEqual([undefined]);
  });

  it('waits while a cart is on Sell, and reloads when it is sold or cleared', () => {
    const { updater, reloads } = setUp();
    setUnsavedWork('cart', true);
    updater.workerChanged();
    expect(reloads).toEqual([]);
    setUnsavedWork('cart', false);
    updater.trySoon();
    expect(reloads).toEqual([undefined]);
  });

  it('waits while a form has typed changes, while someone types, and while a save is on its way', () => {
    const { updater, reloads, state } = setUp();
    setUnsavedWork('form', true);
    updater.workerChanged();
    setUnsavedWork('form', false);
    state.typing = true;
    updater.trySoon();
    expect(reloads).toEqual([]);
    state.typing = false;
    updater.writeStarted();
    updater.trySoon();
    expect(reloads).toEqual([]);
    updater.writeEnded();
    expect(reloads).toEqual([undefined]);
  });

  it('turns the next move between screens into a full load of the target, also with a cart open', () => {
    const { updater, reloads } = setUp();
    setUnsavedWork('cart', true);
    updater.workerChanged();
    expect(updater.beforeNavigate('/staff/retail/sales?branch=b1')).toBe(true);
    expect(reloads).toEqual(['/staff/retail/sales?branch=b1']);
  });

  it('does not load anew on a move while a save is on its way, nor before a new worker took over', () => {
    const { updater, reloads } = setUp();
    expect(updater.beforeNavigate('/staff')).toBe(false);
    updater.workerChanged();
    expect(reloads).toEqual([undefined]);
    const busy = setUp();
    busy.updater.writeStarted();
    busy.updater.workerChanged();
    expect(busy.updater.beforeNavigate('/staff')).toBe(false);
    expect(busy.reloads).toEqual([]);
  });

  it('shows the toast before it reloads', () => {
    const order: string[] = [];
    const { updater } = setUp({ announce: (then) => (order.push('toast'), then()), reload: () => void order.push('reload') });
    updater.workerChanged();
    expect(order).toEqual(['toast', 'reload']);
  });
});

describe('the moment the toast shows (review of #579)', () => {
  /** An announce that holds the reload until `fire` is called, as the 1.2 s toast does. */
  const held = () => {
    const pending: (() => void)[] = [];
    return { announce: (then: () => void) => void pending.push(then), fire: () => pending.splice(0).forEach((f) => f()) };
  };

  it('checks again when the toast is over: a save tapped meanwhile calls the reload off, and it comes once the save is done', () => {
    const toast = held();
    const { updater, reloads, hidden } = setUp({ announce: toast.announce });
    updater.workerChanged();
    updater.writeStarted();
    toast.fire();
    expect(reloads).toEqual([]);
    expect(hidden).toHaveLength(1);
    updater.writeEnded();
    toast.fire();
    expect(reloads).toEqual([undefined]);
  });

  it('a cart started while the toast shows calls the reload off too', () => {
    const toast = held();
    const { updater, reloads } = setUp({ announce: toast.announce });
    updater.workerChanged();
    setUnsavedWork('cart', true);
    toast.fire();
    expect(reloads).toEqual([]);
  });

  it('a move between screens does not load anew when the person starts a save or types on the new screen meanwhile', () => {
    const toast = held();
    const { updater, reloads, state } = setUp({ announce: toast.announce });
    setUnsavedWork('cart', true);
    updater.workerChanged();
    expect(updater.beforeNavigate('/staff/retail/stock')).toBe(true);
    updater.writeStarted();
    toast.fire();
    expect(reloads).toEqual([]);
    updater.writeEnded();
    state.typing = true;
    expect(updater.beforeNavigate('/staff/retail')).toBe(false);
  });

  it('claims the one reload of the minute only when it really reloads', () => {
    const storage = memory();
    const toast = held();
    const { updater, reloads } = setUp({ announce: toast.announce }, storage);
    updater.workerChanged();
    setUnsavedWork('form', true);
    toast.fire();
    setUnsavedWork('form', false);
    updater.trySoon();
    toast.fire();
    expect(reloads).toEqual([undefined]);
  });

  it('a reload held back by the one-a-minute rule is tried again after the minute, without waiting for another event', () => {
    const storage = memory();
    claimReload(1_000_000 - 10_000, storage);
    const { updater, reloads, later, tick } = setUp({}, storage);
    updater.workerChanged();
    expect(reloads).toEqual([]);
    expect(later.map((l) => l.ms)).toEqual([60_000]);
    tick(60_000);
    later.shift()?.fn();
    expect(reloads).toEqual([undefined]);
  });

  /** Runs every retry the updater schedules, as the timers would, until none is left (or a bound is hit). */
  const drain = (later: { fn: () => void }[]) => {
    let runs = 0;
    while (later.length > 0 && runs < 100) {
      later.shift()?.fn();
      runs++;
    }
    return runs;
  };

  it('never flashes the toast while the minute is not over, and stops trying after a few times (a clock set back)', () => {
    const storage = memory();
    // The last reload is in the future for this clock, as after the phone set its clock back: the minute does not end.
    claimReload(1_000_000 + 3_600_000, storage);
    let toasts = 0;
    const { updater, reloads, later } = setUp({ announce: (then) => (toasts++, then()) }, storage);
    updater.workerChanged();
    expect(drain(later)).toBe(5);
    expect(toasts).toBe(0);
    expect(reloads).toEqual([]);
  });

  it('after the capped retries, the next view or move tries again: one toast for the one real attempt', () => {
    const storage = memory();
    claimReload(1_000_000 + 3_600_000, storage);
    let toasts = 0;
    const { updater, reloads, later, tick } = setUp({ announce: (then) => (toasts++, then()) }, storage);
    updater.workerChanged();
    drain(later);
    expect(toasts).toBe(0);
    // The clock is right again and the minute is long over; the tab comes back into view.
    tick(3_700_000);
    updater.trySoon();
    expect(toasts).toBe(1);
    expect(reloads).toEqual([undefined]);
    expect(drain(later)).toBe(0);
  });

  it('when the reloads cannot be counted: no toast, no retry, however long it runs', () => {
    let toasts = 0;
    const { updater, reloads, later } = setUp({ announce: (then) => (toasts++, then()) }, null);
    updater.workerChanged();
    updater.beforeNavigate('/staff');
    expect(drain(later)).toBe(0);
    expect(toasts).toBe(0);
    expect(reloads).toEqual([]);
  });

  it.each([
    ['forgets the claim', { getItem: () => null, setItem: () => undefined }],
    [
      'is full and refuses the claim',
      {
        getItem: () => null,
        setItem: () => {
          throw new DOMException('full', 'QuotaExceededError');
        },
      },
    ],
  ])('a storage that %s shows the toast once and never again, whatever wakes the update', (_, storage) => {
    let toasts = 0;
    const { updater, reloads, later, hidden, tick } = setUp({ announce: (then) => (toasts++, then()) }, storage as unknown as Storage);
    updater.workerChanged();
    expect(drain(later)).toBe(0);
    for (let i = 0; i < 3; i++) {
      tick(120_000);
      updater.trySoon();
      updater.writeStarted();
      updater.writeEnded();
      setUnsavedWork('form', true);
      setUnsavedWork('form', false);
      updater.beforeNavigate('/staff/retail');
      updater.check();
    }
    expect(drain(later)).toBe(0);
    expect(toasts).toBe(1);
    expect(hidden).toHaveLength(1);
    expect(reloads).toEqual([]);
  });

  it('a second move while the toast shows takes the full load to where the person went last', () => {
    const toast = held();
    const { updater, reloads } = setUp({ announce: toast.announce });
    setUnsavedWork('cart', true);
    updater.workerChanged();
    updater.beforeNavigate('/staff/retail/stock');
    updater.beforeNavigate('/staff/retail/sales');
    toast.fire();
    expect(reloads).toEqual(['/staff/retail/sales']);
  });
});

describe('no reload loop (#543)', () => {
  it('reloads once a minute at most', () => {
    const storage = memory();
    const reloads: unknown[] = [];
    const deps = { reload: () => void reloads.push(1) };
    setUp(deps, storage).updater.workerChanged();
    setUp(deps, storage).updater.workerChanged();
    expect(reloads).toHaveLength(1);
  });

  it('never reloads offline, and does once back online', () => {
    const { updater, reloads, state } = setUp();
    state.online = false;
    updater.workerChanged();
    expect(reloads).toEqual([]);
    state.online = true;
    updater.trySoon();
    expect(reloads).toEqual([undefined]);
  });

  it('never reloads when the reloads cannot be counted', () => {
    const { updater, reloads } = setUp({}, null);
    updater.workerChanged();
    updater.beforeNavigate('/staff');
    expect(reloads).toEqual([]);
  });
});

describe('the offline sales outbox', () => {
  it('survives the update: an update reloads and deletes no stored data', async () => {
    const store = memoryStore();
    await store.addItem({
      id: 'sale-1', scope: 'test.example|u1', key: 'key-1', request: { payment_method: 'cash', lines: [] }, occurredAt: '2026-10-25T08:00:00Z',
      branchId: 'b1', branchName: 'Test Branch A', method: 'cash', totalMinor: 1000, lines: [], provisionalNo: 'P-1', state: 'queued', attempts: 0, nextAttemptAt: 0,
    });
    const deleteDatabase = vi.fn();
    const deleteCache = vi.fn();
    vi.stubGlobal('indexedDB', { deleteDatabase });
    vi.stubGlobal('caches', { delete: deleteCache, keys: async () => ['workbox-precache'] });
    const { updater, reloads } = setUp();
    updater.serverBuild('sha-new0002');
    updater.workerChanged();
    expect(reloads).toHaveLength(1);
    expect(deleteDatabase).not.toHaveBeenCalled();
    expect(deleteCache).not.toHaveBeenCalled();
    expect((await store.outbox('test.example|u1')).map((i) => i.id)).toEqual(['sale-1']);
    vi.unstubAllGlobals();
  });

  it('keeps a sale the server refused as too old waiting, without counting it towards giving up', () => {
    expect(classify(426, { code: 'app_too_old', detail: 'A new version of the app is ready.' })).toEqual({
      kind: 'retry',
      message: 'A new version of the app is ready.',
      answered: false,
    });
  });
});

describe('the plugin leaves the reload to the app', () => {
  it('vite-plugin-pwa (autoUpdate) calls onNeedReload instead of reloading when a new worker activates', () => {
    // main.tsx passes onNeedReload; if a plugin upgrade dropped this branch, the immediate mid-sale reload would be back.
    const register = readFileSync(fileURLToPath(new URL('../../node_modules/vite-plugin-pwa/dist/client/build/register.js', import.meta.url)), 'utf8');
    expect(register).toMatch(/addEventListener\("activated"[\s\S]*?if \(onNeedReload\)\s*onNeedReload\(\);\s*else\s*window\.location\.reload\(\)/);
    expect(readFileSync(fileURLToPath(new URL('../main.tsx', import.meta.url)), 'utf8')).toMatch(/onNeedReload: \(\) => updater\.workerChanged\(\)/);
  });
});
