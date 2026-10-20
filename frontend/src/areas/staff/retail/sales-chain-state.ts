// The sales lists grouped by a chain of levels (issue #341): the pure rules behind the screen. The chain is an ordered
// list of levels the user chooses (shop, year, month, salesperson, day, product category, item); what is open is a set
// of path keys like "branch:b1/year:2026/month:2026-10", so the same day under two salespeople is two groups.

export type ChainLevel = 'branch' | 'year' | 'month' | 'seller' | 'day' | 'category' | 'item' | 'variant';

/** The order the old spreadsheet app used, and the order a new user starts with. */
export const DEFAULT_CHAIN: readonly ChainLevel[] = ['branch', 'year', 'month', 'seller', 'day', 'category', 'item'];

/**
 * Every level, the size and colour of an item last (issue #384): offered only to a business that has an item with sizes and
 * colours, so the Group by of a shop without them is what it always was.
 */
export const ALL_LEVELS: readonly ChainLevel[] = [...DEFAULT_CHAIN, 'variant'];

/** The words a person reads for each level. */
export const LEVEL_NAMES: Record<ChainLevel, string> = {
  branch: 'Shop', year: 'Year', month: 'Month', seller: 'Salesperson', day: 'Day', category: 'Product category', item: 'Item', variant: 'Size and colour',
};

/** What a "show more" button calls the groups of a level. */
export const LEVEL_PLURALS: Record<ChainLevel, string> = {
  branch: 'shops', year: 'years', month: 'months', seller: 'salespeople', day: 'days', category: 'categories', item: 'items', variant: 'sizes and colours',
};

export const isTimeLevel = (level: ChainLevel) => level === 'year' || level === 'month' || level === 'day';

/** The chain with `level` moved one place up or down; a level already at the end stays. */
export function moveLevel<L extends string = ChainLevel>(chain: readonly L[], level: L, by: -1 | 1): L[] {
  const i = chain.indexOf(level);
  const j = i + by;
  if (i < 0 || j < 0 || j >= chain.length) return [...chain];
  const next = [...chain];
  next[i] = chain[j] as L;
  next[j] = level;
  return next;
}

/** The chain without `level`. The last level cannot be dropped: a list needs at least one level. */
export function dropLevel<L extends string = ChainLevel>(chain: readonly L[], level: L): L[] {
  return chain.length <= 1 ? [...chain] : chain.filter((l) => l !== level);
}

/** The chain with a dropped level back in, before the first level that comes after it in the usual order. */
export function addLevel<L extends string = ChainLevel>(chain: readonly L[], level: L, order: readonly L[] = DEFAULT_CHAIN as readonly string[] as readonly L[]): L[] {
  if (chain.includes(level)) return [...chain];
  const rank = order.indexOf(level);
  const at = chain.findIndex((l) => order.indexOf(l) > rank);
  return at < 0 ? [...chain, level] : [...chain.slice(0, at), level, ...chain.slice(at)];
}

/** The levels a person may use: the salesperson level is for those who may read profit (a cashier sees no ranking of colleagues). */
export const usableLevels = (sellerAllowed: boolean, variants = false): ChainLevel[] =>
  ALL_LEVELS.filter((l) => (sellerAllowed || l !== 'seller') && (variants || l !== 'variant'));

/** The chain with the levels the person may not use taken out; the usual order if nothing is left. */
export function usableChain(chain: readonly ChainLevel[], sellerAllowed: boolean, variants = false): ChainLevel[] {
  const kept = chain.filter((l) => (sellerAllowed || l !== 'seller') && (variants || l !== 'variant'));
  return kept.length > 0 ? kept : usableLevels(sellerAllowed, variants).filter((l) => l !== 'variant');
}

/** The levels not in the chain, in the usual order. */
export const droppedLevels = <L extends string = ChainLevel>(chain: readonly L[], usable: readonly L[] = DEFAULT_CHAIN as readonly string[] as readonly L[]): L[] => usable.filter((l) => !chain.includes(l));

/** A stored chain, or null when the text is not a non-empty list of known levels, each once. */
export function parseChain<L extends string = ChainLevel>(text: string | null, known: readonly L[] = DEFAULT_CHAIN as readonly string[] as readonly L[]): L[] | null {
  if (!text) return null;
  try {
    const value: unknown = JSON.parse(text);
    if (!Array.isArray(value) || value.length === 0 || !value.every((x): x is L => typeof x === 'string' && known.includes(x as L)) || new Set(value).size !== value.length) return null;
    return value;
  } catch {
    return null;
  }
}

/** Which list the choice belongs to: All sales and Credit sales each remember their own. */
export type ChainList = 'all' | 'credit';

/** Who the remembered Group by belongs to: the user's id, never an email or a phone number. */
export const chainUser = (me: { user_id?: string }) => me.user_id ?? 'staff';

const storeKey = (user: string, list: ChainList) => `bms.salesChain.${list}.${user}`;

