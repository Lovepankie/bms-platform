import type { Me } from '../api/client';
import type { Tour, TourStep } from './model';
import { CUSTOMER_TOURS } from './tours-customers';
import { LENDING_MORE_TOURS } from './tours-lending-more';
import { OPERATOR_TOURS } from './tours-operator';
import { PUBLIC_TOURS } from './tours-public';
import { RETAIL_CASH_TOURS } from './tours-retail-cash';
import { RETAIL_ORDER_TOURS } from './tours-retail-orders';
import { RETAIL_PURCHASING_TOURS } from './tours-retail-purchasing';
import { RETAIL_REPORT_TOURS } from './tours-retail-reports';

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

// The refresh button in the header (issue #430): the installed app has no browser reload.
const refresh: TourStep = {
  id: 'refresh',
  target: 'refresh',
  optional: true,
  title: 'Refresh the page',
  body: 'The installed app has no reload button, so tap Refresh at the top to get the latest figures. If it cannot refresh, or you are offline, it says so and keeps the figures you already have. On a wider screen it also says when the page was last updated.',
};

// The Notifications and app page (ADR-050): install BMS as an app and turn on notifications on this device. Everyone signed in has it.
const notifications: TourStep = {
  id: 'notifications',
  target: 'nav-notifications',
  title: 'Install the app and get notifications',
  body: 'Tap Notifications to install BMS on your phone. There you can also turn on a notice for when something needs you, such as an approval.',
};

// Feedback (issue #447): every member of staff can tell the BMS team an idea, a problem or a question.
const feedback: TourStep = {
  id: 'feedback',
  target: 'nav-feedback',
  title: 'Tell us what you think',
  body: 'Tap Feedback to send the BMS team an idea, a problem or a question. Add a picture of the screen if it helps. You see our reply there too. On a phone it is also under Help.',
};

