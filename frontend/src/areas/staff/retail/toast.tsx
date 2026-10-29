import { MutationCache, useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { icons } from '../../../components/icons';
import { announceToSheet } from '../../../app/ui/announce';
import { BusyButton } from '../../../app/ui/BusyButton';

// A toast (the .toast class of app/ui/feedback.css, docs/ui-rules.md rule 15): a dark neutral pill at the bottom with a leading
// icon for its kind, raised on top of the on-screen success message and gone after six seconds (four was too short for slow readers, Hillary, 29 Oct 2026). A screen calls `useToast()`,
// renders `toast` once and calls `show(message, 'success')` when a write succeeds, `show(message, 'error')` when it fails and
// `show(message, 'info')` for anything else (a refusal, or a change that waits for approval). The tone has no default, so a
// refusal can never draw the green check by omission (#508, docs/ui-rules.md rule 16).

export const TOAST_MS = 6000;

export type ToastTone = 'success' | 'error' | 'info';

export type ShowToast = (message: string, tone: ToastTone) => void;

/**
 * The screen's own toast, for a part inside the screen that writes on its own (the category picker's Add, #578), so a screen
 * shows one toast and never two stacked. The screen wraps that part in a provider with its `show`.
 */
export const ScreenToast = createContext<ShowToast | null>(null);

const TONE_ICON = { success: icons.check, error: icons.error, info: icons.info } as const;

/**
 * The toast itself. It is announced politely (an error assertively) unless `silent`, for a screen whose on-screen message
 * already says the same to assistive technology. Each names its aria-live, so a sheet open over the page (Radix hides the rest of
 * the page then, but never a live region) still lets it be heard.
 */
/** The one action a toast may carry (rule 15), such as Undo (#682). */
export type ToastAction = { label: string; run: () => void; busy?: boolean };

export function Toast({ message, tone, silent = false, action }: { message: string; tone: ToastTone; silent?: boolean; action?: ToastAction }) {
  const announce = silent ? { 'aria-hidden': true } : tone === 'error' ? { role: 'alert', 'aria-live': 'assertive' as const } : { role: 'status', 'aria-live': 'polite' as const };
  const pill = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (!silent) announceToSheet(message, pill.current, tone === 'error');
  }, [message, silent, tone]);
  return (
    <p ref={pill} {...announce} className={`toast toast-${tone}`}>
      <span className="toast-icon">{TONE_ICON[tone]}</span>
      <span>{message}</span>
      {action && (
        // Undo writes, so it is a BusyButton (design system rule 6): busy, and said Working, while its request runs.
        <BusyButton type="button" className="toast-action" busy={action.busy ?? false} onClick={action.run}>
          {action.label}
        </BusyButton>
      )}
    </p>
  );
}

export function useToast(ms: number = TOAST_MS, silent = false): { show: (message: string, tone: ToastTone, action?: ToastAction) => void; toast: ReactNode } {
  // `error` is the error a later info was said after, so a second info replaces the first instead of piling up.
  const [shown, setShown] = useState<{ message: string; tone: ToastTone; error?: string; action?: ToastAction } | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const clear = () => {
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = null;
  };
  useEffect(() => clear, []);
  const show = useCallback(
    (message: string, tone: ToastTone, action?: ToastAction) => {
      clear();
      // An info never hides an error still showing (review of #616: a photo not saved after a count not saved): it is said after
      // the error, in the error's toast, so the one toast a screen shows keeps what went wrong.
      setShown((was) => {
        if (was?.tone !== 'error' || tone !== 'info') return { message, tone, action };
        const error = was.error ?? was.message;
        return { message: `${error} ${message}`, tone: 'error', error };
      });
      // A toast with an action stays a little longer, so there is time to reach it, and stays up while its action works.
      if (!action?.busy) timer.current = setTimeout(() => setShown(null), action ? ms * 2 : ms);
    },
    [ms],
  );
  return { show, toast: shown ? <Toast message={shown.message} tone={shown.tone} silent={silent} action={shown.action} /> : null };
}

// JUSTIFICATION-A3: #682 asks for one shared success toast for every write; nothing raises a toast from the query client yet.
/**
 * What a write says when it works (issue #682): most people read very little, so every save, sale, add, edit, delete, move,
 * purchase and payment answers with a short toast in plain words, such as 'Sale saved' or 'Payment recorded'. A mutation names
 * its words in `meta: { success }`, a sentence or a function of the result and what was sent (null says nothing, for an
 * outcome that is not a success); `doneToasts` raises them in the one `DoneToast` the app mounts beside the router. A write
 * that can be taken back names how in `meta.undo`, and the toast then carries Undo. A write that is not a mutation (a
 * hand-rolled try and catch) calls `sayDone(words)` when it works. A screen that already says it worked, in its own success
 * toast or a success note or screen, keeps that and sets neither, so nothing is said twice. `busy-guard.test.ts` fails a
 * staff write that says nothing, unless a `// toast-exempt: <reason>` line sits above it.
 */
export type DoneWords = string | ((data: never, variables: never) => string | null);

