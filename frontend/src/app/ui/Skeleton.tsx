import { useEffect, useRef, useState, type ReactNode } from 'react';

// The one loading state (#520, docs/ui-rules.md rule 14): nothing at all for the first second, so a quick load never flashes,
// then grey rows in the shape of the list until it arrives. Once the content has been on the screen it stays there through
// a later reload: the skeleton never covers what the person was reading. A different list (a new filter, a new query key)
// is not the same content, so pass its key as `of` and the rules start again for it. Pass `loading` (a query's isPending)
// and the content as children. The look is .skeleton-rows in src/app/ui/kit.css.

/** How long a load may take before the skeleton shows, in ms. */
export const SKELETON_DELAY = 1000;

export function Skeleton({ loading, of, rows = 3, label = 'Loading', children }: {
  loading: boolean;
  /** Which list this is, such as the query key joined; a new value counts as content never shown. */
  of?: string;
  rows?: number;
  label?: string;
  children?: ReactNode;
}) {
  const shown = useRef(false);
  const list = useRef(of);
  if (list.current !== of) {
    list.current = of;
    shown.current = false;
  }
  if (!loading) shown.current = true;
  const waiting = loading && !shown.current;
  // Each wait (a load starting, or a new list while loading) has its own number, and the one-second timer marks only the wait it
  // was started for, so a skeleton from an earlier wait never shows at 0ms on the next one.
  const turn = useRef({ waiting: false, of, id: 0 });
  if (waiting && (!turn.current.waiting || turn.current.of !== of)) turn.current.id += 1;
  turn.current.waiting = waiting;
  turn.current.of = of;
  const id = turn.current.id;
  const [lateFor, setLateFor] = useState(0);

  useEffect(() => {
    if (!waiting) return;
    const timer = window.setTimeout(() => setLateFor(id), SKELETON_DELAY);
    return () => window.clearTimeout(timer);
  }, [waiting, id]);

  if (!waiting) return <>{children}</>;
  if (lateFor !== id) return null;
  return (
    <div className="skeleton-rows" role="status" aria-busy="true">
      <span className="visually-hidden">{label}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton-row" aria-hidden="true">
          <span className="skeleton-bar" />
          <span className="skeleton-bar skeleton-bar-short" />
        </div>
      ))}
    </div>
  );
}
