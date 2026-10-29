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
}

export type CountState = Omit<SavedCount, 'at'>;

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
  out.listed = [...mine.listed, ...theirs.listed.filter((id) => !mine.listed.includes(id))];
  out.done = [...mine.done, ...theirs.done.filter((id) => !mine.done.includes(id))].filter((id) => filled(out, id));
  return out;
}

/** The line at the top of the count (owner, round 2 of #681): plain words for where the counts are kept. Empty before a count. */
export function saveWords(items: number, onDevice: boolean): string {
  if (items === 0) return '';
  return onDevice ? `Saved on this phone, ${items} ${items === 1 ? 'item' : 'items'}` : 'Not saved on this phone. Keep this page open.';
}

/** What another tab of the same count did: it is open there too, or it committed the count. */
export type OtherTab = 'open' | 'committed' | null;

/**
 * Keeps the count on this device on every change, written before the screen is drawn so Saved never shows for a count not
 * stored (issue #681), and stamps each item when its count changes. A change from another tab of the same count arrives as a
 * storage event and is merged item by item into this one (`apply`); the key removed there means that tab committed the count,
 * and this one starts empty (`apply(null)`). `committed` removes the key once this tab commits.
 */
export function useCountSave({ saveKey, restored, saved, apply }: {
  saveKey: string;
  restored: SavedCount | null;
  saved: CountState;
  apply: (next: CountState | null) => void;
}) {
  const at = useRef<Record<string, number>>(restored?.at ?? {});
  const before = useRef<CountState>(saved);
  const latest = useRef(saved);
  latest.current = saved;
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
    if (typed) setOther((o) => (o === 'committed' ? null : o));
    // An empty count is written only over a count already kept, so a commit that removed the key leaves it removed.
    if (idsOf(saved).every((id) => !filled(saved, id)) && readCount(saveKey) === null) {
      setOnDevice(true);
      return setStored(here);
    }
    const ok = writeCount(saveKey, { counts, packCounts, listed, done, at: at.current });
    setOnDevice(ok);
    setStored(ok ? here : null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [counts, packCounts, listed, done, saveKey, here]);
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
      setOther('open');
      const merged = mergeCounts({ ...latest.current, at: at.current }, theirs);
      at.current = merged.at;
      const { at: _, ...next } = merged;
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
    /** The count is committed: the device keeps nothing more of it, and another tab of it hears so. */
    committed: () => {
      clearCount(saveKey);
      at.current = {};
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
