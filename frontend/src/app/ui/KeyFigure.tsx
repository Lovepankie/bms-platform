import type { ReactNode } from 'react';

// The key amount of a screen (issue #526, docs/ui-rules.md rule 8): its label small and muted above, the amount at the display
// size (40px on a phone to 56px) in tabular figures, and at most a few plain rows under it (the cash, mobile money and bank
// behind a total). Pass the value already formatted with money() or formatMinor, so the one formatter draws it. A screen has
// one. The look is .key-figure in src/app/ui/kit.css.

export function KeyFigure({ label, value, tour, children }: { label: string; value: ReactNode; tour?: string; children?: ReactNode }) {
  return (
    <div className="key-figure" data-tour={tour}>
      <span className="key-figure-label">{label}</span>
      <strong className="key-figure-value">{value}</strong>
      {children && <dl className="key-figure-rows">{children}</dl>}
    </div>
  );
}

/** One row under the key figure: the label left, the amount right in tabular figures. */
export function KeyFigureRow({ label, value }: { label: ReactNode; value: ReactNode }) {
  return (
    <div className="key-figure-row">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}
