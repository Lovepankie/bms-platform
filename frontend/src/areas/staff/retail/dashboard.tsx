import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { retail } from '../../../api/retail';
import { retailAnalytics, type Dashboard } from '../../../api/retail-analytics';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { showQty } from './maths';
import { canUse } from './permissions';
import { ChangeLine, ChartLegend, RangeChart, RangePicker } from './range-ui';
import { DEFAULT_RANGE, daysBetween, previousWords, rangeQuery, rangeTitle, readRange, shortDate, writeRange, type RangeChoice } from './range-state';
import { ScreenLink } from './screen-link';
import { Problem, money, useBranchName, useBranchView, useIsPhone } from './ui';

// The owner dashboard on the retail home (issue #149, the range of issue #428). It shows only what the server sent: profit
// figures need retail.profit.read, stock figures retail.stock.read, and what neither permission allows is simply not drawn.
// Every sales figure follows the range picked at the top, set against the period before; the stock counts are as of now.

function Stat({ label, value, hint, change }: { label: string; value: ReactNode; hint?: ReactNode; change?: ReactNode }) {
  return (
    <li className="an-stat">
      <span className="an-stat-label">{label}</span>
      <strong className="an-stat-value num">{value}</strong>
      {change}
      {hint && <span className="hint">{hint}</span>}
    </li>
  );
}

const count = (n: number) => String(n);
const sales = (n: number) => `${n} ${n === 1 ? 'sale' : 'sales'}`;

type Range = NonNullable<Dashboard['range']>;

/** The cash book of the range, from the daily cash summary (the till's figures, ADR-055), up to a month at a time. */
export const CASH_CARD_DAYS = 31;

export function CashBookCards({ from, to, branchIds }: { from: string; to: string; branchIds?: string[] }) {
  const short = daysBetween(from, to) <= CASH_CARD_DAYS;
  const daily = useQuery({
    queryKey: ['retail', 'home-cash', branchIds ?? 'all', from, to],
    queryFn: () => retail.cashDaily({ branchIds, from, to }),
    enabled: short,
  });
  const links = (
    <p className="rc-links">
      <ScreenLink screen="cashSummary" to="/staff/retail/cash-summary">Cash summary</ScreenLink>
      <ScreenLink screen="bankingReport" to="/staff/retail/banking-report">Banking report</ScreenLink>
    </p>
  );
  if (!short) return <><p className="hint">The cash book shows up to {CASH_CARD_DAYS} days here. Open the cash summary for longer.</p>{links}</>;
  if (daily.isPending) return <p className="loading">Adding up the cash book</p>;
  if (daily.isError) return <><p className="hint">The cash book could not be loaded.</p>{links}</>;
  const rows = daily.data.items ?? [];
  const sum = (pick: (r: (typeof rows)[number]) => number | undefined) => {
    const seen = rows.map(pick).filter((v): v is number => v !== undefined);
    return seen.length === 0 ? undefined : seen.reduce((a, b) => a + b, 0);
  };
  const banked = sum((r) => r.banked_minor) ?? 0;
  const expected = sum((r) => r.expected_to_bank_minor);
  const saved = sum((r) => r.savings_minor);
  return (
    <>
      <ul className="an-stats" data-testid="home-cash">
        <Stat label="Banked" value={money(banked)} hint={expected !== undefined ? `Expected to bank ${money(expected)}` : undefined} />
        {saved !== undefined && <Stat label="Savings set aside" value={money(saved)} />}
        <Stat label="Expenses" value={money(sum((r) => r.expenses_minor) ?? 0)} />
      </ul>
      {links}
    </>
  );
}

