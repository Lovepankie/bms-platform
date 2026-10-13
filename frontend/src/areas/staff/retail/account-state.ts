import { RETAIL_CURRENCY, RetailError, businessToday, daysBefore, type AccountEdit, type AccountPaymentRequest, type CustomerAccount, type OpenSale } from '../../../api/retail';
import { formatMinor, parseMinor } from '../../../components/money';

const fmt = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY);

// Customer accounts, the pure rules (ADR-033, issue #196): what a payment on account would do, early checks that only
// save a round trip (the server decides and refuses the same cases), the words for a statement line, and the figures of
// a limit. Money is integer minor units; nothing here rounds.

export const KIND_WORDS: Record<string, string> = {
  sale: 'Credit sale',
  payment: 'Payment on a sale',
  account_payment: 'Payment on account',
  reversal: 'Payment reversed',
  void: 'Sale cancelled',
  return: 'Goods returned, credit note',
  return_void: 'Credit note voided',
};

export const kindWords = (kind: string | undefined): string => KIND_WORDS[kind ?? ''] ?? kind ?? '';

export const METHOD_WORDS: Record<string, string> = { cash: 'Cash', mobile_money: 'Mobile money', bank: 'Bank' };

export const methodWords = (method: string | undefined): string => METHOD_WORDS[method ?? ''] ?? method ?? '';

/** A statement shows the last 90 days unless asked otherwise, ending today in the business's time zone. */
export function defaultStatementRange(now: Date = new Date()): { from: string; to: string } {
  const to = businessToday(now);
  return { from: daysBefore(to, 89), to };
}

// ---- Allocation ---------------------------------------------------------------------------------

export interface Part {
  saleId: string;
  saleNo: string;
  amountMinor: number;
}

/** The sales a payment on account can pay: imported (historical) sales are skipped by the server, so they are left out here. */
export const payableSales = (open: readonly OpenSale[]): OpenSale[] => open.filter((s) => s.historical !== true && (s.balance_minor ?? 0) > 0);

/** What can be allocated at all: the balances of the payable sales. */
export const allocatableMinor = (open: readonly OpenSale[]): number => payableSales(open).reduce((sum, s) => sum + (s.balance_minor ?? 0), 0);

/**
 * What the server would do with an amount given no choice: the oldest sale first (by sale date, then the order the list
 * came in), each paid up to its balance, those dated after the payment left out. `left` is what could not be placed.
 */
export function oldestFirst(open: readonly OpenSale[], amountMinor: number, paidOn: string): { parts: Part[]; leftMinor: number } {
  const parts: Part[] = [];
  let left = amountMinor;
  const ordered = payableSales(open)
    .map((s, i) => ({ s, i }))
    .sort((a, b) => (a.s.sale_date ?? '').localeCompare(b.s.sale_date ?? '') || a.i - b.i);
  for (const { s } of ordered) {
    if (left <= 0) break;
    if ((s.sale_date ?? '') > paidOn) continue;
    const part = Math.min(left, s.balance_minor ?? 0);
    parts.push({ saleId: s.sale_id ?? '', saleNo: s.sale_no ?? '', amountMinor: part });
    left -= part;
  }
  return { parts, leftMinor: left };
}

/** One sale the cashier chose to pay, with the amount as typed. */
export interface ManualRow {
  saleId: string;
  text: string;
}

export const manualAmount = (row: ManualRow): number | null => (row.text.trim() === '' ? 0 : parseMinor(row.text, RETAIL_CURRENCY));

export function manualTotal(rows: readonly ManualRow[]): number {
  return rows.reduce((sum, r) => sum + (manualAmount(r) ?? 0), 0);
}

// ---- The payment form ------------------------------------------------------------------------------

export interface PaymentForm {
  branchId: string;
  amount: string;
  method: 'cash' | 'mobile_money' | 'bank';
  paidOn: string;
  /** true: the cashier chooses the sales; false: oldest first. */
  manual: boolean;
  rows: ManualRow[];
}

/** What stops the payment being saved, in plain words, or null. The server checks all of it again. */
export function paymentProblem(form: PaymentForm, open: readonly OpenSale[], today: string): string | null {
  if (form.branchId === '') return 'Choose the branch that received the money.';
  const amount = parseMinor(form.amount, RETAIL_CURRENCY);
  if (amount === null || amount <= 0) return 'Enter the amount received.';
  if (form.paidOn === '' || form.paidOn > today) return 'The date cannot be in the future.';
  const payable = payableSales(open);
  if (payable.length === 0) return 'This buyer owes nothing that can be paid here.';
  if (!form.manual) {
    const placed = oldestFirst(open, amount, form.paidOn);
    return placed.leftMinor > 0 ? `At most ${fmt(allocatableMinor(open))} can be paid here; ${fmt(amount)} was entered.` : null;
  }
  const rows = form.rows.filter((r) => (manualAmount(r) ?? 0) !== 0 || r.text.trim() !== '');
  if (rows.length === 0) return 'Enter an amount against at least one sale.';
  for (const r of rows) {
    const value = manualAmount(r);
    const sale = payable.find((s) => s.sale_id === r.saleId);
    if (value === null || value <= 0 || !sale) return 'Check the amounts against the sales.';
    if (value > (sale.balance_minor ?? 0)) return `Sale ${sale.sale_no} owes only ${fmt(sale.balance_minor ?? 0)}.`;
    if ((sale.sale_date ?? '') > form.paidOn) return `Sale ${sale.sale_no} is dated after the payment.`;
  }
  const total = manualTotal(rows);
  return total === amount ? null : 'The amounts against the sales must add up to the payment.';
}

