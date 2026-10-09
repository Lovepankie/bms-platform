import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retailAnalytics, type AnalysisRow, type SalesAnalysis, type StockedItem } from '../../../api/retail-analytics';
import { SkeletonList } from '../../../components/states';
import { Bars, Choice, DateRange, Empty, Section, Sparkline, defaultRange, moneyOrNone, percentOrNone, rangeProblem } from './analytics-ui';
import { showQty } from './maths';
import { Gate, Problem, money, useBranchView, useIsPhone } from './ui';

// Sales analysis (issue #149, step 1): totals over time and by branch, category, item and seller for a
// date range, the top ten as bars, and the stock that does not move. Profit columns exist only when the
// server sent them (retail.profit.read in every branch reported).

const GROUPS = [{ value: 'day', label: 'By day' }, { value: 'week', label: 'By week' }, { value: 'month', label: 'By month' }] as const;
const TOPS = [5, 10, 20, 50].map((n) => ({ value: n, label: `Top ${n}` }));
const SLOW = [7, 14, 30, 60, 90, 180].map((n) => ({ value: n, label: `${n} days` }));

/** Rows of one dimension as a compact table; qty and code go under the name on a phone. */
function RowsTable({ title, rows, profit }: { title: string; rows: AnalysisRow[]; profit: boolean }) {
  const phone = useIsPhone();
  if (rows.length === 0) return <Empty />;
  return (
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>{title}</th>
          {!phone && <th className="num">Sold</th>}
          <th className="num">Sales</th>
          {profit && !phone && <th className="num">Profit</th>}
          {profit && !phone && <th className="num">Margin</th>}
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.id ?? r.label}>
            <td>
              {r.label ?? 'Unknown'}
              {r.code && <><br /><span className="hint">{r.code}</span></>}
              {phone && <><br /><span className="hint">{showQty(r.qty ?? '0')}{r.unit ? ` ${r.unit}` : ''} sold{profit && r.margin_bp !== undefined && r.margin_bp !== null ? `, margin ${percentOrNone(r.margin_bp)}` : ''}</span></>}
            </td>
            {!phone && <td className="num">{showQty(r.qty ?? '0')}{r.unit ? ` ${r.unit}` : ''}</td>}
            <td className="num">
              {money(r.sales_minor ?? 0)}
              {phone && profit && r.gross_profit_minor !== undefined && <><br /><span className="hint">profit {money(r.gross_profit_minor)}</span></>}
            </td>
            {profit && !phone && <td className="num">{moneyOrNone(r.gross_profit_minor)}</td>}
            {profit && !phone && <td className="num">{percentOrNone(r.margin_bp)}</td>}
          </tr>
        ))}
      </tbody>
    </table></div>
  );
}

function Stocked({ items, total, empty }: { items: StockedItem[]; total: number; empty: string }) {
  if (items.length === 0) return <Empty>{empty}</Empty>;
  return (
    <>
      <div className="table-wrap" tabIndex={0}><table>
        <thead><tr><th>Item</th><th className="num">In stock</th><th className="num">At price</th></tr></thead>
        <tbody>
          {items.map((i) => (
            <tr key={i.product_id}>
              <td>{i.description}<br /><span className="hint">{i.code}</span></td>
              <td className="num">{showQty(i.qty_on_hand ?? '0')} {i.unit}</td>
              <td className="num">{money(i.stock_at_price_minor ?? 0)}</td>
            </tr>
          ))}
        </tbody>
      </table></div>
      {total > items.length && <p className="hint">Showing {items.length} of {total}. Raise the Top number to see more.</p>}
    </>
  );
}

