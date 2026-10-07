import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { PAYMENT_METHODS, type PaymentMethod } from '../../../api/lending';
import { savings, type SavingsAccount, type SavingsTransaction } from '../../../api/savings';
import { useStaff } from '../context';
import { usePersistedDraft } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { methodWords, money, parseAmount } from './loan-state';

// The money forms of a savings account: deposit, withdrawal, closure, reversal and the status
// changes. Each money form has two steps: the details, then a confirmation that says exactly what
// will happen (amount, account, member, method, fee and the balance after), so a cashier reads it
// back to the member before anything moves. Each write keeps its draft and one Idempotency-Key in
// this tab until the server answers with success (retail/idempotency.ts), so a retry or reload
// posts it once. After a write the account and its transactions are fetched again.

function useRefreshAccount(accountId: string) {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: ['savings', 'account', accountId] });
    void queryClient.invalidateQueries({ queryKey: ['savings', 'accounts'] });
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

/** Who and what the money is for, as the confirmation reads it. */
export function accountLine(a: SavingsAccount): string {
  return `${a.account_no ?? ''}, ${a.member_name ?? 'member'} (${a.member_no ?? ''})`;
}

interface MoneyDraft { amount: string; method: PaymentMethod; reference: string }

export type MoneyKind = 'deposit' | 'withdraw';

/** What the confirmation step says for a deposit or a withdrawal of `amountMinor`. */
export function confirmation(kind: MoneyKind, a: SavingsAccount, amountMinor: number, method: string): { title: string; lines: string[] } {
  const c = a.currency;
  const balance = a.balance_minor ?? 0;
  if (kind === 'deposit') {
    return {
      title: `Deposit ${money(amountMinor, c)}`,
      lines: [
        `Into ${accountLine(a)}.`,
        `Received by ${methodWords(method)}.`,
        `Balance after: ${money(balance + amountMinor, c)}.`,
      ],
    };
  }
  const fee = a.withdrawal_fee_minor ?? 0;
  return {
    title: `Pay out ${money(amountMinor, c)}`,
    lines: [
      `From ${accountLine(a)}.`,
      `Paid by ${methodWords(method)}.`,
      fee > 0 ? `Withdrawal fee: ${money(fee, c)}, taken from the account.` : 'No withdrawal fee.',
      `Balance after: ${money(balance - amountMinor - fee, c)}.`,
      'Above the approval limit, a checker must approve it before any money is paid out.',
    ],
  };
}

