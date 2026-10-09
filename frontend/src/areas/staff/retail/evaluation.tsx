import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retailAnalytics, type Evaluation } from '../../../api/retail-analytics';
import { SkeletonList } from '../../../components/states';
import { Choice, DateRange, Empty, Section, defaultRange, percentOrNone, rangeProblem } from './analytics-ui';
import { showQty } from './maths';
import { Gate, Problem, money, useBranchName, useBranchView, useIsPhone } from './ui';

// Business evaluation (issue #149, step 5): profit to date for a period, what the stock on hand is
// worth at today's prices beyond its cost, and the pace of the last 30 days. Formulas on current prices
// and recent sales, no forecast. The whole screen needs retail.profit.read.

const TOPS = [5, 10, 20, 50].map((n) => ({ value: n, label: `Top ${n} per category` }));

type Position = {
  period?: { sales_minor?: number; profit_minor?: number; margin_bp?: number };
  stock?: { at_cost_minor?: number; at_price_minor?: number; expected_profit_minor?: number; over_cost_bp?: number };
  run_rate?: { avg_daily_sales_minor?: number; avg_daily_profit_minor?: number; days_of_stock?: string; days_of_stock_capped?: boolean };
};

/** The figures of one place (all branches, or one branch) as a two column table. */
function PositionTable({ p }: { p: Position }) {
  const run = p.run_rate;
  return (
    <div className="table-wrap"><table>
      <tbody>
        <tr><td>Profit in the period</td><td className="num">{money(p.period?.profit_minor ?? 0)}</td></tr>
        <tr><td>Stock at cost</td><td className="num">{money(p.stock?.at_cost_minor ?? 0)}</td></tr>
        <tr><td>Stock at selling price</td><td className="num">{money(p.stock?.at_price_minor ?? 0)}</td></tr>
        <tr><td>Expected profit on the stock</td><td className="num">{money(p.stock?.expected_profit_minor ?? 0)}</td></tr>
        <tr><td>Expected profit over cost</td><td className="num">{percentOrNone(p.stock?.over_cost_bp) || 'n/a'}</td></tr>
        <tr><td>Sales a day, last 30 days</td><td className="num">{money(run?.avg_daily_sales_minor ?? 0)}</td></tr>
        <tr><td>Profit a day, last 30 days</td><td className="num">{money(run?.avg_daily_profit_minor ?? 0)}</td></tr>
        <tr><td>Days the stock lasts at that pace</td><td className="num">{run?.days_of_stock ? `${run.days_of_stock_capped ? 'over ' : ''}${run.days_of_stock} days` : 'n/a'}</td></tr>
      </tbody>
    </table></div>
  );
}

export function EvaluationView({ data, nameOf }: { data: Evaluation; nameOf: (id: string | undefined) => string }) {
  const phone = useIsPhone();
  return (
    <>
      <p role="note" className="alert alert-info">{data.note}</p>
      <Section title="All shops together" hint="Profit is sales less the cost the items had when sold; usage, damage and stock-take differences are in Daily profit.">
        <PositionTable p={data.total ?? {}} />
      </Section>
      {(data.branches ?? []).length === 0 && <Empty>No stock and no sales to evaluate.</Empty>}
      {(data.branches ?? []).map((b) => (
        <Section key={b.branch_id} title={nameOf(b.branch_id)}>
          <PositionTable p={b} />
          {(b.categories ?? []).map((c) => (
            <div key={c.category_id} className="an-category">
              <h3>{c.category}</h3>
              <p className="hint">
                Profit {money(c.period?.profit_minor ?? 0)}; stock at cost {money(c.stock?.at_cost_minor ?? 0)}, at price {money(c.stock?.at_price_minor ?? 0)};
                expected profit {money(c.stock?.expected_profit_minor ?? 0)} ({percentOrNone(c.stock?.over_cost_bp) || 'n/a'} over cost)
              </p>
              <div className="table-wrap" tabIndex={0}><table>
                <thead>
                  <tr>
                    <th>Item</th>
                    {!phone && <th className="num">Profit</th>}
                    {!phone && <th className="num">In stock</th>}
                    <th className="num">Expected profit</th>
                    {!phone && <th className="num">Over cost</th>}
                  </tr>
                </thead>
                <tbody>
                  {(c.products ?? []).map((p) => (
                    <tr key={p.product_id}>
                      <td>
                        {p.description}<br /><span className="hint">{p.code}</span>
                        {phone && <><br /><span className="hint">{showQty(p.qty_on_hand ?? '0')} {p.unit} in stock, {percentOrNone(p.stock?.over_cost_bp) || 'n/a'} over cost; profit so far {money(p.period?.profit_minor ?? 0)}</span></>}
                      </td>
                      {!phone && <td className="num">{money(p.period?.profit_minor ?? 0)}</td>}
                      {!phone && <td className="num">{showQty(p.qty_on_hand ?? '0')} {p.unit}</td>}
                      <td className="num">{money(p.stock?.expected_profit_minor ?? 0)}</td>
                      {!phone && <td className="num">{percentOrNone(p.stock?.over_cost_bp) || 'n/a'}</td>}
                    </tr>
                  ))}
                </tbody>
              </table></div>
              {(c.products_total ?? 0) > (c.products ?? []).length && <p className="hint">Showing {(c.products ?? []).length} of {c.products_total} items. Raise the Top number to see more.</p>}
            </div>
          ))}
        </Section>
      ))}
      <p className="hint">{data.note}</p>
    </>
  );
}

function EvaluationPage() {
  const { all, branchId, branchName } = useBranchView();
  const nameOf = useBranchName();
  const initial = defaultRange();
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [top, setTop] = useState(10);
  const problem = rangeProblem(from, to);
  const report = useQuery({
    queryKey: ['retail', 'evaluation', all ? 'all' : branchId, from, to, top],
    queryFn: () => retailAnalytics.evaluation({ branchId: all ? undefined : (branchId ?? ''), from, to, top }),
    enabled: !problem && (all || branchId !== null),
  });
  return (
    <Gate screen="evaluation" title="Business evaluation" wide>
      <p role="note" className="alert alert-info">This is an estimate from today's prices and the last 30 days of sales. It is not a forecast or a promise.</p>
      <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
      <p className="hint">The dates choose the period for profit to date. The stock is as of today and the pace is always the last 30 days.</p>
      <DateRange from={from} to={to} onFrom={setFrom} onTo={setTo} />
      <div className="rt-row"><Choice id="ev-top" label="Rows to show" value={top} options={TOPS} onChange={setTop} /></div>
      {report.isPending && !problem && <SkeletonList label="Loading business evaluation" />}
      <Problem error={report.error} />
      {report.data && <EvaluationView data={report.data} nameOf={nameOf} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/evaluation')({ component: EvaluationPage });
