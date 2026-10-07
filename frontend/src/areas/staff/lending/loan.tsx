import { useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { lending, type Loan, type LoanSchedule, type LoanTransaction, type RepaymentResult } from '../../../api/lending';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { DisburseForm, PayoffQuotePanel, RepaymentForm, RepaymentReceipt, ReverseForm, WriteOffForm } from './actions';
import { LendingGate } from './loans';
import { methodWords, money, statusBadge, txnWords, words } from './loan-state';
import { actionsFor, canReverse, type LoanAction } from './permissions';

// One loan (FR-DIS-04, FR-REP): its status and balances, the schedule with its totals row, and
// its money events. The actions the session may take on a loan in this status sit under the
// header; each opens its form in place. The server checks every action again.

const ACTION_LABELS: Record<LoanAction, string> = {
  disburse: 'Disburse',
  repay: 'Record repayment',
  payoff: 'Payoff quote',
  write_off: 'Request write-off',
  reverse: 'Reverse',
};

const MEMBERS_READ = 'lending.members.read';

export function LoanHeader({ loan, memberName }: { loan: Loan; memberName?: string }) {
  return (
    <section aria-label="Loan" className="rt-card ln-head">
      <p className="ln-item-head">
        <strong>{loan.loan_no}</strong>
        <span className={statusBadge(loan.status)}>{words(loan.status)}</span>
      </p>
      <p>
        {memberName ? `${memberName} (${loan.member_no ?? ''})` : `Member ${loan.member_no ?? ''}`}
      </p>
      <p className="ln-muted">
        Product {loan.product_code}, principal {money(loan.approved_principal_minor ?? loan.requested_principal_minor, loan.currency)}
      </p>
    </section>
  );
}

const date = (d: string | undefined) => d ?? 'None';

export function BalancesCard({ loan }: { loan: Loan }) {
  const b = loan.balances;
  const c = loan.currency;
  if (!b) return <p className="empty-state">No balances yet: the loan has not been disbursed.</p>;
  const dpd = b.days_past_due ?? 0;
  return (
    <section aria-labelledby="ln-balances" className="rt-card">
      <h2 id="ln-balances">Balances</h2>
      <dl className="ln-facts">
        <dt>Principal outstanding</dt>
        <dd>{money(b.principal_outstanding_minor, c)}</dd>
        <dt>Interest outstanding</dt>
        <dd>{money(b.interest_outstanding_minor, c)}</dd>
        <dt>Fees outstanding</dt>
        <dd>{money(b.fees_outstanding_minor, c)}</dd>
        <dt>Penalties outstanding</dt>
        <dd>{money(b.penalties_outstanding_minor, c)}</dd>
        <dt className="ln-strong">Total outstanding</dt>
        <dd className="ln-strong">{money(b.total_outstanding_minor, c)}</dd>
        <dt>Arrears</dt>
        <dd>{(b.arrears_minor ?? 0) > 0 ? <span className="rt-flag">{money(b.arrears_minor, c)}</span> : money(b.arrears_minor ?? 0, c)}</dd>
        <dt>Days past due</dt>
        <dd>{dpd > 0 ? <span className="rt-flag">{dpd}</span> : '0'}</dd>
        <dt>Next due date</dt>
        <dd>{date(b.next_due_date)}</dd>
        <dt>Total paid</dt>
        <dd>{money(b.total_paid_minor ?? 0, c)}</dd>
        <dt>Credit balance</dt>
        <dd>{money(b.credit_balance_minor ?? 0, c)}</dd>
        <dt>Disbursed on</dt>
        <dd>{date(b.disbursed_on)}</dd>
        <dt>Maturity</dt>
        <dd>{date(b.maturity_date)}</dd>
        {b.closed_on && (
          <>
            <dt>Closed on</dt>
            <dd>{b.closed_on}</dd>
          </>
        )}
        {b.written_off_on && (
          <>
            <dt>Written off on</dt>
            <dd>{b.written_off_on}</dd>
          </>
        )}
      </dl>
    </section>
  );
}

export function ScheduleTable({ schedule, currency }: { schedule: LoanSchedule; currency: string | undefined }) {
  const c = schedule.currency ?? currency;
  const rows = schedule.items ?? [];
  const t = schedule.totals;
  if (rows.length === 0) return <p className="empty-state">No schedule yet.</p>;
  return (
    <div className="table-wrap" tabIndex={0} role="region" aria-labelledby="ln-schedule">
      <table>
        <thead>
          <tr>
            <th className="num">No</th>
            <th>Due date</th>
            <th className="num">Principal</th>
            <th className="num">Interest</th>
            <th className="num">Fees</th>
            <th className="num">Total due</th>
            <th className="num">Paid</th>
            <th className="num">Outstanding</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.no}>
              <td className="num">{r.no}</td>
              <td>{r.due_date}</td>
              <td className="num">{money(r.principal_due_minor, c)}</td>
              <td className="num">{money(r.interest_due_minor, c)}</td>
              <td className="num">{money(r.fees_due_minor, c)}</td>
              <td className="num">{money(r.total_due_minor, c)}</td>
              <td className="num">{money(r.total_paid_minor, c)}</td>
              <td className="num">{money(r.outstanding_minor, c)}</td>
              <td>{r.status === 'overdue' ? <span className="rt-flag">Overdue</span> : words(r.status)}</td>
            </tr>
          ))}
        </tbody>
        {t && (
          <tfoot>
            <tr>
              <td colSpan={2}>Totals</td>
              <td className="num">{money(t.principal_due_minor, c)}</td>
              <td className="num">{money(t.interest_due_minor, c)}</td>
              <td className="num">{money(t.fees_due_minor, c)}</td>
              <td className="num">{money(t.total_due_minor, c)}</td>
              <td className="num">{money(t.total_paid_minor, c)}</td>
              <td className="num">{money(t.outstanding_minor, c)}</td>
              <td />
            </tr>
          </tfoot>
        )}
      </table>
    </div>
  );
}

