import { RETAIL_CURRENCY, RETAIL_ZONE } from '../../../api/retail';
import { parseMinor } from '../../../components/money';

// Plain logic shared by the cash book screens (ADR-022): grouping of records, amounts typed by people,
// the order and wording of what the server sends. No figure is ever computed that the server left out.

/** An amount typed in whole money units as integer minor units, or null when it is not one above zero. */
export function amountOrNull(text: string): number | null {
  const minor = parseMinor(text, RETAIL_CURRENCY);
  return minor !== null && minor > 0 ? minor : null;
}

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

/** The time of day of an instant in the business zone, "14:05"; empty for anything that is not an instant. */
export function clockTime(instant: string | undefined, zone: string = RETAIL_ZONE): string {
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
