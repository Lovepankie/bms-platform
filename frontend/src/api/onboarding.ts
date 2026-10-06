import { api, problemOf } from './client';
import type { components } from './schema';

// The self-onboarding API (ADR-024, spec sections 8, 10 and 11), typed by the generated schema
// (`npm run gen:api`): the public sign-up endpoints and the operator portal on the platform host.
// A failure throws an OnboardingError with the server's plain-words detail and code.

type S = components['schemas'];

export type SignUpRequest = S['SignUpRequest'];
export type ApplicantView = S['ApplicantView'];
export type Application = S['Application'];
export type ApplicationList = S['ApplicationList'];
export type ApplicationDetail = S['ApplicationDetail'];
export type PossibleDuplicate = S['PossibleDuplicate'];
export type ActivateRequest = S['ActivateApplicationRequest'];
export type ActivationResult = S['ActivationResult'];
export type OutboxStatus = S['OutboxStatus'];
export type OutboxFailure = S['OutboxFailure'];
export type SignInResponse = S['SignInResponse'];
export type MfaEnrolment = S['MfaEnrolment'];
export type PlatformMe = S['PlatformMe'];

export type ApplicationStatus = 'submitted' | 'needs_info' | 'verified' | 'activated' | 'rejected' | 'expired';

export const APPLICATION_STATUSES: ApplicationStatus[] = ['submitted', 'needs_info', 'verified', 'activated', 'rejected', 'expired'];

/** Plain words for each status, as the operator and the applicant read them. */
export const STATUS_LABEL: Record<ApplicationStatus, string> = {
  submitted: 'Waiting for review',
  needs_info: 'Needs more information',
  verified: 'Verified',
  activated: 'Activated',
  rejected: 'Not accepted',
  expired: 'Expired',
};

export function statusLabel(status: string | undefined): string {
  return (status && STATUS_LABEL[status as ApplicationStatus]) || status || '';
}

export class OnboardingError extends Error {
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
  throw new OnboardingError(problem.detail ?? fallback, problem.code, problem.errors ?? []);
}

// ---- Public: the applicant ------------------------------------------------------------------

export async function applyForAccount(body: SignUpRequest): Promise<void> {
  const { error } = await api.POST('/api/v1/platform/sign-up/applications', { body });
  if (error) fail(error, 'We could not send your application. Try again.');
}

export async function verifyApplicant(token: string): Promise<ApplicantView> {
  const { data, error } = await api.POST('/api/v1/platform/sign-up/verify', { body: { token } });
  if (error || !data) fail(error, 'We could not open your application.');
  return data;
}

export async function applicantStatus(token: string): Promise<ApplicantView> {
  const { data, error } = await api.POST('/api/v1/platform/sign-up/status', { body: { token } });
  if (error || !data) fail(error, 'We could not open your application.');
  return data;
}

export async function applicantReply(token: string, reply: string): Promise<ApplicantView> {
  const { data, error } = await api.POST('/api/v1/platform/sign-up/reply', { body: { token, reply } });
  if (error || !data) fail(error, 'We could not send your answer.');
  return data;
}

// ---- Operator portal --------------------------------------------------------------------------

export async function platformMe(): Promise<PlatformMe> {
  const { data, error } = await api.GET('/api/v1/platform/me');
  if (error || !data) fail(error, 'Could not load your profile');
  return data;
}

export async function listApplications(statuses: ApplicationStatus[]): Promise<ApplicationList> {
  const { data, error } = await api.GET('/api/v1/platform/applications', {
    params: { query: { status: statuses.length > 0 ? statuses : undefined } },
  });
  if (error || !data) fail(error, 'Could not load the applications');
  return data;
}

export async function getApplication(id: string): Promise<ApplicationDetail> {
  const { data, error } = await api.GET('/api/v1/platform/applications/{application_id}', {
    params: { path: { application_id: id } },
  });
  if (error || !data) fail(error, 'Could not load the application');
  return data;
}

export type Decision = 'verify' | 'needs-info' | 'reject';

export async function decide(id: string, decision: Decision, note?: string): Promise<ApplicationDetail> {
  const params = { path: { application_id: id } };
  if (decision === 'verify') {
    const { data, error } = await api.POST('/api/v1/platform/applications/{application_id}/verify', { params });
    if (error || !data) fail(error, 'Could not save the decision');
    return data;
  }
  const body = { note: note ?? '' };
  const { data, error } =
    decision === 'needs-info'
      ? await api.POST('/api/v1/platform/applications/{application_id}/needs-info', { params, body })
      : await api.POST('/api/v1/platform/applications/{application_id}/reject', { params, body });
  if (error || !data) fail(error, 'Could not save the decision');
  return data;
}

export async function activateApplication(id: string, body: ActivateRequest): Promise<ActivationResult> {
  const { data, error } = await api.POST('/api/v1/platform/applications/{application_id}/activate', {
    params: { path: { application_id: id } },
    body,
  });
  if (error || !data) fail(error, 'Could not activate the application');
  return data;
}

export async function outboxStatus(): Promise<OutboxStatus> {
  const { data, error } = await api.GET('/api/v1/platform/outbox');
  if (error || !data) fail(error, 'Could not load the outbox');
  return data;
}

export async function retryOutbox(id: string): Promise<void> {
  const { error } = await api.POST('/api/v1/platform/outbox/{outbox_id}/retry', { params: { path: { outbox_id: id } } });
  if (error) fail(error, 'Could not send it again');
}

export async function listPlans(): Promise<{ code?: string; name?: string; allowed_modules?: string[] }[]> {
  const { data, error } = await api.GET('/api/v1/platform/plans');
  if (error || !data) fail(error, 'Could not load the plans');
  return data.items ?? [];
}
