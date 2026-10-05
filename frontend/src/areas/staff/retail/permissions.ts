import type { Me } from '../../../api/client';

// Which retail screens a session may use. The API is the authority; this only decides what the
// menu and the routes offer, from the same /me permissions the rest of the app reads. Cost,
// cost snapshot and profit appear only with retail.profit.read, and the server never sends them
// otherwise (docs/sdd/07-api-design.md section 7.11.20, chapter 8 section 8.3.2).

export const PROFIT = 'retail.profit.read';

export type RetailScreen = 'sale' | 'restock' | 'usage' | 'stock' | 'stocktake' | 'valuation' | 'profit';

const NEEDS: Record<RetailScreen, string[]> = {
  sale: ['retail.sale.create'],
  restock: ['retail.purchase.create'],
  usage: ['retail.usage.report'],
  stock: ['retail.stock.read'],
  stocktake: ['retail.stocktake.commit', 'retail.stock.read'],
  // Stock value is a stock read; its cost columns and the profit report need retail.profit.read.
  valuation: ['retail.stock.read'],
  profit: [PROFIT],
};

export function permissionsOf(me: Pick<Me, 'permissions'>): string[] {
  return me.permissions ?? [];
}

export function canSeeProfit(me: Pick<Me, 'permissions'>): boolean {
  return permissionsOf(me).includes(PROFIT);
}

export function canUse(me: Pick<Me, 'permissions'>, screen: RetailScreen): boolean {
  const held = permissionsOf(me);
  return NEEDS[screen].every((p) => held.includes(p));
}

export function hasAnyRetailPermission(me: Pick<Me, 'permissions'>): boolean {
  return permissionsOf(me).some((p) => p.startsWith('retail.'));
}

/**
 * The Retail menu entry: the tenant has the module and the user holds a retail permission.
 * /me does not list modules yet; when it does (`modules`), it is honoured, and until then the
 * module is implied by retail permissions, which exist only for tenants that switched it on.
 */
export function showRetail(me: Pick<Me, 'permissions'> & { modules?: string[] }): boolean {
  if (me.modules && !me.modules.includes('retail')) return false;
  return hasAnyRetailPermission(me);
}

export const SCREENS: { screen: RetailScreen; path: string; label: string; hint: string }[] = [
  { screen: 'sale', path: '/staff/retail/sale', label: 'Record a sale', hint: 'Sell items and take payment' },
  { screen: 'restock', path: '/staff/retail/restock', label: 'Restock', hint: 'Record stock bought from a supplier' },
  { screen: 'usage', path: '/staff/retail/usage', label: 'Usage and damage', hint: 'Items used or damaged' },
  { screen: 'stock', path: '/staff/retail/stock', label: 'Stock', hint: 'What is on the shelf' },
  { screen: 'stocktake', path: '/staff/retail/stocktake', label: 'Stock-take', hint: 'Count and correct stock' },
  { screen: 'valuation', path: '/staff/retail/valuation', label: 'Stock value', hint: 'Stock at cost and at price' },
  { screen: 'profit', path: '/staff/retail/profit', label: 'Daily profit', hint: 'Profit per day' },
];

export function screensFor(me: Pick<Me, 'permissions'>) {
  return SCREENS.filter((s) => canUse(me, s.screen));
}
