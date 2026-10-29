import type { Me } from '../api/client';
import type { Tour, TourStep } from './model';

// Page tours for retail home, stock moves, usage, restock and the report screens (issue #337). Plain words, short
// sentences, say what to tap. tours-retail-reports.test.tsx renders each page and fails if an anchor is missing.

const SELL = 'retail.sale.create';
const SALES_READ = 'retail.sale.read';
const STOCK_READ = 'retail.stock.read';
const RESTOCK = 'retail.purchase.create';
const USAGE = 'retail.usage.report';
const TRANSFER = 'retail.stock.transfer';
const PROFIT = 'retail.profit.read';

const held = (me: Me, permission: string) => (me.permissions ?? []).includes(permission);

const retail = (step: Omit<TourStep, 'module'>): TourStep => ({ module: 'retail', ...step });

const rangeStep = (target: string, what: string): TourStep =>
  retail({ id: 'range', target, title: 'Pick the days', body: `Choose the first and the last day. ${what} Use at most 366 days.` });

export const retailHomePage: Tour = {
  id: 'page-retail-home',
  // Version 4 follows the glossary: Buy stock, Count stock, Customers who owe and the other plain words (issue #547).
  // Version 5 says buying price for cost (issue #680).
  version: 5,
  title: 'This page: Retail',
  audience: (me) => (me.permissions ?? []).some((p) => p.startsWith('retail.')),
  autoStart: false,
  page: '/staff/retail',
  steps: [
    retail({ id: 'tiles', target: 'retail-tiles', title: 'One button for each job', body: 'Tap a button to open that page. You only see the pages you may use.' }),
    retail({ id: 'sale', target: 'retail-sale', needs: [SELL], optional: true, title: 'Record a sale', body: 'Tap here when a customer pays. Pick the items, then save the sale.' }),
    retail({ id: 'restock', target: 'retail-restock', needs: [RESTOCK], optional: true, title: 'Buy stock', body: 'Tap here when new goods arrive. Stock goes up and the buying price is saved.' }),
    retail({ id: 'stock', target: 'retail-stock', needs: [STOCK_READ], optional: true, title: 'Check your stock', body: 'Tap here to see how much of each item you hold, shop by shop.' }),
    // ADR-055: a seller sees their own figures; everybody's sales, profit and the value of the stock are the owner's.
    // Issue #428: every figure on the page follows the days picked here, set against the days before.
    retail({ id: 'range', target: 'home-range', needs: [SALES_READ], optional: true, title: 'Pick the days', body: 'Tap Today, 7 days, 30 days, This year, 1 year or All, or Choose days for your own first and last day. Every figure and the chart follow it and are set against the days before. Tap or point at the chart to read one day. Your choice is kept on this phone.' }),
    retail({ id: 'own-sales', target: 'own-sales', needs: [SALES_READ], unless: ['retail.sale.all_sellers'], optional: true, title: 'Your sales', body: 'What you sold in the days you picked, against the days before. Only your own sales are counted. All sales lists them one by one.' }),
  ],
};

export const restockPage: Tour = {
  id: 'page-restock',
  // Version 2 adds the Scan here step (issue #345, ADR-070).
  // Version 3 words the Add the items step for the picker that lists items only while you type (issue #534).
  // Version 4 follows the glossary: Buy stock, Count stock, Customers who owe and the other plain words (issue #547).
  // Version 5 says a line unusual for the item is asked about before saving (issue #353, ADR-093).
  // Version 6 says buying price and selling price (issue #680).
  version: 6,
  title: 'This page: buy stock',
  audience: (me) => held(me, RESTOCK),
  autoStart: false,
  page: '/staff/retail/restock',
  steps: [
    retail({ id: 'supplier', target: 'restock-supplier', title: 'Who supplied it', body: 'Pick the supplier, or type a new one. You can also leave it as No supplier.' }),
    retail({ id: 'search', target: 'restock-search', title: 'Add the items', body: 'Type part of the name or code, and tap each item you bought in the list under the box. Then fill in the buying price, the selling price and how many went to each branch.' }),
    retail({ id: 'scan', target: 'scan-bar', title: 'Scan here', body: 'Or scan each item in the Scan box. It is added for the branch at the top, and a second scan adds one more.' }),
    retail({ id: 'paid', target: 'restock-paid', title: 'How you paid', body: 'Choose Cash, Bank or Credit. Credit means you pay the supplier later.' }),
    retail({ id: 'save', target: 'restock-save', title: 'Save the purchase', body: 'Tap Save the purchase. Stock goes up, and the buying and selling price of each item change to what you typed. If a quantity or unit is unusual for an item, a note beside it asks you to Confirm or Change it first.' }),
  ],
};

