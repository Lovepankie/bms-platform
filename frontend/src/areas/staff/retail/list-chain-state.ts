import { toggleGroup, type OpenGroups } from './sales-chain-state';

// The stock list and the restocks list grouped by a chain of levels (issue #350): the pure rules behind the screens. The same
// idea as the sales lists (sales-chain-state.ts, issue #341): an ordered list of levels the user chooses, groups opened one under
// another as path keys, and what was opened above a level travelling to the server as filters.

export type PurchaseChainLevel = 'branch' | 'year' | 'month' | 'supplier' | 'day' | 'category' | 'item' | 'variant';
export type StockLevelName = 'branch' | 'category';

/** The order a new user starts with, which is also the order a dropped level goes back into. */
export const PURCHASE_ORDER: readonly PurchaseChainLevel[] = ['branch', 'year', 'month', 'supplier', 'day', 'category', 'item'];
/** With the size and colour of an item last (issue #384), offered only to a business that has an item with sizes and colours. */
export const PURCHASE_ALL: readonly PurchaseChainLevel[] = [...PURCHASE_ORDER, 'variant'];
export const STOCK_ORDER: readonly StockLevelName[] = ['branch', 'category'];

export const PURCHASE_NAMES: Record<PurchaseChainLevel, string> = {
  branch: 'Shop', year: 'Year', month: 'Month', supplier: 'Supplier', day: 'Day', category: 'Product category', item: 'Item', variant: 'Size and colour',
};
export const PURCHASE_PLURALS: Record<PurchaseChainLevel, string> = {
  branch: 'shops', year: 'years', month: 'months', supplier: 'suppliers', day: 'days', category: 'categories', item: 'items', variant: 'sizes and colours',
};
export const STOCK_NAMES: Record<StockLevelName, string> = { branch: 'Shop', category: 'Product category' };
export const STOCK_PLURALS: Record<StockLevelName, string> = { branch: 'shops', category: 'categories' };

export const isTime = (level: string) => level === 'year' || level === 'month' || level === 'day';

/** The chain with `level` moved one place up or down; a level already at the end stays. */
export function moveIn<L extends string>(chain: readonly L[], level: L, by: -1 | 1): L[] {
  const i = chain.indexOf(level);
  const j = i + by;
  if (i < 0 || j < 0 || j >= chain.length) return [...chain];
  const next = [...chain];
  next[i] = chain[j] as L;
  next[j] = level;
  return next;
}

/** The chain without `level`; the last level cannot be dropped. */
export const dropFrom = <L extends string>(chain: readonly L[], level: L): L[] => (chain.length <= 1 ? [...chain] : chain.filter((l) => l !== level));

/** The chain with a dropped level back in, before the first level that comes after it in the usual order. */
export function addTo<L extends string>(chain: readonly L[], level: L, order: readonly L[]): L[] {
  if (chain.includes(level)) return [...chain];
  const rank = order.indexOf(level);
  const at = chain.findIndex((l) => order.indexOf(l) > rank);
  return at < 0 ? [...chain, level] : [...chain.slice(0, at), level, ...chain.slice(at)];
}

/** A stored chain, or null when the text is not a non-empty list of known levels, each once. */
export function parseOf<L extends string>(text: string | null, order: readonly L[]): L[] | null {
  if (!text) return null;
  try {
    const value: unknown = JSON.parse(text);
    if (!Array.isArray(value) || value.length === 0 || new Set(value).size !== value.length) return null;
    return value.every((x): x is L => typeof x === 'string' && (order as readonly string[]).includes(x)) ? value : null;
  } catch {
    return null;
  }
}

/** Which list the choice belongs to: stock and past restocks each remember their own. */
export type ListName = 'stock' | 'restocks';

/** Who the remembered choice belongs to: the user's id, never an email or a phone number. */
export const listUser = (me: { user_id?: string }) => me.user_id ?? 'staff';

