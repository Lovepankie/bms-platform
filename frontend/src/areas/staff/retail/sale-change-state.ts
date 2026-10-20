import { RETAIL_CURRENCY, type SalePayment } from '../../../api/retail';
import { formatMinor, parseMinor } from '../../../components/money';

// The till helper of the sale form (issue #346): for a cash sale the seller types what the customer
// handed over and the form shows the change to give back. It is display only. The sale is recorded
// at its total, the daily cash summary counts that total, and the amount typed here is never sent to
// the server, never put in the saved draft and never stored. Whole Uganda shillings, integer
// arithmetic only.

/** The notes offered as quick buttons, in shillings. */
export const NOTES: readonly number[] = [1000, 2000, 5000, 10000, 20000, 50000];

/** Above this a typed amount is a slip of the finger, not money in a till (UGX 100 billion). Keeps every sum a safe integer. */
export const MAX_RECEIVED = 100_000_000_000;

export type Received =
  | { kind: 'empty' }
  | { kind: 'ok'; amount: number }
  | { kind: 'invalid'; why: string };

/** What the seller typed, read as whole shillings. Commas and spaces are allowed ("20,000", "20 000"). */
export function readReceived(text: string): Received {
  const clean = text.replace(/[\s,]/g, '');
  if (clean === '') return { kind: 'empty' };
  if (/^[-+]/.test(clean)) return { kind: 'invalid', why: 'The amount cannot be negative. Type the shillings the customer gave.' };
  if (/^\d+[.]\d*$/.test(clean)) return { kind: 'invalid', why: 'Type whole shillings only, for example 20000.' };
  if (!/^\d+$/.test(clean)) return { kind: 'invalid', why: 'Type numbers only, for example 20000.' };
  const amount = parseMinor(clean, RETAIL_CURRENCY);
  if (amount === null || amount > MAX_RECEIVED) return { kind: 'invalid', why: 'That amount is too large. Check the figure.' };
  return { kind: 'ok', amount };
}

/** The change to give back: what was received less the total, never below zero. */
export function changeDue(total: number, received: number): number {
  return Math.max(received - total, 0);
}

/** Whether the field is shown: only for a cash sale. Mobile money, bank and credit have no change to give. */
export function takesCash(method: SalePayment): boolean {
  return method === 'cash';
}

/**
 * What stops a cash sale being saved because of the amount received, in plain words, or null. An empty field is allowed
 * (the customer paid the exact amount and the seller wants no change); an amount typed must be readable and cover the total.
 * The backend has no part payment on a cash sale, so a shortfall is refused: a part payment is a credit sale.
 */
export function receivedProblem(method: SalePayment, total: number, text: string): string | null {
  if (!takesCash(method)) return null;
  const r = readReceived(text);
  if (r.kind === 'empty') return null;
  if (r.kind === 'invalid') return r.why;
  if (total > 0 && r.amount < total) return `Enter at least the total, ${formatMinor(total, RETAIL_CURRENCY)}.`;
  return null;
}

/** The amount to show on the receipt: the readable amount of a cash sale, else nothing. */
export function receivedFor(method: SalePayment, total: number, text: string): number | undefined {
  if (!takesCash(method) || receivedProblem(method, total, text) !== null) return undefined;
  const r = readReceived(text);
  return r.kind === 'ok' ? r.amount : undefined;
}
