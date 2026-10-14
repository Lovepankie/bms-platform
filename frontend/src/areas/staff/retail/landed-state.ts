import { RETAIL_CURRENCY, type GoodsReceipt, type LandedCostLine, type LandedCostRequest } from '../../../api/retail';
import { formatMinor, parseMinor } from '../../../components/money';
import { milliOf, parseQty, qtyString, showQty } from './maths';

// Landed cost (issue #199): freight, duty, clearing, insurance and other charges of one payee, spread over received lines. These are
// the pure rules of the form: the running figures a person sees before the server's own preview, which decides. Money is integer
// minor units, quantities and weights are integer thousandths. The spread here is the same exact-integer spread the server uses
// (largest remainder, ties to the earlier line), kept so the fabricated API and the tests share one definition.

const money = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY);

export type SpreadMethod = 'value' | 'quantity' | 'weight' | 'manual';

/** The four ways to spread the charges, each in one plain sentence. */
export const SPREAD_METHODS: { value: SpreadMethod; label: string; sentence: string }[] = [
  { value: 'value', label: 'By value', sentence: 'Each line takes a share in proportion to what the goods cost. Dearer goods carry more.' },
  { value: 'quantity', label: 'By quantity', sentence: 'Each line takes a share in proportion to how many were received. Every piece carries the same.' },
  { value: 'weight', label: 'By weight', sentence: 'Each line takes a share in proportion to its weight, which you type for each line. Use it for freight.' },
  { value: 'manual', label: 'Amounts I type', sentence: 'You type the share of each line yourself, and the shares must add up to the invoice total.' },
];

export const methodWords = (method: string | undefined): string => SPREAD_METHODS.find((m) => m.value === method)?.label ?? (method ?? '');

export type ChargeKind = 'freight' | 'duty' | 'clearing' | 'insurance' | 'other';

export const CHARGE_KINDS: { value: ChargeKind; label: string }[] = [
  { value: 'freight', label: 'Freight' },
  { value: 'duty', label: 'Import duty' },
  { value: 'clearing', label: 'Clearing' },
  { value: 'insurance', label: 'Insurance' },
  { value: 'other', label: 'Other' },
];

export const kindWords = (kind: string | undefined): string => CHARGE_KINDS.find((k) => k.value === kind)?.label ?? (kind ?? '');

export const LANDED_STATUS: Record<string, string> = { posted: 'Standing', voided: 'Voided' };

export interface ChargeForm {
  /** Only to key the row in the list. */
  key: string;
  kind: ChargeKind;
  description: string;
  /** The amount as typed, in whole minor units. */
  amount: string;
}

/** A delivery the charges are spread over, as chosen on the form. */
export interface ReceiptRef {
  id: string;
  receiptNo: string;
  supplierName: string;
  receivedOn: string;
}

/** One received line offered for the charges. */
export interface GoodsLineForm {
  receiptLineId: string;
  receiptId: string;
  receiptNo: string;
  code: string;
  description: string;
  unit: string;
  /** Received, in base units, as the server wrote it. */
  qty: string;
  /** What the line was received at, in minor units: the basis of a spread by value. */
  valueMinor: number;
  ticked: boolean;
  /** The line's total weight as typed, in any one unit used on every line (a spread by weight). */
  weight: string;
  /** The line's share as typed, in whole minor units (amounts I type). */
  amount: string;
}

export interface LandedForm {
  supplierId: string;
  supplierRef: string;
  costDate: string;
  dueDate: string;
  method: SpreadMethod;
  /** The invoice total as printed, in whole minor units. */
  total: string;
  note: string;
  charges: ChargeForm[];
  receipts: ReceiptRef[];
  lines: GoodsLineForm[];
}

export const blankCharge = (key: string): ChargeForm => ({ key, kind: 'freight', description: '', amount: '' });

export const blankLanded = (today: string, chargeKey = 'c1'): LandedForm => ({
  supplierId: '', supplierRef: '', costDate: today, dueDate: '', method: 'value', total: '', note: '', charges: [blankCharge(chargeKey)], receipts: [], lines: [],
});

// ---- The goods ----------------------------------------------------------------------------------------------------------

