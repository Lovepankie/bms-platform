import type { Product } from '../../../api/retail';
import type { DeliverRequest, ItemsRequest, Order, OrderRequest, OrderSource, OrderStatus } from '../../../api/retail-orders';
import { milliOf, qtyString, showQty } from './maths';
import { defaultPrice, lineFigures, lineHint, type DraftLine } from './sale-state';
import { chosenPack } from './units-state';

// The rules of the customer order screens (issue #385, ADR-052), kept apart from the markup so they are tested on their own.
// An order is copied from a chat in seconds: the phone first (it finds the customer and their last place and fee), then the
// items, then the place and the fee. Stock is kept aside when the order is saved; the money is taken on delivery.

export const SOURCES: { value: OrderSource; label: string }[] = [
  { value: 'whatsapp', label: 'WhatsApp' },
  { value: 'instagram', label: 'Instagram' },
  { value: 'facebook', label: 'Facebook' },
  { value: 'phone', label: 'Phone call' },
  { value: 'walk_in', label: 'Walk-in' },
  { value: 'other', label: 'Other' },
];

/** The words for a balance some of which open orders keep aside: what is free to sell and what is kept; null when nothing is kept. */
export function keptWords(qty: string | undefined, reserved: string | undefined): string | null {
  if (!reserved || milliOf(reserved) === 0) return null;
  const free = Math.max(0, milliOf(qty ?? '0') - milliOf(reserved));
  return `${showQty(qtyString(free))} free, ${showQty(reserved)} kept for orders`;
}

export const sourceLabel = (s: string | undefined): string => SOURCES.find((x) => x.value === s)?.label ?? 'Other';

/** The words and the colour of where an order stands. */
export function stepOf(status: string | undefined): { label: string; tone: 'info' | 'warning' | 'success' | 'danger' } {
  switch (status as OrderStatus) {
    case 'packed': return { label: 'Packed', tone: 'warning' };
    case 'out_for_delivery': return { label: 'Out for delivery', tone: 'warning' };
    case 'delivered': return { label: 'Delivered', tone: 'success' };
    case 'cancelled': return { label: 'Cancelled', tone: 'danger' };
    default: return { label: 'Ordered', tone: 'info' };
  }
}

export const isOpen = (o: Pick<Order, 'status'>): boolean => o.status === 'ordered' || o.status === 'packed' || o.status === 'out_for_delivery';

/** The next steps an open order offers, in the order a shop does them. */
export function nextSteps(o: Pick<Order, 'status'>): ('pack' | 'send' | 'deliver' | 'cancel')[] {
  if (o.status === 'ordered') return ['pack', 'send', 'deliver', 'cancel'];
  if (o.status === 'packed') return ['send', 'deliver', 'cancel'];
  if (o.status === 'out_for_delivery') return ['deliver', 'cancel'];
  return [];
}

/** The items of an order change only before it goes out (issue #393): while it is ordered or packed. */
export const itemsMayChange = (o: Pick<Order, 'status'>): boolean => o.status === 'ordered' || o.status === 'packed';

/** The order's items as lines to change, each with its product (as read now) and the price agreed; null until every product is read. */
export function itemsDraft(o: Pick<Order, 'lines'>, products: Record<string, Product | undefined>): DraftLine[] | null {
  const lines: DraftLine[] = [];
  for (const l of o.lines ?? []) {
    const product = products[l.product_id ?? ''];
    if (!product) return null;
    lines.push({ product, qty: l.qty ?? '1', price: String(l.unit_price_minor ?? 0), ...(l.unit_id ? { unitId: l.unit_id } : {}) });
  }
  return lines;
}

/** What stops the new items being saved, in plain words, or null. */
export function itemsProblem(lines: DraftLine[]): string | null {
  if (lines.length === 0) return 'Keep at least one item. To take every item off, cancel the order.';
  if (lines.some((l) => lineFigures(l) === null)) return 'Check the quantity and price on each item.';
  if (lines.some((l) => (lineHint(l) ?? '').includes('does not convert exactly'))) return 'Fix the items marked above.';
  return null;
}

