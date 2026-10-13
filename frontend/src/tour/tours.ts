import type { Me } from '../api/client';
import type { Tour, TourStep } from './model';

// The tours themselves (issue #19). Copy rules: plain English, short sentences, no jargon, say
// what to tap. Every target is a data-tour attribute on a screen; tours.test.tsx renders each
// screen and fails if a target is missing, so renaming a screen means updating its tour here.
// A step for a feature that is not built yet carries `feature` and stays hidden until the slice
// that builds it switches its flag on in model.ts and bumps the tour version.

const SETTINGS = 'core.settings.manage';
const SELL = 'retail.sale.create';
const RESTOCK = 'retail.purchase.create';
const STOCKTAKE = ['retail.stocktake.commit', 'retail.stock.read'];
const CATALOGUE = ['retail.catalogue.manage', 'retail.customer.manage'];
const LOANS = 'lending.loans.read';
const DISBURSE = 'lending.disbursements.request';
const REPAY = 'lending.repayments.create';

const held = (me: Me, permission: string) => (me.permissions ?? []).includes(permission);
const isTenantAdmin = (me: Me) => held(me, SETTINGS);
const isSeller = (me: Me) => held(me, SELL) && !isTenantAdmin(me);
/** A retail capability the tenant switched on (ADR-033); /me lists them per module. */
const capable = (me: Me, capability: string) => (me.module_capabilities?.['retail'] ?? []).includes(capability);

const help: TourStep = {
  id: 'help',
  target: 'help-menu',
  title: 'Help is always here',
  body: 'Lost? Tap Help to take this tour again, or to see a short tour of the page you are on.',
};

const insights: TourStep = {
  id: 'insights',
  target: 'nav-insights',
  module: 'lending',
  needs: ['lending.insights.read'],
  optional: true,
  title: 'How the business is doing',
  body: 'See your loans, repayments and members over time, and what changed since yesterday.',
};

const loans: TourStep = {
  id: 'loans',
  target: 'nav-lending',
  module: 'lending',
  needs: [LOANS],
  optional: true,
  title: 'Loans',
  body: 'Find any loan here, from the application to the last payment.',
};

const loanSearch: TourStep = {
  id: 'loan-search',
  route: '/staff/lending',
  target: 'loan-search',
  module: 'lending',
  needs: [LOANS],
  title: 'Find a loan',
  body: 'Type the loan number, the member number or a name, then tap Search.',
};

const loanApplications: TourStep = {
  id: 'loan-applications',
  route: '/staff/lending',
  target: 'loan-status',
  module: 'lending',
  needs: [LOANS],
  title: 'Applications',
  body: 'Pick Submitted or Appraised to see applications waiting for a decision. Pick Approved to see loans ready to pay out.',
};

const branch: TourStep = {
  id: 'branch',
  target: 'branch-picker',
  title: 'Your branch',
  body: 'Pick the branch you are working in. What you see and record follows this choice.',
};

