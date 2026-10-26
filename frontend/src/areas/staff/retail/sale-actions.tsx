import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { RetailError, isWaiting, retail, type Sale } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { CREDIT_WRITE_OFF, SALE_VOID, holdsAt } from './permissions';
import {
  IMPORTED_FULL, IMPORTED_SHORT, MIN_REASON, PAY_METHODS, blankPay, buildPayRequest, fullPay, importedAndOwing, keyReused, owedMinor, paidWords, payFailureWords, payProblem,
  saleMovedOn, settledDate, takesPayment, typedAmount, voidBlocked, voidReasonOf, type PayForm,
  WRITE_OFF_CHECKER, WRITE_OFF_PENDING, canWriteOff, writeOffAlreadyPending, writeOffFailureWords, writeOffMovedOn, writeOffReasonOf, writeOffAmount, writeOffWarning, writeOffWords, } from './sale-actions-state';
import { Problem, Success, WaitingForApproval, money } from './ui';

// Two actions on a sale, for a shop owner (issues #326 and #327): take a payment on a credit sale, from the credit sales list and from
// the sale itself, and void a sale from the sale itself. The API checks every rule again under the sale's lock; the screens only save a
// round trip and say each refusal in plain words (api/retail-errors.ts).

const SALE_CREATE = 'retail.sale.create';

