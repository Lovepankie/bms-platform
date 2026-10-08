import type { Me } from '../../../api/client';
import type { Investment, InvestmentTransaction } from '../../../api/investments';
import { words } from './loan-state';

// Which investment actions a session is offered, and words for the investment codes. The API is
// the authority (it checks the permission, the status and the amounts on every call); this only
// decides what the menu and the investment page show, from the /me permissions and the
// investment as the server sent it. Each action names the permission its route declares
// (docs/sdd/08-security-design.md, chapter 7 section 7.11.16).

export const INVESTMENTS_READ = 'lending.investments.read';
export const INVESTMENTS_OPEN = 'lending.investments.open';
export const PRODUCTS_MANAGE = 'lending.investment_products.manage';

export type InvestmentAction = 'fund' | 'pay_return' | 'payout' | 'rollover' | 'early' | 'instruct';

const NEEDS: Record<InvestmentAction, string> = {
  fund: 'lending.investments.fund',
  pay_return: 'lending.investments.payout',
  payout: 'lending.investments.payout',
  rollover: 'lending.investments.payout',
  early: 'lending.investments.payout',
  instruct: INVESTMENTS_OPEN,
};

const held = (me: Pick<Me, 'permissions'>): string[] => me.permissions ?? [];

/** The Investments menu entry: the user may read investments, and the tenant has lending when /me says. */
export function showInvestments(me: Pick<Me, 'permissions'> & { modules?: string[] }): boolean {
  if (me.modules && !me.modules.includes('lending')) return false;
  return held(me).includes(INVESTMENTS_READ);
}

export function may(me: Pick<Me, 'permissions'>, permission: string): boolean {
  return held(me).includes(permission);
}

/** Whether the action applies to the investment as it stands, before any permission. */
function applies(action: InvestmentAction, inv: Investment): boolean {
  const status = inv.status ?? '';
  switch (action) {
    case 'fund':
      return status === 'pending_funding' && !inv.pending_approval_id;
    case 'pay_return':
      return ['active', 'matured', 'rolled_over'].includes(status) && (inv.return_available_minor ?? 0) > 0;
    case 'payout':
    case 'rollover':
      return status === 'matured';
    case 'early':
      return status === 'active' && inv.early_withdrawal_allowed === true && !inv.pending_approval_id;
    case 'instruct':
      return ['pending_funding', 'active', 'matured'].includes(status);
  }
}

/** The actions offered on an investment, in the order the page shows them. */
export function actionsFor(me: Pick<Me, 'permissions'>, inv: Investment): InvestmentAction[] {
  const order: InvestmentAction[] = ['fund', 'pay_return', 'payout', 'rollover', 'early', 'instruct'];
  return order.filter((a) => may(me, INVESTMENTS_READ) && may(me, NEEDS[a]) && applies(a, inv));
}

/** A Reverse button: a funding, a return payout or a maturity payout not yet reversed (the server checks the rest). */
export function canReverse(me: Pick<Me, 'permissions'>, txn: Pick<InvestmentTransaction, 'txn_type' | 'reversed_by_txn_id'>): boolean {
  if (!may(me, 'lending.investments.payout') || txn.reversed_by_txn_id) return false;
  return ['funding', 'return_payout', 'maturity_payout'].includes(txn.txn_type ?? '');
}

/** The badge class for an investment status: the state is in the words, colour only helps. */
export function investmentBadge(status: string | undefined): string {
  switch (status) {
    case 'active':
      return 'badge badge-info';
    case 'matured':
      return 'badge badge-warning';
    case 'paid_out':
    case 'rolled_over':
      return 'badge badge-success';
    case 'withdrawn_early':
    case 'cancelled':
      return 'badge badge-danger';
    default:
      return 'badge';
  }
}

const TXN_WORDS: Record<string, string> = {
  funding: 'Funding',
  return_accrual: 'Return accrued',
  return_payout: 'Return paid',
  maturity_payout: 'Paid out at maturity',
  early_withdrawal: 'Early withdrawal',
  rollover_out: 'Rolled over',
  rollover_in: 'Funded by rollover',
  reversal: 'Reversal',
};

export const investmentTxnWords = (type: string | undefined): string => (type ? TXN_WORDS[type] ?? words(type) : '');

const INSTRUCTION_WORDS: Record<string, string> = {
  payout: 'Pay out at maturity',
  rollover_principal: 'Roll over the principal, pay the return',
  rollover_all: 'Roll over principal and return',
};

export const instructionWords = (code: string | undefined): string =>
  code ? INSTRUCTION_WORDS[code] ?? words(code) : 'Not given yet';

const PAYOUT_WORDS: Record<string, string> = { at_maturity: 'At maturity', monthly: 'Monthly', quarterly: 'Quarterly' };

export const payoutWords = (code: string | undefined): string => (code ? PAYOUT_WORDS[code] ?? words(code) : '');

const BUCKET_WORDS: Record<string, string> = {
  overdue: 'Matured, not yet paid',
  '0_7': 'Within 7 days',
  '8_30': '8 to 30 days',
  '31_90': '31 to 90 days',
};

export const bucketWords = (bucket: string | undefined): string => (bucket ? BUCKET_WORDS[bucket] ?? bucket : '');

/** Basis points as a percentage with up to two decimals: 1250 is "12.5%". */
export function percent(bp: number | undefined): string {
  if (bp === undefined || bp === null) return 'None';
  const whole = Math.trunc(bp / 100);
  const frac = Math.abs(bp % 100);
  if (frac === 0) return `${whole}%`;
  return `${whole}.${String(frac).padStart(2, '0').replace(/0$/, '')}%`;
}

/** A percentage typed by a person, as basis points (12.5 is 1250), or null. */
export function parsePercent(text: string): number | null {
  const t = text.trim();
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(t)) return null;
  const [w, f = ''] = t.split('.');
  return Number(w) * 100 + Number(f.padEnd(2, '0'));
}