// A tenant admin, invited by a colleague or activated from the public sign-up (ADR-024), lands on
// the set-up checklist after the first run, and this tour starts there.
export const adminWelcome: Tour = {
  id: 'admin-welcome',
  version: 3,
  title: 'Welcome tour',
  audience: isTenantAdmin,
  autoStart: true,
  steps: [
    {
      id: 'welcome',
      title: 'Welcome to your business account',
      body: 'This short tour shows you where things are. It takes about two minutes. You can stop at any time.',
    },
    {
      id: 'checklist',
      route: '/staff/setup',
      target: 'setup-checklist',
      optional: true,
      title: 'Your set-up checklist',
      body: 'This list shows what is left to set up. Work through it at your own pace.',
    },
    {
      id: 'name',
      route: '/staff/setup',
      target: 'setup-name',
      title: 'Your business name',
      body: 'Check the name your customers know you by. It shows on every page and receipt.',
    },
    {
      id: 'logo',
      route: '/staff/setup',
      target: 'setup-logo',
      title: 'Add your logo',
      body: 'Upload your logo here. You and your staff will see it at the top of every page.',
    },
    {
      id: 'colour',
      route: '/staff/setup',
      target: 'setup-colour',
      title: 'Pick your colour',
      body: 'Choose the colour of your business. We check that text stays easy to read on it.',
    },
    {
      id: 'staff',
      route: '/staff/users',
      target: 'staff-invite',
      feature: 'staffAdmin',
      title: 'Add your staff',
      body: 'Invite each person by email or phone. Give them a role, like Sales or Cashier. They only see what their role allows.',
    },
    {
      id: 'modules',
      target: 'nav-areas',
      title: 'Your modules',
      body: 'Each part of the business you use has a tab here, like Retail or Lending. To add or remove one, contact us.',
    },
    {
      id: 'billing',
      route: '/staff/billing',
      target: 'billing-status',
      feature: 'billing',
      title: 'Your plan',
      body: 'See your plan, your modules and when the next payment is due.',
    },
    {
      id: 'trial',
      route: '/staff/billing',
      target: 'billing-trial',
      feature: 'trial',
      title: 'Your free month',
      body: 'See how many free days are left. Your data stays safe when the month ends.',
    },
    {
      id: 'pay',
      route: '/staff/billing',
      target: 'billing-paid',
      feature: 'payments',
      title: 'Tell us you have paid',
      body: 'Pay by mobile money or bank. Then tap I have paid and enter the reference.',
    },
    {
      id: 'agent',
      route: '/staff/billing',
      target: 'billing-agent',
      feature: 'agents',
      title: 'Your agent',
      body: 'If an agent helped you join, you can see who to call here.',
    },
    { ...branch, route: '/staff' },
    {
      id: 'retail',
      route: '/staff/retail',
      target: 'retail-tiles',
      module: 'retail',
      title: 'Your shop each day',
      body: 'Record sales, check stock and restock from here. One big button for each job.',
    },
    {
      id: 'catalogue',
      route: '/staff/retail',
      target: 'retail-catalogue',
      module: 'retail',
      needsAny: CATALOGUE,
      optional: true,
      title: 'Your items and prices',
      body: 'Add items, change prices, and keep your suppliers and credit buyers here.',
    },
    {
      id: 'lending',
      route: '/staff',
      target: 'lending-members',
      module: 'lending',
      needs: ['lending.members.read'],
      title: 'Your members',
      body: 'Your members are listed here, for the branch you picked.',
    },
    loans,
    {
      id: 'approvals',
      target: 'nav-approvals',
      needs: ['core.approvals.read'],
      optional: true,
      title: 'Approvals',
      body: 'Some actions need a second person to agree. They wait for you here.',
    },
    insights,
    help,
  ],
};

