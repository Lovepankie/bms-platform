import type { Me } from '../../../api/client';

// Which retail screens a session may use. The API is the authority; this only decides what the
// menu and the routes offer, from the same /me permissions the rest of the app reads. Cost,
// cost snapshot and profit appear only with retail.profit.read, and the server never sends them
// otherwise (docs/sdd/07-api-design.md section 7.11.20, chapter 8 section 8.3.2).

export const PROFIT = 'retail.profit.read';

export type RetailScreen =
  | 'sale' | 'creditSales' | 'salesHistory' | 'restock' | 'usage' | 'stock' | 'stocktake' | 'transfer' | 'transfers' | 'valuation' | 'profit'
  // The cash book (ADR-022, FR-RET-31).
  | 'savings' | 'banking' | 'expenses' | 'withdrawals' | 'advances' | 'cashSummary' | 'bankingReport' | 'expensesReport' | 'expenseSetup';

export const TRANSFER = 'retail.stock.transfer';
export const SAVINGS_RECORD = 'retail.savings.record';
export const SAVINGS_OVERWRITE = 'retail.savings.overwrite';
export const CASHBOOK_READ = 'retail.cashbook.read';
export const CASHBOOK_VOID = 'retail.cashbook.void';

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
  // The cash book screens each need the permission that records on them; the lists and reports on
  // them need retail.cashbook.read, and a void needs retail.cashbook.void (checked where it is offered).
  savings: [SAVINGS_RECORD],
  banking: ['retail.banking.record'],
  expenses: ['retail.expense.record'],
  withdrawals: ['retail.withdrawal.record'],
  // Advances open to whoever may record an advance or a repayment (see ANY_OF).
  advances: [],
  cashSummary: [CASHBOOK_READ],
  bankingReport: [CASHBOOK_READ],
  expensesReport: [CASHBOOK_READ],
  expenseSetup: ['retail.expense.manage', CASHBOOK_READ],
};

/** Screens that open with any one of these permissions, on top of the ones NEEDS lists. */
const ANY_OF: Partial<Record<RetailScreen, string[]>> = { advances: ['retail.advance.create', 'retail.advance.repay'] };

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
  const held = permissionsOf(me);
  const any = ANY_OF[screen];
  return NEEDS[screen].every((p) => held.includes(p)) && (!any || any.some((p) => held.includes(p)));
}

/** True when the session holds `permission`. */
export function holds(me: Pick<Me, 'permissions'>, permission: string): boolean {
  return permissionsOf(me).includes(permission);
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
  { screen: 'banking', path: '/staff/retail/banking', label: 'Banking', hint: 'Cash banked, and what to expect' },
  { screen: 'savings', path: '/staff/retail/savings', label: 'Savings', hint: 'Set money aside for the day' },
  { screen: 'expenses', path: '/staff/retail/expenses', label: 'Expenses', hint: 'Money spent, by category' },
  { screen: 'withdrawals', path: '/staff/retail/withdrawals', label: 'Cash withdrawals', hint: 'Cash taken out of the bank' },
  { screen: 'advances', path: '/staff/retail/advances', label: 'Advances', hint: 'Money advanced, and repaid' },
  { screen: 'cashSummary', path: '/staff/retail/cash-summary', label: 'Cash summary', hint: 'Each shop, each day' },
  { screen: 'bankingReport', path: '/staff/retail/banking-report', label: 'Banking report', hint: 'Expected, banked and unbanked' },
  { screen: 'expensesReport', path: '/staff/retail/expenses-report', label: 'Expenses report', hint: 'Spending by category and item' },
  { screen: 'expenseSetup', path: '/staff/retail/expense-lists', label: 'Expense lists', hint: 'Categories and items' },
];

export function screensFor(me: Pick<Me, 'permissions'>) {
  return SCREENS.filter((s) => canUse(me, s.screen));
}
