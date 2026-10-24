import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { listPendingApprovals, type Me } from '../../api/client';
import { lending } from '../../api/lending';
import { retailAnalytics, type HomeSummary, type HomeTopItem } from '../../api/retail-analytics';
import { retailOrders } from '../../api/retail-orders';
import { ALL_BRANCHES, branchFilter } from '../../auth/branch';
import { icons } from '../../components/icons';
import { useStaff } from './context';
import { money as lendingMoney } from './lending/loan-state';
import { showLending } from './lending/permissions';
import { ALL_SELLERS, branchesWhere, canUse, holds, holdsAt, showRetail } from './retail/permissions';
import { shortDate } from './retail/range-state';
import { money as retailMoney } from './retail/ui';
import { RowChevron } from '../../app/ui/ListRow';

// Home's card per module the business uses (issue #471, ADR-072): Shop with today's sales, the items low on stock and the open
// customer orders; Lending with the loans due today, those in arrears and the lending requests waiting for approval. A business with
// one module sees only its card. Since issue #485 (ADR-076) the Shop card is followed by the shop's summary cards: today's takings
// by way of paying, what buyers owe, the top five items today or this week and a line per shop, each linking to its full page.
// Every shop figure comes from one read, the Home summary (GET /retail/reports/home, retail.sale.read), which leaves out what the
// session may not see: the money parts need retail.sale.all_sellers (ADR-055), the stock count retail.stock.read and the open
// orders retail.order.manage. Each figure is drawn only for a session holding what it needs; the lending figures come from the
// loans summary (lending.loans.read) and the approvals inbox (core.approvals.read).

/**
 * The approval actions of the lending module (each action's TYPE in the backend's lending packages, checked by a test), asked of the
 * inbox as its action_type filter so the Lending card counts only its own requests.
 */
export const LENDING_APPROVALS = [
  'collateral_release', 'loan_disbursement', 'repayment_reversal', 'loan_write_off', 'charge_waiver', 'savings_reversal',
  'savings_withdrawal', 'investment_funding', 'investment_early_withdrawal', 'investment_reversal', 'loan_bring_in',
  'loan_bring_in_reversal',
];

/**
 * Which figures each card shows the session in the active branch; a card with none is not drawn. The shop total needs
 * retail.sale.all_sellers in the branch chosen (ADR-055); over every branch the server keeps to the shops the session supervises.
 */
export function homeParts(me: Me, branch: string | null = null) {
  const permissions = me.permissions ?? [];
  const retail = showRetail(me);
  const one = branch && branch !== ALL_BRANCHES ? branch : null;
  // The Home summary is a sales read; a session without one (the orders permission alone) reads the orders board instead.
  const summary = retail && canUse(me, 'salesHistory');
  const sales = summary && (one ? holdsAt(me, ALL_SELLERS, one) : holds(me, ALL_SELLERS));
  const lendingOn = !me.modules || me.modules.includes('lending');
  return {
    summary,
    sales,
    lowStock: summary && (one ? holdsAt(me, 'retail.stock.read', one) : holds(me, 'retail.stock.read')),
    orders: retail && canUse(me, 'orders'),
    // A line per shop is for a session that sees every seller's sales in more than one branch, over All branches.
    branches: sales && !one && branchesWhere(me, ALL_SELLERS).length > 1,
    // The takings are the cash summary's figures, so they also need its permissions (ADR-076 decision 3).
    takings: sales && canUse(me, 'cashSummary') && (one ? holdsAt(me, 'retail.cashbook.read', one) : holds(me, 'retail.cashbook.read')),
    loans: showLending(me),
    approvals: lendingOn && permissions.includes('core.approvals.read'),
  };
}

