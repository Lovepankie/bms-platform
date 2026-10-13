import { RETAIL_CURRENCY, type ReturnLineRequest, type ReturnRequest, type Sale, type SaleLine, type SaleReturn } from '../../../api/retail';
import { formatMinor } from '../../../components/money';
import { milliOf, parseQty, qtyString } from './maths';
import { baseQtyMilli } from './units-state';

const fmt = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY);

// Customer returns, the pure rules (returns capability, ADR-033, issue #201): what a return would credit and where the
// credit goes, shown before anything is sent. The server decides every one of them again against the sale as it stands
// under its lock and refuses the same cases, so these only save a round trip. Quantities are integer thousandths and money
// integer minor units; the credit of a line is its share of what the sale line was charged, rounded half up once, and the
// last piece of a line takes the remainder so the pieces add up to the line exactly.

export const REASONS: { value: string; label: string }[] = [
  { value: 'defective', label: 'Faulty' },
  { value: 'damaged', label: 'Damaged' },
  { value: 'wrong_item', label: 'Wrong item' },
  { value: 'not_as_described', label: 'Not as described' },
  { value: 'unwanted', label: 'Customer does not want it' },
  { value: 'price_error', label: 'Price mistake' },
  { value: 'other', label: 'Another reason' },
];

export const reasonWords = (reason: string | undefined): string => REASONS.find((r) => r.value === reason)?.label ?? reason ?? '';

export type Disposition = 'restock' | 'write_off';
export type RefundChoice = 'cash' | 'mobile_money' | 'bank' | 'account';
export type MoneyMethod = 'cash' | 'mobile_money' | 'bank';

export const DISPOSITION_WORDS: Record<Disposition, string> = { restock: 'Put back on the shelf', write_off: 'Damaged, write it off' };

export const REFUND_WORDS: Record<RefundChoice, string> = {
  cash: 'Pay back in cash',
  mobile_money: 'Pay back by mobile money',
  bank: 'Pay back into the bank',
  account: 'Take it off what they owe',
};

export const MONEY_METHODS: { value: MoneyMethod; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'mobile_money', label: 'Mobile money' },
  { value: 'bank', label: 'Bank' },
];

export interface RowForm {
  /** The quantity returned, in the unit chosen; empty when the line is not coming back. */
  qty: string;
  /** `line` is the unit the line was sold in; `base` the item's own unit. They are the same for a line sold in the base unit. */
  entered: 'line' | 'base';
  reason: string;
  disposition: Disposition;
  restockBranchId: string;
}

export interface ReturnForm {
  /** By sale line id. */
  rows: Record<string, RowForm>;
  /** Empty until the person chooses; then the sale's own default applies (see {@link resolved}). */
  refund: RefundChoice | '';
  moneyMethod: '' | MoneyMethod;
  /** The till money is paid out of; empty means the server's default (the person's own branch when it is one, else the sale's). */
  refundBranchId: string;
  note: string;
}

export const blankRow = (branchId: string): RowForm => ({ qty: '', entered: 'line', reason: 'defective', disposition: 'restock', restockBranchId: branchId });

export const blankReturn = (): ReturnForm => ({ rows: {}, refund: '', moneyMethod: '', refundBranchId: '', note: '' });

/** Whether the sale can be credited to the account: the rule the server applies (a credit sale, not imported, still owing). */
export const accountRefundAllowed = (sale: Pick<Sale, 'payment_method' | 'balance_minor' | 'historical'>): boolean =>
  sale.payment_method === 'credit' && sale.historical !== true && (sale.balance_minor ?? 0) > 0;

/** A credit sale with something still owing is credited to the account by default; every other sale is paid back in cash. */
export function defaultRefund(sale: Pick<Sale, 'payment_method' | 'balance_minor' | 'historical'>): RefundChoice {
  return accountRefundAllowed(sale) ? 'account' : 'cash';
}

/** The form with the refund the person chose, or the sale's default when they have not chosen. */
export const resolved = (sale: Pick<Sale, 'payment_method' | 'balance_minor' | 'historical'>, form: ReturnForm): ReturnForm & { refund: RefundChoice } => ({
  ...form,
  refund: form.refund === '' ? defaultRefund(sale) : form.refund,
});

/** What has already come back of a sale, from its returns that were not voided. */
export interface Back {
  byLine: Map<string, { qtyMilli: number; totalMinor: number }>;
  refundedMinor: number;
  creditedMinor: number;
}

