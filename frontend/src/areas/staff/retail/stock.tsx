import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { Fragment, useState } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { Segmented } from '../../../app/ui/Segmented';
import { retail, type AllBranchesStock, type StockLevel, type StockRow } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { useCrumbTail } from '../../../components/breadcrumbs';
import { GroupControls, GroupedBodies, GroupedCards, sameUnitQty, useCategoryGroups } from './category-groups';
import { lineTotalMinor, milliOf, showQty } from './maths';
import { EmptyState, SkeletonList } from '../../../components/states';
import { BranchRequired, CategoryLabel, Gate, NoStockHere, Problem, money, useBranchView, useIsPhone, useProfitAccess } from './ui';
import { CorrectItem, mayCorrect } from './stock-correct';
import { useStaff } from '../context';
import { needsOf } from './permissions';
import { ListGroupBy, ViewSwitch, useListView } from './list-chain';
import { STOCK_NAMES, STOCK_ORDER, listUser, withoutShop, type ListSelection } from './list-chain-state';
import { GroupedStock, type StockFilter } from './stock-grouped';
import { keptWords } from './orders-state';

// Stock per branch (FR-RET-03): balances with a negative flag and a search box. The cost column
// exists only for a session holding retail.profit.read: showCost is the screen's choice and the server
// sends no cost field to anyone else.

/** Search and filters of a stock list, so its groups start again when they change (category-groups.tsx). */
type Narrowing = { searching?: boolean; filterKey?: string; pending?: boolean; /** Under a group of the grouped view the items are one plain list (issue #350). */ flat?: boolean };

/** Set by the stock page for one branch when the session may correct something there: each row then offers Correct (issue #351). */
type Correctable = { branchId: string };

/** A category group's subtotal: its quantity when the items share a unit, and what it holds at the selling price. */
function stockSubtotal(rows: { qty?: string; unit?: string; sell_minor?: number }[]): string {
  // ADR-055: no price came back (a storekeeper), so no value is shown either.
  if (rows.every((r) => r.sell_minor === undefined)) return sameUnitQty(rows) ?? '';
  const value = rows.reduce((sum, r) => sum + lineTotalMinor(r.sell_minor ?? 0, milliOf(r.qty ?? '0')), 0);
  return [sameUnitQty(rows), `${money(value)} at price`].filter(Boolean).join(', ');
}

export function StockTable({ rows, showCost, searching = false, filterKey = '', pending = false, flat = false, correctable }: { rows: StockRow[]; showCost: boolean; correctable?: Correctable } & Narrowing) {
  // On a phone the category sits under the item's name, so the table keeps room for the quantity and price.
  const phone = useIsPhone();
  const [corrected, setCorrected] = useState<string | null>(null);
  const g = useCategoryGroups({ screen: 'stock', items: rows, categoryOf: (r) => r.category, subtotalOf: stockSubtotal, resetKey: filterKey, searching, pending });
  const grouping = g.active && !flat;
  if (rows.length === 0) return <EmptyState art="noProducts" title="No items found.">Try another name or code, or buy stock to add items.</EmptyState>;
  const showCategory = !grouping && phone;
  const columns = 3 + (!phone && !grouping ? 1 : 0) + (showCost ? 1 : 0);
  const row = (r: StockRow) => (
    <Fragment key={r.product_id}>
      <tr>
        <td>
          {r.description}{showCategory && r.category && <><br /><CategoryLabel category={r.category} /></>}
          {correctable && (
            <div>
              <button type="button" className="btn-sm" aria-expanded={corrected === r.product_id} aria-label={`Correct ${r.description}`} onClick={() => setCorrected(corrected === r.product_id ? null : (r.product_id ?? null))}>Correct</button>
            </div>
          )}
        </td>
        {!phone && !grouping && <td>{r.category}</td>}
        <td className="num">
          {showQty(r.qty ?? '0')} {r.unit}
          {r.negative && <div className="rt-flag">Negative</div>}
          {keptWords(r.qty, r.reserved_qty) && <div className="hint">{keptWords(r.qty, r.reserved_qty)}</div>}
        </td>
        {showCost && <td className="num">{r.cost_minor !== undefined ? money(r.cost_minor) : ''}</td>}
        <td className="num">{r.sell_minor !== undefined ? money(r.sell_minor) : ''}</td>
      </tr>
      {correctable && corrected === r.product_id && (
        <tr><td colSpan={columns}><CorrectItem row={r} branchId={correctable.branchId} onClose={() => setCorrected(null)} /></td></tr>
      )}
    </Fragment>
  );
  return (
    <>
    {!flat && <GroupControls state={g} />}
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>Item</th>
          {!phone && !grouping && <th>Category</th>}
          <th className="num">In stock</th>
          {showCost && <th className="num">Average cost</th>}
          <th className="num">Price</th>
        </tr>
      </thead>
      {grouping ? <GroupedBodies state={g} colSpan={3 + (showCost ? 1 : 0)} renderRow={row} /> : <tbody>{rows.map(row)}</tbody>}
    </table></div>
    </>
  );
}