/** The payment form: amount (the balance to start with), how it was paid and the date. `full` fixes the amount at the whole balance. */
export function PayFormView({ sale, today, form, full, busy, error, todayOnly, onChange, onSubmit, onCancel }: {
  sale: Sale;
  today: string;
  /** Under the rule "when the buyer pays" a payment counts on the day it is entered, so it is dated today (ADR-043). */
  todayOnly?: boolean;
  form: PayForm;
  full: boolean;
  busy?: boolean;
  error?: unknown;
  onChange: (form: PayForm) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  const id = `pay-${sale.id ?? ''}`;
  const shown = settledDate(full ? fullPay(form, sale) : form, todayOnly === true, today);
  const problem = payProblem(shown, sale, today);
  return (
    <form
      className="stack"
      aria-label={full ? 'Mark as fully paid' : 'Record a payment'}
      onSubmit={(e) => {
        e.preventDefault();
        if (problem === null && !busy) onSubmit();
      }}
    >
      <p className="hint">Still owes {money(owedMinor(sale))}.</p>
      <label htmlFor={`${id}-amount`}>Amount received</label>
      <input id={`${id}-amount`} inputMode="numeric" value={shown.amount} readOnly={full} onChange={(e) => onChange({ ...form, amount: e.target.value })} />
      <label htmlFor={`${id}-method`}>Paid by</label>
      <select id={`${id}-method`} value={form.method} onChange={(e) => onChange({ ...form, method: e.target.value as PayForm['method'] })}>
        {PAY_METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
      </select>
      <label htmlFor={`${id}-date`}>Date received</label>
      <input id={`${id}-date`} type="date" min={todayOnly ? today : sale.sale_date} max={today} value={todayOnly ? today : form.paidOn} readOnly={todayOnly} onChange={(e) => onChange({ ...form, paidOn: e.target.value })} />
      {todayOnly && <p className="hint" data-testid="pay-today-only">A payment is dated today. If you took the money earlier, ask your administrator.</p>}
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      <Problem error={error} />
      <p className="cluster">
        <BusyButton type="submit" className="rt-primary" busy={busy === true} notReady={problem !== null}>{full ? 'Mark as fully paid' : 'Save payment'}</BusyButton>
        <button type="button" /* busy-exempt: cancel waits for the payment */ className="btn-ghost" disabled={busy} onClick={onCancel}>Cancel</button>
      </p>
    </form>
  );
}

/**
 * Mounted only while the form is open. Each way of paying has its own draft and Idempotency-Key, so "Mark as fully paid" never reuses the key
 * of an earlier part payment whose answer was lost. A refusal that says the sale moved on reloads it; a reused key resets the form.
 */
function PayPanel({ sale, today, full, onDone }: { sale: Sale; today: string; full: boolean; onDone: (message: string | null) => void }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const saleId = sale.id ?? '';
  // The server says a sale is counted when the buyer pays; its payment is then dated today whatever a stored draft holds (ADR-043).
  const todayOnly = sale.counted_when_paid === true;
  const { key, draft, setDraft, discard } = usePersistedDraft<PayForm>(`sale-pay:${me.user_id ?? ''}:${saleId}:${full ? 'full' : 'part'}`, blankPay(sale, today));
  // A second press before the first request has started must not send a second one: the ref answers at once, the mutation state a render later.
  const sending = useRef(false);
  const send = useMutation({
    mutationFn: () => retail.paySale(saleId, buildPayRequest(settledDate(full ? fullPay(draft, sale) : draft, todayOnly, today)), key),
    onSuccess: (result) => {
      discard();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onDone(paidWords(result.payment?.amount_minor ?? 0, result.sale_balance_minor ?? 0));
    },
    onError: (error) => {
      if (saleMovedOn(error) || keyReused(error)) void queryClient.invalidateQueries({ queryKey: ['retail'] });
      if (keyReused(error)) discard();
    },
    onSettled: () => { sending.current = false; },
  });
  // The balance changed under the form (someone else took a payment): a typed amount above the new balance goes back to the balance.
  const owed = owedMinor(sale);
  useEffect(() => {
    setDraft((d) => ((Number(d.amount.replace(/,/g, '')) || 0) > owed ? { ...d, amount: typedAmount(owed) } : d));
  }, [owed, setDraft]);
  return (
    <PayFormView
      sale={sale}
      today={today}
      form={draft}
      full={full}
      todayOnly={todayOnly}
      busy={send.isPending}
      error={payFailureWords(send.error, full)}
      onChange={setDraft}
      onSubmit={() => {
        if (sending.current) return;
        sending.current = true;
        send.mutate();
      }}
      onCancel={() => {
        // Keep the draft only when a request went out and its answer may have been lost (the same key then makes the retry safe).
        if (!(send.error && !(send.error instanceof RetailError))) discard();
        onDone(null);
      }}
    />
  );
}

/**
 * Take a payment on a credit sale that still owes (retail.sale.create at the sale's branch): "Record payment" and the
 * "Mark as fully paid" shortcut. An imported sale shows a note instead (one short line when `compact`, as in a list row). Nothing shows for
 * anyone else. Only this screen keeps imported sales from being paid; the API does not check it yet.
 */
export function PaySaleAction({ sale, today, compact = false }: { sale: Sale; today: string; compact?: boolean }) {
  const { me } = useStaff();
  const [mode, setMode] = useState<'part' | 'full' | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  if (!holdsAt(me, SALE_CREATE, sale.branch_id)) return null;
  const note = importedAndOwing(sale)
    ? <p className="hint" data-testid="imported-note">{compact ? IMPORTED_SHORT : IMPORTED_FULL}</p>
    : null;
  return (
    <div data-testid="pay-sale">
      {message && <Success>{message}</Success>}
      {note}
      {takesPayment(sale) && mode === null && (
        <p className="cluster">
          <button type="button" className="btn-sm" onClick={() => { setMessage(null); setMode('part'); }}>Record payment</button>
          <button type="button" className="btn-sm" onClick={() => { setMessage(null); setMode('full'); }}>Mark as fully paid</button>
        </p>
      )}
      {takesPayment(sale) && mode !== null && (
        <PayPanel sale={sale} today={today} full={mode === 'full'} onDone={(text) => { setMode(null); setMessage(text); }} />
      )}
    </div>
  );
}

/** The void form: a reason of at least five characters, what will happen in plain words, and one clear button. */
export function VoidFormView({ branchName, busy, error, onVoid, onCancel }: {
  branchName?: string;
  busy?: boolean;
  error?: unknown;
  onVoid: (reason: string) => void;
  onCancel: () => void;
}) {
  const [reason, setReason] = useState('');
  const ready = voidReasonOf(reason);
  return (
    <form
      className="stack"
      aria-label="Void this sale"
      onSubmit={(e) => {
        e.preventDefault();
        if (ready && !busy) onVoid(ready);
      }}
    >
      <label htmlFor="void-sale-reason">Why is this sale being voided? (at least {MIN_REASON} characters)</label>
      <input id="void-sale-reason" value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} />
      <p className="hint" data-testid="void-sale-warning">
        The items go back into stock{branchName ? ` at ${branchName}` : ''}, and the sale stops counting in your reports and in what the buyer owes. This cannot be undone.
      </p>
      <Problem error={error} />
      <p className="cluster">
        <BusyButton type="submit" busy={busy === true} disabled={ready === null}>Void this sale</BusyButton>
        <button type="button" /* busy-exempt: keep waits for the void */ className="btn-ghost" disabled={busy} onClick={onCancel}>Keep the sale</button>
      </p>
    </form>
  );
}

/**
 * Void a completed sale (retail.sale.void at the sale's branch). A credit sale that has had a payment says why it cannot be voided;
 * returns made against a sale are only known to the server, which refuses with sale_has_returns. Nothing shows for anyone else.
 */
export function VoidSaleAction({ sale, branchName }: { sale: Sale; branchName?: string }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  // Voided, or found already voided: the button stays away until the reloaded sale says Voided.
  const [done, setDone] = useState(false);
  // ADR-091: the void waits for an administrator; the sale stays as it is until then, and the button stays away.
  const [waiting, setWaiting] = useState(false);
  const sending = useRef(false);
  const send = useMutation({
    mutationFn: (reason: string) => retail.voidSale(sale.id ?? '', reason),
    onSuccess: (outcome) => {
      if (isWaiting(outcome)) setWaiting(true);
      else setDone(true);
      setOpen(false);
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
    onError: (error) => {
      // A void that already went through (a lost answer, a second person) is done, not an error.
      if (error instanceof RetailError && error.code === 'sale_voided') {
        setDone(true);
        setOpen(false);
      }
      if (error instanceof RetailError && ['sale_voided', 'sale_has_payments', 'sale_has_returns'].includes(error.code ?? '')) void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
    onSettled: () => { sending.current = false; },
  });
  if (waiting) return <WaitingForApproval what="The void of this sale waits for an administrator." />;
  if (sale.status !== 'completed' || done || !holdsAt(me, SALE_VOID, sale.branch_id)) return null;
  const blocked = voidBlocked(sale);
  if (blocked) return <p className="hint" data-testid="void-blocked">{blocked}</p>;
  if (!open) return <p><button type="button" className="btn-sm" onClick={() => { send.reset(); setOpen(true); }}>Void this sale</button></p>;
  return <VoidFormView branchName={branchName} busy={send.isPending} error={send.error} onVoid={(reason) => {
    if (sending.current) return;
    sending.current = true;
    send.mutate(reason);
  }} onCancel={() => setOpen(false)} />;
}

/** The write-off form: a reason of at least five characters, what will happen in plain words, and one clear button. */
export function WriteOffFormView({ owed, fee, reason, busy, error, onReason, onWriteOff, onCancel }: {
  owed: number;
  /** The part of owed that is a delivery fee left on the account (issue #385). */
  fee?: number;
  reason: string;
  busy?: boolean;
  error?: unknown;
  onReason: (reason: string) => void;
  onWriteOff: (reason: string) => void;
  onCancel: () => void;
}) {
  const ready = writeOffReasonOf(reason);
  return (
    <form
      className="stack"
      aria-label="Write off this debt"
      onSubmit={(e) => {
        e.preventDefault();
        if (ready && !busy) onWriteOff(ready);
      }}
    >
      <label htmlFor="write-off-reason">Why will this buyer not pay? (at least {MIN_REASON} characters)</label>
      <input id="write-off-reason" value={reason} maxLength={300} onChange={(e) => onReason(e.target.value)} />
      <p className="hint" data-testid="write-off-warning">{writeOffWarning(owed, fee)}</p>
      <p className="hint" data-testid="write-off-checker">{WRITE_OFF_CHECKER}</p>
      <Problem error={error} />
      <p className="cluster">
        <BusyButton type="submit" busy={busy === true} disabled={ready === null}>Write off</BusyButton>
        <button type="button" /* busy-exempt: keep waits for the write-off */ className="btn-ghost" disabled={busy} onClick={onCancel}>Keep the debt</button>
      </p>
    </form>
  );
}

/**
 * Write off the unpaid part of a credit sale that the buyer will never pay (retail.credit.manage at the sale's branch). Offered only on a
 * live credit sale made on the system that still owes; nothing shows for anyone else. Above the business's approval limit the write-off
 * waits for a second administrator, and the screen says so. The sale is never deleted.
 */
export function WriteOffSaleAction({ sale }: { sale: Sale }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  // A write-off waiting for a second administrator: the button stays away, so the same debt is not asked for twice.
  const [pending, setPending] = useState(false);
  const saleId = sale.id ?? '';
  const { key, draft, setDraft, discard } = usePersistedDraft<{ reason: string }>(`sale-write-off:${me.user_id ?? ''}:${saleId}`, { reason: '' });
  const sending = useRef(false);
  const send = useMutation({
    mutationFn: (reason: string) => retail.writeOffSale(saleId, reason, key),
    onSuccess: (outcome) => {
      discard();
      setOpen(false);
      setMessage(writeOffWords(outcome));
      if (outcome.executed !== true) setPending(true);
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
    onError: (error) => {
      if (writeOffAlreadyPending(error)) {
        setOpen(false);
        setPending(true);
      }
      if (writeOffMovedOn(error)) void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
    onSettled: () => { sending.current = false; },
  });
  if (!holdsAt(me, CREDIT_WRITE_OFF, sale.branch_id)) return null;
  if (message) return <Success>{message}</Success>;
  if (pending) return <p className="hint" data-testid="write-off-pending">{WRITE_OFF_PENDING}</p>;
  if (!canWriteOff(sale)) return null;
  if (!open) return <p><button type="button" className="btn-sm" onClick={() => { send.reset(); setOpen(true); }}>Write off</button></p>;
  return (
    <WriteOffFormView
      owed={writeOffAmount(sale)}
      fee={sale.delivery_fee_owed_minor ?? 0}
      reason={draft.reason}
      busy={send.isPending}
      error={writeOffFailureWords(send.error)}
      onReason={(reason) => setDraft({ reason })}
      onWriteOff={(reason) => {
        if (sending.current) return;
        sending.current = true;
        send.mutate(reason);
      }}
      onCancel={() => { discard(); setOpen(false); }}
    />
  );
}
