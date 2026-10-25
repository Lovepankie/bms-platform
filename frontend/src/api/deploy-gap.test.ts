import { afterEach, describe, expect, it, vi } from 'vitest';

// The API client while a deploy switches the server over (issue #496): a 502, 503 or 504 from the proxy, or a
// dropped connection while online, reads as plain words, and a request with an Idempotency-Key is sent once more by
// itself. openapi-fetch keeps the fetch it finds when the client is created, so the stand-in is put in place before
// anything is imported. Every value is fabricated.

const fetchImpl = vi.hoisted(() => {
  const state: { answers: ((request: Request) => Promise<Response>)[]; seen: Request[] } = { answers: [], seen: [] };
  const Base = globalThis.Request;
  globalThis.Request = class extends Base {
    constructor(input: RequestInfo | URL, init?: RequestInit) {
      super(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init);
    }
  } as typeof Request;
  globalThis.fetch = ((input: Request | string, init?: RequestInit) => {
    // A clone is a plain Request, not the subclass above, so the check is against the base class.
    const request = input instanceof Base ? input : new Request(new URL(String(input), 'http://localhost'), init);
    state.seen.push(request);
    const next = state.answers.shift();
    return next ? next(request) : Promise.resolve(new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }));
  }) as typeof fetch;
  return state;
});

import { APP_UPDATING, APP_UPDATING_CHECK, APP_UPDATING_MAYBE_SAVED, api, problemOf, updatingRetry } from './client';
import { FormError, asFormError, unknownOutcome } from './lending-forms';
import { realMembers } from './members';
import { retailMessage } from './retail-errors';

updatingRetry.delayMs = 0;

const json = (status: number, body: unknown, type = 'application/json') =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': type } });
// What Caddy answers when it could not connect to any container (deploy/caddy/upstreams.caddy, not_delivered).
const gap = (status = 502) => async () =>
  new Response('{"code":"app_updating","detail":"The app is updating. Nothing was saved yet. Try again in a minute."}', {
    status,
    headers: { 'content-type': 'application/problem+json', 'Bms-Not-Delivered': '1' },
  });
// What Caddy answers when a container took the request and then reset or was stopped (EOF): it may have been saved.
const reset = (status = 502) => async () => new Response(null, { status });
const dropped = async (): Promise<Response> => {
  throw new TypeError('Failed to fetch');
};
const move = (key?: string) =>
  api.POST('/api/v1/retail/transfers', {
    body: { from_branch_id: 'b1', to_branch_id: 'b2', transfer_date: '2026-10-23', lines: [{ product_id: 'p1', qty: '1' }] },
    params: { header: { 'Idempotency-Key': key as string } },
  });

afterEach(() => {
  fetchImpl.answers = [];
  fetchImpl.seen = [];
  vi.unstubAllGlobals();
});

