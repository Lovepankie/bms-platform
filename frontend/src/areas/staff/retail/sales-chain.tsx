import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { useContext, useState, type ReactNode } from 'react';
import { retail, type SalesSummaryGroup, type SalesSummaryQuery } from '../../../api/retail';
import {
  LEVEL_PLURALS, emptyChainWords, isTimeLevel, offersSales, overLines, pathKey, readChain, selectGroup, shutGroup, toggleGroup, trailOf, wholeSalesNote, writeChain,
  type ChainLevel, type ChainList, type OpenGroups, type Selection,
} from './sales-chain-state';
import { StaffContext } from '../context';
import { capabilityOn } from './permissions';
import { methodLines, periodTitle, salesWord } from './sales-grouped-state';
import { Problem, money } from './ui';

// The All sales and Credit sales lists grouped by a chain of levels the user chose (issue #341): shop, year, month,
// salesperson, day, product category, item, with a summary at every level. Every header is a figure the server summed
// for that group, and each level is one request that carries what was opened above it as filters. What is open lives
// in the screen (useChainView), so coming back from an opened sale finds the same groups open.

/** The list's own filters, passed on to every level. */
export type ChainFilter = Omit<SalesSummaryQuery, 'level' | 'chain' | 'from' | 'to' | 'sellerId' | 'categoryId' | 'itemId' | 'offset' | 'signal'>;

/** The chain this user chose and the groups that are open; changing the chain closes everything. */
export function useChainView(user: string, list: ChainList) {
  const [chain, setChainState] = useState<ChainLevel[]>(() => readChain(user, list));
  const [open, setOpen] = useState<OpenGroups>(new Map());
  return {
    chain,
    setChain: (next: ChainLevel[]) => {
      setChainState(next);
      writeChain(user, list, next);
      setOpen(new Map());
    },
    open,
    toggle: (key: string, label: string) => setOpen((o) => toggleGroup(o, key, label)),
    shut: (key: string) => setOpen((o) => shutGroup(o, key)),
    shutAll: () => setOpen(new Map()),
  };
}
export type ChainView = ReturnType<typeof useChainView>;

interface Shared {
  filter: ChainFilter;
  /** The levels in use, in order (the shop level is already left out when one shop is chosen at the top of the page). */
  chain: ChainLevel[];
  credit: boolean;
  enabled: boolean;
  view: ChainView;
  /** The sales of a group, listed with the screen's own cards and actions (Record payment). */
  renderSales: (sel: Selection) => ReactNode;
}

