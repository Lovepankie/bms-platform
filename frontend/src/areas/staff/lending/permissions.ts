import type { Me } from '../../../api/client';
import { holds, MEMBERS_READ } from './member-permissions';

// Which loan actions a session is offered. The API is the authority (it checks the permission
// and the loan's status on every call); this only decides what the menu and the loan page show,
// from the /me permissions and the loan's status. Each action names the permission its API route
// declares (docs/sdd/08-security-design.md, chapter 7 section 7.11.13). Collecting by mobile money or card (issue #216) is
// the payments permission `core.payments.collect`; whether the business has switched collections on and connected its own
// account is the server's word, given as a plain refusal when the form is sent.

export const LOANS_READ = 'lending.loans.read';

export type LoanAction = 'repay' | 'collect' | 'disburse' | 'payoff' | 'reverse' | 'write_off';

const NEEDS: Record<LoanAction, string> = {
  repay: 'lending.repayments.create',
  collect: 'core.payments.collect',
  disburse: 'lending.disbursements.request',
  payoff: LOANS_READ,
  reverse: 'lending.repayments.reverse_request',
  write_off: 'lending.loans.write_off_request',
};

// The loan statuses each action applies to, as the server checks them. A repayment on a
// written-off loan is recorded as a recovery.
const STATUSES: Record<LoanAction, string[]> = {
  repay: ['active', 'written_off'],
  collect: ['active'],
  disburse: ['approved'],
  payoff: ['active'],
  reverse: ['active', 'closed', 'written_off'],
  write_off: ['active'],
};

const held = (me: Pick<Me, 'permissions'>): string[] => me.permissions ?? [];

/** The Loans menu entry: the user may read loans, and the tenant has lending when /me says. */
export function showLending(me: Pick<Me, 'permissions'> & { modules?: string[] }): boolean {
  if (me.modules && !me.modules.includes('lending')) return false;
  return held(me).includes(LOANS_READ);
}

/** The session holds the permission for the action (whatever the loan's status). */
export function mayDo(me: Pick<Me, 'permissions'>, action: LoanAction): boolean {
  const p = held(me);
  return p.includes(LOANS_READ) && p.includes(NEEDS[action]);
}

/** The actions offered on a loan in `status`, in the order the page shows them. */
export function actionsFor(me: Pick<Me, 'permissions'>, status: string | undefined): LoanAction[] {
  const order: LoanAction[] = ['disburse', 'repay', 'collect', 'payoff', 'write_off'];
  return order.filter((a) => mayDo(me, a) && STATUSES[a].includes(status ?? ''));
}

/**
 * Whether a transaction gets a Reverse button: a repayment (on an active or closed loan) or a
 * recovery, not already reversed, for a user with lending.repayments.reverse_request.
 */
export function canReverse(
  me: Pick<Me, 'permissions'>,
  loanStatus: string | undefined,
  txn: { txn_type?: string; reversed_by_txn_id?: string; payment_method_key?: string },
): boolean {
  // A payment collected by mobile money is reviewed with the payment provider, not reversed by staff (issue #216, #224).
  if (txn.payment_method_key === 'gateway') return false;
  if (!mayDo(me, 'reverse') || txn.reversed_by_txn_id) return false;
  if (txn.txn_type === 'recovery') return true;
  return txn.txn_type === 'repayment' && ['active', 'closed'].includes(loanStatus ?? '');
}

// --- Loan applications (issue #280 slices 3 and 4) -------------------------------------------------------
// The origination routes of chapter 7 section 7.11.13 and the permission each declares, held in the loan's branch (the
// server checks `principal.may(permission, loan.branch_id)`). Approving is also refused to whoever sent or appraised the
// application (FR-APR-03); the decision screen says so instead of hiding the screen.

export const LOANS_CREATE = 'lending.loans.create';
export const LOANS_APPRAISE = 'lending.loans.appraise';
export const LOANS_APPROVE = 'lending.loans.approve';
export const LOANS_CANCEL = 'lending.loans.cancel';

export type ApplicationStep = 'edit' | 'submit' | 'appraise' | 'decide' | 'send_back' | 'cancel';

type Session = Pick<Me, 'permissions' | 'permission_scopes' | 'user_id'> & { modules?: string[] };

/** Start an application: read loans, find the member (the picker searches members) and create, in some branch. */
export const mayApply = (me: Session): boolean => showLending(me) && holds(me, MEMBERS_READ) && holds(me, LOANS_CREATE);

const STEP_STATUSES: Record<ApplicationStep, string[]> = {
  edit: ['draft'],
  submit: ['draft'],
  appraise: ['submitted', 'appraised'],
  decide: ['appraised'],
  send_back: ['submitted', 'appraised'],
  cancel: ['draft', 'submitted', 'appraised', 'approved'],
};

const STEP_NEEDS: Record<ApplicationStep, string> = {
  edit: LOANS_CREATE,
  submit: LOANS_CREATE,
  appraise: LOANS_APPRAISE,
  decide: LOANS_APPROVE,
  send_back: LOANS_APPROVE,
  cancel: LOANS_CANCEL,
};

/** The application steps offered on a loan, in the order the page shows them. */
export function applicationSteps(me: Session, loan: { status?: string; branch_id?: string; created_by?: string }): ApplicationStep[] {
  const order: ApplicationStep[] = ['submit', 'edit', 'appraise', 'decide', 'send_back', 'cancel'];
  return order.filter((s) => {
    if (!showLending(me) || !STEP_STATUSES[s].includes(loan.status ?? '') || !holds(me, STEP_NEEDS[s], loan.branch_id)) return false;
    // A loan officer cancels their own draft; a holder of lending.loans.approve in the branch cancels any open application.
    if (s === 'cancel') return holds(me, LOANS_APPROVE, loan.branch_id) || (loan.status === 'draft' && !!me.user_id && loan.created_by === me.user_id);
    return true;
  });
}
