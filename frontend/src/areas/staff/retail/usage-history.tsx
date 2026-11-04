import { keepPreviousData, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { businessToday, daysBefore, retail, type EntryDistance, type StockMovement } from '../../../api/retail';
import { PeriodPicker } from '../../../app/ui/PeriodPicker';
import { SearchBox } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { Hint } from '../../../app/ui/Hint';
import { EmptyState } from '../../../app/ui/EmptyState';
import { useStaff } from '../context';
import { ENTRY_LOCATION, canUse, holds } from './permissions';
import { useEntryDistances, whereWords } from './entry-location';
import { showDate, showQty } from './maths';
import { Gate, Money, Problem, useBranchName, useBranchView } from './ui';

// Past usage and damage (issue #145, AppSheet "Damaged or used stock not sold"): every item taken out of stock as used in the
// shop or damaged, newest first, in the days chosen, at the branch chosen at the top of the page (or every branch), and for
// one item if one is chosen. Read from the stock movements, so it needs only a stock read; the cost shows to the owner only.

/** "2.5 litre", the quantity taken out, without its minus sign. */
export function takenOut(m: StockMovement): string {
  const qty = showQty((m.qty ?? '0').replace(/^-/, ''));
  return m.unit ? `${qty} ${m.unit}` : qty;
}

/** The business day of a movement, yyyy-mm-dd on the business's clock, the day the server's from and to filter by. */
export function dayOf(m: StockMovement): string {
  const at = new Date(m.at ?? '');
  return Number.isNaN(at.getTime()) ? '' : businessToday(at);
}

/** The cost of what was taken out, as the server sent it (retail.profit.read), never recomputed here; null otherwise. */
export function costOf(m: StockMovement): number | null {
  return m.line_cost_minor ?? null;
}

function UsageRow({ m, branch, where }: { m: StockMovement; branch?: string; where?: EntryDistance }) {
  const cost = costOf(m);
  return (
    <li className="rt-card" data-testid="usage-row">
      <p>
        <strong>{m.description}</strong> {m.code && <span className="hint">({m.code})</span>}{' '}
        <span className={m.kind === 'damage' ? 'badge badge-warning' : 'badge badge-info'}>{m.kind === 'damage' ? 'Damaged' : 'Used'}</span>
      </p>
      <p>
        <span className="num">{takenOut(m)}</span> taken out on {showDate(dayOf(m))}{branch ? ` at ${branch}` : ''}.
        {cost !== null && <> Worth <Money minor={cost} /> at buying price.</>}
      </p>
      {m.note && <p className="hint">Reason: {m.note}</p>}
      {where && <p className="hint" data-testid="usage-where">Where: {whereWords(where)}</p>}
    </li>
  );
}

export function UsageHistoryPage() {
  const today = businessToday();
  const [from, setFrom] = useState(daysBefore(today, 30));
  const [to, setTo] = useState(today);
  const { me } = useStaff();
  const { all, branchId } = useBranchView();
  const nameOf = useBranchName();
  const itemBox = useLiveSearch();
  const itemSearch = itemBox.term;
  const [productId, setProductId] = useState('');
  useEffect(() => setProductId(''), [itemSearch]);
  const products = useQuery({ queryKey: ['retail', 'products', 'all', itemSearch], queryFn: ({ signal }) => retail.listProducts({ query: itemSearch, signal }), enabled: itemSearch !== '', placeholderData: keepPreviousData });
  const branch = all ? undefined : (branchId ?? undefined);
  const ready = (all || branchId !== null) && from <= to;
  const page = useInfiniteQuery({
    queryKey: ['retail', 'usage-history', from, to, branch ?? 'all', productId],
    queryFn: ({ pageParam, signal }) => retail.usageHistory({ from, to, ...(branch ? { branchId: branch } : {}), ...(productId ? { productId } : {}), ...(pageParam ? { cursor: pageParam } : {}), signal }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
    enabled: ready,
  });
  const items = (page.data?.pages ?? []).flatMap((p) => p.items ?? []);
  // ADR-122: how far from the shop each entry was made, for the owner only (asked for the entries, not the lines).
  const usage = (m: StockMovement) => (m.source_type === 'retail.usage' ? (m.source_id ?? '') : '');
  const where = useEntryDistances('usage', items.map(usage), holds(me, ENTRY_LOCATION));
  return (
    <Gate screen="stock" title="Past usage and damage">
      <Hint>Every item taken out of stock as used in the shop or damaged. Choose the days, the branch at the top of the page, or one item.</Hint>
      <div data-tour="usage-history-filters">
        <PeriodPicker from={from} to={to} onChange={(range) => { setFrom(range.from); setTo(range.to); }} />
        <SearchBox id="usage-history-item-search" label="Item (type part of its name or code)" search={itemBox} fetching={products.isPlaceholderData || products.isFetching} count={itemSearch !== '' && products.data ? products.data.length : undefined} />
        {itemSearch !== '' && (
          <>
            <label htmlFor="usage-history-item">Choose the item</label>
            {/* empty: a filter of what the search found; Any of these is always there. */}
            <select id="usage-history-item" value={productId} onChange={(e) => setProductId(e.target.value)}>
              <option value="">Any of these</option>
              {(products.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.description} ({p.code})</option>)}
            </select>
          </>
        )}
      </div>
      {from > to && <p className="hint" role="status">The first day is after the last day. Choose the days again.</p>}
      {ready && page.isPending && <p className="loading">Loading</p>}
      <Problem error={page.error} />
      {page.data && items.length === 0 && (
        <EmptyState
          text="Nothing was used or damaged in these dates."
          action={canUse(me, 'usage') ? { label: 'Record usage or damage', to: '/staff/retail/usage' } : { label: 'Show the last year', onClick: () => { setFrom(daysBefore(today, 365)); setTo(today); } }}
        />
      )}
      <ul style={{ listStyle: 'none', padding: 0 }} data-tour="usage-history-list">
        {items.map((m) => <UsageRow key={m.id} m={m} branch={all ? nameOf(m.branch_id) : undefined} where={where.get(usage(m))} />)}
      </ul>
      {page.hasNextPage && <button type="button" onClick={() => void page.fetchNextPage()} disabled={page.isFetchingNextPage}>{page.isFetchingNextPage ? 'Loading' : 'Show older records'}</button>}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/usage-history')({ component: UsageHistoryPage });
