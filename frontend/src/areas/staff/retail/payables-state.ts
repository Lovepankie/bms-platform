import {
  RETAIL_CURRENCY,
  type SupplierBillMatch, type SupplierBillRequest, type SupplierOpenItem, type SupplierPaymentRequest, type UnbilledReceiptLine,
} from '../../../api/retail';
import { formatMinor, parseMinor } from '../../../components/money';
import { lineTotalMinor, milliOf, parseQty, qtyString, showQty } from './maths';
import { MIN_REASON } from './account-state';

// Optional purchasing (ADR-033, issue #198): the pure rules of the supplier invoice form, the supplier payment form and the
// payables report. The server decides every rule again; these are the running figures a form shows before it sends. Money is
// integer minor units and quantities are integer thousandths. A supplier's invoice is matched against the goods received (the
// receipt lines it bills) and the order they came from: the server's match says billed, cleared and the difference.

const money = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY);

// ---- Supplier invoices ("bills") -------------------------------------------------------------------------------------

/** One receipt line offered for billing: what the receipt says, and what the supplier's invoice says about it. */
export interface BillLineForm {
  receiptLineId: string;
  receiptNo: string;
  orderNo?: string;
  code: string;
  description: string;
  unit: string;
  /** Received and not yet billed, in the unit it was received in. */
  unbilledQty: string;
  /** The cost of one unit as received, the default of the supplier's price. */
  receivedCostMinor: number;
  ticked: boolean;
  qty: string;
  /** The supplier's price for one unit as typed, in whole minor units. */
  price: string;
}

export interface BillForm {
  supplierId: string;
  supplierRef: string;
  billDate: string;
  dueDate: string;
  /** The invoice total as printed, in whole minor units. */
  total: string;
  note: string;
  varianceReason: string;
  lines: BillLineForm[];
}

export const blankBill = (today: string): BillForm => ({ supplierId: '', supplierRef: '', billDate: today, dueDate: '', total: '', note: '', varianceReason: '', lines: [] });

/** The lines to tick from a supplier's unbilled receipts: quantity defaults to what is unbilled, price to what the receipt cost. */
export function billLinesFrom(unbilled: UnbilledReceiptLine[]): BillLineForm[] {
  return unbilled.map((u) => ({
    receiptLineId: u.receipt_line_id ?? '',
    receiptNo: u.receipt_no ?? '',
    ...(u.order_no ? { orderNo: u.order_no } : {}),
    code: u.code ?? '',
    description: u.description ?? '',
    unit: u.unit ?? '',
    unbilledQty: u.unbilled_qty ?? '0',
    receivedCostMinor: u.unit_cost_minor ?? 0,
    ticked: false,
    qty: showQty(u.unbilled_qty ?? ''),
    price: String(u.unit_cost_minor ?? 0),
  }));
}

export const ticked = (form: BillForm): BillLineForm[] => form.lines.filter((l) => l.ticked);

/** What the supplier billed for a line: quantity times price, rounded half up once; 0 while either is not valid. */
export function billLineTotal(l: BillLineForm): number {
  const qty = parseQty(l.qty);
  const price = parseMinor(l.price, RETAIL_CURRENCY);
  return qty === null || price === null ? 0 : lineTotalMinor(price, qty);
}

/** The sum of the ticked lines. */
export const billLinesTotal = (form: BillForm): number => ticked(form).reduce((sum, l) => sum + billLineTotal(l), 0);

/** Why one ticked line cannot be billed yet; null when it can. */
export function billLineProblem(l: BillLineForm): string | null {
  const qty = parseQty(l.qty);
  if (qty === null) return 'Enter how many the supplier billed, as a number above zero.';
  if (qty > milliOf(l.unbilledQty)) return `At most ${showQty(l.unbilledQty)} ${l.unit} arrived and are not yet on an invoice.`;
  if (parseMinor(l.price, RETAIL_CURRENCY) === null) return 'Enter the supplier\'s price for one, in whole shillings.';
  return null;
}

/** The part of the form the match needs: a supplier, the ticked lines in order, and the invoice details. */
export function matchReady(form: BillForm): boolean {
  const lines = ticked(form);
  return form.supplierId !== '' && form.supplierRef.trim() !== '' && form.billDate !== '' && form.dueDate !== '' && lines.length > 0 && lines.every((l) => billLineProblem(l) === null);
}

