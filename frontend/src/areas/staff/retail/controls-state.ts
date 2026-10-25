import type { ControlsCard, ControlsGroup, ControlsPattern, ControlsRecord, Metric } from '../../../api/retail-controls';
import { businessToday, daysBefore } from '../../../api/retail';
import { clockTime } from './cash-state';
import { showPercent } from './maths';
import { parseChain } from './sales-chain-state';

// The Controls page (issue #357, group 1; ADR-047): the pure rules behind the screen. The words are plain on purpose:
// "Sales cancelled", "Goods taken back", "per 100 sales". The page lists what happened and compares it with the period
// before; it does not judge anyone, so there is no ranking and no colour for "bad".

/**
 * The levels of the grouping chain: the shop, the person whose sales they were, the day, the reason given, and the approver
 * (who cancelled the sale or took the goods back). A cancelled sale is counted against the person who entered it, never against
 * the approver, who has a level of their own and appears nowhere in the patterns.
 */
export type ControlsLevel = 'branch' | 'user' | 'day' | 'reason' | 'approver';

/** The order a new user starts with. */
export const CONTROLS_LEVELS: readonly ControlsLevel[] = ['branch', 'user', 'day', 'reason', 'approver'];

export const LEVEL_NAMES: Record<ControlsLevel, string> = { branch: 'Shop', user: 'Person', day: 'Day', reason: 'Reason', approver: 'Approver' };
export const LEVEL_PLURALS: Record<ControlsLevel, string> = { branch: 'shops', user: 'people', day: 'days', reason: 'reasons', approver: 'approvers' };

/** The kinds of thing that are lines of a sale: a person who approved them is not recorded, so the Approver level is left out. */
const LINE_METRICS: readonly string[] = ['price_overrides', 'discounts', 'below_cost'];
export const isLineMetric = (metric: string): boolean => LINE_METRICS.includes(metric);

/** A restock voided or a quantity corrected: about the stock, not about sales, so counted with no rate and no sale to open. */
const STOCK_METRICS: readonly string[] = ['restock_voids', 'quantity_corrections'];
export const isStockMetric = (metric: string): boolean => STOCK_METRICS.includes(metric);

/** The chain as it is asked of the server for a kind of thing: no Approver level for the lines of a sale; never empty. */
export function chainFor(metric: string, chain: readonly ControlsLevel[]): ControlsLevel[] {
  const kept = isLineMetric(metric) || metric === 'quantity_corrections' ? chain.filter((l) => l !== 'approver') : [...chain];
  return kept.length > 0 ? kept : ['branch'];
}

/** The one label for a person the server does not know. */
export const UNKNOWN_PERSON = 'Unknown person';

export type PeriodKind = 'today' | 'week' | 'month' | 'custom';

/** The days of a period, both ends counted: today; Monday to today; the 1st to today; or the two days the person typed. */
export function periodRange(kind: PeriodKind, today: string, custom: { from: string; to: string }): { from: string; to: string } {
  if (kind === 'today') return { from: today, to: today };
  if (kind === 'week') {
    const weekday = new Date(`${today}T00:00:00Z`).getUTCDay();
    return { from: daysBefore(today, (weekday + 6) % 7), to: today };
  }
  if (kind === 'month') return { from: `${today.slice(0, 8)}01`, to: today };
  return custom;
}

/** The number of days in a range, both ends counted. */
export function daysIn(range: { from: string; to: string }): number {
  return Math.round((Date.parse(`${range.to}T00:00:00Z`) - Date.parse(`${range.from}T00:00:00Z`)) / 86_400_000) + 1;
}

/** "the day before", "the 7 days before": what the trend is set against. */
export function previousWords(range: { from: string; to: string }): string {
  const days = daysIn(range);
  return days === 1 ? 'the day before' : `the ${days} days before`;
}

export const WITHIN_CHOICES = [2, 5, 10, 15, 30, 60];

export interface MetricWords {
  metric: Metric;
  title: string;
  /** One plain sentence under the card or the table. */
  what: (within: number) => string;
  /** What a count of this metric counts: "sales", "lines". */
  noun: string;
}

