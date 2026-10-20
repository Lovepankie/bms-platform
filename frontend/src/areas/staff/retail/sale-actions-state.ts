import { RETAIL_CURRENCY, RetailError, type Sale, type SalePaymentRequest, type WriteOffOutcome } from '../../../api/retail';
import { formatMinor, parseMinor } from '../../../components/money';
import { MIN_REASON, reasonOf } from './account-state';

// Recording a payment on a credit sale (issue #326) and voiding a sale (issue #327): the pure rules the two forms show
// before anything is sent. The server decides every one of them again under the sale's lock (payment_exceeds_balance,
// sale_not_payable, sale_voided, sale_has_payments, sale_has_returns), so these only save a round trip.

export const PAY_METHODS: { value: SalePaymentRequest['method']; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'mobile_money', label: 'Mobile money' },
  { value: 'bank', label: 'Bank' },
];

export { MIN_REASON };

/** An amount as a person types it, with no currency and no grouping: "12000". */
export const typedAmount = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY).replace(/^[A-Z]+ /, '').replace(/,/g, '');

/** What is still owed on the sale, never below zero. */
export const owedMinor = (sale: Pick<Sale, 'balance_minor'>): number => Math.max(0, sale.balance_minor ?? 0);

/** True for a live credit sale that still owes money; an imported (historical) sale takes no payment here. */
export function takesPayment(sale: Pick<Sale, 'payment_method' | 'status' | 'balance_minor' | 'historical'>): boolean {
  return sale.payment_method === 'credit' && sale.status === 'completed' && owedMinor(sale) > 0 && sale.historical !== true;
}

/** True for an imported credit sale that still owes: the action is not offered and a note says why. */
export const importedAndOwing = (sale: Pick<Sale, 'payment_method' | 'status' | 'balance_minor' | 'historical'>): boolean =>
  sale.payment_method === 'credit' && sale.status === 'completed' && owedMinor(sale) > 0 && sale.historical === true;

export interface PayForm {
  amount: string;
  method: SalePaymentRequest['method'];
  paidOn: string;
}

/** A fresh form: the whole balance, in cash, paid today. */
export const blankPay = (sale: Pick<Sale, 'balance_minor'>, today: string): PayForm => ({ amount: typedAmount(owedMinor(sale)), method: 'cash', paidOn: today });

/** What stops the payment being saved, in plain words, or null. */
export function payProblem(form: PayForm, sale: Pick<Sale, 'balance_minor' | 'sale_date'>, today: string): string | null {
  const amount = parseMinor(form.amount, RETAIL_CURRENCY);
  if (amount === null || amount <= 0) return 'Enter the amount received.';
  if (amount > owedMinor(sale)) return `This sale owes only ${formatMinor(owedMinor(sale), RETAIL_CURRENCY)}.`;
  if (form.paidOn === '') return 'Choose the date the money was received.';
  if (form.paidOn > today) return 'The date cannot be in the future.';
  if (sale.sale_date && form.paidOn < sale.sale_date) return 'The date cannot be before the sale.';
  return null;
}

/**
 * The form as it is sent: for a sale counted when the buyer pays a payment is dated the day it is entered (ADR-043), so whatever date a
 * stored draft holds, from yesterday or from an earlier visit, the date used is today.
 */
export const settledDate = (form: PayForm, todayOnly: boolean, today: string): PayForm => (todayOnly ? { ...form, paidOn: today } : form);

export function buildPayRequest(form: PayForm): SalePaymentRequest {
  const amount = parseMinor(form.amount, RETAIL_CURRENCY);
  if (amount === null) throw new Error('invalid amount');
  return { amount_minor: amount, method: form.method, paid_on: form.paidOn };
}

/** The shortcut: the whole balance, in the method and on the date already chosen. */
export const fullPay = (form: PayForm, sale: Pick<Sale, 'balance_minor'>): PayForm => ({ ...form, amount: typedAmount(owedMinor(sale)) });

/** A sentence for the result of a payment. */
export const paidWords = (paidMinor: number, balanceMinor: number): string =>
  balanceMinor <= 0
    ? `Recorded ${formatMinor(paidMinor, RETAIL_CURRENCY)}. This sale is now paid in full.`
    : `Recorded ${formatMinor(paidMinor, RETAIL_CURRENCY)}. Still owes ${formatMinor(balanceMinor, RETAIL_CURRENCY)}.`;

/** The short line for an imported credit sale in a list row, and the fuller one on the opened sale. Only the screen enforces this for now. */
export const IMPORTED_SHORT = 'Imported from the old records; record this payment with your accountant.';
export const IMPORTED_FULL = 'This sale was imported from the old records. Payments on it are not recorded from this screen yet, so ask your accountant to record this payment.';
export const IMPORTED_VOID = 'This sale was imported from the old records, so it cannot be voided here. Ask your accountant.';

