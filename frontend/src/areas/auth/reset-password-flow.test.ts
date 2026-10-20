import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The reset page's token handling and submit flow (FR-IAM-15, review of #322): the token leaves the address bar the moment it is read, is
// kept in memory only, and is forgotten once the server accepted it. openapi-fetch keeps the fetch it finds when the client is created,
// so the stand-in is put in place before anything is imported. The token, key and codes are fabricated.

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

import { problemOf } from '../../api/client';
import { plainProblem } from '../../auth/codes';
import { getAccessToken, setAccessToken } from '../../auth/session';
import { forgetResetToken, submitNewPassword, takeResetToken } from './reset-password';

const TOKEN = 'abcdefghijklmnopqrstuvwxyz0123456789';
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const replaceState = vi.fn();

function openAddress(hash: string) {
  vi.stubGlobal('window', { location: { hash, pathname: '/reset-password' }, history: { replaceState } });
}

beforeEach(() => {
  forgetResetToken();
  setAccessToken(null);
  replaceState.mockClear();
  fetchImpl.seen.length = 0;
});
afterEach(() => vi.unstubAllGlobals());

describe('the token in the address', () => {
  it('is taken out of the address bar and the history the moment it is read', () => {
    openAddress(`#token=${TOKEN}`);
    expect(takeResetToken()).toBe(TOKEN);
    expect(replaceState).toHaveBeenCalledTimes(1);
    expect(replaceState).toHaveBeenCalledWith(null, '', '/reset-password');
  });

  it('is still there on a second read after the address was cleared, as when React runs an initialiser twice', () => {
    openAddress(`#token=${TOKEN}`);
    takeResetToken();
    openAddress('');
    expect(takeResetToken()).toBe(TOKEN);
  });

  it('is not invented from a short or missing fragment, and nothing is rewritten', () => {
    openAddress('#token=short');
    expect(takeResetToken()).toBeNull();
    openAddress('');
    expect(takeResetToken()).toBeNull();
    expect(replaceState).not.toHaveBeenCalled();
  });

  it('is not kept in browser storage', () => {
    const storage = { setItem: vi.fn(), getItem: vi.fn() };
    vi.stubGlobal('localStorage', storage);
    vi.stubGlobal('sessionStorage', storage);
    openAddress(`#token=${TOKEN}`);
    takeResetToken();
    expect(storage.setItem).not.toHaveBeenCalled();
  });
});

describe('saving the new password', () => {
  it('posts the token and the password, signs in, and forgets the token', async () => {
    openAddress(`#token=${TOKEN}`);
    takeResetToken();
    fetchImpl.next = async () => json(200, { status: 'signed_in', access_token: 'fabricated-access-token' });

    const next = await submitNewPassword(TOKEN, 'Fabricated-Pass-2026');

    expect(next).toEqual({ kind: 'home' });
    expect(getAccessToken()).toBe('fabricated-access-token');
    const request = fetchImpl.seen.at(-1)!;
    expect(request.method).toBe('POST');
    expect(new URL(request.url).pathname).toBe('/api/v1/auth/staff/password/reset');
    expect(await request.clone().json()).toEqual({ token: TOKEN, password: 'Fabricated-Pass-2026' });
    openAddress('');
    expect(takeResetToken()).toBeNull();
  });

  it('asks for the code of a user with two-step sign-in and signs nobody in', async () => {
    fetchImpl.next = async () => json(200, { status: 'mfa_required', mfa_required: true, mfa_token: 'fabricated-mfa-token' });
    const next = await submitNewPassword(TOKEN, 'Fabricated-Pass-2026');
    expect(next).toEqual({ kind: 'challenge', mfaToken: 'fabricated-mfa-token' });
    expect(getAccessToken()).toBeNull();
  });

  it('sets up two-step sign-in for a role that needs it, with no way past it', async () => {
    const enrolment = { secret: 'JBSWY3DPEHPK3PXP', otpauth_uri: 'otpauth://totp/BMS:test01%40example.test?secret=JBSWY3DPEHPK3PXP' };
    fetchImpl.next = async (request) =>
      new URL(request.url).pathname.endsWith('/mfa/enrol')
        ? json(200, enrolment)
        : json(200, { status: 'mfa_enrolment_required', mfa_enrolment_required: true, mfa_token: 'fabricated-mfa-token' });
    const next = await submitNewPassword(TOKEN, 'Fabricated-Pass-2026');
    expect(next).toEqual({ kind: 'enrol', enrolment, mfaToken: 'fabricated-mfa-token' });
    expect(getAccessToken()).toBeNull();
    expect(await fetchImpl.seen.at(-1)!.clone().json()).toEqual({ mfa_token: 'fabricated-mfa-token' });
  });

  it('sends a locked account to sign-in with no session', async () => {
    fetchImpl.next = async () => json(200, { status: 'sign_in_required' });
    expect(await submitNewPassword(TOKEN, 'Fabricated-Pass-2026')).toEqual({ kind: 'sign-in' });
    expect(getAccessToken()).toBeNull();
  });

  it('keeps the token when the server refuses the password, and says why in plain words', async () => {
    openAddress(`#token=${TOKEN}`);
    takeResetToken();
    fetchImpl.next = async () => json(422, { code: 'weak_password', detail: 'This password is too common.' });
    const error = await submitNewPassword(TOKEN, 'password123').catch((e: unknown) => e);
    expect(plainProblem(problemOf(error), 'x')).toBe('This password is too common.');
    openAddress('');
    expect(takeResetToken()).toBe(TOKEN);
  });

  it('says a used or replaced link is no longer good', async () => {
    fetchImpl.next = async () => json(422, { code: 'reset_link_invalid', detail: 'The reset link is not valid; ask for a new one.' });
    const error = await submitNewPassword(TOKEN, 'Fabricated-Pass-2026').catch((e: unknown) => e);
    expect(plainProblem(problemOf(error), 'x')).toContain('Ask for a new one');
  });
});
