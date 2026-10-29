import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { retail, type Stocktake, type StocktakeLine } from '../../../api/retail';
import { plural } from '../../../app/glossary';
import { useDeviceOnline } from '../../../app/refresh-button';
import { milliOf, qtyString, showQty } from './maths';
import type { PackSize } from './units-state';

// Count stock keeps every count as it is typed (issue #681): on this device at once, per business (the host), branch and
// person, and as a server draft count shortly after, so a reload, a lost connection or a deploy never loses a number. The
// rules live here, out of the page, so they can be tested.

/** The note on every draft Count stock makes, so it never takes a draft Sell made (#656) for one of its own. */
export const COUNT_NOTE = 'Counted on Count stock';

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
  /**
   * The lines of each send whose answer has not come back. A send cut off by a reload or a lost connection may still have made
   * a draft whose id never arrived; the next open finds the person's drafts with exactly these lines and discards them.
   */
  pending: string[];
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
      pending: strings(v.pending) ? v.pending : [],
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

type CountLine = { product_id?: string; counted_qty?: string };

/** The lines of a count as one text, in item order, so a draft's lines and the counts on the page compare as equal. */
export const sigOf = (lines: readonly CountLine[]): string =>
  JSON.stringify(lines.map((l): [string, string] => [l.product_id ?? '', qtyString(milliOf(l.counted_qty ?? '0'))]).sort(([a], [b]) => a.localeCompare(b)));

/** A quantity as a person types it: 4, 2.5. */
export const typedQty = (qty: string | undefined): string => String(milliOf(qty ?? '0') / 1000);

/**
 * One tab or window counts at a time on a device (review of #681): two would each save over the other and could discard the
 * other's draft. The tab that opens Count stock last takes the count, as it reads every count the other saved; the other
 * sees a storage event and stops, until the person asks to count there again (`takeOver`, which reads the count afresh by
 * `epoch`). Storage that is blocked never stops a tab.
 */
