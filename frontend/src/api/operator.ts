import { api, problemOf } from './client';
import type { components } from './schema';

// The operator screens of issue #407 (ADR-056): the businesses on the platform, one business's page and the price book, on the
// platform host for platform operators only. Typed by the generated schema (`npm run gen:api`). A failure throws an OperatorError
// with the server's plain-words detail and code, so a screen can show it as it is.

type S = components['schemas'];

export type Business = S['PlatformTenant'];
export type BusinessDetail = S['BusinessDetail'];
export type TenantAdmin = S['TenantAdmin'];
export type TenantMessage = S['TenantMessage'];
export type HistoryEntry = S['TenantHistoryEntry'];
export type Payment = S['SubscriptionPayment'];
export type PaymentRequest = S['SubscriptionPaymentRequest'];
export type Plan = S['Plan'];
export type PlanDetail = S['PlanDetail'];
export type PlanPrice = S['PlanPrice'];
export type SavePlanRequest = S['SavePlanRequest'];
export type AddPlanPriceRequest = S['AddPlanPriceRequest'];

export type SubscriptionStatus = 'trial' | 'active' | 'past_due' | 'suspended' | 'cancelled';

export class OperatorError extends Error {
  constructor(
    message: string,
    readonly code?: string,
    readonly fields: { field: string; message: string }[] = [],
  ) {
    super(message);
  }
}

function fail(error: unknown, fallback: string): never {
  const problem = problemOf(error);
  const fields = problem.errors ?? [];
  const detail = fields.length > 0 && problem.code === 'validation_failed' ? fields.map((f) => f.message).join(' ') : problem.detail;
  throw new OperatorError(detail ?? fallback, problem.code, fields);
}

export interface BusinessFilter {
  q?: string;
  statuses?: SubscriptionStatus[];
  module?: string;
}

export async function listBusinesses(filter: BusinessFilter = {}): Promise<Business[]> {
  const { data, error } = await api.GET('/api/v1/platform/tenants', {
    params: {
      query: {
        q: filter.q?.trim() ? filter.q.trim() : undefined,
        status: filter.statuses && filter.statuses.length > 0 ? filter.statuses : undefined,
        module: filter.module || undefined,
      },
    },
  });
  if (error || !data) fail(error, 'Could not load the businesses');
  return data.items ?? [];
}

export async function businessOverview(tenantId: string): Promise<BusinessDetail> {
  const { data, error } = await api.GET('/api/v1/platform/tenants/{tenant_id}/overview', {
    params: { path: { tenant_id: tenantId } },
  });
  if (error || !data) fail(error, 'Could not load the business');
  return data;
}

const tenantPath = (tenantId: string) => ({ path: { tenant_id: tenantId } });

export async function suspendBusiness(tenantId: string, reason: string): Promise<Business> {
  const { data, error } = await api.POST('/api/v1/platform/tenants/{tenant_id}/suspend', {
    params: tenantPath(tenantId),
    body: { reason },
  });
  if (error || !data) fail(error, 'Could not suspend the business');
  return data;
}

export async function resumeBusiness(tenantId: string, reason: string): Promise<Business> {
  const { data, error } = await api.POST('/api/v1/platform/tenants/{tenant_id}/resume', {
    params: tenantPath(tenantId),
    body: { reason },
  });
  if (error || !data) fail(error, 'Could not resume the business');
  return data;
}

export async function setSubscriptionStatus(tenantId: string, status: SubscriptionStatus, reason: string): Promise<Business> {
  const { data, error } = await api.POST('/api/v1/platform/tenants/{tenant_id}/subscription', {
    params: tenantPath(tenantId),
    body: { status, reason },
  });
  if (error || !data) fail(error, 'Could not change the status');
  return data;
}

export async function setBusinessPlan(tenantId: string, planCode: string, reason: string): Promise<Business> {
  const { data, error } = await api.PUT('/api/v1/platform/tenants/{tenant_id}/plan', {
    params: tenantPath(tenantId),
    body: { plan_code: planCode, reason },
  });
  if (error || !data) fail(error, 'Could not change the plan');
  return data;
}

