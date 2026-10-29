import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { StocktakeLine } from '../../../api/retail';
import { plural } from '../../../app/glossary';
import { milliOf, qtyString, showQty } from './maths';
import type { PackSize } from './units-state';

// Count stock keeps every count on this device as it is typed (issue #681), per business (the host), branch and person, so
// a reload, a lost connection or a deploy never loses a number. Nothing goes to the server until Review the difference, as
// before. The rules live here, out of the page, so they can be tested.

/** What the count page keeps of a count in progress. */
export interface SavedCount {
  counts: Record<string, string>;
  packCounts: Record<string, { text: string; pack: PackSize }>;
  listed: string[];
  done: string[];
  /** When each item's count last changed, so two tabs merge item by item, the newest count of each item winning. */
  at: Record<string, number>;
  /** When a tab of this count last committed it, so another tab can say its counts went in. */
  committed?: number;
}

export type CountState = Omit<SavedCount, 'at' | 'committed'>;

export const countKey = (host: string, branchId: string, userId: string) => `bms.count.${host}.${branchId}.${userId}`;

const strings = (v: unknown): v is string[] => Array.isArray(v) && v.every((x) => typeof x === 'string');
const isMap = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const textMap = (v: unknown): v is Record<string, string> => isMap(v) && Object.values(v).every((x) => typeof x === 'string');
const numberMap = (v: unknown): v is Record<string, number> => isMap(v) && Object.values(v).every((x) => typeof x === 'number');

/** A stored count, or null when the text is not one. */
export function parseSaved(raw: string | null): SavedCount | null {
  if (raw === null) return null;
  try {
    const v = JSON.parse(raw) as Partial<SavedCount>;
    if (!textMap(v.counts) || !strings(v.listed) || !strings(v.done)) return null;
    return {
      counts: v.counts,
      packCounts: isMap(v.packCounts) ? (v.packCounts as SavedCount['packCounts']) : {},
      listed: v.listed,
      done: v.done,
      at: numberMap(v.at) ? v.at : {},
      ...(typeof v.committed === 'number' ? { committed: v.committed } : {}),
    };
  } catch {
    return null;
  }
}

