import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { type Loan, type LoanSchedule } from '../../../api/lending';
import { payments, type PaymentIntent } from '../../../api/payments';
import { lending } from '../../../api/lending';
import { QrCode, useCopy } from '../../auth/steps';
import { useStaff } from '../context';
import { usePersistedDraft } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { flagWords, isCancellable, isSettled, pollDelay, statusWords, suggestedAmount } from './collect-state';
import { money, parseAmount } from './loan-state';

// "Collect by mobile money or card" on a loan (extras slice A3, issue #216; spec 3.2 and 3.10): staff enter the amount and the
// payer's mobile number, the screen shows the payment link with a QR code and a copy button, and then follows the payment live. The
// money goes into the business's own payment account (Receive payments), never the platform's; a business that has not connected and
// tested its own account gets a plain refusal here and nothing is created. When the payment is recorded the receipt number of the
// repayment is shown, exactly as for a repayment entered by hand.

interface CollectDraft { amount: string; phone: string }

/** The phone as Uganda writes it: 07XXXXXXXX, 2567XXXXXXXX or +2567XXXXXXXX. The server checks it again. */
export const looksLikeUgandaMobile = (phone: string): boolean => /^(\+?256|0)7\d{8}$/.test(phone.replace(/[\s-]/g, ''));

export function CollectPanel({ loan, schedule }: { loan: Loan; schedule: LoanSchedule | undefined }) {
  const { me } = useStaff();
  const loanId = loan.id ?? '';
  const suggested = suggestedAmount(loan.balances, schedule);
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<CollectDraft>(`lending-collect:${me.user_id ?? ''}:${loanId}`, {
    amount: suggested === null ? '' : String(suggested),
    phone: '',
  });
  const [intent, setIntent] = useState<PaymentIntent | null>(null);
  const amountMinor = parseAmount(draft.amount, loan.currency);
  const valid = amountMinor !== null && looksLikeUgandaMobile(draft.phone);
  const start = useMutation({
    mutationFn: () => payments.startLoanRepayment(loanId, amountMinor ?? 0, draft.phone.trim(), key),
    onSuccess: (made) => {
      // The payer's number is not kept in the tab once the link exists; the intent holds it masked.
      finish();
      setIntent(made);
    },
  });

  if (intent) {
    return (
      <CollectStatus
        loan={loan}
        initial={intent}
        onNew={() => {
          finish();
          discard();
          setIntent(null);
          start.reset();
        }}
      />
    );
  }
  return (
    <form
      aria-label="Collect by mobile money or card"
      onSubmit={(e) => {
        e.preventDefault();
        if (valid && !start.isPending) start.mutate();
      }}
    >
      <p className="field-hint">
        The payer pays on their own phone, by mobile money or card, into this business's own payment account. Nothing is recorded on
        the loan until the payment is confirmed.
      </p>
      <label htmlFor="collect-amount">Amount ({loan.currency ?? 'UGX'})</label>
      <input
        id="collect-amount"
        inputMode="decimal"
        value={draft.amount}
        onChange={(e) => setDraft((d) => ({ ...d, amount: e.target.value }))}
        aria-invalid={draft.amount !== '' && amountMinor === null}
        aria-describedby="collect-amount-hint"
      />
      <p id="collect-amount-hint" className="field-hint">
        {suggested !== null
          ? `The instalment due or the arrears is offered first. The loan still owes ${money(loan.balances?.total_outstanding_minor, loan.currency)} in all.`
          : 'Enter the amount the payer will pay.'}
      </p>
      <label htmlFor="collect-phone">Payer's mobile number</label>
      <input
        id="collect-phone"
        type="tel"
        inputMode="tel"
        autoComplete="off"
        placeholder="0700000000"
        value={draft.phone}
        onChange={(e) => setDraft((d) => ({ ...d, phone: e.target.value }))}
        aria-invalid={draft.phone !== '' && !looksLikeUgandaMobile(draft.phone)}
      />
      <Problem error={start.error} />
      <button type="submit" className="rt-primary" disabled={!valid || start.isPending}>
        {start.isPending ? 'Making the link' : 'Make the payment link'}
      </button>
    </form>
  );
}

