import type { Me } from '../../../api/client';
import type { AdminBranch, CloseFacts } from '../../../api/branches';
import { formatMinor } from '../../../components/money';

// The Branches screen's rules, kept out of the component so they can be tested (issue #329; ADR-041). A closed branch is the
// stored status "inactive"; the screens only ever say closed.

const READ = 'core.branches.read';
const MANAGE = 'core.branches.manage';
const held = (me: Pick<Me, 'permissions'>) => me.permissions ?? [];

export const showBranches = (me: Pick<Me, 'permissions'>): boolean => held(me).includes(READ);

/** Closing, reopening and the stocking switch: administrators only (core.branches.manage). Others see the list. */
export const canManageBranches = (me: Pick<Me, 'permissions'>): boolean => held(me).includes(MANAGE);

export const isClosed = (b: Pick<AdminBranch, 'status'>): boolean => b.status === 'inactive';

/** A branch that keeps no goods: false only when the server says so, so an older answer reads as keeping goods. */
export const keepsGoods = (b: { stocks_goods?: boolean }): boolean => b.stocks_goods !== false;

/** A Store (ADR-048): holds stock and takes purchases and transfers, but does not sell. False only when the server says so. */
export const sellsGoods = (b: { sells_goods?: boolean }): boolean => b.sells_goods !== false;

export const closedCount = (all: AdminBranch[]): number => all.filter(isClosed).length;

/** The branches to list: open ones always, closed ones only when asked for. */
export function visibleBranches(all: AdminBranch[], showClosed: boolean): AdminBranch[] {
  return showClosed ? all : all.filter((b) => !isClosed(b));
}

/** "Closed on 12 Aug 2026", or null for an open branch or one closed before the date was kept. */
export function closedOn(b: AdminBranch): string | null {
  if (!isClosed(b) || !b.closed_at) return null;
  const when = new Date(b.closed_at);
  if (Number.isNaN(when.getTime())) return null;
  return `Closed on ${when.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })}`;
}

/** The stock value of a closing warning in the business's currency, or null when it is not known. */
export function stockValueWords(facts: CloseFacts | undefined): string | null {
  if (!facts || facts.stockValueMinor === undefined || !facts.currency) return null;
  try {
    return formatMinor(facts.stockValueMinor, facts.currency);
  } catch {
    return null;
  }
}

/** The one line under a branch's name: what it is, in the words of the people who run it. */
export function branchLine(b: AdminBranch): string {
  if (isClosed(b)) return 'Closed. Its sales and stock records are kept for your reports.';
  if (!sellsGoods(b)) return 'Store: holds stock and takes purchases and transfers in and out. Nothing is sold here. No takings, savings, banking or repayments here; expenses can still be paid.';
  if (!keepsGoods(b)) return 'Keeps no goods: no sales, purchases or stock counts here, and no goods can be moved in. Cash records still work, and goods left here can be moved out.';
  return b.is_head_office ? 'Head office.' : 'Open.';
}

/** What a new branch is (issue #581, ADR-048, ADR-089): the three kinds the Add a branch form offers. */
export type BranchKind = 'shop' | 'store' | 'office';

export const BRANCH_KINDS: readonly { value: BranchKind; label: string }[] = [
  { value: 'shop', label: 'Shop (sells)' },
  { value: 'store', label: 'Store (keeps stock, does not sell)' },
  { value: 'office', label: 'Head office (keeps no goods)' },
];

/** The two flags each kind is sent as: a store keeps stock and does not sell, a head office keeps no goods. */
export const KIND_FLAGS: Record<BranchKind, { sells_goods: boolean; stocks_goods: boolean }> = {
  shop: { sells_goods: true, stocks_goods: true },
  store: { sells_goods: false, stocks_goods: true },
  office: { sells_goods: true, stocks_goods: false },
};

/**
 * The code the server will make from a new branch's name (ADR-089): its letters and digits in capitals, at most six, then 2, 3 and
 * on while the code is taken; BR when the name has fewer than two. Shown on the form only: the server makes the real one, and a
 * branch the caller cannot see may still take a number.
 */
export function madeCode(name: string, taken: readonly string[]): string {
  const letters = name.toUpperCase().replace(/[^A-Z0-9]/g, '');
  const base = letters.length < 2 ? 'BR' : letters.slice(0, 6);
  if (!taken.includes(base)) return base;
  for (let n = 2; ; n++) {
    const code = base.slice(0, 10 - String(n).length) + n;
    if (!taken.includes(code)) return code;
  }
}

/** The toast after a branch is added: its name and what it is, in the words of the people who run it. */
export function addedWords(b: AdminBranch): string {
  const name = b.name ?? 'The branch';
  if (!sellsGoods(b)) return `${name} added. It is a store: it keeps stock and does not sell.`;
  if (!keepsGoods(b)) return `${name} added. It keeps no goods, like a head office.`;
  return `${name} added. It is a shop: it keeps stock and sells.`;
}
