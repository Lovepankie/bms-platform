import { describe, expect, it } from 'vitest';
import { methodLines, periodTitle, salesWord } from './sales-grouped-state';

// The words of a group header (issue #328). Dates and amounts are fabricated. What is open, the chain and the days a
// group asks for are tested in sales-chain-state.test.ts (issue #341).

describe('the words of a period', () => {
  it('names a year, a month and a day', () => {
    expect(periodTitle('year', '2026')).toBe('2026');
    expect(periodTitle('month', '2026-10')).toBe('October 2026');
    expect(periodTitle('day', '2026-10-19')).toBe('Monday 19 October 2026');
    expect(periodTitle('day', '2026-02-01')).toBe('Sunday 1 February 2026');
  });

  it('counts sales in the singular and the plural', () => {
    expect(salesWord(1)).toBe('1 sale');
    expect(salesWord(0)).toBe('0 sales');
    expect(salesWord(12)).toBe('12 sales');
  });
});

describe('the payment methods of a group', () => {
  it('lists the methods used, in a fixed order, with their counts and amounts', () => {
    const lines = methodLines({ credit: { count: 1, total_minor: 2000 }, cash: { count: 2, total_minor: 4000 }, bank: { count: 0, total_minor: 0 } });
    expect(lines.map((l) => [l.label, l.count, l.totalMinor])).toEqual([['Cash', 2, 4000], ['Credit', 1, 2000]]);
  });

  it('is empty when nothing was sold or the server sent no breakdown', () => {
    expect(methodLines(undefined)).toEqual([]);
    expect(methodLines({ cash: { count: 0, total_minor: 0 } })).toEqual([]);
  });
});
