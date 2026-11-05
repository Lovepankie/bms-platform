import { afterEach, describe, expect, it, vi } from 'vitest';
import { getAccessToken, refreshSession, setAccessToken, signedInUser } from './session';

// #112 item 7: a reload starts with no access token in memory; the staff layout calls refreshSession,
// which must sign the user back in from the HttpOnly refresh cookie while that is valid (used at least
// once every 30 days, at most 90 days from sign-in, ADR-014 as amended by #647), and send them to sign-in
// only when the server refuses it.

afterEach(() => {
  vi.unstubAllGlobals();
  setAccessToken(null);
});

const answer = (status: number, body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));

describe('session restore on reload', () => {
  it('restores the signed-in user from a valid refresh cookie with one call', async () => {
    const fetch = vi.fn(() => answer(200, { access_token: 'test-access-token-1' }));
    vi.stubGlobal('fetch', fetch);
    expect(getAccessToken()).toBeNull();
    expect(await refreshSession()).toBe(true);
    expect(getAccessToken()).toBe('test-access-token-1');
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith('/api/v1/auth/refresh', expect.objectContaining({ method: 'POST', credentials: 'same-origin' }));
  });

  it('shares one rotation between concurrent callers, so a reload never looks like token reuse', async () => {
    const fetch = vi.fn(() => answer(200, { access_token: 'test-access-token-2' }));
    vi.stubGlobal('fetch', fetch);
    const results = await Promise.all([refreshSession(), refreshSession(), refreshSession()]);
    expect(results).toEqual([true, true, true]);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('signs out when the refresh credential is expired or revoked', async () => {
    setAccessToken('stale');
    vi.stubGlobal('fetch', vi.fn(() => answer(401, { code: 'unauthenticated' })));
    expect(await refreshSession()).toBe(false);
    expect(getAccessToken()).toBeNull();
  });

  it('treats a network failure as not signed in, without throwing', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('offline'))));
    expect(await refreshSession()).toBe(false);
  });
});

describe('whose sign-in a request goes out under (issue #852)', () => {
  it("reads the token's person, and null with no token or one it cannot read", () => {
    const part = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
    setAccessToken(`${part({ alg: 'none' })}.${part({ sub: '00000000-0000-4000-8000-0000000000a1', knd: 'staff' })}.sig`);
    expect(signedInUser()).toBe('00000000-0000-4000-8000-0000000000a1');
    setAccessToken('not-a-token');
    expect(signedInUser()).toBeNull();
    setAccessToken(null);
    expect(signedInUser()).toBeNull();
  });
});
