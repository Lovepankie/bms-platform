import {
  RETAIL_CURRENCY, businessToday,
  type GoodsReceipt, type GoodsReceiptRequest, type Pack, type Product, type PurchaseOrder, type PurchaseOrderLine, type PurchaseOrderOutcome, type PurchaseOrderRequest,
} from '../../../api/retail';
import { parseMinor } from '../../../components/money';
import { plural } from '../../../app/glossary';
import { lineTotalMinor, milliOf, parseQty, qtyString, showQty } from './maths';
import { baseQtyMilli, buyingPacks, deliveredFactor, factorMilli, packPriceMinor, sameFactor } from './units-state';

// Optional purchasing (the purchasing capability, ADR-033, issue #198): the pure rules of the purchase order form and the
// goods received form. The server decides every rule again (totals, stock, the over-receipt allowance, the numbers); these are
// the running figures a form shows before it sends. Money is integer minor units and quantities are integer thousandths, so
// nothing here is floating point. A line is entered in a unit (a pack of the item, or its base unit), and its total is the
// quantity in that unit times the cost of one such unit, rounded half up once.

// ---- Purchase orders -------------------------------------------------------------------------------------------------

/** One line of the order form: an item, the unit it is counted in, how many and what one costs. */
export interface OrderLineForm {
  productId: string;
  code: string;
  description: string;
  /** What the quantity is counted in, for people: the base unit, or the name of the pack. */
  unitLabel: string;
  /** The item's base unit, which a pack is chosen away from and back to. */
  baseUnit: string;
  /** The packs the item can be bought in (the units capability); empty means the unit is fixed. */
  packs: Pack[];
  /** The pack chosen; absent means the base unit. */
  unitId?: string;
  qty: string;
  /** Cost of one unit as typed, in whole minor units. */
  cost: string;
  /** The item's cost per base unit when the session may see it, so a change of pack suggests a pack cost. */
  baseCostMinor?: number;
}

export interface OrderForm {
  supplierId: string;
  branchId: string;
  expectedOn: string;
  note: string;
  lines: OrderLineForm[];
}

export const blankOrder = (branchId = ''): OrderForm => ({ supplierId: '', branchId, expectedOn: '', note: '', lines: [] });

/** A new line for an item: its base unit, the cost the session may see as a suggestion, and no quantity yet. */
export function orderLineFor(product: Product, unitsOn: boolean): OrderLineForm {
  return {
    productId: product.id ?? '',
    code: product.code ?? '',
    description: product.description ?? '',
    unitLabel: product.unit ?? '',
    baseUnit: product.unit ?? '',
    packs: unitsOn ? buyingPacks(product) : [],
    qty: '',
    cost: product.cost_minor !== undefined ? String(product.cost_minor) : '',
    ...(product.cost_minor !== undefined ? { baseCostMinor: product.cost_minor } : {}),
  };
}

/** The line in another unit: a pack costs its factor times a piece, so the suggested cost follows when the item's cost is known. */
export function withOrderUnit(line: OrderLineForm, unitId: string): OrderLineForm {
  const pack = line.packs.find((k) => k.unit_id === unitId);
  const cost = line.baseCostMinor === undefined ? line.cost : String(pack ? packPriceMinor(line.baseCostMinor, pack.factor) : line.baseCostMinor);
  return { ...line, unitId: pack ? unitId : undefined, unitLabel: pack ? (pack.unit ?? '') : line.baseUnit, cost };
}

/** The quantity of a line in thousandths, or null while it is not a positive quantity with at most three places. */
export const orderQty = (l: OrderLineForm): number | null => parseQty(l.qty);

/** The cost of one unit in minor units, or null while it is not a whole non-negative amount. */
export const orderCost = (l: OrderLineForm): number | null => parseMinor(l.cost, RETAIL_CURRENCY);

/** Quantity times the cost of one unit, rounded half up once; 0 while either is not yet valid. */
export function orderLineTotal(l: OrderLineForm): number {
  const qty = orderQty(l);
  const cost = orderCost(l);
  return qty === null || cost === null ? 0 : lineTotalMinor(cost, qty);
}