/** The request for a valid form: no allocations for oldest first, the chosen sales and amounts otherwise. */
export function buildPaymentRequest(form: PaymentForm): AccountPaymentRequest {
  const amount = parseMinor(form.amount, RETAIL_CURRENCY);
  if (amount === null) throw new Error('invalid amount');
  return {
    branch_id: form.branchId,
    amount_minor: amount,
    method: form.method,
    ...(form.paidOn ? { paid_on: form.paidOn } : {}),
    ...(form.manual
      ? {
          allocations: form.rows
            .map((r) => ({ sale_id: r.saleId, amount_minor: manualAmount(r) ?? 0 }))
            .filter((a) => a.amount_minor > 0),
        }
      : {}),
  };
}

// ---- The account ---------------------------------------------------------------------------------------

/** A sentence for the limit: what it is, and where the buyer stands against it when the caller may see every branch. */
export function limitWords(account: Pick<CustomerAccount, 'credit_limit_minor' | 'exposure_minor' | 'available_minor' | 'over_limit'>, money: (minor: number) => string): string {
  if (account.credit_limit_minor === undefined) return 'No credit limit.';
  const limit = `Credit limit ${money(account.credit_limit_minor)}`;
  if (account.exposure_minor === undefined) return `${limit}.`;
  if (account.over_limit) return `${limit}. Over the limit by ${money(account.exposure_minor - account.credit_limit_minor)}.`;
  return `${limit}. ${money(account.available_minor ?? 0)} still available.`;
}

export const termsWords = (days: number | undefined): string =>
  days === undefined ? 'No payment terms.' : days === 0 ? 'Payment is due on the day of the sale.' : `Payment is due ${days} ${days === 1 ? 'day' : 'days'} after the sale.`;

export interface AccountForm {
  limit: string;
  terms: string;
}

export const accountFormOf = (account: Pick<CustomerAccount, 'credit_limit_minor' | 'terms_days'>): AccountForm => ({
  limit: account.credit_limit_minor === undefined ? '' : String(account.credit_limit_minor),
  terms: account.terms_days === undefined ? '' : String(account.terms_days),
});

/** What stops the limit and terms being saved, or null. Empty means none. */
export function accountProblem(form: AccountForm): string | null {
  if (form.limit.trim() !== '') {
    const limit = parseMinor(form.limit, RETAIL_CURRENCY);
    if (limit === null || limit < 0) return 'Enter the limit as an amount, or leave it empty for no limit.';
  }
  if (form.terms.trim() !== '' && !/^\d{1,3}$/.test(form.terms.trim())) return 'Enter the terms as a number of days, or leave it empty for none.';
  if (form.terms.trim() !== '' && Number(form.terms) > 365) return 'The terms are at most 365 days.';
  return null;
}

/**
 * The request for a valid form. The server wants each of the two decided on purpose: a value, or its clear flag set when the
 * field was left empty. A body that names neither would change nothing, so one field is never left out.
 */
export function accountRequest(form: AccountForm): AccountEdit {
  const limit = form.limit.trim() !== '';
  const terms = form.terms.trim() !== '';
  return {
    ...(limit ? { credit_limit_minor: parseMinor(form.limit, RETAIL_CURRENCY) ?? 0 } : { clear_credit_limit: true }),
    ...(terms ? { terms_days: Number(form.terms) } : { clear_terms_days: true }),
  };
}

// ---- Reversing a payment ---------------------------------------------------------------------------------

/** The fewest characters of a reason for a reversal or a credit override: the same on the screen and on the server. */
export const MIN_REASON = 5;

/** A reason as the server reads it (trimmed), or null while it is too short. */
export const reasonOf = (text: string): string | null => (text.trim().length >= MIN_REASON ? text.trim() : null);

/**
 * The Idempotency-Key of each reversal, one per receipt: a retry of the same receipt with the same reason repeats its key (so
 * a reply lost on the way is replayed, not done twice), while another receipt, or the same receipt with another reason, gets a
 * key of its own. One key shared by every receipt on the page would be sent on a different path after a failure and refused.
 */
export function reversalKeys(make: () => string) {
  const keys = new Map<string, { reason: string; key: string }>();
  return {
    keyFor(receiptId: string, reason: string): string {
      const held = keys.get(receiptId);
      if (held && held.reason === reason) return held.key;
      const key = make();
      keys.set(receiptId, { reason, key });
      return key;
    },
    /** The reversal went through: the receipt will not be reversed again. */
    done(receiptId: string): void {
      keys.delete(receiptId);
    },
  };
}

// ---- A credit sale above the limit ----------------------------------------------------------------------

/** By how much a refused credit sale went over the limit, from the server's problem, or null if it was not that refusal. */
export function exceededBy(error: unknown): number | null {
  if (error instanceof RetailError && (error.code === 'credit_limit_exceeded' || error.code === 'override_reason_required')) {
    const by = error.problem?.['exceeded_by_minor'];
    return typeof by === 'number' ? by : null;
  }
  return null;
}

/** True when the refusal was the limit (not the missing reason): the seller may be offered the override. */
export const isLimitRefusal = (error: unknown): boolean => error instanceof RetailError && error.code === 'credit_limit_exceeded';