/**
 * Why a sale cannot be voided, in words, or null when it can (as far as the screen knows). A credit sale whose paid amount has moved
 * (a payment, or the credit of a return) is refused by the server; an imported sale is kept off the action because its stock and
 * entries predate this system. Returns on a cash sale are only known to the server, which refuses with sale_has_returns.
 */
export function voidBlocked(sale: Pick<Sale, 'status' | 'payment_method' | 'paid_minor' | 'historical'>): string | null {
  if (sale.status === 'voided') return 'This sale is already voided.';
  if (sale.historical === true) return IMPORTED_VOID;
  if (sale.payment_method === 'credit' && (sale.paid_minor ?? 0) > 0) return 'A credit sale that has had a payment or a return cannot be voided.';
  return null;
}

/** The reason to send, or null while it is too short. */
export const voidReasonOf = (text: string): string | null => reasonOf(text);

const codeOf = (error: unknown): string | undefined => (error instanceof RetailError ? error.code : undefined);

/** Refusals that mean the sale moved on since the screen read it: the balance, its state or its voiding changed. */
export const saleMovedOn = (error: unknown): boolean => ['payment_exceeds_balance', 'sale_not_payable', 'sale_voided'].includes(codeOf(error) ?? '');

/** The Idempotency-Key was already used for a different payment: the form must start again with a new key. */
export const keyReused = (error: unknown): boolean => codeOf(error) === 'idempotency_key_reused';

/** What to tell the person about a failed payment, when the plain refusal alone would leave them stuck. */
export function payFailureWords(error: unknown, full: boolean): unknown {
  if (keyReused(error)) return new Error('This payment was changed after it was first sent, so the form has been reset. Check the balance, then enter it again.');
  if (codeOf(error) === 'payment_exceeds_balance' && full) return new Error('The balance has changed; check the new amount.');
  if (codeOf(error) === 'sale_not_payable' || codeOf(error) === 'sale_voided') return new Error('This sale can no longer take a payment. It has been reloaded; check its state.');
  return error;
}

// Writing off a debt (issue #340, ADR-043): the unpaid part of a credit sale that the buyer will not pay becomes a loss. It never
// deletes: the sale stays and reads Written off, and the debt leaves the owing and overdue lists. The server checks every rule again.

/** True for a live credit sale made on the system that still owes money and has not been written off. */
export const canWriteOff = (sale: Pick<Sale, 'payment_method' | 'status' | 'balance_minor' | 'historical' | 'write_off'>): boolean =>
  sale.payment_method === 'credit' && sale.status === 'completed' && owedMinor(sale) > 0 && sale.historical !== true && sale.write_off === undefined;

/** The reason to send, or null while it is too short. */
export const writeOffReasonOf = (text: string): string | null => reasonOf(text);

/** What the person is told before they confirm: the amount, that it is a loss, that nothing is deleted and that it cannot be undone. */
export const writeOffWarning = (owed: number): string =>
  `${formatMinor(owed, RETAIL_CURRENCY)} owed will be cleared as a bad debt. The sale stays on record and reads Written off, the buyer no longer owes it, and it leaves the owing and overdue lists. The loss shows in Daily profit on the day it is written off; if a second administrator has to approve it, that is the day it is approved. This cannot be undone.`;

/** A sentence for the result of a write-off: done, or waiting for a second administrator. */
export const writeOffWords = (outcome: Pick<WriteOffOutcome, 'executed' | 'write_off'>): string =>
  outcome.executed === true
    ? `Cleared ${formatMinor(outcome.write_off?.unpaid_minor ?? 0, RETAIL_CURRENCY)} owed as a bad debt. The sale stays on record.`
    : 'Sent to a second administrator to approve. Nothing changes until they do.';

/** Why a write-off may wait for someone else, said before it is sent. */
export const WRITE_OFF_CHECKER = 'Unless your business sets a limit, a write-off needs a second administrator to approve it. If you are the only active administrator, it goes through at once and is marked so in the audit trail.';

/** What is shown in place of the button while a write-off of the sale waits for a second administrator. */
export const WRITE_OFF_PENDING = 'A write-off of this debt is waiting for a second administrator to approve it. Nothing changes until they do.';

/** The server already holds a waiting request for this sale. */
export const writeOffAlreadyPending = (error: unknown): boolean => codeOf(error) === 'approval_already_pending';

/** Refusals that mean the sale moved on since the screen read it. */
export const writeOffMovedOn = (error: unknown): boolean => ['already_written_off', 'nothing_owing', 'sale_voided'].includes(codeOf(error) ?? '');

/** What to tell the person about a failed write-off, when the plain refusal alone would leave them stuck. */
export function writeOffFailureWords(error: unknown): unknown {
  if (keyReused(error)) return new Error('This write-off was changed after it was first sent, so the form has been reset. Check the sale, then try again.');
  if (writeOffMovedOn(error)) return new Error('This sale changed while you were looking at it. It has been reloaded; check what it owes.');
  return error;
}
