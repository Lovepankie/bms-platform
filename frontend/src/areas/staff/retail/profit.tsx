import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, daysBefore, retail, type DailyProfit, type DailyProfitRow, type Valuation } from '../../../api/retail';
import { showPercent, showQty } from './maths';
import { BranchRequired, CategoryLabel, Gate, Problem, money, useBranchName, useBranchView } from './ui';

// Stock value (FR-RET-09) is a stock read; its cost columns arrive only with retail.profit.read.
// Daily profit (FR-RET-10) needs retail.profit.read (the Gate), and the server refuses it otherwise.

export function ValuationTable({ valuation, nameOf }: { valuation: Valuation; nameOf?: (id: string | undefined) => string }) {
  // The cost and profit columns exist only when the server sent them (retail.profit.read).
  const withCost = valuation.value_at_cost_minor !== undefined;
  const categories = valuation.categories ?? [];
  return (
    <>
      <p className="rt-total">
        {withCost && <>At cost {money(valuation.value_at_cost_minor ?? 0)}<br /></>}
        At selling price {money(valuation.expected_sales_minor ?? 0)}
        {withCost && <><br />Expected profit {money(valuation.expected_profit_minor ?? 0)} ({showPercent(valuation.expected_profit_bp)} of cost)</>}
      </p>
      {categories.length > 0 && (
        <>
          <h2>By category</h2>
          <div className="table-wrap" tabIndex={0}><table>
            <thead>
              <tr>
                <th>Category</th>
                {withCost && <th className="num">At cost</th>}
                <th className="num">At price</th>
                {withCost && <th className="num">Expected profit</th>}
                {withCost && <th className="num">Profit %</th>}
              </tr>
            </thead>
            <tbody>
              {categories.map((c) => (
                <tr key={c.category_id}>
                  <td>{c.category}</td>
                  {withCost && <td className="num">{money(c.value_at_cost_minor ?? 0)}</td>}
                  <td className="num">{money(c.expected_sales_minor ?? 0)}</td>
                  {withCost && <td className="num">{money(c.expected_profit_minor ?? 0)}</td>}
                  {withCost && <td className="num">{showPercent(c.expected_profit_bp)}</td>}
                </tr>
              ))}
            </tbody>
          </table></div>
        </>
      )}
      <h2>By item</h2>
      <div className="table-wrap" tabIndex={0}><table>
        <thead>
          <tr>
            <th>Item</th>
            <th className="num">Qty</th>
            {withCost && <th className="num">At cost</th>}
            <th className="num">At price</th>
            {withCost && <th className="num">Expected profit</th>}
            {withCost && <th className="num">Profit %</th>}
          </tr>
        </thead>
        <tbody>
          {(valuation.rows ?? []).map((r) => (
            <tr key={`${r.branch_id}-${r.product_id}`}>
              <td>{r.description}<br /><CategoryLabel category={r.category} />{nameOf && <><br /><span className="hint">{nameOf(r.branch_id)}</span></>}</td>
              <td className="num">{showQty(r.qty ?? '0')}</td>
              {withCost && <td className="num">{r.value_at_cost_minor !== undefined ? money(r.value_at_cost_minor) : ''}</td>}
              <td className="num">{money(r.expected_sales_minor ?? 0)}</td>
              {withCost && <td className="num">{r.expected_profit_minor !== undefined ? money(r.expected_profit_minor) : ''}</td>}
              {withCost && <td className="num">{r.expected_profit_minor !== undefined ? showPercent(r.expected_profit_bp) : ''}</td>}
            </tr>
          ))}
        </tbody>
      </table></div>
    </>
  );
}

/** Every branch's totals side by side: what the owner reads first in All branches (#144). */
export function BranchTotals({ valuation, nameOf }: { valuation: Valuation; nameOf: (id: string | undefined) => string }) {
  const branches = valuation.branches ?? [];
  const withCost = valuation.value_at_cost_minor !== undefined || branches.some((b) => b.value_at_cost_minor !== undefined);
  if (branches.length === 0) return null;
  return (
    <>
      <h2>By branch</h2>
      <div className="table-wrap" tabIndex={0}><table>
        <thead>
          <tr>
            <th>Branch</th>
            {withCost && <th className="num">At cost</th>}
            <th className="num">At price</th>
            {withCost && <th className="num">Expected profit</th>}
            {withCost && <th className="num">Profit %</th>}
          </tr>
        </thead>
        <tbody>
          {branches.map((b) => (
            <tr key={b.branch_id}>
              <td>{nameOf(b.branch_id)}</td>
              {withCost && <td className="num">{b.value_at_cost_minor !== undefined ? money(b.value_at_cost_minor) : ''}</td>}
              <td className="num">{money(b.expected_sales_minor ?? 0)}</td>
              {withCost && <td className="num">{b.expected_profit_minor !== undefined ? money(b.expected_profit_minor) : ''}</td>}
              {withCost && <td className="num">{b.expected_profit_minor !== undefined ? showPercent(b.expected_profit_bp) : ''}</td>}
            </tr>
          ))}
        </tbody>
      </table></div>
    </>
  );
}

