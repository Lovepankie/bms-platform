import type { Me } from '../api/client';
import type { Tour, TourStep } from './model';

// Page tours for the retail catalogue lists, the cash book and the buyers pages (issue #337). Each is a page tour for whoever may
// open the page; a step that needs more carries its own permission or is optional. Plain words, short sentences, say what to tap.
// tours-retail-cash.test.tsx renders each page and fails if an anchor is missing.

const CATALOGUE = 'retail.catalogue.manage';
const STOCK_READ = 'retail.stock.read';
const PURCHASE = 'retail.purchase.create';
const CUSTOMER = 'retail.customer.manage';
const SALE_READ = 'retail.sale.read';
const SETTINGS = 'core.settings.manage';
const PRICE_EDIT = 'retail.price.edit';
const BANKING = 'retail.banking.record';
const EXPENSE_RECORD = 'retail.expense.record';
const EXPENSE_MANAGE = 'retail.expense.manage';
const CASHBOOK_READ = 'retail.cashbook.read';
const WITHDRAWAL = 'retail.withdrawal.record';
const ADVANCE_CREATE = 'retail.advance.create';
const ADVANCE_REPAY = 'retail.advance.repay';
const SAVINGS = 'retail.savings.record';
const BRANCH_ROLE = 'retail.branch_role.manage';
const PRICELIST = 'retail.pricelist.manage';
const CREDIT_MANAGE = 'retail.credit.manage';

const held = (me: Me, permission: string) => (me.permissions ?? []).includes(permission);
const capable = (me: Me, capability: string) => (me.module_capabilities?.['retail'] ?? []).includes(capability);

/** A step of the retail module. */
const step = (s: Omit<TourStep, 'module'>): TourStep => ({ module: 'retail', ...s });

export const productsPage: Tour = {
  id: 'page-products',
  version: 4,
  title: 'This page: items',
  audience: (me) => held(me, CATALOGUE) && held(me, STOCK_READ),
  autoStart: false,
  page: '/staff/retail/catalogue/products',
  steps: [
    step({ id: 'add', target: 'product-add', title: 'Add an item', body: 'Tap here to add something you sell. Type the code, the name, pick the category and the unit, then the price.' }),
    step({ id: 'sizes', target: 'product-add-sizes', title: 'Sizes and colours', body: 'Sell one design in several sizes or prints? Tap here, type the sizes and the colours once, and each one gets its own code, price and stock.' }),
    step({ id: 'join', target: 'product-join-sizes', title: 'One item per size?', body: 'If you typed each size as its own item, tap here to join them. Each keeps its stock and history.' }),
    step({ id: 'search', target: 'product-search', title: 'Find an item', body: 'Type a name, a code or a category. The list narrows as you type.' }),
    step({ id: 'filters', target: 'product-filters', title: 'Narrow the list', body: 'Pick a category, or choose Switched off to see items you no longer sell.' }),
    step({ id: 'edit', target: 'product-list', title: 'Change an item', body: 'Tap Edit on an item to change its name, category or unit. Tap Stock to see how much of it you hold.' }),
    // Issue #412: the low-stock level is on the item's page, under Edit.
    step({ id: 'minimum', target: 'product-list', title: 'Warn me when stock is below', body: 'Tap Edit on an item to set when you are told it is running low. Set one level for every shop and store, or a different one for a shop. With no level, you are told when it would run out within 14 days.' }),
    step({ id: 'price', target: 'product-list', needs: [PRICE_EDIT], optional: true, title: 'Change a price', body: 'Tap Change price on an item. The old price stays in its history, so earlier sales keep the price they were made at.' }),
  ],
};

/** Issue #384: making an item with sizes and colours. */
export const sizesNewPage: Tour = {
  id: 'page-sizes-new',
  version: 1,
  title: 'This page: an item with sizes and colours',
  audience: (me) => held(me, CATALOGUE) && held(me, STOCK_READ),
  autoStart: false,
  page: '/staff/retail/catalogue/products/sizes/new',
  steps: [
    step({ id: 'name', target: 'sizes-name', title: 'Name it once', body: 'Type what it is called, a short code, the category, the unit and the price. Every size and colour starts from these.' }),
    step({ id: 'options', target: 'sizes-options', title: 'Sizes and colours', body: 'Type the sizes, then the colours or prints, each list separated by commas. Every size comes in every colour.' }),
    step({ id: 'preview', target: 'sizes-preview', title: 'Check the list', body: 'Each one shows its code and price. Change a price here if a bigger size costs more.' }),
    step({ id: 'save', target: 'sizes-save', title: 'Make them', body: 'Tap here. Each size and colour is then an item you can sell, restock, move and count on its own.' }),
  ],
};

