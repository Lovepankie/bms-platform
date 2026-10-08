import { describe, expect, it } from 'vitest';
import { compactMinor, formatValue, percent, presetRange, words } from './format';
import { canExport, canManageDigest, ownPortfolioOnly, showInsights } from './permissions';

describe('insights formatting', () => {
  it('shows basis points to one decimal, rounded half up on the integer', () => {
    expect(percent(1250)).toBe('12.5%');
    expect(percent(1255)).toBe('12.6%');
    expect(percent(10000)).toBe('100.0%');
    expect(percent(4)).toBe('0.0%');
    expect(percent(5)).toBe('0.1%');
  });

  it('formats a value by its kind and shows a dash for an empty one', () => {
    expect(formatValue(1250000, 'money', 'UGX')).toBe('UGX 1,250,000');
    expect(formatValue(125050, 'money', 'KES')).toBe('KES 1,250.50');
    expect(formatValue(800, 'basis_points')).toBe('8.0%');
    expect(formatValue(1, 'days')).toBe('1 day');
    expect(formatValue(36, 'hours')).toBe('36 hours');
    expect(formatValue(1234, 'count')).toBe('1,234');
    expect(formatValue(null, 'basis_points')).toBe('-');
  });

  it('shortens axis labels only', () => {
    expect(compactMinor(1_250_000, 'UGX')).toBe('1.3M');
    expect(compactMinor(350_000, 'UGX')).toBe('350K');
    expect(compactMinor(2_000_000_000, 'UGX')).toBe('2B');
    expect(compactMinor(125_000, 'KES')).toBe('1.3K');
  });

  it('builds the quick ranges ending today', () => {
    const today = new Date(2026, 9, 6);
    expect(presetRange('month', today)).toEqual({ from: '2026-10-01', to: '2026-10-06' });
    expect(presetRange('7d', today)).toEqual({ from: '2026-09-30', to: '2026-10-06' });
    expect(presetRange('year', today)).toEqual({ from: '2025-10-07', to: '2026-10-06' });
    expect(words('written_off')).toBe('Written off');
  });
});

describe('insights permissions', () => {
  const me = (permissions: string[]) => ({ permissions });
  it('offers the page, the export and the digest by permission', () => {
    expect(showInsights(me(['lending.insights.read']))).toBe(true);
    expect(showInsights({ permissions: ['lending.insights.read'], modules: ['retail'] })).toBe(false);
    expect(showInsights(me(['lending.loans.read']))).toBe(false);
    expect(ownPortfolioOnly(me(['lending.insights.read']))).toBe(true);
    expect(ownPortfolioOnly(me(['lending.insights.read', 'lending.insights.all_officers']))).toBe(false);
    expect(canExport(me(['lending.insights.export']))).toBe(true);
    expect(canManageDigest(me(['core.settings.manage']))).toBe(true);
  });
});
