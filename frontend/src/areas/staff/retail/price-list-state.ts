import { RETAIL_CURRENCY, type NewPriceListItem, type Product } from '../../../api/retail';
import { parseMinor } from '../../../components/money';
import { parseCount, qtyString } from './maths';
import { factorMilli, sellingPacks } from './units-state';

// The price list editor's pure rules (ADR-033, issue #195): what a new price needs before it is sent, the units an
// item may be priced in, and the early warning when a price is not above cost. The server is the authority and
// refuses the same cases (422 validation, 409 price_item_overlap); this only saves a round trip. Prices are integer
// minor units per the chosen unit and no floating point is used.

/** The new-price form as typed. */
export interface ItemForm {
  product?: Product;
  /** The unit the price is for: the item's base unit or one of its active packs. */
  unitId: string;
  /** The quantity break in that unit; empty or 0 means from any quantity. */
  minQty: string;
  price: string;
  validFrom: string;
  /** The last day it applies; empty means open ended. */
  validTo: string;
}

export const emptyItemForm = (today: string): ItemForm => ({ unitId: '', minQty: '', price: '', validFrom: today, validTo: '' });

/** The units an item can be priced in: its base unit, then its active packs (the units capability). */
export function unitChoices(product: Product): { id: string; label: string; base: boolean }[] {
  const base = { id: product.unit_id ?? '', label: product.unit ?? 'unit', base: true };
  return [base, ...sellingPacks(product).map((k) => ({ id: k.unit_id ?? '', label: `${k.unit} (${k.factor} ${product.unit})`, base: false }))];
}

/** The form after choosing an item: its base unit is chosen first. */
export const withProduct = (form: ItemForm, product: Product): ItemForm => ({ ...form, product, unitId: product.unit_id ?? '' });

/** What stops the price being saved, in plain words, or null when it can be sent. */
export function itemProblem(form: ItemForm, today: string): string | null {
  if (!form.product) return 'Choose an item.';
  if (!form.unitId) return 'Choose the unit the price is for.';
  if (parseMinor(form.price, RETAIL_CURRENCY) === null) return 'Enter the price as a number.';
  if (form.minQty.trim() !== '' && parseCount(form.minQty) === null) return 'The quantity must be a number with at most three decimals.';
  if (form.validFrom === '') return 'Enter the first day the price applies.';
  if (form.validFrom < today) return 'A price cannot start in the past. A sale already made keeps its price.';
  if (form.validTo !== '' && form.validTo < form.validFrom) return 'The last day cannot be before the first day.';
  return null;
}

/** The request for a valid form; call only when itemProblem is null. */
export function buildItemRequest(form: ItemForm): NewPriceListItem {
  const min = form.minQty.trim() === '' ? 0 : (parseCount(form.minQty) ?? 0);
  return {
    product_id: form.product?.id ?? '',
    unit_id: form.unitId,
    min_qty: qtyString(min),
    price_minor: parseMinor(form.price, RETAIL_CURRENCY) ?? 0,
    valid_from: form.validFrom,
    ...(form.validTo !== '' ? { valid_to: form.validTo } : {}),
  };
}

/**
 * True when the price is not above the item's cost for that unit (a pack costs its factor times the base cost, rounded
 * half up once). Null when the cost is not known to this session, which sees it only with retail.profit.read.
 */
export function belowCostHint(form: ItemForm): boolean | null {
  const cost = form.product?.cost_minor;
  const price = parseMinor(form.price, RETAIL_CURRENCY);
  if (cost === undefined || price === null || !form.product) return null;
  if (form.unitId === form.product.unit_id) return price <= cost;
  const pack = sellingPacks(form.product).find((k) => k.unit_id === form.unitId);
  const f = pack ? factorMilli(pack.factor) : null;
  if (f === null || f === undefined) return null;
  return price <= Number((BigInt(cost) * f + 500n) / 1000n);
}

/** "From any quantity" for no break, else "From 10". */
export const fromQty = (minQty: string | undefined): string => (Number(minQty ?? '0') > 0 ? `From ${Number(minQty)}` : 'Any quantity');

export const STATUS_LABEL: Record<string, string> = { scheduled: 'Starts later', current: 'In force', ended: 'Ended' };
