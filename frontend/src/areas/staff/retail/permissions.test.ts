import { describe, expect, it } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { canSeeProfit, canUse, screensFor, showRetail } from './permissions';

describe('retail permission gating', () => {
  const sales = mockMe('sales');
  const admin = mockMe('admin');

  it('never offers profit or valuation to a sales user', () => {
    expect(canSeeProfit(sales)).toBe(false);
    expect(canUse(sales, 'profit')).toBe(false);
    expect(canUse(sales, 'valuation')).toBe(false);
    const labels = screensFor(sales).map((s) => s.screen);
    expect(labels).toEqual(['sale', 'usage', 'stock']);
  });

  it('offers everything to an admin', () => {
    expect(canSeeProfit(admin)).toBe(true);
    expect(screensFor(admin).map((s) => s.screen)).toEqual(['sale', 'restock', 'usage', 'stock', 'stocktake', 'valuation', 'profit']);
  });

  it('needs stock read as well as commit for a stock-take', () => {
    expect(canUse({ permissions: ['retail.stocktake.commit'] }, 'stocktake')).toBe(false);
  });

  it('shows the Retail menu only with the module and a retail permission', () => {
    expect(showRetail(sales)).toBe(true);
    expect(showRetail({ permissions: ['lending.members.read'] })).toBe(false);
    expect(showRetail({ permissions: [] })).toBe(false);
    expect(showRetail({ permissions: ['retail.sale.create'], modules: ['lending'] })).toBe(false);
    expect(showRetail({ permissions: ['retail.sale.create'], modules: ['retail'] })).toBe(true);
  });
});
