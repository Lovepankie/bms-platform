import { daysBefore } from '../../../api/retail';
import { formatMinor } from '../../../components/money';

// Formatting of insights values. Money comes as integer minor units and is formatted only by
// formatMinor (ADR-004); rates come as basis points and are shown to one decimal place, rounded
// half up on the integer, so no floating point decides a digit (chapter 14 section 14.1).

/** 1250 basis points as "12.5%". */
export function percent(bp: number): string {
  const tenths = Math.floor((Math.abs(bp) + 5) / 10);
  return `${bp < 0 ? '-' : ''}${Math.floor(tenths / 10)}.${tenths % 10}%`;
}

/** A metric value by its kind; an empty value is a dash, never a zero. */
export function formatValue(value: number | null | undefined, kind: string | undefined, currency?: string | null): string {
  if (value === null || value === undefined) return '-';
  switch (kind) {
    case 'money':
      return formatMinor(value, currency ?? 'UGX');
    case 'basis_points':
      return percent(value);
    case 'days':
      return value === 1 ? '1 day' : `${value.toLocaleString('en')} days`;
    case 'hours':
      return value === 1 ? '1 hour' : `${value.toLocaleString('en')} hours`;
    default:
      return value.toLocaleString('en');
  }
}

/** A short money label for an axis: 1.2M, 350K. Only for axis ticks; values elsewhere are exact. */
export function compactMinor(minor: number, currency: string): string {
  const exponent = currency === 'UGX' ? 0 : 2;
  const major = Math.round(minor / 10 ** exponent);
  const abs = Math.abs(major);
  if (abs >= 1_000_000_000) return `${trim(major / 1_000_000_000)}B`;
  if (abs >= 1_000_000) return `${trim(major / 1_000_000)}M`;
  if (abs >= 1_000) return `${trim(major / 1_000)}K`;
  return String(major);
}

function trim(n: number): string {
  return n.toFixed(1).replace(/\.0$/, '');
}

/** The quick ranges of the filter bar, ending `today`, the business date as yyyy-mm-dd (businessToday). */
export function presetRange(preset: string, today: string): { from: string; to: string } {
  const to = today;
  switch (preset) {
    case 'today':
      return { from: to, to };
    case '7d':
      return { from: daysBefore(to, 6), to };
    case '30d':
      return { from: daysBefore(to, 29), to };
    case 'quarter':
      return { from: daysBefore(to, 89), to };
    case 'year':
      return { from: daysBefore(to, 364), to };
    default:
      // This month to date.
      return { from: `${to.slice(0, 8)}01`, to };
  }
}

/** Words for a snake_case key: "written_off" becomes "Written off". */
export function words(key: string): string {
  const s = key.replace(/_/g, ' ');
  return s.charAt(0).toUpperCase() + s.slice(1);
}
