import { useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { investments, type Investment, type InvestmentSchedule, type InvestmentStatementLine } from '../../../api/investments';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { EarlyWithdrawalForm, FundForm, InstructionForm, PayForm, ReverseForm, RolloverForm } from './investment-actions';
import {
  actionsFor,
  canReverse,
  instructionWords,
  investmentBadge,
  investmentTxnWords,
  payoutWords,
  percent,
  type InvestmentAction,
} from './investment-state';
import { InvestmentsGate } from './investments';
import { methodWords, money, words } from './loan-state';

// One investment (FR-INV-03 to FR-INV-11): its terms and balances, the actions the session may
// take on it in this status, the accrual and payout schedule, and the statement of every money
// event with the balances after it. The server checks every action again.

const ACTION_LABELS: Record<InvestmentAction, string> = {
  fund: 'Record funding',
  pay_return: 'Pay return due',
  payout: 'Pay out',
  rollover: 'Roll over',
  early: 'Early withdrawal',
  instruct: 'Maturity choice',
};

export function InvestmentHeader({ inv }: { inv: Investment }) {
  return (
    <section aria-label="Investment" className="rt-card ln-head">
      <p className="ln-item-head">
        <strong>{inv.account_no}</strong>
        <span className={investmentBadge(inv.status)}>{words(inv.status)}</span>
      </p>
      <p>
        <Link to="/staff/lending/investments/member/$memberId" params={{ memberId: inv.member_id ?? '' }}>
          {inv.member_name} ({inv.member_no})
        </Link>
      </p>
      <p className="ln-muted">
        {inv.product_name}: {percent(inv.return_rate_bp)} a year, {inv.return_method === 'compound' ? 'compounding monthly' : 'flat'}, paid{' '}
        {payoutWords(inv.payout_frequency).toLowerCase()}, {inv.term_months} months
      </p>
      {inv.pending_approval_id && <p><span className="badge badge-warning">Waiting for a checker</span></p>}
    </section>
  );
}

const date = (d: string | undefined) => d ?? 'None';

export function InvestmentBalances({ inv }: { inv: Investment }) {
  const c = inv.currency;
  return (
    <section aria-labelledby="iv-balances" className="rt-card">
      <h2 id="iv-balances">Balances</h2>
      <dl className="ln-facts">
        <dt>Amount invested</dt>
        <dd>{money(inv.principal_minor, c)}</dd>
        <dt>Principal held now</dt>
        <dd>{money(inv.principal_held_minor, c)}</dd>
        <dt>Agreed return</dt>
        <dd>{money(inv.agreed_return_minor, c)}</dd>
        <dt>Return accrued</dt>
        <dd>{money(inv.return_accrued_minor, c)}</dd>
        <dt>Return paid</dt>
        <dd>{money(inv.return_paid_minor, c)}</dd>
        <dt className="ln-strong">Return due now</dt>
        <dd className="ln-strong">{money(inv.return_available_minor, c)}</dd>
        <dt>Started</dt>
        <dd>{date(inv.start_date)}</dd>
        <dt>Matures</dt>
        <dd>{date(inv.maturity_date)}</dd>
        <dt>At maturity</dt>
        <dd>{instructionWords(inv.maturity_instruction)}</dd>
        <dt>Early withdrawal</dt>
        <dd>
          {inv.early_withdrawal_allowed
            ? `${inv.early_withdrawal_rule === 'reduced_rate' ? `At ${percent(inv.early_withdrawal_rate_bp)}` : 'Return forfeited'}, penalty ${percent(inv.early_withdrawal_penalty_bp)}`
            : 'Not allowed'}
        </dd>
        {inv.certificate_no && (
          <>
            <dt>Certificate</dt>
            <dd>{inv.certificate_no}</dd>
          </>
        )}
        {inv.closed_on && (
          <>
            <dt>Closed on</dt>
            <dd>{inv.closed_on}</dd>
          </>
        )}
      </dl>
    </section>
  );
}

export function InvestmentScheduleTable({ schedule }: { schedule: InvestmentSchedule }) {
  const c = schedule.currency;
  const rows = schedule.periods ?? [];
  if (rows.length === 0) return <p className="empty-state">The schedule is set when the investment is funded.</p>;
  return (
    <div className="table-wrap" tabIndex={0} role="region" aria-labelledby="iv-schedule">
      <table>
        <thead>
          <tr>
            <th className="num">Month</th>
            <th>Ends</th>
            <th className="num">On</th>
            <th className="num">Return</th>
            <th className="num">To date</th>
            <th>Paid out</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.period_no}>
              <td className="num">{r.period_no}</td>
              <td>{r.period_end}</td>
              <td className="num">{money(r.opening_balance_minor, c)}</td>
              <td className="num">{money(r.return_minor, c)}</td>
              <td className="num">{money(r.cumulative_return_minor, c)}</td>
              <td>{r.payout ? 'Yes' : ''}</td>
              <td>{words(r.status)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td colSpan={3}>Total</td>
            <td className="num">{money(schedule.total_return_minor, c)}</td>
            <td colSpan={3} />
          </tr>
        </tfoot>
      </table>
    </div>
  );
}

export function StatementList({ inv, lines }: { inv: Investment; lines: InvestmentStatementLine[] }) {
  const { me } = useStaff();
  const [reversing, setReversing] = useState<string | null>(null);
  if (lines.length === 0) return <p className="empty-state">No money has moved on this investment yet.</p>;
  return (
    <ul className="ln-list">
      {[...lines].reverse().map((l) => {
        const t = l.transaction ?? {};
        return (
          <li key={t.id} className="rt-card">
            <p className="ln-item-head">
              <strong>{investmentTxnWords(t.txn_type)}</strong>
              <span className="num">{money(t.amount_minor, inv.currency)}</span>
            </p>
            <p className="ln-muted">
              {t.value_date}
              {t.receipt_no && `, ${t.receipt_no}`}
              {t.payment_method_key && `, ${methodWords(t.payment_method_key)}`}
              {t.period_no && `, month ${t.period_no}`}
            </p>
            <p className="ln-muted">
              After it: principal {money(l.principal_balance_minor, inv.currency)}, return payable {money(l.return_payable_minor, inv.currency)}
            </p>
            {(t.penalty_minor ?? 0) > 0 && <p className="ln-muted">Penalty {money(t.penalty_minor, inv.currency)}</p>}
            {t.reversed_by_txn_id && <p><span className="badge badge-danger">Reversed</span></p>}
            {t.reason && <p className="ln-muted">Reason: {t.reason}</p>}
            {canReverse(me, t) &&
              (reversing === t.id ? (
                <ReverseForm inv={inv} txn={t} />
              ) : (
                <button type="button" onClick={() => setReversing(t.id ?? null)} aria-label={`Reverse ${investmentTxnWords(t.txn_type)} ${t.receipt_no ?? ''}`}>
                  Reverse
                </button>
              ))}
          </li>
        );
      })}
    </ul>
  );
}

export function InvestmentView({ inv }: { inv: Investment }) {
  const { me } = useStaff();
  const navigate = useNavigate();
  const id = inv.id ?? '';
  const [open, setOpen] = useState<InvestmentAction | null>(null);
  const funded = inv.status !== 'pending_funding' && inv.status !== 'cancelled';
  const schedule = useQuery({ queryKey: ['lending', 'investment', id, 'schedule'], queryFn: () => investments.schedule(id), enabled: funded });
  const statement = useQuery({ queryKey: ['lending', 'investment', id, 'statement'], queryFn: () => investments.statement(id) });
  const actions = actionsFor(me, inv);
  return (
    <>
      <InvestmentHeader inv={inv} />
      {actions.length > 0 && (
        <div className="ln-actions" role="group" aria-label="Actions">
          {actions.map((a) => (
            <button key={a} type="button" aria-pressed={open === a} onClick={() => setOpen(open === a ? null : a)}>
              {ACTION_LABELS[a]}
            </button>
          ))}
        </div>
      )}
      {open && actions.includes(open) && (
        <section aria-label={ACTION_LABELS[open]} className="rt-card">
          <h2>{ACTION_LABELS[open]}</h2>
          {open === 'fund' && <FundForm inv={inv} />}
          {(open === 'pay_return' || open === 'payout') && <PayForm inv={inv} kind={open} />}
          {open === 'rollover' && (
            <RolloverForm inv={inv} onDone={(next) => void navigate({ to: '/staff/lending/investments/$investmentId', params: { investmentId: next } })} />
          )}
          {open === 'early' && <EarlyWithdrawalForm inv={inv} />}
          {open === 'instruct' && <InstructionForm inv={inv} />}
        </section>
      )}
      <InvestmentBalances inv={inv} />
      {inv.certificate_no && (
        <Link className="btn btn-block" to="/staff/lending/investments/$investmentId/certificate" params={{ investmentId: id }}>
          Certificate and statement to print
        </Link>
      )}
      {inv.rolled_over_to_id && (
        <Link className="btn btn-ghost" to="/staff/lending/investments/$investmentId" params={{ investmentId: inv.rolled_over_to_id }}>
          Go to the investment it rolled into
        </Link>
      )}
      <h2 id="iv-schedule">Schedule</h2>
      {!funded ? (
        <p className="empty-state">The schedule is set when the investment is funded.</p>
      ) : (
        <>
          {schedule.isPending && <p className="loading">Loading the schedule</p>}
          <Problem error={schedule.error} />
          {schedule.data && <InvestmentScheduleTable schedule={schedule.data} />}
        </>
      )}
      <h2>Statement</h2>
      {statement.isPending && <p className="loading">Loading the statement</p>}
      <Problem error={statement.error} />
      {statement.data && <StatementList inv={inv} lines={statement.data.lines ?? []} />}
    </>
  );
}

const route = getRouteApi('/staff/lending/investments/$investmentId');

function InvestmentPage() {
  const { investmentId } = route.useParams();
  const inv = useQuery({ queryKey: ['lending', 'investment', investmentId], queryFn: () => investments.get(investmentId) });
  return (
    <InvestmentsGate title="Investment">
      {inv.isPending && <p className="loading">Loading the investment</p>}
      <Problem error={inv.error} />
      {inv.data && <InvestmentView inv={inv.data} />}
    </InvestmentsGate>
  );
}

export const Route = createLazyRoute('/staff/lending/investments/$investmentId')({ component: InvestmentPage });
