import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { PAYMENT_METHODS, type PaymentMethod } from '../../../api/lending';
import { investments, type Investment, type InvestmentTransaction, type TransactionResult } from '../../../api/investments';
import { useStaff } from '../context';
import { usePersistedDraft } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { instructionWords, investmentTxnWords, percent } from './investment-state';
import { isIsoDate, localDate, money, words } from './loan-state';

// The forms of the investment page: funding, return payout, maturity payout, rollover, early
// withdrawal (with its quote), the maturity instruction and a reversal. Each write keeps its draft
// and one Idempotency-Key in this tab until the server answers with success
// (retail/idempotency.ts), so a retry or reload posts it once. After a write the investment, its
// schedule and statement are fetched again.

function useRefresh(id: string) {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: ['lending', 'investment', id] });
    void queryClient.invalidateQueries({ queryKey: ['lending', 'investments'] });
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

function Waiting({ executed, done }: { executed: boolean; done: string }) {
  return executed ? (
    <p role="status" className="alert alert-success">{done}</p>
  ) : (
    <p role="status" className="alert alert-info">Sent for approval. A checker must approve it in the approvals inbox.</p>
  );
}

interface FundDraft { date: string; method: PaymentMethod; reference: string }

export function FundForm({ inv }: { inv: Investment }) {
  const { me } = useStaff();
  const id = inv.id ?? '';
  const refresh = useRefresh(id);
  const { key, draft, setDraft, finish } = usePersistedDraft<FundDraft>(`invest-fund:${me.user_id ?? ''}:${id}`, {
    date: localDate(),
    method: 'cash',
    reference: '',
  });
  const update = (patch: Partial<FundDraft>) => setDraft((d) => ({ ...d, ...patch }));
  const save = useMutation({
    mutationFn: () =>
      investments.fund(id, { value_date: draft.date, payment_method_key: draft.method, external_reference: draft.reference.trim() || undefined }, key),
    onSuccess: () => {
      finish();
      refresh();
    },
  });
  if (save.data) return <Waiting executed={save.data.executed} done="Funded. The investment is active and its schedule is below." />;
  return (
    <form aria-label="Record funding" onSubmit={(e) => { e.preventDefault(); if (isIsoDate(draft.date) && !save.isPending) save.mutate(); }}>
      <p>
        Receives {money(inv.principal_minor, inv.currency)} from the member. Above the business's approval limit a checker
        approves it first.
      </p>
      <label htmlFor="fund-date">Date received</label>
      <input id="fund-date" type="date" max={localDate()} value={draft.date} onChange={(e) => update({ date: e.target.value })} />
      <MethodChoice name="fund-method" value={draft.method} onChange={(m) => update({ method: m })} />
      <label htmlFor="fund-ref">Reference (optional)</label>
      <input id="fund-ref" maxLength={100} value={draft.reference} onChange={(e) => update({ reference: e.target.value })} autoComplete="off" />
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={!isIsoDate(draft.date) || save.isPending}>
        {save.isPending ? 'Saving' : 'Record funding'}
      </button>
    </form>
  );
}

/** A cash payout to the member: the due return, or principal and return at maturity. */
export function PayForm({ inv, kind }: { inv: Investment; kind: 'pay_return' | 'payout' }) {
  const { me } = useStaff();
  const id = inv.id ?? '';
  const refresh = useRefresh(id);
  const { key, draft, setDraft, finish } = usePersistedDraft<{ method: PaymentMethod; reference: string }>(
    `invest-${kind}:${me.user_id ?? ''}:${id}`,
    { method: 'cash', reference: '' },
  );
  const amount =
    kind === 'pay_return'
      ? inv.return_available_minor ?? 0
      : (inv.principal_held_minor ?? 0) + (inv.return_accrued_minor ?? 0) - (inv.return_paid_minor ?? 0);
  const save = useMutation<TransactionResult>({
    mutationFn: () => {
      const body = { payment_method_key: draft.method, external_reference: draft.reference.trim() || undefined };
      return kind === 'pay_return' ? investments.payReturn(id, body, key) : investments.payout(id, body, key);
    },
    onSuccess: () => {
      finish();
      refresh();
    },
  });
  if (save.data) {
    const t = save.data.transaction ?? {};
    return (
      <p role="status" className="alert alert-success">
        Paid {money(t.amount_minor, inv.currency)}, voucher {t.receipt_no}.
      </p>
    );
  }
  return (
    <form aria-label={kind === 'pay_return' ? 'Pay the return due' : 'Pay out at maturity'} onSubmit={(e) => { e.preventDefault(); if (!save.isPending) save.mutate(); }}>
      <p>
        Pays <strong>{money(amount, inv.currency)}</strong> to the member today.
      </p>
      <MethodChoice name={`${kind}-method`} value={draft.method} onChange={(m) => setDraft((d) => ({ ...d, method: m }))} />
      <label htmlFor={`${kind}-ref`}>Reference (optional)</label>
      <input id={`${kind}-ref`} maxLength={100} value={draft.reference} onChange={(e) => setDraft((d) => ({ ...d, reference: e.target.value }))} autoComplete="off" />
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={save.isPending}>
        {save.isPending ? 'Paying' : 'Pay now'}
      </button>
    </form>
  );
}

