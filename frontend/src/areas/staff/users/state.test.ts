import { describe, expect, it } from 'vitest';
import { planLimitWords } from '../../../api/users';
import {
  EVERY_BRANCH, whoCanDoWhat, canActOn, hasOwner, mayChangeAdministrators, offeredChoices, ownershipTargets, assignmentFor, assignmentsFor, branchUnavailable, purchasingOffered, roleRows, canManageUsers, canResetSignIn, contactLine, resetProofProblem, emptyInvite, hasProblems, inviteBody, inviteProblems, matchesSearch, roleChoice, roleChoices, roleLines, showUsers, whatsappLink,
} from './state';

// The rules of the Users screen (issue #319), with fabricated people and numbers only.

const good = { ...emptyInvite(), fullName: 'Test Cashier 01', phone: '0700 000 001', roleKey: 'cashier', branchIds: [EVERY_BRANCH] };

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
    expect(inviteBody({ ...good, phone: '', email: ' test.cashier01@example.com ', branchIds: ['b1'] })).toEqual({
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

  // Issue #399, ADR-055: a person works in the shops and stores the owner ticks, one assignment each; Purchasing rides along.
  it('sends one assignment per shop or store ticked, and none for the administrator or every shop and store', () => {
    expect(assignmentsFor('retail_storekeeper', ['s1', 'h1', 's1'])).toEqual([
      { role_key: 'retail_storekeeper', branch_id: 's1' },
      { role_key: 'retail_storekeeper', branch_id: 'h1' },
    ]);
    expect(assignmentsFor('retail_storekeeper', [EVERY_BRANCH])).toEqual([{ role_key: 'retail_storekeeper' }]);
    // Nothing ticked is nothing sent, never every branch; the form refuses it first.
    expect(assignmentsFor('retail_storekeeper', [])).toEqual([]);
    expect(inviteProblems({ ...good, branchIds: [] }).branches).toMatch(/Tick where this person works/);
    expect(inviteProblems({ ...good, roleKey: 'tenant_admin', branchIds: [] }).branches).toBeUndefined();
    expect(assignmentsFor('tenant_admin', ['s1'])).toEqual([{ role_key: 'tenant_admin' }]);
  });

  it('adds Purchasing in the same shops and stores when it is ticked, and never to an administrator', () => {
    const seller = { ...good, roleKey: 'retail_sales', branchIds: ['h1', 'h2'], purchasing: true };
    expect(inviteBody(seller).roles).toEqual([
      { role_key: 'retail_sales', branch_id: 'h1' },
      { role_key: 'retail_sales', branch_id: 'h2' },
      { role_key: 'retail_purchasing', branch_id: 'h1' },
      { role_key: 'retail_purchasing', branch_id: 'h2' },
    ]);
    expect(inviteBody({ ...seller, purchasing: false }).roles).toHaveLength(2);
    expect(inviteBody({ ...seller, roleKey: 'tenant_admin' }).roles).toEqual([{ role_key: 'tenant_admin' }]);
    const roles = [{ key: 'tenant_admin' }, { key: 'retail_sales' }, { key: 'retail_purchasing' }];
    expect(purchasingOffered('retail_sales', roles)).toBe(true);
    expect(purchasingOffered('tenant_admin', roles)).toBe(false);
    expect(purchasingOffered('retail_purchasing', roles)).toBe(false);
    expect(purchasingOffered('', roles)).toBe(false);
    expect(purchasingOffered('retail_sales', [{ key: 'retail_sales' }])).toBe(false);
  });
});

