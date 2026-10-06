import { describe, expect, it } from 'vitest';
import { ALL_BRANCHES, branchLabel, initialBranch, type MeForBranches } from './branch';

// #103 and #105 with fabricated branches: a head office that holds no stock and three shops that do.
const HQ = { id: 'hq', code: 'HQ', name: 'Head Office', is_head_office: true };
const shops = [
  { id: 's1', code: 'TESTSHOPA', name: 'Testshopa', is_head_office: false },
  { id: 's2', code: 'TSB', name: 'Test Shop B', is_head_office: false },
  { id: 's3', code: 'TSC', name: 'Test Shop C', is_head_office: false },
];
const admin: MeForBranches = { all_branches: true, default_branch_id: 'hq', branches: [HQ, ...shops] };

describe('branch label (#105)', () => {
  it('prints the name alone when the code is the name in other letters, else Name (CODE)', () => {
    expect(branchLabel(shops[0])).toBe('Testshopa');
    expect(branchLabel({ code: 'second', name: 'SECOND' })).toBe('SECOND');
    expect(branchLabel(HQ)).toBe('Head Office (HQ)');
    expect(branchLabel(shops[1])).toBe('Test Shop B (TSB)');
    expect(branchLabel({ code: 'BR9' })).toBe('BR9');
    expect(branchLabel(undefined)).toBe('');
  });
});

describe('the branch the staff area starts on (#103)', () => {
  it('keeps the last branch this user chose, head office included, and All branches when allowed', () => {
    expect(initialBranch(admin, 's3', new Set(['s1']))).toBe('s3');
    expect(initialBranch(admin, 'hq', new Set(['s1']))).toBe('hq');
    expect(initialBranch(admin, ALL_BRANCHES, null)).toBe(ALL_BRANCHES);
    expect(initialBranch({ ...admin, all_branches: false }, ALL_BRANCHES, new Set(['s2']))).toBe('s2');
  });

  it('starts on the only branch a user has, stock or not', () => {
    expect(initialBranch({ default_branch_id: 's2', branches: [shops[1]!] }, null, new Set())).toBe('s2');
  });

  it('never starts on a head office without stock when shops hold it', () => {
    expect(initialBranch(admin, null, new Set(['s2', 's3']))).toBe('s2');
    expect(initialBranch(admin, 'gone', new Set(['s1', 's2', 's3']))).toBe('s1');
  });

  it('prefers the default branch when it holds stock', () => {
    expect(initialBranch({ ...admin, default_branch_id: 's3' }, null, new Set(['s1', 's3']))).toBe('s3');
    expect(initialBranch(admin, null, new Set(['hq', 's1']))).toBe('hq');
  });

  it('opens a shop rather than an empty head office even before anything holds stock', () => {
    expect(initialBranch(admin, null, new Set())).toBe('s1');
  });

  it('keeps the default branch when stock is unknown (no stock read)', () => {
    expect(initialBranch(admin, null, null)).toBe('hq');
    expect(initialBranch({ branches: [] }, null, null)).toBeNull();
  });
});
