import { useInfiniteQuery } from '@tanstack/react-query';
import { retail, type Purchase, type PurchaseLevel, type PurchasesSummaryGroup } from '../../../api/retail';
import { ListChain, type ListViewState, type TreeKind } from './list-chain';
import { PURCHASE_PLURALS, offersRestocks, restocksWord, wholeRestocksNote, type ListSelection } from './list-chain-state';
import { showQty } from './maths';
import { periodTitle } from './sales-grouped-state';
import { Problem, money } from './ui';

// Past restocks grouped by a chain of levels (issue #350): shop, year, month, supplier, day, category, item, with a summary at every
// level. The groups are the server's figures (GET /retail/purchases/summary); the restocks of an opened group are the screen's own
// cards (RestockCard), so Correct this restock and its void work inside the grouped view exactly as in the flat list.

const timeTitle = (level: PurchaseLevel, key: string) => (level === 'year' || level === 'month' || level === 'day' ? periodTitle(level, key) : null);

const kindFor = (status: 'all' | 'posted' | 'voided'): TreeKind<PurchaseLevel, PurchasesSummaryGroup> => ({
  name: 'restocks',
  plurals: PURCHASE_PLURALS,
  fetch: (level, chain, sel, page, signal) =>
    retail.purchasesSummary({
      level, chain, signal, branchId: sel.branchId, from: sel.from, to: page.to ?? sel.to, supplierId: sel.supplierId, categoryId: sel.categoryId, itemId: sel.itemId, status: status === 'all' ? undefined : status, offset: page.offset,
    }),
  title: (level, g) => timeTitle(level, g.key ?? '') ?? g.label ?? g.key ?? '',
  figures: (level, g, sel) => (
    <>
      {g.total_minor !== undefined && <><span className="rt-group-total">{money(g.total_minor)}</span>{' '}</>}
      <span className="rt-group-meta">
        {g.quantity !== undefined && <>{showQty(g.quantity)} {g.unit} bought, </>}
        {level === 'category' || level === 'item' || level === 'variant' || sel.categoryId || sel.itemId ? 'in ' : ''}{restocksWord(g.purchases_count ?? 0)}
        {(g.voided_count ?? 0) > 0 && <>, {g.voided_count} voided (not counted)</>}
      </span>
    </>
  ),
  nothing: 'No restocks here.',
});

/** The restocks of an opened group, with the screen's own cards: a group of a category or an item lists whole restocks that include it. */
function RestockRows({ sel, status, render }: { sel: ListSelection; status: 'all' | 'posted' | 'voided'; render: (p: Purchase) => React.ReactNode }) {
  const page = useInfiniteQuery({
    queryKey: ['retail', 'restocks', 'rows', sel, status],
    queryFn: ({ pageParam, signal }) =>
      retail.listPurchases({
        branchId: sel.branchId, from: sel.from, to: sel.to, supplierId: sel.supplierId, categoryId: sel.categoryId, productId: sel.itemId,
        ...(status === 'all' ? {} : { status }), ...(pageParam ? { cursor: pageParam } : {}), signal,
      }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
  });
  const items = (page.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const note = wholeRestocksNote(sel);
  return (
    <>
      {note && <p className="rt-group-note">{note}</p>}
      {page.isPending && <p className="loading">Loading</p>}
      <Problem error={page.error} />
      {page.data && items.length === 0 && <p className="empty-state">No restocks here.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>{items.map(render)}</ul>
      {page.hasNextPage && <button type="button" onClick={() => void page.fetchNextPage()} disabled={page.isFetchingNextPage}>{page.isFetchingNextPage ? 'Loading' : 'Show older restocks'}</button>}
    </>
  );
}

export function GroupedRestocks({ view, range, status, render }: {
  view: ListViewState<PurchaseLevel>;
  range: { from: string; to: string };
  status: 'all' | 'posted' | 'voided';
  render: (p: Purchase) => React.ReactNode;
}) {
  return (
    <ListChain<PurchaseLevel, PurchasesSummaryGroup>
      kind={kindFor(status)} filterKey={status} chain={view.chain} enabled={range.from <= range.to} view={view} rootLabel="Past restocks" range={range}
      offersRows={(i) => offersRestocks(view.chain, i)} rowsWord="restocks"
      renderRows={(sel) => <RestockRows sel={sel} status={status} render={render} />}
    />
  );
}
