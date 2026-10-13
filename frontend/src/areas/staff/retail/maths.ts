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

/** unit price times quantity in integer minor units, rounded half up, exact via BigInt. */
export function lineTotalMinor(unitPriceMinor: number, qtyMilli: number): number {
  const product = BigInt(unitPriceMinor) * BigInt(qtyMilli);
  return Number((product + 500n) / 1000n);
}

/** Basis points as a percentage for people: 4872 is "48.72%", 5000 is "50%"; "n/a" when there is none. */
export function showPercent(bp: number | null | undefined): string {
  if (bp === undefined || bp === null) return 'n/a';
  const abs = Math.abs(bp);
  const whole = Math.floor(abs / 100);
  const frac = String(abs % 100).padStart(2, '0').replace(/0+$/, '');
  return `${bp < 0 ? '-' : ''}${whole}${frac ? `.${frac}` : ''}%`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "6 Oct 2026" from "2026-10-06": no hyphen for a narrow row to break at, and no timezone shift. */
export function showDate(iso: string | undefined): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso ?? '');
  return m ? `${Number(m[3])} ${MONTHS[Number(m[2]) - 1] ?? m[2]} ${m[1]}` : (iso ?? '');
}
