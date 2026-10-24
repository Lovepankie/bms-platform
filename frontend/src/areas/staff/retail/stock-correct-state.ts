import type { StockMovement } from '../../../api/retail';
import { milliOf, parseCount, qtyString, showQty } from './maths';

// Correcting the quantity of an item on the stock list (issue #351, ADR-044). The server decides; these helpers only prepare the
// request and put the answers in plain words.

export type ReasonCode = 'entry_mistake' | 'miscount';

export const REASONS: { value: ReasonCode; label: string }[] = [
  { value: 'entry_mistake', label: 'Entry mistake: I typed it wrongly' },
  { value: 'miscount', label: 'Miscount: I counted it wrongly' },
];

/** Why the typed quantity cannot be sent, or null when it can. */
export function quantityProblem(typed: string, current: string): string | null {
  const milli = parseCount(typed);
  if (milli === null) return 'Type the right quantity, for example 6 or 2.5 (up to three decimal places).';
  if (milli === milliOf(current)) return `It is ${showQty(current)} already. Type the right quantity.`;
  return null;
}

/** "That takes away 4 roll." or "That adds 2.5 roll." for the quantity typed against the one on the list. */
export function differenceWords(typed: string, current: string, unit: string | undefined): string | null {
  const milli = parseCount(typed);
  if (milli === null || milli === milliOf(current)) return null;
  const difference = milli - milliOf(current);
  const amount = showQty(qtyString(Math.abs(difference)));
  return `That ${difference < 0 ? 'takes away' : 'adds'} ${amount}${unit ? ` ${unit}` : ''}.`;
}

/** The request body for the right quantity. */
export function correctionRequest(branchId: string, productId: string, typed: string, reason: ReasonCode, note: string, expected: string) {
  return {
    branch_id: branchId,
    product_id: productId,
    new_qty: qtyString(parseCount(typed) ?? 0),
    expected_qty: expected,
    reason_code: reason,
    ...(note.trim() ? { note: note.trim() } : {}),
  };
}

const KIND: Record<string, string> = {
  purchase: 'Purchase',
  sale: 'Sale',
  usage: 'Used',
  damage: 'Damaged',
  return: 'Returned',
  opening: 'Opening stock',
  legacy_balance: 'Opening stock',
  transfer_out: 'Moved out',
  transfer_in: 'Moved in',
  return_to_supplier: 'Sent back to the supplier',
};

/** What a line of the stock history is, in words: a correction and a voided restock say so, a stock-take is a count. */
export function movementTitle(m: StockMovement): string {
  if (m.correction || m.source_type === 'retail.stock_correction') return 'Quantity corrected';
  if (m.source_type === 'retail.purchase_void') return 'Purchase voided';
  if (m.source_type === 'retail.stocktake') return 'Stock count';
  if (m.kind === 'adjustment') return 'Adjustment';
  return KIND[m.kind ?? ''] ?? (m.kind ?? '');
}

/** "+10" or "-4", never a bare number, so a line shows which way the stock moved. */
export function signedQty(m: StockMovement): string {
  const qty = showQty(m.qty ?? '0');
  return qty.startsWith('-') ? qty : `+${qty}`;
}

/** "Corrected from 10 to 6. Entry mistake." for a correction line, null for any other. */
export function correctionWords(m: StockMovement): string | null {
  if (!m.correction) return null;
  const why = m.correction.reason_code === 'entry_mistake' ? 'Entry mistake' : m.correction.reason_code === 'miscount' ? 'Miscount' : '';
  return `Corrected from ${showQty(m.correction.old_qty ?? '0')} to ${showQty(m.correction.new_qty ?? '0')}.${why ? ` ${why}.` : ''}`;
}
