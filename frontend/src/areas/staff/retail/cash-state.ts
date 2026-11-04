import { RETAIL_CURRENCY, businessZone } from '../../../api/retail';
import { parseMinor } from '../../../components/money';

// Plain logic shared by the cash book screens (ADR-022): grouping of records, amounts typed by people,
// the order and wording of what the server sends. No figure is ever computed that the server left out.

/** An amount typed in whole money units as integer minor units, or null when it is not one above zero. */
export function amountOrNull(text: string): number | null {
  const minor = parseMinor(text, RETAIL_CURRENCY);
  return minor !== null && minor > 0 ? minor : null;
}

/** The shortest reason the server takes for an entry dated before today (ADR-120). */
export const MIN_DATE_REASON = 3;

/**
 * The date of a new entry (ADR-120, issue #811): `mayChoose` (the owner's permission at the branch) shows the box; `day` is
 * today unless the owner chose an earlier one, which needs a reason; `sent` is what to send, nothing for today, so the server
 * dates the entry itself.
 * JUSTIFICATION-A3: one rule shared by seven forms; no existing helper decides an entry's date.
 */
export interface EntryDate { mayChoose: boolean; day: string; earlier: boolean; reasonMissing: boolean; sent: { date: string; reason: string } | null }

export function entryDate(mayChoose: boolean, chosen: string | undefined, reason: string | undefined, today: string): EntryDate {
  const day = mayChoose && chosen && chosen <= today ? chosen : today;
  const earlier = day < today;
  const why = (reason ?? '').trim();
  return { mayChoose, day, earlier, reasonMissing: earlier && why.length < MIN_DATE_REASON, sent: earlier ? { date: day, reason: why } : null };
}

/** The request fields of an entry's date: the owner's earlier day under `field` with `date_reason`, else none. */
export const dateFields = (entry: EntryDate, field = 'business_date'): Record<string, string> =>
  entry.sent ? { [field]: entry.sent.date, date_reason: entry.sent.reason } : {};

const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/** "October 2026" from "2026-10-06" (or "2026-10"); the input unchanged when it is neither. */
export function monthLabel(date: string | undefined): string {
  const m = /^(\d{4})-(\d{2})/.exec(date ?? '');
  return m ? `${MONTH_NAMES[Number(m[2]) - 1] ?? m[2]} ${m[1]}` : (date ?? '');
}

export interface MonthGroup<T> { year: string; month: string; label: string; rows: T[] }
export interface ShopGroup<T> { branchId: string; shop: string; months: MonthGroup<T>[] }

/** Records grouped by shop, then year and month, the shops by name and the months newest first (FR-RET-31). */
export function groupByShopMonth<T extends { branch_id?: string; business_date?: string }>(rows: T[], shopName: (id: string | undefined) => string): ShopGroup<T>[] {
  const shops = new Map<string, ShopGroup<T>>();
  for (const row of rows) {
    const id = row.branch_id ?? '';
    const shop = shops.get(id) ?? { branchId: id, shop: shopName(id), months: [] };
    const date = row.business_date ?? '';
    const year = date.slice(0, 4);
    const month = date.slice(5, 7);
    let group = shop.months.find((g) => g.year === year && g.month === month);
    if (!group) {
      group = { year, month, label: monthLabel(date), rows: [] };
      shop.months.push(group);
    }
    group.rows.push(row);
    shops.set(id, shop);
  }
  return [...shops.values()]
    .sort((a, b) => a.shop.localeCompare(b.shop))
    .map((s) => ({ ...s, months: s.months.sort((a, b) => `${b.year}-${b.month}`.localeCompare(`${a.year}-${a.month}`)) }));
}

// JUSTIFICATION-A3: issue #816 asks for a total at every level of the cash book lists; groupByShopMonth has no totals and fixed levels.
/** A cash book record as the grouped list needs it. */
export interface CashRowLike { branch_id?: string; business_date?: string; voided?: boolean; historical?: boolean }

/** What a list sums (issue #816): the amount of a record, the part still owed, and the category of an expense. */
export interface CashFigures<T> {
  amount?: (row: T) => number | undefined;
  owed?: (row: T) => number | undefined;
  category?: (row: T) => { id?: string; name?: string };
}

/** One group of a cash book list with its totals: the records that were not voided, summed from the rows the list shows. */
export interface CashGroup {
  key: string;
  label: string;
  from?: string;
  to?: string;
  /** Records counted: every record that was not voided, imported ones included. */
  count: number;
  voided: number;
  imported: number;
  /** Absent when the list carries no amount, or a counted record came without one (the reader may not see it). */
  totalMinor?: number;
  owedMinor?: number;
}

/** What was opened above a level (shop, days, category), as list-chain-state's ListSelection. */
export interface CashSelection { branchId?: string; from?: string; to?: string; categoryId?: string }

const lastDay = (year: number, month: number) => new Date(Date.UTC(year, month, 0)).getUTCDate();

/** The rows of a selection: the shop, the days and the category opened above. */
export function rowsIn<T extends CashRowLike>(rows: T[], sel: CashSelection, figures: CashFigures<T> = {}): T[] {
  return rows.filter((r) => {
    const day = r.business_date ?? '';
    return (sel.branchId === undefined || r.branch_id === sel.branchId)
      && (sel.from === undefined || day >= sel.from)
      && (sel.to === undefined || day <= sel.to)
      && (sel.categoryId === undefined || (figures.category?.(r).id ?? '') === sel.categoryId);
  });
}

