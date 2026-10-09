import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { retailAnalytics, type Dashboard } from '../../../api/retail-analytics';
import { SkeletonList } from '../../../components/states';
import { Sparkline } from './analytics-ui';
import { showQty } from './maths';
import { Problem, money, useBranchName, useBranchView, useIsPhone } from './ui';

// The owner dashboard on the retail home (issue #149, step 6). It shows only what the server sent:
// profit figures need retail.profit.read, stock figures retail.stock.read, and what neither permission
// allows is simply not drawn. The two placeholder slots at the end belong to the cash book (the banked
// against expected figure and the savings), which is not on this server yet: nothing here calls it.

function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <li className="an-stat">
      <span className="an-stat-label">{label}</span>
      <strong className="an-stat-value num">{value}</strong>
      {hint && <span className="hint">{hint}</span>}
    </li>
  );
}

/** A slot kept for a figure that comes with the cash book. It says so and shows no number. */
function Placeholder({ label }: { label: string }) {
  return (
    <li className="an-stat an-placeholder" data-placeholder="cash-book">
      <span className="an-stat-label">{label}</span>
      <span className="hint">Not available yet. It arrives with the cash book.</span>
    </li>
  );
}

export function DashboardView({ data, nameOf, stockLink }: { data: Dashboard; nameOf: (id: string | undefined) => string; stockLink?: ReactNode }) {
  const phone = useIsPhone();
  const today = data.today ?? { sales_minor: 0, sale_count: 0, cash_minor: 0, credit_minor: 0 };
  const days = data.days ?? [];
  const stock = data.stock;
  const shops = data.shops ?? [];
  const withProfit = data.profit_visible === true;
  const shopStock = shops.some((s) => s.stock_at_price_minor !== undefined);
  const shopProfit = shops.some((s) => s.today_gross_profit_minor !== undefined);
  return (
    <section aria-labelledby="dash-title" className="an-dashboard">
      <h2 id="dash-title">Today, {data.date}</h2>
      <ul className="an-stats">
        <Stat label="Sales today" value={money(today.sales_minor ?? 0)} hint={`${today.sale_count ?? 0} ${today.sale_count === 1 ? 'sale' : 'sales'}`} />
        <Stat label="Cash and mobile money" value={money(today.cash_minor ?? 0)} />
        <Stat label="On credit" value={money(today.credit_minor ?? 0)} />
        {withProfit && today.gross_profit_minor !== undefined && <Stat label="Profit today" value={money(today.gross_profit_minor)} />}
      </ul>

      <h3>Sales, last 7 and 30 days</h3>
      <div className="an-sparks">
        <div>
          <p className="an-spark-title">Last 7 days <strong className="num">{money(data.last7_sales_minor ?? 0)}</strong></p>
          <Sparkline points={days.slice(-7).map((d) => d.sales_minor ?? 0)} label={`Sales a day for the last 7 days, ${money(data.last7_sales_minor ?? 0)} in all`} />
          {withProfit && data.last7_gross_profit_minor !== undefined && <p className="hint">Profit {money(data.last7_gross_profit_minor)}</p>}
        </div>
        <div>
          <p className="an-spark-title">Last 30 days <strong className="num">{money(data.last30_sales_minor ?? 0)}</strong></p>
          <Sparkline points={days.map((d) => d.sales_minor ?? 0)} label={`Sales a day for the last 30 days, ${money(data.last30_sales_minor ?? 0)} in all`} />
          {withProfit && data.last30_gross_profit_minor !== undefined && <p className="hint">Profit {money(data.last30_gross_profit_minor)}</p>}
        </div>
      </div>

      {stock && (
        <>
          <h3>Stock</h3>
          <ul className="an-stats">
            <Stat label="Stock at selling price" value={money(stock.value_at_price_minor ?? 0)} />
            {stock.value_at_cost_minor !== undefined && <Stat label="Stock at cost" value={money(stock.value_at_cost_minor)} />}
            <Stat label="Out of stock" value={stock.out_of_stock ?? 0} hint="items at zero or less" />
            <Stat label="Low stock" value={stock.low_stock ?? 0} hint={`${showQty(stock.low_stock_threshold ?? '5')} or fewer left`} />
          </ul>
          {stockLink}
        </>
      )}

      <h3>By shop</h3>
      {shops.length === 0 ? <p className="empty-state">No sales or stock to show yet.</p> : (
        <div className="table-wrap" tabIndex={0}><table>
          <thead>
            <tr>
              <th>Shop</th>
              <th className="num">Today</th>
              <th className="num">7 days</th>
              {!phone && <th className="num">30 days</th>}
              {shopProfit && !phone && <th className="num">Profit today</th>}
              {shopStock && !phone && <th className="num">Stock at price</th>}
              {shopStock && !phone && <th className="num">Out / low</th>}
            </tr>
          </thead>
          <tbody>
            {shops.map((s) => (
              <tr key={s.branch_id}>
                <td>
                  {nameOf(s.branch_id)}
                  {phone && s.out_of_stock !== undefined && <><br /><span className="hint">{s.out_of_stock} out, {s.low_stock ?? 0} low</span></>}
                  {phone && s.today_gross_profit_minor !== undefined && <><br /><span className="hint">Profit today {money(s.today_gross_profit_minor)}</span></>}
                </td>
                <td className="num">{s.today_sales_minor !== undefined ? money(s.today_sales_minor) : 'n/a'}</td>
                <td className="num">{s.last7_sales_minor !== undefined ? money(s.last7_sales_minor) : 'n/a'}</td>
                {!phone && <td className="num">{s.last30_sales_minor !== undefined ? money(s.last30_sales_minor) : 'n/a'}</td>}
                {shopProfit && !phone && <td className="num">{s.today_gross_profit_minor !== undefined ? money(s.today_gross_profit_minor) : ''}</td>}
                {shopStock && !phone && <td className="num">{s.stock_at_price_minor !== undefined ? money(s.stock_at_price_minor) : ''}</td>}
                {shopStock && !phone && <td className="num">{s.out_of_stock !== undefined ? `${s.out_of_stock} / ${s.low_stock ?? 0}` : ''}</td>}
              </tr>
            ))}
          </tbody>
        </table></div>
      )}

      <h3>Cash book</h3>
      <ul className="an-stats">
        <Placeholder label="Banked against expected" />
        <Placeholder label="Savings" />
      </ul>
    </section>
  );
}

/** The dashboard of the branch chosen in the staff bar, or of every branch the session may read. */
export function RetailDashboard() {
  const { all, branchId } = useBranchView();
  const nameOf = useBranchName();
  const dashboard = useQuery({
    queryKey: ['retail', 'dashboard', all ? 'all' : branchId],
    queryFn: () => retailAnalytics.dashboard({ branchId: all ? undefined : (branchId ?? '') }),
    enabled: all || branchId !== null,
  });
  if (dashboard.isPending) return <SkeletonList label="Loading the dashboard" />;
  if (dashboard.error) return <Problem error={dashboard.error} />;
  return dashboard.data ? <DashboardView data={dashboard.data} nameOf={nameOf} stockLink={<p className="hint"><Link to="/staff/retail/stock">See the stock lists</Link></p>} /> : null;
}
