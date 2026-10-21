import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react';
import { RANGE_CHOICES, changeOf, customProblem, stepWords, type RangeChoice } from './range-state';
import { money } from './ui';

// The range picker and the range chart of the Retail home (issue #428). The chart is inline SVG with no style attribute (the
// CSP refuses inline styles): a line of the range and, dashed, the same steps of the period before. Hover, tap or the arrow keys
// pick a step, and its value is read out under the chart.

export function RangePicker({ value, onChange, today }: { value: RangeChoice; onChange: (choice: RangeChoice) => void; /** The business's day: no range may end after it. */ today?: string }) {
  const [custom, setCustom] = useState(value.period === 'custom');
  const [from, setFrom] = useState(value.from ?? '');
  const [to, setTo] = useState(value.to ?? '');
  const [problem, setProblem] = useState<string | null>(null);
  const show = (e: FormEvent) => {
    e.preventDefault();
    const p = customProblem(from, to, today);
    setProblem(p);
    if (!p) onChange({ period: 'custom', from, to });
  };
  return (
    <div className="rc-picker" data-tour="home-range">
      <div className="rc-chips" role="group" aria-label="Which days to show">
        {RANGE_CHOICES.map((c) => {
          const on = c.period === 'custom' ? custom : !custom && value.period === c.period;
          return (
            <button
              key={c.period}
              type="button"
              className="rc-chip"
              aria-pressed={on}
              onClick={() => {
                if (c.period === 'custom') {
                  setCustom(true);
                  return;
                }
                setCustom(false);
                setProblem(null);
                onChange({ period: c.period });
              }}
            >
              {c.label}
            </button>
          );
        })}
      </div>
      {custom && (
        <form className="rc-custom" onSubmit={show} noValidate>
          <label>From <input type="date" value={from} max={to || today || undefined} onChange={(e) => setFrom(e.target.value)} /></label>
          <label>To <input type="date" value={to} min={from || undefined} max={today} onChange={(e) => setTo(e.target.value)} /></label>
          <button type="submit" className="btn-sm">Show these days</button>
          {problem && <p role="alert" className="rt-flag">{problem}</p>}
        </form>
      )}
    </div>
  );
}

/** Up or down on the period before, in amount and percent; nothing when there is no period before. */
export function ChangeLine({ now, before, words, format = money }: { now: number; before: number | undefined; words: string; format?: (n: number) => string }) {
  if (before === undefined) return null;
  const c = changeOf(now, before);
  const pct = c.percent === null ? '' : `, ${Math.abs(c.percent)}%`;
  const text = c.direction === 'same'
    ? `Same as ${words}`
    : `${c.direction === 'up' ? 'Up' : 'Down'} ${format(Math.abs(c.amount))}${pct} on ${words}`;
  return (
    <span className={`rc-change rc-${c.direction}`}>
      {c.direction !== 'same' && <span aria-hidden="true">{c.direction === 'up' ? '▲ ' : '▼ '}</span>}
      {text}
    </span>
  );
}

export interface ChartPoint {
  start: string;
  value: number;
  previous?: number;
}

const W = 320;
const H = 150;
const TOP = 8;
const BOTTOM = 8;
const SIDE = 6;

function line(values: number[], max: number): string {
  const step = values.length > 1 ? (W - 2 * SIDE) / (values.length - 1) : 0;
  return values
    .map((v, i) => `${(values.length > 1 ? SIDE + i * step : W / 2).toFixed(1)},${(TOP + (1 - v / max) * (H - TOP - BOTTOM)).toFixed(1)}`)
    .join(' ');
}

