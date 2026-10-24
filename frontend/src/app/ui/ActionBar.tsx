import { useEffect, useRef, type ReactNode } from 'react';

// The one primary action of a screen (#508, issue #526, docs/ui-rules.md rule 6), the sale's bar made shared: on a phone a bar
// stuck full width to the bottom of the screen, over the bottom tabs, its button full width and carrying the live amount where
// there is one; from 720px it sits in the form under the last field. While it sticks its measured height is --bottom-bar on the
// page, so the toast rises above it and a field scrolled into view stops above it, without :has() and at any font size; it is
// cleared when the bar goes. The look is .action-bar in src/app/ui/kit.css.

export function ActionBar({ children, className, testId }: { children: ReactNode; className?: string; testId?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    const root = document.documentElement;
    if (!el) return;
    const measure = () => {
      if (getComputedStyle(el).position === 'sticky') root.style.setProperty('--bottom-bar', `${Math.ceil(el.getBoundingClientRect().height)}px`);
      else root.style.removeProperty('--bottom-bar');
    };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(el);
    return () => {
      observer?.disconnect();
      root.style.removeProperty('--bottom-bar');
    };
  }, []);
  return (
    <div ref={ref} className={className ? `action-bar ${className}` : 'action-bar'} data-testid={testId}>
      {children}
    </div>
  );
}
