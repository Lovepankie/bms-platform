import type { ErrorComponentProps } from '@tanstack/react-router';
import { useEffect, useState } from 'react';

// After a deploy the screens are split into new files with new names (ADR-009), and the old ones are gone.
// A phone still running the app it loaded before the deploy then fails to open the next screen with
// "Importing a module script failed" (Safari) or "Failed to fetch dynamically imported module" (Chrome).
// Loading the page again picks up the new app, so the app does that once by itself.

// The last two cover a host that answers a missing file with the page itself instead of a 404.
const STALE =
  /Importing a module script failed|Failed to fetch dynamically imported module|error loading dynamically imported module|Unable to preload CSS|is not a valid JavaScript MIME type|disallowed MIME type/i;
const KEY = 'bms.staleReloadAt';
// One reload per minute at most, so a screen that is really broken does not reload for ever.
const QUIET_MS = 60_000;

/** Whether `error` is the browser failing to load a screen file that a newer deploy replaced. */
export const isStaleAppError = (error: unknown) => STALE.test(error instanceof Error ? error.message : String(error ?? ''));

/** Session storage, or null where the browser refuses it (blocked site data, some locked-down browsers). */
const sessionStore = (): Storage | null => {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
};

/**
 * Claims the one reload allowed this minute; true when the caller should reload now. Without working
 * storage the reload cannot be counted, so it is never claimed and the person gets the button instead:
 * a missing file that a reload does not fix must not reload for ever.
 */
export function claimReload(now = Date.now(), storage: Storage | null = sessionStore()): boolean {
  if (!storage) return false;
  try {
    const seen = storage.getItem(KEY);
    if (seen && now - Number(seen) < QUIET_MS) return false;
    storage.setItem(KEY, String(now));
    return storage.getItem(KEY) === String(now);
  } catch {
    return false;
  }
}

/**
 * How long the new app may take to arrive before the page reloads anyway. An iPhone saving the ~230 files of a new
 * app over mobile data needs far more than the 3 s first chosen, and a reload stops the download, so every tap of
 * "Load the page again" started it over and the phone never got the new app (Hillary's iPhone, 24 Oct 2026).
 */
const UPDATE_LIMIT_MS = 45_000;
/** How long asking the server for a newer worker may take; a hung request must not hold the page for the full limit. */
const CHECK_LIMIT_MS = 5_000;

export const OFFLINE_MESSAGE = 'You are offline. The app will update when you are back online.';

/** Whether the browser says it has no network. Where it cannot tell, the phone counts as online. */
const isOffline = () => typeof navigator !== 'undefined' && navigator.onLine === false;

/**
 * Asks every registration to fetch the newest worker and, when one is installing or waiting, waits until it takes
 * over. The worker is generated with skipWaiting and clientsClaim on (vite.config.ts, registerType autoUpdate), so a
 * new worker takes over by itself as soon as it has saved the new app files.
 */
async function updateWorker(checkMs: number): Promise<void> {
  const sw = typeof navigator !== 'undefined' ? navigator.serviceWorker : undefined;
  if (!sw) return;
  const regs = await sw.getRegistrations();
  await Promise.race([Promise.all(regs.map((r) => r.update().catch(() => undefined))), wait(checkMs)]);
  const next = regs.map((r) => r.installing ?? r.waiting).find((w) => w);
  if (!next) return;
  await new Promise<void>((resolve) => {
    sw.addEventListener('controllerchange', () => resolve(), { once: true });
    // A worker whose install failed never takes over: stop waiting and reload, rather than sit out the limit.
    next.addEventListener?.('statechange', () => next.state === 'redundant' && resolve());
  });
}

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Loads the newest app, also when the phone's offline copy (the service worker) still holds the old one
 * (Hillary, 24 Oct 2026: refreshing kept showing "A new version of the app is ready"). It lets the new worker take
 * over first, for at most UPDATE_LIMIT_MS, then always reloads. It deletes no saved app files and keeps the worker
 * registered, so the app still opens offline and the phone keeps its push notices sign-up (ADR-050); the server
 * keeps the old app files for a phone that is still behind (#525). Offline it reloads nothing and returns 'offline':
 * a reload without network would only show the browser's offline page.
 */
export async function loadFreshApp(
  reload: () => void = () => location.reload(),
  limitMs = UPDATE_LIMIT_MS,
): Promise<'reloaded' | 'offline'> {
  if (isOffline()) return 'offline';
  let timer: ReturnType<typeof setTimeout> | undefined;
  const limit = new Promise<void>((resolve) => {
    timer = setTimeout(resolve, limitMs);
  });
  // A browser that refuses any of this still gets the plain reload.
  await Promise.race([updateWorker(Math.min(CHECK_LIMIT_MS, limitMs)).catch(() => undefined), limit]);
  clearTimeout(timer);
  reload();
  return 'reloaded';
}

/** Reloads the page when a reload can be claimed; true when it reloads. */
export function reloadOnce(now = Date.now(), storage: Storage | null = sessionStore(), reload = () => void loadFreshApp()): boolean {
  if (isOffline() || !claimReload(now, storage)) return false;
  reload();
  return true;
}

/** The router's error screen: a stale app reloads itself; anything else says so in plain words. */
export function RouteError({ error }: ErrorComponentProps) {
  const stale = isStaleAppError(error);
  // The decision is made once, at the first render; the reload itself runs after rendering.
  const [offline, setOffline] = useState(() => stale && isOffline());
  const [reloading] = useState(() => stale && !offline && claimReload());
  // While the new app downloads the page must not be reloaded again, so a tap shows progress instead of the button.
  const [getting, setGetting] = useState(false);
  const load = () => {
    setGetting(true);
    void loadFreshApp().then((r) => {
      setOffline(r === 'offline');
      setGetting(false);
    });
  };
  useEffect(() => {
    if (reloading) void loadFreshApp();
    else if (!stale) console.error('Screen error', error);
  }, [reloading, stale, error]);
  // Back online, the waiting app updates by itself, as the offline message promises.
  useEffect(() => {
    if (!offline) return;
    const back = () => void loadFreshApp();
    window.addEventListener('online', back, { once: true });
    return () => window.removeEventListener('online', back);
  }, [offline]);
  if (reloading || getting) return <p role="status">Getting the new version of the app… This can take a minute on a phone.</p>;
  return (
    <div role="alert" className={stale ? 'alert alert-info' : 'alert alert-danger'}>
      <p>{offline ? OFFLINE_MESSAGE : stale ? 'A new version of the app is ready.' : 'This screen could not open.'}</p>
      <button type="button" className="btn btn-primary" onClick={load}>
        Load the page again
      </button>
    </div>
  );
}
