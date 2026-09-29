import { describe, expect, it } from 'vitest';
import { ALL_BRANCHES, branchFilter, initialBranch } from './branch';
import { invitationToken, normaliseSecondFactor, passwordProblem, recoveryCodesText } from './codes';

describe('normaliseSecondFactor', () => {
  it('recognises a TOTP code', () => {
    expect(normaliseSecondFactor(' 123 456 ')).toEqual({ kind: 'totp', code: '123456' });
  });

  it('recognises a recovery code however it was typed', () => {
    expect(normaliseSecondFactor('abcde fghjk')).toEqual({ kind: 'recovery', code: 'ABCDE-FGHJK' });
    expect(normaliseSecondFactor('ABCDE-FGHJK')).toEqual({ kind: 'recovery', code: 'ABCDE-FGHJK' });
  });

  it('refuses anything else', () => {
    expect(normaliseSecondFactor('12345').kind).toBe('invalid');
  });
});

describe('invitationToken', () => {
  it('reads the token from the fragment', () => {
    expect(invitationToken('#token=abcdefghijklmnopqrstuvwxyz0123')).toBe('abcdefghijklmnopqrstuvwxyz0123');
  });

  it('refuses a missing or short token', () => {
    expect(invitationToken('')).toBeNull();
    expect(invitationToken('#token=short')).toBeNull();
  });
});

describe('passwordProblem', () => {
  it('checks length and confirmation', () => {
    expect(passwordProblem('short', 'short')).toMatch(/10 characters/);
    expect(passwordProblem('Fabricated-Pass-2026', 'Fabricated-Pass-2027')).toMatch(/differ/);
    expect(passwordProblem('Fabricated-Pass-2026', 'Fabricated-Pass-2026')).toBeNull();
  });
});

describe('recoveryCodesText', () => {
  it('numbers the codes one per line', () => {
    expect(recoveryCodesText(['AAAAA-BBBBB', 'CCCCC-DDDDD'])).toBe(' 1. AAAAA-BBBBB\n 2. CCCCC-DDDDD');
  });
});

describe('branch selection (FR-BR-03, FR-BR-04)', () => {
  const me = {
    all_branches: false,
    branches: [
      { id: 'b1', code: 'HQ', name: 'Head Office', is_head_office: true },
      { id: 'b2', code: 'BR2', name: 'Test Branch Two', is_head_office: false },
    ],
    default_branch_id: 'b1',
  };

  it('keeps a stored branch that is still allowed', () => {
    expect(initialBranch(me, 'b2')).toBe('b2');
  });

  it('falls back to the default when the stored choice is not allowed', () => {
    expect(initialBranch(me, 'gone')).toBe('b1');
    expect(initialBranch(me, ALL_BRANCHES)).toBe('b1');
    expect(initialBranch({ ...me, all_branches: true }, ALL_BRANCHES)).toBe(ALL_BRANCHES);
  });

  it('sends no branch filter for all branches', () => {
    expect(branchFilter(ALL_BRANCHES)).toBeUndefined();
    expect(branchFilter('b2')).toEqual(['b2']);
  });
});
