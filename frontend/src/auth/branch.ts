// The active branch (FR-BR-03, FR-BR-04): one branch at a time, or "All branches" for a user whose
// scope covers every branch. Remembered per user in this browser only; lists send it as branch_id.

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

/** The stored choice when still allowed, else the default branch, else the first one. */
export function initialBranch(me: MeForBranches, stored: string | null): string | null {
  const branches = me.branches ?? [];
  if (stored === ALL_BRANCHES && me.all_branches) return ALL_BRANCHES;
  if (stored && branches.some((b) => b.id === stored)) return stored;
  return me.default_branch_id ?? branches[0]?.id ?? null;
}

/** The branch_id filter a list request sends: none for "All branches". */
export function branchFilter(selection: string | null): string[] | undefined {
  return selection && selection !== ALL_BRANCHES ? [selection] : undefined;
}

const key = (userId: string) => `bms.activeBranch.${userId}`;

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
