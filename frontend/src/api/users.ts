import { api, problemOf } from './client';
import type { components } from './schema';

// The staff users client (issue #319; chapter 7 section 7.11.4; FR-IAM-01, FR-IAM-02, FR-IAM-08). The generated types mark
// every field optional, as for the rest of the API; the screens read them through the small helpers in
// areas/staff/users/state.ts. The one-time invitation link is returned by the invite and the resend calls and is never put in a
// query cache: the screens hold it in their own state until the person leaves the page.

export type StaffUser = components['schemas']['User'];
export type StaffRole = components['schemas']['Role'];
export type RoleAssignment = components['schemas']['RoleAssignment'];
export type RoleAssignmentRequest = components['schemas']['RoleAssignmentRequest'];
export type InvitationLink = components['schemas']['InvitationLink'];
export type InvitedUser = components['schemas']['InvitedUser'];
export type InviteUserBody = components['schemas']['InviteUserRequest'];
export type StaffBranch = components['schemas']['Branch'];

export class UsersError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

/** The plan's refusal in plain words. The server names the limit as a number inside its sentence. */
export function planLimitWords(detail: string | undefined): string {
  const count = /at most (\d+)/.exec(detail ?? '')?.[1];
  const cap = count ? `Your plan allows at most ${count} staff users` : 'Your plan has reached its limit of staff users';
  return `${cap}, and that number is used up (people still waiting to join count). Deactivate someone you no longer need, or ask us to change your plan.`;
}

const MESSAGES: Record<string, string | undefined> = {
  last_tenant_admin: 'A business must always keep one active administrator. Make someone else an administrator first, then try again.',
  cannot_deactivate_self: 'You cannot deactivate your own account. Ask another administrator to do it.',
  duplicate_email: 'Someone on your staff already uses this email address.',
  duplicate_phone: 'Someone on your staff already uses this phone number.',
  invalid_phone: 'Use a Uganda mobile number such as 0712 345 678 or +256 712 345 678.',
  invalid_status_transition: 'This person has already joined, or was deactivated, so this is not possible now. Reload the page to see their status.',
  permission_denied: 'You are not allowed to do this. Ask an administrator.',
  // The Owner of the business (ADR-062).
  owner_only: 'Only the owner of the business can add, remove or change an administrator.',
  owner_protected: 'Nobody can remove, deactivate or change the owner of the business. The owner can hand ownership to another administrator.',
  owner_must_transfer_first: 'The owner stays an administrator. Hand ownership to another administrator first.',
  owner_must_be_active_admin: 'Ownership can only go to an active administrator. Make the person an administrator first.',
  already_owner: 'This person is already the owner.',
  no_owner: 'This business has no owner yet. Ask the platform operator to choose one.',
  owner_recently_changed: 'Your email, phone or password changed in the last 24 hours. For safety, ownership can be handed over a day after such a change.',
  owner_changed: 'The owner of the business changed at the same moment. Reload the page and try again.',
  step_up_required: 'Enter your password or authenticator code to change your email or phone.',
  totp_required: 'Enter the current 6 digit code from your authenticator app.',
  invalid_mfa_code: 'That code is not right. Codes change every 30 seconds; type the next one.',
  password_required: 'Enter your own password to confirm.',
  invalid_password: 'That password is not right. Check it and try again; too many wrong tries lock your account for 15 minutes.',
  account_locked: 'Too many wrong tries. Your account is locked for 15 minutes. Try again later.',
};

/** The words for a field problem the server sends with its own code (UserService.checkRoles and the contact check). */
const FIELD_MESSAGES: Record<string, string | undefined> = {
  unknown_branch: 'That branch is closed or is not one you may use. Pick another branch.',
  unknown_role: 'That role is not available. Pick another role.',
  invalid: 'An administrator works in every branch, so no branch can be chosen for that role.',
  required: 'Give a phone number or an email address.',
  invalid_phone: 'Use a Uganda mobile number such as 0712 345 678 or +256 712 345 678.',
};