export const retailSales: Tour = {
  id: 'retail-sales',
  version: 2,
  title: 'Selling tour',
  audience: isSeller,
  autoStart: true,
  steps: [
    {
      id: 'welcome',
      title: 'Welcome',
      body: 'Here is how to sell, check stock and restock. It takes about a minute.',
    },
    { ...branch, optional: true, body: 'Sales and stock are counted per branch. Check that this shows the shop you are in.' },
    {
      id: 'sale',
      route: '/staff/retail',
      target: 'retail-sale',
      module: 'retail',
      title: 'Record a sale',
      body: 'Tap here each time you sell. Add the items, choose how the customer pays, and save.',
    },
    {
      id: 'sale-search',
      route: '/staff/retail/sale',
      target: 'sale-search',
      module: 'retail',
      optional: true,
      title: 'Find the item',
      body: 'Type part of the name or code, then tap Add.',
    },
    {
      id: 'sale-payment',
      route: '/staff/retail/sale',
      target: 'sale-payment',
      module: 'retail',
      optional: true,
      title: 'How the customer pays',
      body: 'Choose how the customer pays. For credit, pick the buyer and the day they will pay.',
    },
    {
      id: 'sale-save',
      route: '/staff/retail/sale',
      target: 'sale-save',
      module: 'retail',
      optional: true,
      title: 'Save the sale',
      body: 'Check the total, then tap Save sale. You get a receipt to show the customer.',
    },
    {
      id: 'stock',
      route: '/staff/retail',
      target: 'retail-stock',
      module: 'retail',
      needs: ['retail.stock.read'],
      title: 'Check stock',
      body: 'See how many of each item your branch has.',
    },
    {
      id: 'stock-search',
      route: '/staff/retail/stock',
      target: 'stock-search',
      module: 'retail',
      needs: ['retail.stock.read'],
      optional: true,
      title: 'Search the stock',
      body: 'Type a name or code. An item marked negative needs a check, so tell your manager.',
    },
    {
      id: 'restock',
      route: '/staff/retail',
      target: 'retail-restock',
      module: 'retail',
      needs: [RESTOCK],
      title: 'Restock',
      body: 'When new stock comes from a supplier, record it here. The counts stay right.',
    },
    {
      id: 'restock-ask',
      route: '/staff/retail/stock',
      target: 'stock-search',
      module: 'retail',
      unless: [RESTOCK],
      optional: true,
      title: 'Running low?',
      body: 'Tell your manager when an item runs low. They record new stock when it arrives.',
    },
    {
      id: 'stocktake',
      route: '/staff/retail',
      target: 'retail-stocktake',
      module: 'retail',
      needs: STOCKTAKE,
      optional: true,
      title: 'Count the stock',
      body: 'When you count the shelf, enter the numbers here. Your manager sees any difference.',
    },
    {
      id: 'shortcuts',
      route: '/staff/retail',
      target: 'retail-nav',
      module: 'retail',
      optional: true,
      title: 'Shortcuts',
      body: 'On a phone, your most used pages are always at the bottom of the screen.',
    },
    help,
  ],
};

export const staffWelcome: Tour = {
  id: 'staff-welcome',
  version: 2,
  title: 'Welcome tour',
  audience: (me) => !isTenantAdmin(me) && !isSeller(me),
  autoStart: true,
  steps: [
    {
      id: 'welcome',
      title: 'Welcome',
      body: 'This short tour shows you the pages you can use. You can stop at any time.',
    },
    { ...branch, optional: true },
    {
      id: 'members',
      route: '/staff',
      target: 'lending-members',
      module: 'lending',
      needs: ['lending.members.read'],
      title: 'Members',
      body: 'Your members are listed here, for the branch you picked.',
    },
    loans,
    loanSearch,
    loanApplications,
    {
      id: 'approvals',
      target: 'nav-approvals',
      needs: ['core.approvals.read'],
      optional: true,
      title: 'Approvals',
      body: 'Some actions need a second person to agree. They wait for you here.',
    },
    {
      id: 'retail',
      target: 'nav-retail',
      module: 'retail',
      optional: true,
      title: 'Retail',
      body: 'Sales and stock live here.',
    },
    help,
  ],
};

export const salePage: Tour = {
  id: 'page-sale',
  version: 1,
  title: 'This page: record a sale',
  audience: (me) => held(me, SELL),
  autoStart: false,
  page: '/staff/retail/sale',
  steps: retailSales.steps.filter((s) => s.route === '/staff/retail/sale'),
};

export const stockPage: Tour = {
  id: 'page-stock',
  version: 1,
  title: 'This page: stock',
  audience: (me) => held(me, 'retail.stock.read'),
  autoStart: false,
  page: '/staff/retail/stock',
  steps: retailSales.steps.filter((s) => s.id === 'stock-search' || s.id === 'restock-ask'),
};

export const setupPage: Tour = {
  id: 'page-setup',
  version: 1,
  title: 'This page: business set-up',
  audience: isTenantAdmin,
  autoStart: false,
  page: '/staff/setup',
  steps: [
    ...adminWelcome.steps.filter((s) => s.route === '/staff/setup'),
    {
      id: 'save',
      target: 'setup-save',
      title: 'Save',
      body: 'Tap Save when you are happy. The logo saves as soon as you upload it.',
    },
  ],
};

