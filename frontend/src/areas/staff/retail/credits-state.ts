import { RETAIL_CURRENCY, type SupplierBill, type SupplierCredit, type SupplierCreditRequest } from '../../../api/retail';
import { formatMinor, parseMinor } from '../../../components/money';
import { lineTotalMinor, milliOf, parseQty, qtyString, showQty } from './maths';
import { MIN_REASON } from './account-state';

// Supplier credit notes (issue #199): goods sent back to a supplier, or a price adjustment, against a supplier invoice that stands.
// The pure rules of the form. They mirror what the server checks so a person sees the limit before sending; the server decides.
// Money is integer minor units, quantities are integer thousandths.

const money = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY);

export type CreditKind = 'goods' | 'price';

export const CREDIT_KINDS: { value: CreditKind; label: string; sentence: string }[] = [
  { value: 'goods', label: 'Goods sent back', sentence: 'You send goods back to the supplier. They come out of stock and the invoice leaves less to pay.' },
  { value: 'price', label: 'A price adjustment', sentence: 'The supplier takes money off the invoice, for example for a wrong price. No stock moves.' },
];

export const creditKindWords = (kind: string | undefined): string => CREDIT_KINDS.find((k) => k.value === kind)?.label ?? (kind ?? '');

export const CREDIT_STATUS: Record<string, string> = { posted: 'Standing', voided: 'Voided' };

/** One invoice line offered for a credit, with what the invoice billed and what earlier credit notes have already taken. */
export interface CreditLineForm {
  billLineId: string;
  receiptNo: string;
  code: string;
  description: string;
  unit: string;
  /** Billed, in the unit the line was received in. */
  billedQty: string;
  /** Already sent back by credit notes that stand, in thousandths of that unit. */
  creditedMilli: number;
  /** The price the invoice charged for one unit as entered. */
  unitPriceMinor: number;
  /** What the invoice charged for the line. */
  lineTotalMinor: number;
  /** Already taken off the line by credit notes that stand, goods or price. */
  creditedMinor: number;
  /** Goods: the quantity sent back, as typed. */
  qty: string;
  /** Goods: the credit for one, as typed, in whole minor units; starts at the billed price. */
  unitCredit: string;
  /** Price: the amount taken off the line, as typed, in whole minor units. */
  amount: string;
}

export interface CreditForm {
  billId: string;
  kind: CreditKind;
  /** The supplier's own credit note number. */
  supplierRef: string;
  creditDate: string;
  reason: string;
  /** The credit total as printed, in whole minor units. */
  total: string;
  note: string;
  lines: CreditLineForm[];
}

export const blankCredit = (today: string, billId = ''): CreditForm => ({ billId, kind: 'goods', supplierRef: '', creditDate: today, reason: '', total: '', note: '', lines: [] });

/** An invoice that can take a goods credit note: a goods invoice that stands and still has something to pay. */
export const canCredit = (bill: SupplierBill): boolean => bill.kind !== 'landed_cost' && bill.status === 'posted' && (bill.open_minor ?? 0) > 0;

/** What earlier credit notes that stand have taken from each invoice line: goods sent back and money. */
export function creditedByLine(credits: SupplierCredit[]): Record<string, { qtyMilli: number; minor: number }> {
  const out: Record<string, { qtyMilli: number; minor: number }> = {};
  for (const c of credits) {
    if (c.status !== 'posted') continue;
    for (const l of c.lines ?? []) {
      const key = l.bill_line_id ?? '';
      const held = out[key] ?? { qtyMilli: 0, minor: 0 };
      out[key] = { qtyMilli: held.qtyMilli + (c.kind === 'goods' ? milliOf(l.unit_qty ?? l.qty ?? '0') : 0), minor: held.minor + (l.line_total_minor ?? 0) };
    }
  }
  return out;
}

/** The lines of an invoice offered for a credit, none used yet; the credit for one starts at the billed price. */
export function creditLinesFrom(bill: SupplierBill, credits: SupplierCredit[]): CreditLineForm[] {
  const taken = creditedByLine(credits);
  return (bill.lines ?? []).map((l) => ({
    billLineId: l.id ?? '',
    receiptNo: l.receipt_no ?? '',
    code: l.code ?? '',
    description: l.description ?? '',
    unit: l.unit ?? '',
    billedQty: l.unit_qty ?? l.qty ?? '0.000',
    creditedMilli: taken[l.id ?? '']?.qtyMilli ?? 0,
    unitPriceMinor: l.unit_price_minor ?? 0,
    lineTotalMinor: l.line_total_minor ?? 0,
    creditedMinor: taken[l.id ?? '']?.minor ?? 0,
    qty: '',
    unitCredit: String(l.unit_price_minor ?? 0),
    amount: '',
  }));
}

/** How many of a line can still be sent back, in thousandths. */
export const leftQtyMilli = (l: CreditLineForm): number => Math.max(0, milliOf(l.billedQty) - l.creditedMilli);

