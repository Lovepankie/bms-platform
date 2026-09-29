// Money is formatted only here, from integer minor units and the currency exponent (ADR-004,
// chapter 5 section 5.6). No floating point touches the amount: the integer is split into
// whole and fractional digits as a string.

const EXPONENTS: Record<string, number> = { UGX: 0, KES: 2, TZS: 2, USD: 2 };

export function formatMinor(minor: number, currency: string): string {
  if (!Number.isSafeInteger(minor)) throw new Error('amount must be a safe integer in minor units');
  const exponent = EXPONENTS[currency];
  if (exponent === undefined) throw new Error(`unknown currency ${currency}`);
  const negative = minor < 0;
  const digits = Math.abs(minor).toString().padStart(exponent + 1, '0');
  const whole = digits.slice(0, digits.length - exponent) || '0';
  const fraction = exponent > 0 ? `.${digits.slice(digits.length - exponent)}` : '';
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${negative ? '-' : ''}${currency} ${grouped}${fraction}`;
}
