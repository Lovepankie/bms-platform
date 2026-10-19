import { beforeEach, describe, expect, it, vi } from 'vitest';

// The staff users client (issue #319): a call that never got an answer, and the server's field problems, in plain words.

const mocks = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn() }));
vi.mock('./client', async (original) => ({ ...(await original<typeof import('./client')>()), api: mocks }));

const { NETWORK_WORDS, UsersError, users } = await import('./users');

const refused = (body: object, status = 422) => ({ error: body, response: { ok: false, status } });

beforeEach(() => {
  Object.values(mocks).forEach((m) => m.mockReset());
});

describe('a call that got no answer', () => {
  it('says the answer did not reach the phone and where to look, for the browser\'s own words', async () => {
    for (const text of ['Failed to fetch', 'Load failed', 'NetworkError when attempting to fetch resource.']) {
      mocks.POST.mockRejectedValueOnce(new TypeError(text));
      const error = await users.invite({ full_name: 'Test Cashier 01', phone: '+256700000001', roles: [{ role_key: 'cashier' }] }).catch((e: unknown) => e);
      expect(error).toBeInstanceOf(UsersError);
      expect((error as InstanceType<typeof UsersError>).message).toBe(NETWORK_WORDS);
      expect((error as InstanceType<typeof UsersError>).code).toBe('network');
      expect((error as Error).message).not.toMatch(/fetch|Load failed/i);
    }
    expect(NETWORK_WORDS).toMatch(/staff list/);
    expect(NETWORK_WORDS).toMatch(/Send a new link/);
  });
});

describe('field problems', () => {
  const field = (code: string, message: string) => refused({ code: 'validation_failed', detail: 'x', errors: [{ field: 'roles', code, message }] });

  it('writes the branch, role and contact problems in plain words, not the server sentence', async () => {
    const cases: [string, string, RegExp][] = [
      ['unknown_branch', 'No such active branch in your scope.', /closed or is not one you may use/],
      ['unknown_role', 'No staff role x.', /role is not available/],
      ['invalid', 'The tenant admin role covers every branch.', /works in every branch/],
      ['required', 'Give an email or a phone.', /phone number or an email/],
    ];
    for (const [code, raw, plain] of cases) {
      mocks.POST.mockResolvedValueOnce(field(code, raw));
      const error = (await users.invite({ full_name: 'T', roles: [] }).catch((e: unknown) => e)) as Error;
      expect(error.message).toMatch(plain);
      expect(error.message).not.toBe(raw);
    }
  });
});