/** How to take a write back: a function of the result and what was sent that returns the request that undoes it, or null. */
export type DoneUndo = (data: never, variables: never) => (() => Promise<unknown>) | null;

declare module '@tanstack/react-query' {
  interface Register {
    mutationMeta: { success?: DoneWords; undo?: DoneUndo };
  }
}

type Done = { message: string; undo?: () => Promise<unknown> };

const doneListeners = new Set<(done: Done) => void>();

/** Says in the app's DoneToast that a write worked, with Undo when `undo` is given. */
export function sayDone(message: string, undo?: () => Promise<unknown>): void {
  doneListeners.forEach((hear) => hear({ message, undo }));
}

/**
 * The query client's mutation cache: a mutation that succeeds says its `meta.success` in the app's DoneToast. The words are
 * built inside a try, because this runs inside the mutation: a throw here would turn a write the server took into an error.
 */
export function doneToasts(): MutationCache {
  return new MutationCache({
    onSuccess: (data, variables, _context, mutation) => {
      const { success, undo } = mutation.meta ?? {};
      try {
        const message = typeof success === 'function' ? (success as (d: unknown, v: unknown) => string | null)(data, variables) : success;
        const back = undo ? (undo as (d: unknown, v: unknown) => (() => Promise<unknown>) | null)(data, variables) : null;
        if (message) sayDone(message, back ?? undefined);
      } catch {
        // The write worked; only its words could not be built, so nothing is said.
      }
    },
  });
}

/** Where the toast goes: into an open modal dialog (else it sits under its backdrop), else the layout beside the side menu. */
function toastHome(): Element {
  try {
    const modal = document.querySelector('dialog:modal');
    if (modal) return modal;
  } catch {
    // A browser without :modal has no top layer to sit under.
  }
  return document.querySelector('.nav-layout') ?? document.body;
}

/** The app's one success toast for the writes that name their words in `meta.success` or call `sayDone`. */
export function DoneToast() {
  const client = useQueryClient();
  const { show, toast } = useToast();
  useEffect(() => {
    const hear = ({ message, undo }: Done) => {
      let pressed = false;
      const run = () => {
        if (pressed || !undo) return;
        pressed = true;
        show(message, 'success', { label: 'Undo', run, busy: true });
        // Inside a promise, so an Undo that throws at once still ends on its error words, never stuck on Working.
        new Promise((resolve) => resolve(undo()))
          .then(
            () => show('Undone', 'success'),
            () => show('Could not undo. Try again from the screen.', 'error'),
          )
          .finally(() => void client.invalidateQueries());
      };
      show(message, 'success', undo ? { label: 'Undo', run } : undefined);
    };
    doneListeners.add(hear);
    return () => {
      doneListeners.delete(hear);
    };
  }, [show, client]);
  // The home is found again after every change to the page while the toast shows: a sheet that closes on save does so in the
  // same render that raises the toast, and would take the toast away with it (review of #685).
  const [home, setHome] = useState<Element>(() => toastHome());
  useEffect(() => {
    if (!toast) return;
    const look = () => setHome((was) => (toastHome() === was ? was : toastHome()));
    look();
    const watch = new MutationObserver(look);
    watch.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['open'] });
    return () => watch.disconnect();
  }, [toast]);
  return toast ? createPortal(toast, home.isConnected ? home : toastHome()) : null;
}

/** What the toast says when an item goes on a form's lines (issue #498): the quantity it now has, or that one is still to type. */
export function addedMessage(name: string | undefined, qty?: string): string {
  const what = name || 'the item';
  return qty && qty.trim() !== '' ? `Added ${what}, now ${qty.trim()}` : `Added ${what}. Type how many.`;
}

/**
 * Every add on Buy stock, Move stock and Count stock says so, as the sale does (issue #498): the toast above, raised for a
 * pick from the search and for a scan alike, and the line added or topped up flashes once (`.line-added` of
 * app/ui/feedback.css, still under reduced motion). `flashOf(key)` is rendered inside the line's card; `n` changes on every
 * add, so a second scan of the same item flashes again, and the flash unmounts when it has run.
 */
export function useItemAdded(): {
  added: (key: string, name: string | undefined, qty?: string) => void;
  already: (message: string) => void;
  flashOf: (key: string) => ReactNode;
  /** The same toast, for anything else the screen says (ScreenToast). */
  show: ShowToast;
  toast: ReactNode;
} {
  const { show, toast } = useToast(2500);
  const [flash, setFlash] = useState<{ key: string; n: number } | null>(null);
  const added = useCallback(
    (key: string, name: string | undefined, qty?: string) => {
      if (typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function') navigator.vibrate(10);
      setFlash((f) => ({ key, n: (f?.n ?? 0) + 1 }));
      show(addedMessage(name, qty), 'success');
    },
    [show],
  );
  const already = useCallback((message: string) => show(message, 'info'), [show]);
  const flashOf = (key: string) => (flash?.key === key ? <span key={flash.n} className="line-added" aria-hidden="true" data-testid="line-added" onAnimationEnd={() => setFlash(null)} /> : null);
  return { added, already, flashOf, show, toast };
}
