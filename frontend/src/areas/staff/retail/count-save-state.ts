import { useEffect, useRef, useState } from 'react';
import { retail, type StocktakeLine } from '../../../api/retail';
import { plural } from '../../../app/glossary';
import { useDeviceOnline } from '../../../app/refresh-button';
import { milliOf, qtyString, showQty } from './maths';
import type { PackSize } from './units-state';

// Count stock keeps every count as it is typed (issue #681): on this device at once, per business (the host), branch and
// person, and as a server draft count shortly after, so a reload, a lost connection or a deploy never loses a number. The
// rules live here, out of the page, so they can be tested.

/** What the count page keeps of a count in progress. */
export interface SavedCount {
  counts: Record<string, string>;
  packCounts: Record<string, { text: string; pack: PackSize }>;
  listed: string[];
  done: string[];
  /** The server draft that holds these counts, once one is made. */
  draftId: string | null;
  /** Drafts replaced while the connection was down, discarded once it is back. */
  stale: string[];
}

export const countKey = (host: string, branchId: string, userId: string) => `bms.count.${host}.${branchId}.${userId}`;

const strings = (v: unknown): v is string[] => Array.isArray(v) && v.every((x) => typeof x === 'string');
const textMap = (v: unknown): v is Record<string, string> =>
  typeof v === 'object' && v !== null && !Array.isArray(v) && Object.values(v).every((x) => typeof x === 'string');

/** The saved count, or null when there is none, storage is blocked or what is stored is not a count. */
export function readCount(key: string): SavedCount | null {
  try {
    const raw = localStorage.getItem(key);
    if (raw === null) return null;
    const v = JSON.parse(raw) as Partial<SavedCount>;
    if (!textMap(v.counts) || !strings(v.listed) || !strings(v.done)) return null;
    const packCounts = typeof v.packCounts === 'object' && v.packCounts !== null ? v.packCounts : {};
    return {
      counts: v.counts,
      packCounts,
      listed: v.listed,
      done: v.done,
      draftId: typeof v.draftId === 'string' ? v.draftId : null,
      stale: strings(v.stale) ? v.stale : [],
    };
  } catch {
    return null;
  }
}

/** Saves the count on this device; false when storage is blocked or full, so the page never says Saved for it. */
export function writeCount(key: string, saved: SavedCount): boolean {
  try {
    localStorage.setItem(key, JSON.stringify(saved));
    return true;
  } catch {
    return false;
  }
}

export function clearCount(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // Blocked storage held nothing.
  }
}

/** Where the server copy stands: being sent, sent, waiting for the connection, or refused. */
export type SendState = 'sending' | 'sent' | 'offline' | 'failed';

/**
 * The line at the top of the count (owner, 29 Oct 2026): never leave the person guessing whether their work is kept.
 * Empty while nothing is counted.
 */
export function saveWords(items: number, onDevice: boolean, send: SendState): string {
  if (items === 0) return '';
  const n = `${items} ${items === 1 ? 'item' : 'items'}`;
  if (send === 'sending') return 'Saving...';
  if (send === 'sent') return `All counts saved, ${n}`;
  if (!onDevice) return send === 'offline' ? 'Not saved yet. Keep this page open until the connection is back.' : 'Not saved yet. Keep this page open.';
  return send === 'offline' ? 'Saved on this phone, will send when online' : `Saved on this phone, ${n}. Will try sending again.`;
}

/** A failure to reach the server at all, rather than an answer from it. */
export const unreachable = (error: unknown): boolean =>
  (typeof navigator !== 'undefined' && navigator.onLine === false) || error instanceof TypeError;

/** How long typing rests before the counts go to the server draft; the device copy is written at once. */
export const SEND_AFTER_MS = 1500;
/** How long a refused send waits before it is tried again. */
export const RETRY_MS = 15_000;

type CountLine = { product_id: string; counted_qty: string };

/**
 * Keeps the count on this device on every change and in a server draft once typing rests (issue #681). Each send makes a new
 * draft with every valid count and discards the one before, through the draft and discard routes of #656; only the person's
 * Commit the count ever commits one. `gen` drops an answer a newer send or the review overtook, and a draft that could not be
 * discarded offline is kept on the device and discarded once the connection is back.
 *
 * `paused` holds the sends while the review is open or on its way; `adopt` makes the review's own draft the one kept, and
 * `reset` forgets the count once it is committed.
 */