/** The link, the QR code and the live status of one collection. */
export function CollectStatus({ loan, initial, onNew }: { loan: Loan; initial: PaymentIntent; onNew: () => void }) {
  const loanId = loan.id ?? '';
  const queryClient = useQueryClient();
  const startedAt = useRef(Date.now());
  const [copied, copy] = useCopy();
  const [waitedLong, setWaitedLong] = useState(false);
  const id = initial.id ?? '';
  const live = useQuery({
    queryKey: ['payments', 'intent', id],
    queryFn: () => payments.intent(id),
    initialData: initial,
    // Every 3 seconds for 2 minutes, then every 15 seconds; nothing once the payment has reached an end of its own.
    refetchInterval: (q) => (isSettled(q.state.data?.status) ? false : pollDelay(Date.now() - startedAt.current)),
  });
  const intent = live.data ?? initial;
  const status = intent.status;
  useEffect(() => {
    const t = window.setTimeout(() => setWaitedLong(true), 120000);
    return () => window.clearTimeout(t);
  }, []);
  // Once the repayment is booked the loan, its schedule and its transactions are stale: fetch them again, once.
  const booked = status === 'booked';
  useEffect(() => {
    if (booked) {
      void queryClient.invalidateQueries({ queryKey: ['lending', 'loan', loanId] });
      void queryClient.invalidateQueries({ queryKey: ['lending', 'loans'] });
    }
  }, [booked, loanId, queryClient]);
  const txns = useQuery({
    queryKey: ['lending', 'loan', loanId, 'transactions', 'receipt', intent.booked_transaction_id],
    queryFn: () => lending.listTransactions(loanId),
    enabled: booked,
  });
  const receipt = txns.data?.find((t) => t.id === intent.booked_transaction_id);
  const cancel = useMutation({
    mutationFn: () => payments.cancelIntent(id),
    onSuccess: (view) => queryClient.setQueryData(['payments', 'intent', id], view),
  });
  const link = intent.redirect_url;
  const open = status === 'pending' || status === 'created';
  const flags = flagWords(intent.flags);
  return (
    <section aria-label="Payment status" className="rt-card" aria-live="polite">
      <p>
        <strong>{money(intent.amount_minor, intent.currency ?? loan.currency)}</strong> from {intent.payer_phone}
      </p>
      <p role="status">{statusWords(intent)}</p>
      {open && waitedLong && <p className="ln-muted">Still waiting. We will update this screen when the payment arrives.</p>}
      {flags.map((f) => (
        <p key={f} className="ln-muted">{f}</p>
      ))}
      {link && (open || status === 'expired') && (
        <div className="ln-collect-link">
          <QrCode value={link} label="QR code of the payment link" />
          <p>
            <a href={link} target="_blank" rel="noreferrer">Open the payment page</a>
          </p>
          <button type="button" onClick={() => copy('link', link)}>
            {copied === 'link' ? 'Copied' : 'Copy the link'}
          </button>
        </div>
      )}
      {intent.confirmation_code && (
        <p className="ln-muted">Payment code <strong>{intent.confirmation_code}</strong></p>
      )}
      {booked && (
        <p>
          {receipt?.receipt_no ? (
            <>
              Receipt <strong>{receipt.receipt_no}</strong>, {money(receipt.amount_minor, receipt.currency ?? loan.currency)}. The receipt text goes to the borrower if the business sends texts.
            </>
          ) : (
            'The repayment is on the loan: see Transactions below for its receipt.'
          )}
        </p>
      )}
      <Problem error={cancel.error ?? live.error} />
      <div className="ln-actions">
        {isCancellable(status) && (
          <button type="button" onClick={() => cancel.mutate()} disabled={cancel.isPending}>
            Cancel this payment
          </button>
        )}
        {(isSettled(status) || status === 'expired') && (
          <button type="button" className="rt-primary" onClick={onNew}>
            Collect another payment
          </button>
        )}
      </div>
    </section>
  );
}