export function MoneyForm({ account, kind, onDone }: { account: SavingsAccount; kind: MoneyKind; onDone?: () => void }) {
  const { me } = useStaff();
  const accountId = account.id ?? '';
  const refresh = useRefreshAccount(accountId);
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<MoneyDraft>(`savings-${kind}:${me.user_id ?? ''}:${accountId}`, {
    amount: '',
    method: 'cash',
    reference: '',
  });
  const [confirming, setConfirming] = useState(false);
  const update = (patch: Partial<MoneyDraft>) => setDraft((d) => ({ ...d, ...patch }));
  const amountMinor = parseAmount(draft.amount, account.currency);
  const available = account.available_minor ?? 0;
  const tooMuch = kind === 'withdraw' && amountMinor !== null && amountMinor > available;
  const valid = amountMinor !== null && !tooMuch;
  const body = () => ({ amount_minor: amountMinor ?? 0, payment_method_key: draft.method, external_reference: draft.reference.trim() || undefined });
  const save = useMutation({
    mutationFn: async () => {
      if (kind === 'deposit') {
        const r = await savings.deposit(accountId, body(), key);
        return { executed: true, txn: r.transaction, balance: r.account?.balance_minor };
      }
      const r = await savings.withdraw(accountId, body(), key);
      return { executed: r.executed, txn: r.outcome.transaction, balance: r.outcome.account?.balance_minor };
    },
    onSuccess: () => {
      finish();
      refresh();
      onDone?.();
    },
  });

  if (save.data) {
    const t = save.data.txn;
    return save.data.executed ? (
      <section role="status" aria-label="Recorded" className="alert alert-success">
        <p>
          <strong>{kind === 'deposit' ? 'Deposit recorded.' : 'Withdrawal paid out.'}</strong> {kind === 'deposit' ? 'Receipt' : 'Voucher'}{' '}
          <strong>{t?.receipt_no}</strong>, {money(t?.amount_minor, account.currency)}.
        </p>
        <p>New balance: {money(save.data.balance, account.currency)}.</p>
      </section>
    ) : (
      <p role="status" className="alert alert-info">
        Sent for approval. A checker must approve it in the approvals inbox before the money is paid out.
      </p>
    );
  }

  const label = kind === 'deposit' ? 'Deposit' : 'Withdrawal';
  if (confirming && amountMinor !== null) {
    const c = confirmation(kind, account, amountMinor, draft.method);
    return (
      <section aria-label={`Confirm the ${label.toLowerCase()}`} className="sv-confirm">
        <h3>{c.title}</h3>
        <ul>
          {c.lines.map((l) => (
            <li key={l}>{l}</li>
          ))}
        </ul>
        <Problem error={save.error} />
        <div className="sv-confirm-actions">
          <button type="button" className="rt-primary" disabled={save.isPending} onClick={() => save.mutate()}>
            {save.isPending ? 'Saving' : `Confirm ${label.toLowerCase()}`}
          </button>
          <button type="button" disabled={save.isPending} onClick={() => setConfirming(false)}>
            Change
          </button>
        </div>
      </section>
    );
  }

  const id = `sv-${kind}`;
  return (
    <form aria-label={`Record a ${label.toLowerCase()}`} onSubmit={(e) => { e.preventDefault(); if (valid) setConfirming(true); }}>
      <label htmlFor={`${id}-amount`}>Amount ({account.currency ?? 'UGX'})</label>
      <input id={`${id}-amount`} inputMode="decimal" value={draft.amount} onChange={(e) => update({ amount: e.target.value })}
        aria-invalid={(draft.amount !== '' && amountMinor === null) || tooMuch} aria-describedby={`${id}-hint`} />
      <p id={`${id}-hint`} className="field-hint">
        {kind === 'withdraw' ? `At most ${money(available, account.currency)} can be paid out now.` : 'Enter an amount above zero.'}
      </p>
      <MethodChoice name={`${id}-method`} value={draft.method} onChange={(m) => update({ method: m })} />
      <label htmlFor={`${id}-ref`}>Reference (optional)</label>
      <input id={`${id}-ref`} maxLength={100} value={draft.reference} onChange={(e) => update({ reference: e.target.value })} autoComplete="off" />
      <button type="submit" className="rt-primary btn-block" disabled={!valid}>
        Review {label.toLowerCase()}
      </button>
      {draft.amount !== '' && (
        <button type="button" className="btn-block" onClick={() => { discard(); save.reset(); }}>Clear this form</button>
      )}
    </form>
  );
}

interface CloseDraft { method: PaymentMethod; reason: string }

export function CloseForm({ account }: { account: SavingsAccount }) {
  const { me } = useStaff();
  const accountId = account.id ?? '';
  const refresh = useRefreshAccount(accountId);
  const { key, draft, setDraft, finish } = usePersistedDraft<CloseDraft>(`savings-close:${me.user_id ?? ''}:${accountId}`, { method: 'cash', reason: '' });
  const [confirming, setConfirming] = useState(false);
  const save = useMutation({
    mutationFn: () => savings.close(accountId, { payment_method_key: draft.method, reason: draft.reason.trim() || undefined }, key),
    onSuccess: () => {
      finish();
      refresh();
    },
  });
  if (save.data) {
    return save.data.executed ? (
      <p role="status" className="alert alert-success">
        Account closed. Paid out {money(save.data.outcome.transaction?.amount_minor ?? 0, account.currency)}, voucher{' '}
        {save.data.outcome.transaction?.receipt_no ?? 'none'}.
      </p>
    ) : (
      <p role="status" className="alert alert-info">Sent for approval. The account closes when a checker approves it.</p>
    );
  }
  if (confirming) {
    return (
      <section aria-label="Confirm the closure" className="sv-confirm">
        <h3>Close {account.account_no}</h3>
        <ul>
          <li>Interest to yesterday is added first.</li>
          <li>
            Then the whole balance, now {money(account.balance_minor, account.currency)} plus that interest, is paid out by{' '}
            {methodWords(draft.method)}, with no fee.
          </li>
          <li>The account cannot be used again.</li>
        </ul>
        <Problem error={save.error} />
        <div className="sv-confirm-actions">
          <button type="button" className="btn-danger" disabled={save.isPending} onClick={() => save.mutate()}>
            {save.isPending ? 'Sending' : 'Confirm closure'}
          </button>
          <button type="button" disabled={save.isPending} onClick={() => setConfirming(false)}>Change</button>
        </div>
      </section>
    );
  }
  return (
    <form aria-label="Close the account" onSubmit={(e) => { e.preventDefault(); setConfirming(true); }}>
      <MethodChoice name="sv-close-method" value={draft.method} onChange={(m) => setDraft((d) => ({ ...d, method: m }))} />
      <label htmlFor="sv-close-reason">Reason (optional)</label>
      <textarea id="sv-close-reason" rows={2} maxLength={500} value={draft.reason} onChange={(e) => setDraft((d) => ({ ...d, reason: e.target.value }))} />
      <button type="submit" className="btn-block">Review closure</button>
    </form>
  );
}