/** The sales of a range a step at a time, with the period before dashed behind it. */
export function RangeChart({ points, group, label, beforeWords, legend }: { points: ChartPoint[]; group: string; label: string; beforeWords?: string; legend?: ReactNode }) {
  const [active, setActive] = useState<number | null>(null);
  const n = points.length;
  const first = points[0];
  if (!first) return null;
  const withBefore = points.some((p) => p.previous !== undefined);
  const max = Math.max(1, ...points.map((p) => Math.max(p.value, p.previous ?? 0)));
  const at = active === null ? n - 1 : Math.min(active, n - 1);
  const step = n > 1 ? (W - 2 * SIDE) / (n - 1) : 0;
  const x = n > 1 ? SIDE + at * step : W / 2;
  const y = (v: number) => TOP + (1 - v / max) * (H - TOP - BOTTOM);
  // The chart's box is read once, on pointerdown (or the first move of a hover), not on every move: reading it forces a layout.
  // A resize or a scroll drops it, as either moves the chart under the pointer.
  const rect = useRef<DOMRect | null>(null);
  useEffect(() => {
    const drop = () => { rect.current = null; };
    window.addEventListener('resize', drop);
    window.addEventListener('scroll', drop, { passive: true, capture: true });
    return () => {
      window.removeEventListener('resize', drop);
      window.removeEventListener('scroll', drop, { capture: true });
    };
  }, []);
  // What a screen reader hears follows the pick a moment after it settles, not every step of a drag.
  const [spoken, setSpoken] = useState<number | null>(null);
  useEffect(() => {
    const t = setTimeout(() => setSpoken(active), 600);
    return () => clearTimeout(t);
  }, [active]);
  const pick = (e: PointerEvent<SVGSVGElement>) => {
    if (e.type === 'pointerdown' || rect.current === null) rect.current = e.currentTarget.getBoundingClientRect();
    const box = rect.current;
    if (box.width <= 0) return;
    setActive(Math.max(0, Math.min(n - 1, Math.round(((e.clientX - box.left) / box.width) * (n - 1)))));
  };
  const keys = (e: KeyboardEvent<SVGSVGElement>) => {
    const moves: Record<string, number> = { ArrowLeft: at - 1, ArrowRight: at + 1, Home: 0, End: n - 1 };
    const to = moves[e.key];
    if (to !== undefined) {
      e.preventDefault();
      setActive(Math.max(0, Math.min(n - 1, to)));
    }
  };
  const p = points[at] ?? first;
  const last = points[n - 1] ?? first;
  return (
    <figure className="rc-chart">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        role="slider"
        aria-label={`${label}. Use the arrow keys to read each step.`}
        aria-orientation="horizontal"
        aria-valuemin={0}
        aria-valuemax={n - 1}
        aria-valuenow={at}
        aria-valuetext={`${stepWords(p.start, group)}, ${money(p.value)}`}
        tabIndex={0}
        onPointerMove={pick}
        onPointerDown={pick}
        onKeyDown={keys}
      >
        <line className="rc-base" x1={SIDE} y1={H - BOTTOM} x2={W - SIDE} y2={H - BOTTOM} />
        {withBefore && <polyline className="rc-prev" points={line(points.map((q) => q.previous ?? 0), max)} />}
        <polyline className="rc-line" points={line(points.map((q) => q.value), max)} />
        <line className="rc-cursor" x1={x} y1={TOP} x2={x} y2={H - BOTTOM} />
        {withBefore && p.previous !== undefined && <circle className="rc-dot-prev" cx={x} cy={y(p.previous)} r={3.5} />}
        <circle className="rc-dot" cx={x} cy={y(p.value)} r={4.5} />
      </svg>
      <div className="rc-axis" aria-hidden="true">
        <span>{stepWords(first.start, group)}</span>
        {n > 1 && <span>{stepWords(last.start, group)}</span>}
      </div>
      <span className="visually-hidden" aria-live="polite" data-testid="rc-spoken">
        {spoken === null ? '' : `${stepWords((points[Math.min(spoken, n - 1)] ?? first).start, group)}, ${money((points[Math.min(spoken, n - 1)] ?? first).value)}`}
      </span>
      <figcaption className="rc-readout">
        <strong>{stepWords(p.start, group)}</strong> <span className="num">{money(p.value)}</span>
        {p.previous !== undefined && beforeWords && <span className="hint"> ({beforeWords}: {money(p.previous)})</span>}
      </figcaption>
      {legend}
    </figure>
  );
}

/** The key under the chart: the range as a line, the period before dashed. */
export function ChartLegend({ before }: { before?: string }) {
  return (
    <p className="rc-legend hint">
      <span className="rc-key rc-key-now" aria-hidden="true" /> These days
      {before && <> <span className="rc-key rc-key-prev" aria-hidden="true" /> {before}</>}
    </p>
  );
}
