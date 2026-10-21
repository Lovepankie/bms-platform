import { describe, expect, it } from 'vitest';
import { canUse, holds } from './permissions';
import { lastSevenDays, ownTotals } from './own-sales';
import { mockMe } from '../../../api/retail-mock';

// A seller's own figures on the Retail home (issue #399, ADR-055), with fabricated amounts.

describe('a seller\'s own sales', () => {
  it('covers today and the six days before, in the business time zone', () => {
    expect(lastSevenDays(new Date('2026-10-21T10:00:00Z'))).toEqual({ from: '2026-10-15', to: '2026-10-21' });
  });

  it('adds up the day groups the server sent, today apart', () => {
    const groups = [
      { key: '2026-10-21', from: '2026-10-21', total_minor: 7_000 },
      { key: '2026-10-19', from: '2026-10-19', total_minor: 3_000 },
      { key: '2026-10-15', from: '2026-10-15', total_minor: 1_000 },
    ];
    expect(ownTotals(groups, '2026-10-21')).toEqual({ today: 7_000, week: 11_000 });
    expect(ownTotals([], '2026-10-21')).toEqual({ today: 0, week: 0 });
  });

  it('is what a seller gets instead of the owner dashboard, the sales analysis and the stock value', () => {
    const seller = mockMe('sales');
    expect(holds(seller, 'retail.sale.all_sellers')).toBe(false);
    expect(canUse(seller, 'salesAnalysis')).toBe(false);
    expect(canUse(seller, 'valuation')).toBe(false);
    expect(canUse(seller, 'ordersReport')).toBe(false);
    expect(canUse(seller, 'salesHistory')).toBe(true);
    const owner = mockMe('admin');
    expect(holds(owner, 'retail.sale.all_sellers')).toBe(true);
    expect(canUse(owner, 'salesAnalysis')).toBe(true);
  });
});
