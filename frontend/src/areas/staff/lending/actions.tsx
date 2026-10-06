import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { PAYMENT_METHODS, lending, type Loan, type LoanTransaction, type PaymentMethod, type RepaymentResult } from '../../../api/lending';
import { useStaff } from '../context';
import { usePersistedDraft } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { allocationSummary, isIsoDate, localDate, money, parseAmount, words } from './loan-state';

// The money-moving forms of the loan page: repayment, disbursement, reversal and write-off, plus
// the payoff quote. Each write keeps its draft and one Idempotency-Key in this tab until the
// server answers with success (retail/idempotency.ts), so a retry or reload posts it once. After
// a write the loan, its schedule and its transactions are fetched again.

function useRefreshLoan(loanId: string) {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: ['lending', 'loan', loanId] });
    void queryClient.invalidateQueries({ queryKey: ['lending', 'loans'] });
  };
}

function MethodChoice({ name, value, onChange }: { name: string; value: string; onChange: (m: PaymentMethod) => void }) {
  return (
    <fieldset>
      <legend>Payment method</legend>
      {PAYMENT_METHODS.map((m) => (
        <label key={m.value}>
          <input type="radio" name={name} checked={value === m.value} onChange={() => onChange(m.value)} />
          {m.label}
        </label>
      ))}
    </fieldset>
  );
}

function ReferenceInput({ id, value, onChange }: { id: string; value: string; onChange: (v: string) => void }) {
  return (
    <>
      <label htmlFor={id}>Reference (optional)</label>
      <input id={id} maxLength={100} value={value} onChange={(e) => onChange(e.target.value)} autoComplete="off" />
      <p className="field-hint">The bank or mobile money transaction number, if there is one.</p>
    </>
  );
}

interface RepayDraft { amount: string; valueDate: string; method: PaymentMethod; reference: string }