export function SalesAnalysisView({ data }: { data: SalesAnalysis }) {
  const phone = useIsPhone();
  const profit = data.profit_visible === true;
  const totals = data.totals ?? { sales_minor: 0, sale_count: 0 };
  const series = data.series ?? [];
  const periodName = data.group === 'month' ? 'Month from' : data.group === 'week' ? 'Week from' : 'Day';
  return (
    <>
      <div className="table-wrap"><table>
        <tbody>
          <tr><td>Sales</td><td className="num">{money(totals.sales_minor ?? 0)}</td></tr>
          <tr><td>Number of sales</td><td className="num">{totals.sale_count ?? 0}</td></tr>
          {profit && <tr><td>Profit</td><td className="num">{moneyOrNone(totals.gross_profit_minor)}</td></tr>}
          {profit && <tr><td>Margin (profit over sales)</td><td className="num">{percentOrNone(totals.margin_bp)}</td></tr>}
        </tbody>
      </table></div>
      {profit && <p className="hint">Profit here is sales less the cost the items had when they were sold. Usage, damage and stock-take differences are in Daily profit.</p>}

      <Section title="Over time">
        {series.length === 0 ? <Empty /> : (
          <>
            <Sparkline points={series.map((p) => p.sales_minor ?? 0)} label={`Sales by ${data.group ?? 'day'}, ${money(totals.sales_minor ?? 0)} in all`} />
            <div className="table-wrap" tabIndex={0}><table>
              <thead><tr><th>{periodName}</th>{!phone && <th className="num">Number</th>}<th className="num">Sales</th>{profit && <th className="num">Profit</th>}</tr></thead>
              <tbody>
                {series.map((p) => (
                  <tr key={p.period_start}>
                    <td>{p.period_start}{phone && <><br /><span className="hint">{p.sale_count} {p.sale_count === 1 ? 'sale' : 'sales'}</span></>}</td>
                    {!phone && <td className="num">{p.sale_count}</td>}
                    <td className="num">{money(p.sales_minor ?? 0)}</td>
                    {profit && <td className="num">{moneyOrNone(p.gross_profit_minor)}</td>}
                  </tr>
                ))}
              </tbody>
            </table></div>
          </>
        )}
      </Section>

      <Section title="Best sellers by sales" hint="The top items by money taken.">
        {(data.by_product ?? []).length === 0 ? <Empty /> : (
          <Bars label="Top items by sales" rows={(data.by_product ?? []).slice(0, 10).map((r) => ({ key: r.id ?? '', name: r.label ?? '', value: r.sales_minor ?? 0, text: money(r.sales_minor ?? 0) }))} />
        )}
      </Section>
      <Section title="By item"><RowsTable title="Item" rows={data.by_product ?? []} profit={profit} /></Section>
      <Section title="Best sellers by quantity"><RowsTable title="Item" rows={data.top_by_quantity ?? []} profit={profit} /></Section>
      <Section title="By branch"><RowsTable title="Branch" rows={data.by_branch ?? []} profit={profit} /></Section>
      <Section title="By category"><RowsTable title="Category" rows={data.by_category ?? []} profit={profit} /></Section>
      <Section title="By seller"><RowsTable title="Seller" rows={data.by_seller ?? []} profit={profit} /></Section>

      {data.slow_movers !== undefined && (
        <Section title={`Slow movers: no sale in ${data.slow_days} days`} hint="Items you hold stock of that have not sold lately. Counted back from today, whatever the dates above.">
          <Stocked items={data.slow_movers ?? []} total={data.slow_movers_total ?? 0} empty="Every item in stock has sold lately." />
        </Section>
      )}
      {data.no_sales !== undefined && (
        <Section title="No sales at all in this range" hint="Active items with no sale between the two dates, with what is in stock.">
          <Stocked items={data.no_sales ?? []} total={data.no_sales_total ?? 0} empty="Every active item sold at least once." />
        </Section>
      )}
    </>
  );
}

function SalesAnalysisPage() {
  const { all, branchId, branchName } = useBranchView();
  const initial = defaultRange();
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [group, setGroup] = useState<'day' | 'week' | 'month'>('day');
  const [top, setTop] = useState(10);
  const [slowDays, setSlowDays] = useState(30);
  const problem = rangeProblem(from, to);
  const analysis = useQuery({
    queryKey: ['retail', 'sales-analysis', all ? 'all' : branchId, from, to, group, top, slowDays],
    queryFn: () => retailAnalytics.salesAnalysis({ branchId: all ? undefined : (branchId ?? ''), from, to, group, top, slowDays }),
    enabled: !problem && (all || branchId !== null),
  });
  return (
    <Gate screen="salesAnalysis" title="Sales analysis" wide>
      <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
      <DateRange from={from} to={to} onFrom={setFrom} onTo={setTo} />
      <div className="rt-row">
        <Choice id="an-group" label="Group the days" value={group} options={[...GROUPS]} onChange={setGroup} />
        <Choice id="an-top" label="Rows to show" value={top} options={TOPS} onChange={setTop} />
        <Choice id="an-slow" label="Slow means no sale in" value={slowDays} options={SLOW} onChange={setSlowDays} />
      </div>
      {analysis.isPending && !problem && <SkeletonList label="Loading sales analysis" />}
      <Problem error={analysis.error} />
      {analysis.data && <SalesAnalysisView data={analysis.data} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/sales-analysis')({ component: SalesAnalysisPage });
