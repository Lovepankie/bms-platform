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
