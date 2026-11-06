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
import { useAddress, useBranchFromAddress } from './screen-link';
import { ToStock } from './analytics-ui';

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

type UsageKind = 'usage' | 'damage' | 'missing';

/** The kinds the address may ask for: the server's kind, the word in the address and what the page says while it shows. */
const KINDS: { kind: UsageKind; word: string; shown: string }[] = [
  { kind: 'usage', word: 'used', shown: 'Showing used stock only.' },
  { kind: 'damage', word: 'damaged', shown: 'Showing damaged stock only.' },
  { kind: 'missing', word: 'missing', shown: 'Showing what stock counts found missing only.' },
];

/**
 * Issue #863: a line a stock count found short, the rows behind Stock health's Found missing. The item opens its stock at
 * that branch; the cost is the server's, present only with retail.profit.read.
 */
function MissingRow({ m, branch }: { m: StockMovement; branch?: string }) {
  const cost = costOf(m);
  return (
    <li className="rt-card" data-testid="missing-row">
      <p>
        <strong><ToStock item={m.product_id} branch={m.branch_id}>{m.description}</ToStock></strong> {m.code && <span className="hint">({m.code})</span>}{' '}
        <span className="badge badge-info">Found missing</span>
      </p>
      <p>
        <span className="num">{takenOut(m)}</span> missing at the stock count on {showDate(dayOf(m))}{branch ? ` at ${branch}` : ''}.
        {cost !== null && <> Worth <Money minor={cost} /> at buying price.</>}
      </p>
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
  // Issue #798: a count on Stock health opens this page on its days and its branch (`?from=&to=&branch=`).
  const { search: asked, go } = useAddress();
  const day = (v: unknown) => (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) ? v : undefined);
  const [from, setFrom] = useState(day(asked.from) ?? daysBefore(today, 30));
  const [to, setTo] = useState(day(asked.to) ?? today);
  const askedBranch = typeof asked.branch === 'string' && asked.branch !== '' ? asked.branch : undefined;
  // `?kind=used|damaged|missing` (a count on Stock health) shows only that kind, in the flat list, until Show used and damaged.
  // Issue #863: missing is the short lines of committed stock counts, the rows behind Found missing.
  const [kind, setKind] = useState<UsageKind | undefined>(KINDS.find((k) => k.word === asked.kind)?.kind);
  const kindWord = KINDS.find((k) => k.kind === kind)?.word;
  const unknownBranch = useBranchFromAddress(askedBranch, go, '/staff/retail/usage-history', { from, to, ...(kindWord ? { kind: kindWord } : {}) });
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
  const grouped = view.view === 'grouped' && kind === undefined;
  const page = useInfiniteQuery({
    queryKey: ['retail', 'usage-history', from, to, branch ?? 'all', productId, kind ?? 'both'],
    queryFn: ({ pageParam, signal }) => retail.usageHistory({ from, to, ...(branch ? { branchId: branch } : {}), ...(productId ? { productId } : {}), ...(kind ? { kind } : {}), ...(pageParam ? { cursor: pageParam } : {}), signal }),
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
        <PeriodPicker from={from} to={to} onChange={(range) => {
          setFrom(range.from);
          setTo(range.to);
          // Opened from a link (issue #798): the address follows the days chosen, so a reload keeps them and not the old ones.
          if (asked.from !== undefined || asked.to !== undefined || asked.kind !== undefined) go('/staff/retail/usage-history', { from: range.from, to: range.to, ...(kindWord ? { kind: kindWord } : {}) }, true);
        }} />
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
      {unknownBranch !== null && <p className="hint" data-testid="branch-not-yours">That branch cannot be opened here (it is closed, or not one you work at), so this is the branch you are using.</p>}
      {from > to && <p className="hint" role="status">The first day is after the last day. Choose the days again.</p>}
      {kind !== undefined && (
        <div role="status" className="alert alert-info" data-testid="one-kind">
          <p>{KINDS.find((k) => k.kind === kind)?.shown}</p>
          <button type="button" className="btn-sm" onClick={() => { setKind(undefined); go('/staff/retail/usage-history', { from, to }, true); }}>Show used and damaged</button>
        </div>
      )}
      {kind === undefined && <ViewSwitch view={view.view} onChange={view.setView} />}
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
      {page.data && items.length === 0 && kind === 'missing' && <p className="empty-state">No stock count found anything missing in these dates. Choose other dates, or Show used and damaged.</p>}
      {page.data && items.length === 0 && kind !== 'missing' && (
        <EmptyState
          text="Nothing was used or damaged in these dates."
          action={canUse(me, 'usage') ? { label: 'Record usage or damage', to: '/staff/retail/usage' } : { label: 'Show the last year', onClick: () => { setFrom(daysBefore(today, 365)); setTo(today); } }}
        />
      )}
      <ul style={{ listStyle: 'none', padding: 0 }} data-tour="usage-history-list">
        {items.map((m) => (m.source_type === 'retail.stocktake'
          ? <MissingRow key={m.id} m={m} branch={all ? nameOf(m.branch_id) : undefined} />
          : <UsageRow key={m.id} m={m} branch={all ? nameOf(m.branch_id) : undefined} where={where.get(usage(m))} first={first.has(m.id ?? '')} />))}
      </ul>
      {page.hasNextPage && <button type="button" onClick={() => void page.fetchNextPage()} disabled={page.isFetchingNextPage}>{page.isFetchingNextPage ? 'Loading' : 'Show older records'}</button>}
      </>}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/usage-history')({ component: UsageHistoryPage });
