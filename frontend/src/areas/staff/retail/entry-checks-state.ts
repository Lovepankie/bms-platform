import { RETAIL_CURRENCY, type EntryConfirmation, type EntryNorms, type EntryUsage, type Product } from '../../../api/retail';
import { formatMinor } from '../../../components/money';
import { plural } from '../../../app/glossary';
import { parseQty, qtyString, showQty } from './maths';
import { baseQtyMilli } from './units-state';

// The entry warnings (issue #353, ADR-093): rule based checks of a line against the item's own history, read once per item from
// GET /retail/products/{id}/entry-norms. A line in another unit than the item's usual one, a quantity ten times the usual or a
// tenth of it, and a selling price twice or half the last one are each said in one plain sentence before saving. A warning never
// blocks: the person confirms it (and it is recorded) or changes the line. No history, a history too short, or an item whose
// history could not be read gives no warning. The server's own refusals (the price floor, stock) are not touched here.

export type EntryScreen = 'buy' | 'sell' | 'move' | 'count';
export type EntryKind = 'unit' | 'quantity' | 'price';

export interface EntryWarning {
  /** The line, the kind and the sentence: a confirmed warning stays confirmed until the line says something else. */
  key: string;
  lineKey: string;
  kind: EntryKind;
  text: string;
  /** The field Change takes the cursor to. */
  fieldId: string;
  confirmation: EntryConfirmation;
}

/** One line as the screen holds it, in the unit it was entered in. */
export interface LineEntry {
  screen: EntryScreen;
  product: Product;
  lineKey: string;
  /** The quantity entered, in thousandths of the entered unit; null when it is not a quantity yet (no warning then). */
  qtyMilli: number | null;
  qtyFieldId: string;
  /** The pack unit entered; absent for the item's own unit. */
  unitId?: string;
  unitName?: string;
  unitFieldId?: string;
  /** The screen offers a choice of unit for this line (pack sizes on and the item has packs); else no unit warning. */
  unitChoosable?: boolean;
  /** Base units in one entered unit: the pack's size (on Buy stock, this delivery's). */
  factor?: string;
  /** Sell only: the price entered for one entered unit. */
  priceMinor?: number | null;
  priceFieldId?: string;
  /** Count only: what the records hold at this branch, in thousandths of the item's own unit. */
  recordedMilli?: number;
}

const VERB: Record<EntryScreen, string> = { buy: 'buy', sell: 'sell', move: 'move', count: 'count' };

const COUNT = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 3 });

/** "1 roll", "200 metres", "2.5 metres", "5,000 metres". */
export function amountOf(milli: number, unit: string): string {
  return `${COUNT.format(milli / 1000)} ${milli === 1000 ? unit : plural(unit)}`;
}

/** A usual quantity for people: whole from 10 up, one decimal under 10, three under 0.1. */
export function roundedMilli(milli: number): number {
  if (milli >= 10_000) return Math.round(milli / 1000) * 1000;
  if (milli >= 100) return Math.round(milli / 100) * 100;
  return Math.max(1, Math.round(milli));
}

/** True when `entered` is `factor` times `usual` or more, or `factor` times less. */
export function farFrom(entered: number, usual: number, factor: number): boolean {
  if (usual <= 0 || entered <= 0) return false;
  return entered >= usual * factor || entered * factor <= usual;
}

/** The median of a usage, in thousandths of the item's own unit; null when the server gave none. */
export function medianMilli(usage: EntryUsage | undefined): number | null {
  const text = usage?.median_qty;
  if (text === undefined) return null;
  const milli = parseQty(text);
  return milli !== null && milli > 0 ? milli : null;
}

/** The history a screen compares with: a move with too few moves of its own compares with the purchases. */
function usageFor(norms: EntryNorms, screen: EntryScreen): EntryUsage | undefined {
  if (screen === 'buy' || screen === 'count') return norms.buy;
  if (screen === 'sell') return norms.sell;
  return medianMilli(norms.move) !== null ? norms.move : norms.buy;
}

/** Thousandths of the entered unit to thousandths of the item's own unit; null when it does not convert exactly. */
function toBase(milli: number, factor: string | undefined): number | null {
  return factor ? baseQtyMilli(milli, factor) : milli;
}

