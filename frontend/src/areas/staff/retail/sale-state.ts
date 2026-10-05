import { RETAIL_CURRENCY, type Product, type SalePayment, type SaleRequest } from '../../../api/retail';
import { parseMinor } from '../../../components/money';
import { lineTotalMinor, milliOf, parseQty, qtyString, showQty } from './maths';

// The sale form's pure logic: the running total, the early hints and the request it submits. The
// hints (stock and price floor) only save a round trip: the server is the authority and refuses
// the same cases with insufficient_stock and price_below_cost (api/retail-errors.ts). The cost is
// known to the form only for a user holding retail.profit.read, so for anyone else only the
// server can refuse a price.

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
  if (qty !== undefined && f.qtyMilli > Math.max(milliOf(qty), 0)) {
    return `Only ${qty.startsWith('-') ? '0' : showQty(qty)} ${unit ?? 'items'} in stock at this branch.`;
  }
  if (cost !== undefined && f.priceMinor <= cost) return 'The price must be above what the item cost.';
  return null;
}

/** The running total of the valid lines, in integer minor units. */
export function draftTotal(lines: DraftLine[]): number {
  return lines.reduce((sum, l) => sum + (lineFigures(l)?.totalMinor ?? 0), 0);
}

/** What stops the sale being saved, in plain words, or null when it can be saved. */
export function draftProblem(draft: Draft): string | null {
  if (draft.lines.length === 0) return 'Add at least one item.';
  if (draft.lines.some((l) => lineFigures(l) === null)) return 'Check the quantity and price on each item.';
  if (draft.lines.some((l) => lineHint(l) !== null)) return 'Fix the items marked above.';
  if (draft.method === 'credit') {
    if (draft.customerId === '' && draft.newBuyerName.trim() === '') return 'Choose or add the buyer for a credit sale.';
    if (draft.dueDate === '') return 'Enter the date the buyer will pay.';
  }
  return null;
}

export function buildSaleRequest(draft: Draft): SaleRequest {
  const credit = draft.method === 'credit';
  return {
    branch_id: draft.branchId,
    payment_method: draft.method,
    ...(credit && draft.customerId ? { customer_id: draft.customerId } : {}),
    ...(credit && !draft.customerId ? { buyer_name: draft.newBuyerName.trim() } : {}),
    ...(credit && !draft.customerId && draft.newBuyerContact.trim() ? { buyer_contact: draft.newBuyerContact.trim() } : {}),
    ...(credit ? { due_date: draft.dueDate } : {}),
    lines: draft.lines.map((l) => {
      const f = lineFigures(l);
      if (!f) throw new Error('invalid line');
      return {
        product_id: l.product.id ?? '',
        qty: qtyString(f.qtyMilli),
        ...(f.priceMinor !== l.product.sell_minor ? { unit_price_minor: f.priceMinor } : {}),
      };
    }),
  };
}
