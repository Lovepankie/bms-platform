import { RetailError, type Product, type Purchase, type PurchasePayment } from '../../../api/retail';
import { showQty } from './maths';
import type { RestockDraft, RestockLine } from './restock';

// Correcting a mistaken restock (issue #351, ADR-044): void it, then enter it again with the right quantity. The rules are the
// server's; these helpers only prepare the form and put each refusal in plain words.

/** The shortest reason the server takes. */
export const MIN_VOID_REASON = 5;

/** The reason typed, trimmed, or null while it is too short. */
export function voidReasonOf(text: string): string | null {
  const reason = text.trim();
  return reason.length >= MIN_VOID_REASON ? reason : null;
}

/** "Entered in metres but this item is kept in rolls?" for a line entered in a unit that is not the item's usual one. */
export function unitHint(line: { unit?: string; base_unit?: string }): string | null {
  if (!line.unit || !line.base_unit || line.unit === line.base_unit) return null;
  return `Entered in ${line.unit} but this item is kept in ${line.base_unit}? If that is wrong, correct this purchase and enter it again in the right unit.`;
}

/** The same hint on the restock form, where the unit is the one chosen for the line. */
export function chosenUnitHint(enteredUnit: string | undefined, usualUnit: string | undefined): string | null {
  return unitHint({ unit: enteredUnit, base_unit: usualUnit });
}

/**
 * The restock form's draft for entering a voided restock again: its supplier, date, payment and lines, with the quantities as
 * they were and the date set to today, to be corrected before saving. A line whose item can no longer be found is left out.
 */
export function redraftOf(purchase: Purchase, products: Product[], today: string): RestockDraft {
  const byId = new Map(products.map((p) => [p.id, p]));
  const lines: RestockLine[] = [];
  for (const l of purchase.lines ?? []) {
    const product = byId.get(l.product_id);
    if (!product) continue;
    const inPack = l.unit_id !== undefined && l.unit_id !== null;
    lines.push({
      product,
      cost: String(inPack ? (l.unit_cost_minor ?? l.cost_minor ?? 0) : (l.cost_minor ?? 0)),
      sell: String(l.sell_minor ?? product.sell_minor ?? 0),
      qtyByBranch: Object.fromEntries((l.qty_by_branch ?? []).map((b) => [b.branch_id ?? '', showQty((inPack ? b.unit_qty : undefined) ?? b.qty ?? '0')])),
      ...(inPack ? { unitId: l.unit_id } : {}),
    });
  }
  return {
    supplierId: purchase.supplier_id ?? '',
    newSupplier: '',
    // Today, not the old date: the old day may be banked in the cash book already (ADR-044).
    purchasedOn: today,
    method: (purchase.payment_method ?? 'cash') as PurchasePayment,
    lines,
  };
}

/** A void that went through, found already voided: the same end state, so not an error. */
export function alreadyVoided(error: unknown): boolean {
  return error instanceof RetailError && error.code === 'purchase_voided';
}

/** A refusal after which the list is out of date (someone else voided it, paid it, or the stock moved). */
export function listIsStale(error: unknown): boolean {
  return error instanceof RetailError && ['purchase_voided', 'purchase_paid', 'purchase_stock_moved'].includes(error.code ?? '');
}

/** What a restock row says about its state, in words. */
export function stateOf(purchase: Purchase): string {
  return purchase.status === 'voided' ? 'Voided' : 'Recorded';
}

/** The names a restock row shows its quantities in: "10 roll" per line, one branch after another. */
export function quantityWords(line: NonNullable<Purchase['lines']>[number]): string {
  const inPack = line.unit_id !== undefined && line.unit_id !== null;
  const qty = showQty(inPack ? (line.unit_qty_total ?? line.qty_total ?? '0') : (line.qty_total ?? '0'));
  return `${qty} ${inPack ? (line.unit ?? '') : (line.base_unit ?? '')}`.trim();
}