/** A call that never got an answer: the phone lost its connection, or the answer was lost on the way back. */
export const NETWORK_WORDS =
  'The answer did not reach your phone, so we cannot tell if it worked. Open the staff list: if the person is there, tap Send a new link.';

function fail(error: unknown, status: number, fallback: string): never {
  const problem = problemOf(error);
  if (problem.code === 'plan_limit_reached') throw new UsersError(planLimitWords(problem.detail), status, problem.code);
  const entries = (problem.errors ?? []) as { field: string; message: string; code?: string }[];
  // `invalid` is also what Bean Validation says for any field; only the roles list's own problem means "no branch for the administrator".
  const fromField = entries.map((e) => (e.code === 'invalid' && e.field !== 'roles' ? undefined : e.code ? FIELD_MESSAGES[e.code] : undefined)).find((m) => m);
  const first = entries.map((e) => e.message).find((m) => m);
  const message = (problem.code && MESSAGES[problem.code]) || fromField || first || problem.detail || fallback;
  throw new UsersError(message, status, problem.code ?? entries[0]?.code);
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) fail(result.error, result.response.status, fallback);
  return result.data;
}

/** Runs one call; a fetch that throws (no connection, a dropped answer) becomes a plain-words error with the code `network`. */
async function sent<T extends { response: Response }>(call: Promise<T>): Promise<T> {
  try {
    return await call;
  } catch (e) {
    if (e instanceof UsersError) throw e;
    throw new UsersError(NETWORK_WORDS, 0, 'network');
  }
}

export const users = {
  /** Every staff user, whatever their status (the list is small: a business has a handful of staff). */
  async list(): Promise<StaffUser[]> {
    const all: StaffUser[] = [];
    let cursor: string | undefined;
    for (let page = 0; page < 20; page++) {
      const data = unwrap(await sent(api.GET('/api/v1/users', { params: { query: { limit: 100, cursor } } })), 'Could not load your staff.');
      all.push(...(data.items ?? []));
      cursor = data.next_cursor ?? undefined;
      if (!cursor) break;
    }
    return all;
  },

  async roles(): Promise<StaffRole[]> {
    return unwrap(await sent(api.GET('/api/v1/roles')), 'Could not load the roles.').items ?? [];
  },

  async branches(): Promise<StaffBranch[]> {
    return unwrap(await sent(api.GET('/api/v1/branches')), 'Could not load the branches.').items ?? [];
  },

  async invite(body: InviteUserBody): Promise<InvitedUser> {
    return unwrap(await sent(api.POST('/api/v1/users', { body })), 'Could not invite this person.');
  },

  async reissue(userId: string): Promise<InvitationLink> {
    return unwrap(await sent(api.POST('/api/v1/users/{user_id}/invitation', { params: { path: { user_id: userId } } })), 'Could not make a new link.');
  },

  async setRoles(userId: string, roles: RoleAssignmentRequest[]): Promise<StaffUser> {
    return unwrap(await sent(api.PUT('/api/v1/users/{user_id}/roles', { params: { path: { user_id: userId } }, body: roles })), 'Could not change the roles.');
  },

  /**
   * Hand ownership of the business to another active administrator (ADR-062). The Owner proves it is still them: the code from
   * their authenticator app when they have two-step sign-in, otherwise their own password. Neither is kept.
   */
  async transferOwnership(userId: string, credential: { totpCode?: string; password?: string }): Promise<StaffUser> {
    const body = credential.totpCode ? { totp_code: credential.totpCode } : { password: credential.password };
    return unwrap(
      await sent(api.POST('/api/v1/users/{user_id}/transfer-ownership', { params: { path: { user_id: userId } }, body })),
      'Could not hand over ownership.',
    );
  },

  async deactivate(userId: string): Promise<StaffUser> {
    return unwrap(await sent(api.POST('/api/v1/users/{user_id}/deactivate', { params: { path: { user_id: userId } } })), 'Could not deactivate this person.');
  },
};
