import { describe, expect, it } from 'vitest';
import { childRange, defaultOpen, emptyWords, groupKey, methodLines, periodTitle, salesWord, shutGroup, toggleGroup } from './sales-grouped-state';

// The rules behind the grouped sales lists (issue #328). Dates and amounts are fabricated.

describe('what is open', () => {
  it('starts with this year and this month, so the days of this month are listed', () => {
    expect([...defaultOpen('2026-10-19')].sort()).toEqual(['month:2026-10', 'year:2026']);
  });

  it('opens a group when tapped and shuts it, with everything inside, when tapped again', () => {
    const open = new Set(['year:2026', 'month:2026-10', 'day:2026-10-19', 'month:2026-09', 'year:2025']);
    const shut = toggleGroup(open, 'month:2026-10');
    expect([...shut].sort()).toEqual(['month:2026-09', 'year:2025', 'year:2026']);
    expect(toggleGroup(shut, 'month:2026-10').has('month:2026-10')).toBe(true);
    expect([...toggleGroup(open, 'year:2026')].sort()).toEqual(['year:2025']);
  });

  it('does not touch the set it was given', () => {
    const open = new Set(['year:2026']);
    toggleGroup(open, 'year:2026');
    expect(open.has('year:2026')).toBe(true);
  });

  it('shuts a group from a breadcrumb only when it is open', () => {
    const open = new Set(['year:2026', 'month:2026-10', 'day:2026-10-19']);
    expect([...shutGroup(open, 'day:2026-10-19')].sort()).toEqual(['month:2026-10', 'year:2026']);
    expect([...shutGroup(open, 'day:2026-10-18')].sort()).toEqual([...open].sort());
  });

  it('builds the key of a group from its level and period', () => {
    expect(groupKey('day', '2026-10-19')).toBe('day:2026-10-19');
  });
});

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

  it('says plainly what an empty level is', () => {
    expect(emptyWords('day', false)).toBe('No sales on this day.');
    expect(emptyWords('month', true)).toBe('No credit sales in this month.');
  });
});

describe('the days a group asks for when it is opened', () => {
  it('are the group\'s own days when the range is wider', () => {
    expect(childRange({ from: '2026-10-01', to: '2026-10-31' }, { from: '2026-01-01', to: '2026-12-31' })).toEqual({ from: '2026-10-01', to: '2026-10-31' });
  });

  it('are cut by the range when the range ends inside the group', () => {
    expect(childRange({ from: '2026-09-01', to: '2026-09-30' }, { from: '2026-09-20', to: '2026-10-19' })).toEqual({ from: '2026-09-20', to: '2026-09-30' });
    expect(childRange({ from: '2026-10-01', to: '2026-10-31' }, { from: '2026-09-20', to: '2026-10-19' })).toEqual({ from: '2026-10-01', to: '2026-10-19' });
  });

  it('are the group\'s own days when there is no range', () => {
    expect(childRange({ from: '2026-01-01', to: '2026-12-31' }, {})).toEqual({ from: '2026-01-01', to: '2026-12-31' });
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