/** The saved count, or null when there is none, storage is blocked or what is stored is not a count. */
export function readCount(key: string): SavedCount | null {
  try {
    return parseSaved(localStorage.getItem(key));
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

const idsOf = (...states: CountState[]) => [...new Set(states.flatMap((s) => [...Object.keys(s.counts), ...Object.keys(s.packCounts)]))];
const itemOf = (s: CountState, id: string) => JSON.stringify([s.counts[id] ?? '', s.packCounts[id] ?? null]);
const filled = (s: CountState, id: string) => (s.counts[id] ?? '').trim() !== '' || (s.packCounts[id]?.text ?? '').trim() !== '';
export const sameCount = (a: CountState, b: CountState) =>
  JSON.stringify([a.counts, a.packCounts, a.listed, a.done]) === JSON.stringify([b.counts, b.packCounts, b.listed, b.done]);

/**
 * Two tabs counting the same branch (review of #681): each item takes the newer of the two counts, never the whole sheet of one
 * tab. The items found keep this tab's order with the other tab's added after; an item emptied in the newer count leaves the
 * counted ones.
 */
export function mergeCounts(mine: SavedCount, theirs: SavedCount): SavedCount {
  const out: SavedCount = { counts: {}, packCounts: {}, listed: [], done: [], at: {} };
  for (const id of idsOf(mine, theirs)) {
    const pick = (theirs.at[id] ?? 0) > (mine.at[id] ?? 0) ? theirs : mine;
    if (pick.counts[id] !== undefined) out.counts[id] = pick.counts[id];
    const pack = pick.packCounts[id];
    if (pack) out.packCounts[id] = pack;
    const at = pick.at[id];
    if (at !== undefined) out.at[id] = at;
  }
  // An item whose newer side no longer lists it (committed or emptied there) leaves the list of found items too.
  const newer = (id: string) => ((theirs.at[id] ?? 0) > (mine.at[id] ?? 0) ? theirs : mine);
  const stays = (id: string) => (mine.at[id] === undefined && theirs.at[id] === undefined) || newer(id).listed.includes(id) || filled(out, id);
  out.listed = [...mine.listed, ...theirs.listed.filter((id) => !mine.listed.includes(id))].filter(stays);
  out.done = [...mine.done, ...theirs.done.filter((id) => !mine.done.includes(id))].filter((id) => filled(out, id));
  const committed = Math.max(mine.committed ?? 0, theirs.committed ?? 0);
  if (committed > 0) out.committed = committed;
  return out;
}

/** The time the oldest kept count was saved, or null when nothing is counted: what a count restored on opening dates from. */
export function savedSince(saved: SavedCount | null): number | null {
  if (!saved) return null;
  const times = idsOf(saved).filter((id) => filled(saved, id)).map((id) => saved.at[id] ?? 0).filter((t) => t > 0);
  return times.length === 0 ? null : Math.min(...times);
}

/** Counts kept longer than this are old enough that the shelf may have moved: the page asks to check them or start again. */
export const OLD_COUNT_MS = 12 * 60 * 60 * 1000;

/** The line at the top of the count (owner, round 2 of #681): plain words for where the counts are kept. Empty before a count. */
export function saveWords(items: number, onDevice: boolean): string {
  if (items === 0) return '';
  return onDevice ? `Saved on this phone, ${items} ${items === 1 ? 'item' : 'items'}` : 'Not saved on this phone. Keep this page open.';
}

/** What another tab of the same count did: it is open there too, it committed every count, or only some of them. */
export type OtherTab = 'open' | 'committed' | 'partly' | null;

/** How long "This count is also open in another tab" stays after the last change heard from it. */
export const OTHER_TAB_MS = 60_000;

/**
 * Keeps the count on this device on every change, written before the screen is drawn so Saved never shows for a count not
 * stored (issue #681), and stamps each item when its count changes. A change from another tab of the same count arrives as a
 * storage event and is merged item by item into this one (`apply`), the newer count of each item winning.
 *
 * A commit never clears counts it did not send (review of #686): `committed` marks the time, the page then drops only the
 * items whose count went in, and that drop is stamped like any change, so another tab drops exactly those items, keeps any
 * newer count of its own, and hears from the mark whether all or some of its counts went in. The stored count removed
 * outright (storage cleared) empties this tab too.
 */
export function useCountSave({ saveKey, restored, saved, apply }: {
  saveKey: string;
  restored: SavedCount | null;
  saved: CountState;
  apply: (next: CountState | null) => void;
}) {
  const at = useRef<Record<string, number>>(restored?.at ?? {});
  const commitAt = useRef<number | undefined>(restored?.committed);
  const before = useRef<CountState>(saved);
  const latest = useRef(saved);
  latest.current = saved;
  const quiet = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [onDevice, setOnDevice] = useState(true);
  const [stored, setStored] = useState<string | null>(null);
  const [other, setOther] = useState<OtherTab>(null);
  const here = JSON.stringify([saved.counts, saved.packCounts]);
  const { counts, packCounts, listed, done } = saved;
  useLayoutEffect(() => {
    const now = Date.now();
    let typed = false;
    for (const id of idsOf(before.current, saved)) {
      if (itemOf(before.current, id) !== itemOf(saved, id)) {
        at.current = { ...at.current, [id]: now };
        typed = true;
      }
    }
    before.current = saved;
    if (typed) setOther((o) => (o === 'committed' || o === 'partly' ? null : o));
    // Nothing found or counted, and nothing kept before: there is nothing to write.
    if (listed.length === 0 && idsOf(saved).every((id) => !filled(saved, id)) && readCount(saveKey) === null) {
      setOnDevice(true);
      return setStored(here);
    }
    const ok = writeCount(saveKey, { counts, packCounts, listed, done, at: at.current, ...(commitAt.current ? { committed: commitAt.current } : {}) });
    setOnDevice(ok);
    setStored(ok ? here : null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [counts, packCounts, listed, done, saveKey, here]);
  useEffect(() => () => {
    if (quiet.current !== null) clearTimeout(quiet.current);
  }, []);
  useEffect(() => {
    const seen = (e: StorageEvent) => {
      if (e.key !== saveKey) return;
      if (e.newValue === null) {
        at.current = {};
        before.current = { counts: {}, packCounts: {}, listed: [], done: [] };
        setOther('committed');
        return apply(null);
      }
      const theirs = parseSaved(e.newValue);
      if (!theirs) return;
      const merged = mergeCounts({ ...latest.current, at: at.current, ...(commitAt.current ? { committed: commitAt.current } : {}) }, theirs);
      const fresh = (theirs.committed ?? 0) > (commitAt.current ?? 0);
      at.current = merged.at;
      commitAt.current = merged.committed;
      const { at: _at, committed: _committed, ...next } = merged;
      if (fresh) {
        setOther(idsOf(next).some((id) => filled(next, id)) ? 'partly' : 'committed');
      } else {
        // Open there too, said until that tab has been quiet for a minute.
        setOther('open');
        if (quiet.current !== null) clearTimeout(quiet.current);
        quiet.current = setTimeout(() => setOther((o) => (o === 'open' ? null : o)), OTHER_TAB_MS);
      }
      if (sameCount(next, latest.current)) return;
      // Taken as it is, so it is not stamped as typed here.
      before.current = next;
      apply(next);
    };
    window.addEventListener('storage', seen);
    return () => window.removeEventListener('storage', seen);
  }, [saveKey, apply]);
  return {
    onDevice,
    /** Every count on the page is the one stored on this device. */
    savedHere: stored === here,
    other,
    /** This tab committed: the page then drops the items that went in, and another tab hears which. */
    committed: () => {
      commitAt.current = Date.now();
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
