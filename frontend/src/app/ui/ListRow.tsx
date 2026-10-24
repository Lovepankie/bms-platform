import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { icons } from '../../components/icons';

// One row style for every list of items, sales and people (#520, docs/ui-rules.md): the title, one muted line under it and
// the amount on the right in tabular figures, with a chevron when the row opens something. The whole row is the target: a
// link with `to`, a button with `onClick`, plain text otherwise. Rows sit in a RowList, and a list sits in a Card, the one
// surface: a white panel with a hairline edge, no stripe and no glow. The look is .ui-card, .row-list and .list-row in
// src/app/ui/kit.css.

export function Card({ title, action, children }: { title?: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="ui-card">
      {(title || action) && (
        <header className="ui-card-head">
          {title && <h2 className="ui-card-title">{title}</h2>}
          {action}
        </header>
      )}
      {children}
    </section>
  );
}

export function RowList({ label, children }: { label?: string; children: ReactNode }) {
  return (
    <ul className="row-list" aria-label={label}>
      {children}
    </ul>
  );
}

export function ListRow({ title, meta, amount, to, onClick, chevron = to !== undefined || onClick !== undefined }: {
  title: ReactNode;
  /** The one muted line: a date and a buyer, a stock count, a role. */
  meta?: ReactNode;
  /** Already formatted with money(), so the one formatter draws it. */
  amount?: ReactNode;
  to?: string;
  onClick?: () => void;
  /** Shown by default when the row opens something. */
  chevron?: boolean;
}) {
  const body = (
    <>
      <span className="list-row-text">
        <span className="list-row-title">{title}</span>
        {meta && <span className="list-row-meta">{meta}</span>}
      </span>
      {amount !== undefined && <span className="list-row-amount">{amount}</span>}
      {chevron && <span className="list-row-chevron">{icons.chevron}</span>}
    </>
  );
  return (
    <li>
      {to !== undefined ? (
        <Link className="list-row" to={to as never}>
          {body}
        </Link>
      ) : onClick ? (
        <button type="button" className="list-row" onClick={onClick}>
          {body}
        </button>
      ) : (
        <div className="list-row">{body}</div>
      )}
    </li>
  );
}
