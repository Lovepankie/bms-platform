// The words of a group header (issue #328): the period in words, the sales count and the payment methods. A group header
// is a figure the server summed (GET /retail/sales/summary); nothing here adds up pages. The chain, what is open and the
// breadcrumb are in sales-chain-state.ts (issue #341).

export type Level = 'year' | 'month' | 'day';

/** A range of days, both ends included; an end that is absent is open. */
export interface DayRange {
  from?: string;
  to?: string;
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

/** "2026", "October 2026" or "Monday 19 October 2026" for a period key of that level. */
export function periodTitle(level: Level, period: string): string {
  const [y, m, d] = period.split('-');
  if (level === 'year' || !y) return period;
  const month = MONTHS[Number(m) - 1] ?? '';
  if (level === 'month') return `${month} ${y}`;
  const weekday = WEEKDAYS[new Date(`${period}T00:00:00Z`).getUTCDay()] ?? '';
  return `${weekday} ${Number(d)} ${month} ${y}`;
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