describe('a 502, 503 or 504 from the proxy that proves nothing reached the API (#496)', () => {
  it.each([502, 503, 504])('reads %i as the app updating, and a read without a key is not sent again', async (status) => {
    fetchImpl.answers = [gap(status)];
    const { error, response } = await api.GET('/api/v1/me');
    expect(response.status).toBe(status);
    expect(problemOf(error).detail).toBe(APP_UPDATING);
    expect(fetchImpl.seen).toHaveLength(1);
  });

  it('says nothing was saved for a write without a key, which is not sent again', async () => {
    fetchImpl.answers = [gap(502)];
    const { error } = await move();
    expect(problemOf(error).detail).toBe(APP_UPDATING);
    expect(fetchImpl.seen).toHaveLength(1);
  });

  it('sends a write with an Idempotency-Key once more, with the same key and body, and returns what that answers', async () => {
    fetchImpl.answers = [gap(502), async () => json(201, { id: 'tr1', status: 'completed' })];
    const { data, error } = await move('test-key-496');
    expect(error).toBeUndefined();
    expect(data).toMatchObject({ id: 'tr1' });
    expect(fetchImpl.seen).toHaveLength(2);
    const [first, second] = fetchImpl.seen as [Request, Request];
    expect(second.headers.get('Idempotency-Key')).toBe('test-key-496');
    expect(second.method).toBe('POST');
    expect(await second.clone().json()).toEqual({ from_branch_id: 'b1', to_branch_id: 'b2', transfer_date: '2026-10-23', lines: [{ product_id: 'p1', qty: '1' }] });
    expect(new URL(first.url).pathname).toBe(new URL(second.url).pathname);
  });

  it('retries only once: a second proof that nothing arrived still says nothing was saved', async () => {
    fetchImpl.answers = [gap(503), gap(502)];
    const { error, response } = await move('test-key-496');
    expect(response.status).toBe(503);
    expect(problemOf(error).detail).toBe(APP_UPDATING);
    expect(fetchImpl.seen).toHaveLength(2);
  });

  it('waits before the retry', async () => {
    updatingRetry.delayMs = 60;
    try {
      fetchImpl.answers = [gap(502), async () => json(201, { id: 'tr1' })];
      const started = Date.now();
      await move('test-key-496');
      expect(Date.now() - started).toBeGreaterThanOrEqual(50);
    } finally {
      updatingRetry.delayMs = 0;
    }
  });

  it('leaves the API\'s own 503 problem alone and does not send it again', async () => {
    fetchImpl.answers = [async () => json(503, { code: 'sms_unavailable', detail: 'Texts are switched off.' }, 'application/problem+json')];
    const { error } = await move('test-key-496');
    expect(problemOf(error)).toEqual({ code: 'sms_unavailable', detail: 'Texts are switched off.' });
    expect(fetchImpl.seen).toHaveLength(1);
  });

  it('reads a 409 idempotency_in_progress on the retry as maybe saved: the first attempt did arrive', async () => {
    fetchImpl.answers = [gap(502), async () => json(409, { code: 'idempotency_in_progress', detail: 'In progress.' }, 'application/problem+json')];
    const { error, response } = await move('test-key-496');
    expect(response.status).toBe(409);
    expect(problemOf(error).detail).toBe(APP_UPDATING_MAYBE_SAVED);
  });

  it('leaves other errors alone', async () => {
    fetchImpl.answers = [async () => new Response(null, { status: 500 })];
    const { error, response } = await move('test-key-496');
    expect(response.status).toBe(500);
    expect(problemOf(error).detail).toBeUndefined();
    expect(fetchImpl.seen).toHaveLength(1);
  });
});

// Review of #556: Caddy gives the same bare 502 when a container took the request and then reset (or was stopped at the
// end of its grace period), and Cloudflare's 504 is an origin timeout; the request may have been saved.
describe('a bare 502, 503 or 504 that does not prove nothing arrived (#496)', () => {
  it('says a write without a key may have been saved and to check first, and does not send it again', async () => {
    fetchImpl.answers = [reset(502)];
    const failure = await realMembers.update('m1', 1, { occupation: 'Test trade' }).catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(FormError);
    expect((failure as FormError).message).toBe(APP_UPDATING_CHECK);
    expect(unknownOutcome(failure)).toBe(true);
    expect(fetchImpl.seen).toHaveLength(1);
  });

  it('reads a Cloudflare 504 page the same way for a write', async () => {
    fetchImpl.answers = [async () => new Response('<html>Gateway time-out</html>', { status: 504, headers: { 'content-type': 'text/html' } })];
    const { error } = await move();
    expect(problemOf(error).detail).toBe(APP_UPDATING_CHECK);
  });

  it('sends a write with a key once more; if that is refused, it may still have been saved by the first', async () => {
    fetchImpl.answers = [reset(502), gap(502)];
    const { error } = await move('test-key-496');
    expect(problemOf(error).detail).toBe(APP_UPDATING_MAYBE_SAVED);
    expect(fetchImpl.seen).toHaveLength(2);
  });

  it('a keyed write refused first and reset on the retry may have been saved by the retry', async () => {
    fetchImpl.answers = [gap(502), reset(502)];
    const { error } = await move('test-key-496');
    expect(problemOf(error).detail).toBe(APP_UPDATING_MAYBE_SAVED);
  });

  it('a keyed write that resets and then succeeds returns the answer (the server replays it)', async () => {
    fetchImpl.answers = [reset(502), async () => json(200, { id: 'tr1' })];
    const { data } = await move('test-key-496');
    expect(data).toMatchObject({ id: 'tr1' });
  });

  it('a read says nothing was saved, since it saves nothing', async () => {
    fetchImpl.answers = [async () => new Response('<html>Bad gateway</html>', { status: 502, headers: { 'content-type': 'text/html' } })];
    const { error } = await api.GET('/api/v1/me');
    expect(problemOf(error)).toEqual({ code: 'app_updating', detail: APP_UPDATING });
  });
});

