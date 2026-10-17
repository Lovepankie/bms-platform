import { describe, expect, it, vi } from 'vitest';

// The real clients against a fetch that fails or answers badly (issue #280). openapi-fetch keeps the fetch it finds when the client is
// created, so the stand-in is put in place before anything is imported. A dropped connection must reach the screen as a FormError with
// status 0 (an unknown outcome), never as a raw TypeError the screen would show as "Something went wrong" and let be saved twice.

const fetchImpl = vi.hoisted(() => {
  const state: { next: (request: Request) => Promise<Response> } = { next: async () => new Response('{}', { status: 200 }) };
  // The client builds a Request from a relative address, which Node cannot; give it a base.
  const Base = globalThis.Request;
  globalThis.Request = class extends Base {
    constructor(input: RequestInfo | URL, init?: RequestInit) {
      super(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init);
    }
  } as typeof Request;
  globalThis.fetch = ((input: Request | string, init?: RequestInit) => state.next(input instanceof Request ? input : new Request(new URL(String(input), 'http://localhost'), init))) as typeof fetch;
  return state;
});

import { FormError, unknownOutcome } from './lending-forms';
import { realCollateral } from './collateral';
import { realMembers } from './members';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/problem+json' } });
const BRANCH = '00000000-0000-4000-8000-0000000000b1';
const member = { branch_id: BRANCH, full_name: 'Test Borrower 01', phone: '0700000001', id_type: 'none' };
const item = { member_id: BRANCH, collateral_type: 'other', description: 'Test item' };

async function failure(call: () => Promise<unknown>): Promise<FormError> {
  try {
    await call();
  } catch (e) {
    expect(e).toBeInstanceOf(FormError);
    return e as FormError;
  }
  throw new Error('the call did not fail');
}

describe('a connection that drops', () => {
  it('reaches the screen as an unknown outcome on every create and lookup, never a raw TypeError', async () => {
    fetchImpl.next = async () => {
      throw new TypeError('Failed to fetch');
    };
    for (const call of [
      () => realMembers.create(member, 'key-1'),
      () => realMembers.update('m1', 1, { occupation: 'x' }),
      () => realMembers.duplicateCheck({ phone: '+256700000001' }),
      () => realMembers.addKin('m1', { full_name: 'Test Kin 01', phone: '0700000002', relationship: 'sibling' }),
      () => realCollateral.create(item, 'key-2'),
      () => realCollateral.list({ memberId: 'm1' }),
      () => realCollateral.addValuation('c1', { valued_on: '2026-01-01', market_value_minor: 1000 }),
    ]) {
      const error = await failure(call);
      expect(error.status).toBe(0);
      expect(error.code).toBe('network');
      expect(error.message).toMatch(/connection/i);
      expect(unknownOutcome(error)).toBe(true);
    }
  });

  it('is a stopped request, not a network failure, when the screen aborted it', async () => {
    fetchImpl.next = async () => {
      throw new DOMException('aborted', 'AbortError');
    };
    expect((await failure(() => realMembers.duplicateCheck({ phone: '+256700000001' }))).code).toBe('aborted');
  });
});

describe('answers from the server', () => {
  it('takes a 503 as an unknown outcome and a 409 or 422 as a refusal that was seen', async () => {
    fetchImpl.next = async () => json(503, { code: 'unavailable' });
    expect(unknownOutcome(await failure(() => realMembers.create(member, 'k')))).toBe(true);
    fetchImpl.next = async () => json(409, { code: 'duplicate_nin', detail: 'Member M000007 already has this NIN.' });
    const dup = await failure(() => realMembers.create(member, 'k'));
    expect(unknownOutcome(dup)).toBe(false);
    expect(dup.fields.national_id).toContain('M000007');
    fetchImpl.next = async () => json(422, { code: 'validation_failed', errors: [{ field: 'phone', code: 'invalid_phone', message: 'Not a valid phone number.' }] });
    expect((await failure(() => realMembers.create(member, 'k'))).fields.phone).toMatch(/Uganda mobile/);
  });

  it('sends the version as If-Match and the key as Idempotency-Key, and returns what the server saved', async () => {
    const seen: Request[] = [];
    fetchImpl.next = async (request) => {
      seen.push(request);
      return json(200, { id: 'm1', version: 2 });
    };
    expect(await realMembers.update('m1', 7, { occupation: 'x' })).toMatchObject({ version: 2 });
    expect(seen[0]?.headers.get('If-Match')).toBe('7');
    await realCollateral.create(item, 'abc-123');
    expect(seen[1]?.headers.get('Idempotency-Key')).toBe('abc-123');
  });
});
