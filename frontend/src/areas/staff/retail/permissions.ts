import type { Me } from '../../../api/client';

// Which retail screens a session may use. The API is the authority; this only decides what the
// menu and the routes offer, from the same /me permissions the rest of the app reads. Cost,
// cost snapshot and profit appear only with retail.profit.read, and the server never sends them
// otherwise (docs/sdd/07-api-design.md section 7.11.20, chapter 8 section 8.3.2).

export const PROFIT = 'retail.profit.read';

export type RetailScreen =
  | 'sale' | 'creditSales' | 'salesHistory' | 'restock' | 'usage' | 'stock' | 'stocktake' | 'transfer' | 'transfers' | 'valuation' | 'profit'
  | 'catalogue' | 'products' | 'categories' | 'units' | 'suppliers' | 'buyers' | 'importer';

/** The catalogue management screens (#146); the Catalogue tile opens when any of them may be used. */
export const CATALOGUE_SCREENS: RetailScreen[] = ['products', 'categories', 'units', 'suppliers', 'buyers'];

export const TRANSFER = 'retail.stock.transfer';

const NEEDS: Record<RetailScreen, string[]> = {
  sale: ['retail.sale.create'],
  // The sales lists read sales in the caller's branch scope.
  creditSales: ['retail.sale.read'],
  salesHistory: ['retail.sale.read'],
  restock: ['retail.purchase.create'],
  usage: ['retail.usage.report'],
  stock: ['retail.stock.read'],
  stocktake: ['retail.stocktake.commit', 'retail.stock.read'],
  // Moving stock needs the transfer permission; the list of transfers is a stock read.
  transfer: [TRANSFER, 'retail.stock.read'],
  transfers: ['retail.stock.read'],
  // Stock value is a stock read; its cost columns and the profit report need retail.profit.read.
  valuation: ['retail.stock.read'],
  profit: [PROFIT],
  // The Catalogue home needs nothing itself: canUse opens it when any screen under it may be used.
  catalogue: [],
  // The lists these screens read are stock, purchase and sale reads; changing them needs the manage permissions.
  products: ['retail.catalogue.manage', 'retail.stock.read'],
  categories: ['retail.catalogue.manage', 'retail.stock.read'],
  units: ['retail.catalogue.manage', 'retail.stock.read'],
  suppliers: ['retail.catalogue.manage', 'retail.purchase.create'],
  buyers: ['retail.customer.manage', 'retail.sale.read'],
  // Importing items needs the catalogue permission; core.settings.manage is the administrator's (#146).
  importer: ['retail.catalogue.manage', 'retail.stock.read', 'core.settings.manage'],
};

/** Every permission a screen needs. */
export function needsOf(screen: RetailScreen): string[] {
  return NEEDS[screen];
}

export function permissionsOf(me: Pick<Me, 'permissions'>): string[] {
  return me.permissions ?? [];
}

/**
 * The branches of the session where `permission` is held. `/me` sends each permission's scope
 * (`permission_scopes`); a payload without them offers every branch, and a permission the user does
 * not hold offers none.
 */
export function branchesWhere<B extends { id?: string }>(
  me: Pick<Me, 'permission_scopes'> & { branches?: B[] },
  permission: string,
): B[] {
  const branches = me.branches ?? [];
  if (!me.permission_scopes) return branches;
  const scope = me.permission_scopes[permission];
  if (!scope) return [];
  if (scope.all_branches) return branches;
  const ids = new Set(scope.branch_ids ?? []);
  return branches.filter((b) => b.id && ids.has(b.id));
}

export function canSeeProfit(me: Pick<Me, 'permissions'>): boolean {
  return permissionsOf(me).includes(PROFIT);
}

export function canUse(me: Pick<Me, 'permissions'>, screen: RetailScreen): boolean {
  if (screen === 'catalogue') return CATALOGUE_SCREENS.some((s) => canUse(me, s));
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
  { screen: 'creditSales', path: '/staff/retail/credit-sales', label: 'Credit sales', hint: 'Who owes, how much, and by when' },
  { screen: 'salesHistory', path: '/staff/retail/sales', label: 'All sales', hint: 'Every sale, with filters' },
  { screen: 'restock', path: '/staff/retail/restock', label: 'Restock', hint: 'Record stock bought from a supplier' },
  { screen: 'usage', path: '/staff/retail/usage', label: 'Usage and damage', hint: 'Items used or damaged' },
  { screen: 'stock', path: '/staff/retail/stock', label: 'Stock', hint: 'What is on the shelf' },
  { screen: 'stocktake', path: '/staff/retail/stocktake', label: 'Stock-take', hint: 'Count and correct stock' },
  { screen: 'transfer', path: '/staff/retail/transfer', label: 'Move stock', hint: 'Send stock to another branch' },
  { screen: 'transfers', path: '/staff/retail/transfers', label: 'Stock moves', hint: 'Stock sent between branches' },
  { screen: 'valuation', path: '/staff/retail/valuation', label: 'Stock value', hint: 'Stock at cost and at price' },
  { screen: 'profit', path: '/staff/retail/profit', label: 'Daily profit', hint: 'Profit per day' },
  { screen: 'catalogue', path: '/staff/retail/catalogue', label: 'Catalogue', hint: 'Items, categories, units, suppliers and credit buyers' },
];

/** The screens under Catalogue, in the order the Catalogue home lists them. */
export const CATALOGUE_LINKS: { screen: RetailScreen; path: string; label: string; hint: string }[] = [
  { screen: 'products', path: '/staff/retail/catalogue/products', label: 'Items', hint: 'Add or change an item, change its price' },
  { screen: 'categories', path: '/staff/retail/catalogue/categories', label: 'Categories', hint: 'Group your items' },
  { screen: 'units', path: '/staff/retail/catalogue/units', label: 'Units', hint: 'Piece, roll, kilogram and so on' },
  { screen: 'suppliers', path: '/staff/retail/catalogue/suppliers', label: 'Suppliers', hint: 'Who you buy from' },
  { screen: 'buyers', path: '/staff/retail/catalogue/buyers', label: 'Credit buyers', hint: 'Who buys on credit' },
];

export function screensFor(me: Pick<Me, 'permissions'>) {
  return SCREENS.filter((s) => canUse(me, s.screen));
}
