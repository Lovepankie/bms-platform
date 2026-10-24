import { Fragment, useId, useRef, useState, type ReactNode } from 'react';
import { milliOf, qtyString, showQty } from './maths';

// Item lists grouped by category as expandable sections (the stock list and the catalogue items; the item pickers
// list matches only while something is typed and are never grouped, issue #534).
// The grouping works on the items the screen already holds, after the server filtered them: it never
// asks for more, so on a paged list it groups the loaded page and the hint says so. The pure part
// (groupByCategory, defaultOpen, sameUnitQty, the stored choice) is tested on its own; the hook keeps
// which groups the user opened, the components draw the controls and the sections.

export const UNCATEGORISED = 'Uncategorised';
/** Up to this many items every group starts open; above it every group starts closed. */
export const OPEN_UP_TO = 30;

export type Group<T> = { key: string; label: string; items: T[]; subtotal: string | null };

const byName = new Intl.Collator('en', { sensitivity: 'base', numeric: true });

/**
 * The items in groups, by category name, A to Z, the items without a category last and each group
 * in the order the items came. `subtotalOf` writes a group's subtotal (null for none).
 */
export function groupByCategory<T>(items: T[], categoryOf: (item: T) => string | null | undefined, subtotalOf?: (items: T[]) => string | null): Group<T>[] {
  const found = new Map<string, Group<T>>();
  for (const item of items) {
    const label = (categoryOf(item) ?? '').trim();
    const key = label.toLowerCase();
    const group = found.get(key) ?? { key, label: label || UNCATEGORISED, items: [], subtotal: null };
    group.items.push(item);
    found.set(key, group);
  }
  const groups = [...found.values()].sort((a, b) => (a.key === '' ? 1 : b.key === '' ? -1 : byName.compare(a.label, b.label)));
  if (subtotalOf) for (const g of groups) g.subtotal = subtotalOf(g.items);
  return groups;
}

/** Whether groups start open: when a search is on (its matches are the point), or when the list is short. */
export function defaultOpen(itemCount: number, searching: boolean): boolean {
  return searching || itemCount <= OPEN_UP_TO;
}

/** The total quantity of rows that share one unit, as "340 piece"; null when the units differ or there is no unit. */
export function sameUnitQty(rows: { qty?: string; unit?: string }[]): string | null {
  const unit = rows[0]?.unit;
  if (!unit || rows.some((r) => r.unit !== unit)) return null;
  const total = rows.reduce((sum, r) => sum + milliOf(r.qty ?? '0'), 0);
  return `${showQty(qtyString(total))} ${unit}`;
}

const storeKey = (screen: string) => `bms.groupByCategory.${screen}`;

/** The remembered choice of a screen (on unless switched off); storage that is blocked or missing means on. */
export function readGrouped(screen: string): boolean {
  try {
    return localStorage.getItem(storeKey(screen)) !== 'off';
  } catch {
    return true;
  }
}

export function writeGrouped(screen: string, on: boolean): void {
  try {
    localStorage.setItem(storeKey(screen), on ? 'on' : 'off');
  } catch {
    // A private window or blocked storage: the choice lasts until the page is left.
  }
}

type Opened = { key: string; base?: boolean; map: Record<string, boolean> };

export type CategoryGroups<T> = {
  /** The user's choice: true when the list is drawn in groups. */
  grouped: boolean;
  setGrouped: (on: boolean) => void;
  groups: Group<T>[];
  isOpen: (key: string) => boolean;
  toggle: (key: string) => void;
  setAll: (open: boolean) => void;
  minGroups: number;
  /** The grouping is worth drawing: switched on, and at least `minGroups` groups. */
  active: boolean;
};

/**
 * The grouping of one list. `resetKey` is the search and filters in one string: when it changes, the
 * groups start again from the default (open for a search or up to 30 items, closed above that). The
 * default is decided when results first arrive for a key and held while more are loaded, so "Show more"
 * does not open or close sections under the user's thumb. While `pending` (the list on screen is the
 * previous answer) nothing is decided, so clearing a search does not open every group of the whole list.
 */