export function TransactionList({ loan, items }: { loan: Loan; items: LoanTransaction[] }) {
  const { me } = useStaff();
  const [reversing, setReversing] = useState<string | null>(null);
  if (items.length === 0) return <p className="empty-state">No money has moved on this loan yet.</p>;
  return (
    <ul className="ln-list">
      {items.map((t) => (
        <li key={t.id} className="rt-card">
          <p className="ln-item-head">
            <strong>{txnWords(t.txn_type)}</strong>
            <span className="num">{money(t.amount_minor, t.currency ?? loan.currency)}</span>
          </p>
          <p className="ln-muted">
            {t.value_date}
            {t.receipt_no && `, ${t.receipt_no}`}
            {t.payment_method_key && `, ${methodWords(t.payment_method_key)}`}
            {t.external_reference && `, ref ${t.external_reference}`}
          </p>
          {t.reversed_by_txn_id && <p><span className="badge badge-danger">Reversed</span></p>}
          {t.reason && <p className="ln-muted">Reason: {t.reason}</p>}
          {canReverse(me, loan.status, t) &&
            (reversing === t.id ? (
              <ReverseForm loanId={loan.id ?? ''} txn={t} />
            ) : (
              <button type="button" onClick={() => setReversing(t.id ?? null)} aria-label={`Reverse ${t.receipt_no ?? 'this payment'}`}>
                Reverse
              </button>
            ))}
        </li>
      ))}
    </ul>
  );
}

/** The loan page body, given the loan; the schedule and transactions load beside it. */
export function LoanView({ loan, memberName }: { loan: Loan; memberName?: string }) {
  const { me } = useStaff();
  const loanId = loan.id ?? '';
  const [open, setOpen] = useState<LoanAction | null>(null);
  const [receipt, setReceipt] = useState<RepaymentResult | null>(null);
  const [round, setRound] = useState(0);
  const schedule = useQuery({ queryKey: ['lending', 'loan', loanId, 'schedule'], queryFn: () => lending.getSchedule(loanId), enabled: loan.status !== 'approved' });
  const txns = useQuery({ queryKey: ['lending', 'loan', loanId, 'transactions'], queryFn: () => lending.listTransactions(loanId) });
  const actions = actionsFor(me, loan.status);

  function choose(a: LoanAction) {
    setOpen(open === a ? null : a);
    setReceipt(null);
  }

  return (
    <>
      <LoanHeader loan={loan} memberName={memberName} />
      {actions.length > 0 && (
        <div className="ln-actions" role="group" aria-label="Actions">
          {actions.map((a) => (
            <button key={a} type="button" aria-pressed={open === a} onClick={() => choose(a)}>
              {ACTION_LABELS[a]}
            </button>
          ))}
        </div>
      )}
      {open && (
        <section aria-label={ACTION_LABELS[open]} className="rt-card">
          <h2>{ACTION_LABELS[open]}</h2>
          {open === 'repay' &&
            (receipt ? (
              <RepaymentReceipt result={receipt} currency={loan.currency} onNew={() => { setReceipt(null); setRound((n) => n + 1); }} />
            ) : (
              <RepaymentForm key={round} loan={loan} onDone={setReceipt} />
            ))}
          {open === 'disburse' && <DisburseForm loan={loan} />}
          {open === 'payoff' && <PayoffQuotePanel loan={loan} />}
          {open === 'write_off' && <WriteOffForm loan={loan} />}
        </section>
      )}
      {!open && receipt && <RepaymentReceipt result={receipt} currency={loan.currency} />}
      <BalancesCard loan={loan} />
      <h2 id="ln-schedule">Schedule</h2>
      {loan.status === 'approved' ? (
        <p className="empty-state">The schedule is set when the loan is disbursed.</p>
      ) : (
        <>
          {schedule.isPending && <p className="loading">Loading the schedule</p>}
          <Problem error={schedule.error} />
          {schedule.data && <ScheduleTable schedule={schedule.data} currency={loan.currency} />}
        </>
      )}
      <h2>Transactions</h2>
      {txns.isPending && <p className="loading">Loading transactions</p>}
      <Problem error={txns.error} />
      {txns.data && <TransactionList loan={loan} items={txns.data} />}
    </>
  );
}

const route = getRouteApi('/staff/lending/loans/$loanId');

function LoanPage() {
  const { loanId } = route.useParams();
  const { me } = useStaff();
  const loan = useQuery({ queryKey: ['lending', 'loan', loanId], queryFn: () => lending.getLoan(loanId) });
  const memberId = loan.data?.member_id;
  const member = useQuery({
    queryKey: ['lending', 'member-name', memberId],
    queryFn: () => lending.getMemberName(memberId ?? ''),
    enabled: !!memberId && (me.permissions ?? []).includes(MEMBERS_READ),
  });
  return (
    <LendingGate title="Loan">
      <Link to="/staff/lending" className="btn btn-ghost">Back to loans</Link>
      {loan.isPending && <p className="loading">Loading the loan</p>}
      <Problem error={loan.error} />
      {loan.data && <LoanView loan={loan.data} memberName={member.data} />}
    </LendingGate>
  );
}

export const Route = createLazyRoute('/staff/lending/loans/$loanId')({ component: LoanPage });
