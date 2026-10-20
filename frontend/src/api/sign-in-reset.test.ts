import { describe, expect, it, vi } from 'vitest';

// The reset clients against a fetch that answers as the server does (FR-IAM-15). openapi-fetch keeps the fetch it finds when the client
// is created, so the stand-in is put in place before anything is imported.

const fetchImpl = vi.hoisted(() => {
  const state: { next: (request: Request) => Promise<Response>; seen: Request[] } = {
    next: async () => new Response('{}', { status: 200 }),
    seen: [],
  };
  const Base = globalThis.Request;
  globalThis.Request = class extends Base {
    constructor(input: RequestInfo | URL, init?: RequestInit) {
      super(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init);
    }
  } as typeof Request;
  globalThis.fetch = ((input: Request | string, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://localhost'), init);
    state.seen.push(request);
    return state.next(request);
  }) as typeof fetch;
  return state;
});

import { requestPasswordReset, resetUserSignIn, SignInResetError } from './sign-in-reset';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const USER = '00000000-0000-4000-8000-0000000000a1';

describe('resetUserSignIn (the future Users screen button, #339, calls this)', () => {
  it('posts to the user and returns the link and its end, camel cased', async () => {
    fetchImpl.seen.length = 0;
    fetchImpl.next = async () => json(200, { url: 'https://test-bms.example.test/reset-password#token=fabricated', expires_at: '2026-10-19T13:00:00Z' });
    const link = await resetUserSignIn(USER, { totpCode: '123456' });
    expect(link).toEqual({ url: 'https://test-bms.example.test/reset-password#token=fabricated', expiresAt: '2026-10-19T13:00:00Z' });
    const request = fetchImpl.seen.at(-1)!;
    expect(request.method).toBe('POST');
    expect(new URL(request.url).pathname).toBe(`/api/v1/users/${USER}/reset-sign-in`);
  });

  it('sends the code of the admin in the body', async () => {
    fetchImpl.next = async () => json(200, { url: 'https://test-bms.example.test/reset-password#token=fabricated', expires_at: '2026-10-19T13:00:00Z' });
    await resetUserSignIn(USER, { totpCode: '123456' });
    expect(await fetchImpl.seen.at(-1)!.clone().json()).toEqual({ totp_code: '123456' });
  });

  it('sends the password, and only the password, for an admin with no authenticator', async () => {
    fetchImpl.next = async () => json(200, { url: 'https://test-bms.example.test/reset-password#token=fabricated', expires_at: '2026-10-19T13:00:00Z' });
    await resetUserSignIn(USER, { password: 'Fabricated-Pass-2026' });
    expect(await fetchImpl.seen.at(-1)!.clone().json()).toEqual({ password: 'Fabricated-Pass-2026' });
  });

  it('prefers the code when both are given, so a password is not sent needlessly', async () => {
    fetchImpl.next = async () => json(200, { url: 'https://test-bms.example.test/reset-password#token=fabricated', expires_at: '2026-10-19T13:00:00Z' });
    await resetUserSignIn(USER, { totpCode: '123456', password: 'Fabricated-Pass-2026' });
    expect(await fetchImpl.seen.at(-1)!.clone().json()).toEqual({ totp_code: '123456' });
  });

  it('asks for a credential without calling the server when there is none', async () => {
    const before = fetchImpl.seen.length;
    const error = (await resetUserSignIn(USER, {}).catch((e: unknown) => e)) as SignInResetError;
    expect(error).toBeInstanceOf(SignInResetError);
    expect(error.message).toMatch(/authenticator app, or your own password/);
    expect(fetchImpl.seen.length).toBe(before);
  });

  it('says in plain words that the password is wrong, or that the account is locked, without echoing the password', async () => {
    fetchImpl.next = async () => json(422, { code: 'invalid_password', detail: 'The password is not correct.' });
    const wrong = (await resetUserSignIn(USER, { password: 'Not-The-Password-1' }).catch((e: unknown) => e)) as SignInResetError;
    expect(wrong.status).toBe(422);
    expect(wrong.message).toMatch(/password is not right/);
    expect(wrong.message).not.toContain('Not-The-Password-1');
    fetchImpl.next = async () => json(423, { code: 'account_locked', detail: 'Too many failed attempts.' });
    const locked = (await resetUserSignIn(USER, { password: 'Fabricated-Pass-2026' }).catch((e: unknown) => e)) as SignInResetError;
    expect(locked.status).toBe(423);
    expect(locked.message).toMatch(/locked for 15 minutes/);
  });

  it('says in plain words that the password is needed when the admin has no authenticator and sent a code', async () => {
    fetchImpl.next = async () => json(422, { code: 'password_required', detail: 'Enter your own password to confirm.' });
    const error = (await resetUserSignIn(USER, { totpCode: '123456' }).catch((e: unknown) => e)) as SignInResetError;
    expect(error.code).toBe('password_required');
    expect(error.message).toMatch(/your own password/);
  });

  it('says in plain words that a code is needed, and when it is wrong', async () => {
    fetchImpl.next = async () => json(422, { code: 'totp_required', detail: 'Enter the current code from your authenticator app.' });
    const missing = (await resetUserSignIn(USER, { totpCode: '123456' }).catch((e: unknown) => e)) as SignInResetError;
    expect(missing.status).toBe(422);
    expect(missing.code).toBe('totp_required');
    expect(missing.message).toMatch(/6 digit code/);
    fetchImpl.next = async () => json(422, { code: 'invalid_mfa_code', detail: 'The code is not valid.' });
    const wrong = (await resetUserSignIn(USER, { totpCode: '000000' }).catch((e: unknown) => e)) as SignInResetError;
    expect(wrong.message).toMatch(/not right/);
  });

  it('carries the server sentence of a refusal for oneself or a tenant admin', async () => {
    fetchImpl.next = async () =>
      json(409, { code: 'admin_reset_by_operator', detail: "An administrator's sign-in is reset by the platform operator. Ask them." });
    const error = (await resetUserSignIn(USER, { totpCode: '123456' }).catch((e: unknown) => e)) as SignInResetError;
    expect(error.status).toBe(409);
    expect(error.code).toBe('admin_reset_by_operator');
    expect(error.message).toContain('platform operator');
  });

  it('carries the server sentence of a refusal, with its status and code', async () => {
    fetchImpl.next = async () =>
      json(409, { code: 'invalid_status_transition', detail: 'This user has not accepted the invitation yet; send the invitation link again.' });
    const error = await resetUserSignIn(USER, { totpCode: '123456' }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(SignInResetError);
    expect((error as SignInResetError).status).toBe(409);
    expect((error as SignInResetError).code).toBe('invalid_status_transition');
    expect((error as SignInResetError).message).toContain('invitation');
  });

  it('says in its own words that only an administrator may', async () => {
    fetchImpl.next = async () => json(403, { code: 'permission_denied', detail: 'You do not have permission to perform this action.' });
    const error = (await resetUserSignIn(USER, { totpCode: '123456' }).catch((e: unknown) => e)) as SignInResetError;
    expect(error.status).toBe(403);
    expect(error.message).toMatch(/administrator/);
  });
});

describe('requestPasswordReset', () => {
  it('posts the login and returns the server words', async () => {
    fetchImpl.next = async () => json(202, { message: 'If an account matches, a reset link has been sent.' });
    expect(await requestPasswordReset('test01@example.test')).toBe('If an account matches, a reset link has been sent.');
    const request = fetchImpl.seen.at(-1)!;
    expect(new URL(request.url).pathname).toBe('/api/v1/auth/staff/password/forgot');
    expect(await request.clone().json()).toEqual({ login: 'test01@example.test' });
  });

  it('turns a 429 into plain words', async () => {
    fetchImpl.next = async () => json(429, { code: 'rate_limited', detail: 'Too many attempts.' });
    const error = (await requestPasswordReset('x').catch((e: unknown) => e)) as SignInResetError;
    expect(error.status).toBe(429);
    expect(error.message).toMatch(/Wait a few minutes/);
  });
});