export const usagePage: Tour = {
  id: 'page-usage',
  // Version 2 words the Add the items step for the picker that lists items only while you type (issue #534).
  version: 2,
  title: 'This page: usage and damage',
  audience: (me) => held(me, USAGE),
  autoStart: false,
  page: '/staff/retail/usage',
  steps: [
    retail({ id: 'kind', target: 'usage-kind', title: 'What happened', body: 'Choose Used in the shop, or Damaged or lost.' }),
    retail({ id: 'reason', target: 'usage-reason', title: 'Say why', body: 'Type a short reason. It is kept with the record.' }),
    retail({ id: 'search', target: 'usage-search', title: 'Add the items', body: 'Type part of the name or code, and tap the item in the list under the box. Then type the quantity for each item.' }),
    retail({ id: 'save', target: 'usage-save', title: 'Save', body: 'Tap Save. The items leave your stock.' }),
  ],
};

export const transferPage: Tour = {
  id: 'page-transfer',
  // Version 2 adds the Scan here step (issue #345, ADR-070).
  // Version 3 words the Add the items step for the picker that lists items only while you type (issue #534).
  // Version 4 says a quantity unusual for the item is asked about before saving (issue #353, ADR-093).
  version: 4,
  title: 'This page: move stock',
  audience: (me) => held(me, TRANSFER) && held(me, STOCK_READ),
  autoStart: false,
  page: '/staff/retail/transfer',
  steps: [
    retail({ id: 'to', target: 'transfer-to', title: 'Where it goes', body: 'The branch chosen at the top of the page is where the stock leaves from. Pick the branch it goes to.' }),
    retail({ id: 'search', target: 'transfer-search', title: 'Add the items', body: 'Type part of the name or code, and tap each item to move in the list under the box.' }),
    retail({ id: 'scan', target: 'scan-bar', title: 'Scan here', body: 'Or scan each item in the Scan box. It is added, and a second scan adds one more.' }),
    retail({ id: 'items', target: 'transfer-items', title: 'How many', body: 'Type the quantity for each item. You cannot move more than the branch holds.' }),
    retail({ id: 'save', target: 'transfer-save', title: 'Save the move', body: 'Tap Move stock, or Send stock if the other branch must receive it first. If a quantity is unusual for an item, a note beside it asks you to Confirm or Change it first.' }),
  ],
};

export const transfersPage: Tour = {
  id: 'page-transfers',
  version: 1,
  title: 'This page: stock moves',
  audience: (me) => held(me, STOCK_READ),
  autoStart: false,
  page: '/staff/retail/transfers',
  steps: [
    retail({ id: 'list', target: 'transfers-list', title: 'Every move, newest first', body: 'Tap a move to see its items. Where you may, you can cancel it from there.' }),
    retail({ id: 'new', target: 'transfers-new', needs: [TRANSFER], optional: true, title: 'Move stock', body: 'Tap here to move stock from one branch to another.' }),
  ],
};

export const valuationPage: Tour = {
  id: 'page-valuation',
  // Version 2 trades the Which branch step, now the title of the top bar, for the stock at price in large figures (issue #526).
  // Version 3 says buying price and selling price (issue #680).
  version: 3,
  title: 'This page: stock value',
  audience: (me) => held(me, STOCK_READ),
  autoStart: false,
  page: '/staff/retail/valuation',
  steps: [
    retail({ id: 'total', target: 'valuation-total', title: 'What your stock is worth', body: 'The large figure is your stock at the price you sell it, for the branch at the top of the page.' }),
    retail({ id: 'figures', target: 'valuation-figures', title: 'By category and item', body: 'See the value at selling price, by category and by item. If you may see buying prices, you also see the value at buying price and the profit you expect. Tap an item to open it in Stock.' }),
  ],
};

export const profitPage: Tour = {
  id: 'page-profit',
  // Version 2 follows the glossary: Buy stock, Count stock, Customers who owe and the other plain words (issue #547).
  version: 2,
  title: 'This page: daily profit',
  audience: (me) => held(me, PROFIT),
  autoStart: false,
  page: '/staff/retail/profit',
  steps: [
    rangeStep('profit-range', 'The report shows each day in between.'),
    retail({ id: 'figures', target: 'profit-figures', title: 'Profit for the period', body: 'See sales, cost, usage and the stock count difference for each day. The total is at the top. Tap the link under it to see the sales.' }),
  ],
};

