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
  if (!keepsGoods(b)) return 'Keeps no goods: no sales, restocks or stock-takes here, and no goods can be moved in. Cash records still work, and goods left here can be moved out.';
  return b.is_head_office ? 'Head office.' : 'Open.';
}
