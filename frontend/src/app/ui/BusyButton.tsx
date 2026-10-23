import { useEffect, useRef, useSyncExternalStore, type ButtonHTMLAttributes, type MouseEvent } from 'react';

// The one busy button (#456): every button that writes (a sale, a payment, an invitation, a sign-in)
// shows a small spinner in its end padding while its request runs and cannot be pressed twice. The label
// never changes, so the width never jumps; the button is aria-busy and aria-disabled (not disabled, so it
// keeps keyboard focus) and its own press guard drops every press. Pass the mutation's isPending (or the
// screen's own submitting state) as `busy`; every other prop is a normal <button> prop.

// One announcer for the whole app: a list of twenty rows sharing one busy flag says "Working" once, not
// twenty times. Each busy button counts itself in while busy; the shell's BusyAnnouncer says Working while
// the count is above zero and clears when the last one is done.
let busyCount = 0;
const listeners = new Set<() => void>();
const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};
const working = () => busyCount > 0;
// The modal announcers mounted now: while one is, the shell's steps aside, so a screen reader hears one Working at a time.
let modalCount = 0;
const inModal = () => modalCount > 0;

function countBusy(delta: number) {
  const before = working();
  busyCount += delta;
  if (working() !== before) listeners.forEach((listener) => listener());
}

/**
 * The polite live region the busy buttons speak through; the app shell mounts it once. An aria-modal dialog hides the shell's
 * one from a screen reader, so a modal that holds a busy button (the phone menu's Sign out) mounts a `modal` one inside itself,
 * and the shell's renders nothing while it is there.
 */
export function BusyAnnouncer({ modal = false }: { modal?: boolean }) {
  const now = useSyncExternalStore(subscribe, working, working);
  const covered = useSyncExternalStore(subscribe, inModal, inModal);
  useEffect(() => {
    if (!modal) return;
    modalCount += 1;
    listeners.forEach((listener) => listener());
    return () => {
      modalCount -= 1;
      listeners.forEach((listener) => listener());
    };
  }, [modal]);
  if (!modal && covered) return null;
  return (
    <span className="visually-hidden" role="status" data-busy-announcer="">
      {now ? 'Working' : ''}
    </span>
  );
}

// The longest a press or submit holds a button whose busy never turns on (an onClick that never settles).
const HOLD_MAX = 10_000;

export type BusyButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { busy: boolean };

export function BusyButton({ busy, disabled, className, onClick, children, ...rest }: BusyButtonProps) {
  const button = useRef<HTMLButtonElement>(null);
  const busyNow = useRef(busy);
  busyNow.current = busy;
  // A second press can land before the pending state renders (a double click is two events in one
  // task), so a press holds the button until busy shows, or until the press's own work is over (an
  // onClick that awaits or throws) and one more task has passed (a form that refused its input). A
  // submit of its form that this button did not send holds it the same way.
  const held = useRef(false);
  const holds = useRef(0);
  const holdTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    if (!busy) {
      held.current = false;
      return;
    }
    countBusy(1);
    return () => countBusy(-1);
  }, [busy]);
  // A button gone while busy (its notice closed by the work it ran) never sees busy end, so its HOLD_MAX timer goes with it.
  useEffect(() => () => clearTimeout(holdTimer.current), []);

  // Enter in a field presses the form's first submit button, which the press guard covers; a form with
  // another submit button first, or one submitted from code, still submits, so while busy (or held by a
  // press of this button) the form drops any submit this button did not send before the screen sees it.
  // A submit that gets through holds the button too, so two submits from code in one task send once.
  useEffect(() => {
    const owner = button.current?.form;
    if (!owner || (rest.type !== undefined && rest.type !== 'submit')) return;
    const drop = (e: SubmitEvent) => {
      if (busyNow.current || (held.current && e.submitter !== button.current)) {
        e.preventDefault();
        e.stopImmediatePropagation();
      } else if (!held.current) {
        hold()();
      }
    };
    owner.addEventListener('submit', drop);
    return () => owner.removeEventListener('submit', drop);
  }, [rest.type]);

  // Holds the button for one press or submit and returns the call that says its work is over. Two tasks
  // after that the query library and React have rendered any pending state it started; if busy shows,
  // the busy effect releases the hold when busy ends. If busy never shows the hold ends after HOLD_MAX at
  // the latest, so the button cannot stick. A newer hold is never released by an older one. A released hold clears its
  // HOLD_MAX timer, so nothing keeps running once the work is over.
  function hold() {
    held.current = true;
    const id = ++holds.current;
    const release = () => {
      if (holds.current !== id || busyNow.current) return;
      held.current = false;
      clearTimeout(holdTimer.current);
    };
    clearTimeout(holdTimer.current);
    holdTimer.current = setTimeout(release, HOLD_MAX);
    return () => void setTimeout(() => setTimeout(release));
  }

  function press(e: MouseEvent<HTMLButtonElement>) {
    if (busyNow.current || held.current) {
      e.preventDefault();
      return;
    }
    const done = hold();
    let result: unknown;
    try {
      result = onClick?.(e);
    } finally {
      if (result instanceof Promise) void result.finally(done);
      else done();
    }
  }

  return (
    <button
      ref={button}
      {...rest}
      className={className ? `${className} busy-button` : 'busy-button'}
      disabled={busy ? undefined : disabled}
      aria-disabled={busy || rest['aria-disabled'] || undefined}
      aria-busy={busy || undefined}
      onClick={press}
    >
      {children}
      {busy && <span className="busy-spinner" aria-hidden="true" />}
    </button>
  );
}
