// The signed-in session (chapter 7 section 7.4.1, ADR-014). The access token lives in memory only,
// never in local or session storage; the refresh token is an HttpOnly cookie the browser sends to
// /api/v1/auth, so a reload restores the session with one refresh call.

type Listener = () => void;

let accessToken: string | null = null;
const listeners = new Set<Listener>();

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  // A new sign-in replaced the refresh cookie: the session left behind by an offline sign-out is no longer this device's.
  if (token !== null) {
    cancelPendingLogout();
    clearLogoutPending();
  }
  accessToken = token;
  listeners.forEach((l) => l());
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

let inFlight: Promise<boolean> | null = null;
let unreachable = false;

/** True when the last refresh got no answer at all (no network), rather than a refusal (ADR-067 decision 9). */
export function refreshFailedOffline(): boolean {
  return unreachable;
}
let refreshPath = '/api/v1/auth/refresh';

/**
 * Which session this page restores: staff on a tenant host, or a platform operator on the platform
 * host, whose refresh cookie belongs to /api/v1/platform/auth (ADR-014). The operator portal sets it.
 */
export function setSessionScope(scope: 'staff' | 'platform'): void {
  refreshPath = scope === 'platform' ? '/api/v1/platform/auth/refresh' : '/api/v1/auth/refresh';
}

/**
 * Rotates the refresh cookie and stores the new access token. Concurrent callers share one
 * request: two rotations of the same token would look like reuse and revoke the session.
 */
export function refreshSession(): Promise<boolean> {
  // The person signed out offline: their cookie never restores a session (ADR-067 decision 9).
  if (logoutPending()) {
    unreachable = false;
    return Promise.resolve(false);
  }
  if (!inFlight) {
    inFlight = fetch(refreshPath, { method: 'POST', credentials: 'same-origin', headers: tenantHeaders() })
      .then(async (response) => {
        unreachable = false;
        if (!response.ok) {
          setAccessToken(null);
          return false;
        }
        const body = (await response.json()) as { access_token?: string | null };
        setAccessToken(body.access_token ?? null);
        return Boolean(body.access_token);
      })
      .catch(() => {
        unreachable = true;
        return false;
      })
      .finally(() => {
        inFlight = null;
      });
  }
  return inFlight;
}

const LOGOUT_PENDING = 'bms.logout_pending';

/**
 * A sign-out that could not reach the server (ADR-067 decision 9): the refresh cookie is HttpOnly, so only the logout call
 * ends that session. Until it does, no refresh restores it and the app stays on sign-in. False when it cannot be kept, so
 * the caller refuses to sign out.
 */
export function markLogoutPending(): boolean {
  try {
    window.localStorage.setItem(LOGOUT_PENDING, '1');
    return window.localStorage.getItem(LOGOUT_PENDING) === '1';
  } catch {
    return false;
  }
}

export function logoutPending(): boolean {
  try {
    return window.localStorage.getItem(LOGOUT_PENDING) === '1';
  } catch {
    return false;
  }
}

function clearLogoutPending(): void {
  try {
    window.localStorage.removeItem(LOGOUT_PENDING);
  } catch {
    // Nothing kept, nothing to clear.
  }
}

// A stalled connection (a captive portal, a weak signal) must not hold the installed app, which has no address bar, on a loader.
const PENDING_LOGOUT_TIMEOUT_MS = 10_000;

function deadline(ms: number): { controller: AbortController; clear: () => void } {
  const controller = new AbortController();
  if (typeof AbortSignal.timeout === 'function') {
    const timeout = AbortSignal.timeout(ms);
    const onTimeout = () => controller.abort(timeout.reason);
    timeout.addEventListener('abort', onTimeout);
    return { controller, clear: () => timeout.removeEventListener('abort', onTimeout) };
  }
  const timer = setTimeout(() => controller.abort(new DOMException('The operation timed out.', 'TimeoutError')), ms);
  return { controller, clear: () => clearTimeout(timer) };
}

// Moved on by every sign-in, so a pending-logout call that began before one never touches the note after it: its late
// answer would otherwise put back, or clear, the note for a cookie that is no longer the last person's.
let signInGeneration = 0;
let pendingLogoutCall: AbortController | null = null;

/** A sign-in is starting: abort any pending-logout call first, so its answer cannot replace the new person's cookie. */
export function cancelPendingLogout(): void {
  signInGeneration += 1;
  pendingLogoutCall?.abort(new DOMException('A sign-in started.', 'AbortError'));
  pendingLogoutCall = null;
}

/**
 * Ends the session an offline sign-out left on the server, before anything restores it. The logout call needs an access
 * token, so the cookie is rotated for that one call; the token is never stored or shown. True once the session is gone
 * (also when the server already refuses the cookie); false with no answer within 10 seconds, and the sign-out stays pending.
 * False as well, with the note left alone, when a sign-in began meanwhile: that sign-in owns the note from then on.
 */
export async function endPendingLogout(): Promise<boolean> {
  if (!logoutPending()) return true;
  // One deadline for both calls, so the person waits 10 seconds at most.
  const limit = deadline(PENDING_LOGOUT_TIMEOUT_MS);
  const generation = signInGeneration;
  pendingLogoutCall = limit.controller;
  try {
    const refreshed = await fetch(refreshPath, {
      method: 'POST',
      credentials: 'same-origin',
      headers: tenantHeaders(),
      signal: limit.controller.signal,
    });
    // 401: the server already refuses the cookie, so the session is gone; any other refusal may be passing.
    if (!refreshed.ok && refreshed.status !== 401) return false;
    const token = refreshed.ok ? ((await refreshed.json()) as { access_token?: string | null }).access_token : null;
    if (token) {
      const out = await fetch('/api/v1/auth/logout', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { ...tenantHeaders(), Authorization: `Bearer ${token}` },
        signal: limit.controller.signal,
      });
      if (!out.ok) return false;
    }
    if (generation !== signInGeneration) return false;
    clearLogoutPending();
    return true;
  } catch {
    return false;
  } finally {
    limit.clear();
    if (pendingLogoutCall === limit.controller) pendingLogoutCall = null;
  }
}

/** Local development only: the tenant from X-Tenant when the host carries no slug (chapter 7). */
export function tenantHeaders(): Record<string, string> {
  const env = import.meta.env;
  return env.DEV && env.VITE_DEV_TENANT ? { 'X-Tenant': env.VITE_DEV_TENANT } : {};
}
