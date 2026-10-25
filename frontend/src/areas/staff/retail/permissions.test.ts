import { describe, expect, it } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { staffMenu } from '../menu';
import { SCREENS, canOpenAccount, canSeeProfit, canUse, holdsAt, screensFor, showRetail } from './permissions';

describe('retail permission gating', () => {
  const sales = mockMe('sales');
  const admin = mockMe('admin');

  it('never offers profit to a sales user, and follows the real permission names', () => {
    expect(canSeeProfit(sales)).toBe(false);
    expect(canUse(sales, 'profit')).toBe(false);
    expect(canUse(sales, 'restock')).toBe(false);
    expect(canUse(sales, 'stocktake')).toBe(false);
    // Stock value, even at the selling price, is the owner's figure (ADR-055), so the seller has no Stock value tile.
    // Since V74 the cash book is the till's (ADR-055), so the seeded sales role has no cash book tile.
    expect(screensFor(sales).map((s) => s.screen)).toEqual(['sale', 'creditSales', 'salesHistory', 'orders', 'usage', 'stock', 'transfers', 'stockHealth', 'creditControl', 'catalogue']);
    expect(canUse(sales, 'transfer')).toBe(false);
  });

  it('maps each screen to the permission the API route declares', () => {
    const only = (p: string) => screensFor({ permissions: [p] }).map((s) => s.screen);
    expect(only('retail.sale.create')).toEqual(['sale']);
    expect(only('retail.purchase.create')).toEqual(['restock']);
    expect(only('retail.usage.report')).toEqual(['usage']);
    expect(only('retail.stock.read')).toEqual(['stock', 'transfers', 'stockHealth']);
    expect(only('retail.profit.read')).toEqual(['profit', 'margins', 'evaluation']);
    expect(only('retail.sale.read')).toEqual(['creditSales', 'salesHistory', 'creditControl']);
    // ADR-055: the sales analysis and the orders report add up everybody's sales, so they are the owner's.
    expect(screensFor({ permissions: ['retail.sale.read', 'retail.sale.all_sellers'] }).map((s) => s.screen)).toContain('salesAnalysis');
    // Controls name staff and rank them by a rate, so it is its own permission, never a sales read.
    expect(only('retail.controls.read')).toEqual(['controls']);
    // Customer orders (issue #385): the orders and their report are one permission; delivering also needs a sale permission.
    expect(only('retail.order.manage')).toEqual(['orders']);
  });

  it('offers everything to an admin', () => {
    expect(canSeeProfit(admin)).toBe(true);
    // The one addition for the trade slices (#192) is the Retail settings tile, the administrator's way to switch
    // features on. The tile of a capability (Branch roles) is absent while the capability is off.
    expect(screensFor(admin).map((s) => s.screen)).toEqual(
      [
        'sale', 'creditSales', 'salesHistory', 'orders', 'ordersReport', 'restock', 'usage', 'stock', 'stocktake', 'transfer', 'transfers', 'valuation', 'profit',
        'salesAnalysis', 'margins', 'stockHealth', 'creditControl', 'evaluation', 'controls', 'catalogue',
        'banking', 'savings', 'expenses', 'withdrawals', 'advances', 'cashSummary', 'bankingReport', 'expensesReport', 'expenseSetup',
        // Paying suppliers and what is owed need no capability (issue #557): they pay what Buy stock bought on credit.
        'supplierPayments', 'supplierPayables',
        'settings',
      ],
    );
  });

  it('shows each cash book tile only with its permission', () => {
    const only = (...p: string[]) => screensFor({ permissions: p }).map((s) => s.screen);
    // Savings and banking close the till (ADR-055): they need the till too.
    expect(only('retail.savings.record')).toEqual([]);
    expect(only('retail.savings.record', 'retail.till.close')).toEqual(['savings']);
    expect(only('retail.banking.record', 'retail.till.close')).toEqual(['banking']);
    expect(only('retail.expense.record')).toEqual(['expenses']);
    expect(only('retail.withdrawal.record')).toEqual(['withdrawals']);
    expect(only('retail.advance.create')).toEqual(['advances']);
    expect(only('retail.advance.repay')).toEqual(['advances']);
    // The lists and reports need the cash book read; setting up the lists needs expense.manage as well.
    expect(only('retail.cashbook.read')).toEqual(['bankingReport', 'expensesReport']);
    expect(only('retail.cashbook.read', 'retail.till.close')).toEqual(['cashSummary', 'bankingReport', 'expensesReport']);
    expect(only('retail.expense.manage')).toEqual([]);
    expect(only('retail.expense.manage', 'retail.cashbook.read')).toEqual(['bankingReport', 'expensesReport', 'expenseSetup']);
    expect(only('retail.cashbook.void', 'retail.savings.overwrite', 'retail.profit.read')).toEqual(['profit', 'margins', 'evaluation']);
  });

  it('gives the cashier the cash book screens that record, and none that need profit or setup', () => {
    const cashier = mockMe('cashier');
    expect(canSeeProfit(cashier)).toBe(false);
    expect(canUse(cashier, 'savings')).toBe(true);
    expect(canUse(cashier, 'banking')).toBe(true);
    expect(canUse(cashier, 'expenses')).toBe(true);
    expect(canUse(cashier, 'withdrawals')).toBe(false);
    expect(canUse(cashier, 'advances')).toBe(false);
    expect(canUse(cashier, 'expenseSetup')).toBe(false);
  });

  it('needs stock read as well as commit for a stock-take', () => {
    expect(canUse({ permissions: ['retail.stocktake.commit'] }, 'stocktake')).toBe(false);
  });

  it('offers Move stock only with retail.stock.transfer and stock read', () => {
    expect(canUse({ permissions: ['retail.stock.transfer'] }, 'transfer')).toBe(false);
    expect(canUse({ permissions: ['retail.stock.transfer', 'retail.stock.read'] }, 'transfer')).toBe(true);
  });

  it('shows the Retail menu only with the module and a retail permission', () => {
    expect(showRetail(sales)).toBe(true);
    expect(showRetail({ permissions: ['lending.members.read'] })).toBe(false);
    expect(showRetail({ permissions: [] })).toBe(false);
    expect(showRetail({ permissions: ['retail.sale.create'], modules: ['lending'] })).toBe(false);
    expect(showRetail({ permissions: ['retail.sale.create'], modules: ['retail'] })).toBe(true);
  });
});