/** Issue #384: items kept apart, one per size, joined as the sizes of one item. */
export const sizesJoinPage: Tour = {
  id: 'page-sizes-join',
  version: 1,
  title: 'This page: join items as sizes',
  audience: (me) => held(me, CATALOGUE) && held(me, STOCK_READ),
  autoStart: false,
  page: '/staff/retail/catalogue/products/sizes/join',
  steps: [
    step({ id: 'name', target: 'join-name', title: 'Name the item', body: 'Type what the item is called, such as Twin cable. Each size gets this name with its size.' }),
    step({ id: 'pick', target: 'join-pick', title: 'Pick the items', body: 'Find each item that is one size or colour, and tap Add as a size.' }),
    step({ id: 'list', target: 'join-list', title: 'Say which size', body: 'Type the size or the colour of each one. They keep their codes, prices and stock.' }),
    step({ id: 'save', target: 'join-save', title: 'Join them', body: 'Tap here. They now show together as one item, and sell and restock as before.' }),
  ],
};

/** Issue #384: the page of an item with sizes and colours. */
export const sizesPage: Tour = {
  id: 'page-sizes',
  version: 2,
  title: 'This page: sizes and colours',
  audience: (me) => held(me, CATALOGUE) && held(me, STOCK_READ),
  autoStart: false,
  page: '/staff/retail/catalogue/products/sizes/$familyId',
  steps: [
    step({ id: 'photo', target: 'family-photo', title: 'A photo', body: 'Add a photo of the item. It shows in the item list and in a sale, so the right print is easy to find.' }),
    step({ id: 'stock', target: 'family-stock', title: 'All its stock', body: 'This is the stock of every size and colour together, in each shop and store you can see.' }),
    step({ id: 'variants', target: 'family-variants', title: 'Each size and colour', body: 'Each one has its own code, price and stock. Tap Change price for one size, or Change to fix its size, colour or code.' }),
    step({ id: 'add', target: 'family-add', title: 'Add more', body: 'Type a new size or a new print. It is made in every colour or size the item has, at the price of the first one.' }),
    step({ id: 'links', target: 'family-links', title: 'Sell and restock', body: 'Record a sale, restock or move stock as usual: look for the name with the size and colour.' }),
  ],
};

export const categoriesPage: Tour = {
  id: 'page-categories',
  version: 1,
  title: 'This page: categories',
  audience: (me) => held(me, CATALOGUE) && held(me, STOCK_READ),
  autoStart: false,
  page: '/staff/retail/catalogue/categories',
  steps: [
    step({ id: 'add', target: 'named-add', title: 'Add a category', body: 'Tap here and type a name, such as Lighting. Items are grouped by their category.' }),
    step({ id: 'list', target: 'named-list', title: 'Rename or switch off', body: 'Tap Rename to fix a name. Tap Switch off to stop new items using it. Old items keep it. Tap Items to see them.' }),
  ],
};

export const unitsPage: Tour = {
  id: 'page-units',
  version: 1,
  title: 'This page: units',
  audience: (me) => held(me, CATALOGUE) && held(me, STOCK_READ),
  autoStart: false,
  page: '/staff/retail/catalogue/units',
  steps: [
    step({ id: 'add', target: 'named-add', title: 'Add a unit', body: 'Tap here and type a unit, such as piece, kg or box. It is how you count an item.' }),
    step({ id: 'list', target: 'named-list', title: 'Rename or switch off', body: 'Tap Rename to fix a name. Tap Switch off to stop new items using it. Old items keep it. Tap Items to see them.' }),
  ],
};

