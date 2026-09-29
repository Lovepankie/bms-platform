import createClient, { type Middleware } from 'openapi-fetch';
import { getAccessToken, refreshSession, tenantHeaders } from '../auth/session';
import type { components, paths } from './schema';

// The typed API client. `schema.d.ts` is generated from docs/api/openapi.json (the committed
// contract snapshot) by `npm run gen:api`; hand-written request or response types are not
// allowed (ADR-009, chapter 7 section 7.3). CI fails if the generated file is stale.

export type Member = components['schemas']['Member'];
export type MemberListItem = components['schemas']['MemberListItem'];
export type MemberPage = components['schemas']['MemberPage'];
export type Me = components['schemas']['Me'];
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
    if (response.status !== 401 || request.url.includes('/api/v1/auth/') || request.method !== 'GET') {
      return response;
    }
    if (!(await refreshSession())) return response;
    const retry = new Request(request);
    retry.headers.set('Authorization', `Bearer ${getAccessToken() ?? ''}`);
    return fetch(retry);
  },
};

export const api = createClient<paths>({ baseUrl: '/' });
api.use(auth);

/** The problem body of a failed call, for showing its detail (chapter 7 section 7.7). */
export function problemOf(error: unknown): Problem {
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

export async function listMembers(branchIds: string[] | undefined, limit = 50): Promise<MemberPage> {
  const { data, error } = await api.GET('/api/v1/lending/members', {
    params: { query: { limit, branch_id: branchIds } },
  });
  if (error || !data) throw new Error('Could not load members');
  return data;
}

export async function listPendingApprovals(branchIds: string[] | undefined): Promise<ApprovalPage> {
  const { data, error } = await api.GET('/api/v1/approvals', {
    params: { query: { status: ['pending'], branch_id: branchIds } },
  });
  if (error || !data) throw new Error(problemOf(error).detail ?? 'Could not load approvals');
  return data;
}
