import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type AllBranchesStock, type StockLevel, type StockRow } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { useCrumbTail } from '../../../components/breadcrumbs';
import { GroupControls, GroupedBodies, GroupedCards, sameUnitQty, useCategoryGroups } from './category-groups';
import { lineTotalMinor, milliOf, showQty } from './maths';
import { EmptyState, SkeletonList } from '../../../components/states';
import { BranchRequired, CategoryLabel, Gate, NoStockHere, Problem, money, useBranchView, useIsPhone, useProfitAccess } from './ui';
import { needsOf } from './permissions';

// Stock per branch (FR-RET-03): balances with a negative flag and a search box. The cost column
// exists only for a session holding retail.profit.read: showCost is the screen's choice and the server
// sends no cost field to anyone else.

/** Search and filters of a stock list, so its groups start again when they change (category-groups.tsx). */
type Narrowing = { searching?: boolean; filterKey?: string; pending?: boolean };

/** A category group's subtotal: its quantity when the items share a unit, and what it holds at the selling price. */
function stockSubtotal(rows: { qty?: string; unit?: string; sell_minor?: number }[]): string {
  const value = rows.reduce((sum, r) => sum + lineTotalMinor(r.sell_minor ?? 0, milliOf(r.qty ?? '0')), 0);
  return [sameUnitQty(rows), `${money(value)} at price`].filter(Boolean).join(', ');
}