export const suppliersPage: Tour = {
  id: 'page-suppliers',
  version: 1,
  title: 'This page: suppliers',
  audience: (me) => held(me, CATALOGUE) && held(me, PURCHASE),
  autoStart: false,
  page: '/staff/retail/catalogue/suppliers',
  steps: [
    step({ id: 'add', target: 'person-add', optional: true, title: 'Add a supplier', body: 'Tap here and type the name and a phone number. You pick a supplier when you restock.' }),
    step({ id: 'list', target: 'person-list', title: 'Your suppliers', body: 'Tap Edit to fix a name or phone. Tap Switch off for a supplier you no longer use. Past restocks keep it.' }),
  ],
};

export const buyersPage: Tour = {
  id: 'page-buyers',
  version: 1,
  title: 'This page: credit buyers',
  audience: (me) => held(me, CUSTOMER) && held(me, SALE_READ),
  autoStart: false,
  page: '/staff/retail/catalogue/buyers',
  steps: [
    step({ id: 'add', target: 'person-add', optional: true, title: 'Add a credit buyer', body: 'Tap here and type the name and a phone number. A credit sale needs a buyer from this list.' }),
    step({ id: 'search', target: 'person-search', title: 'Find a buyer', body: 'Type a name or a phone number. The list narrows as you type.' }),
    step({ id: 'list', target: 'person-list', title: 'What each buyer owes', body: 'Each card shows what the buyer owes. Tap Edit to fix a name or phone. If customer accounts are on, tap Open account to see their limit, take a payment or read their statement.' }),
  ],
};

export const importPage: Tour = {
  id: 'page-import',
  version: 1,
  title: 'This page: import items',
  audience: (me) => held(me, CATALOGUE) && held(me, STOCK_READ) && held(me, SETTINGS),
  autoStart: false,
  page: '/staff/retail/catalogue/import',
  steps: [
    step({ id: 'format', target: 'import-note', title: 'Get the file ready', body: 'The first row names the columns. Then put one item on each row. Nothing is saved until you add the items.' }),
    step({ id: 'file', target: 'import-file', title: 'Choose your file', body: 'Tap here and pick a CSV file saved as UTF-8.' }),
    step({ id: 'paste', target: 'import-text', title: 'Or paste the rows', body: 'You can also paste the rows straight into this box.' }),
    step({ id: 'check', target: 'import-check', title: 'Check it first', body: 'Tap Check the file. You see what would be added and which rows have a problem. When all is well, an Add button appears.' }),
  ],
};

export const bankingPage: Tour = {
  id: 'page-banking',
  version: 1,
  title: 'This page: banking',
  audience: (me) => held(me, BANKING),
  autoStart: false,
  page: '/staff/retail/banking',
  steps: [
    step({ id: 'form', target: 'banking-form', title: 'Record cash you banked', body: 'Pick the date and look at the cash expected. Type the amount you took to the bank, then tap Save banking.' }),
    step({ id: 'report', target: 'banking-report-link', optional: true, title: 'See it by day', body: 'Tap Banking report to compare, day by day, what was expected with what was banked.' }),
    step({ id: 'records', target: 'banking-records', needs: [CASHBOOK_READ], optional: true, title: 'Banking records', body: 'Choose the dates to list what was banked. If one was a mistake, tap Void and give the reason.' }),
  ],
};

export const bankingReportPage: Tour = {
  id: 'page-banking-report',
  version: 1,
  title: 'This page: banking report',
  audience: (me) => held(me, CASHBOOK_READ),
  autoStart: false,
  page: '/staff/retail/banking-report',
  steps: [
    step({ id: 'window', target: 'banking-report-window', title: 'Choose the dates', body: 'Pick the first and the last day you want to see.' }),
    step({ id: 'days', target: 'banking-report-days', title: 'One card for each day', body: 'Each card shows the cash expected and the cash banked. A day with a gap is marked, so you can look into it.' }),
    step({ id: 'record', target: 'banking-back-link', needs: [BANKING], optional: true, title: 'Record banking', body: 'Tap here when you have banked more cash.' }),
  ],
};

