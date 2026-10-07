import type { LoanAllocation } from '../../../api/lending';
import { formatMinor, parseMinor } from '../../../components/money';

// Pure helpers of the loan screens: money in the loan's currency, today's date, words for codes,
// and the allocation of a repayment summed by component. Amounts stay integer minor units
// (ADR-004); the server computes every figure, these only present it.

/** The currency of a loan, UGX when the server sent none (the pilot lends in shillings). */
export const currencyOf = (c: string | undefined): string => c || 'UGX';

/** Minor units as money, or a dash-free placeholder when the server sent no figure. */
export function money(minor: number | undefined, currency: string | undefined): string {
  if (minor === undefined || minor === null) return 'None';
  try {
    return formatMinor(minor, currencyOf(currency));
  } catch {
    return `${currencyOf(currency)} ${minor}`;
  }
}

/** What a person typed as an amount, in minor units above zero, or null. */
export function parseAmount(text: string, currency: string | undefined): number | null {
  let minor: number | null;
  try {
    minor = parseMinor(text, currencyOf(currency));
  } catch {
    return null;
  }
  return minor !== null && minor > 0 ? minor : null;
}

/** The local calendar date as YYYY-MM-DD (not UTC, so late evening is still today). */
export function localDate(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

export const isIsoDate = (s: string): boolean => /^\d{4}-\d{2}-\d{2}$/.test(s);

/** "written_off" becomes "Written off". */
export function words(code: string | undefined): string {
  if (!code) return '';
  const text = code.replace(/_/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

const TXN_WORDS: Record<string, string> = {
  disbursement: 'Disbursement',
  fee_upfront: 'Upfront fee',
  repayment: 'Repayment',
  recovery: 'Recovery',
  reversal: 'Reversal',
  waiver: 'Waiver',
  write_off: 'Write-off',
  credit_refund: 'Credit refund',
  credit_to_savings: 'Credit to savings',
};

export const txnWords = (type: string | undefined): string => (type ? TXN_WORDS[type] ?? words(type) : '');

const METHOD_WORDS: Record<string, string> = { cash: 'Cash', bank: 'Bank', mtn_momo: 'MTN Mobile Money', airtel_money: 'Airtel Money' };

export const methodWords = (key: string | undefined): string => (key ? METHOD_WORDS[key] ?? words(key) : '');

/** The badge class for a loan status: state is in the words too, colour only helps. */
export function statusBadge(status: string | undefined): string {
  switch (status) {
    case 'active':
      return 'badge badge-info';
    case 'closed':
      return 'badge badge-success';
    case 'written_off':
    case 'rejected':
      return 'badge badge-danger';
    case 'approved':
      return 'badge badge-warning';
    default:
      return 'badge';
  }
}

export type Component = 'penalty' | 'fee' | 'interest' | 'principal' | 'overpayment';

const COMPONENTS: { component: Component; label: string }[] = [
  { component: 'penalty', label: 'Penalties' },
  { component: 'fee', label: 'Fees' },
  { component: 'interest', label: 'Interest' },
  { component: 'principal', label: 'Principal' },
  { component: 'overpayment', label: 'Held as credit (overpayment)' },
];

/**
 * A repayment's allocation rows summed by component, in the allocation order (penalties, fees,
 * interest, principal, then any overpayment held as credit). Components with nothing are left
 * out; an unknown component keeps its own name, so nothing the server allocated is hidden.
 */
export function allocationSummary(rows: LoanAllocation[] | undefined): { label: string; amountMinor: number }[] {
  const sums = new Map<string, number>();
  for (const r of rows ?? []) {
    const c = r.component ?? 'other';
    sums.set(c, (sums.get(c) ?? 0) + (r.amount_minor ?? 0));
  }
  const known = COMPONENTS.filter((c) => (sums.get(c.component) ?? 0) !== 0).map((c) => ({ label: c.label, amountMinor: sums.get(c.component) ?? 0 }));
  const others = [...sums.entries()]
    .filter(([c, v]) => v !== 0 && !COMPONENTS.some((k) => k.component === c))
    .map(([c, v]) => ({ label: words(c), amountMinor: v }));
  return [...known, ...others];
}
