import type { Me } from '../../../api/client';
import type { InviteUserBody, RoleAssignmentRequest, StaffBranch, StaffRole, StaffUser } from '../../../api/users';
import { foldText } from '../../../app/ui/fold';
import { normaliseUganda, phoneProblem, showPhone } from '../lending/member-state';

// Who sees the Users screen, and the rules and words it uses (issue #319; FR-IAM-01, FR-IAM-02, FR-IAM-08). The API is the
// authority (it checks the permission, the branch scope, the plan and the last administrator); this decides what the menu and
// the page offer, from the /me permissions, and refuses an obviously wrong form before anything is sent.

export const USERS_READ = 'core.users.read';
export const USERS_MANAGE = 'core.users.manage';

const held = (me: Pick<Me, 'permissions'>): string[] => me.permissions ?? [];

/** The page and its menu entries: everyone who may read the staff list. */
export const showUsers = (me: Pick<Me, 'permissions'>): boolean => held(me).includes(USERS_READ);

/** Inviting, changing roles, sending a new link and deactivating: administrators only. Others see the list. */
export const canManageUsers = (me: Pick<Me, 'permissions'>): boolean => held(me).includes(USERS_MANAGE);

export const TENANT_ADMIN = 'tenant_admin';

/** What each fixed role is for, in the words of the people who pick it. The name is shown after it, with the key. */
const ROLE_WORDS: Record<string, { title: string; about: string }> = {
  tenant_admin: { title: 'Administrator', about: 'Runs the whole business account: staff, settings, branches and everything else. Sees and does everything in every shop and store, profit and costs included.' },
  branch_manager: { title: 'Branch manager', about: 'Runs one branch day to day: members, loans, stock and sales, and approves what needs a second person.' },
  loan_officer: { title: 'Loan officer', about: 'Registers members and handles loan applications and repayments.' },
  cashier: { title: 'Cashier', about: 'Takes and records money at the counter: repayments, deposits and withdrawals.' },
  accountant: { title: 'Accountant', about: 'Reads the books and reports, and keeps the accounts and periods.' },
  auditor: { title: 'Auditor', about: 'Can look at everything but change nothing.' },
  retail_sales: { title: 'Sales person', about: 'Sells in their shops, checks stock and takes buyers\' payments. Sees their own sales only, and no costs, profit or cash book.' },
  retail_storekeeper: { title: 'Storekeeper', about: 'Moves stock between the stores and shops, receives it, counts it and reports damage. Sees no prices and no money, and does not sell.' },
  retail_purchasing: { title: 'Purchasing', about: 'Records purchases and restocks at their shops and stores, and sees the buying prices of what they buy. Give it on top of another role.' },
  retail_till: { title: 'Can close the till and do the banking', about: 'Sees the cash book of their shops: the day\'s takings, the savings and the banking. Give it on top of another role.' },
  retail_supervisor: { title: 'Supervisor', about: 'Sees every seller\'s sales and totals, the dashboard and the sales analysis at their shops. No profit, costs, staff or Controls.' },
};

/** The role that closes the till and does the banking (ADR-055): given on top of another role, so the invitation offers it as a tick box. */
export const TILL = 'retail_till';

/** The roles given on top of another one, as tick boxes on the invitation: buying, and the till. */
export const ADD_ONS = ['retail_purchasing', 'retail_till'] as const;

/**
 * "Who can do what": one plain line per role the business can give, from the same words as the pick-list, so the card and the
 * choices never disagree. The administrator first; roles the business does not have (retail without the module) are left out.
 */
export function whoCanDoWhat(roles: StaffRole[], modules?: string[] | null): { title: string; about: string }[] {
  const lines = roleChoices(roles, modules).map((c) => ({ title: c.label, about: c.about }));
  // The Owner is one administrator, not a role of its own (ADR-062), so its line comes first, before the role lines.
  return lines.length === 0 ? lines : [OWNER_WORDS, ...lines];
}

// --- The Owner of the business (ADR-062) ---------------------------------------------------------

/** The Owner in the words of the "Who can do what" card. */
export const OWNER_WORDS = {
  title: 'Owner',
  about: 'One administrator of the business who sees and does everything an administrator does, and is the only one who can add, remove or change an administrator. Nobody can remove the owner; the owner can hand ownership to another administrator.',
};

type Person = Pick<StaffUser, 'id' | 'status' | 'roles' | 'is_owner'>;

export const isAdministrator = (user: Pick<StaffUser, 'roles'>): boolean => (user.roles ?? []).some((r) => r.role_key === TENANT_ADMIN);

/** The business has an Owner. While it has none (an older business with no active administrator), the administrators keep the rules of before. */
export const hasOwner = (people: Pick<StaffUser, 'is_owner'>[]): boolean => people.some((p) => p.is_owner === true);