export const loansPage: Tour = {
  id: 'page-loans',
  version: 1,
  title: 'This page: loans',
  audience: (me) => held(me, LOANS),
  autoStart: false,
  page: '/staff/lending',
  steps: [
    { ...loanSearch, route: undefined },
    { ...loanApplications, route: undefined },
  ],
};

export const loanPage: Tour = {
  id: 'page-loan',
  version: 1,
  title: 'This page: one loan',
  audience: (me) => held(me, LOANS),
  autoStart: false,
  page: '/staff/lending/loans/$loanId',
  steps: [
    {
      id: 'actions',
      target: 'loan-actions',
      optional: true,
      title: 'What you can do',
      body: 'The buttons here change with the loan. You only see what your role allows.',
    },
    {
      id: 'disburse',
      target: 'loan-disburse',
      needs: [DISBURSE],
      optional: true,
      title: 'Pay out the loan',
      body: 'An approved loan is paid out here. A second person agrees before the money moves.',
    },
    {
      id: 'repay',
      target: 'loan-repay',
      needs: [REPAY],
      optional: true,
      title: 'Record a payment',
      body: 'Enter what the member paid and how. You get a receipt number to give them.',
    },
    {
      id: 'balances',
      target: 'loan-balances',
      optional: true,
      title: 'What is owed',
      body: 'See what is still owed, what is late and when the next payment is due.',
    },
    {
      id: 'schedule',
      target: 'loan-schedule',
      title: 'The schedule',
      body: 'Each payment the member should make, and what has been paid so far.',
    },
    {
      id: 'transactions',
      target: 'loan-transactions',
      title: 'Money in and out',
      body: 'Every payout and payment on this loan, with its receipt number.',
    },
  ],
};

export const cataloguePage: Tour = {
  id: 'page-catalogue',
  version: 1,
  title: 'This page: catalogue',
  audience: (me) => CATALOGUE.some((p) => held(me, p)),
  autoStart: false,
  page: '/staff/retail/catalogue',
  steps: [
    {
      id: 'links',
      target: 'catalogue-links',
      title: 'Your lists',
      body: 'Each list the shop keeps has a button here. You see the ones your role allows.',
    },
    {
      id: 'products',
      target: 'catalogue-products',
      optional: true,
      title: 'Items and prices',
      body: 'Add a new item or change a price here. Old prices are kept.',
    },
    {
      id: 'importer',
      target: 'catalogue-importer',
      needs: [SETTINGS, 'retail.catalogue.manage'],
      optional: true,
      title: 'Many items at once',
      body: 'Have a long list in a spreadsheet? Import it here and check it before saving.',
    },
  ],
};

export const stocktakePage: Tour = {
  id: 'page-stocktake',
  version: 1,
  title: 'This page: stock-take',
  audience: (me) => STOCKTAKE.every((p) => held(me, p)),
  autoStart: false,
  page: '/staff/retail/stocktake',
  steps: [
    {
      id: 'count',
      target: 'count-search',
      title: 'Count each item',
      body: 'Find an item and type how many you counted. Leave an item blank to skip it.',
    },
    {
      id: 'review',
      target: 'count-review',
      title: 'Check before saving',
      body: 'Tap Review to see what differs. Nothing changes until you commit the count.',
    },
  ],
};

export const retailSettingsPage: Tour = {
  id: 'page-retail-settings',
  version: 1,
  title: 'This page: retail settings',
  audience: isTenantAdmin,
  autoStart: false,
  page: '/staff/retail/settings',
  steps: [
    {
      id: 'profile',
      target: 'retail-profile',
      title: 'Shop or trade',
      body: 'Pick Shop for a plain shop. Pick Trade if stores feed a shop and you sell in bulk. It switches on a set of features for you.',
    },
    {
      id: 'features',
      target: 'retail-capabilities',
      title: 'Each feature',
      body: 'Switch any feature on or off yourself. Switching one off hides it. What you recorded with it is kept.',
    },
    {
      id: 'review',
      target: 'retail-settings-save',
      title: 'Check, then save',
      body: 'Tap Review changes to read what will change. Nothing is saved until you tap Confirm.',
    },
  ],
};

