import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { icons } from '../../../components/icons';
import { announceToSheet } from '../../../app/ui/announce';

// A toast (the .toast class of app/ui/feedback.css, docs/ui-rules.md rule 15): a dark neutral pill at the bottom with a leading
// icon for its kind, raised on top of the on-screen success message and gone after four seconds. A screen calls `useToast()`,
// renders `toast` once and calls `show(message, 'success')` when a write succeeds, `show(message, 'error')` when it fails and
// `show(message, 'info')` for anything else (a refusal, or a change that waits for approval). The tone has no default, so a
// refusal can never draw the green check by omission (#508, docs/ui-rules.md rule 16).

export const TOAST_MS = 4000;

export type ToastTone = 'success' | 'error' | 'info';

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

export function useToast(ms: number = TOAST_MS, silent = false): { show: (message: string, tone: ToastTone) => void; toast: ReactNode } {
  const [shown, setShown] = useState<{ message: string; tone: ToastTone } | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const clear = () => {
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = null;
  };
  useEffect(() => clear, []);
  const show = useCallback(
    (message: string, tone: ToastTone) => {
      clear();
      setShown({ message, tone });
      timer.current = setTimeout(() => setShown(null), ms);
    },
    [ms],
  );
  return { show, toast: shown ? <Toast message={shown.message} tone={shown.tone} silent={silent} /> : null };
}
