import { describe, expect, it } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { canSeeProfit, canUse, screensFor, showRetail } from './permissions';

describe('retail permission gating', () => {
  const sales = mockMe('sales');
  const admin = mockMe('admin');

  it('never offers profit to a sales user, and follows the real permission names', () => {
    expect(canSeeProfit(sales)).toBe(false);
    expect(canUse(sales, 'profit')).toBe(false);
    expect(canUse(sales, 'restock')).toBe(false);
    expect(canUse(sales, 'stocktake')).toBe(false);
    // Stock value is a stock read; only its cost columns need retail.profit.read.
    expect(screensFor(sales).map((s) => s.screen)).toEqual(['sale', 'creditSales', 'salesHistory', 'usage', 'stock', 'transfers', 'valuation']);
    expect(canUse(sales, 'transfer')).toBe(false);
  });

  it('maps each screen to the permission the API route declares', () => {
    const only = (p: string) => screensFor({ permissions: [p] }).map((s) => s.screen);
    expect(only('retail.sale.create')).toEqual(['sale']);
    expect(only('retail.purchase.create')).toEqual(['restock']);
    expect(only('retail.usage.report')).toEqual(['usage']);
    expect(only('retail.stock.read')).toEqual(['stock', 'transfers', 'valuation']);
    expect(only('retail.profit.read')).toEqual(['profit']);
    expect(only('retail.sale.read')).toEqual(['creditSales', 'salesHistory']);
  });

  it('offers everything to an admin', () => {
    expect(canSeeProfit(admin)).toBe(true);
    expect(screensFor(admin).map((s) => s.screen)).toEqual(
      ['sale', 'creditSales', 'salesHistory', 'restock', 'usage', 'stock', 'stocktake', 'transfer', 'transfers', 'valuation', 'profit'],
    );
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
