import { useMutation, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { type Loan, type LoanSchedule } from '../../../api/lending';
import { payments, type PaymentIntent } from '../../../api/payments';
import { lending } from '../../../api/lending';
import { savings, type SavingsAccount } from '../../../api/savings';
import { QrCode, useCopy } from '../../auth/steps';
import { useStaff } from '../context';
import { usePersistedDraft } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { flagWords, isCancellable, isSettled, pollDelay, statusWords, suggestedAmount, type CollectKind } from './collect-state';
import { money, parseAmount } from './loan-state';
import { BusyButton } from '../../../app/ui/BusyButton';

// "Collect by mobile money or card" on a loan (extras slice A3, issue #216; spec 3.2 and 3.10): staff enter the amount and the
// payer's mobile number, the screen shows the payment link with a QR code and a copy button, and then follows the payment live. The
// money goes into the business's own payment account (Receive payments), never the platform's; a business that has not connected and
// tested its own account gets a plain refusal here and nothing is created. When the payment is recorded the receipt number of the
// repayment is shown, exactly as for a repayment entered by hand. The same screen collects a savings deposit on a savings account
// (extras slice A4, issue #219): only the target differs (what is started, which pages to refresh, where the receipt is read from).

interface CollectDraft { amount: string; phone: string }

/** The phone as Uganda writes it: 07XXXXXXXX, 2567XXXXXXXX or +2567XXXXXXXX. The server checks it again. */
export const looksLikeUgandaMobile = (phone: string): boolean => /^(\+?256|0)7\d{8}$/.test(phone.replace(/[\s-]/g, ''));

/** What a collection is for: the loan or the savings account, how it is started, and what to refresh and read once it is booked. */
export interface CollectTarget {
  kind: CollectKind;
  id: string;
  currency: string | undefined;
  /** The amount offered first, in minor units, or null. */
  suggested: number | null;
  /** The sentence under the amount box. */
  amountHint: string;
  /** What the payer is paying into, in a few words ("this loan", "this savings account"). */
  intro: string;
  start: (amountMinor: number, phone: string, key: string) => Promise<PaymentIntent>;
  /** The queries that are stale once the payment is booked. */
  invalidate: QueryKey[];
  /** The receipt query key and fetch for the booked transaction. */
  receiptKey: QueryKey;
  receipts: () => Promise<Array<{ id?: string; receipt_no?: string; amount_minor?: number; currency?: string }>>;
}

export function CollectPanel({ loan, schedule }: { loan: Loan; schedule: LoanSchedule | undefined }) {
  const loanId = loan.id ?? '';
  const suggested = suggestedAmount(loan.balances, schedule);
  const target: CollectTarget = {
    kind: 'loan',
    id: loanId,
    currency: loan.currency,
    suggested,
    amountHint:
      suggested !== null
        ? `The instalment due or the arrears is offered first. The loan still owes ${money(loan.balances?.total_outstanding_minor, loan.currency)} in all.`
        : 'Enter the amount the payer will pay.',
    intro: 'the loan',
    start: (amount, phone, key) => payments.startLoanRepayment(loanId, amount, phone, key),
    invalidate: [['lending', 'loan', loanId], ['lending', 'loans']],
    receiptKey: ['lending', 'loan', loanId, 'transactions', 'receipt'],
    receipts: () => lending.listTransactions(loanId),
  };
  return <CollectForm target={target} />;
}

/** "Collect by mobile money or card" on a savings account (issue #219): the deposit is booked like a cashier's once it is paid. */
export function SavingsCollectPanel({ account }: { account: SavingsAccount }) {
  const accountId = account.id ?? '';
  const target: CollectTarget = {
    kind: 'savings',
    id: accountId,
    currency: account.currency,
    suggested: null,
    amountHint: "Enter the amount the payer will pay in. A first deposit must meet the product's minimum opening balance.",
    intro: 'the savings account',
    start: (amount, phone, key) => payments.startSavingsDeposit(accountId, amount, phone, key),
    invalidate: [['savings', 'account', accountId], ['savings', 'accounts']],
    receiptKey: ['savings', 'account', accountId, 'transactions', 'receipt'],
    receipts: async () => (await savings.listTransactions(accountId)).items,
  };
  return <CollectForm target={target} />;
}

export function CollectForm({ target }: { target: CollectTarget }) {
  const { me } = useStaff();
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<CollectDraft>(`${target.kind === 'loan' ? 'lending' : target.kind}-collect:${me.user_id ?? ''}:${target.id}`, {
    amount: target.suggested === null ? '' : String(target.suggested),
    phone: '',
  });
  const [intent, setIntent] = useState<PaymentIntent | null>(null);
  const amountMinor = parseAmount(draft.amount, target.currency);
  const valid = amountMinor !== null && looksLikeUgandaMobile(draft.phone);
  const start = useMutation({
    mutationFn: () => target.start(amountMinor ?? 0, draft.phone.trim(), key),
    onSuccess: (made) => {
      // The payer's number is not kept in the tab once the link exists; the intent holds it masked.
      finish();
      setIntent(made);
    },
  });

  if (intent) {
    return (
      <CollectStatus
        target={target}
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
        {` ${target.intro} `}until the payment is confirmed.
      </p>
      <label htmlFor="collect-amount">Amount ({target.currency ?? 'UGX'})</label>
      <input
        id="collect-amount"
        inputMode="decimal"
        value={draft.amount}
        onChange={(e) => setDraft((d) => ({ ...d, amount: e.target.value }))}
        aria-invalid={draft.amount !== '' && amountMinor === null}
        aria-describedby="collect-amount-hint"
      />
      <p id="collect-amount-hint" className="field-hint">
        {target.amountHint}
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
      <BusyButton busy={start.isPending} type="submit" className="rt-primary" disabled={!valid}>
        Make the payment link
      </BusyButton>
    </form>
  );
}

/** The link, the QR code and the live status of one collection. */
export function CollectStatus({ target, initial, onNew }: { target: CollectTarget; initial: PaymentIntent; onNew: () => void }) {
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
  // Once the payment is booked the subject, its schedule or balance and its transactions are stale: fetch them again, once.
  const booked = status === 'booked';
  useEffect(() => {
    if (booked) {
      for (const queryKey of target.invalidate) void queryClient.invalidateQueries({ queryKey });
    }
  }, [booked, target.id, queryClient]);
  const txns = useQuery({
    queryKey: [...target.receiptKey, intent.booked_transaction_id],
    queryFn: target.receipts,
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
        <strong>{money(intent.amount_minor, intent.currency ?? target.currency)}</strong> from {intent.payer_phone}
      </p>
      <p role="status">{statusWords(intent, target.kind)}</p>
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
              Receipt <strong>{receipt.receipt_no}</strong>, {money(receipt.amount_minor, receipt.currency ?? target.currency)}.{' '}
              {target.kind === 'loan'
                ? 'The receipt text goes to the borrower if the business sends texts.'
                : 'The receipt text goes to the member if the business sends texts.'}
            </>
          ) : target.kind === 'loan' ? (
            'The repayment is on the loan: see Transactions below for its receipt.'
          ) : (
            'The deposit is on the account: see Transactions below for its receipt.'
          )}
        </p>
      )}
      <Problem error={cancel.error ?? live.error} />
      <div className="ln-actions">
        {isCancellable(status) && (
          <BusyButton busy={cancel.isPending} type="button" onClick={() => cancel.mutate()}>
            Cancel this payment
          </BusyButton>
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
