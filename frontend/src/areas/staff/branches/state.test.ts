import { describe, expect, it } from 'vitest';
import type { AdminBranch } from '../../../api/branches';
import { branchLine, canManageBranches, closedCount, closedOn, isClosed, keepsGoods, sellsGoods, showBranches, stockValueWords, visibleBranches } from './state';

const open: AdminBranch = { id: 'a', code: 'KLA', name: 'Test Shop', status: 'active' };
const closed: AdminBranch = { id: 'b', code: 'OLD', name: 'Test Old', status: 'inactive', closed_at: '2026-08-14T10:00:00Z' };
const head: AdminBranch = { id: 'h', code: 'HQ', name: 'Head', is_head_office: true, status: 'active', stocks_goods: false };

describe('the Branches rules (#329)', () => {
  it('reads who may see and who may change', () => {
    expect(showBranches({ permissions: ['core.branches.read'] })).toBe(true);
    expect(showBranches({ permissions: [] })).toBe(false);
    expect(canManageBranches({ permissions: ['core.branches.read'] })).toBe(false);
    expect(canManageBranches({ permissions: ['core.branches.manage'] })).toBe(true);
  });

  it('calls the stored inactive status closed, and hides closed branches by default', () => {
    expect(isClosed(closed)).toBe(true);
    expect(isClosed(open)).toBe(false);
    expect(visibleBranches([open, closed, head], false)).toEqual([open, head]);
    expect(visibleBranches([open, closed, head], true)).toHaveLength(3);
    expect(closedCount([open, closed, head])).toBe(1);
  });

  it('reads a missing stocking flag as keeping goods', () => {
    expect(keepsGoods(open)).toBe(true);
    expect(keepsGoods(head)).toBe(false);
  });

  it('words each branch plainly', () => {
    expect(branchLine(open)).toBe('Open.');
    expect(branchLine(closed)).toMatch(/^Closed\./);
    expect(branchLine(head)).toMatch(/^Keeps no goods/);
    expect(closedOn(closed)).toBe('Closed on 14 Aug 2026');
    expect(closedOn(open)).toBeNull();
    expect(closedOn({ ...closed, closed_at: undefined })).toBeNull();
  });

  it('reads a missing selling flag as a shop and words a store plainly (#366)', () => {
    const store: AdminBranch = { ...open, sells_goods: false };
    expect(sellsGoods(open)).toBe(true);
    expect(sellsGoods(store)).toBe(false);
    expect(branchLine(store)).toMatch(/^Store: holds stock/);
  });

  it('shows the stock value in the business currency, or nothing when unknown', () => {
    expect(stockValueWords({ currency: 'UGX', stockValueMinor: 1250000 })).toBe('UGX 1,250,000');
    expect(stockValueWords({ currency: 'XXX', stockValueMinor: 5 })).toBeNull();
    expect(stockValueWords(undefined)).toBeNull();
  });
});
