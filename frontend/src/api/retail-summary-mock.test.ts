import { describe, expect, it } from 'vitest';
import type { Sale } from './retail';
import { summariseSales } from './retail-summary-mock';

// The mock of the sales summary follows the server's rules (issue #328): completed sales only in the figures, voided
// ones counted apart, profit only on request, newest period first, bounded. Fabricated sales.

const sale = (date: string, method: Sale['payment_method'], total: number, over: Partial<Sale> = {}): Sale =>
  ({ sale_date: date, payment_method: method, total_minor: total, profit_minor: total / 4, status: 'completed', ...over }) as Sale;
const sales = [
  sale('2026-10-19', 'cash', 2000),
  sale('2026-10-19', 'mobile_money', 4000),
  sale('2026-10-19', 'cash', 8000, { status: 'voided' }),
  sale('2026-09-30', 'credit', 2000),
  sale('2025-12-31', 'bank', 6000),
];

describe('summariseSales', () => {
  it('groups by year, newest first, with the methods and the voided count apart', () => {
    const r = summariseSales(sales, 'year', true);
    expect(r.items?.map((g) => g.period)).toEqual(['2026', '2025']);
    const y = r.items?.[0];
    expect([y?.sales_count, y?.total_minor, y?.profit_minor, y?.voided_count]).toEqual([3, 8000, 2000, 1]);
    expect(y?.by_method?.cash).toEqual({ count: 1, total_minor: 2000 });
    expect(y?.by_method?.credit).toEqual({ count: 1, total_minor: 2000 });
    expect([y?.from, y?.to]).toEqual(['2026-01-01', '2026-12-31']);
  });

  it('groups by month and by day with the period\'s own first and last day', () => {
    const months = summariseSales(sales, 'month', true).items ?? [];
    expect(months.map((g) => [g.period, g.from, g.to])).toEqual([
      ['2026-10', '2026-10-01', '2026-10-31'], ['2026-09', '2026-09-01', '2026-09-30'], ['2025-12', '2025-12-01', '2025-12-31'],
    ]);
    const days = summariseSales(sales, 'day', true).items ?? [];
    expect(days[0]).toMatchObject({ period: '2026-10-19', from: '2026-10-19', to: '2026-10-19', sales_count: 2, total_minor: 6000 });
  });

  it('leaves profit out without the permission', () => {
    expect(summariseSales(sales, 'year', false).items?.every((g) => g.profit_minor === undefined)).toBe(true);
  });

  it('stops at the bound of the level and says where to continue', () => {
    const many = Array.from({ length: 14 }, (_, i) => sale(`${2025 + Math.floor(i / 12)}-${String((i % 12) + 1).padStart(2, '0')}-15`, 'cash', 1000));
    const r = summariseSales(many, 'month', true);
    expect(r.items).toHaveLength(12);
    expect(r.has_more).toBe(true);
    expect(r.next_to).toBe('2025-02-28');
  });
});
