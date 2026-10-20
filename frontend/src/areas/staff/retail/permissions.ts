import type { Me } from '../../../api/client';
import type { BranchRole } from '../../../api/retail';

// Which retail screens a session may use. The API is the authority; this only decides what the
// menu and the routes offer, from the same /me permissions the rest of the app reads. Cost,
// cost snapshot and profit appear only with retail.profit.read, and the server never sends them
// otherwise (docs/sdd/07-api-design.md section 7.11.20, chapter 8 section 8.3.2).

export const PROFIT = 'retail.profit.read';

export type RetailScreen =
  | 'sale' | 'creditSales' | 'salesHistory' | 'restock' | 'usage' | 'stock' | 'stocktake' | 'transfer' | 'transfers' | 'valuation' | 'profit' | 'salesAnalysis' | 'margins' | 'stockHealth' | 'creditControl' | 'evaluation'
  | 'catalogue' | 'products' | 'categories' | 'units' | 'suppliers' | 'buyers' | 'importer'
  // The trade screens of ADR-033: offered only while their capability is on (SCREEN_CAPABILITY below).
  | 'stockByLocation' | 'inTransit' | 'priceLists' | 'customerGroups' | 'customerAccounts' | 'customerAgeing' | 'documents' | 'quotations' | 'returns'
  // Optional purchasing (ADR-033, issue #198): orders and goods received, then supplier invoices, payments and what is owed.
  | 'purchaseOrders' | 'goodsReceived' | 'supplierBills' | 'supplierPayments' | 'supplierPayables'
  // Landed costs and supplier credit notes (issue #199), also under the purchasing capability.
  | 'landedCosts' | 'supplierCredits'
  // The cash book (ADR-022, FR-RET-31).
  | 'savings' | 'banking' | 'expenses' | 'withdrawals' | 'advances' | 'cashSummary' | 'bankingReport' | 'expensesReport' | 'expenseSetup'
  // The administrator's retail settings (profile and capabilities) and the screens of a capability (ADR-033).
  | 'settings' | 'branchRoles';

/**
 * The capability a screen belongs to (ADR-033): the screen is offered only while /me lists it, whatever
 * permissions the user holds. A screen that is not listed here belongs to no capability and behaves as
 * it did before capabilities existed. A later slice of the trade build plan adds its screens here.
 */
export const SCREEN_CAPABILITY: Partial<Record<RetailScreen, string>> = {
  branchRoles: 'branch_roles',
  stockByLocation: 'branch_roles',
  inTransit: 'transfer_receipt',
  priceLists: 'price_lists',
  customerGroups: 'price_lists',
  customerAccounts: 'customer_accounts',
  customerAgeing: 'customer_accounts',
  documents: 'documents',
  quotations: 'documents',
  returns: 'returns',
  purchaseOrders: 'purchasing',
  goodsReceived: 'purchasing',
  supplierBills: 'purchasing',
  supplierPayments: 'purchasing',
  supplierPayables: 'purchasing',
  landedCosts: 'purchasing',
  supplierCredits: 'purchasing',
};

/** The catalogue management screens (#146); the Catalogue tile opens when any of them may be used. */
export const CATALOGUE_SCREENS: RetailScreen[] = ['products', 'categories', 'units', 'suppliers', 'buyers'];

export const TRANSFER = 'retail.stock.transfer';
export const PRICELIST_MANAGE = 'retail.pricelist.manage';
/** Price a sale from a list other than the customer's own or the group's. */
export const PRICELIST_USE = 'retail.pricelist.use';
/** Charge a price other than the one a price list resolved, with a reason. */
export const PRICE_OVERRIDE = 'retail.price.override';
/** Set a credit buyer's limit and payment terms (administrators; the sales role edits name and contact only). */
export const CREDIT_MANAGE = 'retail.credit.manage';
/** Sell on credit above a buyer's limit, with a reason. */
export const CREDIT_OVERRIDE = 'retail.credit.override';
/** Issue and reprint quotations, invoices and delivery notes, and make the customer statement PDF (administrators; documents capability). */
export const DOCUMENT_ISSUE = 'retail.document.issue';
/** Take goods back against a sale, and void a return (administrators; consulted only while the returns capability is on). */
export const SALE_RETURN = 'retail.sale.return';
/** Reverse a payment on account (the permission that voids a sale). */
export const SALE_VOID = 'retail.sale.void';
/** Purchase orders and goods received (every holder of the restock permission, consulted only while the purchasing capability is on). */
export const PURCHASE_CREATE = 'retail.purchase.create';
/** Decide a large purchase order in the approvals inbox (a second person, never the one who sent it). */
export const PURCHASE_APPROVE = 'retail.purchase.approve';
/** Supplier invoices, payments to suppliers, open items and the payables report (administrators). */
export const PAYABLE_MANAGE = 'retail.payable.manage';
export const SETTINGS_MANAGE = 'core.settings.manage';
export const SAVINGS_RECORD = 'retail.savings.record';
export const SAVINGS_OVERWRITE = 'retail.savings.overwrite';
export const CASHBOOK_READ = 'retail.cashbook.read';
export const CASHBOOK_VOID = 'retail.cashbook.void';
export const EXPENSE_MANAGE = 'retail.expense.manage';