export const salesAnalysisPage: Tour = {
  id: 'page-sales-analysis',
  version: 1,
  title: 'This page: sales analysis',
  audience: (me) => held(me, SALES_READ),
  autoStart: false,
  page: '/staff/retail/sales-analysis',
  steps: [
    rangeStep('analysis-range', 'Everything below is for these days.'),
    retail({ id: 'options', target: 'analysis-options', title: 'Group and trim', body: 'Group the days by day, week or month. Choose how many rows to show, and what counts as a slow mover.' }),
    retail({ id: 'results', target: 'analysis-results', title: 'Read the results', body: 'See sales over time, best sellers, and sales by branch, category and seller. Tap the sales total to open All sales, or an item to open it in Stock.' }),
  ],
};

export const marginsPage: Tour = {
  id: 'page-margins',
  version: 1,
  title: 'This page: margins',
  audience: (me) => held(me, PROFIT),
  autoStart: false,
  page: '/staff/retail/margins',
  steps: [
    rangeStep('margins-range', 'The margins are for what sold in these days.'),
    retail({ id: 'target', target: 'margins-target', title: 'Your margin target', body: 'Pick the margin you want. Items that sold below it are listed first, in red.' }),
    retail({ id: 'results', target: 'margins-results', title: 'Read the results', body: 'See profit by item and category, and what a price change did to sales and margin.' }),
  ],
};

export const stockHealthPage: Tour = {
  id: 'page-stock-health',
  // Version 2 added the slow movers step (issue #414), version 3 the why-low step (issue #412).
  version: 3,
  title: 'This page: stock health',
  audience: (me) => held(me, STOCK_READ),
  autoStart: false,
  page: '/staff/retail/stock-health',
  steps: [
    rangeStep('health-range', 'It sets the days for used, damaged and missing stock.'),
    retail({ id: 'buy', target: 'health-buy', title: 'When to buy', body: 'Choose how many days of stock is too low, and how many days you want to buy for.' }),
    retail({ id: 'results', target: 'health-results', title: 'What to buy and what is stuck', body: 'See what to reorder, how long each item lasts, and what has not sold for 90 days. Tap an item to open it in Stock.' }),
    // Issue #412: why a row is on the reorder list, and where its level is set.
    retail({ id: 'why-low', target: 'health-results', title: 'Why it needs buying', body: 'Each item to reorder says why. It is below the minimum you set, or it runs out soon at the pace it sells. Tap Set a level to choose "Warn me when stock is below" for it.' }),
    retail({ id: 'slow', target: 'health-slow', title: 'Slow movers', body: 'Items that sell, but slowly: the months it takes to sell what you hold. If it sells better at another branch, tap Move to send some there. Managers who see costs also see the money tied up.' }),
  ],
};

export const creditControlPage: Tour = {
  id: 'page-credit-control',
  // Version 2 follows the glossary: Buy stock, Count stock, Customers who owe and the other plain words (issue #547).
  version: 2,
  title: 'This page: credit control',
  audience: (me) => held(me, SALES_READ),
  autoStart: false,
  page: '/staff/retail/credit-control',
  steps: [
    rangeStep('credit-range', 'It chooses which payments are listed. What is owed is always as of today.'),
    retail({ id: 'sort', target: 'credit-sort', title: 'Order the overdue list', body: 'Show the biggest amounts first, or the oldest first.' }),
    retail({ id: 'results', target: 'credit-results', title: 'Who owes you', body: 'See what customers who owe have to pay and how late it is. Tap a buyer to open their account, or the link at the top to see the credit sales.' }),
  ],
};

export const evaluationPage: Tour = {
  id: 'page-evaluation',
  version: 1,
  title: 'This page: business evaluation',
  audience: (me) => held(me, PROFIT),
  autoStart: false,
  page: '/staff/retail/evaluation',
  steps: [
    rangeStep('evaluation-range', 'It sets the period for profit to date.'),
    retail({ id: 'top', target: 'evaluation-top', title: 'How many items', body: 'Choose how many items to list under each category.' }),
    retail({ id: 'results', target: 'evaluation-results', title: 'Where you stand', body: 'See profit so far, what your stock is worth beyond its cost, and how long it lasts. This is an estimate, not a promise.' }),
  ],
};

export const RETAIL_REPORT_TOURS: readonly Tour[] = [
  retailHomePage, restockPage, usagePage, transferPage, transfersPage, valuationPage, profitPage,
  salesAnalysisPage, marginsPage, stockHealthPage, creditControlPage, evaluationPage,
];
