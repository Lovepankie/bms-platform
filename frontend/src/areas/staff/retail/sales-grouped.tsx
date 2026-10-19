import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { retail, type SalesSummaryGroup, type SalesSummaryQuery } from '../../../api/retail';
import { CHILD_LEVEL, childRange, defaultOpen, emptyWords, groupKey, methodLines, periodTitle, salesWord, shutGroup, toggleGroup, type DayRange, type Level } from './sales-grouped-state';
import { Problem, money } from './ui';

// The All sales and Credit sales lists grouped by year, month and day (issue #328). Every header is a figure the
// server summed for that period, so opening a month or a day never changes the figures above it. A day loads its
// sales only when it is opened (renderDay), with the list the screen already has. What is open lives in the screen
// (useOpenGroups), so coming back from a sale finds the same groups open.

/** The list's own filters, passed on to every level. */
export type GroupedFilter = Omit<SalesSummaryQuery, 'level' | 'from' | 'to' | 'signal'>;

/** Which groups are open: this year and this month to start with, so the days of this month are listed. */
export function useOpenGroups(today: string) {
  const [open, setOpen] = useState(() => defaultOpen(today));
  return { open, toggle: (key: string) => setOpen((o) => toggleGroup(o, key)), shut: (key: string) => setOpen((o) => shutGroup(o, key)) };
}
export type OpenGroups = ReturnType<typeof useOpenGroups>;

interface Shared {
  filter: GroupedFilter;
  credit: boolean;
  enabled: boolean;
  groups: OpenGroups;
  renderDay: (day: string) => ReactNode;
}

interface Ancestor {
  label: string;
  key: string;
}

const LEVEL_WORDS: Record<Level, string> = { year: 'years', month: 'months', day: 'days' };

export function SalesGrouped({ filter, range, credit, enabled, groups, renderDay, rootLabel, empty }: Shared & { range: DayRange; rootLabel: string; empty?: ReactNode }) {
  return (
    <div data-tour="sales-groups" className="rt-groups">
      <GroupList level="year" range={range} trail={[{ label: rootLabel, key: '' }]} filter={filter} credit={credit} enabled={enabled} groups={groups} renderDay={renderDay} empty={empty} />
    </div>
  );
}

/** The groups of one level in a range, newest first, older ones on request. Callers with the same arguments share one request. */
function useGroupLevel(level: Level, filter: GroupedFilter, range: DayRange, enabled: boolean) {
  const query = useInfiniteQuery({
    queryKey: ['retail', 'sales', 'summary', level, filter, range],
    queryFn: ({ pageParam, signal }) => retail.salesSummary({ ...filter, level, from: range.from, to: pageParam ?? range.to, signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.has_more ? last.next_to : undefined),
    enabled,
    placeholderData: keepPreviousData,
  });
  return { query, items: (query.data?.pages ?? []).flatMap((p) => p.items ?? []) };
}

/** What the search box above the groups shows: whether the top level is on its way, and how many sales it holds. */
export function useRootFigures(filter: GroupedFilter, range: DayRange, enabled: boolean) {
  const { query, items } = useGroupLevel('year', filter, range, enabled);
  return {
    fetching: query.isPlaceholderData || (query.isFetching && !query.isFetchingNextPage && query.fetchStatus !== 'idle'),
    count: query.data ? items.reduce((n, g) => n + (g.sales_count ?? 0), 0) : undefined,
    more: query.hasNextPage,
  };
}

function GroupList({ level, range, trail, empty, ...shared }: Shared & { level: Level; range: DayRange; trail: Ancestor[]; empty?: ReactNode }) {
  const { filter, credit, enabled } = shared;
  const { query, items } = useGroupLevel(level, filter, range, enabled);
  if (query.isPending && query.fetchStatus !== 'idle') return <p className="loading">Loading</p>;
  if (query.error) {
    return (
      <>
        <Problem error={query.error} />
        <button type="button" onClick={() => void query.refetch()}>Try again</button>
      </>
    );
  }
  if (query.data && items.length === 0) return empty ?? <p className="empty-state">{level === 'year' ? (credit ? 'No credit sales found.' : 'No sales found.') : emptyWords(level, credit)}</p>;
  return (
    <>
      <ul className="rt-group-list">
        {items.map((g) => (
          <GroupRow key={g.period} level={level} group={g} range={range} trail={trail} {...shared} />
        ))}
      </ul>
      {query.hasNextPage && (
        <button type="button" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
          {query.isFetchingNextPage ? 'Loading' : `Show earlier ${LEVEL_WORDS[level]}`}
        </button>
      )}
    </>
  );
}

function GroupRow({ level, group, range, trail, ...shared }: Shared & { level: Level; group: SalesSummaryGroup; range: DayRange; trail: Ancestor[] }) {
  const { credit, groups } = shared;
  const period = group.period ?? '';
  const key = groupKey(level, period);
  const bodyId = `group-${key.replace(':', '-')}`;
  const isOpen = groups.open.has(key);
  const title = periodTitle(level, period);
  const here = [...trail, { label: title, key }];
  const lines = credit ? [] : methodLines(group.by_method);
  const count = group.sales_count ?? 0;
  return (
    <li className="rt-group">
      {/* No aria-label: the visible words are the name, so the profit, the voided note and the methods are read too. */}
      <button type="button" className="rt-group-head" aria-expanded={isOpen} aria-controls={isOpen ? bodyId : undefined} onClick={() => groups.toggle(key)}>
        <span className="rt-group-title">{title}</span>{' '}
        <span className="rt-group-total">{money(group.total_minor ?? 0)}</span>{' '}
        <span className="rt-group-meta">
          {salesWord(count)}
          {group.profit_minor !== undefined && <>, profit {money(group.profit_minor)}</>}
          {!credit && (group.voided_count ?? 0) > 0 && <>, {group.voided_count} voided (not counted)</>}
        </span>{' '}
        {lines.length > 0 && (
          <span className="rt-group-methods">
            {lines.map((m) => `${m.label} ${m.count}, ${money(m.totalMinor)}`).join('. ')}
          </span>
        )}
      </button>
      {isOpen && (
        <div className="rt-group-body" id={bodyId}>
          {level === 'day' ? (
            <>
              <DayTrail items={here} onShut={groups.shut} />
              {shared.renderDay(period)}
            </>
          ) : (
            <GroupList level={CHILD_LEVEL[level]} range={childRange(group, range)} trail={here} {...shared} />
          )}
        </div>
      )}
    </li>
  );
}

/** Where an open day sits (All sales, year, month, day): tapping a place shuts what is inside it and shows that place again. */
function DayTrail({ items, onShut }: { items: Ancestor[]; onShut: (key: string) => void }) {
  return (
    <nav aria-label="Where you are" className="rt-group-trail">
      <ol>
        {items.map((item, i) => {
          const next = items[i + 1];
          return (
            <li key={item.key || 'root'}>
              {next ? <button type="button" className="btn-link" onClick={() => onShut(next.key)}>{item.label}</button> : <span aria-current="page">{item.label}</span>}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
