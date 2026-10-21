import { describe, expect, it, vi } from 'vitest';

// The find-sign-in client against a fetch that answers as the server does (issue #383). openapi-fetch keeps the fetch it finds when the
// client is created, so the stand-in is put in place before anything is imported.

const fetchImpl = vi.hoisted(() => {
  const state: { next: (request: Request) => Promise<Response>; seen: Request[] } = {
    next: async () => new Response('{}', { status: 202 }),
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

import { FindSignInError, findSignInPage } from './find-sign-in';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

describe('findSignInPage', () => {
  it('posts the email to the platform route and returns the server words', async () => {
    fetchImpl.next = async () => json(202, { message: 'If this email is used by staff of a business, we have sent it.' });
    const words = await findSignInPage('cashier01@example.test');
    expect(words).toBe('If this email is used by staff of a business, we have sent it.');
    const request = fetchImpl.seen.at(-1)!;
    expect(request.method).toBe('POST');
    expect(new URL(request.url).pathname).toBe('/api/v1/platform/find-sign-in');
    expect(await request.clone().json()).toEqual({ email: 'cashier01@example.test' });
  });

  it('says to wait when there were too many tries', async () => {
    fetchImpl.next = async () => json(429, { code: 'rate_limited', detail: 'Too many attempts.' });
    const failure = await findSignInPage('cashier01@example.test').catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(FindSignInError);
    expect((failure as FindSignInError).status).toBe(429);
    expect((failure as FindSignInError).message).toContain('Wait a few minutes');
  });
});
