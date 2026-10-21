import { describe, expect, it } from 'vitest';
import { DEFAULT_RANGE, changeOf, customProblem, parseRange, previousWords, rangeDays, rangeQuery, rangeTitle, stepWords } from './range-state';

// The range of the Retail home (issue #428): the days of each choice by the server's rules, the stored choice read back, the
// change on the period before. Fabricated dates.

describe('the days of a range', () => {
  const today = '2026-10-21';

  it('sets each choice against the period before, as the server does', () => {
    expect(rangeDays({ period: 'today' }, today)).toEqual({ from: today, to: today, previous: { from: '2026-10-20', to: '2026-10-20' } });
    expect(rangeDays({ period: '7d' }, today)).toEqual({ from: '2026-10-15', to: today, previous: { from: '2026-10-08', to: '2026-10-14' } });
    expect(rangeDays({ period: '30d' }, today).previous).toEqual({ from: '2026-08-23', to: '2026-09-21' });
    expect(rangeDays({ period: 'ytd' }, today)).toEqual({ from: '2026-01-01', to: today, previous: { from: '2025-01-01', to: '2025-10-21' } });
    expect(rangeDays({ period: '1y' }, today)).toEqual({ from: '2025-10-22', to: today, previous: { from: '2024-10-22', to: '2025-10-21' } });
    expect(rangeDays({ period: 'all' }, today)).toEqual({ to: today });
    expect(rangeDays({ period: 'custom', from: '2026-10-01', to: '2026-10-10' }, today).previous).toEqual({ from: '2026-09-21', to: '2026-09-30' });
  });

  it('takes 29 February of last year as the 28th', () => {
    expect(rangeDays({ period: 'ytd' }, '2028-02-29').previous).toEqual({ from: '2027-01-01', to: '2027-02-28' });
  });

  it('names the range and the period before in plain words', () => {
    expect(previousWords({ period: 'today' }, rangeDays({ period: 'today' }, today))).toBe('yesterday');
    expect(previousWords({ period: '7d' }, rangeDays({ period: '7d' }, today))).toBe('the 7 days before');
    expect(previousWords({ period: 'ytd' }, rangeDays({ period: 'ytd' }, today))).toBe('the same days last year');
    expect(rangeTitle({ period: 'custom' }, { from: '2026-10-01', to: '2026-10-10' })).toBe('1 Oct 2026 to 10 Oct 2026');
    expect(rangeTitle({ period: 'all' }, { from: '2025-09-16', to: today })).toBe('Since 16 Sep 2025');
    expect(stepWords('2026-10-21', 'day')).toBe('Wed 21 Oct');
    expect(stepWords('2026-10-14', 'week')).toBe('Week from 14 Oct');
    expect(stepWords('2026-10-01', 'month')).toBe('Oct 2026');
  });

  it('asks the dashboard by name, and by days only for a custom range', () => {
    expect(rangeQuery({ period: 'ytd' })).toEqual({ period: 'ytd' });
    expect(rangeQuery({ period: 'custom', from: '2026-10-01', to: '2026-10-10' })).toEqual({ period: 'custom', from: '2026-10-01', to: '2026-10-10' });
  });
});

describe('the stored choice', () => {
  it('is read back only when it is still a choice', () => {
    expect(parseRange(JSON.stringify({ period: '30d' }))).toEqual({ period: '30d' });
    expect(parseRange(JSON.stringify({ period: '5y' }))).toBeNull();
    expect(parseRange('not json')).toBeNull();
    expect(parseRange(null)).toBeNull();
    expect(parseRange(JSON.stringify({ period: 'custom', from: '2026-10-10', to: '2026-10-01' }))).toBeNull();
    expect(parseRange(JSON.stringify({ period: 'custom', from: '2026-10-01', to: '2026-10-10' }))).toEqual({ period: 'custom', from: '2026-10-01', to: '2026-10-10' });
    expect(DEFAULT_RANGE).toEqual({ period: '7d' });
  });

  it('refuses custom days that are not a range of at most 366 days', () => {
    expect(customProblem('', '2026-10-01')).toBe('Pick both days.');
    expect(customProblem('2026-10-02', '2026-10-01')).toBe('The first day is after the last day.');
    expect(customProblem('2025-01-01', '2026-10-01')).toBe('Pick at most 366 days.');
    expect(customProblem('2025-10-21', '2026-10-21')).toBeNull();
    expect(customProblem('2026-10-01', '2026-10-22', '2026-10-21')).toBe('The last day cannot be after today.');
    expect(customProblem('2026-10-01', '2026-10-21', '2026-10-21')).toBeNull();
  });
});

describe('the change on the period before', () => {
  it('is up, down or the same, in amount and whole percent', () => {
    expect(changeOf(12_000, 10_000)).toEqual({ amount: 2_000, percent: 20, direction: 'up' });
    expect(changeOf(7_500, 10_000)).toEqual({ amount: -2_500, percent: -25, direction: 'down' });
    expect(changeOf(5_000, 5_000)).toEqual({ amount: 0, percent: 0, direction: 'same' });
    expect(changeOf(5_000, 0)).toEqual({ amount: 5_000, percent: null, direction: 'up' });
  });
});