export function DashboardView({ data, nameOf, choice = DEFAULT_RANGE, onChoice, stockLink, links, cash, updating = false }: {
  data: Dashboard;
  nameOf: (id: string | undefined) => string;
  choice?: RangeChoice;
  onChoice?: (choice: RangeChoice) => void;
  stockLink?: ReactNode;
  /** The links to the rows behind the figures (issue #336). */
  links?: ReactNode;
  /** The cash book cards of the range, for a session that closes the till. */
  cash?: (range: Range) => ReactNode;
  /** True while the figures of a newly picked range load; the ones shown are still those of `data.range`. */
  updating?: boolean;
}) {
  const phone = useIsPhone();
  const range = data.range;
  const stock = data.stock;
  const shops = data.shops ?? [];
  const today = data.today;
  // ADR-043: with credit sales counted when the buyer pays, the cards say Sold, Money collected and On credit, and profit is
  // that of the money received. The default way (when the sale is made) keeps its words.
  const onPayment = data.credit_sale_recognition === 'on_payment';
  const picker = <RangePicker value={choice} onChange={onChoice ?? (() => undefined)} today={data.date} />;
  // Today stays above the range picker whatever days are picked (issue #428): the split by the way of paying, and profit.
  const withProfit = data.profit_visible === true;
  const todayCard = (
    <>
      <h2 id="today-title">Today, {data.date}</h2>
      <ul className="an-stats" aria-labelledby="today-title" data-testid="today-card">
        {onPayment ? (
          <>
            <Stat label="Sales today" value={money(today?.sales_minor ?? 0)} hint={`${today?.sale_count ?? 0} ${today?.sale_count === 1 ? 'sale' : 'sales'} sold, including credit`} />
            <Stat label="Money collected" value={money(today?.received_minor ?? 0)} hint="Money received today by any method, with payments on earlier credit sales, less money paid back" />
            <Stat label="On credit" value={money(today?.on_credit_minor ?? 0)} hint={`${today?.on_credit_count ?? 0} ${today?.on_credit_count === 1 ? 'sale' : 'sales'} not paid yet. Not counted as profit`} />
            {withProfit && today?.gross_profit_minor !== undefined && <Stat label="Profit today" value={money(today.gross_profit_minor)} hint="From the money received" />}
          </>
        ) : (
          <>
            <Stat label="Sales today" value={money(today?.sales_minor ?? 0)} hint={`${today?.sale_count ?? 0} ${today?.sale_count === 1 ? 'sale' : 'sales'}`} />
            <Stat label="Cash and mobile money" value={money(today?.cash_minor ?? 0)} />
            <Stat label="On credit" value={money(today?.credit_minor ?? 0)} />
            {withProfit && today?.gross_profit_minor !== undefined && <Stat label="Profit today" value={money(today.gross_profit_minor)} />}
          </>
        )}
      </ul>
      {data.credit_recognition_starts_on && (
        <p className="hint" data-testid="credit-starts-on">
          {onPayment
            ? `Credit sales entered and dated before ${data.credit_recognition_starts_on} count as they did, on the day of the sale.`
            : `From ${data.credit_recognition_starts_on}, credit sales will count when the buyer pays.`}
        </p>
      )}
    </>
  );
  if (!range) {
    return <section aria-labelledby="dash-title" className="an-dashboard">{todayCard}<h2 id="dash-title">Sales</h2>{picker}<p className="empty-state">No figures for these days.</p></section>;
  }
  // The words follow the figures on screen, which are those of the range the server answered.
  const shown: RangeChoice = { period: range.period ?? choice.period, from: range.from, to: range.to };
  const days = { from: range.from, to: range.to ?? data.date ?? '' };
  const before = previousWords(shown, days);
  const cur = range.current ?? { sales_minor: 0, sale_count: 0 };
  const prev = range.previous;
  const title = rangeTitle(shown, days);
  const shopProfit = shops.some((s) => s.range_gross_profit_minor !== undefined);
  const shopStock = shops.some((s) => s.out_of_stock !== undefined);
  const withBefore = prev !== undefined;
  const points = (range.series ?? []).map((p) => ({ start: p.start ?? '', value: p.sales_minor ?? 0, previous: p.previous_sales_minor }));
  return (
    <section aria-labelledby="dash-title" className="an-dashboard">
      {todayCard}
      <h2 id="dash-title">{onPayment ? 'Sold' : 'Sales'}, {title}</h2>
      {picker}
      {updating && <p className="loading">Loading the days you picked</p>}
      <div className="rc-headline">
        <strong className="rc-total num">{money(cur.sales_minor ?? 0)}</strong>
        <ChangeLine now={cur.sales_minor ?? 0} before={prev?.sales_minor} words={before} />
      </div>
      {onPayment && <p className="hint">Everything sold, credit included.</p>}
      {range.period === 'all' && <p className="hint" data-testid="all-cap">All shows up to the last 3 years.</p>}
      {range.from && range.to && daysBetween(range.from, range.to) > 731 && <p className="hint" data-testid="no-leaders">The best seller and top salesperson show for ranges of two years or less.</p>}
      <RangeChart
        points={points}
        group={range.group ?? 'day'}
        label={`${onPayment ? 'Sold' : 'Sales'} ${title.toLowerCase()}, ${money(cur.sales_minor ?? 0)} in all`}
        beforeWords={withBefore ? 'before' : undefined}
        legend={<ChartLegend before={withBefore ? before.replace(/^the /, 'The ') : undefined} />}
      />

      <ul className="an-stats" data-testid="range-detail">
        <Stat label="Number of sales" value={cur.sale_count ?? 0} change={<ChangeLine now={cur.sale_count ?? 0} before={prev?.sale_count} words={before} format={count} />} />
        <Stat label="Average sale" value={cur.average_sale_minor !== undefined ? money(cur.average_sale_minor) : 'No sales'} change={cur.average_sale_minor !== undefined && prev?.average_sale_minor !== undefined ? <ChangeLine now={cur.average_sale_minor} before={prev.average_sale_minor} words={before} /> : undefined} />
        {cur.gross_profit_minor !== undefined && (
          <Stat label="Profit" value={money(cur.gross_profit_minor)} hint={onPayment ? 'From the money received' : undefined} change={<ChangeLine now={cur.gross_profit_minor} before={prev?.gross_profit_minor} words={before} />} />
        )}
        {onPayment && cur.received_minor !== undefined && (
          <Stat label="Money collected" value={money(cur.received_minor)} hint="Money received by any method, with payments on earlier credit sales, less money paid back" change={<ChangeLine now={cur.received_minor} before={prev?.received_minor} words={before} />} />
        )}
        {onPayment && today?.on_credit_minor !== undefined && (
          <Stat label="On credit now" value={money(today.on_credit_minor)} hint={`${sales(today.on_credit_count ?? 0)} not paid yet. Not counted as profit`} />
        )}
        {range.busiest_day && (
          <Stat
            label="Busiest day"
            value={shortDate(range.busiest_day.date ?? '')}
            hint={<>{money(range.busiest_day.sales_minor ?? 0)}{range.busiest_day.previous_date && <> (before: {shortDate(range.busiest_day.previous_date)}, {money(range.busiest_day.previous_sales_minor ?? 0)})</>}</>}
          />
        )}
        {range.best_shop && (
          <Stat
            label="Best shop"
            value={nameOf(range.best_shop.id)}
            hint={`${money(range.best_shop.sales_minor ?? 0)}, ${sales(range.best_shop.sale_count ?? 0)}`}
            change={<ChangeLine now={range.best_shop.sales_minor ?? 0} before={range.best_shop.previous_sales_minor} words={before} />}
          />
        )}
        {range.best_seller && (
          <Stat
            label="Best seller"
            value={range.best_seller.label ?? range.best_seller.code ?? ''}
            hint={`${money(range.best_seller.sales_minor ?? 0)}, in ${sales(range.best_seller.sale_count ?? 0)}`}
            change={<ChangeLine now={range.best_seller.sales_minor ?? 0} before={range.best_seller.previous_sales_minor} words={before} />}
          />
        )}
        {range.top_salesperson && (
          <Stat
            label="Top salesperson"
            value={range.top_salesperson.label ?? 'Unknown person'}
            hint={`${money(range.top_salesperson.sales_minor ?? 0)}, ${sales(range.top_salesperson.sale_count ?? 0)}`}
            change={<ChangeLine now={range.top_salesperson.sales_minor ?? 0} before={range.top_salesperson.previous_sales_minor} words={before} />}
          />
        )}
      </ul>
      {cur.sale_count === 0 && <p className="empty-state">No sales in these days.</p>}
      {links}

      <h3>By shop</h3>
      {shops.length === 0 ? <p className="empty-state">No sales or stock to show yet.</p> : (
        <div className="table-wrap" tabIndex={0}><table>
          <thead>
            <tr>
              <th>Shop</th>
              <th className="num">Sales</th>
              {withBefore && !phone && <th className="num">Before</th>}
              {shopProfit && !phone && <th className="num">Profit</th>}
              {shopStock && !phone && <th className="num">Out / low now</th>}
            </tr>
          </thead>
          <tbody>
            {shops.map((s) => (
              <tr key={s.branch_id}>
                <td>
                  {nameOf(s.branch_id)}
                  {phone && s.range_sales_minor !== undefined && s.previous_sales_minor !== undefined && <><br /><ChangeLine now={s.range_sales_minor} before={s.previous_sales_minor} words={before} /></>}
                  {phone && s.range_gross_profit_minor !== undefined && <><br /><span className="hint">Profit {money(s.range_gross_profit_minor)}</span></>}
                  {phone && s.out_of_stock !== undefined && <><br /><span className="hint">{s.out_of_stock} out, {s.low_stock ?? 0} low now</span></>}
                </td>
                <td className="num">{s.range_sales_minor !== undefined ? money(s.range_sales_minor) : 'n/a'}</td>
                {withBefore && !phone && <td className="num">{s.previous_sales_minor !== undefined ? money(s.previous_sales_minor) : 'n/a'}</td>}
                {shopProfit && !phone && <td className="num">{s.range_gross_profit_minor !== undefined ? money(s.range_gross_profit_minor) : ''}</td>}
                {shopStock && !phone && <td className="num">{s.out_of_stock !== undefined ? `${s.out_of_stock} / ${s.low_stock ?? 0}` : ''}</td>}
              </tr>
            ))}
          </tbody>
        </table></div>
      )}

      {stock && (
        <>
          <h3>Stock now</h3>
          <ul className="an-stats">
            {stock.value_at_cost_minor !== undefined && <Stat label="Stock at cost" value={money(stock.value_at_cost_minor)} />}
            {stock.value_at_price_minor !== undefined && <Stat label="Stock at price" value={money(stock.value_at_price_minor)} />}
            <Stat label="Out of stock" value={stock.out_of_stock ?? 0} hint="items at zero or less" />
            <Stat label="Low stock" value={stock.low_stock ?? 0} hint={`at their minimum, or ${showQty(stock.low_stock_threshold ?? '5')} or fewer without one`} />
          </ul>
          <p className="hint">Stock is counted as it is now, whatever days are picked.</p>
          {stockLink}
        </>
      )}

      {cash && (
        <>
          <h3>Cash book, {title}</h3>
          {cash(range)}
        </>
      )}
    </section>
  );
}

