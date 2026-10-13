import { RETAIL_CURRENCY, type Product, type ResolvedLine, type ResolveRequest, type SalePayment, type SaleRequest } from '../../../api/retail';
import { parseMinor } from '../../../components/money';
import { lineTotalMinor, milliOf, parseQty, qtyString, showQty } from './maths';
import { hasNoPrice, isOverride, reasonOk } from './price-state';
import { baseQtyMilli, chosenPack, lineCostMinor, packPriceMinor } from './units-state';

// The sale form's pure logic: the running total, the early hints and the request it submits. The
// hints (stock and price floor) only save a round trip: the server is the authority and refuses
// the same cases with insufficient_stock and price_below_cost (api/retail-errors.ts). The cost is
// known to the form only for a user holding retail.profit.read, so for anyone else only the
// server can refuse a price.

export interface DraftLine {
  product: Product;
  qty: string;
  /** Per the chosen unit: per pack for a pack line, per base unit otherwise. */
  price: string;
  /** A pack unit of the product (ADR-033, units capability); absent means the base unit. */
  unitId?: string;
  /** The seller typed the price (price lists on): it is kept whatever the list says, and is an override when it differs. */
  typed?: boolean;
  /** Why the typed price differs from the resolved one; required then. */
  reason?: string;
}

/** The price the server defaults to for the line's unit: the sell price, times the factor for a pack. */
export function defaultPrice(product: Product, unitId: string | undefined): number {
  const pack = chosenPack(product, unitId);
  return pack ? packPriceMinor(product.sell_minor ?? 0, pack.factor) : (product.sell_minor ?? 0);
}

/** The line in another unit: the price goes back to that unit's default, as the form shows it. */
export function withUnit(line: DraftLine, unitId: string): DraftLine {
  const next = unitId || undefined;
  return { ...line, unitId: next, price: String(defaultPrice(line.product, next)), typed: false, reason: undefined };
}

export interface Draft {
  branchId: string;
  method: SalePayment;
  customerId: string;
  newBuyerName: string;
  newBuyerContact: string;
  dueDate: string;
  /** A price list chosen for the sale (retail.pricelist.use), instead of the customer's own or the group's. */
  priceListId?: string;
  /** Why a credit sale goes above the buyer's credit limit (customer accounts; retail.credit.override); sent when filled in. */
  creditReason?: string;
  lines: DraftLine[];
}

/**
 * What customer accounts add to the form (the customer_accounts capability, ADR-033, issue #196): a credit sale names a buyer
 * from the list (a typed name is refused by the server), and the buyer's terms give the date it falls due when none is typed.
 * Absent while the capability is off, and then the form is exactly what it was.
 */
export interface Accounts {
  /** The chosen buyer's payment terms in days, when they have terms. */
  termsDays?: number;
}

/**
 * What price lists add to the form (the price_lists capability): the server's resolution of the lines, whether it is
 * still being fetched, and the lines already given their resolved prices. Absent while the capability is off, and then
 * the form is exactly what it was before price lists.
 */
export interface Pricing {
  resolved?: readonly ResolvedLine[];
  checking: boolean;
}

export function newLine(product: Product): DraftLine {
  return { product, qty: '1', price: String(product.sell_minor ?? 0) };
}

/** Quantity and unit price as numbers when both are valid, and the line total they give. */
export function lineFigures(line: DraftLine): { qtyMilli: number; priceMinor: number; totalMinor: number } | null {
  const qtyMilli = parseQty(line.qty);
  const priceMinor = parseMinor(line.price, RETAIL_CURRENCY);
  if (qtyMilli === null || priceMinor === null) return null;
  return { qtyMilli, priceMinor, totalMinor: lineTotalMinor(priceMinor, qtyMilli) };
}

/** An early refusal for one line, in plain words, or null: more than the branch holds, or a price not above cost. */
export function lineHint(line: DraftLine): string | null {
  const f = lineFigures(line);
  if (f === null) return null;
  const { qty, cost_minor: cost, unit } = line.product;
  const pack = chosenPack(line.product, line.unitId);
  // A pack line is checked in base units, as the server does: the stock, and the line total against the line cost.
  const baseMilli = pack ? baseQtyMilli(f.qtyMilli, pack.factor) : f.qtyMilli;
  if (baseMilli === null) return `${line.qty} ${pack?.unit ?? ''} does not convert exactly to ${unit ?? 'items'}. Change the quantity.`;
  if (qty !== undefined && baseMilli > Math.max(milliOf(qty), 0)) {
    return `Only ${qty.startsWith('-') ? '0' : showQty(qty)} ${unit ?? 'items'} in stock at this branch.`;
  }
  if (cost !== undefined && pack && f.totalMinor <= lineCostMinor(baseMilli, cost)) return 'The price must be above what the item cost.';
  if (cost !== undefined && !pack && f.priceMinor <= cost) return 'The price must be above what the item cost.';
  return null;
}