export function RolloverForm({ inv, onDone }: { inv: Investment; onDone: (nextId: string) => void }) {
  const { me } = useStaff();
  const id = inv.id ?? '';
  const refresh = useRefresh(id);
  const { key, draft, setDraft, finish } = usePersistedDraft<'rollover_principal' | 'rollover_all'>(
    `invest-rollover:${me.user_id ?? ''}:${id}`,
    'rollover_all',
  );
  const unpaid = (inv.return_accrued_minor ?? 0) - (inv.return_paid_minor ?? 0);
  const save = useMutation({
    mutationFn: () => investments.rollover(id, draft, key),
    onSuccess: (r) => {
      finish();
      refresh();
      if (r.next?.id) onDone(r.next.id);
    },
  });
  return (
    <form aria-label="Roll over" onSubmit={(e) => { e.preventDefault(); if (!save.isPending) save.mutate(); }}>
      <p>A new investment starts on {inv.maturity_date} on the product's current terms, for {inv.term_months} months.</p>
      <fieldset>
        <legend>What rolls over</legend>
        <label>
          <input type="radio" name="rollover-mode" checked={draft === 'rollover_all'} onChange={() => setDraft('rollover_all')} />
          Principal and return: {money((inv.principal_held_minor ?? 0) + unpaid, inv.currency)}
        </label>
        <label>
          <input type="radio" name="rollover-mode" checked={draft === 'rollover_principal'} onChange={() => setDraft('rollover_principal')} />
          Principal only: {money(inv.principal_held_minor, inv.currency)}; the return {money(unpaid, inv.currency)} stays to be paid
        </label>
      </fieldset>
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={save.isPending}>
        {save.isPending ? 'Rolling over' : 'Roll over'}
      </button>
    </form>
  );
}

interface EarlyDraft { method: PaymentMethod; reason: string }