/** The dashboard of the branch chosen in the staff bar, or of every branch the session may read, over the remembered range. */
export function RetailDashboard() {
  const { me } = useStaff();
  const userId = me?.user_id ?? '';
  const [choice, setChoice] = useState<RangeChoice>(() => readRange(userId));
  const { all, branchId } = useBranchView();
  const nameOf = useBranchName();
  const query = rangeQuery(choice);
  const dashboard = useQuery({
    queryKey: ['retail', 'dashboard', all ? 'all' : branchId, query.period, query.from ?? '', query.to ?? ''],
    queryFn: () => retailAnalytics.dashboard({ branchId: all ? undefined : (branchId ?? ''), ...query }),
    enabled: all || branchId !== null,
    // The figures of the range picked before stay on screen while the next ones load on a slow line.
    placeholderData: (previous) => previous,
  });
  const pick = (next: RangeChoice) => {
    setChoice(next);
    writeRange(userId, next);
  };
  if (dashboard.isPending) return <><RangePicker value={choice} onChange={pick} /><SkeletonList label="Loading the dashboard" /></>;
  if (dashboard.error) return <><RangePicker value={choice} onChange={pick} /><Problem error={dashboard.error} /></>;
  const branchIds = all ? undefined : branchId ? [branchId] : undefined;
  return (
    <DashboardView
      data={dashboard.data}
      nameOf={nameOf}
      choice={choice}
      onChoice={pick}
      updating={dashboard.isPlaceholderData}
      stockLink={<p className="hint"><Link to="/staff/retail/stock">See the stock lists</Link></p>}
      links={(
        <p className="rc-links">
          <ScreenLink screen="salesAnalysis" to="/staff/retail/sales-analysis">Sales analysis</ScreenLink>
          <ScreenLink screen="salesHistory" to="/staff/retail/sales">All sales</ScreenLink>
        </p>
      )}
      cash={canUse(me, 'cashSummary') ? (r) => <CashBookCards from={r.from ?? ''} to={r.to ?? ''} branchIds={branchIds} /> : undefined}
    />
  );
}