/** What the server answered for a repayment: the receipt and where the money went. */
export function RepaymentReceipt({ result, currency, onNew }: { result: RepaymentResult; currency: string | undefined; onNew?: () => void }) {
  const t = result.transaction ?? {};
  const rows = allocationSummary(t.allocations);
  return (
    <section aria-label="Repayment recorded" className="rt-card">
      <h2>{t.txn_type === 'recovery' ? 'Recovery recorded' : 'Repayment recorded'}</h2>
      <p>
        Receipt <strong>{t.receipt_no}</strong>, {money(t.amount_minor, currency)} on {t.value_date}.
      </p>
      <div className="table-wrap" tabIndex={0} role="region" aria-label="Where the payment went">
        <table>
          <thead>
            <tr>
              <th>Paid to</th>
              <th className="num">Amount</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.label}>
                <td>{r.label}</td>
                <td className="num">{money(r.amountMinor, currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p>
        Still outstanding: <strong>{money(result.balances?.total_outstanding_minor ?? 0, currency)}</strong>. Loan status:{' '}
        {words(result.loan_status)}.
      </p>
      {onNew && (
        <button type="button" className="rt-primary" onClick={onNew}>
          Record another payment
        </button>
      )}
    </section>
  );
}

export function RepaymentForm({ loan, onDone }: { loan: Loan; onDone: (r: RepaymentResult) => void }) {
  const { me } = useStaff();
  const loanId = loan.id ?? '';
  const refresh = useRefreshLoan(loanId);
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<RepayDraft>(`lending-repay:${me.user_id ?? ''}:${loanId}`, {
    amount: '',
    valueDate: localDate(),
    method: 'cash',
    reference: '',
  });
  const update = (patch: Partial<RepayDraft>) => setDraft((d) => ({ ...d, ...patch }));
  const amountMinor = parseAmount(draft.amount, loan.currency);
  const valid = amountMinor !== null && isIsoDate(draft.valueDate);
  const save = useMutation({
    mutationFn: () =>
      lending.repay(
        loanId,
        { amount_minor: amountMinor ?? 0, value_date: draft.valueDate, payment_method_key: draft.method, external_reference: draft.reference.trim() || undefined },
        key,
      ),
    onSuccess: (r) => {
      finish();
      refresh();
      onDone(r);
    },
  });
  return (
    <form aria-label="Record a repayment" onSubmit={(e) => { e.preventDefault(); if (valid && !save.isPending) save.mutate(); }}>
      {loan.status === 'written_off' && <p className="alert alert-info">This loan is written off: the payment is recorded as a recovery.</p>}
      <label htmlFor="repay-amount">Amount ({loan.currency ?? 'UGX'})</label>
      <input id="repay-amount" inputMode="decimal" value={draft.amount} onChange={(e) => update({ amount: e.target.value })}
        aria-invalid={draft.amount !== '' && amountMinor === null} aria-describedby="repay-amount-hint" />
      <p id="repay-amount-hint" className="field-hint">
        {amountMinor !== null ? `Will record ${money(amountMinor, loan.currency)}` : 'Enter an amount above zero.'}
      </p>
      <label htmlFor="repay-date">Date paid</label>
      <input id="repay-date" type="date" max={localDate()} value={draft.valueDate} onChange={(e) => update({ valueDate: e.target.value })} />
      <MethodChoice name="repay-method" value={draft.method} onChange={(m) => update({ method: m })} />
      <ReferenceInput id="repay-ref" value={draft.reference} onChange={(v) => update({ reference: v })} />
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={!valid || save.isPending}>
        {save.isPending ? 'Saving' : 'Record repayment'}
      </button>
      {draft.amount !== '' && !save.isPending && (
        <button type="button" onClick={() => { discard(); save.reset(); }}>Clear this form</button>
      )}
    </form>
  );
}

interface DisburseDraft { date: string; method: PaymentMethod; reference: string }

export function DisburseForm({ loan }: { loan: Loan }) {
  const { me } = useStaff();
  const loanId = loan.id ?? '';
  const refresh = useRefreshLoan(loanId);
  const { key, draft, setDraft, finish } = usePersistedDraft<DisburseDraft>(`lending-disburse:${me.user_id ?? ''}:${loanId}`, {
    date: localDate(),
    method: 'cash',
    reference: '',
  });
  const update = (patch: Partial<DisburseDraft>) => setDraft((d) => ({ ...d, ...patch }));
  const save = useMutation({
    mutationFn: () =>
      lending.disburse(loanId, { disbursement_date: draft.date, payment_method_key: draft.method, external_reference: draft.reference.trim() || undefined }, key),
    onSuccess: () => {
      finish();
      refresh();
    },
  });
  if (save.data) {
    return save.data.executed ? (
      <p role="status" className="alert alert-success">Disbursed. The loan is now active and its schedule is below.</p>
    ) : (
      <p role="status" className="alert alert-info">Sent for approval. A checker must approve it in the approvals inbox before the money goes out.</p>
    );
  }
  return (
    <form aria-label="Disburse the loan" onSubmit={(e) => { e.preventDefault(); if (isIsoDate(draft.date) && !save.isPending) save.mutate(); }}>
      <p>
        Pays out {money(loan.approved_principal_minor, loan.currency)} to the member.
      </p>
      <label htmlFor="disburse-date">Disbursement date</label>
      <input id="disburse-date" type="date" max={localDate()} value={draft.date} onChange={(e) => update({ date: e.target.value })} />
      <MethodChoice name="disburse-method" value={draft.method} onChange={(m) => update({ method: m })} />
      <ReferenceInput id="disburse-ref" value={draft.reference} onChange={(v) => update({ reference: v })} />
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={!isIsoDate(draft.date) || save.isPending}>
        {save.isPending ? 'Sending' : 'Disburse'}
      </button>
    </form>
  );
}

export function PayoffQuotePanel({ loan }: { loan: Loan }) {
  const loanId = loan.id ?? '';
  const [date, setDate] = useState(localDate());
  const quote = useQuery({
    queryKey: ['lending', 'loan', loanId, 'payoff', date],
    queryFn: () => lending.payoffQuote(loanId, date),
    enabled: isIsoDate(date),
  });
  const q = quote.data;
  const c = q?.currency ?? loan.currency;
  return (
    <section aria-label="Payoff quote">
      <label htmlFor="payoff-date">Pay off on</label>
      <input id="payoff-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
      {quote.isFetching && <p className="loading">Working out the payoff</p>}
      <Problem error={quote.error} />
      {q && (
        <dl className="ln-facts">
          <dt>Principal</dt>
          <dd>{money(q.principal_minor, c)}</dd>
          <dt>Interest</dt>
          <dd>{money(q.interest_minor, c)}</dd>
          <dt>Fees</dt>
          <dd>{money(q.fees_minor, c)}</dd>
          <dt>Penalties</dt>
          <dd>{money(q.penalties_minor, c)}</dd>
          <dt>Rebate</dt>
          <dd>{money(q.rebate_minor === undefined ? undefined : -q.rebate_minor, c)}</dd>
          <dt className="ln-strong">Total to pay off</dt>
          <dd className="ln-strong">{money(q.total_minor, c)}</dd>
        </dl>
      )}
    </section>
  );
}

/** A reason form for an action that a checker approves: a reversal or a write-off. */
function ReasonForm({ id, label, submit, draftName, send }: {
  id: string;
  label: string;
  submit: string;
  draftName: string;
  send: (reason: string, key: string) => Promise<{ executed: boolean }>;
}) {
  const { key, draft, setDraft, finish } = usePersistedDraft<string>(draftName, '');
  const save = useMutation({ mutationFn: () => send(draft.trim(), key), onSuccess: finish });
  if (save.data) {
    return save.data.executed ? (
      <p role="status" className="alert alert-success">Done.</p>
    ) : (
      <p role="status" className="alert alert-info">Sent for approval. It is waiting for a checker in the approvals inbox.</p>
    );
  }
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (draft.trim() !== '' && !save.isPending) save.mutate(); }}>
      <label htmlFor={id}>{label}</label>
      <textarea id={id} rows={2} maxLength={500} value={draft} onChange={(e) => setDraft(e.target.value)} />
      <Problem error={save.error} />
      <button type="submit" className="btn-danger btn-block" disabled={draft.trim() === '' || save.isPending}>
        {save.isPending ? 'Sending' : submit}
      </button>
    </form>
  );
}

export function ReverseForm({ loanId, txn }: { loanId: string; txn: LoanTransaction }) {
  const { me } = useStaff();
  const refresh = useRefreshLoan(loanId);
  return (
    <ReasonForm
      id={`reverse-${txn.id ?? ''}`}
      label={`Why reverse ${txn.receipt_no ?? 'this payment'}?`}
      submit="Request reversal"
      draftName={`lending-reverse:${me.user_id ?? ''}:${txn.id ?? ''}`}
      send={async (reason, key) => {
        const r = await lending.reverse(loanId, txn.id ?? '', reason, key);
        refresh();
        return r;
      }}
    />
  );
}

export function WriteOffForm({ loan }: { loan: Loan }) {
  const { me } = useStaff();
  const loanId = loan.id ?? '';
  const refresh = useRefreshLoan(loanId);
  return (
    <ReasonForm
      id="write-off-reason"
      label="Why write this loan off?"
      submit="Request write-off"
      draftName={`lending-write-off:${me.user_id ?? ''}:${loanId}`}
      send={async (reason, key) => {
        const r = await lending.writeOff(loanId, reason, key);
        refresh();
        return r;
      }}
    />
  );
}
