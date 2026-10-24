import { useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { retail, type CashDailyRow } from '../../../api/retail';
import { useStaff } from '../context';
import { ImportedBadge, WindowFields, useReadScope, useWindow } from './cash-ui';
import { canUse, type RetailScreen } from './permissions';
import { Gate, Problem, money, useBranchName } from './ui';
import { showDate } from './transfers';

// The daily cash summary (FR-RET-27): one card per shop per day, newest first. A line is drawn only when the
// server sent it. Without retail.profit.read the server leaves out the cash restock total and every figure
// net of savings (opening, closing, other movements, savings, expected to bank, the running unbanked total)
// and the day's profit; nothing here ever works one of them out. A day with ledger_basis false came from the
// pilot app and is labelled as imported.

/** Where a figure of the summary can be read row by row; a figure whose screen the session may not open has no link. */
export interface DayLinks { sales?: string; expenses?: string; advances?: string; withdrawals?: string; banked?: string; savings?: string }

export function Line({ label, minor, strong = false, to }: { label: string; minor: number | undefined; strong?: boolean; to?: string }) {
  if (minor === undefined) return null;
  return (
    <p className="rt-row">
      <span>{to ? <Link to={to as never} aria-label={`${label}: see the rows`}>{label}</Link> : label}</span>
      {strong ? <strong>{money(minor)}</strong> : <span>{money(minor)}</span>}
    </p>
  );
}

/** The lines of a day that move cash in or out, in the order of the daily summary. */
export function DayCard({ row, shop, links = {} }: { row: CashDailyRow; shop: string; links?: DayLinks }) {
  const imported = row.ledger_basis === false || row.historical === true;
  const voided = (label: string, minor: number | undefined) => (minor === undefined || minor === 0 ? null : <Line label={label} minor={minor} />);
  return (
    <li className="rt-card">
      <strong>{shop}, {showDate(row.business_date)}</strong>
      {imported && (
        <p className="hint"><ImportedBadge /> These figures come from the old app, not from the books.</p>
      )}
      <Line label="Opening cash" minor={row.opening_minor} />
      <Line label="Cash takings" minor={row.cash_takings_minor} to={links.sales} />
      <Line label="Cash sales voided or refunded" minor={row.cash_sale_voids_minor} />
      {voided('Expenses voided', row.expense_voids_minor)}
      {voided('Advances voided', row.advance_voids_minor)}
      {voided('Repayments voided', row.repayment_voids_minor)}
      {voided('Savings voided', row.savings_voids_minor)}
      {voided('Banking voided', row.banking_voids_minor)}
      {voided('Withdrawals voided', row.withdrawal_voids_minor)}
      <Line label="Stock bought with cash, at cost" minor={row.cash_purchases_minor} />
      <Line label="Savings set aside" minor={row.savings_minor} to={links.savings} />
      <Line label="Expenses" minor={row.expenses_minor} to={links.expenses} />
      <Line label="Advances paid out" minor={row.advances_out_minor} to={links.advances} />
      <Line label="Advance repayments received" minor={row.repayments_in_minor} to={links.advances} />
      <Line label="Withdrawals from the bank" minor={row.withdrawals_in_minor} to={links.withdrawals} />
      <Line label="Banked" minor={row.banked_minor} to={links.banked} />
      <Line label="Other movements" minor={row.other_movements_minor} />
      <Line label="Closing cash" minor={row.closing_minor} />
      <Line label="Cash expected" minor={row.cash_expected_minor} strong />
      <Line label="Expected to bank" minor={row.expected_to_bank_minor} strong />
      <Line label="Unbanked so far" minor={row.unbanked_running_minor} />
      <Line label="Profit for the day" minor={row.daily_profit_minor} />
    </li>
  );
}

export function DaysList({ rows, links }: { rows: CashDailyRow[]; links?: DayLinks }) {
  const shopName = useBranchName();
  if (rows.length === 0) return <p className="empty-state">No cash recorded in this period.</p>;
  const newestFirst = [...rows].sort((a, b) => (b.business_date ?? '').localeCompare(a.business_date ?? '') || shopName(a.branch_id).localeCompare(shopName(b.branch_id)));
  return (
    <ul style={{ listStyle: 'none', padding: 0 }}>
      {newestFirst.map((r) => <DayCard key={`${r.branch_id}-${r.business_date}`} row={r} shop={shopName(r.branch_id)} links={links} />)}
    </ul>
  );
}

export function CashSummary() {
  const { me } = useStaff();
  const scope = useReadScope();
  const window = useWindow(7);
  const report = useQuery({
    queryKey: ['retail', 'cash-daily', scope, window.from, window.to],
    queryFn: () => retail.cashDaily({ branchIds: scope, ...window.query }),
  });
  const reach = (screen: RetailScreen, to: string) => (canUse(me, screen) ? to : undefined);
  const links: DayLinks = {
    sales: reach('salesHistory', '/staff/retail/sales'),
    expenses: reach('expensesReport', '/staff/retail/expenses-report'),
    advances: reach('advances', '/staff/retail/advances'),
    withdrawals: reach('withdrawals', '/staff/retail/withdrawals'),
    banked: reach('bankingReport', '/staff/retail/banking-report'),
    savings: reach('savings', '/staff/retail/savings'),
  };
  return (
    <Gate screen="cashSummary" title="Cash summary">
      <div data-tour="cash-window"><WindowFields id="cash-summary" window={window} /></div>
      {report.isError && <Problem error={report.error} />}
      {report.isPending && <p className="loading">Loading</p>}
      <div data-tour="cash-days">
        {report.data && <DaysList rows={report.data.items ?? []} links={links} />}
      </div>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/cash-summary')({ component: CashSummary });
