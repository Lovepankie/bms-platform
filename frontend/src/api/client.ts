import createClient, { type Middleware } from 'openapi-fetch';
import { clientBuildHeader, forceUpdate, updater } from '../app/auto-update';
import { getAccessToken, refreshSession, tenantHeaders } from '../auth/session';
import { APP_UPDATING, APP_UPDATING_CHECK, APP_UPDATING_MAYBE_SAVED } from './retail-errors';
import type { components, paths } from './schema';

// The typed API client. `schema.d.ts` is generated from docs/api/openapi.json (the committed
// contract snapshot) by `npm run gen:api`; hand-written request or response types are not
// allowed (ADR-009, chapter 7 section 7.3). CI fails if the generated file is stale.

export type Member = components['schemas']['Member'];
export type MemberListItem = components['schemas']['MemberListItem'];
export type MemberPage = components['schemas']['MemberPage'];
export type Me = components['schemas']['Me'];
export type Branding = components['schemas']['Branding'];
export type TenantSettings = components['schemas']['TenantSettings'];
export type SignInResponse = components['schemas']['SignInResponse'];
export type MfaEnrolment = components['schemas']['MfaEnrolment'];
export type Approval = components['schemas']['Approval'];
export type ApprovalPage = components['schemas']['ApprovalPage'];
export type Problem = { code?: string; detail?: string; errors?: { field: string; message: string }[] };

// Every request carries the in-memory access token (chapter 7 section 7.4.1). In local
// development only, the tenant also travels as X-Tenant because localhost carries no slug.
const auth: Middleware = {
  onRequest({ request }) {
    Object.entries(tenantHeaders()).forEach(([k, v]) => request.headers.set(k, v));
    const token = getAccessToken();
    if (token) request.headers.set('Authorization', `Bearer ${token}`);
    return request;
  },
  // An expired access token is refreshed once and a read is retried; writes are not replayed.
  async onResponse({ request, response }) {
    const authPath = ['/api/v1/auth/', '/api/v1/platform/auth/', '/api/v1/platform/sign-up/'].some((p) => request.url.includes(p));
    if (response.status !== 401 || authPath || request.method !== 'GET') {
      return response;
    }
    if (!(await refreshSession())) return response;
    const retry = new Request(request);
    retry.headers.set('Authorization', `Bearer ${getAccessToken() ?? ''}`);
    return fetch(retry);
  },
};

/** How long a request with an Idempotency-Key waits before its one retry; tests shorten it. */
export const updatingRetry = { delayMs: 4000 };

// While a deploy switches the server over, three things can come back instead of an answer, and they differ in what
// they prove (ADR-083). Every one is plain words in a problem body every screen already shows, and the form keeps what
// was typed.
//
// 1. A 502, 503 or 504 with `Bms-Not-Delivered: 1`: Caddy could not connect to any api container for 30 seconds
//    (deploy/caddy/upstreams.caddy), so nothing reached the API: "Nothing was saved yet".
// 2. Any other 502, 503 or 504 that is not the API's own problem body: a container took the request and then reset or
//    was stopped (Caddy says EOF), or Cloudflare timed out on the origin. The request may have been saved.
// 3. A dropped connection while the device says it is online: the request may have been saved too.
//
// A request with an Idempotency-Key is safe to send again, so it is, once, after a few seconds: the server replays the
// first result if the first attempt did land. Then only proof of 1 on every attempt says "Nothing was saved yet"; after
// 2 or 3 a keyed write says it may have been saved and that trying again is safe, and a write without a key says it may
// have been saved and to check first (a dropped one keeps the TypeError, which the screens read as an unknown outcome).
// A read or a sign-in saves nothing, so it always says "Nothing was saved yet". Offline, a dropped connection stays the
// TypeError the offline paths expect.
const GAP_STATUSES = [502, 503, 504];
const notDelivered = (response: Response) => GAP_STATUSES.includes(response.status) && response.headers.get('Bms-Not-Delivered') === '1';
const unsure = (response: Response) =>
  GAP_STATUSES.includes(response.status) && !notDelivered(response) && !(response.headers.get('content-type') ?? '').includes('json');
const online = () => typeof navigator === 'undefined' || navigator.onLine !== false;
const savesNothing = (request: Request) =>
  ['GET', 'HEAD'].includes(request.method) || ['/api/v1/auth/staff/login', '/api/v1/platform/auth/login'].includes(new URL(request.url).pathname);
const updating = (status: number, detail: string) =>
  new Response(JSON.stringify({ code: 'app_updating', detail }), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });

const replays = new WeakMap<Request, Request>();
// Requests one of whose attempts may have reached the API, so whether it was saved is not known.
const maybeSaved = new WeakSet<Request>();
/** The words for a request that ended without an answer, from what its attempts prove. */
function wordsFor(request: Request): string {
  if (!maybeSaved.has(request) || savesNothing(request)) return APP_UPDATING;
  return request.headers.has('Idempotency-Key') ? APP_UPDATING_MAYBE_SAVED : APP_UPDATING_CHECK;
}
async function retryOnce(request: Request, send: typeof fetch): Promise<Response | undefined> {
  const replay = replays.get(request);
  replays.delete(request);
  if (!replay) return undefined;
  await new Promise((resolve) => setTimeout(resolve, updatingRetry.delayMs));
  if (request.signal?.aborted) return undefined;
  try {
    return await send(replay);
  } catch {
    // The retry itself may have landed.
    maybeSaved.add(request);
    return undefined;
  }
}
/** A retry's answer: 409 idempotency_in_progress proves the first attempt reached the API and is still running. */
async function settle(retried: Response): Promise<Response> {
  if (retried.status === 409) {
    const code = await retried.clone().json().then((b: { code?: string }) => b.code, () => undefined);
    if (code === 'idempotency_in_progress') return updating(409, APP_UPDATING_MAYBE_SAVED);
  }
  return retried;
}