// The theme button in the header (issues #429 and #452): Light, Dark or Same as my phone, kept on this device. Every area has it.
const theme: TourStep = {
  id: 'theme',
  target: 'theme-select',
  title: 'Light or dark',
  body: 'Tap the sun, moon or phone at the top right to choose Light, Dark or Same as my phone. Your choice is kept on this device.',
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
  // Version 5 adds the credit payment and void steps (issues #326, #327), so whoever finished version 4 sees them once.
  // Version 6 words the payment step for the grouped lists (issue #328): the sales sit under a day, or in Still owing.
  // Version 7 adds the Group by step (issue #341): the sales lists open from the shop down to the items.
  // Version 8 adds the Branches step (issue #329): closing a branch and keeping its history.
  // Version 9 adds the Cash and change step (issue #346): the amount received and the change to give on a cash sale.
  // Version 10 adds the two correction steps (issue #351): a wrong quantity on the stock list, and a restock entered wrongly.
  // Version 11 adds the step on when a credit sale counts as profit (issue #340, ADR-043).
  // Version 12 adds the step on writing off a debt that will never be paid (issue #340).
  // Version 13 adds the Controls step (issue #357): sales cancelled, goods taken back, prices changed, restocks cancelled and quantities corrected, by shop and person.
  // Version 14 adds the Two-step sign-in step (issue #348): who must use a code from the phone, and how long they have to set it up.
  // Version 15 adds the Notifications step: install the app and turn on notifications (ADR-050).
  // Version 16 adds the Light or dark step (issue #429): the theme selector at the top right.
  // Version 17 adds the Refresh step (issue #430): the button at the top that gets the latest figures.
  // Version 18 adds the Feedback step (issue #447): send the BMS team an idea, a problem or a question.
  version: 18,
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
      body: 'Upload your logo here. You and your staff will see it at the top of every page and as the app icon.',
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
      needs: ['core.users.manage'],
      title: 'Add your staff',
      body: 'Tap Invite someone. Type the name and the phone, pick a role like Sales person or Cashier, and send them the link. They only see what their role allows.',
    },
    {
      id: 'branches',
      route: '/staff/setup/branches',
      target: 'branches-list',
      needs: ['core.branches.read'],
      optional: true,
      title: 'Your branches',
      body: 'Close a shop you no longer use. Its sales and stock records stay for your reports, and it leaves the branch lists. You can reopen it later.',
    },
    {
      id: 'two-step',
      route: '/staff/setup/two-step',
      target: 'twostep-choice',
      title: 'Two-step sign-in',
      body: 'Ask your staff for a code from their phone as well as the password. It is optional until you choose who must use it.',
    },
    {
      id: 'modules',
      target: 'nav-areas',
      title: 'Your modules',
      body: 'Each part of the business you use has its own item in the menu, like Retail or Lending. On a phone, open the menu at the top left. To add or remove one, contact us.',
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
      id: 'sale-change',
      route: '/staff/retail/sale',
      target: 'sale-change',
      module: 'retail',
      needs: [SELL],
      optional: true,
      title: 'Cash and change',
      body: 'For a cash sale, type the amount the customer gave, or tap a note. The change to give back shows in large figures. It is only a guide, and the sale is still saved at its total.',
    },
    {
      id: 'credit-payments',
      route: '/staff/retail',
      target: 'retail-creditSales',
      module: 'retail',
      needs: ['retail.sale.read', SELL],
      optional: true,
      title: 'When a buyer pays what they owe',
      body: 'Open Credit sales and pick Still owing to see every debt, or open the day of the sale. Then tap Record payment. A part payment is fine. If they pay it all, tap Mark as fully paid.',
    },
    {
      id: 'credit-counting',
      route: '/staff/retail',
      target: 'retail-tiles',
      module: 'retail',
      needs: ['retail.profit.read'],
      optional: true,
      title: 'When a credit sale counts as profit',
      body: 'A credit sale can wait to count until the buyer pays. Then the home page shows Sold, Received and On credit apart. The daily savings use only the profit of money received. You choose this in Retail settings, and it starts the next day and is not switched off again.',
    },
    {
      id: 'write-off',
      route: '/staff/retail',
      target: 'retail-creditSales',
      module: 'retail',
      needs: ['retail.sale.read', 'retail.credit.write_off'],
      optional: true,
      title: 'A buyer who will never pay',
      body: 'Open the credit sale and tap Write off. Give the reason. The debt becomes a loss on that day. The sale stays on record and reads Written off.',
    },
    {
      id: 'sales-grouping',
      route: '/staff/retail',
      target: 'retail-salesHistory',
      module: 'retail',
      needs: ['retail.sale.read'],
      optional: true,
      title: 'Your sales, your way',
      body: 'Open All sales. Tap Group by to choose the order: shop, year, month, salesperson, day, category and item. Tap a line to open it. Every line shows its total.',
    },
    {
      id: 'void-sale',
      route: '/staff/retail',
      target: 'retail-salesHistory',
      module: 'retail',
      needs: ['retail.sale.read', 'retail.sale.void'],
      optional: true,
      title: 'Fix a sale made by mistake',
      body: 'Open All sales, open the sale and tap Void this sale. Give the reason. The items go back into stock and the sale stops counting.',
    },
    {
      id: 'correct-stock',
      route: '/staff/retail/stock',
      target: 'stock-list',
      module: 'retail',
      needs: STOCKTAKE,
      optional: true,
      title: 'Fix a wrong quantity',
      body: 'On Stock, tap Correct on an item. Give the right quantity and the reason: an entry mistake or a miscount. The old figure stays in its history and does not count as shrinkage. If you may change items, you can fix the category there too.',
    },
    {
      id: 'void-restock',
      route: '/staff/retail/restock',
      target: 'restock-past',
      module: 'retail',
      needs: [RESTOCK],
      optional: true,
      title: 'Fix a restock entered wrongly',
      body: 'Open Past restocks, tap Correct this restock and give the reason. The stock, the books and the prices go back. The restock stays in the list as Voided. Then enter it again with the right quantity.',
    },
    {
      id: 'controls',
      route: '/staff/retail',
      target: 'retail-controls',
      module: 'retail',
      needs: ['retail.controls.read'],
      optional: true,
      title: 'What deserves a second look',
      body: 'Open Controls to see sales cancelled, goods taken back, prices changed, restocks cancelled and quantities corrected, by shop and by person. Items about sales are shown per 100 sales, so a busy person and a quiet one can be compared fairly.',
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
    notifications,
    feedback,
    refresh,
    theme,
    help,
  ],
};

