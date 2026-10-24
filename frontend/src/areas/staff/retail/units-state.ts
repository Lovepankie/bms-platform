import type { Pack, Product, SaleLine } from '../../../api/retail';
import { showQty } from './maths';

// Units of measure (ADR-033, issue #194): the pure conversions the sale and restock forms show before
// they send. Stock is held in the item's base unit; a pack has a factor, the number of base units in
// one pack ("12.000"). Quantities and factors are integer thousandths and money is integer minor units,
// so nothing is floating point; the rules are the server's (Quantities.java): a quantity that does not
// convert exactly to thousandths is refused, money rounds half up once.

/** The largest quantity the server holds, in thousandths: numeric(14,3). */
const MAX_MILLI = 99_999_999_999_999n;

/** "12.000" as 12000n thousandths; null when it is not a decimal with at most three places. */
export function factorMilli(factor: string | undefined): bigint | null {
  const m = /^(\d+)(?:\.(\d{1,3}))?$/.exec((factor ?? '').trim());
  return m ? BigInt(`${m[1]}${(m[2] ?? '').padEnd(3, '0')}`) : null;
}

/** The packs a sale or restock may use: the active ones. Empty while the units capability is off (the server sends none). */
export function activePacks(product: Product): Pack[] {
  return (product.packs ?? []).filter((k) => k.active && k.unit_id);
}

/** The chosen pack of a line, or undefined for the base unit (no unit chosen, or one that is no longer a pack). */
export function chosenPack(product: Product, unitId: string | undefined): Pack | undefined {
  return unitId ? activePacks(product).find((k) => k.unit_id === unitId) : undefined;
}

/**
 * D23 (#554): the size typed for a buying pack in this delivery ("30"), as the factor the maths take; null when it is not a
 * number of at least 1 with at most three decimals. The server keeps it on the line only.
 */
export function deliveredFactor(size: string): string | null {
  const text = size.trim();
  const f = factorMilli(text);
  return f !== null && f >= 1000n ? text : null;
}

/** Whether two sizes are the same number: "30" and "30.000". */
export function sameFactor(a: string | undefined, b: string | undefined): boolean {
  const x = factorMilli(a);
  return x !== null && x === factorMilli(b);
}

/** The price of one pack from the price of one base unit: base x factor, rounded half up once. */
export function packPriceMinor(baseMinor: number, factor: string | undefined): number {
  const f = factorMilli(factor);
  if (f === null) return baseMinor;
  return Number((BigInt(baseMinor) * f + 500n) / 1000n);
}

/** The cost of one base unit from the cost of one pack: pack / factor, rounded half up. */
export function baseCostMinor(packMinor: number, factor: string | undefined): number {
  const f = factorMilli(factor);
  if (f === null || f === 0n) return packMinor;
  return Number((BigInt(packMinor) * 2000n + f) / (2n * f));
}

/** A quantity of packs in base units, as thousandths; null when it is not exact in thousandths or too large. */
export function baseQtyMilli(qtyMilli: number, factor: string | undefined): number | null {
  const f = factorMilli(factor);
  if (f === null) return null;
  const product = BigInt(qtyMilli) * f;
  if (product % 1000n !== 0n) return null;
  const base = product / 1000n;
  return base > MAX_MILLI ? null : Number(base);
}

/** The cost of the base quantity, rounded half up once: the line cost a pack line is judged against. */
export function lineCostMinor(baseMilli: number, costMinor: number): number {
  return Number((BigInt(baseMilli) * BigInt(costMinor) + 500n) / 1000n);
}

/** A sale line's quantity as it was entered: "2 carton" for a pack line, the base quantity otherwise. */
export function enteredQty(line: SaleLine): string {
  return line.sale_unit ? `${showQty(line.sale_qty ?? '0')} ${line.sale_unit}` : showQty(line.qty ?? '0');
}

/** A sale line's price in the unit it was entered in: per pack for a pack line, per base unit otherwise. */
export function enteredPriceMinor(line: SaleLine): number {
  return line.sale_unit ? (line.sale_unit_price_minor ?? 0) : (line.unit_price_minor ?? 0);
}