// A tour per capability (ADR-033): it starts once, the first time a user it is for signs in with the
// capability on. A capability whose screens are not released has none yet; the slice that releases its
// screens adds the tour here and sets `ready` in capabilities-state.ts (tours.test.tsx checks both).
export const branchRolesCapability: Tour = {
  id: 'capability-branch-roles',
  version: 1,
  title: 'New: branch roles',
  audience: (me) => held(me, 'retail.branch_role.manage'),
  autoStart: true,
  capability: { module: 'retail', key: 'branch_roles' },
  steps: [
    {
      id: 'intro',
      title: 'Branch roles are on',
      body: 'Each branch can now be a store, a shop or both. Stores keep stock and feed the shop. Only shops sell to customers.',
    },
    {
      id: 'tile',
      route: '/staff/retail',
      target: 'retail-branchRoles',
      module: 'retail',
      optional: true,
      title: 'Set the roles',
      body: 'Open Branch roles to say which branches are stores and which are shops.',
    },
    {
      id: 'roles',
      route: '/staff/retail/branch-roles',
      target: 'retail-roles',
      module: 'retail',
      title: 'Pick a role for each branch',
      body: 'Until you pick, every branch works as before. A store cannot record a sale.',
    },
  ],
};

// The page tours of the trade screens (ADR-033, issue #193): Help only, for a user who may use the page
// on a tenant that switched its capability on.
export const stockByLocationPage: Tour = {
  id: 'page-stock-by-location',
  version: 1,
  title: 'This page: stores and shops',
  audience: (me) => held(me, 'retail.stock.read') && capable(me, 'branch_roles'),
  autoStart: false,
  page: '/staff/retail/stock-by-location',
  steps: [
    {
      id: 'search',
      target: 'location-search',
      module: 'retail',
      title: 'Find an item',
      body: 'Type a name or code to see that item in every store and shop you can read.',
    },
    {
      id: 'report',
      target: 'location-report',
      module: 'retail',
      title: 'Stores, shops and the total',
      body: 'Stores come first, then shops. Stock that was sent and not yet received has its own column, so the combined total counts it once.',
    },
  ],
};

export const inTransitPage: Tour = {
  id: 'page-in-transit',
  version: 1,
  title: 'This page: stock on the way',
  audience: (me) => held(me, 'retail.stock.read') && capable(me, 'transfer_receipt'),
  autoStart: false,
  page: '/staff/retail/in-transit',
  steps: [
    {
      id: 'help',
      target: 'transit-help',
      module: 'retail',
      title: 'Stock on the way',
      body: 'When one branch sends stock to another, it waits here until the other branch receives it.',
    },
    {
      id: 'list',
      target: 'transit-list',
      module: 'retail',
      title: 'Receive or take back',
      body: 'At the branch it was sent to, check what arrived and tap Mark as received. At the branch that sent it, you can take it back until then.',
    },
  ],
};

// The tour of the receive-transfers capability: it starts once for whoever moves stock, when first enabled.
export const transferReceiptCapability: Tour = {
  id: 'capability-transfer-receipt',
  version: 1,
  title: 'New: receive transfers',
  audience: (me) => held(me, 'retail.stock.transfer'),
  autoStart: true,
  capability: { module: 'retail', key: 'transfer_receipt' },
  steps: [
    {
      id: 'intro',
      title: 'Stock moves are now sent, then received',
      body: 'When you send stock to another branch it leaves yours at once. The other branch counts it when they receive it.',
    },
    {
      id: 'tile',
      route: '/staff/retail',
      target: 'retail-inTransit',
      module: 'retail',
      optional: true,
      title: 'Stock on the way',
      body: 'Open On the way to receive stock that was sent to you, or to take back stock you sent.',
    },
    {
      id: 'list',
      route: '/staff/retail/in-transit',
      target: 'transit-list',
      module: 'retail',
      title: 'Check what arrived',
      body: 'Type how many of each item arrived. If less came than was sent, the difference is written off as a loss.',
    },
  ],
};