/**
 * The request for the invoice. For the dry run (`forMatch`) a total not yet typed is taken as the sum of the lines, so the match
 * shows the three-way figures before the person has typed the printed total; saving needs the typed total.
 */
export function buildBillRequest(form: BillForm, forMatch = false): SupplierBillRequest {
  const typed = parseMinor(form.total, RETAIL_CURRENCY);
  return {
    supplier_id: form.supplierId,
    supplier_ref: form.supplierRef.trim(),
    bill_date: form.billDate,
    due_date: form.dueDate,
    total_minor: typed ?? (forMatch ? billLinesTotal(form) : 0),
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
    ...(form.varianceReason.trim() ? { variance_reason: form.varianceReason.trim() } : {}),
    lines: ticked(form).map((l) => ({ receipt_line_id: l.receiptLineId, qty: qtyString(parseQty(l.qty) ?? 0), unit_price_minor: parseMinor(l.price, RETAIL_CURRENCY) ?? 0 })),
  };
}

/** A key that changes when anything the match depends on changes, so the dry run repeats only then (the variance reason is not part of it). */
export function matchKey(form: BillForm): string {
  return JSON.stringify({ ...buildBillRequest(form, true), variance_reason: undefined, note: undefined });
}

/** Why the invoice cannot be saved yet, in plain words; null when it can. `match` is the server's answer for the same form. */
export function billProblem(form: BillForm, match: SupplierBillMatch | undefined): string | null {
  if (form.supplierId === '') return 'Choose the supplier.';
  if (form.supplierRef.trim() === '') return 'Enter the supplier\'s invoice number.';
  if (form.billDate === '' || form.dueDate === '') return 'Enter the invoice date and the date it is due.';
  if (ticked(form).length === 0) return 'Tick the goods this invoice is for.';
  if (ticked(form).some((l) => billLineProblem(l) !== null)) return 'Check the lines marked in red.';
  if (parseMinor(form.total, RETAIL_CURRENCY) === null) return 'Enter the invoice total as printed on the invoice.';
  if (match?.total_mismatch) return 'The total you typed is not the sum of the lines. Check both against the invoice.';
  if (match?.reason_required && form.varianceReason.trim().length < MIN_REASON) return `Say why the invoice differs, in at least ${MIN_REASON} characters.`;
  return null;
}

/** The difference between what was billed and what the goods were received at, in words. */
export function varianceWords(minor: number | undefined): string {
  const v = minor ?? 0;
  if (v === 0) return 'The same as the goods were received at';
  return v > 0 ? `${money(v)} more than the goods were received at` : `${money(-v)} less than the goods were received at`;
}

/** The same as a sentence about the invoice, for the check on the form. */
export function invoiceSentence(minor: number | undefined): string {
  const v = minor ?? 0;
  if (v === 0) return 'The invoice is the same as what the goods were received at.';
  return `The invoice is ${money(Math.abs(v))} ${v > 0 ? 'more' : 'less'} than what the goods were received at.`;
}

// ---- Supplier payments -----------------------------------------------------------------------------------------------

export type PaymentMethod = 'cash' | 'mobile_money' | 'bank';

export const PAYMENT_METHODS: { value: PaymentMethod; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'mobile_money', label: 'Mobile money' },
  { value: 'bank', label: 'Bank' },
];

export const methodWords = (method: string | undefined): string => PAYMENT_METHODS.find((m) => m.value === method)?.label ?? (method ?? '');

export type Allocation = 'oldest_first' | 'manual';

export interface PaymentForm {
  supplierId: string;
  branchId: string;
  method: PaymentMethod;
  /** The amount as typed, in whole minor units. */
  amount: string;
  reference: string;
  note: string;
  allocation: Allocation;
  /** The amount typed against each open item for a manual allocation, by `itemKey`. */
  amounts: Record<string, string>;
}

export const blankPayment = (supplierId = '', branchId = ''): PaymentForm => ({ supplierId, branchId, method: 'cash', amount: '', reference: '', note: '', allocation: 'oldest_first', amounts: {} });

/** The key of an open item: an invoice and a credit restock may share an id space, so the kind is part of it. */
export const itemKey = (item: SupplierOpenItem): string => `${item.kind ?? 'bill'}:${item.id ?? ''}`;

