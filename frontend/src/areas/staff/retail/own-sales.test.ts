import { describe, expect, it } from 'vitest';
import { canUse, holds } from './permissions';
import { ownFigures, ownLevel, ownSeries, ownSteps } from './own-sales';
import { rangeDays } from './range-state';
import { mockMe } from '../../../api/retail-mock';

// A seller's own figures on the Retail home (issue #399, ADR-055), over the range of issue #428, with fabricated amounts.

describe('a seller\'s own sales', () => {
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

describe('a seller\'s own range', () => {
  it('reads the summary by day up to 31 days, by month up to a year and by year for All', () => {
    expect(ownLevel(rangeDays({ period: '30d' }, '2026-10-21'))).toBe('day');
    expect(ownLevel(rangeDays({ period: 'ytd' }, '2026-10-21'))).toBe('month');
    expect(ownLevel(rangeDays({ period: 'all' }, '2026-10-21'))).toBe('year');
    expect(ownSteps('month', '2025-10-22', '2026-10-21')).toHaveLength(13);
    expect(ownSteps('day', '2026-10-15', '2026-10-21')).toEqual(['2026-10-15', '2026-10-16', '2026-10-17', '2026-10-18', '2026-10-19', '2026-10-20', '2026-10-21']);
  });

  it('adds up the groups the server sent and lines the period before up step by step', () => {
    const now = [
      { key: '2026-10-21', from: '2026-10-21', total_minor: 7_000, sales_count: 2 },
      { key: '2026-10-19', from: '2026-10-19', total_minor: 3_000, sales_count: 1 },
    ];
    const before = [{ key: '2026-10-14', from: '2026-10-14', total_minor: 4_000, sales_count: 2 }];
    expect(ownFigures(now)).toEqual({ total: 10_000, count: 3, average: 3_333, best: { start: '2026-10-21', total: 7_000 } });
    expect(ownFigures([])).toEqual({ total: 0, count: 0, average: null, best: null });
    const days = rangeDays({ period: '7d' }, '2026-10-21');
    const series = ownSeries('day', days, now, before);
    expect(series).toHaveLength(7);
    expect(series[6]).toEqual({ start: '2026-10-21', value: 7_000, previous: 4_000 });
    expect(series[4]).toEqual({ start: '2026-10-19', value: 3_000, previous: 0 });
    expect(ownSeries('day', days, now, undefined)[6]?.previous).toBeUndefined();
  });
});