export function backOf(returns: readonly SaleReturn[]): Back {
  const back: Back = { byLine: new Map(), refundedMinor: 0, creditedMinor: 0 };
  for (const r of returns.filter((x) => x.status === 'completed')) {
    back.refundedMinor += r.refund_minor ?? 0;
    back.creditedMinor += r.account_credit_minor ?? 0;
    for (const l of r.lines ?? []) {
      const key = l.sale_line_id ?? '';
      const was = back.byLine.get(key) ?? { qtyMilli: 0, totalMinor: 0 };
      back.byLine.set(key, { qtyMilli: was.qtyMilli + milliOf(l.qty ?? '0'), totalMinor: was.totalMinor + (l.line_total_minor ?? 0) });
    }
  }
  return back;
}

export const soldMilli = (line: Pick<SaleLine, 'qty'>): number => milliOf(line.qty ?? '0');

/** What can still come back of a line, in thousandths of the item's own unit. */
export const remainingMilli = (line: Pick<SaleLine, 'id' | 'qty'>, back: Back): number => soldMilli(line) - (back.byLine.get(line.id ?? '')?.qtyMilli ?? 0);

/** The unit the line was sold in: its pack, or null for the item's own unit. */
export const lineUnit = (line: Pick<SaleLine, 'sale_unit_id' | 'sale_unit'>): string | null => (line.sale_unit_id ? (line.sale_unit ?? null) : null);

/** The quantity of a row in thousandths of the item's own unit; null when it is empty, not a quantity, or does not convert exactly. */
export function baseMilliOf(line: Pick<SaleLine, 'sale_unit_id' | 'unit_factor'>, row: Pick<RowForm, 'qty' | 'entered'>): number | null {
  const milli = parseQty(row.qty);
  if (milli === null) return null;
  return row.entered === 'line' && line.sale_unit_id ? baseQtyMilli(milli, line.unit_factor) : milli;
}

/** `amount x part / whole`, half up once, exact. */
export function shareOf(amount: number, part: number, whole: number): number {
  return Number((BigInt(amount) * BigInt(part) * 2n + BigInt(whole)) / (2n * BigInt(whole)));
}

/** The credit for returning `baseMilli` of a line: its share of what it was charged, the last piece taking the remainder. */
export function creditFor(line: Pick<SaleLine, 'id' | 'qty' | 'line_total_minor'>, baseMilli: number, back: Back): number {
  const charged = line.line_total_minor ?? 0;
  const left = charged - (back.byLine.get(line.id ?? '')?.totalMinor ?? 0);
  return baseMilli === remainingMilli(line, back) ? left : Math.min(shareOf(charged, baseMilli, soldMilli(line)), left);
}

/** A problem with one row, in words, or null; an empty row is not a problem, it is a line that stays. */
export function rowProblem(line: SaleLine, row: RowForm, back: Back): string | null {
  if (row.qty.trim() === '') return null;
  const milli = parseQty(row.qty);
  if (milli === null) return 'Enter a quantity with at most three decimal places.';
  const base = baseMilliOf(line, row);
  if (base === null) return 'That quantity does not convert exactly to the item\'s own unit.';
  const left = remainingMilli(line, back);
  if (base > left) return left <= 0 ? 'All of this has come back already.' : `At most ${qtyString(left).replace(/\.?0+$/, '')} can still be returned.`;
  if (row.disposition === 'restock' && row.restockBranchId === '') return 'Choose where it is put back.';
  return null;
}

export interface Planned {
  line: SaleLine;
  row: RowForm;
  baseMilli: number;
  creditMinor: number;
}

/** The rows that are coming back, with their credit. A row with a problem is left out; `returnProblem` says why the form waits. */
export function plannedLines(sale: Pick<Sale, 'lines'>, form: ReturnForm, back: Back): Planned[] {
  const out: Planned[] = [];
  // One row per sale line, so nothing of a line is taken twice within a form.
  for (const line of sale.lines ?? []) {
    const row = form.rows[line.id ?? ''];
    if (!row || row.qty.trim() === '' || rowProblem(line, row, back) !== null) continue;
    const base = baseMilliOf(line, row);
    if (base === null) continue;
    out.push({ line, row, baseMilli: base, creditMinor: creditFor(line, base, back) });
  }
  return out;
}

export interface RefundPlan {
  totalMinor: number;
  /** Taken off what the buyer owes on this credit sale. */
  accountMinor: number;
  /** Paid out in money. */
  moneyMinor: number;
  /** The money is the remainder of an account refund, so the form must say how it is paid. */
  needsMoneyMethod: boolean;
  problem: string | null;
}

