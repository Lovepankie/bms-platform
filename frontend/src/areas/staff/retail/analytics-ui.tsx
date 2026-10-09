import type { ReactNode } from 'react';
import { businessToday, daysBefore } from '../../../api/retail';
import { showPercent } from './maths';
import { money } from './ui';

// Shared pieces of the analytics screens (issue #149): a date range with the server's 366 day limit,
// bars and sparklines drawn as inline SVG (no chart library; the figures are always in the text
// beside them), and small helpers for money and percent cells.

export const MAX_RANGE_DAYS = 366;

/** The default range: the last 30 days, ending today in the business's timezone. */
export function defaultRange(): { from: string; to: string } {
  const to = businessToday();
  return { from: daysBefore(to, 29), to };
}

/** Plain words when the range is not usable, else null. Both ends count, as on the server. */
export function rangeProblem(from: string, to: string): string | null {
  if (!from || !to) return 'Choose both dates.';
  if (from > to) return 'The start date must not be after the end date.';
  const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000;
  if (days >= MAX_RANGE_DAYS) return 'Choose at most 366 days.';
  return null;
}

export function DateRange({ from, to, onFrom, onTo }: { from: string; to: string; onFrom: (v: string) => void; onTo: (v: string) => void }) {
  const problem = rangeProblem(from, to);
  return (
    <>
      <div className="rt-row">
        <div><label htmlFor="range-from">From</label><input id="range-from" type="date" value={from} onChange={(e) => onFrom(e.target.value)} /></div>
        <div><label htmlFor="range-to">To</label><input id="range-to" type="date" value={to} onChange={(e) => onTo(e.target.value)} /></div>
      </div>
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
    </>
  );
}

export function Choice<T extends string | number>({ id, label, value, options, onChange }: {
  id: string; label: string; value: T; options: { value: T; label: string }[]; onChange: (v: T) => void;
}) {
  return (
    <div>
      <label htmlFor={id}>{label}</label>
      <select id={id} value={String(value)} onChange={(e) => onChange((typeof value === 'number' ? Number(e.target.value) : e.target.value) as T)}>
        {options.map((o) => <option key={String(o.value)} value={String(o.value)}>{o.label}</option>)}
      </select>
    </div>
  );
}

export function Section({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <section className="an-section">
      <h2>{title}</h2>
      {hint && <p className="hint">{hint}</p>}
      {children}
    </section>
  );
}

export function Empty({ children = 'Nothing to show for this range.' }: { children?: ReactNode }) {
  return <p className="empty-state">{children}</p>;
}

/** "1 report", "2 reports". */
export const plural = (n: number | undefined, one: string, many = `${one}s`): string => `${n ?? 0} ${n === 1 ? one : many}`;

/** A figure that is only there when the server sent it (a profit reader); empty cells otherwise. */
export const moneyOrNone = (minor: number | null | undefined): string => (minor === undefined || minor === null ? '' : money(minor));
export const percentOrNone = (bp: number | null | undefined): string => (bp === undefined || bp === null ? '' : showPercent(bp));

/**
 * Horizontal bars, one per row, scaled to the largest value. The label and the figure are text, so
 * the SVG adds shape only and is hidden from assistive technology.
 */
export function Bars({ rows, label }: { rows: { key: string; name: string; value: number; text: string }[]; label: string }) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <ol className="bars" aria-label={label}>
      {rows.map((r) => (
        <li key={r.key}>
          <div className="bars-line"><span className="bars-name">{r.name}</span><span className="num">{r.text}</span></div>
          <svg className="bar" viewBox="0 0 100 4" preserveAspectRatio="none" aria-hidden="true" focusable="false">
            <rect className="bar-track" x="0" y="0" width="100" height="4" rx="2" />
            <rect className="bar-fill" x="0" y="0" width={Math.max(r.value > 0 ? 1 : 0, (r.value / max) * 100)} height="4" rx="2" />
          </svg>
        </li>
      ))}
    </ol>
  );
}

/** A line over equally spaced points, from zero to the largest. Pair it with the figures in text. */
export function Sparkline({ points, label }: { points: number[]; label: string }) {
  const max = Math.max(1, ...points);
  const step = points.length > 1 ? 100 / (points.length - 1) : 0;
  const coords = points.map((v, i) => `${(i * step).toFixed(2)},${(22 - (v / max) * 20).toFixed(2)}`).join(' ');
  return (
    <svg className="spark" viewBox="0 0 100 24" preserveAspectRatio="none" role="img" aria-label={label} focusable="false">
      <line className="spark-base" x1="0" y1="22" x2="100" y2="22" />
      {points.length > 0 && <polyline className="spark-line" points={coords} />}
    </svg>
  );
}