// The shop rows of the staff menu (issue #564): every page the session may use, by the same rules as the screens, with no cap.
describe('the shop rows of the staff menu', () => {
  const rows = (me: ReturnType<typeof mockMe>) => staffMenu(me, { sells: true }).map((g) => [g.id, g.links.map((l) => l.label)]);

  it('gives an administrator every row of Today, Stock and Money', () => {
    expect(rows(mockMe('admin'))).toEqual([
      ['home', ['Home']],
      ['today', ['Sell', 'Orders']],
      ['stock', ['My stock', 'Buy stock', 'Count stock', 'Move stock', 'Items and categories']],
      ['money', ['Cash book', 'Expenses', 'Customers who owe', 'What I owe suppliers', 'Savings and advances']],
      ['lending', []],
      ['business', ['Reports', 'Business set-up']],
    ]);
  });

  it('gives a cashier the cash book rows their permissions allow', () => {
    expect(rows(mockMe('cashier'))).toEqual([
      ['home', ['Home']],
      ['today', ['Sell', 'Orders']],
      ['stock', ['My stock', 'Items and categories']],
      ['money', ['Cash book', 'Expenses', 'Customers who owe', 'Savings and advances']],
      ['lending', []],
      ['business', ['Reports']],
    ]);
  });

  // Pages without a row of their own (Usage, Banking, Withdrawals, Advances, Customer accounts and the rest) stay one tap from the
  // menu: Reports opens the Retail page, whose tiles are every shop page the session may use (ADR-086 decision 3).
  it.each(['admin', 'cashier', 'sales'])('leaves no shop page out of reach for %s: a row of its own, or a tile behind Reports', (role) => {
    const me = mockMe(role);
    const rows = staffMenu(me, { sells: true }).flatMap((g) => g.links.map((l) => l.to));
    expect(rows).toContain('/staff/retail');
    const tiles = screensFor(me, { sells: true }).map((s) => s.path);
    const allowed = SCREENS.filter((s) => canUse(me, s.screen));
    for (const s of allowed) expect(rows.includes(s.path) || tiles.includes(s.path), s.screen).toBe(true);
    for (const screen of ['usage', 'banking', 'withdrawals', 'advances', 'customerAccounts'] as const) {
      if (canUse(me, screen)) expect(tiles, screen).toContain(SCREENS.find((s) => s.screen === screen)?.path);
    }
  });

  it('gives a seller without the cash book only the rows they may open', () => {
    // ADR-055: the seeded sales role has no cash book.
    expect(rows(mockMe('sales'))).toEqual([
      ['home', ['Home']],
      ['today', ['Sell', 'Orders']],
      ['stock', ['My stock', 'Items and categories']],
      ['money', ['Customers who owe']],
      ['lending', []],
      ['business', ['Reports']],
    ]);
  });
});

describe('a permission held at a branch (issue #195)', () => {
  const me = {
    permissions: ['retail.price.override'],
    permission_scopes: { 'retail.price.override': { all_branches: false, branch_ids: ['b1'] } },
    branches: [{ id: 'b1' }, { id: 'b2' }],
  };
  it('is held at the branches of its scope and not at the others, as the server decides', () => {
    expect(holdsAt(me, 'retail.price.override', 'b1')).toBe(true);
    expect(holdsAt(me, 'retail.price.override', 'b2')).toBe(false);
    expect(holdsAt(me, 'retail.price.override', undefined)).toBe(true);
    expect(holdsAt(me, 'retail.pricelist.use', 'b1')).toBe(false);
  });
});

describe('customer accounts (issue #196)', () => {
  const caps = (me: ReturnType<typeof mockMe>, on: boolean) => ({ ...me, module_capabilities: { retail: on ? ['customer_accounts'] : [] } });

  it('shows the Customer accounts tile to the administrator who sets limits, and only while the feature is on', () => {
    expect(canUse(caps(mockMe('admin'), true), 'customerAccounts')).toBe(true);
    expect(canUse(caps(mockMe('admin'), false), 'customerAccounts')).toBe(false);
    expect(canUse(caps(mockMe('sales'), true), 'customerAccounts')).toBe(false);
    expect(screensFor(caps(mockMe('sales'), true)).map((s) => s.screen)).toEqual(screensFor(caps(mockMe('sales'), false)).map((s) => s.screen));
  });

  it('opens a buyer\'s account to whoever reads sales, while the feature is on', () => {
    expect(canOpenAccount(caps(mockMe('sales'), true))).toBe(true);
    expect(canOpenAccount(caps(mockMe('sales'), false))).toBe(false);
    expect(canOpenAccount({ permissions: ['retail.stock.read'], module_capabilities: { retail: ['customer_accounts'] } })).toBe(false);
  });
});
