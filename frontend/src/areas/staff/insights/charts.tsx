import { useEffect, useId, useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react';
import { compactMinor, formatValue } from './format';

// Small SVG charts for the insights page (#153): no chart library. Each is drawn at the width of
// its container in pixels (so text stays legible on a 390px phone), coloured only through CSS
// classes on the shared tokens (the CSP forbids inline styles), has one y axis, a hover and
// keyboard read-out drawn inside the SVG, a legend when it has two series or more, and a table
// with the same numbers for screen readers, printing and anyone who prefers rows.

export interface Series {
  key: string;
  label: string;
}

export interface Point {
  label: string;
  /** One value per series, in series order; null where there is no value. */
  values: (number | null)[];
}

/** The width of an element, followed as it resizes; a phone width before the first measure. */
function useWidth(): [React.RefObject<HTMLDivElement>, number] {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(360);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver((entries) => {
      const w = Math.floor(entries[0]?.contentRect.width ?? 0);
      if (w > 0) setWidth(w);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}

/** Rounds a maximum up to a tidy axis top (1, 2 or 5 times a power of ten). */
export function niceMax(max: number): number {
  if (max <= 0) return 1;
  const power = 10 ** Math.floor(Math.log10(max));
  for (const step of [1, 2, 5, 10]) {
    if (step * power >= max) return step * power;
  }
  return 10 * power;
}

function tick(value: number, kind: string, currency: string): string {
  if (kind === 'money') return compactMinor(value, currency);
  if (kind === 'basis_points') return `${Math.round(value / 100)}%`;
  return value.toLocaleString('en');
}

/** The rows of a chart as a table, closed by default, open when printed. */
export function ChartTable({ caption, series, points, kind, currency }: {
  caption: string;
  series: Series[];
  points: Point[];
  kind: string;
  currency: string;
}) {
  return (
    <details className="iv-table">
      <summary>Show as table</summary>
      <div className="table-wrap" role="region" aria-label={caption} tabIndex={0}>
        <table>
          <caption className="sr-only">{caption}</caption>
          <thead>
            <tr>
              <th scope="col">Period</th>
              {series.map((s) => (
                <th key={s.key} scope="col" className="num">{s.label}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {points.map((p) => (
              <tr key={p.label}>
                <th scope="row">{p.label}</th>
                {p.values.map((v, i) => (
                  <td key={series[i]?.key ?? i} className="num">{formatValue(v, kind, currency)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}

function Legend({ series }: { series: Series[] }) {
  if (series.length < 2) return null;
  return (
    <ul className="iv-legend" aria-hidden="true">
      {series.map((s, i) => (
        <li key={s.key}>
          <span className={`iv-swatch iv-c${i + 1}`} />
          {s.label}
        </li>
      ))}
    </ul>
  );
}

const PAD = { top: 12, right: 12, bottom: 28, left: 48 };

/** Every n-th axis label and the last one, never a regular label too close to the last. */
export function showLabel(i: number, count: number, every: number): boolean {
  if (i === count - 1) return true;
  return i % every === 0 && count - 1 - i >= every;
}

/** The read-out drawn inside the SVG at the active point. */
function ReadOut({ x, plotTop, plotBottom, width, lines }: {
  x: number;
  plotTop: number;
  plotBottom: number;
  width: number;
  lines: { text: string; series?: number }[];
}) {
  const boxWidth = Math.min(220, width - 16);
  const boxHeight = 10 + lines.length * 18;
  const left = x + 12 + boxWidth > width ? Math.max(4, x - 12 - boxWidth) : x + 12;
  return (
    <g className="iv-readout" aria-hidden="true">
      <line className="iv-crosshair" x1={x} x2={x} y1={plotTop} y2={plotBottom} />
      <rect className="iv-readout-box" x={left} y={plotTop} width={boxWidth} height={boxHeight} rx={6} />
      {lines.map((l, i) => (
        <g key={i}>
          {l.series !== undefined && (
            <rect className={`iv-c${l.series + 1}-fill`} x={left + 8} y={plotTop + 8 + i * 18} width={8} height={8} rx={2} />
          )}
          <text className={i === 0 ? 'iv-readout-title' : 'iv-readout-text'} x={left + (l.series !== undefined ? 22 : 8)} y={plotTop + 16 + i * 18}>
            {l.text}
          </text>
        </g>
      ))}
    </g>
  );
}

/**
 * A line chart over periods: one to four series of the same kind on one axis. Hover or focus and
 * the arrow keys move a crosshair that reads out every series at that period.
 */
export function LineChart({ title, series, points, kind, currency, height = 220 }: {
  title: string;
  series: Series[];
  points: Point[];
  kind: string;
  currency: string;
  height?: number;
}) {
  const [ref, width] = useWidth();
  const [active, setActive] = useState<number | null>(null);
  const id = useId();
  const plotW = Math.max(40, width - PAD.left - PAD.right);
  const plotH = height - PAD.top - PAD.bottom;
  const values = points.flatMap((p) => p.values.map((v) => v ?? 0));
  const max = niceMax(Math.max(0, ...values));
  // A negative value (a month whose write-offs exceed its income) extends the axis below zero.
  const min = Math.min(0, ...values) < 0 ? -niceMax(-Math.min(...values)) : 0;
  const x = (i: number) => PAD.left + (points.length <= 1 ? plotW / 2 : (i * plotW) / (points.length - 1));
  const y = (v: number) => PAD.top + plotH - ((v - min) / (max - min)) * plotH;
  const ticks = min < 0 ? [min, 0, max] : [0, max / 2, max];
  const labelEvery = Math.max(1, Math.ceil(points.length / Math.max(2, Math.floor(plotW / 64))));

  function pick(e: PointerEvent<SVGRectElement>) {
    const box = e.currentTarget.getBoundingClientRect();
    const rel = e.clientX - box.left;
    const i = points.length <= 1 ? 0 : Math.round((rel / box.width) * (points.length - 1));
    setActive(Math.min(points.length - 1, Math.max(0, i)));
  }
  function key(e: KeyboardEvent<SVGSVGElement>) {
    if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
      e.preventDefault();
      const step = e.key === 'ArrowRight' ? 1 : -1;
      setActive((a) => Math.min(points.length - 1, Math.max(0, (a ?? (step > 0 ? -1 : points.length)) + step)));
    } else if (e.key === 'Escape') {
      setActive(null);
    }
  }

  return (
    <figure className="iv-chart">
      <figcaption id={id}>{title}</figcaption>
      <Legend series={series} />
      <div ref={ref} className="iv-canvas">
        {points.length === 0 ? (
          <p className="ln-muted">No data in this range.</p>
        ) : (
          <svg
            width={width}
            height={height}
            role="img"
            aria-labelledby={id}
            tabIndex={0}
            onKeyDown={key}
            onBlur={() => setActive(null)}
          >
            {ticks.map((t) => (
              <g key={t}>
                <line className="iv-grid" x1={PAD.left} x2={PAD.left + plotW} y1={y(t)} y2={y(t)} />
                <text className="iv-axis" x={PAD.left - 6} y={y(t) + 4} textAnchor="end">{tick(t, kind, currency)}</text>
              </g>
            ))}
            {points.map((p, i) =>
              showLabel(i, points.length, labelEvery) ? (
                <text key={p.label} className="iv-axis" x={x(i)} y={height - 8} textAnchor={i === 0 ? 'start' : i === points.length - 1 ? 'end' : 'middle'}>
                  {p.label}
                </text>
              ) : null,
            )}
            {series.map((s, si) => {
              const d = points
                .map((p, i) => (p.values[si] === null ? null : `${x(i)},${y(p.values[si] ?? 0)}`))
                .filter((v): v is string => v !== null);
              return (
                <g key={s.key}>
                  <polyline className={`iv-line iv-c${si + 1}`} points={d.join(' ')} />
                  {points.length <= 16 &&
                    points.map((p, i) =>
                      p.values[si] === null ? null : (
                        <circle key={i} className={`iv-dot iv-c${si + 1}-fill`} cx={x(i)} cy={y(p.values[si] ?? 0)} r={4} />
                      ),
                    )}
                </g>
              );
            })}
            {active !== null && points[active] && (
              <ReadOut
                x={x(active)}
                plotTop={PAD.top}
                plotBottom={PAD.top + plotH}
                width={width}
                lines={[
                  { text: points[active].label },
                  ...series.map((s, si) => ({ text: `${s.label}: ${formatValue(points[active]?.values[si], kind, currency)}`, series: si })),
                ]}
              />
            )}
            <rect
              className="iv-hit"
              x={PAD.left}
              y={PAD.top}
              width={plotW}
              height={plotH}
              onPointerMove={pick}
              onPointerDown={pick}
              onPointerLeave={() => setActive(null)}
            />
          </svg>
        )}
      </div>
      <ChartTable caption={title} series={series} points={points} kind={kind} currency={currency} />
    </figure>
  );
}

/** Columns over periods for one measure (a rate, a count). Hover, tap or the arrow keys read a column out. */
export function ColumnChart({ title, label, points, kind, currency, height = 200 }: {
  title: string;
  label: string;
  points: Point[];
  kind: string;
  currency: string;
  height?: number;
}) {
  const [ref, width] = useWidth();
  const [active, setActive] = useState<number | null>(null);
  const id = useId();
  const plotW = Math.max(40, width - PAD.left - PAD.right);
  const plotH = height - PAD.top - PAD.bottom;
  const max = kind === 'basis_points' ? 10_000 : niceMax(Math.max(0, ...points.map((p) => p.values[0] ?? 0)));
  const slot = points.length === 0 ? plotW : plotW / points.length;
  const bar = Math.max(2, Math.min(32, slot - 2));
  const y = (v: number) => PAD.top + plotH - (Math.min(v, max) / max) * plotH;
  const labelEvery = Math.max(1, Math.ceil(points.length / Math.max(2, Math.floor(plotW / 64))));
  function key(e: KeyboardEvent<SVGSVGElement>) {
    if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
      e.preventDefault();
      const step = e.key === 'ArrowRight' ? 1 : -1;
      setActive((a) => Math.min(points.length - 1, Math.max(0, (a ?? (step > 0 ? -1 : points.length)) + step)));
    }
  }
  return (
    <figure className="iv-chart">
      <figcaption id={id}>{title}</figcaption>
      <div ref={ref} className="iv-canvas">
        {points.length === 0 ? (
          <p className="ln-muted">No data in this range.</p>
        ) : (
          <svg width={width} height={height} role="img" aria-labelledby={id} tabIndex={0} onKeyDown={key} onBlur={() => setActive(null)}>
            {[0, max / 2, max].map((t) => (
              <g key={t}>
                <line className="iv-grid" x1={PAD.left} x2={PAD.left + plotW} y1={y(t)} y2={y(t)} />
                <text className="iv-axis" x={PAD.left - 6} y={y(t) + 4} textAnchor="end">{tick(t, kind, currency)}</text>
              </g>
            ))}
            {points.map((p, i) => {
              const v = p.values[0] ?? null;
              const cx = PAD.left + slot * i + slot / 2;
              return (
                <g key={p.label}>
                  {v !== null && v !== undefined && (
                    <rect
                      className={`iv-bar${active === i ? ' iv-bar-active' : ''}`}
                      x={cx - bar / 2}
                      y={y(v)}
                      width={bar}
                      height={Math.max(0, PAD.top + plotH - y(v))}
                      rx={Math.min(4, bar / 2)}
                    />
                  )}
                  <rect
                    className="iv-hit"
                    x={PAD.left + slot * i}
                    y={PAD.top}
                    width={slot}
                    height={plotH}
                    onPointerEnter={() => setActive(i)}
                    onPointerDown={() => setActive(i)}
                    onPointerLeave={() => setActive(null)}
                  />
                  {showLabel(i, points.length, labelEvery) && (
                    <text className="iv-axis" x={cx} y={height - 8} textAnchor="middle">{p.label}</text>
                  )}
                </g>
              );
            })}
            {active !== null && points[active] && (
              <ReadOut
                x={PAD.left + slot * active + slot / 2}
                plotTop={PAD.top}
                plotBottom={PAD.top + plotH}
                width={width}
                lines={[{ text: points[active].label }, { text: `${label}: ${formatValue(points[active].values[0], kind, currency)}` }]}
              />
            )}
          </svg>
        )}
      </div>
      <ChartTable caption={title} series={[{ key: 'v', label }]} points={points} kind={kind} currency={currency} />
    </figure>
  );
}

export interface BarRow {
  key: string;
  label: string;
  value: number;
  /** A second line under the label, for example "12 loans, PAR 30 4.0%". */
  note?: string;
  /** Rendered after the value: a drill-down link or button. */
  action?: ReactNode;
}

/**
 * Horizontal bars, one row per item, value written beside each bar (ageing, breakdowns, funnel).
 * Plain HTML rows with an SVG bar each, so a long label wraps on a phone instead of colliding.
 */
export function BarList({ title, rows, kind, currency, tone = 'c1' }: {
  title: string;
  rows: BarRow[];
  kind: string;
  currency: string;
  tone?: 'c1' | 'c2' | 'c3';
}) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <figure className="iv-chart">
      <figcaption>{title}</figcaption>
      {rows.length === 0 ? (
        <p className="ln-muted">Nothing to show.</p>
      ) : (
        <ul className="iv-bars">
          {rows.map((r) => (
            <li key={r.key}>
              <span className="iv-bars-label">
                {r.label}
                {r.note && <span className="iv-bars-note">{r.note}</span>}
              </span>
              <svg className="iv-bars-track" viewBox="0 0 100 10" preserveAspectRatio="none" aria-hidden="true">
                <rect className="iv-bars-bg" x={0} y={0} width={100} height={10} rx={2} />
                <rect className={`iv-${tone}-fill`} x={0} y={0} width={r.value <= 0 ? 0 : Math.max(1, (r.value / max) * 100)} height={10} rx={2} />
              </svg>
              <span className="iv-bars-value">
                {formatValue(r.value, kind, currency)}
                {r.action}
              </span>
            </li>
          ))}
        </ul>
      )}
    </figure>
  );
}