/** Adding, removing or changing an administrator: the Owner only (or every administrator while the business has no Owner). */
export function mayChangeAdministrators(me: Pick<Me, 'permissions' | 'is_owner'>, people: Pick<StaffUser, 'is_owner'>[]): boolean {
  return canManageUsers(me) && (me.is_owner === true || !hasOwner(people));
}

/**
 * Whether the card of a person offers the administrator's actions (Send a new link, Change roles, Deactivate). The server refuses the
 * rest with 403 `owner_only` and `owner_protected`; this only hides what cannot work. One's own card keeps its actions.
 */
export function canActOn(me: Pick<Me, 'permissions' | 'is_owner' | 'user_id'>, user: Person, people: Pick<StaffUser, 'is_owner'>[]): boolean {
  if (!canManageUsers(me)) return false;
  if (user.id === me.user_id) return true;
  if (user.is_owner === true) return false;
  return isAdministrator(user) ? mayChangeAdministrators(me, people) : true;
}

/**
 * The role choices the signed-in person may give: the administrator role only when they may change administrators. `people` is
 * null while the staff list is loading: then only the Owner is offered the administrator role, so it never flashes for others.
 */
export function offeredChoices(choices: RoleChoice[], me: Pick<Me, 'permissions' | 'is_owner'>, people: Pick<StaffUser, 'is_owner'>[] | null): RoleChoice[] {
  const may = people === null ? canManageUsers(me) && me.is_owner === true : mayChangeAdministrators(me, people);
  return may ? choices : choices.filter((c) => c.key !== TENANT_ADMIN);
}

/** Who ownership can go to: the other active administrators, offered to the Owner only. */
export function ownershipTargets<P extends Person>(me: Pick<Me, 'is_owner' | 'user_id'>, people: P[]): P[] {
  if (me.is_owner !== true) return [];
  return people.filter((p) => p.id !== me.user_id && p.status === 'active' && isAdministrator(p));
}

/** The role that records purchases and restocks (ADR-055): given on top of another role, so the invitation offers it as a tick box. */
export const PURCHASING = 'retail_purchasing';

export interface RoleChoice {
  key: string;
  /** "Sales person": the role's name in plain words. The key stays in the docs and the tours, not on a small phone screen. */
  label: string;
  /** One plain sentence on what the role may do. */
  about: string;
}

/** A role as the pick-list shows it. A role this screen has no words for still appears, under the name the server gives it. */
export function roleChoice(role: Pick<StaffRole, 'key' | 'name'>): RoleChoice {
  const key = role.key ?? '';
  const words = ROLE_WORDS[key];
  return {
    key,
    label: words?.title ?? role.name ?? key,
    about: words?.about ?? 'Ask the platform operator what this role may do.',
  };
}

/**
 * The pick-list: administrators first, then the others as the server lists them. The retail roles are left out for a business without
 * the retail module (`modules` from /me; when it is not known, every role is shown and the server decides).
 */
export function roleChoices(roles: StaffRole[], modules?: string[] | null): RoleChoice[] {
  const retail = !modules || modules.includes('retail');
  const choices = roles.filter((r) => r.key && (retail || !r.key.startsWith('retail_'))).map(roleChoice);
  return [...choices.filter((c) => c.key === TENANT_ADMIN), ...choices.filter((c) => c.key !== TENANT_ADMIN)];
}

// --- The invite form -------------------------------------------------------------------------------

export interface InviteDraft {
  fullName: string;
  phone: string;
  email: string;
  roleKey: string;
  /** The branches the person works in, or [EVERY_BRANCH]; empty means none chosen yet. Ignored for the administrator. */
  branchIds: string[];
  /** Also records purchases and restocks there (the Purchasing role, ADR-055). */
  purchasing: boolean;
  /** Also closes the till and does the banking there (the till role, ADR-055). */
  till?: boolean;
}

export const emptyInvite = (): InviteDraft => ({ fullName: '', phone: '', email: '', roleKey: '', branchIds: [], purchasing: false });

export type InviteProblems = Partial<Record<'fullName' | 'contact' | 'phone' | 'email' | 'roleKey' | 'branches', string>>;

/**
 * The choice "every branch" (every shop and store), kept apart from "none chosen yet" so that taking off a person's last branch never
 * widens them to every branch (ADR-055): an empty choice cannot be saved.
 */
export const EVERY_BRANCH = '*';

/** A role other than the administrator needs a branch, or every branch, chosen. */
export const needsBranches = (roleKey: string, branchIds: string[]): boolean =>
  roleKey !== '' && roleKey !== TENANT_ADMIN && branchIds.filter((b) => b !== '').length === 0;

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function emailProblem(raw: string): string | null {
  const text = raw.trim();
  if (text === '') return null;
  return EMAIL.test(text) ? null : 'Check the email address: it looks like name@example.com.';
}