/** Where the credit goes: the server's rules (open balance, money only up to what came in), shown before saving. */
export function refundPlan(sale: Pick<Sale, 'payment_method' | 'total_minor' | 'paid_minor' | 'historical'>, totalMinor: number, form: Pick<ReturnForm, 'refund' | 'moneyMethod'>, back: Back): RefundPlan {
  const credit = sale.payment_method === 'credit';
  let account = 0;
  let money = totalMinor;
  let needs = false;
  if (totalMinor > 0 && form.refund === 'account') {
    const open = (sale.total_minor ?? 0) - (sale.paid_minor ?? 0);
    if (!credit || open <= 0) return { totalMinor, accountMinor: 0, moneyMinor: totalMinor, needsMoneyMethod: false, problem: 'There is no balance to take this off. Pay it back instead.' };
    if (sale.historical === true) return { totalMinor, accountMinor: 0, moneyMinor: totalMinor, needsMoneyMethod: false, problem: 'This is an imported sale. Pay it back instead.' };
    account = Math.min(totalMinor, open);
    money = totalMinor - account;
    needs = money > 0;
  } else if (totalMinor === 0) {
    money = 0;
  }
  if (money > 0 && credit) {
    const allowed = (sale.paid_minor ?? 0) - back.creditedMinor - back.refundedMinor;
    if (money > allowed) {
      return { totalMinor, accountMinor: account, moneyMinor: money, needsMoneyMethod: needs, problem: `Only ${fmt(Math.max(allowed, 0))} of this sale was paid in money and not paid back. Take the rest off what the buyer owes.` };
    }
  }
  return { totalMinor, accountMinor: account, moneyMinor: money, needsMoneyMethod: needs, problem: null };
}

/** Why the form cannot be saved yet, in words, or null. */
export function returnProblem(sale: Sale, chosen: ReturnForm, back: Back): string | null {
  const form = resolved(sale, chosen);
  for (const line of sale.lines ?? []) {
    const row = form.rows[line.id ?? ''];
    const problem = row ? rowProblem(line, row, back) : null;
    if (problem) return `${line.description}: ${problem}`;
  }
  const planned = plannedLines(sale, form, back);
  if (planned.length === 0) return 'Enter the quantity that is coming back.';
  const total = planned.reduce((sum, p) => sum + p.creditMinor, 0);
  const plan = refundPlan(sale, total, form, back);
  if (plan.problem) return plan.problem;
  if (plan.needsMoneyMethod && form.moneyMethod === '') return `${fmt(plan.accountMinor)} can be taken off what they owe. Choose how the other ${fmt(plan.moneyMinor)} is paid out.`;
  if (form.note.length > 300) return 'The note is too long.';
  return null;
}

/** The request for a form that has no problem: only the rows that are coming back. */
export function buildReturnRequest(sale: Sale, chosen: ReturnForm, back: Back): ReturnRequest {
  const form = resolved(sale, chosen);
  const lines: ReturnLineRequest[] = plannedLines(sale, form, back).map((p) => ({
    sale_line_id: p.line.id ?? '',
    qty: qtyString(parseQty(p.row.qty) ?? 0),
    ...(p.row.entered === 'line' && p.line.sale_unit_id ? { unit_id: p.line.sale_unit_id } : {}),
    reason: p.row.reason,
    disposition: p.row.disposition,
    ...(p.row.disposition === 'restock' && p.row.restockBranchId !== '' && p.row.restockBranchId !== sale.branch_id ? { restock_branch_id: p.row.restockBranchId } : {}),
  }));
  const total = plannedLines(sale, form, back).reduce((sum, p) => sum + p.creditMinor, 0);
  const plan = refundPlan(sale, total, form, back);
  return {
    lines,
    refund_method: form.refund,
    ...(plan.needsMoneyMethod && form.moneyMethod !== '' ? { money_method: form.moneyMethod } : {}),
    ...(plan.moneyMinor > 0 && form.refundBranchId !== '' ? { refund_branch_id: form.refundBranchId } : {}),
    ...(form.note.trim() !== '' ? { note: form.note.trim() } : {}),
  };
}

/** A credit note's lines for people: "2 x Item" with the unit it was entered in. */
export function returnedWords(line: { qty?: string; unit?: string; unit_qty?: string; description?: string }): string {
  const shown = (text: string) => (text.includes('.') ? text.replace(/0+$/, '').replace(/\.$/, '') : text);
  return line.unit && line.unit_qty ? `${shown(line.unit_qty)} ${line.unit} of ${line.description ?? ''}` : `${shown(line.qty ?? '0')} of ${line.description ?? ''}`;
}

/** How the credit note was settled, in a sentence. */
export function settledWords(r: Pick<SaleReturn, 'account_credit_minor' | 'refund_minor' | 'refund_method'>): string {
  const parts: string[] = [];
  if ((r.account_credit_minor ?? 0) > 0) parts.push(`${fmt(r.account_credit_minor ?? 0)} taken off what the buyer owes`);
  if ((r.refund_minor ?? 0) > 0) parts.push(`${fmt(r.refund_minor ?? 0)} paid back${r.refund_method ? ` by ${r.refund_method === 'mobile_money' ? 'mobile money' : r.refund_method}` : ''}`);
  return parts.length > 0 ? parts.join(' and ') : 'Nothing to pay';
}