describe('roles in plain words', () => {
  it('names a role as "Sales person", without its key, with what it is for', () => {
    const c = roleChoice({ key: 'retail_sales', name: 'Sales' });
    expect(c.label).toBe('Sales person');
    expect(c.label).not.toMatch(/retail_sales/);
    expect(c.about).toMatch(/Sells in their shops/);
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

  it('names a role in several shops and stores on one line, a store marked as such', () => {
    const user = { roles: [{ role_key: 'retail_storekeeper', branch_id: 's1' }, { role_key: 'retail_storekeeper', branch_id: 'h1' }] };
    const branches = [{ id: 's1', name: 'Test Store', sells_goods: false }, { id: 'h1', name: 'Test Shop' }];
    expect(roleLines(user, branches, [])).toEqual(['Storekeeper, Test Store (store), Test Shop']);
    expect(roleRows(user)).toEqual([{ roleKey: 'retail_storekeeper', branchIds: ['s1', 'h1'] }]);
    expect(roleRows({ roles: [{ role_key: 'cashier', branch_id: 'b1' }, { role_key: 'cashier' }] })).toEqual([{ roleKey: 'cashier', branchIds: [EVERY_BRANCH] }]);
  });

  it('names the Storekeeper and Purchasing roles in plain words, and leaves retail roles out of a business without retail', () => {
    expect(roleChoice({ key: 'retail_storekeeper', name: 'Storekeeper' }).about).toMatch(/Sees no prices and no money, and does not sell/);
    expect(roleChoice({ key: 'retail_purchasing', name: 'Purchasing' }).about).toMatch(/Buys stock and records purchases/);
    const all = [{ key: 'tenant_admin' }, { key: 'loan_officer' }, { key: 'retail_sales' }, { key: 'retail_storekeeper' }];
    expect(roleChoices(all, ['lending']).map((c) => c.key)).toEqual(['tenant_admin', 'loan_officer']);
    expect(roleChoices(all, ['retail']).map((c) => c.key)).toHaveLength(4);
    expect(roleChoices(all, undefined).map((c) => c.key)).toHaveLength(4);
  });
});

// Issue #399, ADR-055: the card says what each role does in the words of the pick-list, so the two cannot drift apart.
describe('who can do what', () => {
  const roles = [
    { key: 'retail_sales' }, { key: 'tenant_admin' }, { key: 'retail_supervisor' }, { key: 'retail_storekeeper' },
    { key: 'retail_purchasing' }, { key: 'retail_till' }, { key: 'loan_officer' },
  ];
  it('has one line per role, the administrator first, in the pick-list words', () => {
    const lines = whoCanDoWhat(roles, ['retail', 'lending']);
    expect(lines.map((l) => l.title)).toEqual([
      'Owner', 'Administrator', 'Sales person', 'Supervisor', 'Storekeeper', 'Purchasing', 'Can close the till and do the banking', 'Loan officer',
    ]);
    expect(lines.slice(1).map((l) => l.about)).toEqual(roleChoices(roles, ['retail', 'lending']).map((c) => c.about));
    expect(lines[0]?.about).toMatch(/only one who can add, remove or change an administrator/);
    expect(lines[1]?.about).toMatch(/every shop and store, profit and costs/);
    expect(lines.find((l) => l.title === 'Storekeeper')?.about).toMatch(/no prices and no money/);
    expect(lines.find((l) => l.title === 'Supervisor')?.about).toMatch(/No profit, costs/);
    expect(lines.find((l) => l.title === 'Purchasing')?.about).toMatch(/buying prices of what they buy/);
    expect(lines.map((l) => `${l.title} ${l.about}`).join(' ')).not.toMatch(/[\u2013\u2014]/);
  });

  it('leaves the retail roles out of a business without retail', () => {
    expect(whoCanDoWhat(roles, ['lending']).map((l) => l.title)).toEqual(['Owner', 'Administrator', 'Loan officer']);
  });

  it('adds the till in the same shops and stores when it is ticked', () => {
    const body = inviteBody({ ...good, roleKey: 'retail_sales', branchIds: ['h1'], till: true });
    expect(body.roles).toEqual([{ role_key: 'retail_sales', branch_id: 'h1' }, { role_key: 'retail_till', branch_id: 'h1' }]);
    expect(purchasingOffered('retail_sales', roles, 'retail_till')).toBe(true);
    expect(purchasingOffered('retail_till', roles, 'retail_till')).toBe(false);
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

// JUSTIFICATION-A3: tests for the three new pure helpers of Reset sign-in; the anchor is only the insertion point.
describe('Reset sign-in (issue #339)', () => {
  const admin = { permissions: ['core.users.read', 'core.users.manage'], user_id: 'me' };
  const seller = { id: 's', status: 'active', roles: [{ role_key: 'retail_sales' }] };
  it('is for an administrator, an active person, who is not an administrator and not themselves', () => {
    expect(canResetSignIn(admin, seller)).toBe(true);
    expect(canResetSignIn({ ...admin, permissions: ['core.users.read'] }, seller)).toBe(false);
    expect(canResetSignIn(admin, { ...seller, id: 'me' })).toBe(false);
    expect(canResetSignIn(admin, { ...seller, roles: [{ role_key: 'tenant_admin' }] })).toBe(false);
    expect(canResetSignIn(admin, { ...seller, roles: [{ role_key: 'cashier' }, { role_key: 'tenant_admin' }] })).toBe(false);
    expect(canResetSignIn(admin, { ...seller, status: 'invited' })).toBe(false);
    expect(canResetSignIn(admin, { ...seller, status: 'deactivated' })).toBe(false);
  });

  it('asks for six digits with an authenticator app and for the password without one', () => {
    expect(resetProofProblem(true, '123456')).toBeNull();
    expect(resetProofProblem(true, ' 123456 ')).toBeNull();
    expect(resetProofProblem(true, '12345')).toMatch(/6 digits/);
    expect(resetProofProblem(true, 'abcdef')).toMatch(/6 digits/);
    expect(resetProofProblem(false, 'invented-password-1')).toBeNull();
    expect(resetProofProblem(false, '')).toMatch(/password/);
  });

  it('words the WhatsApp message for a new password, not an invitation', () => {
    const text = decodeURIComponent(whatsappLink({ phone: '+256700000007', name: 'Test Seller 07', business: 'Test Trading Co', url: 'https://test.example/x#token=t', reset: true }));
    expect(text).toContain('Hello Test, here is your link to choose a new password for Test Trading Co.');
    expect(text).not.toContain('invited');
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

// The Owner of the business (ADR-062, FR-IAM-18): what the screen offers, with fabricated people only. The server is the authority.
describe('the owner', () => {
  const MANAGE = ['core.users.read', 'core.users.manage'];
  type P = { id: string; status: string; is_owner?: boolean; roles: { role_key: string; branch_id?: string }[] };
  const admin = (id: string, extra: Partial<P> = {}): P => ({ id, status: 'active', roles: [{ role_key: 'tenant_admin' }], ...extra });
  const owner = admin('o', { is_owner: true });
  const other = admin('a');
  const cashier: P = { id: 'c', status: 'active', roles: [{ role_key: 'cashier', branch_id: 'h1' }] };
  const people: P[] = [owner, other, cashier, admin('gone', { status: 'deactivated' }), admin('new', { status: 'invited' })];
  const ownerMe = { user_id: 'o', permissions: MANAGE, is_owner: true };
  const adminMe = { user_id: 'a', permissions: MANAGE, is_owner: false };

  it('lets only the owner change administrators, and every administrator while the business has none', () => {
    expect(hasOwner(people)).toBe(true);
    expect(mayChangeAdministrators(ownerMe, people)).toBe(true);
    expect(mayChangeAdministrators(adminMe, people)).toBe(false);
    expect(mayChangeAdministrators(adminMe, [other, cashier])).toBe(true);
    expect(mayChangeAdministrators({ permissions: ['core.users.read'], is_owner: true }, people)).toBe(false);
  });

  it('hides the actions on the owner and on other administrators from an administrator, never on other staff', () => {
    expect(canActOn(adminMe, owner, people)).toBe(false);
    expect(canActOn(adminMe, admin('b'), people)).toBe(false);
    expect(canActOn(adminMe, cashier, people)).toBe(true);
    expect(canActOn(adminMe, other, people)).toBe(true);
    expect(canActOn(ownerMe, other, people)).toBe(true);
    expect(canActOn(ownerMe, owner, people)).toBe(true);
  });

  it('offers the administrator role only to someone who may give it', () => {
    const choices = roleChoices([{ key: 'tenant_admin' }, { key: 'cashier' }], ['lending']);
    expect(offeredChoices(choices, ownerMe, people).map((c) => c.key)).toEqual(['tenant_admin', 'cashier']);
    expect(offeredChoices(choices, adminMe, people).map((c) => c.key)).toEqual(['cashier']);
    // While the staff list loads, only the Owner sees the administrator role, so it never flashes for anyone else.
    expect(offeredChoices(choices, adminMe, null).map((c) => c.key)).toEqual(['cashier']);
    expect(offeredChoices(choices, ownerMe, null).map((c) => c.key)).toEqual(['tenant_admin', 'cashier']);
  });

  it('hands ownership only to another active administrator, and only from the owner', () => {
    expect(ownershipTargets(ownerMe, people).map((p) => p.id)).toEqual(['a']);
    expect(ownershipTargets(adminMe, people)).toEqual([]);
  });
});