/** Every tour, in the order the first matching auto tour is chosen. */
// The tour of the pack sizes capability (units of measure, issue #194): it starts once for whoever manages the
// catalogue, when first enabled. Pack sizes are set on each item and chosen on the sale and restock forms.
export const unitsCapability: Tour = {
  id: 'capability-units',
  version: 1,
  title: 'New: pack sizes',
  audience: (me) => held(me, 'retail.catalogue.manage'),
  autoStart: true,
  capability: { module: 'retail', key: 'units' },
  steps: [
    {
      id: 'intro',
      title: 'Pack sizes are on',
      body: 'An item can now have packs, like a carton of 12. Stock is still counted in single items; a sale or a restock can be entered by the pack.',
    },
    {
      id: 'tile',
      route: '/staff/retail',
      target: 'retail-catalogue',
      module: 'retail',
      optional: true,
      title: 'Add the packs',
      body: 'Open Catalogue, then Items. Choose Edit on an item and add its pack sizes. Then the sale and restock screens offer them.',
    },
  ],
};

// The tour of the price lists capability (issue #195): it starts once for whoever manages price lists, when first
// enabled, and the page tour is Help only. Lists are made on their page; buyers are put in a group on Credit buyers.
export const priceListsCapability: Tour = {
  id: 'capability-price-lists',
  version: 1,
  title: 'New: price lists',
  audience: (me) => held(me, 'retail.pricelist.manage'),
  autoStart: true,
  capability: { module: 'retail', key: 'price_lists' },
  steps: [
    {
      id: 'intro',
      title: 'Price lists are on',
      body: 'You can now give some customers their own prices, with a lower price for larger quantities. An item that is not in a list is sold at its retail price.',
    },
    {
      id: 'tile',
      route: '/staff/retail',
      target: 'retail-priceLists',
      module: 'retail',
      optional: true,
      title: 'Make a list',
      body: 'Open Price lists, add a list, then add the price of each item. Put customers in a group, or give a customer a list of their own.',
    },
    {
      id: 'lists',
      route: '/staff/retail/price-lists',
      target: 'price-list-lists',
      module: 'retail',
      title: 'Your lists',
      body: 'Tap Prices on a list to add prices, from a quantity and for a date range. The seller sees which list priced each item on the sale.',
    },
  ],
};

export const priceListsPage: Tour = {
  id: 'page-price-lists',
  version: 1,
  title: 'This page: price lists',
  audience: (me) => held(me, 'retail.pricelist.manage') && capable(me, 'price_lists'),
  autoStart: false,
  page: '/staff/retail/price-lists',
  steps: [
    {
      id: 'help',
      target: 'price-list-help',
      module: 'retail',
      title: 'How a price is chosen',
      body: 'The customer\'s own list first, then their group\'s list. In the list, the largest quantity break that fits the line. Otherwise the retail price.',
    },
    {
      id: 'lists',
      target: 'price-list-lists',
      module: 'retail',
      title: 'Prices are added, not changed',
      body: 'A new price takes over from the old one on its first day. Earlier sales keep the price they were made at.',
    },
  ],
};

