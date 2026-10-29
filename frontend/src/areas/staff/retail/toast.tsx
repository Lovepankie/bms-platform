import { createContext, useCallback, useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { icons } from '../../../components/icons';
import { announceToSheet } from '../../../app/ui/announce';

// A toast (the .toast class of app/ui/feedback.css, docs/ui-rules.md rule 15): a dark neutral pill at the bottom with a leading
// icon for its kind, raised on top of the on-screen success message and gone after four seconds. A screen calls `useToast()`,
// renders `toast` once and calls `show(message, 'success')` when a write succeeds, `show(message, 'error')` when it fails and
// `show(message, 'info')` for anything else (a refusal, or a change that waits for approval). The tone has no default, so a
// refusal can never draw the green check by omission (#508, docs/ui-rules.md rule 16).

export const TOAST_MS = 4000;

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
export function Toast({ message, tone, silent = false }: { message: string; tone: ToastTone; silent?: boolean }) {
  const announce = silent ? { 'aria-hidden': true } : tone === 'error' ? { role: 'alert', 'aria-live': 'assertive' as const } : { role: 'status', 'aria-live': 'polite' as const };
  const pill = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (!silent) announceToSheet(message, pill.current, tone === 'error');
  }, [message, silent, tone]);
  return (
    <p ref={pill} {...announce} className={`toast toast-${tone}`}>
      <span className="toast-icon">{TONE_ICON[tone]}</span>
      <span>{message}</span>
    </p>
  );
}

// Issue #680: one toast at a time in the whole app. Every show takes the next number, and only the toast holding the newest one is
// drawn, so the layout's "You are now in <shop>." replaces a screen's toast, and a screen's next toast replaces it.
let newest = 0;
const newestListeners = new Set<() => void>();
const takeNewest = () => {
  newest += 1;
  for (const listener of newestListeners) listener();
  return newest;
};
const subscribeNewest = (listener: () => void) => {
  newestListeners.add(listener);
  return () => void newestListeners.delete(listener);
};

export function useToast(ms: number = TOAST_MS, silent = false): { show: (message: string, tone: ToastTone) => void; toast: ReactNode } {
  // `error` is the error a later info was said after, so a second info replaces the first instead of piling up.
  const [shown, setShown] = useState<{ message: string; tone: ToastTone; error?: string; turn: number } | null>(null);
  const current = useSyncExternalStore(subscribeNewest, () => newest, () => newest);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const clear = () => {
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = null;
  };
  useEffect(() => clear, []);
  const show = useCallback(
    (message: string, tone: ToastTone) => {
      clear();
      const turn = takeNewest();
      // An info never hides an error still showing (review of #616: a photo not saved after a count not saved): it is said after
      // the error, in the error's toast, so the one toast a screen shows keeps what went wrong.
      setShown((was) => {
        if (was?.tone !== 'error' || tone !== 'info') return { message, tone, turn };
        const error = was.error ?? was.message;
        return { message: `${error} ${message}`, tone: 'error', error, turn };
      });
      timer.current = setTimeout(() => setShown(null), ms);
    },
    [ms],
  );
  return { show, toast: shown && shown.turn === current ? <Toast message={shown.message} tone={shown.tone} silent={silent} /> : null };
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