export function StockTable({ rows, showCost, searching = false, filterKey = '', pending = false }: { rows: StockRow[]; showCost: boolean } & Narrowing) {
  // On a phone the category sits under the item's name, so the table keeps room for the quantity and price.
  const phone = useIsPhone();
  const g = useCategoryGroups({ screen: 'stock', items: rows, categoryOf: (r) => r.category, subtotalOf: stockSubtotal, resetKey: filterKey, searching, pending });
  if (rows.length === 0) return <EmptyState art="noProducts" title="No items found.">Try another name or code, or restock to add items.</EmptyState>;
  const showCategory = !g.active && phone;
  const row = (r: StockRow) => (
    <tr key={r.product_id}>
      <td>{r.description}{showCategory && r.category && <><br /><CategoryLabel category={r.category} /></>}</td>
      {!phone && !g.active && <td>{r.category}</td>}
      <td className="num">
        {showQty(r.qty ?? '0')} {r.unit}
        {r.negative && <div className="rt-flag">Negative</div>}
      </td>
      <td className="num">{money(r.sell_minor ?? 0)}</td>
      {showCost && <td className="num">{r.cost_minor !== undefined ? money(r.cost_minor) : ''}</td>}
    </tr>
  );
  return (
    <>
    <GroupControls state={g} />
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>Item</th>
          {!phone && !g.active && <th>Category</th>}
          <th className="num">In stock</th>
          <th className="num">Price</th>
          {showCost && <th className="num">Cost</th>}
        </tr>
      </thead>
      {g.active ? <GroupedBodies state={g} colSpan={3 + (showCost ? 1 : 0)} renderRow={row} /> : <tbody>{rows.map(row)}</tbody>}
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

const branchName = (b: { code?: string; name?: string }) => branchLabel(b);

/** All branches on a wide screen: a column per branch and a total, the negative flag on each cell. */
export function AllBranchesTable({ data, showCost: mayCost, searching = false, filterKey = '', pending = false }: { data: AllBranchesStock; showCost: boolean } & Narrowing) {
  const showCost = mayCost && data.items.some((r) => r.cost_minor !== undefined);
  const g = useCategoryGroups({ screen: 'stock', items: data.items, categoryOf: (r) => r.category, subtotalOf: totalsSubtotal, resetKey: filterKey, searching, pending });
  if (data.items.length === 0) return <EmptyState art="noProducts" title="No items found.">Try another name or code, or restock to add items.</EmptyState>;
  const row = (r: AllBranchesStock['items'][number]) => (
    <tr key={r.product_id}>
      <td>{r.description}{!g.active && <><br /><CategoryLabel category={r.category} /></>}</td>
      <td className="num"><strong><Qty qty={r.total_qty ?? '0'} unit={r.unit} negative={r.negative} /></strong></td>
      {(r.balances ?? []).map((c) => (
        <td key={c.branch_id} className="num"><Qty qty={c.qty ?? '0'} negative={c.negative} /></td>
      ))}
      <td className="num">{money(r.sell_minor ?? 0)}</td>
      {showCost && <td className="num">{r.cost_minor !== undefined ? money(r.cost_minor) : ''}</td>}
    </tr>
  );
  return (
    <>
    <GroupControls state={g} />
    <div className="table-wrap" tabIndex={0}><table className="stock-matrix">
      <thead>
        <tr>
          <th>Item</th>
          <th className="num">Total</th>
          {data.branches.map((b) => <th key={b.id} className="num">{branchName(b)}</th>)}
          <th className="num">Price</th>
          {showCost && <th className="num">Cost</th>}
        </tr>
      </thead>
      {g.active ? <GroupedBodies state={g} colSpan={3 + data.branches.length + (showCost ? 1 : 0)} renderRow={row} /> : <tbody>{data.items.map(row)}</tbody>}
    </table></div>
    </>
  );
}

/** All branches on a phone: one card per product with the total, and a button that opens its branches. */
export function AllBranchesList({ data, showCost, searching = false, filterKey = '', pending = false }: { data: AllBranchesStock; showCost: boolean } & Narrowing) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const g = useCategoryGroups({ screen: 'stock', items: data.items, categoryOf: (r) => r.category, subtotalOf: totalsSubtotal, resetKey: filterKey, searching, pending });
  if (data.items.length === 0) return <EmptyState art="noProducts" title="No items found.">Try another name or code, or restock to add items.</EmptyState>;
  const names = Object.fromEntries(data.branches.map((b) => [b.id ?? '', branchName(b)]));
  const card = (r: AllBranchesStock['items'][number]) => {
        const id = r.product_id ?? '';
        const expanded = open[id] === true;
        return (
          <li key={id} className="rt-card">
            <div className="rt-row">
              <span>
                <strong>{r.description}</strong>
                {!g.active && <><br /><CategoryLabel category={r.category} /></>}
              </span>
              <span className="num"><strong>Total <Qty qty={r.total_qty ?? '0'} unit={r.unit} negative={r.negative} /></strong></span>
            </div>
            <p className="hint">{money(r.sell_minor ?? 0)} each{showCost && r.cost_minor !== undefined ? `, cost ${money(r.cost_minor)}` : ''}</p>
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
      <GroupControls state={g} />
      {g.active ? <GroupedCards state={g} renderItem={card} /> : <ul style={{ listStyle: 'none', padding: 0 }}>{data.items.map(card)}</ul>}
    </>
  );
}

const LEVELS: { value: StockLevel | ''; label: string }[] = [
  { value: '', label: 'All items' },
  { value: 'out', label: 'Out of stock' },
  { value: 'low', label: 'Low stock' },
];

/** Tabs for the stock lists: everything, out of stock (zero or less) or low stock (5 or fewer, out included). */
export function StockLevelFilter({ level, onChange }: { level: StockLevel | ''; onChange: (level: StockLevel | '') => void }) {
  return (
    <>
      <div className="cluster filters" role="group" aria-label="Show">
        {LEVELS.map((l) => (
          <button key={l.value} type="button" className="btn-sm" aria-pressed={level === l.value} onClick={() => onChange(l.value)}>
            {l.label}
          </button>
        ))}
      </div>
      {level === 'out' && <p className="hint">Items with none left, or less than none.</p>}
      {level === 'low' && <p className="hint">Items with 5 or fewer left, including those that are out of stock.</p>}
    </>
  );
}

export function StockPage() {
  const { all, branchId, branchName: name } = useBranchView();
  const canProfit = useProfitAccess();
  const phone = useIsPhone();
  const search = useLiveSearch();
  const query = search.term;
  const [negativeOnly, setNegativeOnly] = useState(false);
  const [categoryId, setCategoryId] = useState('');
  const [level, setLevel] = useState<StockLevel | ''>('');
  const categories = useQuery({ queryKey: ['retail', 'categories'], queryFn: () => retail.listCategories() });
  const one = useQuery({
    queryKey: ['retail', 'stock', branchId, query, categoryId, negativeOnly, level],
    queryFn: ({ signal }) => retail.listStock({ branchId: branchId ?? '', query, categoryId, negativeOnly, level: level || undefined, signal }),
    enabled: !all && branchId !== null,
    placeholderData: keepPreviousData,
  });
  const many = useQuery({
    queryKey: ['retail', 'stock', 'all-branches', query, categoryId, negativeOnly, level],
    queryFn: ({ signal }) => retail.listStockAllBranches({ query, categoryId, negativeOnly, level: level || undefined, signal }),
    enabled: all,
    placeholderData: keepPreviousData,
  });
  const result = all ? many : one;
  const finding = result.isPlaceholderData || (result.isFetching && result.fetchStatus !== 'idle');
  const shown = all ? many.data?.items.length : one.data?.length;
  // The whole-business view is the stock "by location": a last crumb says so (components/breadcrumbs.tsx).
  useCrumbTail(all ? 'By location' : undefined);
  const narrowing = { pending: result.isPlaceholderData, searching: query !== '', filterKey: [all ? 'all' : branchId, query, categoryId, negativeOnly, level].join('|') };
  return (
    <Gate screen="stock" title="Stock">
      <p className="branch-line">Branch: <strong>{all ? 'All branches' : name}</strong></p>
      {!all && branchId !== null && <NoStockHere branchId={branchId} />}
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
      {!all && branchId === null && <BranchRequired permissions={needsOf('stock')} />}
      {result.isPending && (all || branchId !== null) && <SkeletonList label="Loading stock" />}
      <Problem error={result.error} />
      {shown === 0 && query && <NoResults term={query} onClear={search.clear} noun="items" />}
      <SearchResults fetching={finding}>
        {!all && one.data && <StockTable rows={one.data} showCost={canProfit} {...narrowing} />}
        {all && many.data && (phone ? <AllBranchesList data={many.data} showCost={canProfit} {...narrowing} /> : <AllBranchesTable data={many.data} showCost={canProfit} {...narrowing} />)}
      </SearchResults>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stock')({ component: StockPage });