/** The form with the lines of a delivery added, all ticked; a delivery already on the form is left as it is. */
export function addReceipt(form: LandedForm, receipt: GoodsReceipt): LandedForm {
  const id = receipt.id ?? '';
  if (id === '' || form.receipts.some((r) => r.id === id)) return form;
  const lines: GoodsLineForm[] = (receipt.lines ?? []).map((l) => ({
    receiptLineId: l.id ?? '',
    receiptId: id,
    receiptNo: receipt.receipt_no ?? '',
    code: l.code ?? '',
    description: l.description ?? '',
    unit: l.unit ?? '',
    qty: l.qty ?? '0.000',
    valueMinor: l.line_total_minor ?? 0,
    ticked: true,
    weight: '',
    amount: '',
  }));
  const ref: ReceiptRef = { id, receiptNo: receipt.receipt_no ?? '', supplierName: receipt.supplier_name ?? '', receivedOn: receipt.received_on ?? '' };
  return { ...form, receipts: [...form.receipts, ref], lines: [...form.lines, ...lines] };
}

export function removeReceipt(form: LandedForm, receiptId: string): LandedForm {
  return { ...form, receipts: form.receipts.filter((r) => r.id !== receiptId), lines: form.lines.filter((l) => l.receiptId !== receiptId) };
}

export const tickedLines = (form: LandedForm): GoodsLineForm[] => form.lines.filter((l) => l.ticked);

// ---- The charges --------------------------------------------------------------------------------------------------------

/** The amount typed on a charge in minor units; null while it is not a whole amount above zero. */
export function chargeAmount(c: ChargeForm): number | null {
  const v = parseMinor(c.amount, RETAIL_CURRENCY);
  return v !== null && v > 0 ? v : null;
}

/** The charges added up; an amount that is not valid counts as none. */
export const chargesTotal = (form: LandedForm): number => form.charges.reduce((sum, c) => sum + (chargeAmount(c) ?? 0), 0);

/** The invoice total as typed, or null while it is not a whole amount above zero. */
export function typedTotal(form: LandedForm): number | null {
  const v = parseMinor(form.total, RETAIL_CURRENCY);
  return v !== null && v > 0 ? v : null;
}

export function chargeProblem(c: ChargeForm): string | null {
  return chargeAmount(c) === null ? 'Enter the amount of this charge, in whole shillings, above zero.' : null;
}

// ---- The spread ---------------------------------------------------------------------------------------------------------

/**
 * Spreads `total` over `bases` in exact integers: each line takes the whole part of its proportion and the units left over go, one
 * each, to the lines with the biggest remainders (a tie goes to the earlier line). The shares always add up to `total`. Null when
 * the bases add up to nothing, since nothing can then be shared.
 */
export function spreadMinor(total: number, bases: number[]): number[] | null {
  const sum = bases.reduce((a, b) => a + BigInt(b), 0n);
  if (sum <= 0n || bases.some((b) => b < 0)) return null;
  const parts = bases.map((b) => {
    const scaled = BigInt(total) * BigInt(b);
    return { share: scaled / sum, rest: scaled % sum };
  });
  let left = total - parts.reduce((a, p) => a + Number(p.share), 0);
  const order = parts.map((_, i) => i).sort((a, b) => (parts[a]!.rest === parts[b]!.rest ? a - b : parts[a]!.rest > parts[b]!.rest ? -1 : 1));
  const shares = parts.map((p) => Number(p.share));
  for (const i of order) {
    if (left <= 0) break;
    shares[i] = (shares[i] ?? 0) + 1;
    left -= 1;
  }
  return shares;
}

/** The weight typed on a line in thousandths; null while it is not a number above zero. */
export const weightOf = (l: GoodsLineForm): number | null => parseQty(l.weight);

/** The share typed on a line in minor units; null while it is not a whole amount (zero is allowed). */
export const typedShare = (l: GoodsLineForm): number | null => parseMinor(l.amount, RETAIL_CURRENCY);

/** What a spread by `method` uses on a line, in minor units, thousandths or thousandths of the weight unit. */
export function basisOf(l: GoodsLineForm, method: SpreadMethod): number {
  if (method === 'value') return l.valueMinor;
  if (method === 'quantity') return milliOf(l.qty);
  if (method === 'weight') return weightOf(l) ?? 0;
  return typedShare(l) ?? 0;
}

/** The typed shares of the ticked lines added up. */
export const typedSharesTotal = (form: LandedForm): number => tickedLines(form).reduce((sum, l) => sum + (typedShare(l) ?? 0), 0);

