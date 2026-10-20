import { api, fetchSettings, problemOf, type MfaEnrolment, type TenantSettings } from './client';

// The two-step sign-in client (issue #348; ADR-046; chapter 7 sections 7.11.2 and 7.11.4). The tenant's policy is three keys of the
// settings resource, changed with PATCH /settings and the settings version in If-Match; the signed-in set-up of a person's own second
// factor uses the same enrol and confirm routes as the first run, with the session instead of a sign-in token.

export type Policy = 'off' | 'roles' | 'everyone';

/** What the administrator chose on the screen. */
export type Choice = { policy: Policy; roles: string[]; graceDays: number };

export class TwoStepError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

const MESSAGES: Record<string, string | undefined> = {
  version_conflict: 'Someone else changed the settings while you were looking. Reload the page and choose again.',
  permission_denied: 'You are not allowed to change this. Ask an administrator.',
  invalid_password: 'That password is not right.',
  password_required: 'Type your own password to confirm.',
  totp_required: 'Type the current code from your authenticator app to confirm.',
  account_locked: 'Too many wrong tries. Wait 15 minutes and try again.',
  validation_failed: 'One of the roles you picked is not available. Reload the page and choose again.',
  invalid_mfa_code: 'That code is not right. Codes change every 30 seconds; try the next one.',
  mfa_already_enrolled: 'Two-step sign-in is already on for your account.',
};

const NETWORK_WORDS = 'The answer did not reach your phone, so we cannot tell if it worked. Reload the page to see how it is now.';

function fail(error: unknown, status: number, fallback: string): never {
  const problem = problemOf(error);
  throw new TwoStepError((problem.code && MESSAGES[problem.code]) || problem.detail || fallback, status, problem.code);
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) fail(result.error, result.response.status, fallback);
  return result.data;
}

async function sent<T>(call: Promise<T>): Promise<T> {
  try {
    return await call;
  } catch (e) {
    if (e instanceof TwoStepError) throw e;
    throw new TwoStepError(NETWORK_WORDS, 0, 'network');
  }
}

/** The proof of an administrator who relaxes the rule: the code from their authenticator, or their own password when they have none. */
export type Proof = { totp_code?: string; password?: string };

/** Whether the new choice asks more people than the current one does (a role added, or everyone, or something where nothing was). */
export function widens(current: Choice, next: Choice): boolean {
  if (next.policy === 'off') return false;
  if (current.policy === 'off') return true;
  if (next.policy === 'everyone') return current.policy !== 'everyone';
  return current.policy === 'roles' && next.roles.some((r) => !current.roles.includes(r));
}

/** Whether the new choice asks fewer people than the current one does: off, everyone narrowed to roles, or a role dropped. */
export function relaxes(current: Choice, next: Choice): boolean {
  if (current.policy === 'off') return false;
  if (next.policy === 'off') return true;
  if (current.policy === 'everyone') return next.policy === 'roles';
  return next.policy === 'roles' && current.roles.some((r) => !next.roles.includes(r));
}

/**
 * The settings body for a choice. The grace period moves the deadline, so it is sent only when it is meant to: when the policy
 * widens (new people need a date) or the administrator changed the grace period. Narrowing never sends it, so a date that has passed
 * is not re-opened. The caller passes the administrator's proof when saving needs it (the rule gets weaker or a date is put back).
 */
export function bodyOf(choice: Choice, current: Choice, proof: Proof = {}) {
  const sendGrace = choice.policy !== 'off' && (widens(current, choice) || choice.graceDays !== current.graceDays);
  return {
    require_mfa_all_staff: choice.policy === 'everyone',
    require_mfa_roles: choice.policy === 'roles' ? choice.roles : [],
    ...(sendGrace ? { mfa_grace_days: choice.graceDays } : {}),
    ...proof,
  };
}

export const twoStep = {
  settings: (): Promise<TenantSettings> => fetchSettings(),

  async save(version: number, choice: Choice, current: Choice, proof: Proof = {}): Promise<TenantSettings> {
    return unwrap(
      await sent(api.PATCH('/api/v1/settings', { params: { header: { 'If-Match': `"${version}"` } }, body: bodyOf(choice, current, proof) })),
      'Could not save your choice.',
    );
  },

  /** Starts the signed-in user's own set-up with their own password: a secret and a QR address, shown once. */
  async start(password: string): Promise<MfaEnrolment> {
    return unwrap(await sent(api.POST('/api/v1/auth/staff/mfa/enrol', { body: { password } })), 'Two-step sign-in could not start. Try again.');
  },

  /** Turns it on with the first code and returns the ten recovery codes, shown once. */
  async confirm(code: string): Promise<string[]> {
    const data = unwrap(await sent(api.POST('/api/v1/auth/staff/mfa/confirm', { body: { code } })), 'That code did not work. Try again.');
    return data.recovery_codes ?? [];
  },
};
