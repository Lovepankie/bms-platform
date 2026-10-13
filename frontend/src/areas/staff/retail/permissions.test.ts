import { describe, expect, it } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { shortcutsFor } from './nav';
import { canOpenAccount, canSeeProfit, canUse, holdsAt, screensFor, showRetail } from './permissions';

describe('retail permission gating', () => {
  const sales = mockMe('sales');
  const admin = mockMe('admin');

  it('never offers profit to a sales user, and follows the real permission names', () => {
    expect(canSeeProfit(sales)).toBe(false);
    expect(canUse(sales, 'profit')).toBe(false);
    expect(canUse(sales, 'restock')).toBe(false);
    expect(canUse(sales, 'stocktake')).toBe(false);
    // Stock value is a stock read; only its cost columns need retail.profit.read.
    // The seeded sales role also holds four cash book permissions (chapter 8 matrix).
    expect(screensFor(sales).map((s) => s.screen)).toEqual(['sale', 'creditSales', 'salesHistory', 'usage', 'stock', 'transfers', 'valuation', 'salesAnalysis', 'stockHealth', 'creditControl', 'catalogue', 'banking', 'savings', 'expenses', 'cashSummary', 'bankingReport', 'expensesReport']);
    expect(canUse(sales, 'transfer')).toBe(false);
  });

  it('maps each screen to the permission the API route declares', () => {
    const only = (p: string) => screensFor({ permissions: [p] }).map((s) => s.screen);
    expect(only('retail.sale.create')).toEqual(['sale']);
    expect(only('retail.purchase.create')).toEqual(['restock']);
    expect(only('retail.usage.report')).toEqual(['usage']);
    expect(only('retail.stock.read')).toEqual(['stock', 'transfers', 'valuation', 'stockHealth']);
    expect(only('retail.profit.read')).toEqual(['profit', 'margins', 'evaluation']);
    expect(only('retail.sale.read')).toEqual(['creditSales', 'salesHistory', 'salesAnalysis', 'creditControl']);
  });

  it('offers everything to an admin', () => {
    expect(canSeeProfit(admin)).toBe(true);
    // The one addition for the trade slices (#192) is the Retail settings tile, the administrator's way to switch
    // features on. The tile of a capability (Branch roles) is absent while the capability is off.
    expect(screensFor(admin).map((s) => s.screen)).toEqual(
      [
        'sale', 'creditSales', 'salesHistory', 'restock', 'usage', 'stock', 'stocktake', 'transfer', 'transfers', 'valuation', 'profit',
        'salesAnalysis', 'margins', 'stockHealth', 'creditControl', 'evaluation', 'catalogue',
        'banking', 'savings', 'expenses', 'withdrawals', 'advances', 'cashSummary', 'bankingReport', 'expensesReport', 'expenseSetup',
        'settings',
      ],
    );
  });

  it('shows each cash book tile only with its permission', () => {
    const only = (...p: string[]) => screensFor({ permissions: p }).map((s) => s.screen);
    expect(only('retail.savings.record')).toEqual(['savings']);
    expect(only('retail.banking.record')).toEqual(['banking']);
    expect(only('retail.expense.record')).toEqual(['expenses']);
    expect(only('retail.withdrawal.record')).toEqual(['withdrawals']);
    expect(only('retail.advance.create')).toEqual(['advances']);
    expect(only('retail.advance.repay')).toEqual(['advances']);
    // The lists and reports need the cash book read; setting up the lists needs expense.manage as well.
    expect(only('retail.cashbook.read')).toEqual(['cashSummary', 'bankingReport', 'expensesReport']);
    expect(only('retail.expense.manage')).toEqual([]);
    expect(only('retail.expense.manage', 'retail.cashbook.read')).toEqual(['cashSummary', 'bankingReport', 'expensesReport', 'expenseSetup']);
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

describe('the retail bottom bar', () => {
  it('follows the pilot order: Sales, Banked, Savings, Expenses, Advances', () => {
    expect(shortcutsFor(mockMe('admin')).map((s) => s.screen)).toEqual(['sale', 'banking', 'savings', 'expenses', 'advances']);
  });

  it('gives a cashier only the shortcuts their permissions allow, then the stock ones', () => {
    expect(shortcutsFor(mockMe('cashier')).map((s) => s.screen)).toEqual(['sale', 'banking', 'savings', 'expenses', 'stock']);
  });

  it('gives the seeded sales role its sale shortcut, the three it records, then stock', () => {
    expect(shortcutsFor(mockMe('sales')).map((s) => s.screen)).toEqual(['sale', 'banking', 'savings', 'expenses', 'stock']);
  });

  it('keeps the old shortcuts for a user with no cash book permission', () => {
    const me = mockMe('sales');
    const plain = { ...me, permissions: (me.permissions ?? []).filter((p) => !/cash|savings|banking|expense/.test(p)) };
    expect(shortcutsFor(plain).map((s) => s.screen)).toEqual(['sale', 'stock', 'usage']);
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
