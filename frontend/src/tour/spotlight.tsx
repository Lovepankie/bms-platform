import type { KeyboardEvent, ReactNode, Ref, TouchEvent } from 'react';
import { useRef } from 'react';
import type { TourStep } from './model';
import { announcement, swipeAction } from './model';

// The spotlight card (issue #19): a modal dialog docked to the top or bottom of the screen, away
// from the lit target, with progress dots, Back, Next or Finish, a Close button and, for a
// first-run tour, "Do not show this again". It only draws; the host decides what it shows.
// Everything is a CSS class (app/ui/tour.css); the hole around the target is placed by the host
// through CSS custom properties, so no style attribute is ever written into the markup.

export interface CardProps {
  tourTitle: string;
  step: TourStep;
  index: number;
  total: number;
  side: 'top' | 'bottom';
  /** Offer "Do not show this again" (first-run tours only). */
  canRemember: boolean;
  remember: boolean;
  onRemember: (value: boolean) => void;
  onNext: () => void;
  onBack: () => void;
  onClose: () => void;
  onKeyDown?: (event: KeyboardEvent<HTMLDivElement>) => void;
  cardRef?: Ref<HTMLDivElement>;
  titleRef?: Ref<HTMLHeadingElement>;
}

export function TourCard(props: CardProps) {
  const { step, index, total } = props;
  const last = index === total - 1;
  const touch = useRef<{ x: number; y: number } | null>(null);

  function touchStart(event: TouchEvent) {
    const t = event.touches[0];
    touch.current = t ? { x: t.clientX, y: t.clientY } : null;
  }

  function touchEnd(event: TouchEvent) {
    const start = touch.current;
    const t = event.changedTouches[0];
    touch.current = null;
    if (!start || !t) return;
    const action = swipeAction(t.clientX - start.x, t.clientY - start.y);
    if (action === 'next') props.onNext();
    if (action === 'back' && index > 0) props.onBack();
  }

  return (
    <div
      ref={props.cardRef}
      className={`tour-card tour-card-${props.side}`}
      role="dialog"
      aria-modal="true"
      aria-labelledby="tour-title"
      aria-describedby="tour-body"
      onKeyDown={props.onKeyDown}
      onTouchStart={touchStart}
      onTouchEnd={touchEnd}
    >
      <div className="tour-card-head">
        <p className="tour-count">
          <span className="eyebrow">{props.tourTitle}</span>
          <span>
            Step {index + 1} of {total}
          </span>
        </p>
        <button type="button" className="btn-ghost tour-close" onClick={props.onClose} aria-label="Close the tour">
          <span aria-hidden="true">&#x2715;</span>
        </button>
      </div>
      <h2 id="tour-title" ref={props.titleRef} tabIndex={-1}>
        {step.title}
      </h2>
      <p id="tour-body">{step.body}</p>
      <ol className="tour-dots" aria-hidden="true">
        {Array.from({ length: total }, (_, i) => (
          <li key={i} className={i === index ? 'is-current' : i < index ? 'is-done' : undefined} />
        ))}
      </ol>
      {props.canRemember && (
        <label className="tour-remember">
          <input type="checkbox" checked={props.remember} onChange={(e) => props.onRemember(e.target.checked)} />
          Do not show this again
        </label>
      )}
      <div className="tour-actions">
        {index > 0 ? (
          <button type="button" onClick={props.onBack}>
            Back
          </button>
        ) : (
          <button type="button" className="btn-ghost" onClick={props.onClose}>
            Skip the tour
          </button>
        )}
        <button type="button" className="btn-primary" onClick={props.onNext}>
          {last ? 'Finish' : 'Next'}
        </button>
      </div>
      <p className="visually-hidden" aria-live="polite">
        {announcement(index, total, step)}
      </p>
    </div>
  );
}

/** The dimmed layer: a click shield, plus the hole around the target when there is one. */
export function TourBackdrop({ lit, holeRef, children }: { lit: boolean; holeRef?: Ref<HTMLDivElement>; children: ReactNode }) {
  return (
    <div className="tour-layer">
      <div className={lit ? 'tour-backdrop' : 'tour-backdrop is-dim'} aria-hidden="true" />
      {lit && <div ref={holeRef} className="tour-hole" aria-hidden="true" />}
      {children}
    </div>
  );
}
