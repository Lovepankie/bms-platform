import { RETAIL_CURRENCY, type Product, type SalePayment, type SaleRequest } from '../../../api/retail';
import { parseMinor } from '../../../components/money';
import { lineTotalMinor, parseQty, qtyString } from './maths';

// The sale form's pure logic: the running total and the request it submits.

export interface DraftLine {
  product: Product;
  qty: string;
  price: string;
}

export interface Draft {
  branchId: string;
  method: SalePayment;
  customerId: string;
  newBuyerName: string;
  newBuyerContact: string;
  dueDate: string;
  lines: DraftLine[];
}

export function newLine(product: Product): DraftLine {
  return { product, qty: '1', price: String(product.sellMinor) };
}

/** Quantity and unit price as numbers when both are valid, and the line total they give. */
export function lineFigures(line: DraftLine): { qtyMilli: number; priceMinor: number; totalMinor: number } | null {
  const qtyMilli = parseQty(line.qty);
  const priceMinor = parseMinor(line.price, RETAIL_CURRENCY);
  if (qtyMilli === null || priceMinor === null) return null;
  return { qtyMilli, priceMinor, totalMinor: lineTotalMinor(priceMinor, qtyMilli) };
}

/** The running total of the valid lines, in integer minor units. */
export function draftTotal(lines: DraftLine[]): number {
  return lines.reduce((sum, l) => sum + (lineFigures(l)?.totalMinor ?? 0), 0);
}

/** What stops the sale being saved, in plain words, or null when it can be saved. */
export function draftProblem(draft: Draft): string | null {
  if (draft.lines.length === 0) return 'Add at least one item.';
  if (draft.lines.some((l) => lineFigures(l) === null)) return 'Check the quantity and price on each item.';
  if (draft.method === 'credit') {
    if (draft.customerId === '' && draft.newBuyerName.trim() === '') return 'Choose or add the buyer for a credit sale.';
    if (draft.dueDate === '') return 'Enter the date the buyer will pay.';
  }
  return null;
}

export function buildSaleRequest(draft: Draft): SaleRequest {
  const credit = draft.method === 'credit';
  return {
    branchId: draft.branchId,
    paymentMethod: draft.method,
    ...(credit && draft.customerId ? { customerId: draft.customerId } : {}),
    ...(credit && !draft.customerId ? { buyerName: draft.newBuyerName.trim() } : {}),
    ...(credit && !draft.customerId && draft.newBuyerContact.trim() ? { buyerContact: draft.newBuyerContact.trim() } : {}),
    ...(credit ? { dueDate: draft.dueDate } : {}),
    lines: draft.lines.map((l) => {
      const f = lineFigures(l);
      if (!f) throw new Error('invalid line');
      return {
        productId: l.product.id,
        qty: qtyString(f.qtyMilli),
        ...(f.priceMinor !== l.product.sellMinor ? { unitPriceMinor: f.priceMinor } : {}),
      };
    }),
  };
}
