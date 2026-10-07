import { useNavigate, useRouterState } from '@tanstack/react-router';
import { type KeyboardEvent, type ReactNode, createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { api, type Me } from '../api/client';
import {
  type Progress,
  type Tour,
  type TourStatus,
  type TourStep,
  cardSide,
  keyAction,
  mainTourFor,
  nextIndex,
  pageTourFor,
  samePage,
  shouldAutoStart,
  stepsFor,
  trapFocus,
} from './model';
import { TourBackdrop, TourCard } from './spotlight';
import { TOURS } from './tours';

// The tour runtime (issue #19, ADR-025): starts the user's first-run tour once, goes to each
// step's page, waits for its data-tour target, lights it up, keeps focus inside the card, and
// remembers on the server how the user left the tour. Closing without "Do not show this again"
// only hides the tour until the next page load; Help replays any tour at any time.

const WAIT_MS = 1500;
const FOCUSABLE = 'button:not([disabled]), input:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])';

/** Tours closed this page load (without "Do not show this again"), so they do not start again. */
const closedThisLoad = new Set<string>();

interface Active {
  tour: Tour;
  steps: TourStep[];
  index: number;
  direction: 1 | -1;
}

interface TourApi {
  /** The user's own first-run tour, for "Take the tour". */
  mainTour: Tour | undefined;
  /** The tour of the page the user is on, for "Show me this page". */
  pageTour: Tour | undefined;
  start: (tour: Tour) => void;
}

const TourContext = createContext<TourApi>({ mainTour: undefined, pageTour: undefined, start: () => undefined });

export function useTour(): TourApi {
  return useContext(TourContext);
}

function query(target: string): HTMLElement | null {
  return document.querySelector<HTMLElement>(`[data-tour="${CSS.escape(target)}"]`);
}

export function TourProvider({ me, saveProgress = true, children }: { me: Me; saveProgress?: boolean; children: ReactNode }) {
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const [progress, setProgress] = useState<Progress>(() => (me.tours ?? {}) as Progress);
  const [active, setActive] = useState<Active | null>(null);
  const [target, setTarget] = useState<HTMLElement | null>(null);
  const [ready, setReady] = useState(false);
  const [side, setSide] = useState<'top' | 'bottom'>('bottom');
  const [remember, setRemember] = useState(false);
  const hole = useRef<HTMLDivElement>(null);
  const card = useRef<HTMLDivElement>(null);
  const title = useRef<HTMLHeadingElement>(null);
  const opener = useRef<HTMLElement | null>(null);

  const mainTour = useMemo(() => mainTourFor(TOURS, me), [me]);
  const pageTour = useMemo(() => pageTourFor(TOURS, me, pathname), [me, pathname]);

  const start = useCallback(
    (tour: Tour) => {
      const steps = stepsFor(tour, me);
      if (steps.length === 0) return;
      opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      setRemember(false);
      setReady(false);
      setActive({ tour, steps, index: 0, direction: 1 });
    },
    [me],
  );

  // First run: the user's main tour starts by itself until it is finished or dismissed.
  useEffect(() => {
    if (!mainTour || closedThisLoad.has(mainTour.id) || !shouldAutoStart(mainTour, progress)) return;
    const timer = window.setTimeout(() => start(mainTour), 400);
    return () => window.clearTimeout(timer);
    // Only on sign-in: progress changes when the tour ends, and must not restart it.
  }, [mainTour]);

  const record = useCallback(
    (tour: Tour, status: TourStatus) => {
      setProgress((p) => ({ ...p, [tour.id]: { status, version: tour.version } }));
      if (!saveProgress) return;
      // Progress is a convenience: a failed save only means the tour may show once more.
      void api
        .PUT('/api/v1/me/tours/{tour_id}', { params: { path: { tour_id: tour.id } }, body: { status, version: tour.version } })
        .catch(() => undefined);
    },
    [saveProgress],
  );

  const end = useCallback(
    (finished: boolean) => {
      if (!active) return;
      if (remember) record(active.tour, 'dismissed');
      else if (finished) record(active.tour, 'completed');
      else closedThisLoad.add(active.tour.id);
      setActive(null);
      setTarget(null);
      const back = opener.current;
      opener.current = null;
      window.setTimeout(() => back?.focus(), 0);
    },
    [active, remember, record],
  );

  const go = useCallback(
    (direction: 1 | -1) => {
      if (!active) return;
      const here = (step: TourStep) => !step.route || samePage(window.location.pathname, step.route);
      const next = nextIndex(active.steps, active.index, direction, (step) => !here(step) || query(step.target ?? '') !== null);
      if (next === -1) {
        if (direction === 1) end(true);
        return;
      }
      setReady(false);
      setActive({ ...active, index: next, direction });
    },
    [active, end],
  );

  // Each step: go to its page, then wait for its target. A missing optional target is skipped in
  // the direction of travel; a missing required one shows the card in the middle instead.
  const step = active?.steps[active.index];
  useEffect(() => {
    if (!active || !step) return;
    if (step.route && !samePage(pathname, step.route)) {
      void navigate({ to: step.route as never });
      return;
    }
    if (!step.target) {
      setTarget(null);
      setReady(true);
      return;
    }
    const wanted = step.target;
    const began = performance.now();
    let frame = 0;
    const look = () => {
      const found = query(wanted);
      if (found) {
        const still = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
        // To the top of the screen (data-tour elements keep a scroll margin), so the card docked
        // at the bottom leaves it in view; one that cannot scroll that far gets the card on top.
        found.scrollIntoView({ block: step.placement === 'top' ? 'end' : 'start', behavior: still ? 'auto' : 'smooth' });
        setTarget(found);
        setReady(true);
        return;
      }
      if (performance.now() - began < WAIT_MS) {
        frame = window.requestAnimationFrame(look);
        return;
      }
      if (step.optional) {
        const next = nextIndex(active.steps, active.index, active.direction, () => true);
        if (next === -1) {
          if (active.direction === 1) end(true);
          else setActive({ ...active, direction: 1 });
        } else setActive({ ...active, index: next });
        return;
      }
      setTarget(null);
      setReady(true);
    };
    look();
    return () => window.cancelAnimationFrame(frame);
    // The step and the page decide; active changes identity on every move.
  }, [step, active?.index, pathname]);

  // The hole follows the target as the page scrolls or the screen turns. Its box is set as CSS
  // custom properties through the CSSOM, never as a style attribute (the CSP has no unsafe-inline).
  useEffect(() => {
    if (!target || !ready) return;
    const place = () => {
      const box = target.getBoundingClientRect();
      const node = hole.current;
      if (node) {
        node.style.setProperty('--tour-top', `${Math.round(box.top - 6)}px`);
        node.style.setProperty('--tour-left', `${Math.round(box.left - 6)}px`);
        node.style.setProperty('--tour-width', `${Math.round(box.width + 12)}px`);
        node.style.setProperty('--tour-height', `${Math.round(box.height + 12)}px`);
      }
      setSide(cardSide(step?.placement, box, window.innerHeight));
    };
    place();
    window.addEventListener('scroll', place, true);
    window.addEventListener('resize', place);
    return () => {
      window.removeEventListener('scroll', place, true);
      window.removeEventListener('resize', place);
    };
  }, [target, ready, step]);

  // Focus goes to the step title, so a screen reader reads it, and cannot leave the card.
  useEffect(() => {
    if (!ready) return;
    title.current?.focus();
    const keep = (event: FocusEvent) => {
      if (card.current && event.target instanceof Node && !card.current.contains(event.target)) title.current?.focus();
    };
    document.addEventListener('focusin', keep);
    return () => document.removeEventListener('focusin', keep);
  }, [ready, step]);

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Tab') {
      const controls = Array.from(card.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []);
      const next = trapFocus(controls.length, controls.indexOf(document.activeElement as HTMLElement), event.shiftKey);
      event.preventDefault();
      controls[next]?.focus();
      return;
    }
    const action = keyAction(event.key);
    if (!action || (action !== 'close' && event.target instanceof HTMLInputElement)) return;
    event.preventDefault();
    if (action === 'close') end(false);
    if (action === 'next') go(1);
    if (action === 'back' && active && active.index > 0) go(-1);
  }

  const value = useMemo(() => ({ mainTour, pageTour, start }), [mainTour, pageTour, start]);

  return (
    <TourContext.Provider value={value}>
      {children}
      {active &&
        step &&
        ready &&
        createPortal(
          <TourBackdrop lit={target !== null} holeRef={hole}>
            <TourCard
              tourTitle={active.tour.title}
              step={step}
              index={active.index}
              total={active.steps.length}
              side={target ? side : 'bottom'}
              canRemember={active.tour.autoStart}
              remember={remember}
              onRemember={setRemember}
              onNext={() => go(1)}
              onBack={() => go(-1)}
              onClose={() => end(false)}
              onKeyDown={onKeyDown}
              cardRef={card}
              titleRef={title}
            />
          </TourBackdrop>,
          document.body,
        )}
    </TourContext.Provider>
  );
}