export const orderTotal = (lines: OrderLineForm[]): number => lines.reduce((sum, l) => sum + orderLineTotal(l), 0);

/** Why a line cannot be saved yet, in plain words; null when it can. */
export function orderLineProblem(l: OrderLineForm): string | null {
  if (orderQty(l) === null) return 'Enter how many, as a number above zero with at most three decimals.';
  if (orderCost(l) === null) return 'Enter what one costs, in whole shillings.';
  return null;
}

/** Why the order cannot be saved yet; null when it can. */
export function orderProblem(form: OrderForm): string | null {
  if (form.supplierId === '') return 'Choose the supplier.';
  if (form.lines.length === 0) return 'Add at least one item.';
  if (form.lines.some((l) => orderLineProblem(l) !== null)) return 'Check the quantity and cost of each item.';
  return null;
}

/** The request for a draft order. Absent dates and branch are left out, so the server takes its defaults. */
export function buildOrderRequest(form: OrderForm): PurchaseOrderRequest {
  return {
    supplier_id: form.supplierId,
    ...(form.branchId ? { branch_id: form.branchId } : {}),
    ...(form.expectedOn ? { expected_on: form.expectedOn } : {}),
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
    lines: form.lines.map((l) => ({
      product_id: l.productId,
      qty: qtyString(orderQty(l) ?? 0),
      cost_minor: orderCost(l) ?? 0,
      ...(l.unitId ? { unit_id: l.unitId } : {}),
    })),
  };
}

/** The form of a draft order being edited. Its lines keep the unit they were entered in; changing the unit means a new line. */
export function formFromOrder(order: PurchaseOrder): OrderForm {
  return {
    supplierId: order.supplier_id ?? '',
    branchId: order.branch_id ?? '',
    expectedOn: order.expected_on ?? '',
    note: order.note ?? '',
    lines: (order.lines ?? []).map((l) => {
      const inPack = l.unit_id !== undefined && (l.unit_factor === undefined || factorMilli(l.unit_factor) !== 1000n);
      return {
        productId: l.product_id ?? '',
        code: l.code ?? '',
        description: l.description ?? '',
        unitLabel: l.unit ?? '',
        baseUnit: l.unit ?? '',
        packs: [],
        ...(inPack ? { unitId: l.unit_id } : {}),
        qty: showQty(l.unit_qty ?? l.qty ?? ''),
        cost: String(l.unit_cost_minor ?? l.cost_minor ?? 0),
      };
    }),
  };
}

/** A quantity held in base units, shown in the unit its line was entered in when that is exact, and in base units otherwise. */
export function inLineUnit(base: string | undefined, line: { unit_id?: string; unit_factor?: string }): string {
  const milli = milliOf(base ?? '0');
  const factor = line.unit_id !== undefined && line.unit_factor !== undefined ? factorMilli(line.unit_factor) : 1000n;
  if (factor === null || factor === 0n) return showQty(qtyString(milli));
  const scaled = BigInt(milli) * 1000n;
  return scaled % factor === 0n ? showQty(qtyString(Number(scaled / factor))) : showQty(qtyString(milli));
}

export type OrderStatus = NonNullable<PurchaseOrder['status']>;

export const ORDER_STATUS_WORDS: Record<OrderStatus, string> = {
  draft: 'Draft',
  sent: 'Sent',
  partly_received: 'Partly received',
  received: 'Received',
  cancelled: 'Cancelled',
};

export const orderStatusWords = (status: string | undefined): string => ORDER_STATUS_WORDS[status as OrderStatus] ?? (status ?? '');

/** What may be done to an order in its status; the server decides again. */
export const canEditOrder = (o: PurchaseOrder): boolean => o.status === 'draft';
export const canSendOrder = (o: PurchaseOrder): boolean => o.status === 'draft';
export const canReceiveOrder = (o: PurchaseOrder): boolean => o.status === 'sent' || o.status === 'partly_received';
export const canCancelOrder = (o: PurchaseOrder): boolean => o.status === 'draft' || o.status === 'sent' || o.status === 'partly_received';