/** One quantity with its unit and the negative flag in words. */
function Qty({ qty, unit, negative }: { qty: string; unit?: string; negative?: boolean }) {
  return (
    <>
      {showQty(qty)}{unit ? ` ${unit}` : ''}
      {negative && <div className="rt-flag">Negative</div>}
    </>
  );
}

/** The subtotal of a group of all-branches rows: the total quantity and what it holds at the selling price. */
const totalsSubtotal = (rows: AllBranchesStock['items']) => stockSubtotal(rows.map((r) => ({ qty: r.total_qty, unit: r.unit, sell_minor: r.sell_minor })));

const branchName = (b: { code?: string; name?: string; closed?: boolean }) => `${branchLabel(b)}${b.closed ? ' (closed)' : ''}`;

/** All branches on a wide screen: a column per branch and a total, the negative flag on each cell. */
export function AllBranchesTable({ data, showCost: mayCost, searching = false, filterKey = '', pending = false, flat = false }: { data: AllBranchesStock; showCost: boolean } & Narrowing) {
  const showCost = mayCost && data.items.some((r) => r.cost_minor !== undefined);
  const g = useCategoryGroups({ screen: 'stock', items: data.items, categoryOf: (r) => r.category, subtotalOf: totalsSubtotal, resetKey: filterKey, searching, pending });
  const grouping = g.active && !flat;
  if (data.items.length === 0) return <EmptyState art="noProducts" title="No items found.">Try another name or code, or buy stock to add items.</EmptyState>;
  const row = (r: AllBranchesStock['items'][number]) => (
    <tr key={r.product_id}>
      <td>{r.description}{!grouping && <><br /><CategoryLabel category={r.category} /></>}</td>
      <td className="num"><strong><Qty qty={r.total_qty ?? '0'} unit={r.unit} negative={r.negative} /></strong></td>
      {(r.balances ?? []).map((c) => (
        <td key={c.branch_id} className="num"><Qty qty={c.qty ?? '0'} negative={c.negative} /></td>
      ))}
      {showCost && <td className="num">{r.cost_minor !== undefined ? money(r.cost_minor) : ''}</td>}
      <td className="num">{r.sell_minor !== undefined ? money(r.sell_minor) : ''}</td>
    </tr>
  );
  return (
    <>
    {!flat && <GroupControls state={g} />}
    <div className="table-wrap" tabIndex={0}><table className="stock-matrix">
      <thead>
        <tr>
          <th>Item</th>
          <th className="num">Total</th>
          {data.branches.map((b) => <th key={b.id} className="num">{branchName(b)}</th>)}
          {showCost && <th className="num">Average cost</th>}
          <th className="num">Price</th>
        </tr>
      </thead>
      {grouping ? <GroupedBodies state={g} colSpan={3 + data.branches.length + (showCost ? 1 : 0)} renderRow={row} /> : <tbody>{data.items.map(row)}</tbody>}
    </table></div>
    </>
  );
}

/** All branches on a phone: one card per product with the total, and a button that opens its branches. */
export function AllBranchesList({ data, showCost, searching = false, filterKey = '', pending = false, flat = false }: { data: AllBranchesStock; showCost: boolean } & Narrowing) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const g = useCategoryGroups({ screen: 'stock', items: data.items, categoryOf: (r) => r.category, subtotalOf: totalsSubtotal, resetKey: filterKey, searching, pending });
  const grouping = g.active && !flat;
  if (data.items.length === 0) return <EmptyState art="noProducts" title="No items found.">Try another name or code, or buy stock to add items.</EmptyState>;
  const names = Object.fromEntries(data.branches.map((b) => [b.id ?? '', branchName(b)]));
  const card = (r: AllBranchesStock['items'][number]) => {
        const id = r.product_id ?? '';
        const expanded = open[id] === true;
        return (
          <li key={id} className="rt-card">
            <div className="rt-row">
              <span>
                <strong>{r.description}</strong>
                {!grouping && <><br /><CategoryLabel category={r.category} /></>}
              </span>
              <span className="num"><strong>Total <Qty qty={r.total_qty ?? '0'} unit={r.unit} negative={r.negative} /></strong></span>
            </div>
            {r.sell_minor !== undefined && <p className="hint">{showCost && r.cost_minor !== undefined ? `Average cost ${money(r.cost_minor)}, price` : 'Price'} {money(r.sell_minor)} each</p>}
            <button type="button" className="btn-sm" aria-expanded={expanded} onClick={() => setOpen({ ...open, [id]: !expanded })}>
              {expanded ? 'Hide branches' : 'Show branches'}
            </button>
            {expanded && (
              <div>
                {(r.balances ?? []).map((c) => (
                  <div key={c.branch_id} className="rt-row">
                    <span>{names[c.branch_id ?? '']}</span>
                    <span className="num"><Qty qty={c.qty ?? '0'} unit={r.unit} negative={c.negative} /></span>
                  </div>
                ))}
              </div>
            )}
          </li>
        );
  };
  return (
    <>
      {!flat && <GroupControls state={g} />}
      {grouping ? <GroupedCards state={g} renderItem={card} /> : <ul style={{ listStyle: 'none', padding: 0 }}>{data.items.map(card)}</ul>}
    </>
  );
}

