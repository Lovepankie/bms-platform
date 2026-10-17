import { useCallback, useState, type ReactNode } from 'react';

// After a save whose answer may have been lost (no answer, a timeout, a server error) the record may or may not exist. Saving again
// could make a second one, and these routes have no key the server honours. So the screen looks the record up first and blocks
// saving until that look has SUCCEEDED: found means it is already saved (open it), not found means it is safe to save again, and a
// look that itself fails keeps the block with a button to look again. Never swallow the failure of the look.

export type GuardState<T> = { phase: 'clear' } | { phase: 'checking' } | { phase: 'found'; item: T } | { phase: 'unsure' };

export interface SaveGuard<T> {
  state: GuardState<T>;
  /** True while saving must not happen. */
  blocked: boolean;
  /** Look up what may have been saved. `lookup` resolves with the record or null, and rejects when the look itself failed. */
  check(lookup: () => Promise<T | null>): Promise<void>;
  /** The person says this is a different record and wants to save anyway (only where that is a real choice). */
  release(): void;
  /** For a look that failed: the lookup to repeat. */
  last: (() => Promise<T | null>) | null;
}

export function useSaveGuard<T>(): SaveGuard<T> {
  const [state, setState] = useState<GuardState<T>>({ phase: 'clear' });
  const [last, setLast] = useState<(() => Promise<T | null>) | null>(null);
  const check = useCallback(async (lookup: () => Promise<T | null>) => {
    setLast(() => lookup);
    setState({ phase: 'checking' });
    try {
      const item = await lookup();
      setState(item ? { phase: 'found', item } : { phase: 'clear' });
    } catch {
      setState({ phase: 'unsure' });
    }
  }, []);
  const release = useCallback(() => setState({ phase: 'clear' }), []);
  return { state, blocked: state.phase !== 'clear', check, release, last };
}

/** The words for the state, with the way out. `found` shows what the caller gives (a link to the saved record). */
export function SaveGuardNotice<T>({ guard, what, found, canRelease }: { guard: SaveGuard<T>; what: string; found: (item: T) => ReactNode; canRelease?: boolean }) {
  const { state } = guard;
  if (state.phase === 'clear') return null;
  if (state.phase === 'checking') return <p role="status" className="alert alert-info">The answer did not come back. Checking whether the {what} was saved.</p>;
  if (state.phase === 'found') {
    return (
      <div role="alert" className="alert alert-warning stack">
        <p>The answer did not come back, and the {what} is already saved. It was not saved a second time.</p>
        <p className="cluster">
          {found(state.item)}
          {canRelease && <button type="button" className="btn-sm btn-ghost" onClick={guard.release}>This is a different one, save again</button>}
        </p>
      </div>
    );
  }
  return (
    <div role="alert" className="alert alert-warning stack">
      <p>The answer did not come back, and we could not check whether the {what} was saved. Saving again now could make it twice.</p>
      {guard.last && <button type="button" className="btn-sm" onClick={() => void guard.check(guard.last as () => Promise<T | null>)}>Check again</button>}
    </div>
  );
}
