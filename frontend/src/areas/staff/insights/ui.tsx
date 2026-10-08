import { useId, useState, type ReactNode } from 'react';
import type { InsightsDrill, InsightsMetric } from '../../../api/insights';
import { formatValue } from './format';

// The building blocks of the insights page: a number card with its definition and drill-down,
// and the definition tip. Styled in src/app/ui/insights.css with the shared tokens.

/** What a drill-down opens: a table, its parameters and the title of the number it explains. */
export interface DrillTarget {
  table: string;
  params: Record<string, string>;
  title: string;
}

export function drillOf(metric: { label?: string; drill?: InsightsDrill | null }): DrillTarget | null {
  if (!metric.drill?.table) return null;
  return { table: metric.drill.table, params: { ...(metric.drill.params ?? {}) }, title: metric.label ?? metric.drill.table };
}

/** The "i" button that shows how a number is computed; it opens on click or focus, Escape closes it. */
export function InfoTip({ label, text }: { label: string; text: string }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  return (
    <span className="iv-tip">
      <button
        type="button"
        className="iv-tip-btn"
        aria-label={`How ${label} is calculated`}
        aria-expanded={open}
        aria-describedby={open ? id : undefined}
        onClick={() => setOpen((o) => !o)}
        onBlur={() => setOpen(false)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') setOpen(false);
        }}
      >
        i
      </button>
      <span id={id} role="tooltip" className={open ? 'iv-tip-text iv-open' : 'iv-tip-text'}>
        {text}
      </span>
    </span>
  );
}

/** One number: its label and definition, its value, an optional note, and a button to the rows behind it. */
export function MetricCard({ metric, onDrill, note, emphasis }: {
  metric: InsightsMetric;
  onDrill?: (target: DrillTarget) => void;
  note?: ReactNode;
  emphasis?: boolean;
}) {
  const label = metric.label ?? metric.key ?? '';
  const value = formatValue(metric.value, metric.kind, metric.currency);
  const target = drillOf(metric);
  return (
    <div className={emphasis ? 'iv-card iv-card-hero' : 'iv-card'}>
      <p className="iv-card-label">
        <span>{label}</span>
        <InfoTip label={label} text={metric.definition ?? ''} />
      </p>
      {target && onDrill ? (
        <button type="button" className="iv-card-value iv-value-btn" onClick={() => onDrill(target)} aria-label={`${label}: ${value}. Show the rows`}>
          {value}
        </button>
      ) : (
        <p className="iv-card-value">{value}</p>
      )}
      {note && <p className="iv-card-note">{note}</p>}
    </div>
  );
}

/** A grid of number cards. */
export function MetricGrid({ metrics, onDrill, keys }: {
  metrics: InsightsMetric[];
  onDrill?: (target: DrillTarget) => void;
  keys?: string[];
}) {
  const shown = keys ? keys.map((k) => metrics.find((m) => m.key === k)).filter((m): m is InsightsMetric => !!m) : metrics;
  return (
    <div className="iv-grid">
      {shown.map((m) => (
        <MetricCard key={m.key} metric={m} onDrill={onDrill} />
      ))}
    </div>
  );
}

export function metricValue(metrics: InsightsMetric[] | undefined, key: string): number | null {
  const m = metrics?.find((x) => x.key === key);
  return m?.value ?? null;
}