/** The amount typed for a payment in minor units; null while it is not a positive whole amount. */
export function paymentAmount(form: PaymentForm): number | null {
  const v = parseMinor(form.amount, RETAIL_CURRENCY);
  return v !== null && v > 0 ? v : null;
}

/** The sum of the amounts typed against the open items; an amount that is not valid counts as none. */
export function allocatedMinor(amounts: Record<string, string>): number {
  return Object.values(amounts).reduce((sum, text) => sum + (parseMinor(text, RETAIL_CURRENCY) ?? 0), 0);
}

/** What is left of the payment to give to invoices; negative when too much was given. */
export function remainingMinor(form: PaymentForm): number {
  return (paymentAmount(form) ?? 0) - allocatedMinor(form.amounts);
}

/** The manual amount typed for an item is a valid whole amount that is not more than the item is owed. */
export function itemAmountProblem(item: SupplierOpenItem, text: string | undefined): string | null {
  if (text === undefined || text.trim() === '') return null;
  const v = parseMinor(text, RETAIL_CURRENCY);
  if (v === null) return 'Enter a whole amount.';
  if (v > (item.open_minor ?? 0)) return `At most ${money(item.open_minor ?? 0)} is owed on this one.`;
  return null;
}

/** Why the payment cannot be saved yet, in plain words; null when it can. `owed` is the supplier's total owed. */
export function paymentProblem(form: PaymentForm, items: SupplierOpenItem[] | undefined, owed: number | undefined): string | null {
  if (form.supplierId === '') return 'Choose the supplier.';
  if (form.branchId === '') return 'Choose the branch that pays.';
  const amount = paymentAmount(form);
  if (amount === null) return 'Enter the amount paid, in whole shillings.';
  if (form.allocation === 'oldest_first') {
    return owed !== undefined && amount > owed ? `This supplier is owed ${money(owed)}. Pay no more than that.` : null;
  }
  if (items === undefined) return 'Wait for the list of what is owed.';
  if (items.some((i) => itemAmountProblem(i, form.amounts[itemKey(i)]) !== null)) return 'Check the amounts marked in red.';
  const left = remainingMinor(form);
  if (left > 0) return `${money(left)} of the payment is not given to an invoice yet.`;
  if (left < 0) return `${money(-left)} more is given to invoices than the payment.`;
  return null;
}

/** The request for a payment. A manual allocation names the invoices (bill) and credit restocks (restock) and what each takes. */
export function buildPaymentRequest(form: PaymentForm, items: SupplierOpenItem[]): SupplierPaymentRequest {
  const allocations = items
    .map((i) => ({ item: i, minor: parseMinor(form.amounts[itemKey(i)] ?? '', RETAIL_CURRENCY) ?? 0 }))
    .filter((a) => a.minor > 0)
    .map((a) => ({ amount_minor: a.minor, ...(a.item.kind === 'restock' ? { purchase_id: a.item.id } : { bill_id: a.item.id }) }));
  return {
    supplier_id: form.supplierId,
    branch_id: form.branchId,
    method: form.method,
    amount_minor: paymentAmount(form) ?? 0,
    allocation: form.allocation,
    ...(form.reference.trim() ? { reference: form.reference.trim() } : {}),
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
    ...(form.allocation === 'manual' ? { allocations } : {}),
  };
}

/** How late an open item is, in words. Zero or less is not yet due. */
export function lateWords(days: number | undefined): string {
  const d = days ?? 0;
  if (d <= 0) return 'Not yet due';
  return d === 1 ? '1 day late' : `${d} days late`;
}

export const KIND_WORDS: Record<string, string> = { bill: 'Supplier invoice', restock: 'Stock bought on credit' };

// ---- The payables report ---------------------------------------------------------------------------------------------

/** The columns of the report in the order the page shows them: what is owed by how late it is. */
export const AGEING_COLUMNS = [
  { key: 'not_due_minor', label: 'Not due yet' },
  { key: 'days1_to30_minor', label: '1 to 30 days late' },
  { key: 'days31_to60_minor', label: '31 to 60 days late' },
  { key: 'days61_to90_minor', label: '61 to 90 days late' },
  { key: 'over90_minor', label: 'Over 90 days late' },
] as const;
