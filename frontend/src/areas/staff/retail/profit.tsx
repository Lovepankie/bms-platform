import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, daysBefore, retail, type DailyProfit, type DailyProfitRow, type Valuation } from '../../../api/retail';
import { showPercent, showQty } from './maths';
import { BranchRequired, CategoryLabel, Gate, Problem, money, useBranchName, useBranchView, useIsPhone } from './ui';

// Stock value (FR-RET-09) is a stock read; its cost columns arrive only with retail.profit.read.
// Daily profit (FR-RET-10) needs retail.profit.read (the Gate), and the server refuses it otherwise.

type Sums = { sales?: number; cost?: number; profit?: number; bp?: number };

/** The headline figures of a valuation as a small two column table, so no figure wraps on a phone. */
function Headline({ sums }: { sums: Sums }) {
  const withCost = sums.cost !== undefined;
  return (
    <div className="table-wrap"><table>
      <tbody>
        {withCost && <tr><td>At cost</td><td className="num">{money(sums.cost ?? 0)}</td></tr>}
        <tr><td>At selling price</td><td className="num">{money(sums.sales ?? 0)}</td></tr>
        {withCost && <tr><td>Expected profit</td><td className="num">{money(sums.profit ?? 0)}</td></tr>}
        {withCost && <tr><td>Expected profit % (over cost)</td><td className="num">{showPercent(sums.bp)}</td></tr>}
      </tbody>
    </table></div>
  );
}

/**
 * Rows of totals (branches, categories): a table with a column for each figure, or on a phone one card
 * each with a line per figure, so the profit is never off the edge of the screen.
 */
function SummaryRows({ label, rows, withCost }: { label: string; rows: { key: string; name: string; sums: Sums }[]; withCost: boolean }) {
  const phone = useIsPhone();
  if (phone) {
    return (
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {rows.map(({ key, name, sums }) => (
          <li key={key} className="rt-card">
            <strong>{name}</strong>
            <div className="rt-row"><span>At price</span><span className="num">{money(sums.sales ?? 0)}</span></div>
            {withCost && sums.cost !== undefined && <div className="rt-row"><span>At cost</span><span className="num">{money(sums.cost)}</span></div>}
            {withCost && sums.profit !== undefined && <div className="rt-row"><span>Expected profit</span><span className="num">{money(sums.profit)}</span></div>}
            {withCost && sums.profit !== undefined && <div className="rt-row"><span>Profit % (over cost)</span><span className="num">{showPercent(sums.bp)}</span></div>}
          </li>
        ))}
      </ul>
    );
  }
  return (
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>{label}</th>
          {withCost && <th className="num">At cost</th>}
          <th className="num">At price</th>
          {withCost && <th className="num">Expected profit</th>}
          {withCost && <th className="num">Profit %</th>}
        </tr>
      </thead>
      <tbody>
        {rows.map(({ key, name, sums }) => (
          <tr key={key}>
            <td>{name}</td>
            {withCost && <td className="num">{sums.cost !== undefined ? money(sums.cost) : ''}</td>}
            <td className="num">{money(sums.sales ?? 0)}</td>
            {withCost && <td className="num">{sums.profit !== undefined ? money(sums.profit) : ''}</td>}
            {withCost && <td className="num">{sums.profit !== undefined ? showPercent(sums.bp) : ''}</td>}
          </tr>
        ))}
      </tbody>
    </table></div>
  );
}

const sumsOf = (r: { expected_sales_minor?: number; value_at_cost_minor?: number; expected_profit_minor?: number; expected_profit_bp?: number }): Sums => ({
  sales: r.expected_sales_minor, cost: r.value_at_cost_minor, profit: r.expected_profit_minor, bp: r.expected_profit_bp,
});

export function ValuationTable({ valuation, nameOf }: { valuation: Valuation; nameOf?: (id: string | undefined) => string }) {
  // The cost and profit columns exist only when the server sent them (retail.profit.read).
  const withCost = valuation.value_at_cost_minor !== undefined;
  const categories = valuation.categories ?? [];
  const phone = useIsPhone();
  return (
    <>
      <Headline sums={sumsOf(valuation)} />
      {categories.length > 0 && (
        <>
          <h2>By category</h2>
          <SummaryRows label="Category" withCost={withCost} rows={categories.map((c) => ({ key: c.category_id ?? '', name: c.category ?? '', sums: sumsOf(c) }))} />
        </>
      )}
      <h2>By item</h2>
      <div className="table-wrap" tabIndex={0}><table>
        <thead>
          <tr>
            <th>Item</th>
            {!phone && <th className="num">Qty</th>}
            {withCost && !phone && <th className="num">At cost</th>}
            <th className="num">At price</th>
            {withCost && <th className="num">{phone ? 'Profit' : 'Expected profit'}</th>}
            {withCost && !phone && <th className="num">Profit %</th>}
          </tr>
        </thead>
        <tbody>
          {(valuation.rows ?? []).map((r) => (
            <tr key={`${r.branch_id}-${r.product_id}`}>
              <td>
                {r.description}<br /><CategoryLabel category={r.category} />
                {nameOf && <><br /><span className="hint">{nameOf(r.branch_id)}</span></>}
                {phone && <><br /><span className="hint">{showQty(r.qty ?? '0')} in stock{withCost && r.value_at_cost_minor !== undefined ? `, cost ${money(r.value_at_cost_minor)}` : ''}</span></>}
              </td>
              {!phone && <td className="num">{showQty(r.qty ?? '0')}</td>}
              {withCost && !phone && <td className="num">{r.value_at_cost_minor !== undefined ? money(r.value_at_cost_minor) : ''}</td>}
              <td className="num">{money(r.expected_sales_minor ?? 0)}</td>
              {withCost && (
                <td className="num">
                  {r.expected_profit_minor !== undefined ? money(r.expected_profit_minor) : ''}
                  {phone && r.expected_profit_minor !== undefined && <><br /><span className="hint">{showPercent(r.expected_profit_bp)}</span></>}
                </td>
              )}
              {withCost && !phone && <td className="num">{r.expected_profit_minor !== undefined ? showPercent(r.expected_profit_bp) : ''}</td>}
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
      <SummaryRows label="Branch" withCost={withCost} rows={branches.map((b) => ({ key: b.branch_id ?? '', name: nameOf(b.branch_id), sums: sumsOf(b) }))} />
      <h2>All branches together</h2>
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
  // On a phone the cost and usage go under the name, so Sales and Profit stay in view.
  const phone = useIsPhone();
  return (
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>{label}</th>
          <th className="num">Sales</th>
          {!phone && <th className="num">Cost</th>}
          {!phone && <th className="num">Usage</th>}
          <th className="num">Profit</th>
        </tr>
      </thead>
      <tbody>
        {rows.map(({ key, name, f }) => (
          <tr key={key}>
            <td>{name}{phone && <><br /><span className="hint">Cost {money(f.cost)}, usage {money(f.usage)}</span></>}</td>
            <td className="num">{money(f.sales)}</td>
            {!phone && <td className="num">{money(f.cost)}</td>}
            {!phone && <td className="num">{money(f.usage)}</td>}
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
