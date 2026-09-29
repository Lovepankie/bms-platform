import createClient, { type Middleware } from 'openapi-fetch';
import type { components, paths } from './schema';

// The typed API client. `schema.d.ts` is generated from docs/api/openapi.json (the committed
// contract snapshot) by `npm run gen:api`; hand-written request or response types are not
// allowed (ADR-009, chapter 7 section 7.3). CI fails if the generated file is stale.

export type Member = components['schemas']['Member'];
export type MemberListItem = components['schemas']['MemberListItem'];
export type MemberPage = components['schemas']['MemberPage'];

// Local development only: the API runs with AUTH_MODE=dev and ALLOW_TENANT_HEADER=true, and
// reads the tenant and principal from these headers (chapter 7 sections 7.2 and 7.4.3). The
// variables are unset in every production build, so no header is sent there; real sign-in
// replaces this when the identity module lands.
const devHeaders: Middleware = {
  onRequest({ request }) {
    const env = import.meta.env;
    if (env.DEV && env.VITE_DEV_TENANT) {
      request.headers.set('X-Tenant', env.VITE_DEV_TENANT);
      request.headers.set('X-Dev-User-Id', env.VITE_DEV_USER_ID ?? '00000000-0000-4000-8000-00000000d001');
      request.headers.set('X-Dev-Permissions', env.VITE_DEV_PERMISSIONS ?? 'lending.members.read');
      request.headers.set('X-Dev-Branch-Ids', env.VITE_DEV_BRANCH_IDS ?? '*');
    }
    return request;
  },
};

export const api = createClient<paths>({ baseUrl: '/' });
api.use(devHeaders);

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

export async function listMembers(limit = 50): Promise<MemberPage> {
  const { data, error } = await api.GET('/api/v1/lending/members', { params: { query: { limit } } });
  if (error || !data) throw new Error('Could not load members');
  return data;
}
