import { api, problemOf } from './client';
import type { components } from './schema';

// The approval rules client (issue #343, ADR-091): which actions of the business ask for a second person, and from what amount.
// Read with GET /settings/approvals; changed with PUT and the settings version in If-Match, always with the administrator's proof
// (the code from their authenticator, or their own password when they have none), whether the change tightens or loosens a rule.

export type ApprovalRule = components['schemas']['ApprovalRule'];
export type ApprovalRules = components['schemas']['ApprovalRules'];
export type RuleChange = components['schemas']['ApprovalRuleChange'];
export type Proof = { totp_code?: string; password?: string };

export class ApprovalRulesError extends Error {
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
  invalid_mfa_code: 'That code is not right. Codes change every 30 seconds; try the next one.',
  account_locked: 'Too many wrong tries. Wait 15 minutes and try again.',
  approval_always_on: 'This action always needs a second person. It cannot be switched off.',
  validation_failed: 'One of the amounts is not right. Use whole shillings, 0 or more.',
};

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    const problem = problemOf(result.error);
    throw new ApprovalRulesError((problem.code && MESSAGES[problem.code]) || problem.detail || fallback, result.response.status, problem.code);
  }
  return result.data;
}

export const approvalRules = {
  async get(): Promise<ApprovalRules> {
    return unwrap(await api.GET('/api/v1/settings/approvals'), 'Could not load the approval rules.');
  },

  async save(version: number, rules: RuleChange[], proof: Proof): Promise<ApprovalRules> {
    return unwrap(
      await api.PUT('/api/v1/settings/approvals', { params: { header: { 'If-Match': `"${version}"` } }, body: { rules, ...proof } }),
      'Could not save the approval rules.',
    );
  },
};
