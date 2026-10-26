import { useEffect, useState, useSyncExternalStore } from 'react';
import { useMedia } from '../../components/side-menu';

// The new-feature badge (issue #603): a small New pill beside a menu entry, and a dot on the tab that leads to it, until this
// person opens the feature once. Then this device remembers it in localStorage and both are gone for good. Every read and write
// of storage is in try/catch: where storage is blocked the badge keeps showing and nothing breaks. Both blink by opacity only,
// the one blink the design guard allows, which the owner asked for, for at most five seconds per page load (WCAG 2.2.2): the
// clock starts with the first badge drawn, and a badge drawn or redrawn later (the drawer opened again, More visited again)
// blinks only for what is left of it, then stands still until the feature is opened. Under reduced motion they never blink.

const keyOf = (feature: string) => `bms.new.${feature}.seen`;
const listeners = new Set<() => void>();

function seen(feature: string): boolean {
  try {
    return localStorage.getItem(keyOf(feature)) === '1';
  } catch {
    return false;
  }
}

/** Remembers on this device that the person has opened `feature`, which hides its badges everywhere at once. */
export function markFeatureSeen(feature: string) {
  if (seen(feature)) return;
  try {
    localStorage.setItem(keyOf(feature), '1');
  } catch {
    return;
  }
  listeners.forEach((notify) => notify());
}

function subscribe(notify: () => void) {
  listeners.add(notify);
  // Another tab that opened the feature hides the badge here too.
  window.addEventListener('storage', notify);
  return () => {
    listeners.delete(notify);
    window.removeEventListener('storage', notify);
  };
}

/** True until this person has opened `feature` on this device. */
export function useIsNew(feature: string): boolean {
  return useSyncExternalStore(subscribe, () => !seen(feature), () => false);
}

const BLINK_MS = 5000;
let blinkStart: number | undefined;

/** How long the badges of this page load may still blink, in ms; the first call starts the clock. */
function blinkLeft(): number {
  const now = performance.now();
  blinkStart ??= now;
  return Math.max(0, blinkStart + BLINK_MS - now);
}

/** Starts the five seconds again, as a new page load does; for tests. */
export function restartBlinkClock() {
  blinkStart = undefined;
}

/** The badge's classes: the blink only within the page load's five seconds, and never under reduced motion. */
function useBlink(base: string): string {
  const still = useMedia('(prefers-reduced-motion: reduce)');
  const [left] = useState(blinkLeft);
  const [blinking, setBlinking] = useState(left > 0);
  useEffect(() => {
    if (!blinking) return;
    const timer = window.setTimeout(() => setBlinking(false), left);
    return () => window.clearTimeout(timer);
  }, [blinking, left]);
  return still || !blinking ? base : `${base} new-blink`;
}

/** The New pill, read out as ", New" after the entry's name. */
export function NewBadge({ feature }: { feature: string }) {
  const fresh = useIsNew(feature);
  const className = useBlink('new-badge');
  if (!fresh) return null;
  return (
    <span className={className}>
      <span className="visually-hidden">, New</span>
      <span aria-hidden="true">New</span>
    </span>
  );
}

/** The dot on a tab's icon. It is hidden from screen readers: the tab says New in its own name (useIsNew). */
export function NewDot({ feature }: { feature: string }) {
  const fresh = useIsNew(feature);
  const className = useBlink('new-dot');
  return fresh ? <span className={className} aria-hidden="true" data-testid="new-dot" /> : null;
}