/** What is wrong with the form, in plain words; empty when it can be sent. */
export function inviteProblems(draft: InviteDraft): InviteProblems {
  const out: InviteProblems = {};
  if (draft.fullName.trim() === '') out.fullName = 'Type the person\'s full name.';
  const phone = draft.phone.trim() === '' ? null : phoneProblem(draft.phone);
  const email = emailProblem(draft.email);
  if (phone) out.phone = phone;
  if (email) out.email = email;
  if (draft.phone.trim() === '' && draft.email.trim() === '') out.contact = 'Give a phone number or an email address, so the person can be reached.';
  if (draft.roleKey === '') out.roleKey = 'Choose what this person will do.';
  if (needsBranches(draft.roleKey, draft.branchIds)) out.branches = 'Tick where this person works, or every one.';
  return out;
}

export const hasProblems = (p: InviteProblems): boolean => Object.keys(p).length > 0;

/** The roles to send: the administrator covers every branch; any other role may be limited to one branch. */
export function assignmentFor(roleKey: string, branchId: string): RoleAssignmentRequest {
  return roleKey === TENANT_ADMIN || branchId === '' ? { role_key: roleKey } : { role_key: roleKey, branch_id: branchId };
}

/**
 * One role in the shops and stores the person works in: one assignment per branch, the way the server scopes a role (ADR-017, ADR-055).
 * The administrator, and no branch ticked, mean every shop and store.
 */
export function assignmentsFor(roleKey: string, branchIds: string[]): RoleAssignmentRequest[] {
  const ids = [...new Set(branchIds.filter((b) => b !== ''))];
  if (roleKey === TENANT_ADMIN || ids.includes(EVERY_BRANCH)) return [{ role_key: roleKey }];
  return ids.map((b) => assignmentFor(roleKey, b));
}

/** Whether the invitation may add Purchasing: a role that is neither the administrator (who has it) nor Purchasing itself. */
export const purchasingOffered = (roleKey: string, roles: Pick<StaffRole, 'key'>[], addOn: string = PURCHASING): boolean =>
  roleKey !== '' && roleKey !== TENANT_ADMIN && !(ADD_ONS as readonly string[]).includes(roleKey) && roles.some((r) => r.key === addOn);

/** The request body. A phone is written as the server would store it, so what is sent is what is checked here. */
export function inviteBody(draft: InviteDraft): InviteUserBody {
  const roles = assignmentsFor(draft.roleKey, draft.branchIds);
  if (draft.purchasing && draft.roleKey !== TENANT_ADMIN && draft.roleKey !== PURCHASING) roles.push(...assignmentsFor(PURCHASING, draft.branchIds));
  if (draft.till && draft.roleKey !== TENANT_ADMIN && draft.roleKey !== TILL) roles.push(...assignmentsFor(TILL, draft.branchIds));
  const body: InviteUserBody = { full_name: draft.fullName.trim(), roles };
  if (draft.phone.trim() !== '') body.phone = normaliseUganda(draft.phone) ?? draft.phone.trim();
  if (draft.email.trim() !== '') body.email = draft.email.trim();
  return body;
}

// --- Reading the list ------------------------------------------------------------------------------

export const STATUS_WORDS: Record<string, string> = { invited: 'Invited, not joined yet', active: 'Active', deactivated: 'Deactivated' };
export const statusWords = (status: string | undefined): string => STATUS_WORDS[status ?? ''] ?? (status ?? '');

export function branchLabel(branchId: string | undefined, branches: StaffBranch[]): string {
  if (!branchId) return 'every branch';
  const found = branches.find((b) => b.id === branchId);
  if (!found) return 'one branch';
  const name = found.name ?? found.code ?? 'one branch';
  return found.sells_goods === false ? `${name} (store)` : name;
}

/** A user's roles, one row per role with the shops and stores it applies in, in the order the server lists them. */
export interface RoleRow {
  roleKey: string;
  /** [EVERY_BRANCH] for every branch; empty means none chosen yet. */
  branchIds: string[];
}

export function roleRows(user: Pick<StaffUser, 'roles'>): RoleRow[] {
  const rows: RoleRow[] = [];
  for (const r of user.roles ?? []) {
    const key = r.role_key ?? '';
    const row = rows.find((x) => x.roleKey === key);
    const every = !r.branch_id;
    if (!row) rows.push({ roleKey: key, branchIds: every ? [EVERY_BRANCH] : [r.branch_id ?? ''] });
    else if (every) row.branchIds = [EVERY_BRANCH];
    else if (!row.branchIds.includes(EVERY_BRANCH)) row.branchIds.push(r.branch_id ?? '');
  }
  return rows;
}

