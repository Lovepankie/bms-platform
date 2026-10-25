import { api, problemOf } from './client';
import type { components } from './schema';

// Closing the business (issue #571, ADR-090): the Owner's request with the step-up and its cancel on the business's own host,
// and the operator's list, removal and the businesses with no Owner on the platform host. Typed by the generated schema.

type S = components['schemas'];

export type Closure = S['BusinessClosure'];
export type PlatformClosure = S['PlatformClosure'];
export type ClosureRemoved = S['PlatformClosureRemoved'];
export type TenantWithoutOwner = S['TenantWithoutOwner'];
export type OwnerCandidate = S['OwnerCandidate'];

/** The step-up: the authenticator code when the Owner has two-step sign-in, else the password. */
export type Proof = { totp_code: string } | { password: string };

function fail(error: unknown, fallback: string): never {
  const problem = problemOf(error);
  const fields = problem.errors ?? [];
  throw new Error(fields.length > 0 ? fields.map((f) => f.message).join(' ') : (problem.detail ?? fallback));
}

export async function closureView(): Promise<Closure> {
  const { data, error } = await api.GET('/api/v1/closure');
  if (error || !data) fail(error, 'Could not load the closing of the business.');
  return data;
}

export async function requestClosure(proof: Proof): Promise<Closure> {
  const { data, error } = await api.POST('/api/v1/closure', { body: proof });
  if (error || !data) fail(error, 'Could not ask to close the business. Nothing was changed.');
  return data;
}

export async function cancelClosure(): Promise<Closure> {
  const { data, error } = await api.POST('/api/v1/closure/cancel');
  if (error || !data) fail(error, 'Could not cancel the closing. Try again.');
  return data;
}

export async function platformClosures(): Promise<PlatformClosure[]> {
  const { data, error } = await api.GET('/api/v1/platform/closures');
  if (error || !data) fail(error, 'Could not load the businesses closing.');
  return data;
}

export async function removeClosedBusiness(closureId: string, totpCode: string, reason: string): Promise<ClosureRemoved> {
  const { data, error } = await api.POST('/api/v1/platform/closures/{closure_id}/remove', {
    params: { path: { closure_id: closureId } },
    body: { totp_code: totpCode, reason },
  });
  if (error || !data) fail(error, 'Could not remove the business. Nothing was changed.');
  return data;
}

export async function tenantsWithoutOwner(): Promise<TenantWithoutOwner[]> {
  const { data, error } = await api.GET('/api/v1/platform/tenants/without-owner');
  if (error || !data) fail(error, 'Could not load the businesses with no owner.');
  return data;
}
