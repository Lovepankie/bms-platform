import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { sellingBranches, needsOf } from './permissions';
import { RecordSale } from './sale';
import { StocktakePage } from './stocktake';
import { BranchRequired } from './ui';

// Issue #329 (ADR-041): a branch that keeps no goods (a head office) is not a branch to sell, move, count or restock in. The server
// refuses it (422 branch_not_stocking); these tests prove the screens do not offer it. Branch names are fabricated.

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');

function meWithHeadNotStocking() {
  const me = mockMe('admin');
  me.branches = (me.branches ?? []).map((b, i) => (i === 0 ? { ...b, stocks_goods: false } : b));
  return me;
}

function on(me: ReturnType<typeof mockMe>, branch: string, node: ReactElement) {
  return renderToString(
    <QueryClientProvider client={new QueryClient()}>
      <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

describe('a store, a branch that holds stock but does not sell (#366)', () => {
  function meWithStore() {
    const me = mockMe('admin');
    me.branches = (me.branches ?? []).map((b, i) => (i === 0 ? { ...b, sells_goods: false } : b));
    return me;
  }

  it('is not a branch a sale can be made at, yet stock screens still offer it', () => {
    const me = meWithStore();
    const first = me.branches?.[0]?.id ?? '';
    expect(sellingBranches(me.branches ?? [], undefined).map((b) => b.id)).not.toContain(first);
    const asked = on(me, 'all', <BranchRequired permissions={needsOf('stocktake')} stocking />);
    expect(asked).toContain('Test Branch A (BR1)');
  });

  it('shows the sale screen as a plain note with a button for each shop', () => {
    const me = meWithStore();
    const first = me.branches?.[0]?.id ?? '';
    const html = on(me, first, <RecordSale />);
    expect(html).toContain('is a store, and a store does not sell');
    expect(html).toContain('Sell at Test Branch B (BR2)');
  });
});

describe('a branch that keeps no goods', () => {
  it('is left out of the choices of a stock screen, and the screen says why when it is the one chosen', () => {
    const me = meWithHeadNotStocking();
    const first = me.branches?.[0]?.id ?? '';
    const asked = on(me, first, <BranchRequired permissions={needsOf('stocktake')} stocking />);
    expect(asked).toContain('keeps no goods, so nothing is sold, restocked, moved or counted there');
    expect(asked).toContain('Test Branch B (BR2)');
    expect(asked).not.toContain('<button type="button" class="btn-sm" disabled="">Test Branch A');
    expect(asked).not.toMatch(/<button[^>]*>Test Branch A/);
  });

  it('is still offered on a screen that is not about goods', () => {
    const me = meWithHeadNotStocking();
    const cash = on(me, 'all', <BranchRequired permissions={needsOf('stocktake')} />);
    expect(cash).toContain('Test Branch A (BR1)');
  });

  it('is not where the sale and stock-take screens work', () => {
    const me = meWithHeadNotStocking();
    const first = me.branches?.[0]?.id ?? '';
    for (const html of [on(me, first, <RecordSale />), on(me, first, <StocktakePage />)]) {
      expect(html).toContain('keeps no goods');
      expect(html).not.toContain('Branch: <strong>');
    }
  });

  it('is not a branch a sale can be made at', () => {
    const me = meWithHeadNotStocking();
    expect(sellingBranches(me.branches ?? [], undefined).map((b) => b.id)).not.toContain(me.branches?.[0]?.id);
  });
});
