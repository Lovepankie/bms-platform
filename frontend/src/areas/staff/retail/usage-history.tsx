import { keepPreviousData, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { businessToday, daysBefore, retail, type EntryDistance, type StockMovement, type UsageLevel, type UsageSummaryGroup } from '../../../api/retail';
import { ListChain, ViewSwitch, useListView, type TreeKind } from './list-chain';
import { USAGE_ORDER, USAGE_PLURALS, listUser, type ListSelection } from './list-chain-state';
import { periodTitle } from './sales-grouped-state';
import { PeriodPicker } from '../../../app/ui/PeriodPicker';
import { SearchBox } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { Hint } from '../../../app/ui/Hint';
import { EmptyState } from '../../../app/ui/EmptyState';
import { useStaff } from '../context';
import { ENTRY_LOCATION, USAGE_REPORT, canUse, holds, holdsAt } from './permissions';
import { ReceiptPhoto } from './receipt-photo';
import { useEntryDistances, whereWords } from './entry-location';
import { UndoUsageAction, UndoneMark, undoneWords } from './usage-undo';
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

/**
 * Issue #818: the movements that are the first line of their entry in the list, worked out once per list, so Undo this entry
 * and the Undone words show once per entry. How many lines an entry has is the server's to say, never counted here.
 */
export function firstLines(items: StockMovement[]): Set<string> {
  const seen = new Set<string>();
  const first = new Set<string>();
  for (const m of items) {
    const entry = m.source_id ? `${m.source_type ?? ''}|${m.source_id}` : `line|${m.id ?? ''}`;
    if (!seen.has(entry)) {
      seen.add(entry);
      first.add(m.id ?? '');
    }
  }
  return first;
}

function UsageRow({ m, branch, where, first }: { m: StockMovement; branch?: string; where?: EntryDistance; first: boolean }) {
  const { me } = useStaff();
  const cost = costOf(m);
  return (
    <li className="rt-card" data-testid="usage-row">
      <p>
        <strong>{m.description}</strong> {m.code && <span className="hint">({m.code})</span>}{' '}
        <span className={m.kind === 'damage' ? 'badge badge-warning' : 'badge badge-info'}>{m.kind === 'damage' ? 'Damaged' : 'Used'}</span>
        {m.undone && <>{' '}<UndoneMark /></>}
      </p>
      <p>
        <span className="num">{takenOut(m)}</span> taken out on {showDate(dayOf(m))}{branch ? ` at ${branch}` : ''}.
        {cost !== null && <> Worth <Money minor={cost} /> at buying price.</>}
      </p>
      {m.note && <p className="hint">Reason: {m.note}</p>}
      {m.undone && first && <p className="hint" data-testid="usage-undone-words">{undoneWords(m)}. The stock went back.</p>}
      {where && <p className="hint" data-testid="usage-where">Where: {whereWords(where)}</p>}
      {/* Issue #819: the entry's receipt photo, once per entry, on its first line. */}
      {first && <ReceiptPhoto kind="usage" recordId={m.source_id} has={m.has_receipt_photo} canAdd={!m.undone && holdsAt(me, USAGE_REPORT, m.branch_id)} />}
      {first && <UndoUsageAction m={m} />}
    </li>
  );
}

// JUSTIFICATION-A3: issue #816 asks for this list grouped by shop, month, category and item with totals, as the old app did.
/** "2 used, 1 damaged", leaving out a kind with none. */
export function kindsWord(g: UsageSummaryGroup): string {
  const parts = [(g.used_count ?? 0) > 0 ? `${g.used_count} used` : '', (g.damaged_count ?? 0) > 0 ? `${g.damaged_count} damaged` : ''].filter(Boolean);
  return parts.join(', ');
}

const usageKind = (filter: { productId: string }): TreeKind<UsageLevel, UsageSummaryGroup> => ({
  name: 'usage',
  plurals: USAGE_PLURALS,
  fetch: (level, chain, sel, page, signal) =>
    retail.usageSummary({ level, chain, signal, branchId: sel.branchId, from: sel.from, to: page.to ?? sel.to, categoryId: sel.categoryId, itemId: sel.itemId ?? (filter.productId || undefined), offset: page.offset }),
  title: (level, g) => (level === 'month' ? periodTitle('month', g.key ?? '') : g.label ?? g.key ?? ''),
  // The cost is the server's, present only with retail.profit.read; the count and the quantity are for everyone who reads the list.
  figures: (_level, g) => (
    <>
      {g.cost_minor !== undefined && <><span className="rt-group-total"><Money minor={g.cost_minor} /></span>{' '}</>}
      <span className="rt-group-meta">
        {g.quantity !== undefined && <><span className="num">{showQty(g.quantity)}</span> {g.unit} taken out, </>}
        {g.records_count === 1 ? '1 record' : `${g.records_count ?? 0} records`}: {kindsWord(g)}
        {g.cost_minor !== undefined && ', at buying price'}
      </span>
    </>
  ),
  nothing: 'Nothing was used or damaged here.',
});

/**
 * The records of an opened item: the list's own rows, read with what was opened above. The chain is always shop, month, category
 * and item (no Group by here, as the old app), so rows open only under an item and the item names them; the category is above it.
 */
function UsageRows({ sel, all, nameOf }: { sel: ListSelection; all: boolean; nameOf: (id: string | undefined) => string }) {
  const page = useInfiniteQuery({
    queryKey: ['retail', 'usage-history', 'rows', sel],
    queryFn: ({ pageParam, signal }) => retail.usageHistory({ from: sel.from ?? '', to: sel.to ?? '', ...(sel.branchId ? { branchId: sel.branchId } : {}), ...(sel.itemId ? { productId: sel.itemId } : {}), ...(pageParam ? { cursor: pageParam } : {}), signal }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
  });
  const items = (page.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const first = firstLines(items);
  return (
    <>
      {page.isPending && <p className="loading">Loading</p>}
      <Problem error={page.error} />
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {items.map((m) => <UsageRow key={m.id} m={m} branch={all && !sel.branchId ? nameOf(m.branch_id) : undefined} first={first.has(m.id ?? '')} />)}
      </ul>
      {page.hasNextPage && <button type="button" onClick={() => void page.fetchNextPage()} disabled={page.isFetchingNextPage}>{page.isFetchingNextPage ? 'Loading' : 'Show older records'}</button>}
    </>
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
  // Issue #816: Flat list as it always was, or Grouped by shop, month, category and item with the server's totals.
  const view = useListView<UsageLevel>(listUser(me), 'usage', USAGE_ORDER);
  const grouped = view.view === 'grouped';
  const page = useInfiniteQuery({
    queryKey: ['retail', 'usage-history', from, to, branch ?? 'all', productId],
    queryFn: ({ pageParam, signal }) => retail.usageHistory({ from, to, ...(branch ? { branchId: branch } : {}), ...(productId ? { productId } : {}), ...(pageParam ? { cursor: pageParam } : {}), signal }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
    enabled: ready && !grouped,
  });
  const items = (page.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const first = firstLines(items);
  // ADR-122: how far from the shop each entry was made, for the owner only (asked for the entries, not the lines).
  const usage = (m: StockMovement) => (m.source_type === 'retail.usage' ? (m.source_id ?? '') : '');
  const where = useEntryDistances('usage', items.map(usage), holds(me, ENTRY_LOCATION));
  return (
    <Gate screen="stock" title="Past usage and damage">
      <Hint>Every item taken out of stock as used in the shop or damaged. Choose the days, the branch at the top of the page, or one item. An entry undone stays here marked Undone and counts in no total.</Hint>
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
      <ViewSwitch view={view.view} onChange={view.setView} />
      {grouped && ready && (
        <ListChain<UsageLevel, UsageSummaryGroup>
          kind={usageKind({ productId })} filterKey={{ productId }} chain={[...USAGE_ORDER]} enabled={ready} view={view} rootLabel="Past usage and damage"
          range={{ from, to, ...(branch ? { branchId: branch } : {}) }}
          renderRows={(sel) => <UsageRows sel={sel} all={all} nameOf={nameOf} />}
        />
      )}
      {!grouped && <>
      {ready && page.isPending && <p className="loading">Loading</p>}
      <Problem error={page.error} />
      {page.data && items.length === 0 && (
        <EmptyState
          text="Nothing was used or damaged in these dates."
          action={canUse(me, 'usage') ? { label: 'Record usage or damage', to: '/staff/retail/usage' } : { label: 'Show the last year', onClick: () => { setFrom(daysBefore(today, 365)); setTo(today); } }}
        />
      )}
      <ul style={{ listStyle: 'none', padding: 0 }} data-tour="usage-history-list">
        {items.map((m) => <UsageRow key={m.id} m={m} branch={all ? nameOf(m.branch_id) : undefined} where={where.get(usage(m))} first={first.has(m.id ?? '')} />)}
      </ul>
      {page.hasNextPage && <button type="button" onClick={() => void page.fetchNextPage()} disabled={page.isFetchingNextPage}>{page.isFetchingNextPage ? 'Loading' : 'Show older records'}</button>}
      </>}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/usage-history')({ component: UsageHistoryPage });