/** The running total of the valid lines, in integer minor units. */
export function draftTotal(lines: DraftLine[]): number {
  return lines.reduce((sum, l) => sum + (lineFigures(l)?.totalMinor ?? 0), 0);
}

/** What stops the sale being saved, in plain words, or null when it can be saved. */
export function draftProblem(draft: Draft, pricing?: Pricing, accounts?: Accounts): string | null {
  if (draft.lines.length === 0) return 'Add at least one item.';
  if (draft.lines.some((l) => lineFigures(l) === null)) return 'Check the quantity and price on each item.';
  if (draft.lines.some((l) => lineHint(l) !== null)) return 'Fix the items marked above.';
  if (pricing) {
    if (pricing.checking) return 'Checking the prices.';
    if (!pricing.resolved) return 'The prices could not be checked. Try again.';
    if (pricing.resolved.some(hasNoPrice)) return 'A price list has no price for an item marked above.';
    if (draft.lines.some((l, i) => isOverride(l, pricing.resolved?.[i]) && !reasonOk(l.reason))) return 'Give a reason for each changed price.';
  }
  if (draft.method === 'credit') {
    if (accounts) {
      // Customer accounts: a buyer from the list, and a date unless the buyer's terms give one.
      if (draft.customerId === '') return 'Choose the credit buyer from the list, or add them first.';
      if (draft.dueDate === '' && accounts.termsDays === undefined) return 'Enter the date the buyer will pay.';
      return null;
    }
    if (draft.customerId === '' && draft.newBuyerName.trim() === '') return 'Choose or add the buyer for a credit sale.';
    if (draft.dueDate === '') return 'Enter the date the buyer will pay.';
  }
  return null;
}

/**
 * The question the form asks the server while price lists are on: the price and source of every line for the chosen
 * customer and list. Null until every line has a quantity the server can read, so a half-typed one asks nothing.
 */
export function resolveRequestOf(draft: Draft): ResolveRequest | null {
  if (draft.lines.length === 0) return null;
  const lines: ResolveRequest['lines'] = [];
  for (const l of draft.lines) {
    const qty = parseQty(l.qty);
    if (qty === null) return null;
    const pack = chosenPack(l.product, l.unitId);
    lines.push({ product_id: l.product.id ?? '', qty: qtyString(qty), ...(pack ? { unit_id: pack.unit_id } : {}) });
  }
  return { branch_id: draft.branchId, ...(draft.customerId ? { customer_id: draft.customerId } : {}), ...(draft.priceListId ? { price_list_id: draft.priceListId } : {}), lines };
}

export function buildSaleRequest(draft: Draft, pricing?: Pricing, accounts?: Accounts): SaleRequest {
  const credit = draft.method === 'credit';
  return {
    branch_id: draft.branchId,
    payment_method: draft.method,
    // With price lists on, a named customer sets the price list on any sale; otherwise only a credit sale names one.
    ...((credit || pricing) && draft.customerId ? { customer_id: draft.customerId } : {}),
    ...(pricing && draft.priceListId ? { price_list_id: draft.priceListId } : {}),
    ...(credit && !draft.customerId ? { buyer_name: draft.newBuyerName.trim() } : {}),
    ...(credit && !draft.customerId && draft.newBuyerContact.trim() ? { buyer_contact: draft.newBuyerContact.trim() } : {}),
    // With customer accounts on and no date typed, the server dates the sale from the buyer's terms.
    ...(credit && (draft.dueDate !== '' || !accounts) ? { due_date: draft.dueDate } : {}),
    ...(credit && accounts && draft.creditReason?.trim() ? { credit_override_reason: draft.creditReason.trim() } : {}),
    lines: draft.lines.map((l, i) => {
      const f = lineFigures(l);
      if (!f) throw new Error('invalid line');
      const pack = chosenPack(l.product, l.unitId);
      // With price lists on, only a price that differs from the resolved one is sent, with its reason (an override).
      const override = pricing ? isOverride(l, pricing.resolved?.[i]) : f.priceMinor !== defaultPrice(l.product, pack?.unit_id);
      return {
        product_id: l.product.id ?? '',
        qty: qtyString(f.qtyMilli),
        ...(pack ? { unit_id: pack.unit_id } : {}),
        ...(override ? { unit_price_minor: f.priceMinor } : {}),
        ...(pricing && override ? { price_override_reason: (l.reason ?? '').trim() } : {}),
      };
    }),
  };
}
