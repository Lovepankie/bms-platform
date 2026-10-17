import type { Me } from '../../../api/client';
import { branchesWhere } from '../retail/permissions';

// Which member and collateral screens and actions a session is offered (issue #280). The API is the
// authority (it checks the permission and the branch on every call); this only decides what the menu and
// the pages show, from the /me permissions, their branch scopes and modules. Each name is the permission
// its route declares (docs/sdd/08-security-design.md, chapter 7 sections 7.11.11 and 7.11.14).

export const MEMBERS_READ = 'lending.members.read';
export const MEMBERS_CREATE = 'lending.members.create';
export const MEMBERS_UPDATE = 'lending.members.update';
export const MEMBERS_VERIFY = 'lending.members.verify_kyc';
export const COLLATERAL_READ = 'lending.collateral.read';
export const COLLATERAL_MANAGE = 'lending.collateral.manage';
export const COLLATERAL_RELEASE = 'lending.collateral.release_request';

type Session = Pick<Me, 'permissions' | 'permission_scopes'> & { modules?: string[] };

const held = (me: Pick<Me, 'permissions'>): string[] => me.permissions ?? [];
const hasLending = (me: Session): boolean => !me.modules || me.modules.includes('lending');

/**
 * Whether the session holds `permission`, and, given a branch, holds it there. A payload without scopes counts as every
 * branch (as `branchesWhere` does); a permission held with a list of branches counts only for those.
 */
export function holds(me: Session, permission: string, branchId?: string): boolean {
  if (!held(me).includes(permission)) return false;
  if (branchId === undefined || !me.permission_scopes) return true;
  const scope = me.permission_scopes[permission];
  if (!scope) return false;
  return !!scope.all_branches || (scope.branch_ids ?? []).includes(branchId);
}

/** The Members menu entry: the user may read members, and the tenant has lending when /me says. */
export const showMembers = (me: Session): boolean => hasLending(me) && holds(me, MEMBERS_READ);
/** The Collateral menu entry. */
export const showCollateral = (me: Session): boolean => hasLending(me) && holds(me, COLLATERAL_READ);

/** Add a member: anywhere the permission reaches, or in `branchId` when given. */
export const mayCreateMember = (me: Session, branchId?: string): boolean => hasLending(me) && holds(me, MEMBERS_READ) && holds(me, MEMBERS_CREATE, branchId);
export const mayEditMember = (me: Session, branchId?: string): boolean => hasLending(me) && holds(me, MEMBERS_READ) && holds(me, MEMBERS_UPDATE, branchId);
export const mayVerifyKyc = (me: Session, branchId?: string): boolean => hasLending(me) && holds(me, MEMBERS_READ) && holds(me, MEMBERS_VERIFY, branchId);
export const mayManageCollateral = (me: Session, branchId?: string): boolean => hasLending(me) && holds(me, COLLATERAL_READ) && holds(me, COLLATERAL_MANAGE, branchId);
export const mayReleaseCollateral = (me: Session, branchId?: string): boolean => hasLending(me) && holds(me, COLLATERAL_READ) && holds(me, COLLATERAL_RELEASE, branchId);

/** The branches a new member may be added to: those where the user holds the create permission. */
export function memberBranches<B extends { id?: string }>(me: Pick<Me, 'permission_scopes'> & { branches?: B[] }): B[] {
  return branchesWhere(me, MEMBERS_CREATE);
}
