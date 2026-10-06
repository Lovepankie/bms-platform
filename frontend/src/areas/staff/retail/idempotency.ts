import { useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react';

/** A fresh key for one attempt at a money-moving write (chapter 7 section 7.8). */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256));
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = bytes.map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Where a form's draft and its key are kept: this tab only, surviving a reload (#77). */
export interface DraftStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

function tabStore(): DraftStore | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage;
  } catch {
    return null;
  }
}

interface Saved<T> { key: string; draft: T }

/** The saved draft and key under `name`, or a fresh key and `initial` when none is readable. */
export function loadDraft<T>(store: DraftStore | null, name: string, initial: T): Saved<T> {
  try {
    const raw = store?.getItem(`retail-draft:${name}`);
    if (raw) {
      const saved = JSON.parse(raw) as Partial<Saved<T>>;
      if (typeof saved.key === 'string' && saved.key !== '' && saved.draft !== undefined) return { key: saved.key, draft: saved.draft };
    }
  } catch {
    // Unreadable or blocked storage: start a fresh form.
  }
  return { key: newIdempotencyKey(), draft: initial };
}

export function saveDraft<T>(store: DraftStore | null, name: string, saved: Saved<T>): void {
  try {
    store?.setItem(`retail-draft:${name}`, JSON.stringify(saved));
  } catch {
    // Full or blocked storage: the form still works, it just does not survive a reload.
  }
}

export function dropDraft(store: DraftStore | null, name: string): void {
  try {
    store?.removeItem(`retail-draft:${name}`);
  } catch {
    // Nothing to do.
  }
}

/**
 * A money-moving form's draft and its one `Idempotency-Key`, kept together in this tab's
 * sessionStorage under `name` until `finish` is called (after the server answered with success, or
 * when the user abandons the draft). A reload, or a retry after a lost answer, restores both, so the
 * same entry posts once. `discard` drops the draft and starts a fresh one with a fresh key. Mount a
 * new form (a new `key` prop) for the next record.
 */
export function usePersistedDraft<T>(
  name: string,
  initial: T,
): { key: string; draft: T; setDraft: Dispatch<SetStateAction<T>>; finish: () => void; discard: () => void } {
  const store = useRef(tabStore()).current;
  const blank = useRef(initial).current;
  const [saved, setSaved] = useState(() => loadDraft(store, name, blank));
  const done = useRef(false);
  useEffect(() => {
    if (!done.current) saveDraft(store, name, saved);
  }, [store, name, saved]);
  const setDraft = useCallback<Dispatch<SetStateAction<T>>>(
    (next) => setSaved((s) => ({ key: s.key, draft: typeof next === 'function' ? (next as (d: T) => T)(s.draft) : next })),
    [],
  );
  const finish = useCallback(() => {
    done.current = true;
    dropDraft(store, name);
  }, [store, name]);
  const discard = useCallback(() => {
    dropDraft(store, name);
    setSaved({ key: newIdempotencyKey(), draft: blank });
  }, [store, name, blank]);
  return { key: saved.key, draft: saved.draft, setDraft, finish, discard };
}