export function useCountTab(key: string): { mine: boolean; epoch: number; takeOver: () => void } {
  const lockKey = `${key}.tab`;
  const [tab] = useState(() => `${Date.now().toString(36)}.${Math.random().toString(36).slice(2)}`);
  const [mine, setMine] = useState(true);
  const [epoch, setEpoch] = useState(0);
  const since = useRef(0);
  useEffect(() => {
    if (!mine) return;
    since.current = Date.now();
    try {
      localStorage.setItem(lockKey, JSON.stringify({ tab, since: since.current }));
    } catch {
      // Blocked storage keeps nothing to share.
    }
  }, [mine, epoch, lockKey, tab]);
  useEffect(() => {
    const seen = (e: StorageEvent) => {
      if (e.key !== lockKey || e.newValue === null) return;
      try {
        const other = JSON.parse(e.newValue) as { tab?: string; since?: number };
        // The later opener wins; two that open at the same moment are told apart by their tab names.
        if (other.tab && other.tab !== tab && ((other.since ?? 0) > since.current || ((other.since ?? 0) === since.current && other.tab > tab))) setMine(false);
      } catch {
        // Not a claim.
      }
    };
    window.addEventListener('storage', seen);
    return () => window.removeEventListener('storage', seen);
  }, [lockKey, tab]);
  return { mine, epoch, takeOver: () => { setEpoch((n) => n + 1); setMine(true); } };
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

/** A failure to reach the server at all, rather than an answer from it: the request may still have landed. */
export const unreachable = (error: unknown): boolean =>
  (typeof navigator !== 'undefined' && navigator.onLine === false) || error instanceof TypeError;

/** How long typing rests before the counts go to the server draft; the device copy is written at once. */
export const SEND_AFTER_MS = 1500;
/** How long a failed send waits before it is tried again, the connection dropped or the server refused it; tests shorten it. */
export const countRetry = { delayMs: 10_000 };

/**
 * Keeps the count on this device on every change and in a server draft once typing rests (issue #681). Each send makes a new
 * draft with every valid count, noted `COUNT_NOTE`, and discards the one before; only the person's Commit the count ever
 * commits one. The server work runs one step at a time. `gen` drops an answer a newer send or the review overtook, and a
 * draft that could not be discarded offline is kept on the device and discarded once the connection is back.
 *
 * On opening, the person's drafts at the branch are read once (`GET /retail/stocktakes`): a draft cut off by a reload is
 * discarded, and with nothing saved on this device the newest count is picked up from another device (`pickUp`, which
 * says whether the page took it). `paused` holds the sends while the review is open or on its way; `adopt` makes the
 * review's own draft the one kept, and `reset` forgets the count once it is committed.
 */
export function useCountSave({ branchId, saveKey, restored, saved, lines, ready, blocked, paused, pickUp }: {
  branchId: string;
  saveKey: string;
  restored: SavedCount | null;
  saved: Pick<SavedCount, 'counts' | 'packCounts' | 'listed' | 'done'>;
  lines: { product_id: string; counted_qty: string }[];
  /** The count sheet is loaded. */
  ready: boolean;
  /** The count sheet could not be loaded. */
  blocked: boolean;
  paused: boolean;
  pickUp: (draft: Stocktake) => boolean;
}) {
  const sig = sigOf(lines);
  // A restored count is sent again once, so the server holds what the device holds.
  const server = useRef({ id: restored?.draftId ?? null, stale: restored?.stale ?? [], pending: restored?.pending ?? [], gen: 0, sig: restored ? null : sigOf([]) as string | null });
  const latest = useRef(saved);
  latest.current = saved;
  const pausedNow = useRef(paused);
  pausedNow.current = paused;
  const gone = useRef(false);
  const chain = useRef<Promise<void>>(Promise.resolve());
  const retryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const started = useRef(false);
  const [send, setSend] = useState<SendState>(() => (!restored ? 'sent' : unreachable(undefined) ? 'offline' : 'sending'));
  const [onDevice, setOnDevice] = useState(true);
  const [stored, setStored] = useState<string | null>(null);
  const [moved, setMoved] = useState(0);
  const [retry, setRetry] = useState(0);
  const online = useDeviceOnline();
  const enqueue = (step: () => Promise<void>) => {
    chain.current = chain.current.then(step).catch(() => undefined);
  };
  /** The device copy now, written at once when a send starts, so a reload in flight still knows what was sent. */
  const persist = () => {
    if (gone.current) return;
    const { id, stale, pending } = server.current;
    writeCount(saveKey, { ...latest.current, draftId: id, stale, pending });
    setMoved((n) => n + 1);
  };
  const later = () => {
    if (retryTimer.current !== null) clearTimeout(retryTimer.current);
    retryTimer.current = setTimeout(() => setRetry((n) => n + 1), countRetry.delayMs);
  };
  const flush = async () => {
    for (const id of [...server.current.stale]) {
      try {
        await retail.discardStocktake(id);
      } catch (e) {
        // Unreachable, it waits for the next try; a refusal (committed elsewhere, or gone) needs nothing more.
        if (unreachable(e)) return later();
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
  };
  const dropPending = (sent: string) => {
    const at = server.current.pending.indexOf(sent);
    if (at >= 0) server.current.pending = server.current.pending.filter((_, i) => i !== at);
  };
  const push = async (sending: typeof lines, sent: string) => {
    const gen = ++server.current.gen;
    if (sending.length > 0) {
      server.current.pending = [...server.current.pending, sent].slice(-20);
      persist();
    }
    try {
      const made = sending.length === 0 ? null : await retail.createStocktake({ branch_id: branchId, lines: sending, note: COUNT_NOTE });
      dropPending(sent);
      if (gone.current) {
        if (made?.id) await retail.discardStocktake(made.id).catch(() => undefined);
        return;
      }
      if (gen !== server.current.gen) {
        if (made?.id) server.current.stale = [...server.current.stale, made.id];
      } else {
        holdIn(made?.id ?? null, sent);
        setSend('sent');
      }
      await flush();
    } catch (e) {
      // A refusal made nothing; a lost connection may have, so its lines stay pending for the next open to look for.
      if (!unreachable(e)) dropPending(sent);
      if (gone.current || gen !== server.current.gen) return;
      setSend(unreachable(e) ? 'offline' : 'failed');
      later();
    }
  };
  /** Once, on opening: discard a draft a cut off send made, and pick up a count from another device when none is here. */
  const start = async () => {
    const lookFor = new Set(server.current.pending);
    if (lookFor.size === 0 && restored) return;
    try {
      const ours = (await retail.myDraftStocktakes(branchId)).filter((d) => d.note === COUNT_NOTE && d.id);
      if (gone.current) return;
      for (const d of ours) {
        if (d.id !== server.current.id && lookFor.has(sigOf(d.lines ?? []))) server.current.stale = [...server.current.stale, d.id ?? ''];
      }
      server.current.pending = server.current.pending.filter((p) => !lookFor.has(p));
      const newest = ours.find((d) => !server.current.stale.includes(d.id ?? ''));
      if (!restored && server.current.id === null && newest && !pausedNow.current && pickUp(newest)) {
        server.current = { ...server.current, id: newest.id ?? null, sig: sigOf(newest.lines ?? []), gen: server.current.gen + 1 };
        setSend('sent');
      }
      persist();
      await flush();
    } catch (e) {
      // Tried again on the next open, or once the connection is back.
      started.current = false;
      if (unreachable(e)) later();
    }
  };
  useEffect(() => {
    if (!ready || !online || started.current) return;
    started.current = true;
    enqueue(start);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, online, retry]);
  useEffect(() => {
    if (paused) return;
    if (sig === server.current.sig) {
      if (online && server.current.stale.length > 0) enqueue(flush);
      return;
    }
    if (!online) return setSend('offline');
    if (blocked) return setSend('failed');
    setSend('sending');
    if (!ready) return;
    const sending = lines;
    const t = setTimeout(() => enqueue(() => push(sending, sig)), SEND_AFTER_MS);
    return () => clearTimeout(t);
    // push reads this render's lines and sig, which are in the deps through sig.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig, online, paused, ready, blocked, retry]);
  useEffect(
    () => () => {
      gone.current = true;
      if (retryTimer.current !== null) clearTimeout(retryTimer.current);
    },
    [],
  );
  // The device copy, written on every change before the screen is drawn, so Saved never shows for a count not yet stored; a
  // count with nothing in it and nothing to discard or look for leaves nothing behind.
  const { counts, packCounts, listed, done } = saved;
  const here = JSON.stringify([counts, packCounts]);
  useLayoutEffect(() => {
    const { id, stale, pending } = server.current;
    const empty = Object.values(counts).every((v) => v.trim() === '') && Object.values(packCounts).every((p) => p.text.trim() === '');
    if (empty && !id && stale.length === 0 && pending.length === 0) {
      clearCount(saveKey);
      setOnDevice(true);
      return setStored(here);
    }
    const ok = writeCount(saveKey, { counts, packCounts, listed, done, draftId: id, stale, pending });
    setOnDevice(ok);
    setStored(ok ? here : null);
  }, [counts, packCounts, listed, done, moved, saveKey, here]);
  return {
    send,
    onDevice,
    /** Every count on the page is the one stored on this device. */
    savedHere: stored === here,
    /** The review made draft `id` from the counts it sent: it becomes the one kept, and a send still on its way is dropped. */
    adopt: (id: string | null, sent: typeof lines) => {
      server.current.gen++;
      holdIn(id, sigOf(sent));
      setSend('sent');
      enqueue(flush);
    },
    /** The count is committed: nothing is left to keep or discard. */
    reset: () => {
      server.current = { ...server.current, id: null, gen: server.current.gen + 1, sig: sigOf([]) };
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
