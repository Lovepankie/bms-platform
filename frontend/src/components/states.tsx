import type { ReactNode } from 'react';
import { illustrations } from './illustrations';

// The shared state components (#106, docs/ui/design-system.md): an empty list, a finished or failed
// step, a branded loader and skeleton rows. Each states its meaning in words; the illustration of a
// finished step and the motion only help, and the motion stops under prefers-reduced-motion (base.css).

/**
 * An empty list in one plain line (issue #526): the title and its one short follow-on sentence read as one line, with at most
 * one action under it. No illustration and no dashed box, the same look as the kit's EmptyState (.empty-line in kit.css).
 */
export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty-line">
      <p>
        {title}
        {children && <> {children}</>}
      </p>
      {action}
    </div>
  );
}

/** A finished or failed step: the success or error illustration, a title and what to do next. */
export function StatusPanel({ tone, title, children, action }: { tone: 'success' | 'error'; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className={`status-panel status-panel-${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
      <span className="state-art">{illustrations[tone]}</span>
      <p className="state-title">{title}</p>
      {children && <p className="state-text">{children}</p>}
      {action && <div className="state-action">{action}</div>}
    </div>
  );
}

/** The branded loading state: the accent ring around a tinted mark, with the label in words. */
export function BrandLoader({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="brand-loader" role="status">
      <svg className="brand-loader-art" viewBox="0 0 48 48" aria-hidden="true" focusable="false">
        <circle className="ill-tint" cx="24" cy="24" r="20" />
        <circle className="brand-loader-track" cx="24" cy="24" r="20" />
        <circle className="brand-loader-arc" cx="24" cy="24" r="20" />
        <rect className="ill-primary brand-loader-core" x="17" y="17" width="14" height="14" rx="4" />
      </svg>
      <span>{label}</span>
    </div>
  );
}

/** Placeholder rows while a list loads; screen readers hear the label once. */
export function SkeletonList({ rows = 3, label = 'Loading' }: { rows?: number; label?: string }) {
  return (
    <div className="skeleton-list" role="status" aria-busy="true">
      <span className="visually-hidden">{label}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton-card" aria-hidden="true">
          <span className="skeleton skeleton-title" />
          <span className="skeleton skeleton-line" />
        </div>
      ))}
    </div>
  );
}
