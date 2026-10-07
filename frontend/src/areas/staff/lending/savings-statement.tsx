import { useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { savings, type SavingsStatement } from '../../../api/savings';
import { Problem } from '../retail/ui';
import { isIsoDate, localDate, money } from './loan-state';
import { SavingsGate } from './savings';

// The account statement (FR-SAV-04): opening balance, every movement in the range by value date
// with its running balance, and the closing balance. Three months to today unless changed.

export function threeMonthsBefore(day: string): string {
  const [y, m, d] = day.split('-').map(Number);
  const back = new Date(Date.UTC(y ?? 1970, (m ?? 1) - 1 - 3, (d ?? 1) + 1));
  return back.toISOString().slice(0, 10);
}

export function StatementTable({ s }: { s: SavingsStatement }) {
  const c = s.currency;
  const lines = s.lines ?? [];
  return (
    <>
      <dl className="ln-facts">
        <dt>Account</dt>
        <dd>{s.account_no}</dd>
        <dt>Member</dt>
        <dd>
          {s.member_name} ({s.member_no})
        </dd>
        <dt>Product</dt>
        <dd>{s.product_name}</dd>
        <dt>From</dt>
        <dd>{s.from}</dd>
        <dt>To</dt>
        <dd>{s.to}</dd>
      </dl>
      <div className="table-wrap" tabIndex={0} role="region" aria-label="Statement lines">
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Details</th>
              <th className="num">Out</th>
              <th className="num">In</th>
              <th className="num">Balance</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>{s.from}</td>
              <td>Opening balance</td>
              <td />
              <td />
              <td className="num">{money(s.opening_balance_minor, c)}</td>
            </tr>
            {lines.map((l) => (
              <tr key={l.seq}>
                <td>{l.value_date}</td>
                <td>
                  {l.description}
                  {l.receipt_no ? ` ${l.receipt_no}` : ''}
                </td>
                <td className="num">{l.debit_minor ? money(l.debit_minor, c) : ''}</td>
                <td className="num">{l.credit_minor ? money(l.credit_minor, c) : ''}</td>
                <td className="num">{money(l.balance_minor, c)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td colSpan={2}>Totals and closing balance</td>
              <td className="num">{money(s.total_debits_minor, c)}</td>
              <td className="num">{money(s.total_credits_minor, c)}</td>
              <td className="num">{money(s.closing_balance_minor, c)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
      {lines.length === 0 && <p className="empty-state">No movements in this range.</p>}
    </>
  );
}

const route = getRouteApi('/staff/lending/savings/accounts/$accountId/statement');

function StatementPage() {
  const { accountId } = route.useParams();
  const today = localDate();
  const [from, setFrom] = useState(threeMonthsBefore(today));
  const [to, setTo] = useState(today);
  const valid = isIsoDate(from) && isIsoDate(to) && from <= to;
  const statement = useQuery({
    queryKey: ['savings', 'account', accountId, 'statement', from, to],
    queryFn: () => savings.statement(accountId, from, to),
    enabled: valid,
  });
  return (
    <SavingsGate title="Statement">
      <Link to="/staff/lending/savings/accounts/$accountId" params={{ accountId }} className="btn btn-ghost">
        Back to the account
      </Link>
      <form className="sv-range" onSubmit={(e) => e.preventDefault()}>
        <div>
          <label htmlFor="sv-from">From</label>
          <input id="sv-from" type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div>
          <label htmlFor="sv-to">To</label>
          <input id="sv-to" type="date" value={to} max={today} onChange={(e) => setTo(e.target.value)} />
        </div>
      </form>
      {!valid && <p className="alert alert-warning">Choose a start date on or before the end date.</p>}
      {statement.isFetching && <p className="loading">Loading the statement</p>}
      <Problem error={statement.error} />
      {statement.data && <StatementTable s={statement.data} />}
    </SavingsGate>
  );
}

export const Route = createLazyRoute('/staff/lending/savings/accounts/$accountId/statement')({ component: StatementPage });