export function useCategoryGroups<T>(o: {
  screen: string;
  items: T[];
  categoryOf: (item: T) => string | null | undefined;
  subtotalOf?: (items: T[]) => string | null;
  resetKey: string;
  searching: boolean;
  /** The list shown is still the previous answer (a kept placeholder): the default is not decided from it. */
  pending?: boolean;
  minGroups?: number;
}): CategoryGroups<T> {
  const [grouped, setGroupedState] = useState(() => readGrouped(o.screen));
  const latch = useRef<{ key: string; open: boolean } | null>(null);
  if (o.items.length > 0 && !o.pending && latch.current?.key !== o.resetKey) latch.current = { key: o.resetKey, open: defaultOpen(o.items.length, o.searching) };
  const [opened, setOpened] = useState<Opened>({ key: o.resetKey, map: {} });
  const mine = opened.key === o.resetKey ? opened : { key: o.resetKey, map: {} };
  const base = mine.base ?? latch.current?.open ?? defaultOpen(o.items.length, o.searching);
  const groups = groupByCategory(o.items, o.categoryOf, o.subtotalOf);
  const isOpen = (key: string) => mine.map[key] ?? base;
  return {
    grouped,
    setGrouped: (on) => { writeGrouped(o.screen, on); setGroupedState(on); },
    groups,
    isOpen,
    toggle: (key) => setOpened({ ...mine, key: o.resetKey, map: { ...mine.map, [key]: !isOpen(key) } }),
    setAll: (open) => setOpened({ key: o.resetKey, base: open, map: {} }),
    minGroups: o.minGroups ?? 1,
    active: grouped && groups.length >= (o.minGroups ?? 1),
  };
}

/** The switch and, when grouped, Expand all and Collapse all. `paged` adds the hint that only the loaded items are grouped. */
export function GroupControls<T>({ state, paged = false }: { state: CategoryGroups<T>; paged?: boolean }) {
  const id = useId();
  // A list with a single category has nothing to group: the switch stays out of the way, unless it is off.
  if (state.grouped && state.groups.length < state.minGroups) return null;
  const all = state.groups.every((g) => state.isOpen(g.key));
  const none = state.groups.every((g) => !state.isOpen(g.key));
  return (
    <>
      <div className="cluster cat-controls">
        <label htmlFor={`${id}-switch`} className="cat-switch">
          <input id={`${id}-switch`} type="checkbox" checked={state.grouped} onChange={(e) => state.setGrouped(e.target.checked)} />
          Group by category
        </label>
        {state.grouped && (
          <>
            <button type="button" className="btn-sm" disabled={all} onClick={() => state.setAll(true)}>Expand all</button>
            <button type="button" className="btn-sm" disabled={none} onClick={() => state.setAll(false)}>Collapse all</button>
          </>
        )}
      </div>
      {state.grouped && paged && <p className="hint">Grouped within the items loaded so far. Show more items loads the rest.</p>}
    </>
  );
}

/** What a group's header says: the count and the subtotal, if the screen has one. */
export const groupSummary = (g: { items: unknown[]; subtotal: string | null }) => `${g.items.length} ${g.items.length === 1 ? 'item' : 'items'}${g.subtotal ? `, ${g.subtotal}` : ''}`;

function Header({ label, summary, open, controls, onToggle }: { label: string; summary: string; open: boolean; controls: string; onToggle: () => void }) {
  return (
    <button type="button" className="cat-head" aria-expanded={open} aria-controls={controls} onClick={onToggle}>
      <span className="cat-name">{label}</span>
      <span className="cat-meta">{summary}</span>
    </button>
  );
}

/** The groups of a table: a header row and a body of rows per group, in the table the screen already draws. */
export function GroupedBodies<T>({ state, colSpan, renderRow }: { state: CategoryGroups<T>; colSpan: number; renderRow: (item: T) => ReactNode }) {
  const base = useId();
  return (
    <>
      {state.groups.map((g, i) => {
        const open = state.isOpen(g.key);
        const id = `${base}-${i}`;
        return (
          <Fragment key={g.key}>
            <tbody className="cat-group">
              <tr><th scope="rowgroup" colSpan={colSpan}><Header label={g.label} summary={groupSummary(g)} open={open} controls={id} onToggle={() => state.toggle(g.key)} /></th></tr>
            </tbody>
            <tbody id={id} hidden={!open}>{open && g.items.map(renderRow)}</tbody>
          </Fragment>
        );
      })}
    </>
  );
}

/** The groups of a card list: a heading with its button and the group's list. */
export function GroupedCards<T>({ state, renderItem }: { state: CategoryGroups<T>; renderItem: (item: T) => ReactNode }) {
  const base = useId();
  return (
    <div>
      {state.groups.map((g, i) => {
        const open = state.isOpen(g.key);
        const id = `${base}-${i}`;
        return (
          <section key={g.key} className="cat-group">
            <h3 className="cat-title"><Header label={g.label} summary={groupSummary(g)} open={open} controls={id} onToggle={() => state.toggle(g.key)} /></h3>
            <ul id={id} hidden={!open} style={{ listStyle: 'none', padding: 0 }}>{open && g.items.map(renderItem)}</ul>
          </section>
        );
      })}
    </div>
  );
}
