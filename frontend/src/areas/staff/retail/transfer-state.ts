import type { Product, TransferRequest } from '../../../api/retail';
import { milliOf, parseQty, qtyString, showQty } from './maths';

// The stock move form's pure logic (FR-RET-16): the early stock hint and the request it submits.
// The hint only saves a round trip: the server refuses a quantity above the source branch's
// balance with insufficient_stock, always (ADR-020 decision 4).

export interface TransferLineDraft {
  product: Product;
  qty: string;
}

export interface TransferDraft {
  toBranchId: string;
  transferDate: string;
  note: string;
  lines: TransferLineDraft[];
}

/** More than the source branch holds, in plain words, or null. */
export function transferLineHint(line: TransferLineDraft): string | null {
  const milli = parseQty(line.qty);
  if (milli === null) return 'Enter a quantity above zero.';
  const { qty, unit } = line.product;
  if (qty !== undefined && milli > Math.max(milliOf(qty), 0)) {
    return `Only ${qty.startsWith('-') ? '0' : showQty(qty)} ${unit ?? 'items'} in stock at this branch.`;
  }
  return null;
}

/** What stops the move being saved, in plain words, or null when it can be saved. */
export function transferProblem(fromBranchId: string, draft: TransferDraft): string | null {
  if (draft.toBranchId === '') return 'Choose the branch the stock goes to.';
  if (draft.toBranchId === fromBranchId) return 'Choose a different branch to move the stock to.';
  if (draft.lines.length === 0) return 'Add at least one item.';
  if (draft.lines.some((l) => transferLineHint(l) !== null)) return 'Fix the items marked above.';
  return null;
}

export function buildTransfer(fromBranchId: string, draft: TransferDraft): TransferRequest {
  const note = draft.note.trim();
  return {
    from_branch_id: fromBranchId,
    to_branch_id: draft.toBranchId,
    ...(draft.transferDate ? { transfer_date: draft.transferDate } : {}),
    ...(note ? { note } : {}),
    lines: draft.lines.map((l) => ({ product_id: l.product.id ?? '', qty: qtyString(parseQty(l.qty) ?? 0) })),
  };
}