/** What sending an order came to, in words: sent, or waiting for a second person (202) who can approve purchase orders in Approvals. */
export function sendOutcomeWords(outcome: PurchaseOrderOutcome): string {
  return outcome.executed === false
    ? 'This order is large enough to need a second person. It stays a draft until someone who can approve purchase orders does so in Approvals, and then it is sent.'
    : `Sent ${outcome.order?.order_no ?? 'the order'}. You can now receive goods against it.`;
}

/**
 * The quantity on an order line still to come, in base thousandths (never below zero). A line ordered in a pack counts the packs
 * received whatever each held (D23, #554: received_unit_qty), so ten cartons of 30 fill an order of ten cartons of 24.
 */
export function stillToComeMilli(line: PurchaseOrderLine): number {
  if (line.unit_id && line.unit_qty !== undefined && line.received_unit_qty !== undefined) {
    return baseQtyMilli(Math.max(0, milliOf(line.unit_qty) - milliOf(line.received_unit_qty)), line.unit_factor) ?? 0;
  }
  return Math.max(0, milliOf(line.qty ?? '0') - milliOf(line.received_qty ?? '0'));
}

// ---- Goods received --------------------------------------------------------------------------------------------------

/** One line of the goods received form. Against an order it carries the order line; on a blind receipt, the item. */
export interface ReceiveLineForm {
  /** Stable key for the list. */
  key: string;
  orderLineId?: string;
  productId: string;
  code: string;
  description: string;
  unitLabel: string;
  /** The base unit's name, for the selling price, which is always per base unit. */
  baseUnit: string;
  packs: Pack[];
  unitId?: string;
  /** D23 (#554): how many base units each pack held in this delivery, typed; starts at usualSize. */
  packSize?: string;
  /** The pack's size before this delivery: the item's usual one, or on an order line the size it was ordered at. */
  usualSize?: string;
  /** D23 (#554): make packSize the pack's usual size from now on. */
  fromNowOn?: boolean;
  /**
   * Issue #624: the line's pack has its own selling price, for an order line whose packs the form does not carry; then only
   * someone who may change prices is offered "use from now on".
   */
  packHasOwnPrice?: boolean;
  /** Received now; blank means none of this line arrives. */
  qty: string;
  cost: string;
  /** A new selling price per base unit; blank leaves the price as it is. */
  sell: string;
  /** What was ordered and what is still to come, in the unit entered, for the hint (order lines only). */
  orderedText?: string;
  stillToCome?: string;
}

export interface ReceiveForm {
  supplierId: string;
  branchId: string;
  receivedOn: string;
  deliveryRef: string;
  note: string;
  /** Hold the delivery as received and not costed until its landed cost is in (issue #199); absent means not held. */
  hold?: boolean;
  lines: ReceiveLineForm[];
}

export const blankReceipt = (branchId = ''): ReceiveForm => ({ supplierId: '', branchId, receivedOn: businessToday(), deliveryRef: '', note: '', lines: [] });

/**
 * What is still to come on an order line, in the unit the line was ordered in, as a quantity string; blank when nothing is left
 * or when it does not convert exactly to that unit (then the person types it).
 */
export function defaultReceiveQty(line: PurchaseOrderLine): string {
  const left = stillToComeMilli(line);
  if (left <= 0) return '';
  const factor = line.unit_id !== undefined ? factorMilli(line.unit_factor) : 1000n;
  if (factor === null || factor === 0n) return '';
  const scaled = BigInt(left) * 1000n;
  if (scaled % factor !== 0n) return '';
  return qtyString(Number(scaled / factor));
}