/** The chain this user chose for the list; storage that is blocked, missing or garbled gives the usual order. */
export function readChain(user: string, list: ChainList): ChainLevel[] {
  try {
    return parseChain(localStorage.getItem(storeKey(user, list)), ALL_LEVELS) ?? [...DEFAULT_CHAIN];
  } catch {
    return [...DEFAULT_CHAIN];
  }
}

export function writeChain(user: string, list: ChainList, chain: readonly ChainLevel[]): void {
  try {
    localStorage.setItem(storeKey(user, list), JSON.stringify(chain));
  } catch {
    // A private window or blocked storage: the choice lasts until the page is left.
  }
}

/** With one shop already chosen at the top of the page the shop level would be a single row: leave it out. */
export function effectiveChain<L extends string = ChainLevel>(chain: readonly L[], shopChosen: boolean): L[] {
  const without = chain.filter((l) => l !== 'branch');
  return shopChosen && without.length > 0 ? without : [...chain];
}

/** Whether a group at `index` offers "Show the sales": on a day, on any level when the chain has no day, and on the level above a last item or size level. */
export function offersSales(chain: readonly ChainLevel[], index: number): boolean {
  const level = chain[index];
  const last = chain.length - 1;
  return level === 'day' || !chain.includes('day') || (index + 1 === last && (chain[last] === 'item' || chain[last] === 'variant'));
}

/** What the user opened above a level: it travels to the server as filters. */
export interface Selection {
  branchId?: string;
  from?: string;
  to?: string;
  sellerId?: string;
  categoryId?: string;
  itemId?: string;
}

/** A group as far as the selection needs it. */
export interface Picked {
  key?: string;
  from?: string;
  to?: string;
}

/** The two later of the starts and the two earlier of the ends: the days a group's children are asked for. */
function narrowed(group: Picked, sel: Selection): { from?: string; to?: string } {
  const from = [group.from, sel.from].filter((x): x is string => !!x).sort().at(-1);
  const to = [group.to, sel.to].filter((x): x is string => !!x).sort().at(0);
  return { ...(from ? { from } : {}), ...(to ? { to } : {}) };
}

/** The selection after opening `group` at `level`: the parent's selection plus what this group is. */
export function selectGroup(sel: Selection, level: ChainLevel, group: Picked): Selection {
  switch (level) {
    case 'branch': return { ...sel, branchId: group.key };
    case 'seller': return { ...sel, sellerId: group.key };
    case 'category': return { ...sel, categoryId: group.key };
    // A size and colour narrows the item chosen above to that one variant: an item id or a product id, the server takes both.
    case 'item': case 'variant': return { ...sel, itemId: group.key };
    default: return { ...sel, ...narrowed(group, sel) };
  }
}

/** True when the figures at `level` under `sel` are summed over sale lines, not whole sales (a category, an item or a size is the level or was chosen above). */
export const overLines = (level: ChainLevel, sel: Selection): boolean => level === 'category' || level === 'item' || level === 'variant' || !!sel.categoryId || !!sel.itemId;

/** The words above the sales listed under a chosen category or item: they are whole sales, so their totals can be more than the line above. */
export function wholeSalesNote(sel: Selection): string | null {
  if (sel.itemId) return 'Whole sales that include this item.';
  if (sel.categoryId) return 'Whole sales that include this category.';
  return null;
}

export const pathKey = (parent: string, level: string, key: string) => `${parent}/${level}:${key}`;

/** The open groups, each with the words shown for it, in the order they were opened. */
export type OpenGroups = ReadonlyMap<string, string>;

/** The groups with `key` opened if it was shut, or shut (with everything inside it) if it was open. */
export function toggleGroup(open: OpenGroups, key: string, label: string): Map<string, string> {
  const next = new Map(open);
  if (next.has(key)) {
    for (const k of open.keys()) if (k === key || k.startsWith(`${key}/`)) next.delete(k);
  } else {
    next.set(key, label);
  }
  return next;
}

/** The groups with `key` and everything inside it shut: the way back up from a crumb. */
export function shutGroup(open: OpenGroups, key: string): Map<string, string> {
  return open.has(key) ? toggleGroup(open, key, '') : new Map(open);
}

export interface Crumb {
  key: string;
  label: string;
}

/** The path to the group opened last, from the first level down, as crumbs; empty when nothing is open. */
export function trailOf(open: OpenGroups): Crumb[] {
  const last = [...open.keys()].at(-1);
  if (last === undefined) return [];
  const parts = last.split('/').slice(1);
  return parts.map((_, i) => {
    const key = `/${parts.slice(0, i + 1).join('/')}`;
    return { key, label: open.get(key) ?? parts[i] ?? '' };
  });
}

/** The words for a level that has nothing in it. */
export function emptyChainWords(credit: boolean): string {
  return credit ? 'No credit sales here.' : 'No sales here.';
}
