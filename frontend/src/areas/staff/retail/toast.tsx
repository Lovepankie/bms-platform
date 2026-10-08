import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';

// A toast (the .toast class of app/ui/feedback.css): a short confirmation raised on top of the on-screen
// success message, announced politely and gone after a few seconds. A screen calls `useToast()`, renders
// `toast` once and calls `show(message)` when a write succeeds.

export const TOAST_MS = 4000;

export function Toast({ message }: { message: string }) {
  return (
    <p role="status" aria-live="polite" className="toast">
      {message}
    </p>
  );
}

export function useToast(ms: number = TOAST_MS): { show: (message: string) => void; toast: ReactNode } {
  const [message, setMessage] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const clear = () => {
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = null;
  };
  useEffect(() => clear, []);
  const show = useCallback(
    (next: string) => {
      clear();
      setMessage(next);
      timer.current = setTimeout(() => setMessage(null), ms);
    },
    [ms],
  );
  return { show, toast: message ? <Toast message={message} /> : null };
}
