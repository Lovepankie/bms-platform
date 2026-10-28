import type { Product, TransferRequest } from '../../../api/retail';
import { milliOf, parseQty, qtyString, showQty } from './maths';
import { packsAndPieces, packsAndPiecesMilli, packsAndPiecesProblem, standardPack, type PackSize } from './units-state';

// The stock move form's pure logic (FR-RET-16): the early stock hint and the request it submits.
// The hint only saves a round trip: the server refuses a quantity above the source branch's
// balance with insufficient_stock, always (ADR-020 decision 4).

export interface TransferLineDraft {
  product: Product;
  /** The pieces to move, or all of it in pieces. */
  qty: string;
  /** Issue #650: the whole cartons to move, for an item with a standard pack; the pieces are added to them. */
  packs?: string;
  /** The pack the cartons were typed in, kept from the first carton typed so a fresher item never reads them in another size. */
  pack?: PackSize;
}

/**
 * Issue #650: the pack a line is moved in: the one its cartons were typed in while any are typed, else the item's standard
 * pack now. Undefined for an item with neither.
 */
export function linePack(line: TransferLineDraft): PackSize | undefined {
  if ((line.packs ?? '').trim() !== '' && line.pack) return line.pack;
  const pack = standardPack(line.product);
  return pack ? { unit: pack.unit, factor: pack.factor } : undefined;
}

/** Issue #650: a line with its cartons typed as `packs`, keeping the pack of the first carton typed (review of #654). */
export function withPacks(line: TransferLineDraft, packs: string): TransferLineDraft {
  const kept = (line.packs ?? '').trim() !== '' && line.pack ? line.pack : linePack(line);
  return { ...line, packs, pack: packs.trim() === '' ? undefined : kept };
}

/** Issue #650: the quantity of a line in base unit thousandths, its cartons and pieces added up; null when it cannot be read. */
export function lineMilli(line: TransferLineDraft): number | null {
  const pack = linePack(line);
  if (!pack || (line.packs ?? '').trim() === '') return parseQty(line.qty);
  const milli = packsAndPiecesMilli(line.packs ?? '', line.qty, pack);
  return milli !== null && milli > 0 ? milli : null;
}

export interface TransferDraft {
  toBranchId: string;
  transferDate: string;
  note: string;
  lines: TransferLineDraft[];
}

/** More than the source branch holds, in plain words, or null. */
export function transferLineHint(line: TransferLineDraft): string | null {
  const pack = linePack(line);
  const { qty, unit } = line.product;
  const milli = lineMilli(line);
  // Numbers that read but add up to more than a quantity can hold are too many, not too few or unreadable (reviews of #654 and
  // #659). Only a pack with a size and a unit reads cartons; with none, or no cartons typed, the pieces alone are judged, and
  // unreadable cartons are said to be unreadable before any count of pieces is judged.
  const packs = (line.packs ?? '').trim();
  const number = (text: string) => /^\d+(\.\d{1,3})?$/.test(text.trim());
  const tooManyPieces = milli === null && (packs === '' || /^\d+$/.test(packs)) && number(line.qty) && /[1-9]/.test(line.qty)
    && parseQty(line.qty) === null;
  if (tooManyPieces) return 'That is more pieces than BMS can move at once. Check the number.';
  const tooMany = milli === null && !!pack && !!unit && !!pack.unit && packs !== '' && packsAndPiecesMilli('1', '', pack) !== null && /^\d+$/.test(packs)
    && (line.qty.trim() === '' || number(line.qty)) && packsAndPiecesMilli(packs, line.qty, pack) === null;
  if (tooMany) return 'That is more than BMS can move at once. Check the number.';
  const unreadable = pack?.unit && unit ? packsAndPiecesProblem(line.packs ?? '', line.qty, unit, pack.unit) : null;
  if (unreadable) return unreadable;
  if (milli === null) return 'Enter a quantity above zero.';
  if (qty !== undefined && milli > Math.max(milliOf(qty), 0)) {
    if (pack && !qty.startsWith('-')) return `Only ${packsAndPieces(qty, unit, pack)} in stock at this branch.`;
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
    lines: draft.lines.map((l) => ({ product_id: l.product.id ?? '', qty: qtyString(lineMilli(l) ?? 0) })),
  };
}
