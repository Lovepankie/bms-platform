import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { retail, type SalesSummaryQuery } from '../../../api/retail';
import type { DayRange, Level } from './sales-grouped-state';

// The top level of the sales lists (issue #328): the search box above the groups shows how many sales the years hold.
// The groups themselves are in sales-chain.tsx (issue #341).

/** The list's own filters, passed on to every level. */
export type GroupedFilter = Omit<SalesSummaryQuery, 'level' | 'from' | 'to' | 'signal'>;

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
