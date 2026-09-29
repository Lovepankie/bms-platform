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
  accessToken = token;
  listeners.forEach((l) => l());
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

let inFlight: Promise<boolean> | null = null;

/**
 * Rotates the refresh cookie and stores the new access token. Concurrent callers share one
 * request: two rotations of the same token would look like reuse and revoke the session.
 */
export function refreshSession(): Promise<boolean> {
  if (!inFlight) {
    inFlight = fetch('/api/v1/auth/refresh', { method: 'POST', credentials: 'same-origin', headers: tenantHeaders() })
      .then(async (response) => {
        if (!response.ok) {
          setAccessToken(null);
          return false;
        }
        const body = (await response.json()) as { access_token?: string | null };
        setAccessToken(body.access_token ?? null);
        return Boolean(body.access_token);
      })
      .catch(() => false)
      .finally(() => {
        inFlight = null;
      });
  }
  return inFlight;
}

/** Local development only: the tenant from X-Tenant when the host carries no slug (chapter 7). */
export function tenantHeaders(): Record<string, string> {
  const env = import.meta.env;
  return env.DEV && env.VITE_DEV_TENANT ? { 'X-Tenant': env.VITE_DEV_TENANT } : {};
}