/**
 * Help in the staff bar: replay the first-run tour, or a tour of this page, at any time. A native
 * dialog (a bottom sheet on a phone) sits in the top layer, so the staff bar's clipping cannot hide
 * it, and the browser keeps focus inside it and closes it on Escape.
 */
export function HelpMenu() {
  const { mainTour, pageTour, start } = useTour();
  const dialog = useRef<HTMLDialogElement>(null);

  function pick(tour: Tour) {
    dialog.current?.close();
    start(tour);
  }

  return (
    <div className="help-menu" data-tour="help-menu">
      <button type="button" className="btn-ghost btn-sm" aria-haspopup="dialog" onClick={() => dialog.current?.showModal()}>
        Help
      </button>
      <dialog ref={dialog} className="sheet help-sheet" aria-labelledby="help-title">
        <h2 id="help-title">Help</h2>
        <div className="form-actions">
          {mainTour && (
            <button type="button" className="btn-primary" onClick={() => pick(mainTour)}>
              Take the tour
            </button>
          )}
          {pageTour && (
            <button type="button" onClick={() => pick(pageTour)}>
              Show me this page
            </button>
          )}
          {!mainTour && !pageTour && <p className="muted">There is no tour for this page yet.</p>}
          <button type="button" className="btn-ghost" onClick={() => dialog.current?.close()}>
            Close
          </button>
        </div>
      </dialog>
    </div>
  );
}