export const retailSales: Tour = {
  id: 'retail-sales',
  // Version 3 adds the credit payment step (issue #326), so whoever finished version 2 sees it once.
  // Version 4 words that step for the grouped lists (issue #328): the sales sit under a day, or in Still owing.
  // Version 5 adds the Group by step (issue #341): the sales lists open from the shop down to the items.
  // Version 6 adds the Cash and change step (issue #346): the amount received and the change to give on a cash sale.
  // Version 7 adds the Correct step on the stock list (issue #351): fix a wrong quantity or category where you see it.
  // Version 8 adds the Notifications step: install the app and turn on notifications (ADR-050).
  // Version 9 adds the Light or dark step (issue #429): the theme selector at the top right.
  // Version 10 words the Save step for Share receipt and WhatsApp customer (issue #439): send the receipt from your phone.
  // Version 11 adds the Refresh step (issue #430): the button at the top that gets the latest figures.
  // Version 12 adds the Feedback step (issue #447): send the BMS team an idea, a problem or a question.
  // Version 13 adds the Scan step (issue #345): a barcode scanner, a typed barcode or the phone camera adds the item.
  version: 13,
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
      id: 'sale-scan',
      route: '/staff/retail/sale',
      target: 'scan-bar',
      module: 'retail',
      optional: true,
      title: 'Scan the barcode',
      body: 'With a barcode scanner, just scan: the item is added, and scanning it again adds one more. You can also type the barcode and press Enter, or tap Scan to use the phone camera.',
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
      id: 'sale-change',
      route: '/staff/retail/sale',
      target: 'sale-change',
      module: 'retail',
      optional: true,
      title: 'Cash and change',
      body: 'For a cash sale, type the amount the customer gave, or tap a note. The change to give back shows in large figures. It is only a guide, and the sale is still saved at its total.',
    },
    {
      id: 'sale-save',
      route: '/staff/retail/sale',
      target: 'sale-save',
      module: 'retail',
      optional: true,
      title: 'Save the sale',
      body: 'Check the total, then tap Save sale. You get a receipt to show the customer. Tap Share receipt to send the PDF from your phone, on WhatsApp or any other app. WhatsApp customer sends the details to their number.',
    },
    {
      id: 'credit-payments',
      route: '/staff/retail',
      target: 'retail-creditSales',
      module: 'retail',
      needs: ['retail.sale.read'],
      optional: true,
      title: 'When a buyer pays what they owe',
      body: 'Open Credit sales and pick Still owing to see every debt, or open the day of the sale. Then tap Record payment. A part payment is fine. If they pay it all, tap Mark as fully paid.',
    },
    {
      id: 'sales-grouping',
      route: '/staff/retail',
      target: 'retail-salesHistory',
      module: 'retail',
      needs: ['retail.sale.read'],
      optional: true,
      title: 'Your sales, your way',
      body: 'Open All sales. Tap Group by to choose the order: shop, year, month, salesperson, day, category and item. Tap a line to open it. Every line shows its total.',
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
      id: 'correct-stock',
      route: '/staff/retail/stock',
      target: 'stock-list',
      module: 'retail',
      needs: STOCKTAKE,
      optional: true,
      title: 'Fix a mistake',
      body: 'Typed a wrong quantity? Tap Correct on the item. Give the right quantity and the reason. The old figure stays in its history.',
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
      body: 'Your most used retail pages are listed under Retail in the menu. On a phone, open the menu at the top left first.',
    },
    notifications,
    feedback,
    refresh,
    theme,
    help,
  ],
};