const LEVELS: { value: StockLevel | ''; label: string }[] = [
  { value: '', label: 'All items' },
  { value: 'out', label: 'Out of stock' },
  { value: 'low', label: 'Low stock' },
];

/**
 * Tabs for the stock lists: everything, out of stock (zero or less) or low stock (at or below the item's minimum, else 5 or fewer,
 * out included); out and low keep only items the shop has stocked (issue #490).
 */
export function StockLevelFilter({ level, onChange }: { level: StockLevel | ''; onChange: (level: StockLevel | '') => void }) {
  return (
    <>
      <Segmented label="Show" options={LEVELS} value={level} onChange={onChange} />
      {level === 'out' && <p className="hint">Items you have stocked with none left, or less than none.</p>}
      {level === 'low' && <p className="hint">Items you have stocked that are at or below their minimum, or 5 or fewer without one, including those out of stock.</p>}
    </>
  );
}

/** The items of an opened group of the grouped view (issue #350): the same tables as the flat list, filtered by what was opened, so Correct works here. */
function StockRows({ sel, filter, all, branchId }: { sel: ListSelection; filter: StockFilter; all: boolean; branchId: string | null }) {
  const { me } = useStaff();
  const canProfit = useProfitAccess();
  const phone = useIsPhone();
  const branch = sel.branchId ?? (all ? null : branchId);
  const categoryId = sel.categoryId ?? filter.categoryId;
  const level = filter.level || undefined;
  const one = useQuery({
    queryKey: ['retail', 'stock', branch, filter.query, categoryId, filter.negativeOnly, level ?? '', filter.includeInactive],
    queryFn: ({ signal }) => retail.listStock({ branchId: branch ?? '', query: filter.query, categoryId, negativeOnly: filter.negativeOnly, level, includeInactive: filter.includeInactive, signal }),
    enabled: branch !== null,
  });
  const many = useQuery({
    queryKey: ['retail', 'stock', 'all-branches', filter.query, categoryId, filter.negativeOnly, level ?? '', filter.includeClosed, filter.includeInactive],
    queryFn: ({ signal }) => retail.listStockAllBranches({ query: filter.query, categoryId, negativeOnly: filter.negativeOnly, level, includeClosed: filter.includeClosed, includeInactive: filter.includeInactive, signal }),
    enabled: branch === null,
  });
  const result = branch === null ? many : one;
  const key = [branch ?? 'all', filter.query, categoryId, filter.negativeOnly, level ?? ''].join('|');
  return (
    <>
      {result.isPending && <SkeletonList label="Loading stock" />}
      <Problem error={result.error} />
      {branch !== null && one.data && <StockTable rows={one.data} showCost={canProfit} flat filterKey={key} correctable={mayCorrect(me, branch) ? { branchId: branch } : undefined} />}
      {branch === null && many.data && (phone ? <AllBranchesList data={many.data} showCost={canProfit} flat filterKey={key} /> : <AllBranchesTable data={many.data} showCost={canProfit} flat filterKey={key} />)}
    </>
  );
}

