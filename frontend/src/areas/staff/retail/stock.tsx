import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type AllBranchesStock, type StockLevel, type StockRow } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { showQty } from './maths';
import { BranchRequired, CategoryLabel, Gate, NoStockHere, Problem, money, useBranchView, useIsPhone, useProfitAccess } from './ui';

// Stock per branch (FR-RET-03): balances with a negative flag and a search box. The cost column
// exists only for a session holding retail.profit.read: showCost is the screen's choice and the server
// sends no cost field to anyone else.

export function StockTable({ rows, showCost }: { rows: StockRow[]; showCost: boolean }) {
  // On a phone the category sits under the item's name, so the table keeps room for the quantity and price.
  const phone = useIsPhone();
  if (rows.length === 0) return <p className="empty-state">No items found.</p>;
  return (
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>Item</th>
          {!phone && <th>Category</th>}
          <th className="num">In stock</th>
          <th className="num">Price</th>
          {showCost && <th className="num">Cost</th>}
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.product_id}>
            <td>{r.description}{phone && r.category && <><br /><CategoryLabel category={r.category} /></>}</td>
            {!phone && <td>{r.category}</td>}
            <td className="num">
              {showQty(r.qty ?? '0')} {r.unit}
              {r.negative && <div className="rt-flag">Negative</div>}
            </td>
            <td className="num">{money(r.sell_minor ?? 0)}</td>
            {showCost && <td className="num">{r.cost_minor !== undefined ? money(r.cost_minor) : ''}</td>}
          </tr>
        ))}
      </tbody>
    </table></div>
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

const branchName = (b: { code?: string; name?: string }) => branchLabel(b);

/** All branches on a wide screen: a column per branch and a total, the negative flag on each cell. */
export function AllBranchesTable({ data, showCost: mayCost }: { data: AllBranchesStock; showCost: boolean }) {
  const showCost = mayCost && data.items.some((r) => r.cost_minor !== undefined);
  if (data.items.length === 0) return <p className="empty-state">No items found.</p>;
  return (
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
      <tbody>
        {data.items.map((r) => (
          <tr key={r.product_id}>
            <td>{r.description}<br /><CategoryLabel category={r.category} /></td>
            <td className="num"><strong><Qty qty={r.total_qty ?? '0'} unit={r.unit} negative={r.negative} /></strong></td>
            {(r.balances ?? []).map((c) => (
              <td key={c.branch_id} className="num"><Qty qty={c.qty ?? '0'} negative={c.negative} /></td>
            ))}
            <td className="num">{money(r.sell_minor ?? 0)}</td>
            {showCost && <td className="num">{r.cost_minor !== undefined ? money(r.cost_minor) : ''}</td>}
          </tr>
        ))}
      </tbody>
    </table></div>
  );
}

/** All branches on a phone: one card per product with the total, and a button that opens its branches. */
export function AllBranchesList({ data, showCost }: { data: AllBranchesStock; showCost: boolean }) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  if (data.items.length === 0) return <p className="empty-state">No items found.</p>;
  const names = Object.fromEntries(data.branches.map((b) => [b.id ?? '', branchName(b)]));
  return (
    <ul style={{ listStyle: 'none', padding: 0 }}>
      {data.items.map((r) => {
        const id = r.product_id ?? '';
        const expanded = open[id] === true;
        return (
          <li key={id} className="rt-card">
            <div className="rt-row">
              <span>
                <strong>{r.description}</strong>
                <br />
                <CategoryLabel category={r.category} />
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
      })}
    </ul>
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
  const [query, setQuery] = useState('');
  const [negativeOnly, setNegativeOnly] = useState(false);
  const [categoryId, setCategoryId] = useState('');
  const [level, setLevel] = useState<StockLevel | ''>('');
  const categories = useQuery({ queryKey: ['retail', 'categories'], queryFn: () => retail.listCategories() });
  const one = useQuery({
    queryKey: ['retail', 'stock', branchId, query, categoryId, negativeOnly, level],
    queryFn: () => retail.listStock({ branchId: branchId ?? '', query, categoryId, negativeOnly, level: level || undefined }),
    enabled: !all && branchId !== null,
  });
  const many = useQuery({
    queryKey: ['retail', 'stock', 'all-branches', query, categoryId, negativeOnly, level],
    queryFn: () => retail.listStockAllBranches({ query, categoryId, negativeOnly, level: level || undefined }),
    enabled: all,
  });
  const result = all ? many : one;
  return (
    <Gate screen="stock" title="Stock">
      <p className="branch-line">Branch: <strong>{all ? 'All branches' : name}</strong></p>
      {!all && branchId !== null && <NoStockHere branchId={branchId} />}
      <StockLevelFilter level={level} onChange={setLevel} />
      <label htmlFor="stock-search">Search by name, code or category</label>
      <input id="stock-search" type="search" value={query} onChange={(e) => setQuery(e.target.value)} autoComplete="off" />
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
      {!all && branchId === null && <BranchRequired permission={'retail.stock.read'} />}
      {result.isPending && (all || branchId !== null) && <p className="loading">Loading</p>}
      <Problem error={result.error} />
      {!all && one.data && <StockTable rows={one.data} showCost={canProfit} />}
      {all && many.data && (phone ? <AllBranchesList data={many.data} showCost={canProfit} /> : <AllBranchesTable data={many.data} showCost={canProfit} />)}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stock')({ component: StockPage });