export const staffWelcome: Tour = {
  id: 'staff-welcome',
  // Version 3 adds the Notifications step: install the app and turn on notifications (ADR-050).
  // Version 4 adds the Light or dark step (issue #429): the theme selector at the top right.
  // Version 5 adds the Refresh step (issue #430): the button at the top that gets the latest figures.
  // Version 6 adds the Feedback step (issue #447): send the BMS team an idea, a problem or a question.
  version: 6,
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
    notifications,
    feedback,
    refresh,
    theme,
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

// The stock list and the restocks list grouped by a chain (issue #350): the switch to Grouped, which explains the Group by control.
const groupedViewStep = (what: string, levels: string): TourStep => ({
  id: 'grouped-view',
  target: 'list-view',
  module: 'retail',
  title: 'See it in groups',
  body: `Choose Grouped to see ${what}. Tap a line to open it. Under Group by, choose the order: ${levels}. Move a line up or down, or take it out. We remember your choice. Choose Flat list to go back.`,
});

export const stockPage: Tour = {
  id: 'page-stock',
  // Version 2 adds the Correct step (issue #351); version 3 the grouped view (issue #350).
  version: 3,
  title: 'This page: stock',
  audience: (me) => held(me, 'retail.stock.read'),
  autoStart: false,
  page: '/staff/retail/stock',
  steps: [
    ...retailSales.steps.filter((s) => s.id === 'stock-search' || s.id === 'restock-ask' || s.id === 'correct-stock'),
    groupedViewStep('your stock by shop and category, with its value', 'shop and product category'),
  ],
};

// JUSTIFICATION-A3: a page tour for the new Past restocks page (issue #351), of the same shape as the page tours around it.
export const restocksPage: Tour = {
  id: 'page-restocks',
  // Version 2 adds the grouped view (issue #350).
  version: 2,
  title: 'This page: past restocks',
  audience: (me) => held(me, RESTOCK),
  autoStart: false,
  page: '/staff/retail/restocks',
  steps: [
    { id: 'restocks-filters', target: 'restocks-filters', module: 'retail', title: 'Find the restock', body: 'Choose the first and last day, or show only the voided ones. Every restock is kept, even a voided one.' },
    { id: 'restocks-list', target: 'restocks-list', module: 'retail', optional: true, title: 'Correct this restock', body: 'Tap Correct this restock, give the reason, then enter it again with the right quantity or unit. If less than its quantity is left, use Correct on the Stock page instead.' },
    groupedViewStep('your restocks in groups, with how many in each', 'shop, year, month, supplier, day, category and item'),
  ],
};

// JUSTIFICATION-A3: two page tours of the same shape as stockPage, one per grouped list (issue #328); no existing tour covers these pages.
// The All sales and Credit sales lists grouped by a chain of levels (issues #328, #341).
const salesGroupsStep: TourStep = {
  id: 'groups',
  target: 'sales-groups',
  module: 'retail',
  title: 'Open a line',
  body: 'Tap a line to open it. Each line shows what was sold, how many sales, and how they were paid. Profit shows if you may see it. The way you came shows above the list; tap a place in it to go back. Tap a day to see its sales one by one.',
};

const groupByStep: TourStep = {
  id: 'group-by',
  target: 'sales-group-by',
  module: 'retail',
  title: 'Group by',
  body: 'Choose how the sales are grouped: shop, year, month, salesperson, day, category and item. Move a line up or down, or take it out. We remember your choice.',
};

export const salesPage: Tour = {
  id: 'page-sales',
  // Version 2 adds the Group by step (issue #341).
  version: 2,
  title: 'This page: all sales',
  audience: (me) => held(me, 'retail.sale.read'),
  autoStart: false,
  page: '/staff/retail/sales',
  steps: [
    { id: 'range', target: 'sales-range', module: 'retail', title: 'Pick the days', body: 'Choose the first and the last day to look at. The sales under it are grouped the way you choose below.' },
    groupByStep,
    salesGroupsStep,
  ],
};

export const creditSalesPage: Tour = {
  id: 'page-credit-sales',
  // Version 2 adds the Group by step (issue #341).
  version: 2,
  title: 'This page: credit sales',
  audience: (me) => held(me, 'retail.sale.read'),
  autoStart: false,
  page: '/staff/retail/credit-sales',
  steps: [
    { id: 'owing', target: 'credit-owing', module: 'retail', title: 'Who still owes', body: 'Choose Still owing, Overdue or Paid to narrow the list.' },
    groupByStep,
    salesGroupsStep,
  ],
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

// Version 2 adds the inactive items step (issue #342), so whoever finished version 1 sees it once.
export const cataloguePage: Tour = {
  id: 'page-catalogue',
  version: 2,
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
      id: 'inactive',
      target: 'catalogue-products',
      needs: ['retail.catalogue.manage'],
      optional: true,
      title: 'Items you stopped selling',
      body: 'Open an item and tick Inactive. It is never deleted: it leaves the sale, restock and move lists, and its history and reports stay. Show inactive items on the Stock page brings it back into view.',
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
  // Version 2 adds the credit sale step (issue #340, ADR-043).
  // Version 3 says in that step that the credit rule is one way: once it has started it is not switched off here.
  // Version 4 adds the slow movers step (issue #414).
  version: 4,
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
    {
      id: 'credit',
      target: 'retail-credit-recognition',
      title: 'When a credit sale counts',
      body: 'Choose whether a credit sale counts as income and profit when it is made, or only when the buyer pays. Your daily savings follow this choice. You read the change before you confirm it. Once "when the buyer pays" has started, it is not switched off here.',
    },
    {
      id: 'slow-movers',
      target: 'retail-slow-movers',
      title: 'Slow movers',
      body: 'Choose when an item counts as slow on Stock health: how many months of stock is too much. You can also count an item as slow when few sold in 90 days. Tap Save.',
    },
  ],
};

// A tour per capability (ADR-033): it starts once, the first time a user it is for signs in with the
// capability on. A capability whose screens are not released has none yet; the slice that releases its
// screens adds the tour here and sets `ready` in capabilities-state.ts (tours.test.tsx checks both).
export const assistantPage: Tour = {
  id: 'page-assistant',
  version: 1,
  title: 'This page: AI assistant',
  audience: (me) => held(me, 'lending.advisor.configure'),
  autoStart: false,
  page: '/staff/assistant',
  steps: [
    {
      id: 'switch',
      target: 'assistant-switch',
      title: 'Switch it on or off',
      body: 'The assistant is off until an administrator reads the notice and switches it on. While it is off nothing leaves your business.',
    },
    {
      id: 'limit',
      target: 'assistant-caps',
      title: 'The daily limit',
      body: 'This is the most the assistant may use in a day. At the limit, the Insights page shows the automatic summary instead.',
    },
  ],
};

// The Users page (issue #319): a page tour for whoever may read the staff list; the steps about changing people need the manage permission.
export const usersPage: Tour = {
  id: 'page-users',
  version: 5,
  title: 'This page: staff users',
  audience: (me) => held(me, 'core.users.read'),
  autoStart: false,
  page: '/staff/users',
  steps: [
    {
      id: 'invite',
      target: 'staff-invite',
      needs: ['core.users.manage'],
      title: 'Invite someone',
      body: 'Tap here to add a person. You type their name and phone, choose what they do, and get a link to send them. The link is shown once.',
    },
    {
      id: 'roles-card',
      target: 'who-can-do-what',
      title: 'Who can do what',
      body: 'Open this to see what each role may do. There is one short line for every role.',
    },
    {
      id: 'owner',
      target: 'staff-transfer-owner',
      optional: true,
      title: 'You are the owner',
      body: 'Only you can add, remove or change an administrator, and nobody can remove you. Transfer ownership hands this to another administrator: you stay an administrator, and they become the one owner.',
    },
    {
      id: 'search',
      target: 'staff-search',
      title: 'Find a person',
      body: 'Type a name, a phone number or an email. The list narrows as you type.',
    },
    {
      id: 'list',
      target: 'staff-list',
      optional: true,
      title: 'Who has joined',
      body: 'Invited means the person has not opened their link yet. Each card says what the person does and in which shops and stores. On their card you can send a new link, change their roles and where they work, or deactivate them when they leave. Reset sign-in makes a link for someone who forgot their password.',
    },
  ],
};

// The Notifications and app page (ADR-050): for everyone signed in. The install control is always on the page, so its step never
// waits on whether this device can install right now. JUSTIFICATION-A3: a page tour is a data object, one per screen, as every
// tour in this file is; the new screen needs exactly this one.
export const notificationsPage: Tour = {
  id: 'page-notifications',
  // Version 3 adds the Appearance step (issue #452), so whoever finished version 2 sees it once.
  version: 3,
  title: 'This page: notifications and the app',
  audience: () => true,
  autoStart: false,
  page: '/staff/notifications',
  steps: [
    {
      id: 'install',
      target: 'install-app',
      title: 'Install the app',
      body: 'Add BMS to your home screen or app list. On an iPhone or iPad use Safari: tap Share, then Add to Home Screen. If you cannot see them, try ⋯ or View More.',
    },
    {
      id: 'appearance',
      target: 'appearance',
      title: 'How BMS looks',
      body: 'Choose Light, Dark or Same as my phone. It changes only this device, like the button at the top right.',
    },
    {
      id: 'toggle',
      target: 'notifications-toggle',
      title: 'Notifications on this device',
      body: 'Turn this on to hear when something needs you. Your phone asks once, and only after you tap. A notice never shows a name, a phone number or an amount.',
    },
    {
      id: 'devices',
      target: 'notifications-devices',
      optional: true,
      title: 'Your devices',
      body: 'Each device that tells you things is listed here. Remove one you no longer use.',
    },
    {
      // Issue #412: the turn-on card shows in the staff area of the installed app, not on this page, so this step points at nothing.
      id: 'turn-on-card',
      title: 'Asked once, with one tap',
      body: 'When you open the installed app, a card asks "Get told when something needs you?". Tap Turn on and your phone asks once. Not now asks again in a few days, at most three times.',
    },
  ],
};

// Feedback (issue #447, ADR-065): the form and what the person sent before, with the BMS team's reply.
export const feedbackPage: Tour = {
  id: 'page-feedback',
  version: 1,
  title: 'This page: feedback',
  audience: () => true,
  autoStart: false,
  page: '/staff/feedback',
  steps: [
    {
      id: 'kind',
      target: 'feedback-kind',
      title: 'What is it?',
      body: 'Choose an idea, a problem or a question.',
    },
    {
      id: 'message',
      target: 'feedback-message',
      title: 'Your message',
      body: 'Say it in your own words, up to 2000 characters. For a problem, say what you did and what happened.',
    },
    {
      id: 'screenshot',
      target: 'feedback-screenshot',
      title: 'A picture of the screen',
      body: 'If it helps, add a PNG or JPEG picture of up to 2 MB. The page you came from, the version of BMS and your browser go with it on their own.',
    },
    {
      id: 'send',
      target: 'feedback-send',
      title: 'Send it',
      body: 'Tap Send feedback. It goes to the BMS team, and you can send up to 10 in an hour.',
    },
    {
      id: 'history',
      target: 'feedback-history',
      title: 'What you sent',
      body: 'Everything you sent is here, with whether the team has read it or finished with it, and their reply. Only you see your own.',
    },
  ],
};

// The Branches page (issue #329): a page tour for whoever may read branches; closing and reopening need the manage permission.
export const branchesPage: Tour = {
  id: 'page-branches',
  // Version 2 adds the Store step (issue #366): a branch that holds stock but does not sell.
  version: 2,
  title: 'This page: branches',
  audience: (me) => held(me, 'core.branches.read'),
  autoStart: false,
  page: '/staff/setup/branches',
  steps: [
    {
      id: 'intro',
      target: 'branches-intro',
      title: 'Closing a branch keeps its history',
      body: 'A closed branch keeps its sales and stock records for your reports. It only leaves the branch lists and the sale, restock and move-stock screens.',
    },
    {
      id: 'list',
      target: 'branches-list',
      title: 'Your branches',
      body: 'Each card is a branch. Tap Close this branch for a shop you no longer use. Tap Mark as keeping no goods for a head office with no stock. Goods left there can still be moved out. Closed branches are hidden. Tick Show closed branches to see them, then tap Reopen to bring one back.',
    },
    {
      id: 'store',
      target: 'branches-list',
      title: 'A store holds stock but does not sell',
      body: 'Tap Mark as a store (does not sell) for a branch that only keeps stock. It still takes restocks and stock moves, but it leaves the sale screen and the till. Tap Sells again to make it a shop.',
    },
  ],
};

// The Two-step sign-in page (issue #348): a page tour for whoever may change the settings.
export const twoStepPage: Tour = {
  id: 'page-two-step',
  version: 1,
  title: 'This page: two-step sign-in',
  audience: isTenantAdmin,
  autoStart: false,
  page: '/staff/setup/two-step',
  steps: [
    {
      id: 'intro',
      target: 'twostep-intro',
      title: 'A code from the phone',
      body: 'Two-step sign-in asks for a code from a phone as well as the password. Then a stolen password is not enough to sign in.',
    },
    {
      id: 'choice',
      target: 'twostep-choice',
      title: 'Who must use it',
      body: 'Optional means nobody has to. Pick some roles, or everyone, to require it. Before you save, you see who will be asked and from which day.',
    },
  ],
};

// Business set-up > Notifications for the business (issue #412): what the business sends by push, and when the daily summary goes.
export const businessNotificationsPage: Tour = {
  id: 'page-business-notifications',
  version: 1,
  title: 'This page: notifications for the business',
  audience: isTenantAdmin,
  autoStart: false,
  page: '/staff/setup/notifications',
  steps: [
    {
      id: 'intro',
      target: 'business-notify-intro',
      title: 'What your staff are told',
      body: 'BMS can tell your staff on their phones when something needs them. Each person still turns it on for their own phone.',
    },
    {
      id: 'switches',
      target: 'business-notify-switches',
      title: 'Each notice on or off',
      body: 'New orders, orders delivered, stock running low, credit to collect and the daily summary are all on until you switch one off.',
    },
    {
      id: 'time',
      target: 'business-notify-time',
      title: 'The daily summary',
      body: 'Choose when the owner and the administrators get the number of sales at each shop. It goes at 20:00 unless you change it.',
    },
  ],
};

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

// The tour of the documents capability (issue #197): it starts once for whoever may issue documents, when first enabled. A
// sale never needs a document, which the intro says; the buttons are on the sale, the list and the series on the Documents page.
export const documentsCapability: Tour = {
  id: 'capability-documents',
  version: 1,
  title: 'New: invoices and quotations',
  audience: (me) => held(me, 'retail.document.issue'),
  autoStart: true,
  capability: { module: 'retail', key: 'documents' },
  steps: [
    {
      id: 'intro',
      title: 'Documents are on',
      body: 'You can now make an invoice or a delivery note from a sale, and a quotation before it. The till receipt is still the default: a sale never needs a document.',
    },
    {
      id: 'tile',
      route: '/staff/retail',
      target: 'retail-documents',
      module: 'retail',
      optional: true,
      title: 'Find your documents',
      body: 'Open Documents to see every invoice, delivery note and quotation, and to open its PDF. Each has a number for its branch.',
    },
    {
      id: 'help',
      route: '/staff/retail/documents',
      target: 'documents-help',
      module: 'retail',
      title: 'Where documents are made',
      body: 'Open a sale from All sales and tap Make invoice or Make delivery note. Make a quotation from Quotations.',
    },
  ],
};

export const documentsPage: Tour = {
  id: 'page-documents',
  version: 1,
  title: 'This page: documents',
  audience: (me) => held(me, 'retail.document.issue') && capable(me, 'documents'),
  autoStart: false,
  page: '/staff/retail/documents',
  steps: [
    {
      id: 'list',
      target: 'documents-list',
      module: 'retail',
      title: 'Every document, newest first',
      body: 'Type a number or a buyer\'s name to find one. Tap Open PDF to see it. A document stays as it was issued; a cancelled sale shows its documents as void.',
    },
    {
      id: 'help',
      target: 'documents-help',
      module: 'retail',
      title: 'Make one from a sale',
      body: 'A sale is made first. Then open it and make the invoice or the delivery note. The number never skips and never repeats.',
    },
  ],
};

export const quotationsPage: Tour = {
  id: 'page-quotations',
  version: 1,
  title: 'This page: quotations',
  audience: (me) => held(me, 'retail.document.issue') && capable(me, 'documents'),
  autoStart: false,
  page: '/staff/retail/quotations',
  steps: [
    {
      id: 'new',
      target: 'quotation-new',
      module: 'retail',
      title: 'Offer prices until a date',
      body: 'Choose one branch, tap New quotation, pick the buyer, the last day and the items. Nothing is taken out of stock.',
    },
    {
      id: 'list',
      target: 'quotations-list',
      module: 'retail',
      title: 'When the buyer says yes',
      body: 'Tap Make it a sale on an open quotation. You can keep the quoted prices and make the invoice at the same time. A quotation can be made into a sale once.',
    },
  ],
};

// The tour of the purchasing capability (issue #198): it starts once for whoever may order and receive goods, when first enabled.
// JUSTIFICATION-A3: two new tours for the new screens; each tour is data, like the ones above it.
export const purchasingCapability: Tour = {
  id: 'capability-purchasing',
  // Version 2 adds the landed cost and credit note steps (issue #199), so whoever finished version 1 sees them once.
  version: 2,
  title: 'New: purchase orders',
  audience: (me) => held(me, 'retail.purchase.create'),
  autoStart: true,
  capability: { module: 'retail', key: 'purchasing' },
  steps: [
    {
      id: 'intro',
      title: 'Purchase orders are on',
      body: 'You can order from a supplier, receive what arrives, record the supplier\'s invoice and pay it. You can also add freight and duty to what goods cost, and record credit notes from suppliers. Restock still works as before for buying without an order.',
    },
    {
      id: 'tile',
      route: '/staff/retail',
      target: 'retail-purchaseOrders',
      module: 'retail',
      optional: true,
      title: 'Your orders',
      body: 'Open Purchase orders to make an order. When the goods arrive, open the order and choose Receive goods.',
    },
    {
      id: 'tile-landed',
      route: '/staff/retail',
      target: 'retail-landedCosts',
      module: 'retail',
      needs: ['retail.payable.manage'],
      optional: true,
      title: 'Freight and duty',
      body: 'Open Landed costs to add freight, duty, clearing or insurance to what a delivery cost you. The charges are shared over the goods, so each item\'s cost includes its part.',
    },
    {
      id: 'tile-credits',
      route: '/staff/retail',
      target: 'retail-supplierCredits',
      module: 'retail',
      needs: ['retail.payable.manage'],
      optional: true,
      title: 'Credit notes from suppliers',
      body: 'Open Supplier credit notes when you send goods back, or when a supplier takes money off an invoice. The credit comes off what you still owe on that invoice.',
    },
    {
      id: 'list',
      route: '/staff/retail/purchase-orders',
      target: 'purchase-orders-list',
      module: 'retail',
      title: 'Find an order',
      body: 'Type an order number or a supplier\'s name. Open an order to send it, change it while it is a draft, or receive goods against it.',
    },
  ],
};

export const purchasingPage: Tour = {
  id: 'page-purchase-orders',
  version: 1,
  title: 'This page: purchase orders',
  audience: (me) => held(me, 'retail.purchase.create') && capable(me, 'purchasing'),
  autoStart: false,
  page: '/staff/retail/purchase-orders',
  steps: [
    {
      id: 'help',
      target: 'purchase-orders-help',
      module: 'retail',
      title: 'How an order works',
      body: 'Save an order as a draft, then send it. A large order waits for a second person to approve it. When goods arrive, receive them against the order, in part or in full.',
    },
    {
      id: 'list',
      target: 'purchase-orders-list',
      module: 'retail',
      title: 'Your orders',
      body: 'Each order shows its status: draft, sent, partly received, received or cancelled. Use the buttons above to see only one kind.',
    },
  ],
};

// The landed costs page tour (issue #199): the money side belongs to whoever may manage what is owed to suppliers.
// JUSTIFICATION-A3: a new tour for the new screen; each tour is data, like the ones above it.
export const landedCostsPage: Tour = {
  id: 'page-landed-costs',
  version: 1,
  title: 'This page: landed costs',
  audience: (me) => held(me, 'retail.payable.manage') && capable(me, 'purchasing'),
  autoStart: false,
  page: '/staff/retail/landed-costs',
  steps: [
    {
      id: 'help',
      target: 'landed-help',
      module: 'retail',
      title: 'What a landed cost is',
      body: 'Freight, duty, clearing and insurance are part of what goods cost. Record them here and they are shared over the goods you choose.',
    },
    {
      id: 'new',
      target: 'landed-new',
      module: 'retail',
      title: 'Add the charges',
      body: 'Tap New landed cost. Choose who is owed, type the charges, pick how to share them, then tick the goods. You see each line\'s share before you save.',
    },
    {
      id: 'list',
      target: 'landed-list',
      module: 'retail',
      title: 'Your landed costs',
      body: 'Open one to see the share each item took, or to void it. The person you owe is paid on the supplier payments page, like any supplier.',
    },
  ],
};

// The Controls page tour (issue #357): for whoever may read the controls, the administrator's permission by default.
// JUSTIFICATION-A3: a new tour for the new screen; each tour is data, like the ones above it.
export const controlsPage: Tour = {
  id: 'page-controls',
  version: 1,
  title: 'This page: controls',
  audience: (me) => held(me, 'retail.controls.read'),
  autoStart: false,
  page: '/staff/retail/controls',
  steps: [
    { id: 'period', target: 'controls-period', module: 'retail', title: 'Pick the days', body: 'Choose today, this week, this month or your own days. Every count is set against the days just before.' },
    { id: 'cards', target: 'controls-cards', module: 'retail', title: 'The figures', body: 'Each card is one kind of thing to look at. Tap a card to see where and who. A rate is the number per 100 sales, so a busy person and a quiet one can be compared.' },
    { id: 'group-by', target: 'controls-group-by', module: 'retail', title: 'Group by', body: 'Choose how the lines are grouped: shop, person, day and reason. Move a line up or down, or take it out. We remember your choice.' },
    { id: 'tables', target: 'controls-tables', module: 'retail', title: 'Open a line', body: 'Tap a line to open it, down to the single sales. Tap Open sale to see one. Export CSV saves what you see.' },
    { id: 'patterns', target: 'controls-patterns', module: 'retail', title: 'Repeat patterns', body: "People or items whose rate is more than twice their shop's own. It is a reason to look, not a finding." },
  ],
};

/** The tours whose screens tours.test.tsx maps one by one; the slices below carry their own test files (test-kit.tsx). */
export const CORE_TOURS: readonly Tour[] = [
  adminWelcome, retailSales, staffWelcome, salePage, stockPage, restocksPage, salesPage, creditSalesPage, setupPage, loansPage, loanPage, cataloguePage, stocktakePage,
  retailSettingsPage, branchRolesCapability, stockByLocationPage, inTransitPage, transferReceiptCapability, unitsCapability,
  priceListsCapability, priceListsPage, customerAccountsCapability, accountsPage, returnsCapability, returnsPage, documentsCapability, documentsPage, quotationsPage,
  purchasingCapability, purchasingPage, landedCostsPage, assistantPage, usersPage, branchesPage, controlsPage, twoStepPage, businessNotificationsPage, notificationsPage, feedbackPage, ...CUSTOMER_TOURS,
];

const dynamicSegments = (tour: Tour) => (tour.page ?? '').split('/').filter((part) => part.startsWith('$')).length;

/**
 * Every tour. pageTourFor returns the first tour whose page matches, and a `$name` segment matches any segment, so a page with a fixed
 * segment (`goods-received/new`) must come before its `$` sibling (`goods-received/$receiptId`): the tours with fewer `$` segments go
 * first (the sort is stable, so the order inside each group is the order written). route-coverage.test.ts proves it for every route.
 */
export const TOURS: readonly Tour[] = [...CORE_TOURS, ...RETAIL_REPORT_TOURS, ...RETAIL_CASH_TOURS, ...RETAIL_PURCHASING_TOURS, ...RETAIL_ORDER_TOURS, ...LENDING_MORE_TOURS, ...PUBLIC_TOURS, ...OPERATOR_TOURS]
  .map((tour, order) => ({ tour, order }))
  .sort((a, b) => dynamicSegments(a.tour) - dynamicSegments(b.tour) || a.order - b.order)
  .map(({ tour }) => tour);