const STATUS_WORDS = {
  freeze: { label: 'Why freeze this account?', submit: 'Freeze', done: 'Account frozen. Nothing can be taken out until it is unfrozen.' },
  unfreeze: { label: 'Why unfreeze this account?', submit: 'Unfreeze', done: 'Account unfrozen.' },
  reactivate: { label: 'How was the member identified?', submit: 'Reactivate', done: 'Account reactivated.' },
} as const;

export function StatusForm({ account, change }: { account: SavingsAccount; change: 'freeze' | 'unfreeze' | 'reactivate' }) {
  const accountId = account.id ?? '';
  const refresh = useRefreshAccount(accountId);
  const [reason, setReason] = useState('');
  const words = STATUS_WORDS[change];
  const save = useMutation({ mutationFn: () => savings.setStatus(accountId, change, reason.trim()), onSuccess: refresh });
  if (save.data) return <p role="status" className="alert alert-success">{words.done}</p>;
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (reason.trim() !== '' && !save.isPending) save.mutate(); }}>
      <label htmlFor={`sv-${change}-reason`}>{words.label}</label>
      <textarea id={`sv-${change}-reason`} rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <Problem error={save.error} />
      <button type="submit" className={change === 'freeze' ? 'btn-danger btn-block' : 'rt-primary btn-block'} disabled={reason.trim() === '' || save.isPending}>
        {save.isPending ? 'Saving' : words.submit}
      </button>
    </form>
  );
}

export function ReverseSavingsForm({ account, txn }: { account: SavingsAccount; txn: SavingsTransaction }) {
  const { me } = useStaff();
  const accountId = account.id ?? '';
  const refresh = useRefreshAccount(accountId);
  const { key, draft, setDraft, finish } = usePersistedDraft<string>(`savings-reverse:${me.user_id ?? ''}:${txn.id ?? ''}`, '');
  const save = useMutation({
    mutationFn: () => savings.reverse(accountId, txn.id ?? '', draft.trim(), key),
    onSuccess: () => {
      finish();
      refresh();
    },
  });
  if (save.data) {
    return <p role="status" className="alert alert-info">Sent for approval. It is reversed when a checker approves it.</p>;
  }
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (draft.trim() !== '' && !save.isPending) save.mutate(); }}>
      <label htmlFor={`sv-reverse-${txn.id ?? ''}`}>Why reverse {txn.receipt_no ?? 'this transaction'}?</label>
      <textarea id={`sv-reverse-${txn.id ?? ''}`} rows={2} maxLength={500} value={draft} onChange={(e) => setDraft(e.target.value)} />
      {txn.txn_type === 'withdrawal' && <p className="field-hint">Its withdrawal fee is reversed with it.</p>}
      <Problem error={save.error} />
      <button type="submit" className="btn-danger btn-block" disabled={draft.trim() === '' || save.isPending}>
        {save.isPending ? 'Sending' : 'Request reversal'}
      </button>
    </form>
  );
}
