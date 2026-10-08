import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { retail, type CashDailyRow } from '../../../api/retail';
import { ImportedBadge, WindowFields, useReadScope, useWindow } from './cash-ui';
import { Gate, Problem, money, useBranchName } from './ui';
import { showDate } from './transfers';

// The daily cash summary (FR-RET-27): one card per shop per day, newest first. A line is drawn only when the
// server sent it. Without retail.profit.read the server leaves out the cash restock total and every figure
// net of savings (opening, closing, other movements, savings, expected to bank, the running unbanked total)
// and the day's profit; nothing here ever works one of them out. A day with ledger_basis false came from the
// pilot app and is labelled as imported.

export function Line({ label, minor, strong = false }: { label: string; minor: number | undefined; strong?: boolean }) {
  if (minor === undefined) return null;
  return (
    <p className="rt-row">
      <span>{label}</span>
      {strong ? <strong>{money(minor)}</strong> : <span>{money(minor)}</span>}
    </p>
  );
}

/** The lines of a day that move cash in or out, in the order of the daily summary. */
export function DayCard({ row, shop }: { row: CashDailyRow; shop: string }) {
  const imported = row.ledger_basis === false || row.historical === true;
  const voided = (label: string, minor: number | undefined) => (minor === undefined || minor === 0 ? null : <Line label={label} minor={minor} />);
  return (
    <li className="rt-card">
      <strong>{shop}, {showDate(row.business_date)}</strong>
      {imported && (
        <p className="hint"><ImportedBadge /> These figures come from the old app, not from the books.</p>
      )}
      <Line label="Opening cash" minor={row.opening_minor} />
      <Line label="Cash takings" minor={row.cash_takings_minor} />
      <Line label="Cash sales voided" minor={row.cash_sale_voids_minor} />
      {voided('Expenses voided', row.expense_voids_minor)}
      {voided('Advances voided', row.advance_voids_minor)}
      {voided('Repayments voided', row.repayment_voids_minor)}
      {voided('Savings voided', row.savings_voids_minor)}
      {voided('Banking voided', row.banking_voids_minor)}
      {voided('Withdrawals voided', row.withdrawal_voids_minor)}
      <Line label="Cash restocks, at cost" minor={row.cash_purchases_minor} />
      <Line label="Savings set aside" minor={row.savings_minor} />
      <Line label="Expenses" minor={row.expenses_minor} />
      <Line label="Advances paid out" minor={row.advances_out_minor} />
      <Line label="Advance repayments received" minor={row.repayments_in_minor} />
      <Line label="Withdrawals from the bank" minor={row.withdrawals_in_minor} />
      <Line label="Banked" minor={row.banked_minor} />
      <Line label="Other movements" minor={row.other_movements_minor} />
      <Line label="Closing cash" minor={row.closing_minor} />
      <Line label="Cash expected" minor={row.cash_expected_minor} strong />
      <Line label="Expected to bank" minor={row.expected_to_bank_minor} strong />
      <Line label="Unbanked so far" minor={row.unbanked_running_minor} />
      <Line label="Profit for the day" minor={row.daily_profit_minor} />
    </li>
  );
}

export function DaysList({ rows }: { rows: CashDailyRow[] }) {
  const shopName = useBranchName();
  if (rows.length === 0) return <p className="empty-state">No cash recorded in this period.</p>;
  const newestFirst = [...rows].sort((a, b) => (b.business_date ?? '').localeCompare(a.business_date ?? '') || shopName(a.branch_id).localeCompare(shopName(b.branch_id)));
  return (
    <ul style={{ listStyle: 'none', padding: 0 }}>
      {newestFirst.map((r) => <DayCard key={`${r.branch_id}-${r.business_date}`} row={r} shop={shopName(r.branch_id)} />)}
    </ul>
  );
}

function CashSummary() {
  const scope = useReadScope();
  const window = useWindow(7);
  const report = useQuery({
    queryKey: ['retail', 'cash-daily', scope, window.from, window.to],
    queryFn: () => retail.cashDaily({ branchIds: scope, ...window.query }),
  });
  return (
    <Gate screen="cashSummary" title="Cash summary">
      <WindowFields id="cash-summary" window={window} />
      {report.isError && <Problem error={report.error} />}
      {report.isPending && <p className="loading">Loading</p>}
      {report.data && <DaysList rows={report.data.items ?? []} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/cash-summary')({ component: CashSummary });