const deployGap: Middleware = {
  onRequest({ request }) {
    // The body is spent by the first attempt, so the retry needs its own copy taken before it is sent.
    if (request.headers.has('Idempotency-Key')) replays.set(request, request.clone());
    return request;
  },
  async onResponse({ request, response, options }) {
    if (!notDelivered(response) && !unsure(response)) return response;
    if (unsure(response)) maybeSaved.add(request);
    const retried = await retryOnce(request, options.fetch);
    if (retried) {
      if (unsure(retried)) maybeSaved.add(request);
      else if (!notDelivered(retried)) return settle(retried);
    }
    return updating(response.status, wordsFor(request));
  },
  async onError({ request, error, options }) {
    if (!online() || (error as { name?: string } | null)?.name === 'AbortError') return;
    if (savesNothing(request)) return new TypeError(APP_UPDATING);
    if (!replays.has(request)) return;
    maybeSaved.add(request);
    const retried = await retryOnce(request, options.fetch);
    if (retried) return settle(retried);
    return new TypeError(APP_UPDATING_MAYBE_SAVED);
  },
};

// The app's build both ways (#576, ADR-088): every request says which app sent it, every answer says which server built it. A
// 426 app_too_old means the server no longer serves this app.
const appBuild: Middleware = {
  onRequest({ request }) {
    const build = clientBuildHeader();
    if (build) request.headers.set('Bms-Client-Build', build);
    return request;
  },
  onResponse({ response }) {
    updater.serverBuild(response.headers.get('Bms-Build'));
    if (response.status === 426) forceUpdate();
    return response;
  },
};

export { APP_UPDATING, APP_UPDATING_CHECK, APP_UPDATING_MAYBE_SAVED };
export const api = createClient<paths>({ baseUrl: '/' });
api.use(appBuild);
api.use(auth);
api.use(deployGap);

// A save on its way holds back the app's own reload (ADR-088). It is counted around the whole call, retries included, and ends
// however the call ends: a middleware that throws (a failed refresh, the deploy-gap words) would skip the middleware hooks.
// A call that never ends (a phone link that hangs, no answer and no error) stops holding it back after WRITE_HOLD_MS, so one lost
// request cannot keep the tab on the old app for good.
export const WRITE_HOLD_MS = 2 * 60_000;
for (const method of ['POST', 'PUT', 'PATCH', 'DELETE'] as const) {
  const send = api[method] as (...args: unknown[]) => Promise<unknown>;
  (api as unknown as Record<string, unknown>)[method] = async (...args: unknown[]) => {
    let held = true;
    const release = () => {
      if (!held) return;
      held = false;
      updater.writeEnded();
    };
    updater.writeStarted();
    const expiry = setTimeout(release, WRITE_HOLD_MS);
    try {
      return await send(...args);
    } finally {
      clearTimeout(expiry);
      release();
    }
  };
}

/** The problem body of a failed call, for showing its detail (chapter 7 section 7.7). */
export function problemOf(error: unknown): Problem {
  if (error instanceof Error && [APP_UPDATING, APP_UPDATING_MAYBE_SAVED, APP_UPDATING_CHECK].includes(error.message)) return { code: 'app_updating', detail: error.message };
  return (error ?? {}) as Problem;
}

/** Liveness of the API (GET /healthz). Outside /api/v1, so not part of the typed contract. */
export async function fetchHealth(): Promise<'UP' | 'DOWN'> {
  try {
    const response = await fetch('/healthz');
    if (!response.ok) return 'DOWN';
    const body = (await response.json()) as { status?: string };
    return body.status === 'UP' ? 'UP' : 'DOWN';
  } catch {
    return 'DOWN';
  }
}

export async function fetchMe(): Promise<Me> {
  const { data, error } = await api.GET('/api/v1/me');
  if (error || !data) throw new Error(problemOf(error).detail ?? 'Could not load your profile');
  return data;
}

export async function listMembers(
  branchIds: string[] | undefined,
  limit = 50,
  q?: string,
  signal?: AbortSignal,
): Promise<MemberPage> {
  const { data, error } = await api.GET('/api/v1/lending/members', {
    params: { query: { limit, branch_id: branchIds, q: q || undefined } },
    signal,
  });
  if (error || !data) throw new Error('Could not load members');
  return data;
}

/** The pending requests in the branches given, only of `actionTypes` when given (the server filters). */
export async function listPendingApprovals(branchIds: string[] | undefined, actionTypes?: string[]): Promise<ApprovalPage> {
  const { data, error } = await api.GET('/api/v1/approvals', {
    params: { query: { status: ['pending'], branch_id: branchIds, action_type: actionTypes } },
  });
  if (error || !data) throw new Error(problemOf(error).detail ?? 'Could not load approvals');
  return data;
}

export async function fetchSettings(): Promise<TenantSettings> {
  const { data, error } = await api.GET('/api/v1/settings');
  if (error || !data) throw new Error(problemOf(error).detail ?? 'Could not load the settings');
  return data;
}
