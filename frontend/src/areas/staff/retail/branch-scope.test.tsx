import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import { mockMe } from '../../../api/retail-mock';
import { ALL_BRANCHES } from '../../../auth/branch';
import { StaffContext } from '../context';
import { useSingleBranch, useStockingBranch } from './ui';

// Review of #559, point 4: the write screens (Sale, Expenses, Banking, Savings, Cash withdrawals, Advances, Usage, Count stock)
// dropped their "Branch: X" line because the top bar's title names the branch. That holds only while the branch they record at is
// always the one in that title: these hooks hand back exactly the branch chosen at the top, or none (the screen then asks for a
// branch), and never fall back to another of the person's shops. Every name is invented.

function Probe({ stocking }: { stocking: boolean }) {
  const single = useSingleBranch();
  const stock = useStockingBranch();
  const { branchId } = stocking ? stock : single;
  return <>{branchId ?? 'none'}</>;
}

function at(me: Me, branch: string | null, stocking = false) {
  return renderToString(
    <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined }}>
      <Probe stocking={stocking} />
    </StaffContext.Provider>,
  );
}

describe('the branch a write screen records at', () => {
  const me = mockMe('admin');
  const [a, b] = me.branches ?? [];

  it('is the branch in the title of the top bar, for each branch', () => {
    for (const branch of [a, b]) {
      expect(at(me, branch?.id ?? null)).toBe(branch?.id);
      expect(at(me, branch?.id ?? null, true)).toBe(branch?.id);
    }
  });

  it('is none, never another shop, for All branches, no choice, or a branch that keeps no goods', () => {
    expect(at(me, ALL_BRANCHES)).toBe('none');
    expect(at(me, null)).toBe('none');
    expect(at(me, ALL_BRANCHES, true)).toBe('none');
    const office = { ...me, branches: (me.branches ?? []).map((x, i) => (i === 0 ? { ...x, stocks_goods: false } : x)) } as Me;
    expect(at(office, a?.id ?? null, true)).toBe('none');
  });
});