export async function setSubscriptionDates(
  tenantId: string,
  dates: { trial_ends_on?: string; paid_until?: string; clear_trial_ends_on?: boolean; clear_paid_until?: boolean },
  reason: string,
): Promise<Business> {
  const { data, error } = await api.PUT('/api/v1/platform/tenants/{tenant_id}/subscription-dates', {
    params: tenantPath(tenantId),
    body: { ...dates, reason },
  });
  if (error || !data) fail(error, 'Could not change the date');
  return data;
}

export async function setBusinessModules(tenantId: string, modules: string[], reason: string): Promise<Business> {
  const { data, error } = await api.PUT('/api/v1/platform/tenants/{tenant_id}/modules', {
    params: tenantPath(tenantId),
    body: { modules, reason },
  });
  if (error || !data) fail(error, 'Could not change the modules');
  return data;
}

/** A pilot (complimentary) business is free with no free month end (ADR-024 decision 6). */
export async function setBusinessPilot(tenantId: string, pilot: boolean, reason: string): Promise<Business> {
  const { data, error } = await api.PUT('/api/v1/platform/tenants/{tenant_id}/pilot', {
    params: tenantPath(tenantId),
    body: { pilot, reason },
  });
  if (error || !data) fail(error, 'Could not change the pilot mark');
  return data;
}

/**
 * The key is made once per form and kept while only the confirmation box changes, so a second tap, a retry, or a resend
 * after a lost answer records the payment once (chapter 7 section 7.8).
 */
export async function recordPayment(tenantId: string, idempotencyKey: string, body: PaymentRequest): Promise<Payment> {
  const { data, error } = await api.POST('/api/v1/platform/tenants/{tenant_id}/payments', {
    params: { ...tenantPath(tenantId), header: { 'Idempotency-Key': idempotencyKey } },
    body,
  });
  if (error || !data) fail(error, 'Could not record the payment');
  return data;
}

/** Both owner rescues need the operator's own code and a reason, kept in the platform audit log. */
export async function resetOwnerSignIn(
  tenantId: string,
  userId: string,
  totpCode: string,
  reason: string,
): Promise<{ url?: string; expires_at?: string }> {
  const { data, error } = await api.POST('/api/v1/platform/tenants/{tenant_id}/users/{user_id}/reset-sign-in', {
    params: { path: { tenant_id: tenantId, user_id: userId } },
    body: { totp_code: totpCode, reason },
  });
  if (error || !data) fail(error, 'Could not make the link');
  return data;
}

export async function resetOwnerTwoStep(tenantId: string, userId: string, totpCode: string, reason: string): Promise<void> {
  const { error } = await api.POST('/api/v1/platform/tenants/{tenant_id}/users/{user_id}/mfa/reset', {
    params: { path: { tenant_id: tenantId, user_id: userId } },
    body: { totp_code: totpCode, reason },
  });
  if (error) fail(error, 'Could not reset the two-step sign-in');
}

export async function listPricePlans(includeInactive = true): Promise<Plan[]> {
  const { data, error } = await api.GET('/api/v1/platform/plans', {
    params: { query: { include_inactive: includeInactive } },
  });
  if (error || !data) fail(error, 'Could not load the plans');
  return data.items ?? [];
}

export async function planDetail(code: string): Promise<PlanDetail> {
  const { data, error } = await api.GET('/api/v1/platform/plans/{plan_code}', { params: { path: { plan_code: code } } });
  if (error || !data) fail(error, 'Could not load the plan');
  return data;
}

export async function savePlan(code: string, body: SavePlanRequest): Promise<PlanDetail> {
  const { data, error } = await api.PUT('/api/v1/platform/plans/{plan_code}', { params: { path: { plan_code: code } }, body });
  if (error || !data) fail(error, 'Could not save the plan');
  return data;
}

export async function addPlanPrice(code: string, body: AddPlanPriceRequest): Promise<PlanDetail> {
  const { data, error } = await api.POST('/api/v1/platform/plans/{plan_code}/prices', {
    params: { path: { plan_code: code } },
    body,
  });
  if (error || !data) fail(error, 'Could not save the price');
  return data;
}