export function useCountSave({ branchId, saveKey, restored, saved, lines, ready, paused }: {
  branchId: string;
  saveKey: string;
  restored: SavedCount | null;
  saved: Omit<SavedCount, 'draftId' | 'stale'>;
  lines: CountLine[];
  ready: boolean;
  paused: boolean;
}) {
  const sig = JSON.stringify(lines);
  // A restored count is sent again once, so the server holds what the device holds.
  const server = useRef({ id: restored?.draftId ?? null, stale: restored?.stale ?? [], gen: 0, sig: restored ? null : '[]' as string | null });
  const [send, setSend] = useState<SendState>('sent');
  const [onDevice, setOnDevice] = useState(true);
  const [moved, setMoved] = useState(0);
  const [retry, setRetry] = useState(0);
  const online = useDeviceOnline();
  const flush = async () => {
    for (const id of [...server.current.stale]) {
      try {
        await retail.discardStocktake(id);
      } catch (e) {
        // Offline it waits for the connection; a refusal (committed elsewhere, or gone) needs nothing more.
        if (unreachable(e)) return;
      }
      server.current.stale = server.current.stale.filter((x) => x !== id);
      setMoved((n) => n + 1);
    }
  };
  /** The counts are now in draft `id`, or in none: the one before it is discarded. */
  const holdIn = (id: string | null, sent: string | null) => {
    const old = server.current.id;
    server.current = { ...server.current, id, sig: sent, stale: old && old !== id ? [...server.current.stale, old] : server.current.stale };
    setMoved((n) => n + 1);
    void flush();
  };
  const push = async () => {
    const gen = ++server.current.gen;
    try {
      const made = lines.length === 0 ? null : await retail.createStocktake({ branch_id: branchId, lines });
      if (gen !== server.current.gen) {
        if (made?.id) server.current.stale = [...server.current.stale, made.id];
        return void flush();
      }
      holdIn(made?.id ?? null, sig);
      setSend('sent');
    } catch (e) {
      if (gen !== server.current.gen) return;
      setSend(unreachable(e) ? 'offline' : 'failed');
      if (!unreachable(e)) setTimeout(() => setRetry((n) => n + 1), RETRY_MS);
    }
  };
  useEffect(() => {
    if (paused || !ready || sig === server.current.sig) return;
    if (!online) return setSend('offline');
    setSend('sending');
    const t = setTimeout(() => void push(), SEND_AFTER_MS);
    return () => clearTimeout(t);
    // push reads this render's lines and sig, which are in the deps through sig.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig, online, paused, ready, retry]);
  useEffect(() => {
    if (online) void flush();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [online]);
  // The device copy, written on every change; a count with nothing in it and no draft to discard leaves nothing behind.
  const { counts, packCounts, listed, done } = saved;
  useEffect(() => {
    const { id, stale } = server.current;
    const empty = Object.values(counts).every((v) => v.trim() === '') && Object.values(packCounts).every((p) => p.text.trim() === '');
    if (empty && !id && stale.length === 0) {
      clearCount(saveKey);
      return setOnDevice(true);
    }
    setOnDevice(writeCount(saveKey, { counts, packCounts, listed, done, draftId: id, stale }));
  }, [counts, packCounts, listed, done, moved, saveKey]);
  return {
    send,
    onDevice,
    online,
    /** The review made draft `id` from these same counts: it becomes the one kept, and a send still on its way is dropped. */
    adopt: (id: string | null) => {
      server.current.gen++;
      holdIn(id, sig);
      setSend('sent');
    },
    /** The count is committed: nothing is left to keep or discard. */
    reset: () => {
      server.current = { id: null, stale: server.current.stale, gen: server.current.gen + 1, sig: '[]' };
      setMoved((n) => n + 1);
    },
  };
}

/** The changed lines named on the success screen; the rest are counted in one line. */
export const CHANGES_SHOWN = 5;

/** What a committed count changed, in plain words: "3 went up, 1 went down, 4 matched" and each changed line, the first few named. */
export function changeWords(lines: readonly StocktakeLine[], units?: ReadonlyMap<string, string | undefined>): { summary: string; changes: string[] } {
  const diff = (l: StocktakeLine) => milliOf(l.committed_variance_qty ?? l.variance_qty ?? '0');
  const up = lines.filter((l) => diff(l) > 0);
  const down = lines.filter((l) => diff(l) < 0);
  const same = lines.length - up.length - down.length;
  const parts = [up.length > 0 && `${up.length} went up`, down.length > 0 && `${down.length} went down`, same > 0 && `${same} matched`].filter(Boolean);
  const changed = [...up, ...down];
  const more = changed.length - CHANGES_SHOWN;
  const changes = changed.slice(0, CHANGES_SHOWN).map((l) => {
    const d = diff(l);
    const unit = units?.get(l.product_id ?? '');
    const qty = showQty(qtyString(Math.abs(d)));
    const word = unit ? ` ${Math.abs(d) === 1000 ? unit : plural(unit)}` : '';
    return `${l.description ?? 'An item'}: ${qty}${word} ${d > 0 ? 'more' : 'less'}`;
  });
  if (more > 0) changes.push(`and ${more} more ${more === 1 ? 'item' : 'items'} changed`);
  return { summary: `${parts.join(', ')}.`, changes };
}