const chainKey = (user: string, list: ListName) => `bms.listChain.${list}.${user}`;
const viewKey = (user: string, list: ListName) => `bms.listView.${list}.${user}`;

export function readChainOf<L extends string>(user: string, list: ListName, order: readonly L[], known: readonly L[] = order): L[] {
  try {
    return parseOf(localStorage.getItem(chainKey(user, list)), known) ?? [...order];
  } catch {
    return [...order];
  }
}

export function writeChainOf(user: string, list: ListName, chain: readonly string[]): void {
  try {
    localStorage.setItem(chainKey(user, list), JSON.stringify(chain));
  } catch {
    // A private window or blocked storage: the choice lasts until the page is left.
  }
}

/** Whether the list is drawn flat (the way it always was) or grouped by the chain; flat until the person chooses. */
export type ListView = 'flat' | 'grouped';

export function readView(user: string, list: ListName): ListView {
  try {
    return localStorage.getItem(viewKey(user, list)) === 'grouped' ? 'grouped' : 'flat';
  } catch {
    return 'flat';
  }
}

export function writeView(user: string, list: ListName, view: ListView): void {
  try {
    localStorage.setItem(viewKey(user, list), view);
  } catch {
    // A private window or blocked storage: the choice lasts until the page is left.
  }
}

/** With one shop already chosen at the top of the page the shop level would be a single row: leave it out. */
export function withoutShop<L extends string>(chain: readonly L[], shopChosen: boolean): L[] {
  const without = chain.filter((l) => l !== 'branch');
  return shopChosen && without.length > 0 ? without : [...chain];
}

/** What the user opened above a level: it travels to the server as filters. */
export interface ListSelection {
  branchId?: string;
  from?: string;
  to?: string;
  supplierId?: string;
  categoryId?: string;
  itemId?: string;
}

/** A group as far as the selection needs it. */
export interface PickedGroup {
  key?: string;
  from?: string;
  to?: string;
}

/** The later of the starts and the earlier of the ends: the days a group's children are asked for. */
function narrowed(group: PickedGroup, sel: ListSelection): { from?: string; to?: string } {
  const from = [group.from, sel.from].filter((x): x is string => !!x).sort().at(-1);
  const to = [group.to, sel.to].filter((x): x is string => !!x).sort().at(0);
  return { ...(from ? { from } : {}), ...(to ? { to } : {}) };
}

/** The selection after opening `group` at `level`: the parent's selection plus what this group is. */
export function selectIn(sel: ListSelection, level: string, group: PickedGroup): ListSelection {
  switch (level) {
    case 'branch': return { ...sel, branchId: group.key };
    case 'supplier': return { ...sel, supplierId: group.key };
    case 'category': return { ...sel, categoryId: group.key };
    case 'item': case 'variant': return { ...sel, itemId: group.key };
    default: return { ...sel, ...narrowed(group, sel) };
  }
}

/** Whether a restocks group at `index` offers "Show the restocks": on a day, on any level when the chain has no day, and above a last item level. */
export function offersRestocks(chain: readonly string[], index: number): boolean {
  const level = chain[index];
  const last = chain.length - 1;
  return level === 'day' || !chain.includes('day') || (index + 1 === last && (chain[last] === 'item' || chain[last] === 'variant'));
}

export const pathOf = (parent: string, level: string, key: string) => `${parent}/${level}:${key}`;

export { toggleGroup };
export type { OpenGroups };

/** The words under a restock group that is a category or an item: whole restocks, so their totals can be more than the line above. */
export function wholeRestocksNote(sel: ListSelection): string | null {
  if (sel.itemId) return 'Whole purchases that include this item.';
  if (sel.categoryId) return 'Whole purchases that include this category.';
  return null;
}

/** "3 purchases" or "1 purchase". */
export const restocksWord = (n: number) => (n === 1 ? '1 purchase' : `${n} purchases`);

/** "3 items" or "1 item". */
export const itemsWord = (n: number) => (n === 1 ? '1 item' : `${n} items`);