// The tour of the customer accounts capability (issue #196): it starts once for whoever sets credit limits, when first
// enabled. A seller meets the same feature on Credit buyers (a limit refuses a credit sale), which the intro says.
export const customerAccountsCapability: Tour = {
  id: 'capability-customer-accounts',
  version: 1,
  title: 'New: customer accounts',
  audience: (me) => held(me, 'retail.credit.manage'),
  autoStart: true,
  capability: { module: 'retail', key: 'customer_accounts' },
  steps: [
    {
      id: 'intro',
      title: 'Customer accounts are on',
      body: 'Each credit buyer can now have a credit limit and payment terms. A credit sale needs a buyer from the list. It is refused above their limit, unless someone you allow gives a reason.',
    },
    {
      id: 'tile',
      route: '/staff/retail',
      target: 'retail-customerAccounts',
      module: 'retail',
      optional: true,
      title: 'Open an account',
      body: 'Open Customer accounts to set a buyer\'s limit and terms. You can also take a payment that is not for one sale, and read their statement.',
    },
    {
      id: 'list',
      route: '/staff/retail/accounts',
      target: 'accounts-list',
      module: 'retail',
      title: 'Your credit buyers',
      body: 'Each buyer shows what they owe, their limit and their terms. Tap Open account to work on one. Sellers reach this page from Credit buyers.',
    },
  ],
};

export const accountsPage: Tour = {
  id: 'page-accounts',
  version: 1,
  title: 'This page: customer accounts',
  audience: (me) => held(me, 'retail.credit.manage') && capable(me, 'customer_accounts'),
  autoStart: false,
  page: '/staff/retail/accounts',
  steps: [
    {
      id: 'help',
      target: 'accounts-help',
      module: 'retail',
      title: 'What an account holds',
      body: 'The limit is the most a buyer may owe over all your branches. The terms set the date a credit sale falls due. A payment goes to the oldest sales first, unless you choose.',
    },
    {
      id: 'list',
      target: 'accounts-list',
      module: 'retail',
      title: 'Find a buyer',
      body: 'Type a name or a phone number to find a buyer. Tap Open account to see their payments and statement.',
    },
  ],
};

// The tour of the returns capability (issue #201): it starts once for whoever may take goods back, when first enabled.
export const returnsCapability: Tour = {
  id: 'capability-returns',
  version: 1,
  title: 'New: customer returns',
  audience: (me) => held(me, 'retail.sale.return'),
  autoStart: true,
  capability: { module: 'retail', key: 'returns' },
  steps: [
    {
      id: 'intro',
      title: 'Customer returns are on',
      body: 'You can take goods back against a sale, all of it or part. Each return is a numbered credit note. The customer is paid back, or the value comes off what they owe.',
    },
    {
      id: 'tile',
      route: '/staff/retail',
      target: 'retail-returns',
      module: 'retail',
      optional: true,
      title: 'Your credit notes',
      body: 'Open Customer returns to see every credit note. To take goods back, open the sale in All sales and choose Return goods.',
    },
    {
      id: 'list',
      route: '/staff/retail/returns',
      target: 'returns-list',
      module: 'retail',
      title: 'Find a credit note',
      body: 'Type a credit note number, a sale number or a buyer\'s name. Open one to see the goods, where they went, and to void it if it was a mistake.',
    },
  ],
};

export const returnsPage: Tour = {
  id: 'page-returns',
  version: 1,
  title: 'This page: customer returns',
  audience: (me) => held(me, 'retail.sale.return') && capable(me, 'returns'),
  autoStart: false,
  page: '/staff/retail/returns',
  steps: [
    {
      id: 'help',
      target: 'returns-help',
      module: 'retail',
      title: 'How a return starts',
      body: 'A return is made from a sale. Open the sale, choose Return goods, say how many came back and what happens to them. Damaged goods are written off and never go back into stock.',
    },
    {
      id: 'list',
      target: 'returns-list',
      module: 'retail',
      title: 'The credit notes',
      body: 'Each credit note shows its number, the sale, what it came to and how it was paid back. Open one to see its lines.',
    },
  ],
};

export const TOURS: readonly Tour[] = [
  adminWelcome, retailSales, staffWelcome, salePage, stockPage, setupPage, loansPage, loanPage, cataloguePage, stocktakePage,
  retailSettingsPage, branchRolesCapability, stockByLocationPage, inTransitPage, transferReceiptCapability, unitsCapability,
  priceListsCapability, priceListsPage, customerAccountsCapability, accountsPage, returnsCapability, returnsPage,
];
