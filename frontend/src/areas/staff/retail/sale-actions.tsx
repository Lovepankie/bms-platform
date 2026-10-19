import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { RetailError, retail, type Sale } from '../../../api/retail';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { SALE_VOID, holdsAt } from './permissions';
import {
  IMPORTED_FULL, IMPORTED_SHORT, MIN_REASON, PAY_METHODS, blankPay, buildPayRequest, fullPay, importedAndOwing, keyReused, owedMinor, paidWords, payFailureWords, payProblem,
  saleMovedOn, takesPayment, typedAmount, voidBlocked, voidReasonOf, type PayForm,
} from './sale-actions-state';
import { Problem, Success, money } from './ui';

// Two actions on a sale, for a shop owner (issues #326 and #327): take a payment on a credit sale, from the credit sales list and from
// the sale itself, and void a sale from the sale itself. The API checks every rule again under the sale's lock; the screens only save a
// round trip and say each refusal in plain words (api/retail-errors.ts).

const SALE_CREATE = 'retail.sale.create';

/** The payment form: amount (the balance to start with), how it was paid and the date. `full` fixes the amount at the whole balance. */
export function PayFormView({ sale, today, form, full, busy, error, onChange, onSubmit, onCancel }: {
  sale: Sale;
  today: string;
  form: PayForm;
  full: boolean;
  busy?: boolean;
  error?: unknown;
  onChange: (form: PayForm) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  const id = `pay-${sale.id ?? ''}`;
  const shown = full ? fullPay(form, sale) : form;
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
      <input id={`${id}-date`} type="date" min={sale.sale_date} max={today} value={form.paidOn} onChange={(e) => onChange({ ...form, paidOn: e.target.value })} />
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      <Problem error={error} />
      <p className="cluster">
        <button type="submit" className="rt-primary" disabled={problem !== null || busy}>{busy ? 'Saving' : full ? 'Mark as fully paid' : 'Save payment'}</button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={onCancel}>Cancel</button>
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
  const { key, draft, setDraft, discard } = usePersistedDraft<PayForm>(`sale-pay:${me.user_id ?? ''}:${saleId}:${full ? 'full' : 'part'}`, blankPay(sale, today));
  // A second press before the first request has started must not send a second one: the ref answers at once, the mutation state a render later.
  const sending = useRef(false);
  const send = useMutation({
    mutationFn: () => retail.paySale(saleId, buildPayRequest(full ? fullPay(draft, sale) : draft), key),
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
        <button type="submit" disabled={ready === null || busy}>{busy ? 'Voiding' : 'Void this sale'}</button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={onCancel}>Keep the sale</button>
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
  const sending = useRef(false);
  const send = useMutation({
    mutationFn: (reason: string) => retail.voidSale(sale.id ?? '', reason),
    onSuccess: () => {
      setDone(true);
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
