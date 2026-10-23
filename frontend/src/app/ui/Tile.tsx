import { Link } from '@tanstack/react-router';
import { useId, type ReactNode } from 'react';

// The one navigation tile (#508, docs/ui-rules.md): every hub page that leads to other screens (Retail home, Catalogue, Business
// set-up) lists them as a grid of these, an icon above the label and a one-line hint under it, so a hub reads the same everywhere.
// The link is named by its label alone and described by its hint, so a screen reader hears "Record a sale, link" first. The look
// is .tile-grid and .tile in src/app/ui/navigation.css.

export function TileGrid({ label, tour, children }: { label: string; tour?: string; children: ReactNode }) {
  return (
    <ul className="tile-grid" aria-label={label} data-tour={tour}>
      {children}
    </ul>
  );
}

export function Tile({ to, icon, label, hint, tour }: { to: string; icon: ReactNode; label: string; hint?: string; tour?: string }) {
  const hintId = useId();
  return (
    <li>
      <Link className="tile" to={to as never} data-tour={tour} aria-describedby={hint ? hintId : undefined}>
        <span className="icon-chip">{icon}</span>
        <strong className="tile-label">{label}</strong>
        {hint && (
          <span className="tile-hint" id={hintId} aria-hidden="true">
            {hint}
          </span>
        )}
      </Link>
    </li>
  );
}
