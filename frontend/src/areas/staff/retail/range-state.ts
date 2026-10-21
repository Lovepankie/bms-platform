import { daysBefore } from '../../../api/retail';

// The range of the Retail home (issue #428): Today, 7 days, 30 days, This year, 1 year, All, or two days the person picks. The
// owner's dashboard asks the server for the range by name; a seller's own figures are read from the sales summary, so the days
// are worked out here by the same rules as the server (DashboardRange): the period before is as long as the range, and This
// year is set against the same days of last year. The choice is remembered per person in this browser.

export type RangePeriod = 'today' | '7d' | '30d' | 'ytd' | '1y' | 'all' | 'custom';

export interface RangeChoice {
  period: RangePeriod;
  /** Custom only: yyyy-mm-dd, both ends counted. */
  from?: string;
  to?: string;
}

export const RANGE_CHOICES: { period: RangePeriod; label: string }[] = [
  { period: 'today', label: 'Today' },
  { period: '7d', label: '7 days' },
  { period: '30d', label: '30 days' },
  { period: 'ytd', label: 'This year' },
  { period: '1y', label: '1 year' },
  { period: 'all', label: 'All' },
  { period: 'custom', label: 'Choose days' },
];

export const DEFAULT_RANGE: RangeChoice = { period: '7d' };

/** The longest custom range, as every other dated report (366 days, both ends counted). */
export const MAX_CUSTOM_DAYS = 366;

const DATE = /^\d{4}-\d{2}-\d{2}$/;

const validDate = (d: unknown): d is string => typeof d === 'string' && DATE.test(d) && !Number.isNaN(Date.parse(`${d}T00:00:00Z`));

/** The number of days from one date to another, both ends counted. */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1;
}

/** Why two typed days are not a range, or null when they are. */
export function customProblem(from: string, to: string, today?: string): string | null {
  if (!validDate(from) || !validDate(to)) return 'Pick both days.';
  if (from > to) return 'The first day is after the last day.';
  if (today && to > today) return 'The last day cannot be after today.';
  if (daysBetween(from, to) > MAX_CUSTOM_DAYS) return 'Pick at most 366 days.';
  return null;
}

/** A stored choice read back, or null when it is not one (an old or changed value). */
export function parseRange(raw: string | null): RangeChoice | null {
  if (!raw) return null;
  try {
    const v = JSON.parse(raw) as Partial<RangeChoice>;
    if (!RANGE_CHOICES.some((c) => c.period === v.period)) return null;
    if (v.period !== 'custom') return { period: v.period as RangePeriod };
    return v.from && v.to && customProblem(v.from, v.to) === null ? { period: 'custom', from: v.from, to: v.to } : null;
  } catch {
    return null;
  }
}

// Per tenant and person: the tenant is the host (or the dev server's X-Tenant slug), as for the chosen branch.
function storeKey(userId: string): string {
  const env = import.meta.env;
  const tenant = env.DEV && env.VITE_DEV_TENANT ? env.VITE_DEV_TENANT : window.location.hostname;
  return `bms.homeRange.${tenant}.${userId}`;
}

export function readRange(userId: string): RangeChoice {
  try {
    return parseRange(window.localStorage.getItem(storeKey(userId))) ?? DEFAULT_RANGE;
  } catch {
    return DEFAULT_RANGE;
  }
}

export function writeRange(userId: string, choice: RangeChoice): void {
  try {
    window.localStorage.setItem(storeKey(userId), JSON.stringify(choice));
  } catch {
    // Blocked storage: the choice lasts until the page is left.
  }
}

/** The query of the dashboard for a choice. */
export function rangeQuery(choice: RangeChoice): { period: RangePeriod; from?: string; to?: string } {
  return choice.period === 'custom' ? { period: 'custom', from: choice.from, to: choice.to } : { period: choice.period };
}