/** Party kinds anyone who records expenses may add on the fly; the others need retail.expense.manage (ADR-022 decision 13). */
const BENEFICIARY_KINDS: readonly string[] = ['supplier', 'other'];

/** Of these party kinds, the ones the session may add: beneficiaries with the record permission, the rest with manage. */
export function addableKinds<K extends string>(me: Pick<Me, 'permissions'>, kinds: readonly K[]): K[] {
  return kinds.filter((k) => BENEFICIARY_KINDS.includes(k) || permissionsOf(me).includes(EXPENSE_MANAGE));
}

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
  // The API needs only a stock read for both, but they are the screens of whoever moves stock between
  // stores and shops; a seller sees the same screens with every capability on as with every one off.
  stockByLocation: [TRANSFER, 'retail.stock.read'],
  inTransit: [TRANSFER, 'retail.stock.read'],
  // Price lists and customer groups are the administrator's (retail.pricelist.manage); the editor finds items by the stock read.
  priceLists: [PRICELIST_MANAGE, 'retail.stock.read'],
  customerGroups: [PRICELIST_MANAGE],
  // The Customer accounts tile is the administrator's (limits and terms are retail.credit.manage); a seller opens one buyer's
  // account from Credit buyers or Credit control, which the account page allows with a sales read (canOpenAccount).
  customerAccounts: [CREDIT_MANAGE, 'retail.sale.read'],
  // Customer ageing is a tile of the administrator's, like Customer accounts, so a seller's menu is the same with the feature on or
  // off; the report itself is a sales read on the server and agrees with Credit control.
  customerAgeing: [CREDIT_MANAGE, 'retail.sale.read'],
  // Documents are an after-step the administrator issues; a quotation is converted with retail.sale.create, checked where it is offered.
  documents: [DOCUMENT_ISSUE],
  quotations: [DOCUMENT_ISSUE],
  // Customer returns and their credit notes are the administrator's (retail.sale.return); the sale to take goods back from is found
  // in the sales lists, which are a sales read.
  returns: [SALE_RETURN, 'retail.sale.read'],
  // Purchase orders and goods received are the restock permission's; the money side (invoices, payments, what is owed) is the administrators'.
  purchaseOrders: [PURCHASE_CREATE],
  goodsReceived: [PURCHASE_CREATE],
  supplierBills: [PAYABLE_MANAGE],
  supplierPayments: [PAYABLE_MANAGE],
  supplierPayables: [PAYABLE_MANAGE],
  // Landed costs and supplier credit notes are the administrator's too (issue #199): no new permission exists.
  landedCosts: [PAYABLE_MANAGE],
  supplierCredits: [PAYABLE_MANAGE],
  // Stock value is a stock read; its cost columns and the profit report need retail.profit.read.
  valuation: ['retail.stock.read'],
  profit: [PROFIT],
  // The analytics reports (issue #149); profit columns arrive only with retail.profit.read.
  salesAnalysis: ['retail.sale.read'],
  margins: [PROFIT],
  stockHealth: ['retail.stock.read'],
  creditControl: ['retail.sale.read'],
  evaluation: [PROFIT],
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
  // Profile and capabilities are the administrator's (core.settings.manage); roles need their own permission and the capability.
  settings: ['core.settings.manage'],
  branchRoles: ['retail.branch_role.manage', 'retail.stock.read'],
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

export function canUse(me: Pick<Me, 'permissions' | 'module_capabilities'>, screen: RetailScreen): boolean {
  if (screen === 'catalogue') return CATALOGUE_SCREENS.some((s) => canUse(me, s));
  const capability = SCREEN_CAPABILITY[screen];
  if (capability && !capabilityOn(me, 'retail', capability)) return false;
  const held = permissionsOf(me);
  const any = ANY_OF[screen];
  return NEEDS[screen].every((p) => held.includes(p)) && (!any || any.some((p) => held.includes(p)));
}

/** True when the session holds `permission`. */
export function holds(me: Pick<Me, 'permissions'>, permission: string): boolean {
  return permissionsOf(me).includes(permission);
}

/**
 * True when the session holds `permission` at `branchId`: the check the server makes (ADR-017), so a user whose override
 * covers one branch is not offered it at another. Without a branch it is the plain `holds`.
 */