/** How much of the total the typed shares have not yet given to a line; negative when too much was given. */
export function sharesLeft(form: LandedForm): number {
  return (typedTotal(form) ?? 0) - typedSharesTotal(form);
}

/** Why one ticked line cannot be used with the chosen method; null when it can. */
export function lineProblem(l: GoodsLineForm, method: SpreadMethod): string | null {
  if (method === 'weight' && weightOf(l) === null) return 'Enter the weight of this line, as a number above zero.';
  if (method === 'manual' && typedShare(l) === null) return 'Enter this line\'s share, in whole shillings.';
  return null;
}

// ---- The form -----------------------------------------------------------------------------------------------------------

/** Why the form cannot be reviewed yet, in plain words; null when it can. The server decides every rule again. */
export function landedProblem(form: LandedForm): string | null {
  if (form.supplierId === '') return 'Choose who the charges are owed to.';
  if (form.supplierRef.trim() === '') return 'Enter the invoice number of the person you owe.';
  if (form.dueDate === '') return 'Enter the date the invoice is due.';
  if (form.charges.length === 0) return 'Add at least one charge.';
  if (form.charges.some((c) => chargeProblem(c) !== null)) return 'Check the charges marked in red.';
  const typed = typedTotal(form);
  if (typed === null) return 'Enter the invoice total as printed on the invoice.';
  if (typed !== chargesTotal(form)) return `The invoice total is ${money(typed)} but the charges come to ${money(chargesTotal(form))}. Check both against the invoice.`;
  const lines = tickedLines(form);
  if (lines.length === 0) return 'Tick the goods the charges are for.';
  if (lines.some((l) => lineProblem(l, form.method) !== null)) return 'Check the lines marked in red.';
  if (form.method === 'manual' && sharesLeft(form) !== 0) {
    const left = sharesLeft(form);
    return left > 0 ? `${money(left)} of the total is not given to a line yet.` : `${money(-left)} more is given to lines than the total.`;
  }
  if (form.method !== 'manual' && lines.reduce((sum, l) => sum + basisOf(l, form.method), 0) <= 0) return 'The goods you ticked add up to nothing by this method. Choose another way to spread.';
  return null;
}

/** The request for the landed cost; the same body is sent to the preview and to save. */
export function buildLandedRequest(form: LandedForm): LandedCostRequest {
  return {
    supplier_id: form.supplierId,
    supplier_ref: form.supplierRef.trim(),
    cost_date: form.costDate || undefined,
    due_date: form.dueDate,
    method: form.method,
    total_minor: typedTotal(form) ?? 0,
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
    charges: form.charges.map((c) => ({ kind: c.kind, ...(c.description.trim() ? { description: c.description.trim() } : {}), amount_minor: chargeAmount(c) ?? 0 })),
    lines: tickedLines(form).map((l) => ({
      receipt_line_id: l.receiptLineId,
      ...(form.method === 'weight' ? { weight: qtyString(weightOf(l) ?? 0) } : {}),
      ...(form.method === 'manual' ? { amount_minor: typedShare(l) ?? 0 } : {}),
    })),
  };
}

/** A key that changes when anything the preview depends on changes. */
export const previewKey = (form: LandedForm): string => JSON.stringify(buildLandedRequest({ ...form, note: '' }));

// ---- Words for the result ----------------------------------------------------------------------------------------------

/** What a landed cost did to the cost of a line, in words; empty when the server sent no cost figures (no profit access). */
export function costEffectWords(l: LandedCostLine): string {
  if (l.cost_before_minor === undefined || l.cost_after_minor === undefined) return '';
  if (l.cost_applied) return `Cost of one goes from ${money(l.cost_before_minor)} to ${money(l.cost_after_minor)}.`;
  return l.cost_note ? `Cost not changed. ${l.cost_note}` : 'Cost not changed.';
}

/** The quantity of a line for people, with its unit. */
export const lineQtyWords = (qty: string | undefined, unit: string | undefined): string => `${showQty(qty ?? '0')} ${unit ?? ''}`.trim();

/** True when any line carries the figures only retail.profit.read sees. */
export const hasProfitFigures = (lines: LandedCostLine[] | undefined): boolean => (lines ?? []).some((l) => l.to_inventory_minor !== undefined);
