import { api } from './client';
import { unwrap } from './retail';
import type { components } from './schema';

// "Warn me when stock is below" (issue #412, ADR-057): an item's low-stock level for every shop and store, and a branch's own level
// where it should differ. Read with the stock; the level for every branch is the catalogue manager's, a branch's own level also that
// of whoever counts the stock there. Quantities travel as strings in the item's unit, three places.

export type StockMinimum = components['schemas']['RetailStockMinimum'];
export type StockMinimumBranch = components['schemas']['RetailStockMinimumBranch'];

export const stockMinimumApi = {
  async get(productId: string): Promise<StockMinimum> {
    return unwrap(await api.GET('/api/v1/retail/products/{product_id}/stock-minimum', { params: { path: { product_id: productId } } }));
  },

  /** null removes the level. */
  async setEveryBranch(productId: string, minQty: string | null): Promise<StockMinimum> {
    return unwrap(
      await api.PUT('/api/v1/retail/products/{product_id}/stock-minimum', {
        params: { path: { product_id: productId } },
        body: { min_qty: minQty } as never,
      }),
    );
  },

  /** null removes the branch's own level, so the level for every branch applies there again. */
  async setBranch(productId: string, branchId: string, minQty: string | null): Promise<StockMinimum> {
    return unwrap(
      await api.PUT('/api/v1/retail/products/{product_id}/stock-minimum/branches/{branch_id}', {
        params: { path: { product_id: productId, branch_id: branchId } },
        body: { min_qty: minQty } as never,
      }),
    );
  },
};

/** A quantity typed by a person: above zero, at most three places. Returns the wire form or null when it is not one. */
export function parseLevel(text: string): string | null {
  const t = text.trim().replace(',', '.');
  if (!/^\d{1,11}(\.\d{1,3})?$/.test(t)) return null;
  return Number(t) > 0 ? t : null;
}

/** "10.000" as "10", "2.500" as "2.5". */
export function showLevel(qty: string | null | undefined): string {
  if (!qty) return '';
  return qty.includes('.') ? qty.replace(/\.?0+$/, '') : qty;
}