describe('a dropped connection (#496)', () => {
  it('while online, sends a write with a key once more and returns what that answers', async () => {
    fetchImpl.answers = [dropped, async () => json(201, { id: 'tr1' })];
    const { data } = await move('test-key-496');
    expect(data).toMatchObject({ id: 'tr1' });
    expect(fetchImpl.seen).toHaveLength(2);
  });

  it('while online, without a key, throws a TypeError in the updating words, and is not sent again', async () => {
    fetchImpl.answers = [dropped];
    const failure = await api.GET('/api/v1/me').catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(TypeError);
    expect(problemOf(failure).detail).toBe(APP_UPDATING);
    expect(asFormError(failure).message).toBe(APP_UPDATING);
    expect(asFormError(failure).status).toBe(0);
    expect(fetchImpl.seen).toHaveLength(1);
  });

  // A dropped connection may have been saved (the answer was lost, or the old api's stop ran out of grace), so a write
  // never says "Nothing was saved yet" after one (review of #556).
  it('while online, a write with a key whose retry also drops says it may have been saved and a retry is safe', async () => {
    fetchImpl.answers = [dropped, dropped];
    const failure = await move('test-key-496').catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(TypeError);
    expect((failure as Error).message).toBe(APP_UPDATING_MAYBE_SAVED);
    expect(problemOf(failure)).toEqual({ code: 'app_updating', detail: APP_UPDATING_MAYBE_SAVED });
    expect(asFormError(failure).message).toBe(APP_UPDATING_MAYBE_SAVED);
    expect(unknownOutcome(asFormError(failure))).toBe(true);
    expect(fetchImpl.seen).toHaveLength(2);
  });

  it('while online, a dropped write whose retry meets a gap says it may have been saved', async () => {
    fetchImpl.answers = [dropped, gap(502)];
    const { error } = await move('test-key-496');
    expect(problemOf(error).detail).toBe(APP_UPDATING_MAYBE_SAVED);
    expect(fetchImpl.seen).toHaveLength(2);
  });

  it('a write refused by the proxy whose retry drops says it may have been saved', async () => {
    fetchImpl.answers = [gap(502), dropped];
    const { error } = await move('test-key-496');
    expect(problemOf(error).detail).toBe(APP_UPDATING_MAYBE_SAVED);
    expect(fetchImpl.seen).toHaveLength(2);
  });

  it('while online, a write without a key stays the TypeError the screens read as an unknown outcome, and is not sent again', async () => {
    fetchImpl.answers = [dropped];
    const failure = await move().catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(TypeError);
    expect((failure as Error).message).toBe('Failed to fetch');
    expect(asFormError(failure).message).toContain('The connection dropped');
    expect(unknownOutcome(asFormError(failure))).toBe(true);
    expect(fetchImpl.seen).toHaveLength(1);
  });

  it('while online, a sign-in, which saves nothing, says the app is updating', async () => {
    fetchImpl.answers = [dropped];
    const failure = await api.POST('/api/v1/auth/staff/login', { body: { login: 'staff01@example.test', password: 'test-password-01' } }).catch((e: unknown) => e);
    expect(problemOf(failure).detail).toBe(APP_UPDATING);
    expect(fetchImpl.seen).toHaveLength(1);
  });

  it('offline, stays the plain TypeError the offline paths expect, and is not sent again', async () => {
    vi.stubGlobal('navigator', { onLine: false });
    fetchImpl.answers = [dropped];
    const failure = await move('test-key-496').catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(TypeError);
    expect((failure as Error).message).toBe('Failed to fetch');
    expect(asFormError(failure).message).toContain('The connection dropped');
    expect(fetchImpl.seen).toHaveLength(1);
  });
});

describe('the words', () => {
  it('are what the retail and lending screens show for a bare 502, 503 or 504 with no body: maybe saved, check first', () => {
    for (const status of [502, 503, 504]) expect(retailMessage(undefined, status)).toBe(APP_UPDATING_CHECK);
    expect(retailMessage(undefined, 500)).toBe('The server had a problem. Try again in a moment.');
    expect(retailMessage({ code: 'app_updating', detail: APP_UPDATING }, 502)).toBe(APP_UPDATING);
  });

  it('say the app is updating, that nothing was saved, and when to try again', () => {
    expect(APP_UPDATING).toBe('The app is updating. Nothing was saved yet. Try again in a minute.');
  });
});
