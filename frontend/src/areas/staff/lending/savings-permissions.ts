import type { Me } from '../../../api/client';

// Which savings actions a session is offered. The API is the authority (it checks the permission
// and the account's status on every call); this only decides what the menu and the account page
// show, from the /me permissions and the account's status. Each action names the permission its
// route declares (chapter 7 section 7.11.15, chapter 8).

export const SAVINGS_READ = 'lending.savings.read';

export type SavingsAction = 'deposit' | 'withdraw' | 'close' | 'freeze' | 'unfreeze' | 'reactivate';

const NEEDS: Record<SavingsAction, string> = {
  deposit: 'lending.savings.deposit',
  withdraw: 'lending.savings.withdraw',
  close: 'lending.savings.withdraw',
  freeze: 'lending.savings.withdraw_approve',
  unfreeze: 'lending.savings.withdraw_approve',
  reactivate: 'lending.savings.withdraw_approve',
};

// The account statuses each action applies to, as the server checks them. Deposits land on a
// dormant or frozen account too; money leaves only an active one.
const STATUSES: Record<SavingsAction, string[]> = {
  deposit: ['active', 'dormant', 'frozen'],
  withdraw: ['active'],
  close: ['active'],
  freeze: ['active', 'dormant'],
  unfreeze: ['frozen'],
  reactivate: ['dormant'],
};

const held = (me: Pick<Me, 'permissions'>): string[] => me.permissions ?? [];

/** The Savings menu entry: the user may read savings, and the tenant has lending when /me says. */
export function showSavings(me: Pick<Me, 'permissions'> & { modules?: string[] }): boolean {
  if (me.modules && !me.modules.includes('lending')) return false;
  return held(me).includes(SAVINGS_READ);
}

export function maySave(me: Pick<Me, 'permissions'>, action: SavingsAction): boolean {
  const p = held(me);
  return p.includes(SAVINGS_READ) && p.includes(NEEDS[action]);
}

/** The actions offered on an account in `status`, in the order the page shows them. */
export function savingsActionsFor(me: Pick<Me, 'permissions'>, status: string | undefined): SavingsAction[] {
  const order: SavingsAction[] = ['deposit', 'withdraw', 'reactivate', 'unfreeze', 'freeze', 'close'];
  return order.filter((a) => maySave(me, a) && STATUSES[a].includes(status ?? ''));
}

export const mayOpenAccounts = (me: Pick<Me, 'permissions'>): boolean =>
  held(me).includes(SAVINGS_READ) && held(me).includes('lending.savings.open');

export const mayManageProducts = (me: Pick<Me, 'permissions'>): boolean => held(me).includes('lending.savings_products.manage');

/** A deposit or a withdrawal not yet reversed, on an account that is not closed. */
export function canReverseSavings(
  me: Pick<Me, 'permissions'>,
  status: string | undefined,
  txn: { txn_type?: string; reversed_by_txn_id?: string },
): boolean {
  if (!maySave(me, 'withdraw') || status === 'closed' || txn.reversed_by_txn_id) return false;
  return txn.txn_type === 'deposit' || txn.txn_type === 'withdrawal';
}