/** The receive form for an order: one line for each order line that still has something to come, quantity filled with what is left. */
export function receiveFormFor(order: PurchaseOrder, branchId = ''): ReceiveForm {
  const lines: ReceiveLineForm[] = (order.lines ?? [])
    .filter((l) => stillToComeMilli(l) > 0)
    .map((l) => {
      const qty = defaultReceiveQty(l);
      return {
        key: l.id ?? '',
        orderLineId: l.id,
        productId: l.product_id ?? '',
        code: l.code ?? '',
        description: l.description ?? '',
        unitLabel: l.unit ?? l.base_unit ?? '',
        baseUnit: l.base_unit ?? l.unit ?? '',
        packs: [],
        ...(l.unit_id && l.unit_factor && factorMilli(l.unit_factor) !== 1000n ? { unitId: l.unit_id, packSize: showQty(l.unit_factor), usualSize: l.unit_factor } : {}),
        qty: qty ? showQty(qty) : '',
        cost: String(l.unit_cost_minor ?? l.cost_minor ?? 0),
        sell: '',
        orderedText: showQty(l.unit_qty ?? l.qty ?? '0'),
        ...(qty ? { stillToCome: showQty(qty) } : {}),
      };
    });
  return { ...blankReceipt(branchId), supplierId: order.supplier_id ?? '', lines };
}

/** A blind receipt line for an item: no order, so the unit, the cost and the quantity are all the person's to enter. */
export function blindLineFor(product: Product, unitsOn: boolean, key: string): ReceiveLineForm {
  return {
    key,
    productId: product.id ?? '',
    code: product.code ?? '',
    description: product.description ?? '',
    unitLabel: product.unit ?? '',
    baseUnit: product.unit ?? '',
    packs: unitsOn ? buyingPacks(product) : [],
    qty: '',
    cost: product.cost_minor !== undefined ? String(product.cost_minor) : '',
    sell: '',
  };
}

/** A blind receipt line in another unit; the cost is left for the person to retype, since the item's cost is per base unit. */
export function withReceiveUnit(line: ReceiveLineForm, unitId: string): ReceiveLineForm {
  const pack = line.packs.find((k) => k.unit_id === unitId);
  const { packSize: _size, usualSize: _usual, fromNowOn: _now, ...rest } = line;
  return {
    ...rest,
    unitId: pack ? unitId : undefined,
    unitLabel: pack ? (pack.unit ?? '') : line.baseUnit,
    ...(pack ? { packSize: showQty(pack.factor ?? ''), usualSize: pack.factor } : {}),
  };
}

/** The base units in one pack of the line for this delivery: the typed size, else the usual; null when the typed size is wrong. */
export function receiveFactor(l: ReceiveLineForm): string | null | undefined {
  if (!l.unitId) return undefined;
  const usual = l.usualSize ?? l.packs.find((k) => k.unit_id === l.unitId)?.factor;
  return l.packSize === undefined ? (usual ?? undefined) : deliveredFactor(l.packSize);
}

/** Whether the size typed differs from the usual one, so "use from now on" is offered. */
export const sizeDiffers = (l: ReceiveLineForm): boolean => {
  const f = receiveFactor(l);
  return typeof f === 'string' && !sameFactor(f, l.usualSize);
};

/** Whether the line is being received now: it has something typed in its quantity. */
export const isReceived = (l: ReceiveLineForm): boolean => l.qty.trim() !== '';

/** Why one line cannot be saved yet, in plain words; null when it can (or when nothing of it is received). */
export function receiveLineProblem(l: ReceiveLineForm): string | null {
  if (!isReceived(l)) return null;
  const qty = parseQty(l.qty);
  if (qty === null) return 'Enter how many arrived, as a number above zero with at most three decimals.';
  const factor = receiveFactor(l);
  if (factor === null) return `Enter how many ${plural(l.baseUnit)} were in each ${l.unitLabel}: 1 or more.`;
  if (factor && baseQtyMilli(qty, factor) === null) return `That does not convert exactly to ${l.baseUnit}. Change it.`;
  if (parseMinor(l.cost, RETAIL_CURRENCY) === null) return 'Enter what one cost, in whole shillings.';
  if (l.sell.trim() !== '' && parseMinor(l.sell, RETAIL_CURRENCY) === null) return 'Enter the new selling price in whole shillings, or leave it blank.';
  return null;
}