/** The groups of one level under a selection. Callers with the same arguments share one request. */
function useLevel(level: ChainLevel, chain: ChainLevel[], filter: ChainFilter, sel: Selection, enabled: boolean) {
  const time = isTimeLevel(level);
  const chosen = Object.fromEntries(Object.entries(sel).filter(([, v]) => v !== undefined)) as Selection;
  const query = useInfiniteQuery({
    queryKey: ['retail', 'sales', 'summary', level, filter, chosen],
    queryFn: ({ pageParam, signal }) =>
      retail.salesSummary({
        ...filter, ...chosen, level, chain, signal,
        ...(time ? { to: typeof pageParam === 'string' ? pageParam : chosen.to } : { offset: typeof pageParam === 'number' ? pageParam : 0 }),
      }),
    initialPageParam: undefined as string | number | undefined,
    getNextPageParam: (last) => (last.has_more ? (time ? last.next_to : last.next_offset) : undefined),
    enabled,
    placeholderData: keepPreviousData,
  });
  // A group can come twice when pages are cut by an order that moves between requests (the others are ordered by their
  // total): the first one stands, so a row is never drawn twice.
  const seen = new Set<string>();
  const items = (query.data?.pages ?? []).flatMap((p) => p.items ?? []).filter((g) => {
    const key = g.key ?? '';
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  return { query, items };
}

/** The way back up from the open group: "All sales > Head Office > 2026", each crumb shutting what is below it. Shared with the Controls page. */
export function GroupTrail({ view, rootLabel }: { view: Pick<ChainView, 'open' | 'shut' | 'shutAll'>; rootLabel: string }) {
  const crumbs = trailOf(view.open);
  if (crumbs.length === 0) return null;
  return (
    <nav aria-label="Where you are" className="rt-group-trail">
      <ol>
        <li><button type="button" className="btn-link" onClick={view.shutAll}>{rootLabel}</button></li>
        {crumbs.map((c, i) => {
          const next = crumbs[i + 1];
          return (
            <li key={c.key}>
              {next ? <button type="button" className="btn-link" onClick={() => view.shut(next.key)}>{c.label}</button> : <span aria-current="page">{c.label}</span>}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

export function SalesChain({ range, rootLabel, empty, ...shared }: Shared & { range: Selection; rootLabel: string; empty?: ReactNode }) {
  return (
    <div data-tour="sales-groups" className="rt-groups">
      <GroupTrail view={shared.view} rootLabel={rootLabel} />
      <LevelList index={0} sel={range} parent="" root empty={empty} {...shared} />
    </div>
  );
}

function LevelList({ index, sel, parent, root, empty, ...shared }: Shared & { index: number; sel: Selection; parent: string; root?: boolean; empty?: ReactNode }) {
  const { filter, chain, credit, enabled } = shared;
  const level = chain[index] ?? 'year';
  const { query, items } = useLevel(level, chain, filter, sel, enabled);
  if (query.isPending && query.fetchStatus !== 'idle') return <p className="loading">Loading</p>;
  if (query.error) {
    return (
      <>
        <Problem error={query.error} />
        <button type="button" onClick={() => void query.refetch()}>Try again</button>
      </>
    );
  }
  if (query.data && items.length === 0) {
    return empty ?? <p className="empty-state">{root ? (credit ? 'No credit sales found.' : 'No sales found.') : emptyChainWords(credit)}</p>;
  }
  return (
    <>
      <ul className="rt-group-list">
        {items.map((g) => (
          <GroupRow key={g.key} index={index} level={level} group={g} sel={sel} parent={parent} {...shared} />
        ))}
      </ul>
      {query.hasNextPage && (
        <button type="button" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
          {query.isFetchingNextPage ? 'Loading' : `Show ${isTimeLevel(level) ? 'earlier' : 'more'} ${LEVEL_PLURALS[level]}`}
        </button>
      )}
    </>
  );
}

/** `lines`: the figures are summed over sale lines, so the number of sales is "in N sales" (those that hold the category or the item). */
function Figures({ group, credit, lines: overLines }: { group: SalesSummaryGroup; credit: boolean; lines: boolean }) {
  const lines = credit ? [] : methodLines(group.by_method);
  // With credit sales counted when the buyer pays (ADR-043) this profit is the profit of what was sold, not of the money received.
  const staff = useContext(StaffContext);
  const profitWord = staff && capabilityOn(staff.me, 'retail', 'credit_on_payment') ? 'profit of sales made' : 'profit';
  return (
    <>
      <span className="rt-group-total">{money(group.total_minor ?? 0)}</span>{' '}
      <span className="rt-group-meta">
        {group.quantity !== undefined && <>{group.quantity} sold, </>}
        {overLines ? 'in ' : ''}{salesWord(group.sales_count ?? 0)}
        {group.profit_minor !== undefined && <>, {profitWord} {money(group.profit_minor)}</>}
        {!credit && (group.voided_count ?? 0) > 0 && <>, {group.voided_count} voided (not counted)</>}
      </span>{' '}
      {lines.length > 0 && <span className="rt-group-methods">{lines.map((m) => `${m.label} ${m.count}, ${money(m.totalMinor)}`).join('. ')}</span>}
    </>
  );
}

const titleOf = (level: ChainLevel, group: SalesSummaryGroup) =>
  level === 'year' || level === 'month' || level === 'day' ? periodTitle(level, group.key ?? '') : (group.label ?? group.key ?? '');

function GroupRow({ index, level, group, sel, parent, ...shared }: Shared & { index: number; level: ChainLevel; group: SalesSummaryGroup; sel: Selection; parent: string }) {
  const { credit, chain, view, renderSales } = shared;
  const key = group.key ?? '';
  const path = pathKey(parent, level, key);
  const title = titleOf(level, group);
  const last = index === chain.length - 1;
  const bodyId = `group-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
  const isOpen = view.open.has(path);
  const here = selectGroup(sel, level, { key, from: group.from, to: group.to });
  return (
    <li className="rt-group">
      {/* No aria-label: the visible words are the name, so the profit, the voided note and the methods are read too. */}
      <button type="button" className="rt-group-head" aria-expanded={isOpen} aria-controls={isOpen ? bodyId : undefined} onClick={() => view.toggle(path, title)}>
        <span className="rt-group-title">{title}</span>{' '}
        <Figures group={group} credit={credit} lines={overLines(level, sel)} />
      </button>
      {isOpen && (
        <div className="rt-group-body" id={bodyId}>
          {last ? (
            <SalesUnder sel={here} renderSales={renderSales} />
          ) : (
            <>
              <LevelList index={index + 1} sel={here} parent={path} {...shared} />
              {offersSales(chain, index) && <SalesButton day={level === 'day'} sel={here} renderSales={renderSales} />}
            </>
          )}
        </div>
      )}
    </li>
  );
}

/** The sales of a group, with a word when they are whole sales that include a category or an item (their totals can be more than the line). */
function SalesUnder({ sel, renderSales }: { sel: Selection; renderSales: (sel: Selection) => ReactNode }) {
  const note = wholeSalesNote(sel);
  return (
    <>
      {note && <p className="rt-group-note">{note}</p>}
      {renderSales(sel)}
    </>
  );
}

/** A group that opens onto more groups can still show its sales: on request, so the groups stay the first thing seen. */
function SalesButton({ day, sel, renderSales }: { day: boolean; sel: Selection; renderSales: (sel: Selection) => ReactNode }) {
  const [shown, setShown] = useState(false);
  const what = day ? 'the sales of this day' : 'the sales here';
  return (
    <div className="rt-day-sales">
      <button type="button" aria-expanded={shown} onClick={() => setShown(!shown)}>{shown ? `Hide ${what}` : `Show ${what}`}</button>
      {shown && <SalesUnder sel={sel} renderSales={renderSales} />}
    </div>
  );
}