export const METRICS: MetricWords[] = [
  { metric: 'voided_sales', title: 'Sales cancelled', noun: 'sales', what: () => 'Sales that were recorded and then cancelled (voided). The goods went back into stock.' },
  { metric: 'quick_cancels', title: 'Cancelled at once', noun: 'sales', what: (m) => `Sales cancelled within ${m} ${m === 1 ? 'minute' : 'minutes'} of being recorded, often a slip at the till.` },
  { metric: 'returns', title: 'Goods taken back', noun: 'returns', what: () => 'Customer returns, each with a credit note.' },
  { metric: 'credit_writeoffs', title: 'Credit written off', noun: 'sales', what: () => 'Sales on credit the buyer never paid, written off. The value is what the buyer still owed.' },
  { metric: 'price_overrides', title: 'Prices changed', noun: 'lines', what: () => 'Sale lines where the price typed was not the price the system chose. A reason is always given.' },
  { metric: 'discounts', title: 'Discounts given', noun: 'lines', what: () => 'Sale lines sold for less than the price the system chose. The money is what was given up.' },
  { metric: 'restock_voids', title: 'Purchases cancelled', noun: 'purchases', what: () => 'Purchases of stock entered wrongly and voided, one line for each shop the purchase stocked. The stock, the books and the prices went back. The value is the quantity at the selling price now. No rate: it is not about sales.' },
  { metric: 'quantity_corrections', title: 'Quantities corrected', noun: 'corrections', what: () => 'Stock quantities changed on the stock list as an entry mistake or a miscount. The value is the change at the selling price now. No rate: it is not about sales.' },
  { metric: 'below_cost', title: 'Sold below cost', noun: 'lines', what: () => 'Sale lines priced under what the item cost. Shown only to people who may read profit.' },
];

export const wordsOf = (metric: Metric): MetricWords => METRICS.find((m) => m.metric === metric) ?? (METRICS[0] as MetricWords);

/** The cards in the order the page draws them; a card the server did not send is not drawn. */
export const orderedCards = (cards: ControlsCard[] | undefined): ControlsCard[] =>
  METRICS.flatMap((m) => (cards ?? []).filter((c) => c.metric === m.metric));

/** "Up 3, from 5", "Down 2, from 7", "Same as before", "None, and none before", "New: 4, none before". */
export function trendWords(count: number, previous: number, before: string): string {
  if (count === 0 && previous === 0) return `None, and none in ${before}`;
  if (previous === 0) return `Up: ${count}, none in ${before}`;
  if (count === previous) return `Same as in ${before} (${previous})`;
  const change = Math.abs(count - previous);
  return `${count > previous ? 'Up' : 'Down'} ${change}, from ${previous} in ${before}`;
}

/** "2.5 per 100 sales"; plain words when there were no sales to set it against. */
export const rateWords = (rate: string | undefined): string => (rate === undefined ? 'No sales to compare with' : `${rate} per 100 sales`);

/** "1.2% of the value of sales", or nothing without a share. */
export const shareWords = (bp: number | undefined): string => (bp === undefined ? '' : `${showPercent(bp)} of the value of sales`);

/** What the person has open above a level: it travels to the server as filters. */
export interface Narrow {
  branchId?: string;
  userId?: string;
  approverId?: string;
  reason?: string;
  from?: string;
  to?: string;
}

/** The selection after opening `group` at `level`: the parent's plus what this group is. A day narrows to that day. */
export function selectGroup(sel: Narrow, level: ControlsLevel, group: Pick<ControlsGroup, 'key' | 'day'>): Narrow {
  switch (level) {
    case 'branch': return { ...sel, branchId: group.key };
    case 'user': return { ...sel, userId: group.key };
    case 'approver': return { ...sel, approverId: group.key };
    case 'reason': return { ...sel, reason: group.key };
    default: return { ...sel, from: group.day ?? group.key, to: group.day ?? group.key };
  }
}

/**
 * Whether a group can be opened. A shop, a person or an approver the server could not name has an empty key, and opening it
 * would ask for everybody's records, so it is shown and counted but not opened.
 */