export function holdsAt(me: Pick<Me, 'permissions' | 'permission_scopes' | 'branches'>, permission: string, branchId: string | null | undefined): boolean {
  if (!holds(me, permission)) return false;
  if (!branchId) return true;
  return branchesWhere(me, permission).some((b) => b.id === branchId);
}

/**
 * True when the salesperson level may be offered: profit read covers every branch whose sales are in view (the one chosen,
 * or all the session may read), the rule the server applies to the level and to the seller filter (ADR-017).
 */
export function canRankSellers(me: Pick<Me, 'permissions' | 'permission_scopes' | 'branches'>, branchId: string | null | undefined): boolean {
  const viewed = branchesWhere(me, 'retail.sale.read').filter((b) => !branchId || b.id === branchId);
  return viewed.length > 0 && viewed.every((b) => holdsAt(me, PROFIT, b.id));
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

/** The retail capabilities of ADR-033; a screen of a capability is offered only while /me lists it. */
export const BRANCH_ROLES = 'branch_roles';
export const UNITS = 'units';
export const PRICE_LISTS = 'price_lists';
export const CUSTOMER_ACCOUNTS = 'customer_accounts';
export const RETURNS = 'returns';
export const PURCHASING = 'purchasing';
export const BRANCH_ROLE_MANAGE = 'retail.branch_role.manage';
export const TRANSFER_RECEIPT = 'transfer_receipt';
export const DOCUMENTS = 'documents';

/** The documents capability is on and the session may issue documents somewhere: the buttons and screens of documents are offered. */
export function canIssueDocuments(me: Pick<Me, 'permissions' | 'module_capabilities'>): boolean {
  return capabilityOn(me, 'retail', DOCUMENTS) && permissionsOf(me).includes(DOCUMENT_ISSUE);
}

/** A buyer's account page (customer_accounts): open to whoever reads sales while the feature is on; its forms check their own permissions. */
export function canOpenAccount(me: Pick<Me, 'permissions' | 'module_capabilities'>): boolean {
  return capabilityOn(me, 'retail', CUSTOMER_ACCOUNTS) && permissionsOf(me).includes('retail.sale.read');
}

/** True when /me lists `capability` among the enabled capabilities of `module` (absent means off). */
export function capabilityOn(me: Pick<Me, 'module_capabilities'>, module: string, capability: string): boolean {
  return (me.module_capabilities?.[module] ?? []).includes(capability);
}

/**
 * Whether a sale may be made at the branch. `roles` is undefined while branch_roles is off or not yet
 * read, and then every branch sells, as before roles existed. The server refuses a sale at a store
 * whatever this says (422 branch_role_forbids_sales).
 */
export function sellsAt(roles: BranchRole[] | undefined, branchId: string | null | undefined): boolean {
  if (!roles || !branchId) return true;
  return roles.find((r) => r.branch_id === branchId)?.can_sell ?? true;
}

/** The branches of `branches` a sale may be made at: stores are left out. */
export function sellingBranches<B extends { id?: string; stocks_goods?: boolean }>(branches: B[], roles: BranchRole[] | undefined): B[] {
  return branches.filter((b) => b.stocks_goods !== false && sellsAt(roles, b.id));
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
  { screen: 'stockByLocation', path: '/staff/retail/stock-by-location', label: 'Stores and shops', hint: 'Stock in each store and shop, and on the way' },
  { screen: 'transfers', path: '/staff/retail/transfers', label: 'Stock moves', hint: 'Stock sent between branches' },
  { screen: 'inTransit', path: '/staff/retail/in-transit', label: 'On the way', hint: 'Stock sent and not yet received' },
  { screen: 'valuation', path: '/staff/retail/valuation', label: 'Stock value', hint: 'Stock at cost and at price' },
  { screen: 'profit', path: '/staff/retail/profit', label: 'Daily profit', hint: 'Profit per day' },
  { screen: 'salesAnalysis', path: '/staff/retail/sales-analysis', label: 'Sales analysis', hint: 'Sales by day, shop, item and seller' },
  { screen: 'margins', path: '/staff/retail/margins', label: 'Margins', hint: 'Profit by item, low margins, price changes' },
  { screen: 'stockHealth', path: '/staff/retail/stock-health', label: 'Stock health', hint: 'Days of cover, reorder, dead stock, shrinkage' },
  { screen: 'creditControl', path: '/staff/retail/credit-control', label: 'Credit control', hint: 'Who owes, how late, payments received' },
  { screen: 'evaluation', path: '/staff/retail/evaluation', label: 'Business evaluation', hint: 'Profit to date and what the stock is worth' },
  { screen: 'catalogue', path: '/staff/retail/catalogue', label: 'Catalogue', hint: 'Items, categories, units, suppliers and credit buyers' },
  { screen: 'banking', path: '/staff/retail/banking', label: 'Banking', hint: 'Cash banked, and what to expect' },
  { screen: 'savings', path: '/staff/retail/savings', label: 'Savings', hint: 'Set money aside for the day' },
  { screen: 'expenses', path: '/staff/retail/expenses', label: 'Expenses', hint: 'Money spent, by category' },
  { screen: 'withdrawals', path: '/staff/retail/withdrawals', label: 'Cash withdrawals', hint: 'Cash taken out of the bank' },
  { screen: 'advances', path: '/staff/retail/advances', label: 'Advances', hint: 'Money advanced, and repaid' },
  { screen: 'cashSummary', path: '/staff/retail/cash-summary', label: 'Cash summary', hint: 'Each shop, each day' },
  { screen: 'bankingReport', path: '/staff/retail/banking-report', label: 'Banking report', hint: 'Expected, banked and unbanked' },
  { screen: 'expensesReport', path: '/staff/retail/expenses-report', label: 'Expenses report', hint: 'Spending by category and item' },
  { screen: 'expenseSetup', path: '/staff/retail/expense-lists', label: 'Expense lists', hint: 'Categories and items' },
  { screen: 'priceLists', path: '/staff/retail/price-lists', label: 'Price lists', hint: 'Different prices for different customers, with quantity discounts' },
  { screen: 'customerGroups', path: '/staff/retail/customer-groups', label: 'Customer groups', hint: 'Group customers that share a price list' },
  { screen: 'customerAccounts', path: '/staff/retail/accounts', label: 'Customer accounts', hint: 'Credit limits, statements and payments from customers' },
  { screen: 'customerAgeing', path: '/staff/retail/customer-ageing', label: 'Customer ageing', hint: 'What each credit buyer owes, by how late it is' },
  { screen: 'documents', path: '/staff/retail/documents', label: 'Documents', hint: 'Invoices, delivery notes and quotations, with their numbers' },
  { screen: 'quotations', path: '/staff/retail/quotations', label: 'Quotations', hint: 'Prices offered to a buyer until a date' },
  { screen: 'returns', path: '/staff/retail/returns', label: 'Customer returns', hint: 'Goods taken back from customers, and their credit notes' },
  { screen: 'purchaseOrders', path: '/staff/retail/purchase-orders', label: 'Purchase orders', hint: 'Order from a supplier, then receive what arrives' },
  { screen: 'goodsReceived', path: '/staff/retail/goods-received', label: 'Goods received', hint: 'Deliveries from suppliers, with or without an order' },
  { screen: 'supplierBills', path: '/staff/retail/supplier-bills', label: 'Supplier invoices', hint: 'What suppliers have billed you for the goods received' },
  { screen: 'supplierPayments', path: '/staff/retail/supplier-payments', label: 'Supplier payments', hint: 'Money paid to suppliers, and what each payment cleared' },
  { screen: 'supplierPayables', path: '/staff/retail/supplier-payables', label: 'What you owe suppliers', hint: 'Owed to each supplier, by how late it is' },
  { screen: 'landedCosts', path: '/staff/retail/landed-costs', label: 'Landed costs', hint: 'Freight, duty and clearing, added to what the goods cost' },
  { screen: 'supplierCredits', path: '/staff/retail/supplier-credits', label: 'Supplier credit notes', hint: 'Goods sent back to a supplier, or money taken off an invoice' },
  { screen: 'branchRoles', path: '/staff/retail/branch-roles', label: 'Branch roles', hint: 'Which branches are stores and which are shops' },
  { screen: 'settings', path: '/staff/retail/settings', label: 'Retail settings', hint: 'Shop or trade, and the features you use' },
];

/** The screens under Catalogue, in the order the Catalogue home lists them. */
export const CATALOGUE_LINKS: { screen: RetailScreen; path: string; label: string; hint: string }[] = [
  { screen: 'products', path: '/staff/retail/catalogue/products', label: 'Items', hint: 'Add or change an item, change its price' },
  { screen: 'categories', path: '/staff/retail/catalogue/categories', label: 'Categories', hint: 'Group your items' },
  { screen: 'units', path: '/staff/retail/catalogue/units', label: 'Units', hint: 'Piece, roll, kilogram and so on' },
  { screen: 'suppliers', path: '/staff/retail/catalogue/suppliers', label: 'Suppliers', hint: 'Who you buy from' },
  { screen: 'buyers', path: '/staff/retail/catalogue/buyers', label: 'Credit buyers', hint: 'Who buys on credit' },
];

/**
 * The screens the session may use. With `sells` false (every branch it can sell at is a store) the
 * sales screen is left out, so a user whose branches are stores starts from the stock screens.
 */
export function screensFor(me: Pick<Me, 'permissions' | 'module_capabilities'>, opts: { sells?: boolean } = {}) {
  return SCREENS.filter((s) => canUse(me, s.screen) && (opts.sells !== false || s.screen !== 'sale'));
}
