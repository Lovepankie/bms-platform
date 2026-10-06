import type { Me } from '../../../api/client';

// Which loan actions a session is offered. The API is the authority (it checks the permission
// and the loan's status on every call); this only decides what the menu and the loan page show,
// from the /me permissions and the loan's status. Each action names the permission its API route
// declares (docs/sdd/08-security-design.md, chapter 7 section 7.11.13).

export const LOANS_READ = 'lending.loans.read';

export type LoanAction = 'repay' | 'disburse' | 'payoff' | 'reverse' | 'write_off';

const NEEDS: Record<LoanAction, string> = {
  repay: 'lending.repayments.create',
  disburse: 'lending.disbursements.request',
  payoff: LOANS_READ,
  reverse: 'lending.repayments.reverse_request',
  write_off: 'lending.loans.write_off_request',
};

// The loan statuses each action applies to, as the server checks them. A repayment on a
// written-off loan is recorded as a recovery.
const STATUSES: Record<LoanAction, string[]> = {
  repay: ['active', 'written_off'],
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
  const order: LoanAction[] = ['disburse', 'repay', 'payoff', 'write_off'];
  return order.filter((a) => mayDo(me, a) && STATUSES[a].includes(status ?? ''));
}

/**
 * Whether a transaction gets a Reverse button: a repayment (on an active or closed loan) or a
 * recovery, not already reversed, for a user with lending.repayments.reverse_request.
 */
export function canReverse(
  me: Pick<Me, 'permissions'>,
  loanStatus: string | undefined,
  txn: { txn_type?: string; reversed_by_txn_id?: string },
): boolean {
  if (!mayDo(me, 'reverse') || txn.reversed_by_txn_id) return false;
  if (txn.txn_type === 'recovery') return true;
  return txn.txn_type === 'repayment' && ['active', 'closed'].includes(loanStatus ?? '');
}