export const canOpen = (level: ControlsLevel, g: Pick<ControlsGroup, 'key'>): boolean => level === 'reason' || level === 'day' || (g.key ?? '') !== '';

/** The title of a group row: the shop, the person (with a word when deactivated), the reason or the day in words. */
export function groupTitle(level: ControlsLevel, g: ControlsGroup): string {
  const label = g.label ?? (level === 'user' || level === 'approver' ? UNKNOWN_PERSON : (g.key ?? ''));
  if (level === 'user' || level === 'approver') return g.user_status === 'deactivated' ? `${label} (deactivated)` : g.user_status === 'invited' ? `${label} (not yet signed in)` : label;
  if (level === 'day') return dayWords(g.day ?? g.key ?? '');
  return label;
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

/** "Monday 19 October 2026" for a yyyy-mm-dd day. */
export function dayWords(day: string): string {
  const [y, m, d] = day.split('-');
  if (!y || !m || !d) return day;
  return `${WEEKDAYS[new Date(`${day}T00:00:00Z`).getUTCDay()] ?? ''} ${Number(d)} ${MONTHS[Number(m) - 1] ?? ''} ${y}`;
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/**
 * The one line under a group title: how many (lines are counted with the sales they are in), worth how much, and the rate,
 * which is always sales with such an event per 100 sales entered. An approver entered no sales to set a rate against.
 * `money` is the screen's formatter.
 */
export function groupLine(g: ControlsGroup, metric: string, level: ControlsLevel, money: (minor: number) => string): string {
  const count = g.count ?? 0;
  const sales = g.sales_affected ?? 0;
  if (isStockMetric(metric)) return `${metric === 'restock_voids' ? plural(count, 'restock', 'restocks') : plural(count, 'correction', 'corrections')}, ${money(g.value_minor ?? 0)}`;
  const head = isLineMetric(metric)
    ? `${plural(count, 'line', 'lines')} in ${plural(sales, 'sale', 'sales')}`
    : metric === 'returns' ? plural(count, 'return', 'returns') : plural(count, 'sale', 'sales');
  const parts = [head, money(g.value_minor ?? 0)];
  if (g.rate_per_100 !== undefined) parts.push(`${g.rate_per_100} per 100 sales`);
  else if (level !== 'approver') parts.push('no sales of their own to compare with');
  return parts.join(', ');
}

/** "Monday 19 October 2026, 14:05" for an instant, in the business zone; empty for anything that is not an instant. */
export function whenWords(at: string | undefined): string {
  const t = at ? Date.parse(at) : Number.NaN;
  return Number.isNaN(t) ? '' : `${dayWords(businessToday(new Date(t)))}, ${clockTime(at)}`;
}

/** The person whose sale it was, as a record shows them. */
export const whoWords = (r: Pick<ControlsRecord, 'user_name' | 'user_status'>): string => {
  const name = r.user_name ?? UNKNOWN_PERSON;
  return r.user_status === 'deactivated' ? `${name} (deactivated)` : name;
};

/** The word before an approver's name; a sale is otherwise cancelled. */
const APPROVED: Record<string, string> = { returns: 'Taken back', credit_writeoffs: 'Written off', restock_voids: 'Voided' };

/** "Cancelled by Test Admin One", "Taken back by ...", "Written off by ..." or "Voided by ..."; empty for a price, a cost or a correction, which have no approver. */
export function approverWords(r: Pick<ControlsRecord, 'approver_id' | 'approver_name' | 'approver_status'>, metric: string): string {
  if (!r.approver_id) return '';
  const name = r.approver_name ?? UNKNOWN_PERSON;
  return `${APPROVED[metric] ?? 'Cancelled'} by ${r.approver_status === 'deactivated' ? `${name} (deactivated)` : name}`;
}

const SALE_ENDING: Record<string, string> = {
  voided_sales: 'were cancelled',
  returns: 'had goods taken back',
  price_overrides: 'had a price changed',
  below_cost: 'were sold below cost',
};

/**
 * A repeat pattern in plain words. A person is the one whose sales they were, never the one who cancelled them:
 * "5 of the 13 sales Test Seller One entered at Head Office were cancelled (38.5 per 100). The shop's own is 14.0, so 2.8
 * times." An item: the lines of the item that were on such sales.
 */
export function patternSentence(p: ControlsPattern, shop: string): string {
  const rest = `(${p.rate_per_100} per 100). The shop's own is ${p.shop_rate_per_100}, so ${p.times} times.`;
  const count = p.count ?? 0;
  const outOf = p.out_of ?? 0;
  if (p.subject === 'user') {
    const name = p.user_status === 'deactivated' ? `${p.name} (deactivated)` : (p.name ?? UNKNOWN_PERSON);
    return `${count} of the ${outOf} sales ${name} entered at ${shop} ${SALE_ENDING[p.metric ?? ''] ?? 'were flagged'} ${rest}`;
  }
  const lines = p.metric === 'voided_sales' ? 'were on cancelled sales' : p.metric === 'below_cost' ? 'had a price under cost' : 'had the price changed';
  return `${count} of the ${outOf} lines of ${p.name} sold at ${shop} ${lines} ${rest}`;
}

/** Where the person's chosen chain is kept: per user, never an email or a phone number. */
const storeKey = (user: string) => `bms.controlsChain.${user}`;

export function readControlsChain(user: string): ControlsLevel[] {
  try {
    return parseChain(localStorage.getItem(storeKey(user)), CONTROLS_LEVELS) ?? [...CONTROLS_LEVELS];
  } catch {
    return [...CONTROLS_LEVELS];
  }
}

export function writeControlsChain(user: string, chain: readonly ControlsLevel[]): void {
  try {
    localStorage.setItem(storeKey(user), JSON.stringify(chain));
  } catch {
    // Blocked storage: the choice lasts until the page is left.
  }
}

/** One CSV field: quoted when it holds a comma, quote or line break; a text a spreadsheet would run as a formula is made plain text. */
export function csvField(value: string | number | undefined): string {
  let text = value === undefined ? '' : String(value);
  if (/^[=+\-@\t\r]/.test(text) && typeof value === 'string') text = `'${text}`;
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

export const csvOf = (rows: (string | number | undefined)[][]): string => `${rows.map((r) => r.map(csvField).join(',')).join('\r\n')}\r\n`;

/** The groups on screen as a CSV: the name, how many, the value, the sales entered and the rate. */
export function groupsCsv(level: ControlsLevel, groups: ControlsGroup[], metric = 'voided_sales'): string {
  if (isStockMetric(metric)) {
    // Not about sales: no sales to count, no rate.
    return csvOf([[LEVEL_NAMES[level], 'Count', 'Value'], ...groups.map((g) => [groupTitle(level, g), g.count ?? 0, g.value_minor ?? 0])]);
  }
  return csvOf([
    [LEVEL_NAMES[level], 'Count', 'Sales with it', 'Value', 'Sales entered', 'Per 100 sales'],
    ...groups.map((g) => [groupTitle(level, g), g.count ?? 0, g.sales_affected ?? 0, g.value_minor ?? 0, g.sales_count ?? 0, g.rate_per_100]),
  ]);
}

/** The records on screen as a CSV. */
export function recordsCsv(records: ControlsRecord[], metric: string): string {
  const who = metric === 'restock_voids' ? 'Entered by' : metric === 'quantity_corrections' ? 'Corrected by' : 'Person (whose sale)';
  const number = metric === 'restock_voids' ? 'Purchase' : metric === 'quantity_corrections' ? 'Record' : 'Sale';
  if (isStockMetric(metric)) {
    // No credit note to give.
    return csvOf([
      [number, 'Time', 'Shop', who, 'Approver', 'Reason', 'Item', 'Value'],
      ...records.map((r) => [r.sale_no, whenWords(r.at), r.branch_name, whoWords(r), approverWords(r, metric), r.reason, r.item, r.value_minor ?? 0]),
    ]);
  }
  return csvOf([
    [number, 'Credit note', 'Time', 'Shop', who, 'Approver', 'Reason', 'Item', 'Value'],
    ...records.map((r) => [r.sale_no, r.reference, whenWords(r.at), r.branch_name, whoWords(r), approverWords(r, metric), r.reason, r.item, r.value_minor ?? 0]),
  ]);
}