export interface RangeDays {
  /** Absent for All: from the first sale. */
  from?: string;
  to: string;
  previous?: { from: string; to: string };
}

const minusYear = (d: string): string => {
  const y = `${Number(d.slice(0, 4)) - 1}${d.slice(4)}`;
  // 29 February of last year is the 28th, as java.time has it.
  return d.endsWith('-02-29') ? `${y.slice(0, 8)}28` : y;
};

/** The days of a choice and of the period before it, by the rules of the server. */
export function rangeDays(choice: RangeChoice, today: string): RangeDays {
  const before = (from: string, to: string): RangeDays => {
    const n = daysBetween(from, to);
    return { from, to, previous: { from: daysBefore(from, n), to: daysBefore(from, 1) } };
  };
  switch (choice.period) {
    case 'today':
      return before(today, today);
    case '7d':
      return before(daysBefore(today, 6), today);
    case '30d':
      return before(daysBefore(today, 29), today);
    case 'ytd': {
      const jan1 = `${today.slice(0, 4)}-01-01`;
      return { from: jan1, to: today, previous: { from: minusYear(jan1), to: minusYear(today) } };
    }
    case '1y': {
      const from = daysBefore(minusYear(today), -1);
      return { from, to: today, previous: { from: minusYear(from), to: daysBefore(from, 1) } };
    }
    case 'all':
      return { to: today };
    default:
      return choice.from && choice.to ? before(choice.from, choice.to) : before(today, today);
  }
}

/** Words for the period before: "yesterday", "the 7 days before", "the same days last year". */
export function previousWords(choice: RangeChoice, days: RangeDays): string {
  if (choice.period === 'ytd') return 'the same days last year';
  if (choice.period === '1y') return 'the year before';
  if (!days.from) return '';
  const n = daysBetween(days.from, days.to);
  if (n === 1) return choice.period === 'today' ? 'yesterday' : 'the day before';
  return `the ${n} days before`;
}

/** The heading of a range: "Today", "Last 7 days", "1 Oct to 21 Oct 2026". */
export function rangeTitle(choice: RangeChoice, days: RangeDays): string {
  switch (choice.period) {
    case 'today':
      return 'Today';
    case '7d':
      return 'Last 7 days';
    case '30d':
      return 'Last 30 days';
    case 'ytd':
      return 'This year';
    case '1y':
      return 'Last 12 months';
    case 'all':
      return days.from ? `Since ${shortDate(days.from)}` : 'All time';
    default:
      return days.from ? `${shortDate(days.from)} to ${shortDate(days.to)}` : 'Chosen days';
  }
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/** "21 Oct 2026". */
export function shortDate(d: string): string {
  return `${Number(d.slice(8, 10))} ${MONTHS[Number(d.slice(5, 7)) - 1]} ${d.slice(0, 4)}`;
}

/** What one step of the chart is: "Tue 21 Oct", "Week from 14 Oct", "Oct 2026", "2026". */
export function stepWords(start: string, group: string): string {
  if (group === 'year') return start.slice(0, 4);
  if (group === 'month') return `${MONTHS[Number(start.slice(5, 7)) - 1]} ${start.slice(0, 4)}`;
  const day = `${Number(start.slice(8, 10))} ${MONTHS[Number(start.slice(5, 7)) - 1]}`;
  if (group === 'week') return `Week from ${day}`;
  return `${WEEKDAYS[new Date(`${start}T00:00:00Z`).getUTCDay()]} ${day}`;
}

export interface Change {
  /** Now less before, in minor units. */
  amount: number;
  /** In whole percent of before, rounded; null when there was nothing before. */
  percent: number | null;
  direction: 'up' | 'down' | 'same';
}

export function changeOf(now: number, before: number): Change {
  const amount = now - before;
  return {
    amount,
    percent: before > 0 ? Math.round((amount * 100) / before) : null,
    direction: amount > 0 ? 'up' : amount < 0 ? 'down' : 'same',
  };
}
