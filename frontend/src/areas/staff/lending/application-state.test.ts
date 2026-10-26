import { describe, expect, it } from 'vitest';
import type { Loan } from '../../../api/lending';
import type { LoanProduct } from '../../../api/loan-applications';
import { applicationProblems, blankApplication, decisionBody, draftOf, partiesChanged, pledgeable, rateWords, selfApproval, termWords, updateBody } from './application-state';
import { applicationSteps, mayApply } from './permissions';

// The pure rules of the loan application screens (issue #280 slices 3 and 4, FR-ORG-01, FR-ORG-06, FR-APR-03). Fabricated values.

const product: LoanProduct = {
  id: 'p1', status: 'active',
  current_version: { currency: 'KES', min_principal_minor: 10_000, max_principal_minor: 1_000_000, min_term_count: 2, max_term_count: 8, term_unit: 'week', requires_guarantor: false, requires_collateral: false },
};
const loan: Loan = {
  id: 'l1', branch_id: 'b1', status: 'draft', currency: 'KES', requested_principal_minor: 500_000, requested_term_count: 4, term_unit: 'week', purpose_category: 'business',
  guarantors: [{ member_id: 'g1', member_no: 'M000002', guaranteed_amount_minor: 100_000 }], collateral: [], created_by: 'u1', version: 2,
};

describe('application rules', () => {
  it('types money in the product currency: two decimals for KES, and the limits in its words', () => {
    const d = { ...blankApplication('m1'), productId: 'p1', amount: '50.005', purpose: 'business' };
    expect(applicationProblems(d, product, '2026-10-25', 'KES').requested_principal_minor).toMatch(/in KES, with no letters and at most 2 digits after the point/);
    expect(applicationProblems({ ...d, amount: '20,000.00' }, product, '2026-10-25', 'KES').requested_principal_minor).toBe('This product lends from KES 100.00 to KES 10,000.00.');
    expect(applicationProblems({ ...d, amount: '500', term: '9' }, product, '2026-10-25', 'KES')).toEqual({ requested_term_count: 'This product allows from 2 weeks to 8 weeks.' });
    expect(applicationProblems({ ...d, amount: '500', date: '2026-10-24' }, product, '2026-10-25', 'KES').proposed_disbursement_date).toMatch(/today or a later day/);
  });

  it('checks a draft in the currency it is labelled and sent in, even when its product no longer takes applications (review of #591)', () => {
    const d = draftOf(loan);
    // An archived product is not in the active list, so there are no product terms: the KES draft still checks in KES.
    expect(applicationProblems(d, undefined, '2026-10-25', 'KES').requested_principal_minor).toBeUndefined();
    expect(applicationProblems({ ...blankApplication('m1'), amount: '1,500.50', purpose: 'business' }, undefined, '2026-10-25', 'KES').requested_principal_minor).toBeUndefined();
    expect(applicationProblems({ ...blankApplication('m1'), amount: '1,500.50', purpose: 'business' }, undefined, '2026-10-25', 'UGX').requested_principal_minor).toMatch(/in shillings/);
  });

  it('reads a draft back as typed and sends only what changed', () => {
    const d = draftOf(loan);
    expect(d).toMatchObject({ amount: '5,000.00', term: '4', guarantors: [{ memberId: 'g1', amount: '1,000.00' }] });
    expect(updateBody(loan, d)).toEqual({});
    expect(updateBody(loan, { ...d, amount: '4,000' })).toEqual({ requested_principal_minor: 400_000 });
    expect(partiesChanged(loan, d, 'KES')).toEqual({ guarantors: false, pledges: false });
    expect(partiesChanged(loan, { ...d, guarantors: [] }, 'KES')).toEqual({ guarantors: true, pledges: false });
  });

  it('pledges only an item held for the member, valued, and in the loan currency', () => {
    const item = { custody_status: 'in_custody', currency: 'KES', collateral_value_minor: 1 };
    expect(pledgeable(item, 'KES')).toBe(true);
    expect(pledgeable({ ...item, currency: 'UGX' }, 'KES')).toBe(false);
    expect(pledgeable({ ...item, collateral_value_minor: undefined }, 'KES')).toBe(false);
    expect(pledgeable({ ...item, custody_status: 'released' }, 'KES')).toBe(false);
  });

  it('says rates and terms in words', () => {
    expect(rateWords(350, 'per_month', 'flat')).toBe('3.5% a month, flat');
    expect(rateWords(1200, 'per_year', 'declining')).toBe('12% a year, on the balance');
    expect(termWords(1, 'month')).toBe('1 month');
  });

  it('approves without sending terms that were not changed, and declines with the reason only', () => {
    const appraised = { ...loan, status: 'appraised' };
    expect(decisionBody({ decision: 'approve', amount: '5,000.00', term: '4', note: '' }, appraised)).toEqual({ decision: 'approve', approved_principal_minor: undefined, approved_term_count: undefined, note: undefined });
    expect(decisionBody({ decision: 'reject', amount: '', term: '', note: ' No income. ' }, appraised)).toEqual({ decision: 'reject', note: 'No income.' });
  });

  it('keeps the maker from approving: who sent or appraised it (FR-APR-03)', () => {
    expect(selfApproval({ ...loan, submitted_by: 'u1' }, 'u1')).toMatch(/You sent/);
    expect(selfApproval({ ...loan, appraised_by: 'u1' }, 'u1')).toMatch(/You appraised/);
    expect(selfApproval({ ...loan, submitted_by: 'u2', appraised_by: 'u3' }, 'u1')).toBeNull();
  });
});