function Figure({ label, query, children }: { label: string; query: { isPending: boolean; isError: boolean }; children: () => ReactNode }) {
  return (
    <div className="home-figure">
      <dt>{label}</dt>
      <dd>{query.isError ? <span className="hint">Could not load</span> : query.isPending ? <span className="hint">Loading</span> : children()}</dd>
    </div>
  );
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

const METHODS: Record<string, string> = { cash: 'Cash', mobile_money: 'Mobile money', bank: 'Bank' };

type Parts = ReturnType<typeof homeParts>;
type Summary = ReturnType<typeof useHomeSummary>;

/** The cache key of the Home summary of a branch (undefined for all of them); the phone's My stock badge reads the same entry (#539). */
export const homeSummaryKey = (branchId: string | undefined) => ['retail', 'home', branchId ?? 'all'];

/**
 * The options of the Home summary read, shared by Home and the phone's My stock badge (#539) so both use one cache entry,
 * one gate: a session Home never asks for is never asked for by the badge either. Home keeps no freshness window, so it
 * reads again each time it opens and shows a sale just recorded; only the badge waits a minute between reads.
 */
export function homeSummaryQuery(parts: Parts, branch: string | null) {
  const branchId = branch && branch !== ALL_BRANCHES ? branch : undefined;
  return {
    queryKey: homeSummaryKey(branchId),
    queryFn: ({ signal }: { signal: AbortSignal }) => retailAnalytics.home(branchId, signal),
    enabled: parts.summary && (parts.sales || parts.lowStock || parts.orders) && branch !== null,
  };
}

/** The one read behind every shop figure on Home, under the branch chosen in the header. */
function useHomeSummary(parts: Parts) {
  const { branch } = useStaff();
  return useQuery(homeSummaryQuery(parts, branch));
}

function ShopCard({ parts, home }: { parts: Parts; home: Summary }) {
  const { branch } = useStaff();
  const branchId = branch && branch !== ALL_BRANCHES ? branch : undefined;
  // Only a session that cannot read the summary (no sales read) asks the orders board for the open orders.
  const board = useQuery({
    queryKey: ['retail', 'orders', 'board', branchId ?? 'all'],
    queryFn: ({ signal }) => retailOrders.board(branchId, signal),
    enabled: parts.orders && !parts.summary && branch !== null,
  });
  const today = home.data?.sales_today;
  const ordersFrom = parts.summary ? home : board;
  const open = parts.summary
    ? (home.data?.open_orders ?? 0)
    : (board.data?.ordered ?? 0) + (board.data?.packed ?? 0) + (board.data?.out_for_delivery ?? 0);
  return (
    <article className="card home-card" aria-labelledby="home-shop">
      <h2 id="home-shop" className="card-title">{icons.shop}<span>Shop</span></h2>
      <dl className="home-figures">
        {parts.sales && answered(home, 'sales_today') && (
          <Figure label="Sales today" query={home}>
            {() => <>{retailMoney(today?.sales_minor ?? 0)} <span className="hint">{plural(today?.sale_count ?? 0, 'sale', 'sales')}</span></>}
          </Figure>
        )}
        {parts.lowStock && answered(home, 'low_stock') && <Figure label="Low on stock" query={home}>{() => plural(home.data?.low_stock ?? 0, 'item', 'items')}</Figure>}
        {parts.orders && (!parts.summary || answered(home, 'open_orders')) && <Figure label="Open customer orders" query={ordersFrom}>{() => open}</Figure>}
      </dl>
      <Link className="link-row" to="/staff/retail">Open the shop<RowChevron /></Link>
    </article>
  );
}

/** A shop summary card: a heading, its body once the summary has answered, and the link to its full page. */
function SummaryCard({ id, title, home, tour, children, link }: { id: string; title: string; home: Summary; tour?: string; children: () => ReactNode; link: ReactNode }) {
  return (
    <article className="card home-card home-summary" aria-labelledby={id} data-tour={tour}>
      <h2 id={id} className="card-title"><span>{title}</span></h2>
      <div className="home-summary-body">
        {home.isError ? <p className="hint">Could not load</p> : home.isPending ? <p className="hint">Loading</p> : children()}
      </div>
      {link}
    </article>
  );
}

function TakingsCard({ home }: { home: Summary }) {
  const takings = home.data?.takings;
  return (
    <SummaryCard
      id="home-takings"
      title="Takings today"
      home={home}
      tour="home-shop-today"
      link={<Link className="link-row" to="/staff/retail/cash-summary">Open the cash summary<RowChevron /></Link>}
    >
      {() => (
        <>
          <dl className="home-figures">
            {(takings?.methods ?? []).map((m) => (
              <div className="home-figure" key={m.method}>
                <dt>{METHODS[m.method ?? ''] ?? m.method}</dt>
                <dd>{retailMoney(m.amount_minor ?? 0)}</dd>
              </div>
            ))}
            <div className="home-figure">
              <dt>Total</dt>
              <dd>{retailMoney(takings?.total_minor ?? 0)}</dd>
            </div>
          </dl>
          {(takings?.methods ?? []).every((m) => !m.sales_minor && !m.payments_minor && !m.fees_minor && !m.out_minor) && (
            <p className="hint">No money taken yet today.</p>
          )}
        </>
      )}
    </SummaryCard>
  );
}

function CreditCard({ home }: { home: Summary }) {
  const owed = home.data?.credit_owed;
  return (
    <SummaryCard id="home-credit" title="Credit owed" home={home} link={<Link className="link-row" to="/staff/retail/credit-control">Open credit control<RowChevron /></Link>}>
      {() =>
        !owed?.owed_minor ? (
          <p className="hint">No customer owes you money.</p>
        ) : (
          <dl className="home-figures">
            <div className="home-figure">
              <dt>Owed by customers</dt>
              <dd>{retailMoney(owed.owed_minor)} <span className="hint">{plural(owed.customers ?? 0, 'customer', 'customers')}</span></dd>
            </div>
            {owed.oldest_due_date && (
              <div className="home-figure">
                <dt>Oldest due</dt>
                <dd>{shortDate(owed.oldest_due_date)}</dd>
              </div>
            )}
          </dl>
        )
      }
    </SummaryCard>
  );
}

function TopItemsCard({ home }: { home: Summary }) {
  const [week, setWeek] = useState(false);
  const items: HomeTopItem[] = (week ? home.data?.top_items?.week : home.data?.top_items?.today) ?? [];
  return (
    <SummaryCard id="home-top" title="Top items" home={home} link={<Link className="link-row" to="/staff/retail/sales-analysis">Open sales analysis<RowChevron /></Link>}>
      {() => (
        <>
          <div className="cluster" role="group" aria-label="Top items of">
            <button type="button" className="btn-sm" aria-pressed={!week} onClick={() => setWeek(false)}>Today</button>
            <button type="button" className="btn-sm" aria-pressed={week} onClick={() => setWeek(true)}>This week</button>
          </div>
          {items.length === 0 ? (
            <p className="hint">{week ? 'Nothing sold yet this week.' : 'Nothing sold yet today.'}</p>
          ) : (
            <ol className="home-list">
              {items.map((i) => (
                <li key={i.item_id}>
                  <span>{i.label} <span className="hint">{qty(i.qty)} {i.unit}</span></span>
                  <span>{retailMoney(i.sales_minor ?? 0)}</span>
                </li>
              ))}
            </ol>
          )}
        </>
      )}
    </SummaryCard>
  );
}

function BranchesCard({ home }: { home: Summary }) {
  const lines = home.data?.branches ?? [];
  return (
    <SummaryCard id="home-branches" title="Each shop today" home={home} link={<Link className="link-row" to="/staff/retail">Open the shop dashboard<RowChevron /></Link>}>
      {() => (
        <ul className="home-list">
          {lines.map((b) => (
            <li key={b.branch_id}>
              <span>{b.name}</span>
              <span>{b.sale_count ? <>{retailMoney(b.sales_minor ?? 0)} <span className="hint">{plural(b.sale_count, 'sale', 'sales')}</span></> : <span className="hint">No sales yet</span>}</span>
            </li>
          ))}
        </ul>
      )}
    </SummaryCard>
  );
}

/** True while the summary is loading or failed (the card says so), and once it answered, when it holds the part. */
function answered(home: Summary, part: keyof HomeSummary): boolean {
  return home.isPending || home.isError || home.data?.[part] !== undefined;
}

/** A quantity without its trailing zeros: "2.000" reads 2, "1.500" reads 1.5. */
function qty(value: string | undefined): string {
  return value && value.includes('.') ? value.replace(/\.?0+$/, '') : (value ?? '0');
}

/** The Shop card and, for a session that sees everybody's money, the shop's summary cards after it. */
function ShopCards({ parts }: { parts: Parts }) {
  const home = useHomeSummary(parts);
  // The server decides every figure: a part it leaves out (no money permission over the branches covered, a branch outside the
  // session's scope, fewer than two shops for the lines) takes its card away once it has answered, rather than saying "nothing".
  const sent = (part: keyof HomeSummary) => answered(home, part);
  return (
    <>
      <ShopCard parts={parts} home={home} />
      {parts.takings && sent('takings') && <TakingsCard home={home} />}
      {parts.sales && sent('credit_owed') && <CreditCard home={home} />}
      {parts.sales && sent('top_items') && <TopItemsCard home={home} />}
      {parts.branches && sent('branches') && <BranchesCard home={home} />}
    </>
  );
}

function LendingCard({ parts }: { parts: Parts }) {
  const { branch } = useStaff();
  const branchIds = branchFilter(branch);
  const summary = useQuery({
    queryKey: ['lending', 'home-summary', branch],
    queryFn: () => lending.homeSummary(branchIds),
    enabled: parts.loans && branch !== null,
  });
  const approvals = useQuery({
    queryKey: ['approvals', branch, 'lending'],
    queryFn: () => listPendingApprovals(branchIds, LENDING_APPROVALS),
    enabled: parts.approvals && branch !== null,
  });
  const s = summary.data;
  const waiting = approvals.data?.items?.length ?? 0;
  return (
    <article className="card home-card" aria-labelledby="home-lending">
      <h2 id="home-lending" className="card-title">{icons.lending}<span>Lending</span></h2>
      <dl className="home-figures">
        {parts.loans && (
          <Figure label="Due today" query={summary}>
            {() => <>{lendingMoney(s?.due_today_minor ?? 0, s?.currency)} <span className="hint">{plural(s?.due_today_count ?? 0, 'loan', 'loans')}</span></>}
          </Figure>
        )}
        {parts.loans && (
          <Figure label="In arrears" query={summary}>
            {() => <>{lendingMoney(s?.in_arrears_minor ?? 0, s?.currency)} <span className="hint">{plural(s?.in_arrears_count ?? 0, 'loan', 'loans')}</span></>}
          </Figure>
        )}
        {parts.approvals && (
          <Figure label="Waiting for approval" query={approvals}>
            {() => <Link to="/staff/approvals">{waiting}{approvals.data?.next_cursor ? '+' : ''}</Link>}
          </Figure>
        )}
      </dl>
      {parts.loans && <Link className="link-row" to="/staff/lending">Open the loans<RowChevron /></Link>}
    </article>
  );
}

/** The cards of the modules the business uses, Shop (with its summary cards) then Lending as in the side menu. */
export function HomeCards() {
  const { me, branch } = useStaff();
  const parts = homeParts(me, branch);
  const shop = parts.sales || parts.lowStock || parts.orders;
  const lendingCard = parts.loans || parts.approvals;
  if (!shop && !lendingCard) return null;
  return (
    <section className="home-cards" aria-label="Your business today">
      {shop && <ShopCards parts={parts} />}
      {lendingCard && <LendingCard parts={parts} />}
    </section>
  );
}
