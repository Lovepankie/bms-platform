// The active branch (FR-BR-03, FR-BR-04): one branch at a time, or "All branches" for a user whose
// scope covers every branch. Remembered per tenant and user in this browser only; lists send it as
// branch_id.

export const ALL_BRANCHES = 'all';

// Shapes as generated from the contract, where every field is optional.
export interface BranchChoice {
  id?: string;
  code?: string;
  name?: string;
  is_head_office?: boolean;
}

export interface MeForBranches {
  all_branches?: boolean;
  branches?: BranchChoice[];
  default_branch_id?: string | null;
}

/**
 * How a branch reads in a picker or on a screen (#105): the name alone when the code is the name in
 * other letters (an imported "GAYAZA Gayaza"), otherwise "Name (CODE)", the code kept to tell
 * branches apart.
 */
export function branchLabel(b: BranchChoice | undefined): string {
  if (!b) return '';
  const name = (b.name ?? '').trim();
  const code = (b.code ?? '').trim();
  if (!name || !code) return name || code;
  return name.toLowerCase() === code.toLowerCase() ? name : `${name} (${code})`;
}

/**
 * The branch the staff area starts on (#103): the stored choice when still allowed (the last one this
 * user picked), else the only branch the user has, else a branch that holds stock (the user's default
 * branch first), else the default branch, but never a head office with nothing on its shelves while
 * other branches exist. `stocked` lists the branches holding stock, or is null when that is unknown
 * (no stock read, or not loaded), and then the default branch stands as before.
 */
export function initialBranch(me: MeForBranches, stored: string | null, stocked: ReadonlySet<string> | null = null): string | null {
  const branches = me.branches ?? [];
  if (stored === ALL_BRANCHES && me.all_branches) return ALL_BRANCHES;
  if (stored && branches.some((b) => b.id === stored)) return stored;
  if (branches.length === 1) return branches[0]?.id ?? null;
  const fallback = branches.find((b) => b.id === me.default_branch_id) ?? branches[0];
  if (stocked) {
    if (fallback?.id && stocked.has(fallback.id)) return fallback.id;
    const trading = branches.find((b) => b.id && stocked.has(b.id));
    if (trading) return trading.id ?? null;
    // No branch holds stock yet: still never open on the empty head office when a shop exists.
    if (fallback?.is_head_office) return branches.find((b) => !b.is_head_office)?.id ?? fallback.id ?? null;
  }
  return fallback?.id ?? null;
}

/** The branch_id filter a list request sends: none for "All branches". */
export function branchFilter(selection: string | null): string[] | undefined {
  return selection && selection !== ALL_BRANCHES ? [selection] : undefined;
}

// Per tenant and user: the tenant is the host (or the dev server's X-Tenant slug).
function key(userId: string): string {
  const env = import.meta.env;
  const tenant = env.DEV && env.VITE_DEV_TENANT ? env.VITE_DEV_TENANT : window.location.hostname;
  return `bms.activeBranch.${tenant}.${userId}`;
}

export function loadBranch(userId: string): string | null {
  try {
    return window.localStorage.getItem(key(userId));
  } catch {
    return null;
  }
}

export function saveBranch(userId: string, selection: string): void {
  try {
    window.localStorage.setItem(key(userId), selection);
  } catch {
    // Private windows may refuse storage; the choice then lasts for this page only.
  }
}
