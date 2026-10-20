import { retail, type StockChainLevel, type StockLevel, type StockSummaryGroup } from '../../../api/retail';
import { ListChain, type ListViewState, type TreeKind } from './list-chain';
import { STOCK_PLURALS, itemsWord, type ListSelection } from './list-chain-state';
import { money } from './ui';
import type { ReactNode } from 'react';

// The stock list grouped by shop and category (issue #350): how many items each group holds and what they are worth, summed by the
// server (GET /retail/stock/summary) with the search, category, negative and stock-level filters of the list. The items of an opened
// group are the screen's own stock table (stock.tsx), so Correct on a stock row works inside the grouped view.

/** The stock list's own filters, passed to every level. */
export interface StockFilter {
  query: string;
  categoryId: string;
  negativeOnly: boolean;
  level: StockLevel | '';
  includeClosed: boolean;
  includeInactive: boolean;
}

const kindFor = (f: StockFilter): TreeKind<StockChainLevel, StockSummaryGroup> => ({
  name: 'stock',
  plurals: STOCK_PLURALS,
  fetch: (level, chain, sel, page, signal) =>
    retail.stockSummary({
      level, chain, signal, branchId: sel.branchId, categoryId: sel.categoryId ?? (f.categoryId || undefined), query: f.query, negativeOnly: f.negativeOnly,
      stockLevel: f.level || undefined, includeClosed: f.includeClosed, includeInactive: f.includeInactive, offset: page.offset,
    }),
  title: (_level, g) => g.label ?? g.key ?? '',
  figures: (_level, g) => (
    <>
      <span className="rt-group-total">{money(g.value_minor ?? 0)}</span>{' '}
      <span className="rt-group-meta">
        at price, {itemsWord(g.items_count ?? 0)}
        {g.cost_minor !== undefined && <>, {money(g.cost_minor)} at cost</>}
        {(g.negative_count ?? 0) > 0 && <>, {g.negative_count} negative</>}
      </span>
    </>
  ),
  nothing: 'No stock here.',
});

export function GroupedStock({ view, filter, chain, range, enabled, renderRows }: {
  view: ListViewState<StockChainLevel>;
  filter: StockFilter;
  chain: StockChainLevel[];
  range: ListSelection;
  enabled: boolean;
  renderRows: (sel: ListSelection) => ReactNode;
}) {
  return <ListChain<StockChainLevel, StockSummaryGroup> kind={kindFor(filter)} filterKey={filter} chain={chain} enabled={enabled} view={view} rootLabel="Stock" range={range} renderRows={renderRows} />;
}