/** "Sales person, Kampala branch" per role of the user; a role in several shops and stores names them all on one line. */
export function roleLines(user: StaffUser, branches: StaffBranch[], roles: StaffRole[]): string[] {
  return roleRows(user).map((r) => {
    const found = roles.find((x) => x.key === r.roleKey);
    const assigned = (user.roles ?? []).find((x) => x.role_key === r.roleKey);
    const name = ROLE_WORDS[r.roleKey]?.title ?? found?.name ?? assigned?.role_name ?? r.roleKey;
    const where = r.branchIds.includes(EVERY_BRANCH) ? branchLabel(undefined, branches) : r.branchIds.map((b) => branchLabel(b, branches)).join(', ');
    return `${name}, ${where}`;
  });
}

/** The contact line of a user: the phone first (phone first is how most staff are reached), written to read, then the email. */
export function contactLine(user: StaffUser): string {
  return [user.phone_e164 ? showPhone(user.phone_e164) : null, user.email].filter(Boolean).join(' · ');
}

/**
 * Whether a typed search finds a person: their name or email, or their phone however it is typed (0700 000 002, +256700000002,
 * 700000002 or just the last digits). The browser filters the list, since a business has a handful of staff.
 */
export function matchesSearch(user: StaffUser, rawTerm: string): boolean {
  const term = foldText(rawTerm);
  if (term === '') return true;
  if (foldText(`${user.full_name ?? ''} ${user.email ?? ''}`).includes(term)) return true;
  const stored = user.phone_e164 ?? '';
  if (stored === '') return false;
  if (normaliseUganda(rawTerm) === stored) return true;
  const typed = rawTerm.replace(/\D/g, '');
  if (typed.length < 3) return false;
  const international = stored.replace(/\D/g, '');
  return international.includes(typed) || `0${international.slice(3)}`.includes(typed);
}

/** A role's branch that is closed, or no longer on the list of branches: it cannot be kept when the roles are saved. */
export function branchUnavailable(branchId: string, branches: StaffBranch[]): boolean {
  if (branchId === '' || branchId === EVERY_BRANCH || branches.length === 0) return false;
  return !branches.some((b) => b.id === branchId && (b.status ?? 'active') === 'active');
}

// --- The one-time link -----------------------------------------------------------------------------

// --- Reset someone's sign-in (issue #339; FR-IAM-15, ADR-042) --------------------------------------

/**
 * The Reset sign-in action: an administrator, for an active person who is not an administrator and not themselves. The server
 * refuses the others (409 `admin_reset_by_operator`, `cannot_reset_own_sign_in`, `invalid_status_transition`); this only hides
 * the button, so nobody is offered a step that cannot work.
 */
export function canResetSignIn(me: Pick<Me, 'permissions' | 'user_id'>, user: Pick<StaffUser, 'id' | 'status' | 'roles'>): boolean {
  return canManageUsers(me) && user.status === 'active' && user.id !== me.user_id && !(user.roles ?? []).some((r) => r.role_key === TENANT_ADMIN);
}

/**
 * What the administrator must type to prove it is still them: the 6 digits of their own authenticator app when they have two-step
 * sign-in (`me.mfa_enabled`), otherwise their own current password. null when it can be sent.
 */
export function resetProofProblem(mfaEnabled: boolean, typed: string): string | null {
  if (mfaEnabled) return /^\d{6}$/.test(typed.trim()) ? null : 'Type the 6 digits from your authenticator app.';
  return typed === '' ? 'Type your own password.' : null;
}

/** The expiry in the reader's own words, for example "Fri 23 Oct 2026, 14:30". */
export function expiryWords(iso: string | undefined): string {
  const d = iso ? new Date(iso) : null;
  return d && !Number.isNaN(d.getTime()) ? d.toLocaleString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : 'soon';
}

/** The WhatsApp link with the message already typed. With a phone the chat opens on that person; without one, WhatsApp asks who. */
export function whatsappLink(opts: { phone?: string | null; name: string; business: string; url: string; expiresAt?: string; reset?: boolean }): string {
  const first = opts.name.trim().split(/\s+/)[0] || 'there';
  const intro = opts.reset ? `Hello ${first}, here is your link to choose a new password for ${opts.business}.` : `Hello ${first}, you have been invited to join ${opts.business}.`;
  const text = `${intro} Open this link to ${opts.reset ? 'choose it' : 'set up your sign-in'}: ${opts.url} . It works once and stops working on ${expiryWords(opts.expiresAt)}.`;
  const digits = (opts.phone ?? '').replace(/\D/g, '');
  return `https://wa.me/${digits}?text=${encodeURIComponent(text)}`;
}
