import { describe, expect, it } from 'vitest';
import { allocationSummary, localDate, money, parseAmount, statusBadge, txnWords, words } from './loan-state';

describe('amounts', () => {
  it('parses what a person typed into integer minor units above zero', () => {
    expect(parseAmount('50000', 'UGX')).toBe(50000);
    expect(parseAmount('1,250,000', 'UGX')).toBe(1250000);
    expect(parseAmount(' 2000 ', undefined)).toBe(2000);
    expect(parseAmount('12.50', 'USD')).toBe(1250);
  });

  it('refuses zero, negatives, decimals the currency has not, and words', () => {
    for (const text of ['', '0', '-500', '12.5', 'abc', '1e5']) expect(parseAmount(text, 'UGX')).toBeNull();
    expect(parseAmount('12.505', 'USD')).toBeNull();
    expect(parseAmount('100', 'XXX')).toBeNull();
  });

  it('formats money in the loan currency, without floats', () => {
    expect(money(1250000, 'UGX')).toBe('UGX 1,250,000');
    expect(money(0, undefined)).toBe('UGX 0');
    expect(money(-5000, 'UGX')).toBe('-UGX 5,000');
    expect(money(undefined, 'UGX')).toBe('None');
    expect(money(100, 'XXX')).toBe('XXX 100');
  });
});

describe('allocation summary', () => {
  it('sums the rows by component in allocation order and leaves out empty ones', () => {
    const rows = [
      { component: 'principal', item_no: 1, amount_minor: 40000 },
      { component: 'interest', item_no: 1, amount_minor: 8000 },
      { component: 'principal', item_no: 2, amount_minor: 10000 },
      { component: 'penalty', item_no: 1, amount_minor: 2000 },
      { component: 'fee', item_no: 1, amount_minor: 0 },
      { component: 'overpayment', amount_minor: 5000 },
    ];
    expect(allocationSummary(rows)).toEqual([
      { label: 'Penalties', amountMinor: 2000 },
      { label: 'Interest', amountMinor: 8000 },
      { label: 'Principal', amountMinor: 50000 },
      { label: 'Held as credit (overpayment)', amountMinor: 5000 },
    ]);
  });

  it('keeps a component it does not know, and copes with nothing', () => {
    expect(allocationSummary([{ component: 'rebate_adjustment', amount_minor: 100 }])).toEqual([{ label: 'Rebate adjustment', amountMinor: 100 }]);
    expect(allocationSummary(undefined)).toEqual([]);
  });
});

describe('words and dates', () => {
  it('turns codes into words', () => {
    expect(words('written_off')).toBe('Written off');
    expect(txnWords('fee_upfront')).toBe('Upfront fee');
    expect(txnWords('restructure_in')).toBe('Restructure in');
    expect(statusBadge('written_off')).toContain('badge-danger');
    expect(statusBadge('draft')).toBe('badge');
  });

  it('gives the local calendar date', () => {
    expect(localDate(new Date(2026, 9, 6, 23, 30))).toBe('2026-10-06');
    expect(localDate(new Date(2026, 0, 2, 0, 5))).toBe('2026-01-02');
  });
});