export function StockPage() {
  const { me } = useStaff();
  const { all, branchId, branchName: name } = useBranchView();
  const canProfit = useProfitAccess();
  const phone = useIsPhone();
  const list = useListView(listUser(me), 'stock', STOCK_ORDER);
  const grouped = list.view === 'grouped';
  const search = useLiveSearch();
  const query = search.term;
  const [negativeOnly, setNegativeOnly] = useState(false);
  const [categoryId, setCategoryId] = useState('');
  const [level, setLevel] = useState<StockLevel | ''>('');
  // Closed branches that still hold stock are left out of the whole-business list unless asked for (#329).
  const [showClosed, setShowClosed] = useState(false);
  // An inactive item (issue #342) leaves the list unless asked for; its history and the reports keep it.
  const [showInactive, setShowInactive] = useState(false);
  const categories = useQuery({ queryKey: ['retail', 'categories'], queryFn: () => retail.listCategories() });
  const one = useQuery({
    queryKey: ['retail', 'stock', branchId, query, categoryId, negativeOnly, level, showInactive],
    queryFn: ({ signal }) => retail.listStock({ branchId: branchId ?? '', query, categoryId, negativeOnly, level: level || undefined, includeInactive: showInactive, signal }),
    enabled: !all && branchId !== null && !grouped,
    placeholderData: keepPreviousData,
  });
  const many = useQuery({
    queryKey: ['retail', 'stock', 'all-branches', query, categoryId, negativeOnly, level, showClosed, showInactive],
    queryFn: ({ signal }) => retail.listStockAllBranches({ query, categoryId, negativeOnly, level: level || undefined, includeClosed: showClosed, includeInactive: showInactive, signal }),
    enabled: all && !grouped,
    placeholderData: keepPreviousData,
  });
  const result = all ? many : one;
  const finding = result.isPlaceholderData || (result.isFetching && result.fetchStatus !== 'idle');
  const shown = all ? many.data?.items.length : one.data?.length;
  // The whole-business view is the stock "by location": a last crumb says so (components/breadcrumbs.tsx).
  useCrumbTail(all ? 'By location' : undefined);
  const stockFilter: StockFilter = { query, categoryId, negativeOnly, level, includeClosed: showClosed, includeInactive: showInactive };
  const shopChosen = !all && branchId !== null;
  const narrowing = { pending: result.isPlaceholderData, searching: query !== '', filterKey: [all ? 'all' : branchId, query, categoryId, negativeOnly, level, showInactive].join('|') };
  return (
    <Gate screen="stock" title="Stock">
      <p className="branch-line">Branch: <strong>{all ? 'All branches' : name}</strong></p>
      {!all && branchId !== null && <NoStockHere branchId={branchId} />}
      <ViewSwitch view={list.view} onChange={list.setView} />
      <StockLevelFilter level={level} onChange={setLevel} />
      <div data-tour="stock-search">
        <SearchBox id="stock-search" label="Search by name, code or category" search={search} fetching={finding} count={shown} />
      </div>
      <label htmlFor="stock-category">Category</label>
      <select id="stock-category" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
        <option value="">All categories</option>
        {(categories.data ?? []).map((c) => (
          <option key={c.id} value={c.id}>{c.name}</option>
        ))}
      </select>
      <label style={{ fontWeight: 400 }}>
        <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={negativeOnly} onChange={(e) => setNegativeOnly(e.target.checked)} />
        Show only negative stock
      </label>
      <label style={{ fontWeight: 400 }}>
        <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} />
        Show inactive items
      </label>
      {all && grouped && (
        <label style={{ fontWeight: 400 }}>
          <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} />
          Include closed shops that still hold stock
        </label>
      )}
      {all && !grouped && (showClosed || (many.data?.closed_branches ?? 0) > 0) && (
        <label style={{ fontWeight: 400 }}>
          <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} />
          {showClosed ? 'Showing closed branches' : `${many.data?.closed_branches ?? 0} closed ${many.data?.closed_branches === 1 ? 'shop' : 'shops'} with stock hidden. Show them`}
        </label>
      )}
      {!all && branchId === null && <BranchRequired permissions={needsOf('stock')} />}
      {grouped && (all || branchId !== null) && (
        <>
          <ListGroupBy chain={list.chain} order={STOCK_ORDER} names={STOCK_NAMES} onChange={list.setChain} />
          <div data-tour="stock-list">
            <GroupedStock
              view={list} filter={stockFilter} chain={withoutShop(list.chain, shopChosen)} enabled range={shopChosen ? { branchId } : {}}
              renderRows={(sel) => <StockRows sel={sel} filter={stockFilter} all={all} branchId={branchId} />}
            />
          </div>
        </>
      )}
      {!grouped && result.isPending && (all || branchId !== null) && <SkeletonList label="Loading stock" />}
      {!grouped && <Problem error={result.error} />}
      {!grouped && shown === 0 && query && <NoResults term={query} onClear={search.clear} noun="items" />}
      {!grouped && (
      <div data-tour="stock-list">
      <SearchResults fetching={finding}>
        {!all && one.data && <StockTable rows={one.data} showCost={canProfit} correctable={mayCorrect(me, branchId) && branchId !== null ? { branchId } : undefined} {...narrowing} />}
        {all && many.data && (phone ? <AllBranchesList data={many.data} showCost={canProfit} {...narrowing} /> : <AllBranchesTable data={many.data} showCost={canProfit} {...narrowing} />)}
      </SearchResults>
      </div>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stock')({ component: StockPage });