export const expensesPage: Tour = {
  id: 'page-expenses',
  version: 1,
  title: 'This page: expenses',
  audience: (me) => held(me, EXPENSE_RECORD),
  autoStart: false,
  page: '/staff/retail/expenses',
  steps: [
    step({ id: 'form', target: 'expense-form', title: 'Record an expense', body: 'Pick the date, the category and the item. Type the amount. Some items ask you to explain. Then tap save.' }),
    step({ id: 'lists', target: 'expenses-lists-link', needs: [EXPENSE_MANAGE, CASHBOOK_READ], optional: true, title: 'Change what you can pick', body: 'Tap Expense lists to add a category or an item.' }),
    step({ id: 'report', target: 'expenses-report-link', optional: true, title: 'Totals', body: 'Tap Expenses report to see the totals by category, item, shop or month.' }),
    step({ id: 'records', target: 'expense-records', needs: [CASHBOOK_READ], optional: true, title: 'Expense records', body: 'Choose the dates to list what was spent. If one was a mistake, tap Void and give the reason.' }),
  ],
};

export const expensesReportPage: Tour = {
  id: 'page-expenses-report',
  version: 1,
  title: 'This page: expenses report',
  audience: (me) => held(me, CASHBOOK_READ),
  autoStart: false,
  page: '/staff/retail/expenses-report',
  steps: [
    step({ id: 'window', target: 'expenses-report-window', title: 'Choose the dates', body: 'Pick the first and the last day you want to see.' }),
    step({ id: 'group', target: 'expenses-report-group', title: 'Group the totals', body: 'Tap By category, By item, By shop or By month to change how the totals are grouped.' }),
    step({ id: 'rows', target: 'expenses-report-rows', title: 'The totals', body: 'Each card is one group with its total and how many expenses it holds. The total for the whole period is at the bottom.' }),
    step({ id: 'record', target: 'expenses-back-link', needs: [EXPENSE_RECORD], optional: true, title: 'Record an expense', body: 'Tap here to record a new expense.' }),
  ],
};

export const expenseListsPage: Tour = {
  id: 'page-expense-lists',
  version: 1,
  title: 'This page: expense lists',
  audience: (me) => held(me, EXPENSE_MANAGE) && held(me, CASHBOOK_READ),
  autoStart: false,
  page: '/staff/retail/expense-lists',
  steps: [
    step({ id: 'help', target: 'expense-lists-help', title: 'What these lists are', body: 'The expense form offers these categories and the items inside them. Nothing is deleted. A switched off one stays on old records.' }),
    step({ id: 'body', target: 'expense-lists-body', title: 'Add and change', body: 'Type a new category and tap Add category. Inside a category you can add items, rename them or switch them off.' }),
  ],
};

export const withdrawalsPage: Tour = {
  id: 'page-withdrawals',
  version: 1,
  title: 'This page: cash withdrawals',
  audience: (me) => held(me, WITHDRAWAL),
  autoStart: false,
  page: '/staff/retail/withdrawals',
  steps: [
    step({ id: 'form', target: 'withdrawal-form', title: 'Record cash taken from the bank', body: 'Pick the date, type the amount and what it is for. Then tap save. The cash goes into the till.' }),
    step({ id: 'records', target: 'withdrawal-records', needs: [CASHBOOK_READ], optional: true, title: 'Withdrawal records', body: 'Choose the dates to list what was taken out. If one was a mistake, tap Void and give the reason.' }),
  ],
};

export const advancesPage: Tour = {
  id: 'page-advances',
  version: 1,
  title: 'This page: advances',
  audience: (me) => held(me, ADVANCE_CREATE) || held(me, ADVANCE_REPAY),
  autoStart: false,
  page: '/staff/retail/advances',
  steps: [
    step({ id: 'modes', target: 'advance-modes', title: 'Pick what to do', body: 'Tap Advances for the list, New advance to pay one out, Repay to take money back, or Who owes for the balances.' }),
    step({ id: 'body', target: 'advance-body', title: 'The page below', body: 'What you tapped above shows here. In the list, tap Open on an advance to see its repayments.' }),
  ],
};