/** Thousandths of the item's own unit shown in the entered unit, rounded for people. */
function inEnteredUnit(baseMilli: number, factor: string | undefined): number {
  const f = factor ? parseQty(factor) : null;
  return roundedMilli(f ? (baseMilli * 1000) / f : baseMilli);
}

/**
 * The warnings of one line, at most one about its quantity (the unit first, since a wrong unit makes the quantity look wrong
 * too) and one about its price. Empty without norms.
 */
export function entryWarnings(norms: EntryNorms | undefined, e: LineEntry): EntryWarning[] {
  if (!norms || e.qtyMilli === null || e.qtyMilli <= 0) return [];
  const own = norms.unit ?? e.product.unit ?? 'item';
  const entered = e.unitId ? (e.unitName ?? own) : own;
  const verb = VERB[e.screen];
  const usage = usageFor(norms, e.screen);
  const out: EntryWarning[] = [];
  const base = toBase(e.qtyMilli, e.factor);
  const record = (kind: EntryKind, text: string, fieldId: string, extra: Partial<EntryConfirmation> = {}): EntryWarning => ({
    key: `${e.lineKey}|${kind}|${text}`,
    lineKey: e.lineKey,
    kind,
    text,
    fieldId,
    confirmation: {
      product_id: e.product.id ?? '',
      screen: e.screen,
      kind,
      entered_qty: showQty(qtyString(e.qtyMilli ?? 0)),
      ...(e.unitId ? { entered_unit_id: e.unitId } : {}),
      ...(usage?.median_qty !== undefined ? { median_qty: usage.median_qty } : {}),
      ...extra,
    },
  });

  // The unit: Buy stock and Sell, where a unit is chosen; Move stock and Count stock are always in the item's own unit.
  const usual = usage?.usual_unit;
  const unitDiffers = (e.screen === 'buy' || e.screen === 'sell') && e.unitChoosable === true && usual !== undefined && (usage?.usual_unit_id ?? undefined) !== (e.unitId ?? undefined);
  if (unitDiffers) {
    out.push(record('unit', `You usually ${verb} this in ${plural(usual)}; you entered ${amountOf(e.qtyMilli, entered)}. Is that right?`, e.unitFieldId ?? e.qtyFieldId));
  } else if (base !== null) {
    const recorded = e.screen === 'count' && e.recordedMilli !== undefined && e.recordedMilli > 0 ? e.recordedMilli : null;
    const median = recorded ?? medianMilli(usage);
    if (median !== null && farFrom(base, median, norms.quantity_factor ?? 10)) {
      const text = recorded !== null
        ? `The records show ${amountOf(recorded, own)} here; you counted ${amountOf(base, own)}. Is that right?`
        : e.screen === 'count'
          ? `You usually buy about ${amountOf(roundedMilli(median), own)} of this at a time; you counted ${amountOf(base, own)}. Is that right?`
          : `You usually ${verb} about ${amountOf(inEnteredUnit(median, e.factor), entered)} of this at a time; you entered ${amountOf(e.qtyMilli, entered)}. Is that right?`;
      out.push(record('quantity', text, e.qtyFieldId, recorded !== null ? { median_qty: showQty(qtyString(recorded)) } : {}));
    }
  }

  // The price: Sell only, against the last price charged per piece. The price floor (retail.price.below_cost) stays the server's.
  const last = norms.last_sell_minor;
  if (e.screen === 'sell' && last !== undefined && last > 0 && e.priceMinor !== undefined && e.priceMinor !== null && e.priceMinor > 0) {
    const f = e.factor ? parseQty(e.factor) : null;
    const perBase = f ? (e.priceMinor * 1000) / f : e.priceMinor;
    if (farFrom(perBase, last, norms.price_factor ?? 2)) {
      const lastHere = f ? Math.round((last * f) / 1000) : last;
      const money = (m: number) => formatMinor(m, RETAIL_CURRENCY);
      out.push(record('price', `You last sold this at ${money(lastHere)} a ${entered}; you entered ${money(e.priceMinor)}. Is that right?`, e.priceFieldId ?? e.qtyFieldId, {
        entered_price_minor: e.priceMinor,
        last_price_minor: lastHere,
      }));
    }
  }
  return out;
}