/** The value of what arrives now, at the cost as typed; 0 for lines that are not valid yet. */
export function receiveTotal(lines: ReceiveLineForm[]): number {
  return lines.reduce((sum, l) => {
    const qty = isReceived(l) ? parseQty(l.qty) : null;
    const cost = parseMinor(l.cost, RETAIL_CURRENCY);
    return sum + (qty === null || cost === null ? 0 : lineTotalMinor(cost, qty));
  }, 0);
}

/** True when more arrives than is still to come on an order line: allowed up to the business's allowance, which the server judges. */
export function overOrdered(l: ReceiveLineForm, order: PurchaseOrder | undefined): boolean {
  if (!order || !l.orderLineId || !isReceived(l)) return false;
  const line = (order.lines ?? []).find((x) => x.id === l.orderLineId);
  const qty = parseQty(l.qty);
  if (!line || qty === null) return false;
  const factor = line.unit_id !== undefined ? factorMilli(line.unit_factor) : 1000n;
  if (factor === null) return false;
  return BigInt(qty) * factor > BigInt(stillToComeMilli(line)) * 1000n;
}

/** Why the receipt cannot be saved yet; null when it can. `blind` means no order, which needs the supplier. */
export function receiptProblem(form: ReceiveForm, blind: boolean): string | null {
  if (blind && form.supplierId === '') return 'Choose the supplier.';
  if (form.branchId === '') return 'Choose where the goods go.';
  if (!form.lines.some(isReceived)) return 'Enter how many arrived for at least one item.';
  if (form.lines.some((l) => receiveLineProblem(l) !== null)) return 'Check the lines marked in red.';
  return null;
}

/** The request for a receipt: only the lines with a quantity, against the order's lines or by item. */
export function buildReceiptRequest(form: ReceiveForm, orderId?: string): GoodsReceiptRequest {
  return {
    ...(orderId ? { order_id: orderId } : { supplier_id: form.supplierId }),
    branch_id: form.branchId,
    received_on: form.receivedOn || undefined,
    ...(form.deliveryRef.trim() ? { delivery_ref: form.deliveryRef.trim() } : {}),
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
    ...(form.hold ? { hold_for_costing: true } : {}),
    lines: form.lines.filter(isReceived).map((l) => ({
      ...(l.orderLineId ? { order_line_id: l.orderLineId } : { product_id: l.productId, ...(l.unitId ? { unit_id: l.unitId } : {}) }),
      ...packSizeOf(l),
      qty: qtyString(parseQty(l.qty) ?? 0),
      cost_minor: parseMinor(l.cost, RETAIL_CURRENCY) ?? 0,
      ...(l.sell.trim() !== '' ? { sell_minor: parseMinor(l.sell, RETAIL_CURRENCY) ?? 0 } : {}),
    })),
  };
}

/** D23 (#554): the size each pack held in this delivery, and "use from now on" only when it differs from the usual size. */
function packSizeOf(l: ReceiveLineForm): { pack_size?: string; use_from_now_on?: boolean } {
  const factor = receiveFactor(l);
  if (!factor) return {};
  return { pack_size: factor, ...(l.fromNowOn && sizeDiffers(l) ? { use_from_now_on: true } : {}) };
}

/** Whether a receipt can still be voided: it stands. The server refuses a billed one or one whose stock has moved. */
export const canVoidReceipt = (r: GoodsReceipt): boolean => r.status === 'posted';

export const RECEIPT_STATUS_WORDS: Record<string, string> = { posted: 'Standing', voided: 'Voided' };

// ---- Settings: tolerances in percent, stored in basis points -----------------------------------------------------------

/** Basis points as the text of a percentage with two places: 100 is "1.00", 25 is "0.25". */
export function bpToPercentText(bp: number | undefined): string {
  const value = bp ?? 0;
  return `${Math.floor(value / 100)}.${String(value % 100).padStart(2, '0')}`;
}

/** What was typed as a percentage ("1", "1.5", "0.25") as basis points; null when it is not 0 to 100 with at most two places. */
export function percentTextToBp(text: string): number | null {
  const m = /^(\d{1,3})(?:\.(\d{1,2}))?$/.exec(text.trim());
  if (!m) return null;
  const bp = Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0') || '0');
  return bp <= 10000 ? bp : null;
}