/** How much can still be taken off a line, in minor units. */
export const leftMinor = (l: CreditLineForm): number => Math.max(0, l.lineTotalMinor - l.creditedMinor);

/** The line has something typed for this kind of credit. */
export const lineUsed = (l: CreditLineForm, kind: CreditKind): boolean => (kind === 'goods' ? l.qty.trim() !== '' : l.amount.trim() !== '');

export const usedLines = (form: CreditForm): CreditLineForm[] => form.lines.filter((l) => lineUsed(l, form.kind));

/** The credit for a line in minor units: quantity times the credit for one, rounded half up once; 0 while not valid. */
export function lineCredit(l: CreditLineForm, kind: CreditKind): number {
  if (kind === 'price') return parseMinor(l.amount, RETAIL_CURRENCY) ?? 0;
  const qty = parseQty(l.qty);
  const each = parseMinor(l.unitCredit, RETAIL_CURRENCY);
  return qty === null || each === null ? 0 : lineTotalMinor(each, qty);
}

/** The credit of the lines used, added up. */
export const creditTotal = (form: CreditForm): number => usedLines(form).reduce((sum, l) => sum + lineCredit(l, form.kind), 0);

/** Why one used line cannot be credited; null when it can. */
export function creditLineProblem(l: CreditLineForm, kind: CreditKind): string | null {
  if (kind === 'price') {
    const amount = parseMinor(l.amount, RETAIL_CURRENCY);
    if (amount === null || amount === 0) return 'Enter the amount to take off, in whole shillings, above zero.';
    if (amount > leftMinor(l)) return `At most ${money(leftMinor(l))} can be taken off this line.`;
    return null;
  }
  const qty = parseQty(l.qty);
  if (qty === null) return 'Enter how many are sent back, as a number above zero.';
  if (qty > leftQtyMilli(l)) return `At most ${showQty(qtyString(leftQtyMilli(l)))} ${l.unit} can be sent back: the invoice billed ${showQty(l.billedQty)} and earlier credit notes took ${showQty(qtyString(l.creditedMilli))}.`;
  const each = parseMinor(l.unitCredit, RETAIL_CURRENCY);
  if (each === null) return 'Enter the credit for one, in whole shillings.';
  if (each > l.unitPriceMinor) return `The credit for one cannot be more than the invoice charged, ${money(l.unitPriceMinor)}.`;
  return null;
}

/**
 * Why the credit note cannot be saved yet, in plain words; null when it can. `openMinor` is what the invoice still has to pay, and
 * a credit can never be more than that.
 */
export function creditProblem(form: CreditForm, openMinor: number | undefined): string | null {
  if (form.billId === '') return 'Choose the supplier invoice this credit note is against.';
  if (form.supplierRef.trim() === '') return 'Enter the number on the supplier\'s credit note.';
  if (form.creditDate === '') return 'Enter the date of the credit note.';
  if (form.reason.trim().length < MIN_REASON) return `Say why, in at least ${MIN_REASON} characters.`;
  const lines = usedLines(form);
  if (lines.length === 0) return form.kind === 'goods' ? 'Enter how many of at least one item are sent back.' : 'Enter the amount to take off at least one line.';
  if (lines.some((l) => creditLineProblem(l, form.kind) !== null)) return 'Check the lines marked in red.';
  const typed = parseMinor(form.total, RETAIL_CURRENCY);
  if (typed === null || typed === 0) return 'Enter the credit total as printed on the credit note.';
  if (typed !== creditTotal(form)) return `The total you typed is ${money(typed)} but the lines come to ${money(creditTotal(form))}. Check both against the credit note.`;
  if (openMinor !== undefined && typed > openMinor) return `The invoice has only ${money(openMinor)} left to pay, and a credit cannot be more than that.`;
  return null;
}

/** The request for the credit note. A goods line names the credit for one only when it differs from the billed price. */
export function buildCreditRequest(form: CreditForm): SupplierCreditRequest {
  return {
    bill_id: form.billId,
    kind: form.kind,
    supplier_ref: form.supplierRef.trim(),
    credit_date: form.creditDate || undefined,
    total_minor: parseMinor(form.total, RETAIL_CURRENCY) ?? 0,
    reason: form.reason.trim(),
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
    lines: usedLines(form).map((l) =>
      form.kind === 'price'
        ? { bill_line_id: l.billLineId, amount_minor: parseMinor(l.amount, RETAIL_CURRENCY) ?? 0 }
        : {
            bill_line_id: l.billLineId,
            qty: qtyString(parseQty(l.qty) ?? 0),
            ...(parseMinor(l.unitCredit, RETAIL_CURRENCY) !== l.unitPriceMinor ? { unit_credit_minor: parseMinor(l.unitCredit, RETAIL_CURRENCY) ?? 0 } : {}),
          },
    ),
  };
}
