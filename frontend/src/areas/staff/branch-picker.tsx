import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import type { Me } from '../../api/client';
import { retail } from '../../api/retail';
import { ALL_BRANCHES, branchLabel, initialBranch, loadBranch, saveBranch } from '../../auth/branch';

// The branch picker in the staff bar (FR-BR-03) and the branch it starts on (#103). A user with a
// stored choice gets it back; anyone else starts on a branch that trades rather than an empty head
// office, which needs one stock read, so the staff area waits for it before showing a screen.

/** The branches holding stock, for a session that may read stock; shared with the retail screens. */
export function useStockedBranches(me: Me | undefined) {
  return useQuery({
    queryKey: ['retail', 'stocked-branches'],
    queryFn: () => retail.stockedBranches(),
    enabled: (me?.permissions ?? []).includes('retail.stock.read'),
    staleTime: 60_000,
    retry: 1,
  });
}

/** The active branch, a way to choose it (remembered per tenant and user), and whether it is known yet. */
export function useActiveBranch(me: Me | undefined): { branch: string | null; choose: (selection: string) => void; ready: boolean } {
  const [branch, setBranch] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const stocked = useStockedBranches(me);
  // Waits for the stock read only while it is on its way (a failure or no stock read starts as before).
  const waiting = (me?.permissions ?? []).includes('retail.stock.read') && stocked.isPending;

  useEffect(() => {
    if (!me || waiting) return;
    const valid = (s: string | null) => (s === ALL_BRANCHES ? Boolean(me.all_branches) : (me.branches ?? []).some((b) => b.id === s));
    // A profile refetch (every token refresh) keeps the branch already in use.
    setBranch((current) => (current !== null && valid(current) ? current : initialBranch(me, loadBranch(me.user_id ?? ''), stocked.data ? new Set(stocked.data) : null)));
    setReady(true);
  }, [me, waiting, stocked.data]);

  const choose = (selection: string) => {
    setBranch(selection);
    if (me) saveBranch(me.user_id ?? '', selection);
  };
  return { branch, choose, ready };
}

export function BranchPicker({ me, branch, onChoose }: { me: Me; branch: string | null; onChoose: (selection: string) => void }) {
  return (
    <label className="branch-picker" data-tour="branch-picker">
      Branch{' '}
      <select value={branch ?? ''} onChange={(e) => onChoose(e.target.value)}>
        {me.all_branches && <option value={ALL_BRANCHES}>All branches</option>}
        {(me.branches ?? []).map((b) => (
          <option key={b.id} value={b.id}>
            {branchLabel(b)}
          </option>
        ))}
      </select>
    </label>
  );
}