export function EarlyWithdrawalForm({ inv }: { inv: Investment }) {
  const { me } = useStaff();
  const id = inv.id ?? '';
  const refresh = useRefresh(id);
  const quote = useQuery({ queryKey: ['lending', 'investment', id, 'early-quote'], queryFn: () => investments.earlyQuote(id) });
  const { key, draft, setDraft, finish } = usePersistedDraft<EarlyDraft>(`invest-early:${me.user_id ?? ''}:${id}`, { method: 'cash', reason: '' });
  const save = useMutation({
    mutationFn: () => investments.earlyWithdraw(id, { payment_method_key: draft.method, reason: draft.reason.trim() }, key),
    onSuccess: () => {
      finish();
      refresh();
    },
  });
  if (save.data) return <Waiting executed={save.data.executed} done="Withdrawn." />;
  const q = quote.data;
  const c = q?.currency ?? inv.currency;
  return (
    <form aria-label="Early withdrawal" onSubmit={(e) => { e.preventDefault(); if (draft.reason.trim() !== '' && !save.isPending) save.mutate(); }}>
      {quote.isPending && <p className="loading">Working out the early withdrawal</p>}
      <Problem error={quote.error} />
      {q && (
        <dl className="ln-facts" aria-label="Early withdrawal today">
          <dt>Rule</dt>
          <dd>{q.rule === 'reduced_rate' ? `Reduced rate ${percent(inv.early_withdrawal_rate_bp)}` : words(q.rule)}</dd>
          <dt>Principal</dt>
          <dd>{money(q.principal_minor, c)}</dd>
          <dt>Return earned</dt>
          <dd>{money(q.earned_return_minor, c)}</dd>
          <dt>Return already paid</dt>
          <dd>{money(q.return_paid_minor === undefined ? undefined : -q.return_paid_minor, c)}</dd>
          <dt>Penalty</dt>
          <dd>{money(q.penalty_minor === undefined ? undefined : -q.penalty_minor, c)}</dd>
          <dt className="ln-strong">Cash to the member</dt>
          <dd className="ln-strong">{money(q.cash_minor, c)}</dd>
        </dl>
      )}
      <p className="field-hint">A checker approves it; the amount is worked out again on the day they approve.</p>
      <MethodChoice name="early-method" value={draft.method} onChange={(m) => setDraft((d) => ({ ...d, method: m }))} />
      <label htmlFor="early-reason">Why does the member withdraw early?</label>
      <textarea id="early-reason" rows={2} maxLength={500} value={draft.reason} onChange={(e) => setDraft((d) => ({ ...d, reason: e.target.value }))} />
      <Problem error={save.error} />
      <button type="submit" className="btn-danger btn-block" disabled={!q || draft.reason.trim() === '' || save.isPending}>
        {save.isPending ? 'Sending' : 'Request early withdrawal'}
      </button>
    </form>
  );
}

const INSTRUCTIONS = ['payout', 'rollover_principal', 'rollover_all'] as const;

export function InstructionForm({ inv }: { inv: Investment }) {
  const id = inv.id ?? '';
  const refresh = useRefresh(id);
  const [choice, setChoice] = useState<string>(inv.maturity_instruction ?? 'payout');
  const save = useMutation({ mutationFn: () => investments.instruct(id, choice), onSuccess: refresh });
  return (
    <form aria-label="Maturity instruction" onSubmit={(e) => { e.preventDefault(); if (!save.isPending) save.mutate(); }}>
      <fieldset>
        <legend>At maturity the member wants</legend>
        {INSTRUCTIONS.map((i) => (
          <label key={i}>
            <input type="radio" name="instruction" checked={choice === i} onChange={() => setChoice(i)} />
            {instructionWords(i)}
          </label>
        ))}
      </fieldset>
      {inv.product_type === 'recurring' && <p className="field-hint">A recurring deposit renews with its return when no choice is given.</p>}
      <Problem error={save.error} />
      {save.isSuccess && <p role="status" className="alert alert-success">Saved.</p>}
      <button type="submit" className="rt-primary" disabled={save.isPending}>
        {save.isPending ? 'Saving' : 'Save the choice'}
      </button>
    </form>
  );
}

export function ReverseForm({ inv, txn }: { inv: Investment; txn: InvestmentTransaction }) {
  const { me } = useStaff();
  const id = inv.id ?? '';
  const refresh = useRefresh(id);
  const { key, draft, setDraft, finish } = usePersistedDraft<string>(`invest-reverse:${me.user_id ?? ''}:${txn.id ?? ''}`, '');
  const save = useMutation({
    mutationFn: () => investments.reverse(id, txn.id ?? '', draft.trim(), key),
    onSuccess: () => {
      finish();
      refresh();
    },
  });
  if (save.data) return <Waiting executed={save.data.executed} done="Reversed." />;
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (draft.trim() !== '' && !save.isPending) save.mutate(); }}>
      <label htmlFor={`reverse-${txn.id ?? ''}`}>Why reverse this {investmentTxnWords(txn.txn_type).toLowerCase()}?</label>
      <textarea id={`reverse-${txn.id ?? ''}`} rows={2} maxLength={500} value={draft} onChange={(e) => setDraft(e.target.value)} />
      <Problem error={save.error} />
      <button type="submit" className="btn-danger btn-block" disabled={draft.trim() === '' || save.isPending}>
        {save.isPending ? 'Sending' : 'Request reversal'}
      </button>
    </form>
  );
}
