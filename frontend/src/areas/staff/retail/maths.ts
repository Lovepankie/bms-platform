// Quantity and line maths for the retail screens. Quantities are decimal strings with up to three
// places ("3.500"); they are held as integer thousandths so no floating point touches a total
// (ADR-004). The server computes the real totals; these are the running figures the form shows.

/** "3.5" becomes 3500 thousandths; null when not a positive quantity with at most 3 places. */
export function parseQty(text: string): number | null {
  const match = /^(\d+)(?:\.(\d{1,3}))?$/.exec(text.trim());
  if (!match) return null;
  const milli = Number(`${match[1]}${(match[2] ?? '').padEnd(3, '0')}`);
  return Number.isSafeInteger(milli) && milli > 0 ? milli : null;
}

/** As parseQty but zero is allowed, for counted quantities in a stock-take. */
export function parseCount(text: string): number | null {
  return /^0(\.0{1,3})?$/.test(text.trim()) ? 0 : parseQty(text);
}

/** Thousandths back to the wire format, always three places ("3.500"). */
export function qtyString(milli: number): string {
  const sign = milli < 0 ? '-' : '';
  const digits = Math.abs(milli).toString().padStart(4, '0');
  return `${sign}${digits.slice(0, -3)}.${digits.slice(-3)}`;
}

/** A quantity for people: "3.5", "12", never trailing zeros. */
export function showQty(text: string): string {
  return text.includes('.') ? text.replace(/0+$/, '').replace(/\.$/, '') : text;
}

/** Signed thousandths of a stored quantity string, for sums and comparisons. */
export function milliOf(text: string): number {
  const negative = text.trim().startsWith('-');
  const magnitude = parseCount(negative ? text.trim().slice(1) : text) ?? 0;
  return negative ? -magnitude : magnitude;
}

/**
 * Issue #629: a count that takes an item holding stock to 0, or to less than half of what it held, is the likely slip of a
 * tap (a 0 meant for the next line). It asks before it is committed; a count that keeps half or more stays as it is. The one
 * rule of the screen and the mock; the server's StockService.bigDrop is pinned to it by the cases of maths.test.ts (#636).
 */
export function bigDrop(l: { expected_qty?: string; counted_qty?: string }): boolean {
  const expected = milliOf(l.expected_qty ?? '0');
  return expected > 0 && milliOf(l.counted_qty ?? '0') * 2 < expected;
}

/** unit price times quantity in integer minor units, rounded half up (away from zero, as the server's HALF_UP), exact via BigInt. */
export function lineTotalMinor(unitPriceMinor: number, qtyMilli: number): number {
  const product = BigInt(unitPriceMinor) * BigInt(qtyMilli);
  // BigInt division truncates toward zero, so a negative quantity (stock below zero) rounds with the half taken off.
  return Number((product + (product < 0n ? -500n : 500n)) / 1000n);
}

/** Basis points as a percentage for people: 4872 is "48.72%", 5000 is "50%"; "n/a" when there is none. */
export function showPercent(bp: number | null | undefined): string {
  if (bp === undefined || bp === null) return 'n/a';
  const abs = Math.abs(bp);
  const whole = Math.floor(abs / 100);
  const frac = String(abs % 100).padStart(2, '0').replace(/0+$/, '');
  return `${bp < 0 ? '-' : ''}${whole}${frac ? `.${frac}` : ''}%`;
}

// The one date formatter lives with the period picker of the kit (issue #526); the retail screens import it from here as before.
export { showDate } from '../../../app/ui/PeriodPicker';
