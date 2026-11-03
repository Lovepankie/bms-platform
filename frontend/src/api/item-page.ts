import { api } from './client';
import { type StockMovement, unwrap } from './retail';
import type { components } from './schema';

// The item page (issue #813, ADR-121): one item's stock in each shop the person may read, then every movement of it newest first
// with the shop's balance after each. Buying figures and profit come only to a holder of retail.profit.read; the server leaves
// them out for everyone else, so the screen never hides them itself.

export type ItemStock = components['schemas']['RetailItemStock'];
export type ItemShop = components['schemas']['RetailItemShop'];

export interface ItemMovementsQuery {
  productId: string;
  branchId?: string;
  from?: string;
  to?: string;
  cursor?: string;
  signal?: AbortSignal;
}

export const itemPageApi = {
  async item(productId: string, signal?: AbortSignal): Promise<ItemStock> {
    return unwrap(await api.GET('/api/v1/retail/stock/items/{product_id}', { params: { path: { product_id: productId } }, signal }));
  },

  async movements({ productId, branchId, from, to, cursor, signal }: ItemMovementsQuery): Promise<{ items: StockMovement[]; next_cursor?: string }> {
    const page = unwrap(
      await api.GET('/api/v1/retail/stock/items/{product_id}/movements', {
        params: {
          path: { product_id: productId },
          query: { ...(branchId ? { branch_id: [branchId] } : {}), ...(from ? { from } : {}), ...(to ? { to } : {}), ...(cursor ? { cursor } : {}) },
        },
        signal,
      }),
    );
    return { items: page.items ?? [], ...(page.next_cursor ? { next_cursor: page.next_cursor } : {}) };
  },
};