/**
 * The new items for the server. An item already on the order in the same unit sends its price, so the price agreed is kept when
 * it is unchanged (the server compares); a new item sends a price only when it differs from today's, as a new order does.
 */
export function buildItemsRequest(o: Pick<Order, 'lines'>, lines: DraftLine[], note: string): ItemsRequest {
  const onOrder = new Set((o.lines ?? []).map((l) => `${l.product_id}|${l.unit_id ?? ''}`));
  return {
    lines: lines.map((l) => {
      const f = lineFigures(l);
      if (!f) throw new Error('invalid line');
      const pack = chosenPack(l.product, l.unitId);
      const known = onOrder.has(`${l.product.id}|${pack?.unit_id ?? ''}`);
      return {
        product_id: l.product.id ?? '',
        qty: qtyString(f.qtyMilli),
        ...(pack ? { unit_id: pack.unit_id } : {}),
        ...(known || f.priceMinor !== defaultPrice(l.product, pack?.unit_id) ? { unit_price_minor: f.priceMinor } : {}),
        ...(l.reason?.trim() ? { price_override_reason: l.reason.trim() } : {}),
      };
    }),
    ...(note.trim() ? { note: note.trim() } : {}),
  };
}

export interface OrderDraft {
  branchId: string;
  source: OrderSource;
  phone: string;
  customerId: string;
  name: string;
  place: string;
  deliveryDate: string;
  fee: string;
  notes: string;
  lines: DraftLine[];
}

export const blankOrder = (branchId: string, today: string): OrderDraft => ({
  branchId, source: 'whatsapp', phone: '', customerId: '', name: '', place: '', deliveryDate: today, fee: '', notes: '', lines: [],
});

/** The digits of a phone number, enough of them to look the customer up (the server matches the last nine). */
export function lookupKey(phone: string): string | null {
  const digits = phone.replace(/\D/g, '');
  return digits.length >= 9 ? digits : null;
}

/** A whole number of shillings typed in a box, or null; empty is zero. */
export function feeOf(text: string): number | null {
  const t = text.replace(/[\s,]/g, '');
  if (t === '') return 0;
  return /^\d{1,13}$/.test(t) ? Number(t) : null;
}

export function itemsTotal(lines: DraftLine[]): number {
  return lines.reduce((sum, l) => sum + (lineFigures(l)?.totalMinor ?? 0), 0);
}

/** What stops the order being saved, in plain words, or null. */
export function orderProblem(d: OrderDraft, today: string): string | null {
  if (d.customerId === '' && d.name.trim() === '') return 'Type the name of the customer, or pick them from the list.';
  if (d.lines.length === 0) return 'Add at least one item.';
  if (d.lines.some((l) => lineFigures(l) === null)) return 'Check the quantity and price on each item.';
  if (d.lines.some((l) => (lineHint(l) ?? '').includes('does not convert exactly'))) return 'Fix the items marked above.';
  if (feeOf(d.fee) === null) return 'The delivery fee is a whole number of shillings.';
  if (d.deliveryDate !== '' && d.deliveryDate < today) return 'The delivery day cannot be before today.';
  return null;
}

export function buildOrderRequest(d: OrderDraft): OrderRequest {
  const fee = feeOf(d.fee) ?? 0;
  return {
    branch_id: d.branchId,
    source: d.source,
    ...(d.customerId ? { customer_id: d.customerId } : {}),
    ...(d.name.trim() ? { customer_name: d.name.trim() } : {}),
    ...(d.phone.trim() ? { customer_phone: d.phone.trim() } : {}),
    ...(d.place.trim() ? { delivery_place: d.place.trim() } : {}),
    ...(d.deliveryDate ? { delivery_date: d.deliveryDate } : {}),
    delivery_fee_minor: fee,
    ...(d.notes.trim() ? { notes: d.notes.trim() } : {}),
    lines: d.lines.map((l) => {
      const f = lineFigures(l);
      if (!f) throw new Error('invalid line');
      const pack = chosenPack(l.product, l.unitId);
      return {
        product_id: l.product.id ?? '',
        qty: qtyString(f.qtyMilli),
        ...(pack ? { unit_id: pack.unit_id } : {}),
        ...(f.priceMinor !== defaultPrice(l.product, pack?.unit_id) ? { unit_price_minor: f.priceMinor } : {}),
      };
    }),
  };
}

