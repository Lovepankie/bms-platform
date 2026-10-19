import { describe, expect, it } from 'vitest';
import { planLimitWords } from '../../../api/users';
import {
  assignmentFor, branchUnavailable, canManageUsers, contactLine, emptyInvite, hasProblems, inviteBody, inviteProblems, matchesSearch, roleChoice, roleChoices, roleLines, showUsers, whatsappLink,
} from './state';

// The rules of the Users screen (issue #319), with fabricated people and numbers only.

const good = { ...emptyInvite(), fullName: 'Test Cashier 01', phone: '0700 000 001', roleKey: 'cashier' };

describe('who sees the screen', () => {
  it('shows the page to a reader and the actions to a manager only', () => {
    expect(showUsers({ permissions: ['core.users.read'] })).toBe(true);
    expect(showUsers({ permissions: ['core.settings.manage'] })).toBe(false);
    expect(canManageUsers({ permissions: ['core.users.read'] })).toBe(false);
    expect(canManageUsers({ permissions: ['core.users.read', 'core.users.manage'] })).toBe(true);
    expect(showUsers({})).toBe(false);
  });
});

describe('the invite form', () => {
  it('accepts a phone alone or an email alone, and wants one of them', () => {
    expect(hasProblems(inviteProblems(good))).toBe(false);
    expect(hasProblems(inviteProblems({ ...good, phone: '', email: 'test.cashier01@example.com' }))).toBe(false);
    expect(inviteProblems({ ...good, phone: '', email: '' }).contact).toMatch(/phone number or an email/);
  });

  it('asks for a name and a role', () => {
    const p = inviteProblems({ ...good, fullName: '  ', roleKey: '' });
    expect(p.fullName).toBeTruthy();
    expect(p.roleKey).toBeTruthy();
  });

  it('checks a Uganda phone the way the member forms do', () => {
    expect(inviteProblems({ ...good, phone: '0700000001' }).phone).toBeUndefined();
    expect(inviteProblems({ ...good, phone: '+256 700 000 001' }).phone).toBeUndefined();
    expect(inviteProblems({ ...good, phone: '700000001' }).phone).toMatch(/Start the number with 0/);
    expect(inviteProblems({ ...good, phone: '12345' }).phone).toMatch(/Uganda mobile number/);
    expect(inviteProblems({ ...good, phone: '+254700000001' }).phone).toBeTruthy();
  });

  it('checks an email', () => {
    expect(inviteProblems({ ...good, email: 'not-an-email' }).email).toBeTruthy();
    expect(inviteProblems({ ...good, email: 'test.cashier01@example.com' }).email).toBeUndefined();
  });

  it('sends the phone as the server stores it, and leaves out what was not typed', () => {
    expect(inviteBody(good)).toEqual({ full_name: 'Test Cashier 01', phone: '+256700000001', roles: [{ role_key: 'cashier' }] });
    expect(inviteBody({ ...good, phone: '', email: ' test.cashier01@example.com ', branchId: 'b1' })).toEqual({
      full_name: 'Test Cashier 01',
      email: 'test.cashier01@example.com',
      roles: [{ role_key: 'cashier', branch_id: 'b1' }],
    });
  });

  it('never sends a branch with the administrator role, which covers every branch', () => {
    expect(assignmentFor('tenant_admin', 'b1')).toEqual({ role_key: 'tenant_admin' });
    expect(assignmentFor('loan_officer', '')).toEqual({ role_key: 'loan_officer' });
    expect(assignmentFor('loan_officer', 'b1')).toEqual({ role_key: 'loan_officer', branch_id: 'b1' });
  });
});

describe('roles in plain words', () => {
  it('names a role as "Sales person", without its key, with what it is for', () => {
    const c = roleChoice({ key: 'retail_sales', name: 'Sales' });
    expect(c.label).toBe('Sales person');
    expect(c.label).not.toMatch(/retail_sales/);
    expect(c.about).toMatch(/Sells in the shop/);
  });

  it('keeps a role it has no words for, under the name the server gives it, administrator first', () => {
    const list = roleChoices([{ key: 'cashier', name: 'Cashier' }, { key: 'new_role', name: 'Stock clerk' }, { key: 'tenant_admin', name: 'Tenant admin' }]);
    expect(list.map((c) => c.key)).toEqual(['tenant_admin', 'cashier', 'new_role']);
    expect(list[2]?.label).toBe('Stock clerk');
  });

  it('writes a user\'s roles with their branch', () => {
    const user = { roles: [{ role_key: 'cashier', branch_id: 'b1' }, { role_key: 'auditor' }] };
    expect(roleLines(user, [{ id: 'b1', name: 'Test Branch One' }], [])).toEqual(['Cashier, Test Branch One', 'Auditor, every branch']);
  });
});

describe('the plan limit', () => {
  it('turns the server sentence into plain words with the number', () => {
    const words = planLimitWords('The plan allows at most 5 (max_staff_users); the limit has been reached.');
    expect(words).toMatch(/at most 5 staff users/);
    expect(words).not.toMatch(/max_staff_users/);
    expect(planLimitWords(undefined)).toMatch(/limit of staff users/);
  });
});

describe('the WhatsApp message', () => {
  it('opens the chat of the invited phone with the link and the expiry in the text', () => {
    const href = whatsappLink({ phone: '+256700000001', name: 'Test Cashier 01', business: 'Test Trading Co', url: 'https://test.example/accept-invitation#token=abc', expiresAt: '2026-10-22T10:00:00Z' });
    expect(href.startsWith('https://wa.me/256700000001?text=')).toBe(true);
    const text = decodeURIComponent(href.split('?text=')[1] ?? '');
    expect(text).toContain('Hello Test,');
    expect(text).toContain('Test Trading Co');
    expect(text).toContain('https://test.example/accept-invitation#token=abc');
    expect(text).toMatch(/stops working on/);
  });

  it('lets WhatsApp ask who to send to when there is no phone', () => {
    expect(whatsappLink({ phone: null, name: 'Test', business: 'B', url: 'https://x.example/l' }).startsWith('https://wa.me/?text=')).toBe(true);
  });
});

describe('finding a person', () => {
  const user = { full_name: 'Test Seller 02', email: 'seller02@example.com', phone_e164: '+256700000002' };

  it('matches a name, an email, and a phone however it is typed', () => {
    for (const term of ['seller', 'SELLER02@', 'Test Seller 02', '0700000002', '0700 000 002', '+256 700 000 002', '256700000002', '700000002', '000 002']) {
      expect(matchesSearch(user, term), term).toBe(true);
    }
  });

  it('does not match another number, or a couple of digits', () => {
    for (const term of ['0700000003', '+256700000003', '07', '12', 'nobody']) expect(matchesSearch(user, term), term).toBe(false);
    expect(matchesSearch({ full_name: 'Test Owner 01' }, '000 002')).toBe(false);
  });

  it('shows a phone written to read', () => {
    expect(contactLine(user)).toBe('+256 700 000 002 \u00b7 seller02@example.com');
  });
});

describe('a branch that has closed', () => {
  const branches = [{ id: 'a', status: 'active' }, { id: 'c', status: 'inactive' }];
  it('is unavailable; every branch, an open branch, and an unloaded list are not', () => {
    expect(branchUnavailable('c', branches)).toBe(true);
    expect(branchUnavailable('gone', branches)).toBe(true);
    expect(branchUnavailable('a', branches)).toBe(false);
    expect(branchUnavailable('', branches)).toBe(false);
    expect(branchUnavailable('c', [])).toBe(false);
  });
});
