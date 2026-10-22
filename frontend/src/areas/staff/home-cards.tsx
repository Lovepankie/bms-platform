import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { listPendingApprovals, type Me } from '../../api/client';
import { lending } from '../../api/lending';
import { retailAnalytics } from '../../api/retail-analytics';
import { retailOrders } from '../../api/retail-orders';
import { ALL_BRANCHES, branchFilter } from '../../auth/branch';
import { icons } from '../../components/icons';
import { useStaff } from './context';
import { money as lendingMoney } from './lending/loan-state';
import { showLending } from './lending/permissions';
import { ALL_SELLERS, canUse, holds, holdsAt, showRetail } from './retail/permissions';
import { money as retailMoney, useBranchView } from './retail/ui';

// Home's card per module the business uses (issue #471, ADR-072): Shop with today's sales, the items low on stock and the open
// customer orders; Lending with the loans due today, those in arrears and the lending requests waiting for approval. A business with
// one module sees only its card. Each figure is shown only to a session whose permissions the endpoint behind it requires: the retail
// dashboard (every seller's sales, so retail.sale.all_sellers too, ADR-055; its stock part needs retail.stock.read), the orders board
// (retail.order.manage), the loans summary (lending.loans.read) and the approvals inbox (core.approvals.read).

/**
 * The approval actions of the lending module (each action's TYPE in the backend's lending packages, checked by a test), asked of the
 * inbox as its action_type filter so the Lending card counts only its own requests.
 */
export const LENDING_APPROVALS = [
  'collateral_release', 'loan_disbursement', 'repayment_reversal', 'loan_write_off', 'charge_waiver', 'savings_reversal',
  'savings_withdrawal', 'investment_funding', 'investment_early_withdrawal', 'investment_reversal',
];

/**
 * Which figures each card shows the session in the active branch; a card with none is not drawn. The shop total needs
 * retail.sale.all_sellers in the branch chosen (ADR-055); over every branch the server keeps to the shops the session supervises.
 */
export function homeParts(me: Me, branch: string | null = null) {
  const permissions = me.permissions ?? [];
  const retail = showRetail(me);
  const one = branch && branch !== ALL_BRANCHES ? branch : null;
  const sales = retail && canUse(me, 'salesHistory') && (one ? holdsAt(me, ALL_SELLERS, one) : holds(me, ALL_SELLERS));
  const lendingOn = !me.modules || me.modules.includes('lending');
  return {
    sales,
    lowStock: sales && (one ? holdsAt(me, 'retail.stock.read', one) : holds(me, 'retail.stock.read')),
    orders: retail && canUse(me, 'orders'),
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

function ShopCard({ parts }: { parts: ReturnType<typeof homeParts> }) {
  const { branch } = useStaff();
  const branchId = branch && branch !== ALL_BRANCHES ? branch : undefined;
  // The dashboard's own query for the range Today, so Home and the dashboard share one answer. The endpoint has no today-only
  // form: it always reads the last 30 days (ADR-072).
  const view = useBranchView();
  const dashboard = useQuery({
    queryKey: ['retail', 'dashboard', view.all ? 'all' : view.branchId, 'today', '', ''],
    queryFn: () => retailAnalytics.dashboard({ branchId: view.all ? undefined : (view.branchId ?? ''), period: 'today' }),
    enabled: parts.sales && (view.all || view.branchId !== null),
  });
  const board = useQuery({
    queryKey: ['retail', 'orders', 'board', branchId ?? 'all'],
    queryFn: ({ signal }) => retailOrders.board(branchId, signal),
    enabled: parts.orders && branch !== null,
  });
  const today = dashboard.data?.today;
  const open = (board.data?.ordered ?? 0) + (board.data?.packed ?? 0) + (board.data?.out_for_delivery ?? 0);
  return (
    <article className="card home-card" aria-labelledby="home-shop">
      <h2 id="home-shop" className="card-title">{icons.stock}<span>Shop</span></h2>
      <dl className="home-figures">
        {parts.sales && (
          <Figure label="Sales today" query={dashboard}>
            {() => <>{retailMoney(today?.sales_minor ?? 0)} <span className="hint">{plural(today?.sale_count ?? 0, 'sale', 'sales')}</span></>}
          </Figure>
        )}
        {parts.lowStock && <Figure label="Low on stock" query={dashboard}>{() => plural(dashboard.data?.stock?.low_stock ?? 0, 'item', 'items')}</Figure>}
        {parts.orders && <Figure label="Open customer orders" query={board}>{() => open}</Figure>}
      </dl>
      <Link to="/staff/retail">Open the shop</Link>
    </article>
  );
}

function LendingCard({ parts }: { parts: ReturnType<typeof homeParts> }) {
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
      {parts.loans && <Link to="/staff/lending">Open the loans</Link>}
    </article>
  );
}

/** The cards of the modules the business uses, Shop then Lending as in the side menu. */
export function HomeCards() {
  const { me, branch } = useStaff();
  const parts = homeParts(me, branch);
  const shop = parts.sales || parts.orders;
  const lendingCard = parts.loans || parts.approvals;
  if (!shop && !lendingCard) return null;
  return (
    <section className="home-cards" aria-label="Your business today">
      {shop && <ShopCard parts={parts} />}
      {lendingCard && <LendingCard parts={parts} />}
    </section>
  );
}