export type PayChoice = 'cash' | 'mobile_money' | 'bank' | 'part' | 'credit' | 'account';

export const PAY_CHOICES: { value: PayChoice; label: string }[] = [
  { value: 'cash', label: 'Cash, all of it' },
  { value: 'mobile_money', label: 'Mobile money, all of it' },
  { value: 'bank', label: 'Bank, all of it' },
  { value: 'part', label: 'Part now, the rest later' },
  { value: 'credit', label: 'All later (credit), fee paid now' },
  { value: 'account', label: 'On the customer account, delivery fee too' },
];

/** The payment choices an order offers: the account, fee included, only for a credit buyer on the list. */
export const payChoicesFor = (o: Pick<Order, 'customer_id' | 'delivery_fee_minor'>) =>
  PAY_CHOICES.filter((c) => c.value !== 'account' || (o.customer_id !== undefined && (o.delivery_fee_minor ?? 0) > 0));

export interface DeliverForm {
  choice: PayChoice;
  paidNow: string;
  paidNowMethod: 'cash' | 'mobile_money' | 'bank';
  dueDate: string;
  note: string;
}

export const blankDeliver = (): DeliverForm => ({ choice: 'cash', paidNow: '', paidNowMethod: 'cash', dueDate: '', note: '' });

/** What is paid at the door and what is left on credit, for the figures the form shows. */
export function deliverFigures(o: Pick<Order, 'items_total_minor' | 'delivery_fee_minor'>, f: DeliverForm): { now: number; later: number } | null {
  const total = (o.items_total_minor ?? 0) + (o.delivery_fee_minor ?? 0);
  if (f.choice === 'cash' || f.choice === 'mobile_money' || f.choice === 'bank') return { now: total, later: 0 };
  const now = f.choice === 'credit' ? (o.delivery_fee_minor ?? 0) : f.choice === 'account' ? (feeOf(f.paidNow) ?? null) : feeOf(f.paidNow);
  if (now === null) return null;
  return { now, later: total - now };
}

/** What stops the delivery being saved, in plain words, or null. The fee is always paid at the door. */
export function deliverProblem(o: Pick<Order, 'items_total_minor' | 'delivery_fee_minor'>, f: DeliverForm): string | null {
  const fig = deliverFigures(o, f);
  if (!fig) return 'Type the amount paid now in whole shillings.';
  const fee = o.delivery_fee_minor ?? 0;
  if (f.choice === 'part') {
    if (fig.now <= 0) return 'Type the amount paid now.';
    if (fig.now < fee) return 'The delivery fee is paid when the goods arrive, so take at least the fee now.';
    if (fig.later <= 0) return 'That is everything, so choose Cash, Mobile money or Bank.';
  }
  if (f.choice === 'account' && fig.now > (o.items_total_minor ?? 0)) return 'With the fee on the account, the money taken now cannot be more than the items.';
  return null;
}

export function buildDeliverRequest(o: Pick<Order, 'delivery_fee_minor'>, f: DeliverForm): DeliverRequest {
  const extra = { ...(f.note.trim() ? { note: f.note.trim() } : {}) };
  if (f.choice === 'cash' || f.choice === 'mobile_money' || f.choice === 'bank') return { payment_method: f.choice, ...extra };
  const now = f.choice === 'credit' ? (o.delivery_fee_minor ?? 0) : (feeOf(f.paidNow) ?? 0);
  return {
    payment_method: 'credit',
    ...(f.choice === 'account' ? { fee_on_account: true } : {}),
    ...(now > 0 ? { paid_now_minor: now, paid_now_method: f.paidNowMethod } : {}),
    ...(f.dueDate ? { due_date: f.dueDate } : {}),
    ...extra,
  };
}
