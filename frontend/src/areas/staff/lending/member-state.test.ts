import { describe, expect, it } from 'vitest';
import type { MemberRecord } from '../../../api/members';
import {
  blankMember, createBody, draftOf, duplicateHold, kycMissing, memberProblems, normaliseNin, normaliseUganda, phoneProblem, updateBody,
} from './member-state';

// The phone cases are the shared ones of chapter 13 section 13.6 (FR-MEM-02): the same forms the server accepts become
// +2567XXXXXXXX and everything else is refused. All numbers fabricated.
describe('normaliseUganda', () => {
  it.each([
    ['0712345678', '+256712345678'],
    ['712345678', '+256712345678'],
    ['256712345678', '+256712345678'],
    ['+256 712 345 678', '+256712345678'],
    ['0712-345-678', '+256712345678'],
    ['  +256712345678 ', '+256712345678'],
  ])('%s', (raw, e164) => expect(normaliseUganda(raw)).toBe(e164));

  it.each(['', '071234567', '07123456789', '+25671234567', '+255712345678', '0612345678', 'abc', '+256 7123'])('refuses %s', (raw) => {
    expect(normaliseUganda(raw)).toBeNull();
  });

  it('refuses a bare nine digits in the form, which the server would accept', () => {
    expect(phoneProblem('712345678')).toMatch(/Start the number with 0/);
    expect(phoneProblem('0712345678')).toBeNull();
    expect(phoneProblem('')).toBeNull();
    expect(phoneProblem('12345')).toMatch(/ten digits starting 07/);
  });
});

describe('normaliseNin', () => {
  it('upper-cases, removes spaces, and needs CM or CF and twelve letters or digits', () => {
    expect(normaliseNin('cm000000000001')).toBe('CM000000000001');
    expect(normaliseNin('CF 00000000 0001')).toBe('CF000000000001');
    expect(normaliseNin('CX000000000001')).toBeNull();
    expect(normaliseNin('CM00000000001')).toBeNull();
  });
});

const member = (over: Partial<MemberRecord> = {}): MemberRecord => ({
  id: 'm1', branch_id: 'b1', member_no: 'M000001', full_name: 'Test Borrower 01', phone_e164: '+256700000001', id_type: 'nin', national_id: 'CM000000000001',
  gender: 'female', marital_status: 'married', monthly_income_minor: 0, date_of_birth: '1990-01-01', version: 3, ...over,
});

describe('memberProblems', () => {
  const today = '2026-10-16';
  it('asks for the branch, name, phone and national ID, in plain words with no field names', () => {
    const p = memberProblems(blankMember(), today);
    expect(Object.keys(p).sort()).toEqual(['branch_id', 'full_name', 'national_id', 'phone']);
    for (const text of Object.values(p)) expect(text).not.toMatch(/_/);
  });
  it('needs the other ID number for a passport and nothing for no ID', () => {
    const base = { ...blankMember('b1'), fullName: 'Test Borrower 01', phone: '0700000001' };
    expect(memberProblems({ ...base, idType: 'passport' }, today).other_id_number).toBeTruthy();
    expect(memberProblems({ ...base, idType: 'none' }, today)).toEqual({});
  });
  it('refuses a date of birth that is today or impossible, and a malformed income', () => {
    const base = { ...blankMember('b1'), fullName: 'T', phone: '0700000001', nationalId: 'CM000000000001' };
    expect(memberProblems({ ...base, dateOfBirth: today }, today).date_of_birth).toBeTruthy();
    expect(memberProblems({ ...base, dateOfBirth: '2000-02-30' }, today).date_of_birth).toBeTruthy();
    expect(memberProblems({ ...base, monthlyIncome: '12.5' }, today).monthly_income_minor).toBeTruthy();
    expect(memberProblems({ ...base, dateOfBirth: '1990-05-05', monthlyIncome: '150,000' }, today)).toEqual({});
  });
  it('says a saved date of birth or income cannot be emptied, instead of silently keeping it', () => {
    const was = draftOf(member());
    const p = memberProblems({ ...was, dateOfBirth: '', monthlyIncome: '' }, today, true, was);
    expect(p.date_of_birth).toMatch(/cannot be removed/);
    expect(p.monthly_income_minor).toMatch(/cannot be removed/);
  });
});

describe('request bodies', () => {
  it('sends a registration with the typed phone and NIN, and no empty strings', () => {
    const body = createBody({ ...blankMember('b1'), fullName: ' Test Borrower 01 ', phone: '0700 000 001', nationalId: 'cm000000000001', monthlyIncome: '200000' });
    expect(body).toMatchObject({ branch_id: 'b1', full_name: 'Test Borrower 01', phone: '+256700000001', id_type: 'nin', national_id: 'CM000000000001', monthly_income_minor: 200000 });
    expect(body.gender).toBeUndefined();
    expect(body.other_id_number).toBeUndefined();
    expect(JSON.stringify(body)).not.toContain('""');
  });
  it('sends the normalised phone and national ID, so a pasted no-break space cannot pass here and fail on the server', () => {
    const body = createBody({ ...blankMember('b1'), fullName: 'T', phone: '0700\u00a0000\u00a0001', altPhone: '+256\u202f700 000 002', nationalId: 'cm\u00a0000000000001' });
    expect(body).toMatchObject({ phone: '+256700000001', alt_phone: '+256700000002', national_id: 'CM000000000001' });
    expect(JSON.stringify(body)).not.toMatch(/[\u00a0\u202f]/);
  });
  it('sends only what changed on an edit', () => {
    const m = member();
    expect(updateBody(m, { ...draftOf(m), occupation: 'Test trader' })).toEqual({ occupation: 'Test trader' });
  });
  it('maps "not said" to the server values, not an empty string', () => {
    const m = member();
    expect(updateBody(m, { ...draftOf(m), gender: '', maritalStatus: '' })).toEqual({ gender: 'unspecified', marital_status: 'unknown' });
  });
  it('shows an income of 0 as 0 on edit, and an unspecified gender as not said', () => {
    expect(draftOf(member()).monthlyIncome).toBe('0');
    expect(draftOf(member({ gender: 'unspecified', marital_status: 'unknown' })).gender).toBe('');
  });
});

describe('duplicateHold', () => {
  const c = (match_reasons: string[]) => ({ id: 'x', match_reasons, in_scope: true });
  it('stops always on the same national ID, and on the same phone until confirmed', () => {
    expect(duplicateHold([c(['nin'])], true)).toBe('nin');
    expect(duplicateHold([c(['phone'])], false)).toBe('phone');
    expect(duplicateHold([c(['phone'])], true)).toBeNull();
    expect(duplicateHold([c(['name'])], false)).toBeNull();
    expect(duplicateHold([], false)).toBeNull();
  });
});

describe('kycMissing', () => {
  it('lists what stops the details being checked', () => {
    const m = { id_type: 'nin', national_id: 'CM000000000001', district: 'Test District' };
    expect(kycMissing(m, 0, false)).toEqual(['a next of kin', 'a photo of the front of the ID']);
    expect(kycMissing(m, 1, true)).toEqual([]);
  });
});