/** A sum of the counted rows, or undefined when one of them has no figure. */
function sumOf<T>(rows: T[], of: ((row: T) => number | undefined) | undefined): number | undefined {
  if (!of) return undefined;
  let total = 0;
  for (const r of rows) {
    const v = of(r);
    if (v === undefined) return undefined;
    total += v;
  }
  return total;
}

/**
 * The groups of one level of a cash book list under a selection, with the count and the totals of each (issue #816): shops by
 * name, years, months and days newest first, categories by name. Voided records are counted apart and left out of the totals.
 */
export function cashGroups<T extends CashRowLike>(rows: T[], level: string, sel: CashSelection, figures: CashFigures<T>, shopName: (id: string | undefined) => string): CashGroup[] {
  const groups = new Map<string, { key: string; label: string; from?: string; to?: string; rows: T[] }>();
  for (const row of rowsIn(rows, sel, figures)) {
    const date = row.business_date ?? '';
    const y = date.slice(0, 4);
    const m = date.slice(5, 7);
    let g: { key: string; label: string; from?: string; to?: string };
    switch (level) {
      case 'branch': g = { key: row.branch_id ?? '', label: shopName(row.branch_id) }; break;
      case 'category': {
        const c = figures.category?.(row) ?? {};
        g = { key: c.id ?? '', label: c.name ?? 'No category' };
        break;
      }
      case 'year': g = { key: y, label: y, from: `${y}-01-01`, to: `${y}-12-31` }; break;
      case 'month': g = { key: `${y}-${m}`, label: monthLabel(date), from: `${y}-${m}-01`, to: `${y}-${m}-${String(lastDay(Number(y), Number(m))).padStart(2, '0')}` }; break;
      default: g = { key: date, label: date, from: date, to: date };
    }
    const at = groups.get(g.key) ?? { ...g, rows: [] };
    at.rows.push(row);
    groups.set(g.key, at);
  }
  const time = level === 'year' || level === 'month' || level === 'day';
  return [...groups.values()]
    .sort((a, b) => (time ? b.key.localeCompare(a.key) : a.label.localeCompare(b.label)))
    .map(({ rows: in_, ...g }) => {
      const counted = in_.filter((r) => !r.voided);
      const totalMinor = sumOf(counted, figures.amount);
      const owedMinor = sumOf(counted, figures.owed);
      return {
        ...g,
        count: counted.length,
        voided: in_.length - counted.length,
        imported: counted.filter((r) => r.historical).length,
        ...(totalMinor !== undefined ? { totalMinor } : {}),
        ...(owedMinor !== undefined ? { owedMinor } : {}),
      };
    });
}

/** "3 expenses" or "1 expense", from the singular the list uses ("expense", "banking record"). */
export const recordsWord = (n: number, what: string) => (n === 1 ? `1 ${what}` : `${n} ${what}s`);

/** The time of day of an instant in the business zone, "14:05"; empty for anything that is not an instant. */
export function clockTime(instant: string | undefined, zone: string = businessZone()): string {
  const t = instant ? Date.parse(instant) : Number.NaN;
  if (Number.isNaN(t)) return '';
  return new Intl.DateTimeFormat('en-GB', { timeZone: zone, hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(t));
}

/** Who entered a record and when, from the fields the server sends; empty when it sent neither. */
export function enteredBy(by: string | undefined, at: string | undefined): string {
  const time = clockTime(at);
  if (!by) return time ? `at ${time}` : '';
  return time ? `by ${by} at ${time}` : `by ${by}`;
}

export type BankingFlag = 'ok' | 'shortfall' | 'surplus' | 'not_banked';

/** The server's banking flag in words, with the difference when there is one. */
export function flagWords(flag: BankingFlag | undefined, difference: number | undefined, show: (minor: number) => string): string {
  switch (flag) {
    case 'ok': return 'Matches what was expected';
    case 'shortfall': return difference === undefined ? 'Shortfall: less banked than expected' : `Shortfall: ${show(Math.abs(difference))} less than expected`;
    case 'surplus': return difference === undefined ? 'Surplus: more banked than expected' : `Surplus: ${show(Math.abs(difference))} more than expected`;
    case 'not_banked': return 'Not banked yet';
    default: return '';
  }
}

/** The warnings the server may send on a banking or withdrawal, in plain words; an unknown one is skipped. */
export function warningWords(warning: string): string | null {
  if (warning === 'cash_below_banked') return 'You banked more than the cash on record for this shop. Check the till and the other records for the day.';
  if (warning === 'bank_balance_negative') return 'This is more than the bank balance on record. The withdrawal is saved; check that the bank deposits are all recorded.';
  return null;
}

/** The saved panel of a screen, only while the shop it was saved for is still the one selected (a branch switch clears it). */
export function savedFor<T>(saved: { branchId: string | null; value: T } | null, branchId: string | null): T | null {
  return saved !== null && saved.branchId === branchId ? saved.value : null;
}

/** Whether the Void action is offered: the person may void there, the record is live, and it was not imported (imported records cannot be voided). */
export function voidOffered(row: { voided?: boolean; historical?: boolean }, mayVoid: boolean): boolean {
  return mayVoid && !row.voided && !row.historical;
}
