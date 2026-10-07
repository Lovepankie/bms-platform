// Pure helpers for the sign-in screens; unit tested in codes.test.ts.

/** The second factor as typed: a 6 digit TOTP code or a recovery code (FR-IAM-06, FR-IAM-11). */
export function normaliseSecondFactor(input: string): { kind: 'totp' | 'recovery' | 'invalid'; code: string } {
  const compact = input.replace(/\s+/g, '');
  if (/^\d{6}$/.test(compact)) return { kind: 'totp', code: compact };
  const recovery = compact.replace(/-/g, '').toUpperCase();
  if (/^[A-Z0-9]{10}$/.test(recovery)) return { kind: 'recovery', code: `${recovery.slice(0, 5)}-${recovery.slice(5)}` };
  return { kind: 'invalid', code: compact };
}

/** The invitation token from the link's fragment, which never reaches a server log. */
export function invitationToken(hash: string): string | null {
  const params = new URLSearchParams(hash.startsWith('#') ? hash.slice(1) : hash);
  const token = params.get('token');
  return token && token.length >= 20 ? token : null;
}

/** Client-side echo of the server's rule (FR-IAM-04); the server decides. */
export function passwordProblem(password: string, confirm: string): string | null {
  if (password.length < 10) return 'Use at least 10 characters.';
  if (password.length > 128) return 'Use at most 128 characters.';
  if (password !== confirm) return 'The two passwords differ.';
  return null;
}

/** Recovery codes as a text block the user can copy or print once. */
export function recoveryCodesText(codes: readonly string[]): string {
  return codes.map((c, i) => `${String(i + 1).padStart(2, ' ')}. ${c}`).join('\n');
}

/** The password rules as a live checklist: only what the server checks (FR-IAM-04). */
export function passwordChecks(password: string, confirm: string): { label: string; ok: boolean }[] {
  return [
    { label: 'At least 10 characters', ok: password.length >= 10 },
    { label: 'No more than 128 characters', ok: password.length > 0 && password.length <= 128 },
    { label: 'Both passwords match', ok: password.length > 0 && password === confirm },
  ];
}

// The server's problem codes (chapter 7 section 7.7) as plain sentences for the sign-in family.
// A code not listed here falls back to the server's own detail, which is already plain English.
const PLAIN: Record<string, string> = {
  invalid_credentials: 'That email, phone or password is not right. Check them and try again.',
  account_locked: 'Too many tries. Your account is locked for 15 minutes. Try again later.',
  invalid_mfa_code: 'That code is not right. Codes change every 30 seconds; try the next one.',
  mfa_token_invalid: 'This step took too long. Start again from the sign-in page.',
  mfa_already_enrolled: 'Two-step sign-in is already on for your account.',
  mfa_enrolment_not_started: 'Start again: open the set-up step once more to get a new code.',
  invitation_invalid: 'This invitation link has been used or replaced. Ask your administrator for a new one.',
  invitation_expired: 'This invitation link has expired. Ask your administrator for a new one.',
};

/** A plain sentence for a failed sign-in step; never echoes a token, code or password. */
export function plainProblem(problem: { code?: string; detail?: string } | null | undefined, fallback: string): string {
  if (problem?.code && PLAIN[problem.code]) return PLAIN[problem.code] as string;
  return problem?.detail ?? fallback;
}

/** The set-up key in groups of four, easier to read aloud and type. */
export function groupSecret(secret: string): string {
  return (secret.replace(/\s+/g, '').match(/.{1,4}/g) ?? []).join(' ');
}

/** Only digits, at most six: what the code field accepts as the user types. */
export function sixDigits(input: string): string {
  return input.replace(/\D/g, '').slice(0, 6);
}