describe('application steps by permission, status and branch', () => {
  const me = (permissions: string[], scopes?: Record<string, { all_branches?: boolean; branch_ids?: string[] }>) => ({ user_id: 'u1', modules: ['lending'], permissions: ['lending.loans.read', 'lending.members.read', ...permissions], permission_scopes: scopes });

  it('offers each step for the statuses the server accepts', () => {
    const all = me(['lending.loans.create', 'lending.loans.appraise', 'lending.loans.approve', 'lending.loans.cancel']);
    expect(applicationSteps(all, { ...loan, status: 'draft' })).toEqual(['submit', 'edit', 'cancel']);
    expect(applicationSteps(all, { ...loan, status: 'submitted' })).toEqual(['appraise', 'send_back', 'cancel']);
    expect(applicationSteps(all, { ...loan, status: 'appraised' })).toEqual(['appraise', 'decide', 'send_back', 'cancel']);
    expect(applicationSteps(all, { ...loan, status: 'approved' })).toEqual(['cancel']);
    expect(applicationSteps(all, { ...loan, status: 'rejected' })).toEqual([]);
  });

  it('needs the permission in the loan\'s branch, and lending switched on', () => {
    const scoped = me(['lending.loans.appraise'], { 'lending.loans.appraise': { branch_ids: ['b2'] } });
    expect(applicationSteps(scoped, { ...loan, status: 'submitted' })).toEqual([]);
    expect(applicationSteps(scoped, { ...loan, status: 'submitted', branch_id: 'b2' })).toEqual(['appraise']);
    expect(applicationSteps({ ...me(['lending.loans.appraise']), modules: ['retail'] }, { ...loan, status: 'submitted' })).toEqual([]);
  });

  it('lets an officer cancel only their own draft; a manager cancels any open application', () => {
    const officer = me(['lending.loans.cancel']);
    expect(applicationSteps(officer, loan)).toEqual(['cancel']);
    expect(applicationSteps(officer, { ...loan, created_by: 'u2' })).toEqual([]);
    expect(applicationSteps(officer, { ...loan, status: 'submitted' })).toEqual([]);
    expect(applicationSteps(me(['lending.loans.cancel', 'lending.loans.approve']), { ...loan, status: 'approved' })).toEqual(['cancel']);
  });

  it('starts an application only with create, and members to find the borrower', () => {
    expect(mayApply(me(['lending.loans.create']))).toBe(true);
    expect(mayApply({ ...me(['lending.loans.create']), permissions: ['lending.loans.read', 'lending.loans.create'] })).toBe(false);
    expect(mayApply(me([]))).toBe(false);
  });
});
