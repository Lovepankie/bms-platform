import { api, problemOf } from './client';

// The forgotten staff password (FR-IAM-15, ADR-042; chapter 7 section 7.11.2 and 7.11.4). Two calls:
//  - requestPasswordReset: the sign-in page's "Forgot password?" form. It answers the same for every login, so the page can too.
//  - resetUserSignIn: the admin action (the Users screen's Reset sign-in button, issue #339). It returns the one-time link,
//    shown once to the admin to hand to the user.

export interface SignInResetLink {
  /** The link the user opens to choose a new password; contains the token in its fragment */
  url: string;
  /** When the link stops working (one hour after it was issued), an ISO 8601 time */
  expiresAt: string;
}

export class SignInResetError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

const MESSAGES: Record<string, string | undefined> = {
  permission_denied: 'Only a tenant administrator can reset someone’s sign-in.',
  // A branch-scoped administrator gets 404 for a person outside their branches too, so the words cover both (issue #369).
  not_found: 'This person could not be found, or they work in a branch you do not manage. Reload the list; if they are still there, ask an administrator who covers every branch.',
  rate_limited: 'Too many tries from here. Wait a few minutes and try again.',
  totp_required: 'Enter the current 6 digit code from your authenticator app.',
  invalid_mfa_code: 'That code is not right. Codes change every 30 seconds; type the next one.',
  password_required: 'Enter your own password to confirm.',
  invalid_password: 'That password is not right. Check it and try again; too many wrong tries lock your account for 15 minutes.',
  account_locked: 'Too many wrong tries. Your account is locked for 15 minutes. Try again later.',
  invalid_credentials: 'Your session could not be checked. Sign in again.',
};

/**
 * Proof that the signed-in admin is still themselves: the current code from their authenticator app when they have two-step sign-in
 * (`me.mfa_enabled`), otherwise their own current password. Send the one that applies; the server asks for the other kind in plain
 * words if it is the wrong one. Neither is ever logged or kept.
 */
export interface AdminCredential {
  totpCode?: string;
  password?: string;
}

function failure(error: unknown, status: number, fallback: string): SignInResetError {
  const problem = problemOf(error);
  const message = (problem.code && MESSAGES[problem.code]) || problem.detail || fallback;
  return new SignInResetError(message, status, problem.code);
}

/** The generated client types an answer with no error body as never; read it through the shape every answer has. */
function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    throw failure(result.error, result.response.status, fallback);
  }
  return result.data;
}

/**
 * Asks for a reset link for an email or phone. Resolves with the words to show, whether or not an account matched: the server
 * never says, and neither may the page.
 */
export async function requestPasswordReset(login: string): Promise<string> {
  const data = unwrap(await api.POST('/api/v1/auth/staff/password/forgot', { body: { login } }), 'The request could not be sent. Try again.');
  return data.message ?? 'If an account matches, a reset link has been sent.';
}

/**
 * Issues a one-time link for an active user (POST /api/v1/users/{id}/reset-sign-in, permission core.users.manage). The link is
 * returned once and never emailed (the user is emailed that one was issued, without the link): show it to the admin to hand over,
 * and do not keep it. Issuing another replaces it.
 *
 * `credential` is the code from the admin's own authenticator (`totpCode`) when `me.mfa_enabled`, otherwise the admin's own current
 * password (`password`; two-step sign-in is optional, so an admin without it is not locked out). The server answers 422 `totp_required`
 * or `password_required` when the one that applies is missing, 422 `invalid_mfa_code` for a wrong or already used code, 422
 * `invalid_password` for a wrong password (counted toward the account's sign-in lock: the fifth wrong one locks it for 15 minutes,
 * 423 `account_locked`). The call is refused, with a plain sentence in the error's message, for the admin's own account and for a
 * tenant admin (409: an administrator's sign-in is reset by the platform operator), for a user who has not accepted the invitation
 * yet (409: use the invitation link) and for a deactivated user (409). A refused target does not spend the code or count the password.
 */
export async function resetUserSignIn(userId: string, credential: AdminCredential): Promise<SignInResetLink> {
  const totp_code = credential.totpCode?.trim();
  const password = credential.password;
  if (!totp_code && !password) {
    throw new SignInResetError('Enter the code from your authenticator app, or your own password, to confirm.', 422, 'credential_required');
  }
  const data = unwrap(
    await api.POST('/api/v1/users/{user_id}/reset-sign-in', {
      params: { path: { user_id: userId } },
      body: totp_code ? { totp_code } : { password },
    }),
    'The link could not be made. Try again.',
  );
  if (!data.url || !data.expires_at) throw new SignInResetError('The link could not be made. Try again.', 200);
  return { url: data.url, expiresAt: data.expires_at };
}