function ValuationPage() {
  const { all, branchId, branchName } = useBranchView();
  const nameOf = useBranchName();
  const valuation = useQuery({
    queryKey: ['retail', 'valuation', all ? 'all' : branchId],
    queryFn: () => retail.valuation({ branchId: all ? undefined : (branchId ?? '') }),
    enabled: all || branchId !== null,
  });
  return (
    <Gate screen="valuation" title="Stock value">
      {!all && branchId === null ? <BranchRequired /> : (
        <>
          <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
          {valuation.isPending && <p className="loading">Loading</p>}
          <Problem error={valuation.error} />
          {valuation.data && (
            <>
              {all && <BranchTotals valuation={valuation.data} nameOf={nameOf} />}
              <ValuationTable valuation={valuation.data} nameOf={all ? nameOf : undefined} />
            </>
          )}
        </>
      )}
    </Gate>
  );
}

type Figures = { sales: number; cost: number; usage: number; profit: number };
const add = (to: Figures, r: DailyProfitRow): Figures => ({
  sales: to.sales + (r.sales_minor ?? 0), cost: to.cost + (r.cost_of_sales_minor ?? 0), usage: to.usage + (r.usage_cost_minor ?? 0), profit: to.profit + (r.profit_minor ?? 0),
});
const none = (): Figures => ({ sales: 0, cost: 0, usage: 0, profit: 0 });

function FiguresTable({ label, rows }: { label: string; rows: { key: string; name: string; f: Figures }[] }) {
  return (
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>{label}</th>
          <th className="num">Sales</th>
          <th className="num">Cost</th>
          <th className="num">Usage</th>
          <th className="num">Profit</th>
        </tr>
      </thead>
      <tbody>
        {rows.map(({ key, name, f }) => (
          <tr key={key}>
            <td>{name}</td>
            <td className="num">{money(f.sales)}</td>
            <td className="num">{money(f.cost)}</td>
            <td className="num">{money(f.usage)}</td>
            <td className="num">{money(f.profit)}{f.profit < 0 ? ' (loss)' : ''}</td>
          </tr>
        ))}
      </tbody>
    </table></div>
  );
}

/**
 * The profit report. With `nameOf` (All branches) the report is shown per branch and then per day over
 * every branch, so the total is the sum of the branch lines above it (#144).
 */
export function ProfitTable({ report, nameOf }: { report: DailyProfit; nameOf?: (id: string | undefined) => string }) {
  const rows = report.rows ?? [];
  const byDay = new Map<string, Figures>();
  const byBranch = new Map<string, Figures>();
  for (const r of rows) {
    byDay.set(r.date ?? '', add(byDay.get(r.date ?? '') ?? none(), r));
    byBranch.set(r.branch_id ?? '', add(byBranch.get(r.branch_id ?? '') ?? none(), r));
  }
  const days = [...byDay.entries()].sort(([x], [y]) => x.localeCompare(y)).map(([date, f]) => ({ key: date, name: date, f }));
  return (
    <>
      <p className="rt-total">Profit for the period {money(report.profit_minor ?? 0)}</p>
      {nameOf && (
        <>
          <h2>By branch</h2>
          <FiguresTable label="Branch" rows={[...byBranch.entries()].map(([id, f]) => ({ key: id, name: nameOf(id), f }))} />
          <h2>By day, all branches</h2>
        </>
      )}
      <FiguresTable label="Day" rows={days} />
      <p className="hint">Usage and damage reports count here. Stock-take differences do not: they are in the books, not in this report.</p>
    </>
  );
}

function ProfitPage() {
  const { all, branchId, branchName } = useBranchView();
  const nameOf = useBranchName();
  // The last seven days in the business's timezone, the server's own business date (#112 item 9).
  const [to, setTo] = useState(() => businessToday());
  const [from, setFrom] = useState(() => daysBefore(businessToday(), 6));
  const profit = useQuery({
    queryKey: ['retail', 'profit', all ? 'all' : branchId, from, to],
    queryFn: () => retail.dailyProfit({ branchId: all ? undefined : (branchId ?? ''), from, to }),
    enabled: (all || branchId !== null) && from <= to,
  });
  return (
    <Gate screen="profit" title="Daily profit">
      {!all && branchId === null ? <BranchRequired /> : (
        <>
          <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
          <div className="rt-row">
            <div><label htmlFor="from">From</label><input id="from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
            <div><label htmlFor="to">To</label><input id="to" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
          </div>
          {from > to && <p role="alert" className="rt-flag">The start date must not be after the end date.</p>}
          {profit.isPending && from <= to && <p className="loading">Loading</p>}
          <Problem error={profit.error} />
          {profit.data && <ProfitTable report={profit.data} nameOf={all ? nameOf : undefined} />}
        </>
      )}
    </Gate>
  );
}

export const ValuationRoute = createLazyRoute('/staff/retail/valuation')({ component: ValuationPage });
export const Route = createLazyRoute('/staff/retail/profit')({ component: ProfitPage });
