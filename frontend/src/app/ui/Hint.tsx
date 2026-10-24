import { useEffect, useRef, useState, type ReactNode } from 'react';
import { icons } from '../../components/icons';

// The one help line of a screen or a section (issue #526, docs/ui-rules.md rule 17): one line at most on screen. A longer text is
// cut at the end of its first line, and an info button beside it shows the rest and hides it again. The whole text is always in
// the page, so a screen reader reads all of it; the cut is only what a sighted person sees. The button is drawn only when the
// text is cut, measured after layout, so a short hint is a plain line. A warning or a consequence that changes what the person
// does next (it cannot be undone, the secret is shown once, damaged goods never go back) is never cut: `full` draws it whole.
// The look is .hint-line in src/app/ui/kit.css.

export function Hint({ children, tour, full = false }: { children: ReactNode; tour?: string; full?: boolean }) {
  if (full) {
    return (
      <p className="hint" data-tour={tour}>
        {children}
      </p>
    );
  }
  return <CutHint tour={tour}>{children}</CutHint>;
}

function CutHint({ children, tour }: { children: ReactNode; tour?: string }) {
  const text = useRef<HTMLParagraphElement>(null);
  const [cut, setCut] = useState(false);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const el = text.current;
    if (!el) return;
    const measure = () => setCut(el.scrollHeight > el.clientHeight + 1 || el.scrollWidth > el.clientWidth + 1);
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(el);
    return () => observer?.disconnect();
  }, []);

  return (
    <div className={open ? 'hint-line hint-open' : 'hint-line'} data-tour={tour}>
      <p ref={text} className="hint">
        {children}
      </p>
      {(cut || open) && (
        <button type="button" className="btn-ghost btn-icon hint-more" aria-expanded={open} aria-label={open ? 'Show less' : 'Show more'} onClick={() => setOpen(!open)}>
          {icons.info}
        </button>
      )}
    </div>
  );
}
