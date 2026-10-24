import { Link } from '@tanstack/react-router';
import { useEffect, useId, useRef, type ReactNode } from 'react';

// The one "it worked" screen (#520, docs/ui-rules.md rules 5 and 8): a check drawn in 400ms, what was done in words, the key
// amount large in tabular figures, then what to do next as plain buttons, the first one primary. Centred, as only empty and
// success states are. Focus moves to the title so a screen reader hears it at once. The title is the page's h1 when the
// screen replaces the page, or an h2 (`level={2}`) under a page heading. The look is .success-screen in
// src/app/ui/kit.css.

export type SuccessAction = { label: string; to: string } | { label: string; onClick: () => void };

export function SuccessScreen({ title, amount, detail, actions, focus = true, level = 1 }: {
  title: string;
  /** Already formatted with money(), so the one formatter draws it. */
  amount?: string;
  detail?: ReactNode;
  /** The next steps, the primary one first. */
  actions: SuccessAction[];
  /** Off only where the screen is one example among others, as on /staff/kit. */
  focus?: boolean;
  level?: 1 | 2;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  const titleId = useId();
  const Heading = level === 1 ? 'h1' : 'h2';
  useEffect(() => {
    if (focus) heading.current?.focus();
  }, [focus]);
  return (
    <section className="success-screen" aria-labelledby={titleId}>
      <span className="success-mark" aria-hidden="true">
        <svg className="success-ring" viewBox="0 0 72 72" focusable="false">
          <circle cx="36" cy="36" r="36" />
        </svg>
        <span className="success-tick">
          <span className="success-tick-inner">
            <svg viewBox="0 0 72 72" focusable="false">
              <path d="M22 37l10 10 19-21" />
            </svg>
          </span>
        </span>
      </span>
      <Heading id={titleId} className="success-title" ref={heading} tabIndex={-1}>
        {title}
      </Heading>
      {amount && <p className="success-amount">{amount}</p>}
      {detail && <p className="success-detail">{detail}</p>}
      <div className="success-actions">
        {actions.map((a, i) => {
          const look = i === 0 ? 'btn btn-primary' : 'btn';
          return 'to' in a ? (
            <Link key={a.label} className={look} to={a.to as never}>
              {a.label}
            </Link>
          ) : (
            <button key={a.label} type="button" className={i === 0 ? 'btn-primary' : undefined} onClick={a.onClick}>
              {a.label}
            </button>
          );
        })}
      </div>
    </section>
  );
}