export const cashSummaryPage: Tour = {
  id: 'page-cash-summary',
  version: 1,
  title: 'This page: cash summary',
  audience: (me) => held(me, CASHBOOK_READ),
  autoStart: false,
  page: '/staff/retail/cash-summary',
  steps: [
    step({ id: 'window', target: 'cash-window', title: 'Choose the dates', body: 'It starts with the last seven days. Change the first and the last day to see more.' }),
    step({ id: 'days', target: 'cash-days', title: 'One card for each shop and day', body: 'The card walks from the opening cash to the closing cash. Tap a name, such as Expenses or Banked, to see the rows behind it.' }),
  ],
};

export const savingsPage: Tour = {
  id: 'page-savings',
  version: 1,
  title: 'This page: savings',
  audience: (me) => held(me, SAVINGS),
  autoStart: false,
  page: '/staff/retail/savings',
  steps: [
    step({ id: 'form', target: 'savings-form', title: 'Set money aside for the day', body: 'Pick the date. You see what was sold and the amount to set aside. Check it and tap save.' }),
    step({ id: 'records', target: 'savings-records', needs: [CASHBOOK_READ], optional: true, title: 'Savings records', body: 'Choose the dates to list what was set aside. If one was a mistake, tap Void and give the reason.' }),
  ],
};

export const branchRolesPage: Tour = {
  id: 'page-branch-roles',
  version: 1,
  title: 'This page: branch roles',
  audience: (me) => held(me, BRANCH_ROLE) && held(me, STOCK_READ) && capable(me, 'branch_roles'),
  autoStart: false,
  page: '/staff/retail/branch-roles',
  steps: [
    step({ id: 'help', target: 'branch-roles-help', title: 'Stores and shops', body: 'A store keeps stock and feeds the shop. Only a shop sells to customers. A branch can be both.' }),
    step({ id: 'roles', target: 'retail-roles', title: 'Pick a role for each branch', body: 'Choose Store, Shop or Both under the branch name. It saves at once. Tap Stock to see what each branch holds.' }),
  ],
};

export const customerGroupsPage: Tour = {
  id: 'page-customer-groups',
  version: 1,
  title: 'This page: customer groups',
  audience: (me) => held(me, PRICELIST) && capable(me, 'price_lists'),
  autoStart: false,
  page: '/staff/retail/customer-groups',
  steps: [
    step({ id: 'help', target: 'customer-groups-help', title: 'Why groups', body: 'Customers in one group share a price list. A customer\'s own price list wins over the group\'s.' }),
    step({ id: 'add', target: 'customer-groups-add', title: 'Add a group', body: 'Tap here, type a name and pick a price list. You can pick none, and then the retail price applies.' }),
    step({ id: 'list', target: 'customer-groups-list', title: 'Change a group', body: 'Tap Edit to rename it or pick another price list. Tap Credit buyers to put a buyer in a group.' }),
  ],
};

export const customerAgeingPage: Tour = {
  id: 'page-customer-ageing',
  version: 1,
  title: 'This page: customer ageing',
  audience: (me) => held(me, CREDIT_MANAGE) && held(me, SALE_READ) && capable(me, 'customer_accounts'),
  autoStart: false,
  page: '/staff/retail/customer-ageing',
  steps: [
    step({ id: 'help', target: 'ageing-help', title: 'Who owes and how late', body: 'Every credit buyer who owes money, by how many days late. Tap Credit sales to see the sales behind it.' }),
    step({ id: 'top', target: 'ageing-top', title: 'How many rows', body: 'Choose Top 10, 20, 50 or 100 buyers, the ones who owe the most first.' }),
    step({ id: 'search', target: 'ageing-search', title: 'Find a buyer', body: 'Type a name to narrow the rows already on the page.' }),
    step({ id: 'report', target: 'ageing-report', title: 'Read the table', body: 'The total is at the top. Tap a buyer\'s name to open their account and take a payment.' }),
  ],
};

export const RETAIL_CASH_TOURS: readonly Tour[] = [
  productsPage, sizesNewPage, sizesJoinPage, sizesPage, categoriesPage, unitsPage, suppliersPage, buyersPage, importPage,
  bankingPage, bankingReportPage, expensesPage, expensesReportPage, expenseListsPage, withdrawalsPage, advancesPage, cashSummaryPage, savingsPage,
  branchRolesPage, customerGroupsPage, customerAgeingPage,
];
