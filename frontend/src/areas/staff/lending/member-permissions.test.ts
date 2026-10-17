import { describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import { FormError, fieldMessages, formMessage, unknownOutcome } from '../../../api/lending-forms';
import { holds, mayCreateMember, mayManageCollateral, memberBranches, showCollateral, showMembers } from './member-permissions';

const BR1 = '00000000-0000-4000-8000-0000000000b1';
const BR2 = '00000000-0000-4000-8000-0000000000b2';
const officer = (over: Partial<Me> & { modules?: string[] } = {}): Me & { modules?: string[] } => ({
  user_id: 'u1', full_name: 'Test Officer 01', kind: 'staff',
  permissions: ['lending.members.read', 'lending.members.create', 'lending.collateral.read', 'lending.collateral.manage'],
  permission_scopes: {
    'lending.members.read': { all_branches: true, branch_ids: [] },
    'lending.members.create': { all_branches: false, branch_ids: [BR1] },
    'lending.collateral.read': { all_branches: true, branch_ids: [] },
    'lending.collateral.manage': { all_branches: false, branch_ids: [BR1] },
  },
  branches: [{ id: BR1, code: 'BR1', name: 'Test Branch A' }, { id: BR2, code: 'BR2', name: 'Test Branch B' }],
  ...over,
});

describe('permission helpers with branch scope', () => {
  it('offers adding a member only in the branches the permission reaches', () => {
    const me = officer();
    expect(mayCreateMember(me)).toBe(true);
    expect(mayCreateMember(me, BR1)).toBe(true);
    expect(mayCreateMember(me, BR2)).toBe(false);
    expect(memberBranches(me).map((b) => b.id)).toEqual([BR1]);
    expect(mayManageCollateral(me, BR2)).toBe(false);
    expect(mayManageCollateral(me, BR1)).toBe(true);
  });
  it('counts a permission held for every branch in any branch, and one held for a list only for that list', () => {
    const wide = officer({ permission_scopes: { 'lending.members.create': { all_branches: true, branch_ids: [] }, 'lending.members.read': { all_branches: true, branch_ids: [] } } });
    expect(holds(wide, 'lending.members.create', BR2)).toBe(true);
    expect(holds(wide, 'lending.members.create', 'any-other-branch')).toBe(true);
    const narrow = officer({ permission_scopes: { 'lending.members.create': { all_branches: false, branch_ids: [BR1] } } });
    expect(holds(narrow, 'lending.members.create', BR1)).toBe(true);
    expect(holds(narrow, 'lending.members.create', BR2)).toBe(false);
  });
  it('counts a payload without scopes as every branch, and a missing permission as none', () => {
    expect(holds(officer({ permission_scopes: undefined }), 'lending.members.create', BR2)).toBe(true);
    expect(holds(officer(), 'lending.members.update', BR1)).toBe(false);
  });
  it('hides the menus without the read permission or without the lending module', () => {
    expect(showMembers(officer())).toBe(true);
    expect(showMembers(officer({ modules: ['retail'] }))).toBe(false);
    expect(showCollateral(officer({ permissions: ['lending.members.read'] }))).toBe(false);
  });
});

describe('form errors in plain words', () => {
  it('ties a duplicate national ID to its field and names the other member from the server detail', () => {
    const problem = { code: 'duplicate_nin', detail: 'Member M000007 already has this NIN.' };
    expect(formMessage(problem, 409)).toContain('M000007');
    expect(fieldMessages(problem).national_id).toContain('M000007');
  });
  it('words collateral refusals without mentioning a loan', () => {
    for (const code of ['invalid_status_transition', 'approval_already_pending', 'self_approval_forbidden']) {
      expect(formMessage({ code }, 409)).not.toMatch(/loan/i);
    }
  });
  it('never shows an API field name', () => {
    const out = fieldMessages({ code: 'validation_failed', errors: [{ field: 'alt_phone', code: 'invalid_phone', message: 'Not a valid phone number.' }] });
    expect(out.alt_phone).toMatch(/Uganda mobile/);
  });
  it('knows an answer that may have been lost, so the screen looks before it retries', () => {
    expect(unknownOutcome(new FormError('x', 0, 'network'))).toBe(true);
    expect(unknownOutcome(new FormError('x', 503, undefined))).toBe(true);
    expect(unknownOutcome(new FormError('x', 409, 'duplicate_phone'))).toBe(false);
    // A failure that is not an answer at all (a raw TypeError from a dropped connection) is a lost answer too.
    expect(unknownOutcome(new TypeError('Failed to fetch'))).toBe(true);
    expect(unknownOutcome('not an error')).toBe(false);
  });
});
