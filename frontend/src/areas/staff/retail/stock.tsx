import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type StockRow } from '../../../api/retail';
import { showQty } from './maths';
import { BranchRequired, Gate, NoStockHere, Problem, money, useProfitAccess, useSingleBranch } from './ui';

// Stock per branch (FR-RET-03): balances with a negative flag and a search box. The cost column
// exists only for a session holding retail.profit.read: showCost is the screen's choice and the server
// sends no cost field to anyone else.

export function StockTable({ rows, showCost }: { rows: StockRow[]; showCost: boolean }) {
  if (rows.length === 0) return <p className="empty-state">No items found.</p>;
  return (
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>Item</th>
          <th>Category</th>
          <th className="num">In stock</th>
          <th className="num">Price</th>
          {showCost && <th className="num">Cost</th>}
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.product_id}>
            <td>{r.description}</td>
            <td>{r.category}</td>
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

export function StockPage() {
  const { branchId, branchName } = useSingleBranch();
  const canProfit = useProfitAccess();
  const [query, setQuery] = useState('');
  const [negativeOnly, setNegativeOnly] = useState(false);
  const [categoryId, setCategoryId] = useState('');
  const categories = useQuery({ queryKey: ['retail', 'categories'], queryFn: () => retail.listCategories() });
  const stock = useQuery({
    queryKey: ['retail', 'stock', branchId, query, categoryId, negativeOnly],
    queryFn: () => retail.listStock({ branchId: branchId ?? '', query, categoryId, negativeOnly }),
    enabled: branchId !== null,
  });
  return (
    <Gate screen="stock" title="Stock">
      {branchId === null ? (
        <BranchRequired />
      ) : (
        <>
          <p className="branch-line">Branch: <strong>{branchName}</strong></p>
          <NoStockHere branchId={branchId} />
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
          {stock.isPending && <p className="loading">Loading</p>}
          <Problem error={stock.error} />
          {stock.data && <StockTable rows={stock.data} showCost={canProfit} />}
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stock')({ component: StockPage });
