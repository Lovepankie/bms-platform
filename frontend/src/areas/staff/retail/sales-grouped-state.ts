// The sales lists grouped by year, month and day (issue #328): the pure rules behind the screen. A group header is
// a figure the server summed (GET /retail/sales/summary); nothing here adds up pages. What is open is a set of keys
// like "year:2026", "month:2026-10" and "day:2026-10-19".

export type Level = 'year' | 'month' | 'day';

/** The level under a year or a month; a day opens onto the sales themselves. */
export const CHILD_LEVEL: Record<'year' | 'month', Level> = { year: 'month', month: 'day' };

/** A range of days, both ends included; an end that is absent is open. */
export interface DayRange {
  from?: string;
  to?: string;
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

export const groupKey = (level: Level, period: string) => `${level}:${period}`;

/** The groups open when the screen first shows: this year and this month, so the days of this month are listed. */
export function defaultOpen(today: string): Set<string> {
  return new Set([groupKey('year', today.slice(0, 4)), groupKey('month', today.slice(0, 7))]);
}

/** The set with `key` opened if it was shut, or shut (with everything inside it) if it was open. */
export function toggleGroup(open: ReadonlySet<string>, key: string): Set<string> {
  const next = new Set(open);
  if (next.has(key)) {
    next.delete(key);
    // A shut group takes the groups inside it with it, so opening it again starts from its days or months.
    const inside = key.slice(key.indexOf(':') + 1);
    for (const k of open) if (k !== key && k.slice(k.indexOf(':') + 1).startsWith(`${inside}-`)) next.delete(k);
  } else {
    next.add(key);
  }
  return next;
}

/** The set with `key` and everything inside it shut: the way back up from a breadcrumb. */
export function shutGroup(open: ReadonlySet<string>, key: string): Set<string> {
  return open.has(key) ? toggleGroup(open, key) : new Set(open);
}

/** "2026", "October 2026" or "Monday 19 October 2026" for a period key of that level. */
export function periodTitle(level: Level, period: string): string {
  const [y, m, d] = period.split('-');
  if (level === 'year' || !y) return period;
  const month = MONTHS[Number(m) - 1] ?? '';
  if (level === 'month') return `${month} ${y}`;
  const weekday = WEEKDAYS[new Date(`${period}T00:00:00Z`).getUTCDay()] ?? '';
  return `${weekday} ${Number(d)} ${month} ${y}`;
}

/** The days a group's children are asked for: the group's own days, narrowed by the range the screen is showing. */
export function childRange(group: DayRange, range: DayRange): { from: string; to: string } {
  const from = [group.from, range.from].filter((x): x is string => !!x).sort().at(-1) ?? '';
  const to = [group.to, range.to].filter((x): x is string => !!x).sort().at(0) ?? '';
  return { from, to };
}

export const salesWord = (n: number) => (n === 1 ? '1 sale' : `${n} sales`);

export type MethodName = 'cash' | 'mobile_money' | 'bank' | 'credit';
export interface MethodLine {
  method: MethodName;
  label: string;
  count: number;
  totalMinor: number;
}

const METHOD_LABELS: [MethodName, string][] = [
  ['cash', 'Cash'],
  ['mobile_money', 'Mobile money'],
  ['bank', 'Bank'],
  ['credit', 'Credit'],
];

/** The payment methods a group used, in a fixed order; a method with no sales is left out. */
export function methodLines(by: Partial<Record<MethodName, { count?: number; total_minor?: number }>> | undefined): MethodLine[] {
  return METHOD_LABELS.flatMap(([method, label]) => {
    const f = by?.[method];
    const count = f?.count ?? 0;
    return count > 0 ? [{ method, label, count, totalMinor: f?.total_minor ?? 0 }] : [];
  });
}

/** The words for a level that has nothing in it. */
export function emptyWords(level: Level, credit: boolean): string {
  const what = credit ? 'credit sales' : 'sales';
  return level === 'day' ? `No ${what} on this day.` : `No ${what} in this ${level}.`;
}
