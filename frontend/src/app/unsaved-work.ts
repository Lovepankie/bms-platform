import { useEffect, useId } from 'react';

// What a reload would cost the person right now (#576): a cart on Sell or a form with typed changes. The app updates itself only
// when nothing is registered here, so it never reloads in the middle of a sale or a half-typed form. A screen sets its entry while
// it holds work and clears it when the work is saved, discarded or the screen closes.

const holders = new Set<string>();
const listeners = new Set<() => void>();

const changed = () => listeners.forEach((listener) => listener());

/** Records that `id` holds unsaved work (`dirty`) or no longer does. */
export function setUnsavedWork(id: string, dirty: boolean): void {
  if (dirty === holders.has(id)) return;
  if (dirty) holders.add(id);
  else holders.delete(id);
  changed();
}

/** Whether any screen holds unsaved work. */
export const hasUnsavedWork = (): boolean => holders.size > 0;

/** Calls `listener` whenever the answer of hasUnsavedWork may have changed; returns the unsubscribe. */
export function onUnsavedWorkChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => void listeners.delete(listener);
}

/** Registers the calling screen's unsaved work while `dirty` is true, and clears it when the screen closes. */
export function useUnsavedWork(dirty: boolean): void {
  const id = useId();
  useEffect(() => {
    setUnsavedWork(id, dirty);
  }, [id, dirty]);
  useEffect(() => () => setUnsavedWork(id, false), [id]);
}
