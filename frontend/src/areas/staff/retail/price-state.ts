import { RETAIL_CURRENCY, type ResolvedLine, type SaleLine } from '../../../api/retail';
import { parseMinor } from '../../../components/money';
import { showQty } from './maths';

// Price lists on the sale form (ADR-033, issue #195): the pure rules the form shows before it sends. The server
// resolves every price (the customer's list, then the group's, then the item for the unit and quantity, then the
// retail price) and refuses what it cannot justify; this file only decides what the form shows. A price the seller
// types that differs from the resolved one is an override: it needs retail.price.override and a reason, as the
// server insists (403, 422 override_reason_required). Money is integer minor units and nothing rounds here.

/** The shortest reason the server accepts for a changed price. */
export const MIN_REASON = 5;

/** What a sale line holds of its price: the effective price as typed or resolved, whether the seller typed it, and why. */
export interface PriceLine {
  price: string;
  /** The seller typed this price: it is kept whatever the list says, and is an override when it differs. */
  typed?: boolean;
  reason?: string;
}

/** The lines with the resolved price on every line the seller did not type; unchanged without a resolution. */
export function pricedLines<T extends PriceLine>(lines: T[], resolved: readonly ResolvedLine[] | undefined): T[] {
  if (!resolved) return lines;
  return lines.map((l, i) => {
    const r = resolved[i];
    return l.typed || !r || r.unit_price_minor === undefined ? l : { ...l, price: String(r.unit_price_minor) };
  });
}

/** True when the seller typed a price that differs from the one the server resolved for the line. */
export function isOverride(line: PriceLine, resolved: ResolvedLine | undefined): boolean {
  if (!line.typed || !resolved) return false;
  const typed = parseMinor(line.price, RETAIL_CURRENCY);
  return typed !== null && typed !== resolved.unit_price_minor;
}

export const reasonOk = (reason: string | undefined): boolean => (reason ?? '').trim().length >= MIN_REASON;

/** A line that is refused because the strict list has no price for it. */
export const hasNoPrice = (resolved: ResolvedLine | undefined): boolean => resolved?.problem === 'no_price_in_list';

/** The note under a line: which price applied, in plain words. */
export function sourceNote(resolved: ResolvedLine | undefined, listName: string | undefined): string {
  if (!resolved) return '';
  if (hasNoPrice(resolved)) return `${listName ?? 'The price list'} has no price for this item.`;
  if (resolved.price_source === 'list') {
    const from = Number(resolved.break_min_qty ?? '0') > 0 ? `, from ${showQty(resolved.break_min_qty ?? '')}` : '';
    return `Price list ${listName ?? ''}${from}`.replace('  ', ' ');
  }
  return 'Retail price';
}

/** What a saved sale line says about its price (receipt and history); empty for a line made without price lists. */
export function receiptNote(line: SaleLine, money: (minor: number) => string): string {
  const p = line.price;
  if (!p) return '';
  const list = p.price_list ? `Price list ${p.price_list}` : '';
  const from = Number(p.break_min_qty ?? '0') > 0 ? `, from ${showQty(p.break_min_qty ?? '')}` : '';
  if (p.source === 'list') return `${list}${from}`;
  if (p.source === 'order_agreed') return `Price agreed on the order (today ${money(p.resolved_unit_price_minor ?? 0)})`;
  if (p.source === 'override') return `Price changed from ${money(p.resolved_unit_price_minor ?? 0)}${list ? ` (${list}${from})` : ''}: ${p.override_reason ?? ''}`;
  return 'Retail price';
}
