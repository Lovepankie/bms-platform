import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { type ReceiptStatusFilter, type UnallocatedReceipt, receipts } from '../../../api/payments';
import { money } from '../lending/loan-state';
import { useStaff } from '../context';
import {
  ALLOCATE_PURPOSES,
  REFUND_METHODS,
  STATUS_FILTERS,
  canAskAllocate,
  reasonProblem,
  referenceProblem,
  refusalWords,
  showUnallocated,
  subjectProblem,
} from './state';

// "Unallocated payments" (extras slice A5, issue #224; FR-PAY-04): money the payment provider confirmed that the loan or account would
// not take. A cashier asks to put it on a loan or savings account, or records that it was sent back; nothing moves until a second
// person approves it in the approvals inbox. Every identifier and reference on screen is the business's own; the payer's phone number is
// never part of the answer.

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong.');

function when(iso: string | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' });
}

type Action = 'allocate' | 'refund';

export function ReceiptActionForm({ receipt, action, onDone, onCancel }: { receipt: UnallocatedReceipt; action: Action; onDone: () => void; onCancel: () => void }) {
  const [purpose, setPurpose] = useState(ALLOCATE_PURPOSES[0]?.value ?? '');
  const [subject, setSubject] = useState('');
  const [method, setMethod] = useState(REFUND_METHODS[0]?.value ?? '');
  const [reference, setReference] = useState('');
  const [reason, setReason] = useState('');
  // One key per form: a second click or a retry asks for the same thing once.
  const key = useRef(`ur-${receipt.id}-${action}-${Math.random().toString(36).slice(2)}`);
  // A second click before the screen has redrawn must not send a second request (the key would make it harmless, but not silent).
  const sending = useRef(false);
  const send = () =>
    action === 'allocate'
      ? receipts.allocate(receipt.id ?? '', purpose, subject.trim(), reason.trim(), key.current)
      : receipts.refund(receipt.id ?? '', method, reference.trim(), reason.trim(), key.current);
  const ask = useMutation({
    mutationFn: async () => {
      if (sending.current) return null;
      sending.current = true;
      try {
        return await send();
      } finally {
        sending.current = false;
      }
    },
    onSuccess: (outcome) => {
      if (outcome) onDone();
    },
  });
  const problems = [action === 'allocate' ? subjectProblem(subject) : referenceProblem(reference), reasonProblem(reason)];
  const ready = problems.every((p) => p === null);
  return (
    <form
      className="stack"
      aria-label={action === 'allocate' ? 'Put this payment on a loan or account' : 'Record that this payment was sent back'}
      onSubmit={(e) => {
        e.preventDefault();
        if (ready && !ask.isPending) ask.mutate();
      }}
    >
      {action === 'allocate' ? (
        <>
          <label htmlFor={`purpose-${receipt.id}`}>It is</label>
          <select id={`purpose-${receipt.id}`} value={purpose} onChange={(e) => setPurpose(e.target.value)}>
            {ALLOCATE_PURPOSES.map((p) => (
              <option key={p.value} value={p.value}>{p.label}</option>
            ))}
          </select>
          <label htmlFor={`subject-${receipt.id}`}>Loan or savings account identifier</label>
          <input id={`subject-${receipt.id}`} value={subject} onChange={(e) => setSubject(e.target.value)} autoComplete="off" spellCheck={false} aria-invalid={subject !== '' && subjectProblem(subject) !== null} />
          {subject !== '' && subjectProblem(subject) && <p role="alert" className="field-error">{subjectProblem(subject)}</p>}
        </>
      ) : (
        <>
          <label htmlFor={`method-${receipt.id}`}>It went back</label>
          <select id={`method-${receipt.id}`} value={method} onChange={(e) => setMethod(e.target.value)}>
            {REFUND_METHODS.map((m) => (
              <option key={m.value} value={m.value}>{m.label}</option>
            ))}
          </select>
          <label htmlFor={`reference-${receipt.id}`}>Your reference for the refund</label>
          <input id={`reference-${receipt.id}`} value={reference} onChange={(e) => setReference(e.target.value)} autoComplete="off" spellCheck={false} aria-invalid={reference !== '' && referenceProblem(reference) !== null} />
          {reference !== '' && referenceProblem(reference) && <p role="alert" className="field-error">{referenceProblem(reference)}</p>}
          <p className="hint">The system does not send the money. Send it first, then record it here; a second person checks the record.</p>
        </>
      )}
      <label htmlFor={`reason-${receipt.id}`}>Why</label>
      <textarea id={`reason-${receipt.id}`} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={300} rows={2} />
      {reason !== '' && reasonProblem(reason) && <p role="alert" className="field-error">{reasonProblem(reason)}</p>}
      {ask.error && <p role="alert" className="alert alert-danger">{messageOf(ask.error)}</p>}
      <p className="cluster">
        <button type="submit" className="rt-primary" disabled={!ready || ask.isPending}>{ask.isPending ? 'Asking' : 'Ask for approval'}</button>
        <button type="button" className="btn-ghost" disabled={ask.isPending} onClick={onCancel}>Cancel</button>
      </p>
    </form>
  );
}

function Row({ receipt, canAsk, onAsked }: { receipt: UnallocatedReceipt; canAsk: boolean; onAsked: () => void }) {
  const [action, setAction] = useState<Action | null>(null);
  const waiting = receipt.pending_requests ?? [];
  return (
    <li className="rt-card stack" data-testid="receipt">
      <h3>
        {money(receipt.amount_minor, receipt.currency)} <span className="hint">paid {when(receipt.paid_at)}</span>
      </h3>
      <p>{refusalWords(receipt.refusal_code)}.</p>
      <dl className="facts">
        <dt>Payment code</dt>
        <dd>{receipt.confirmation_code ?? 'None'}</dd>
        <dt>Paid by</dt>
        <dd>{receipt.reported_method ?? 'Not given'}</dd>
        <dt>Our reference</dt>
        <dd>{receipt.order_reference}</dd>
        <dt>Meant for</dt>
        <dd>{receipt.intended_subject?.purpose === 'lending.savings_deposit' ? 'A savings account' : 'A loan'} ({receipt.intended_subject?.subject_id})</dd>
      </dl>
      {receipt.status === 'allocated' && receipt.allocation && <p className="badge badge-success">Put on {receipt.allocation.subject_type === 'loan' ? 'a loan' : 'a savings account'}</p>}
      {receipt.status === 'refunded' && receipt.refund && <p className="badge">Sent back, reference {receipt.refund.reference}</p>}
      {receipt.status === 'reversed' && <p className="badge">Reversed by the payment provider</p>}
      {receipt.note && <p className="hint">Reason given: {receipt.note}</p>}
      {waiting.length > 0 && (
        <p role="note" className="alert alert-warning">
          {waiting.length === 1 ? 'A request for this payment is waiting' : 'Requests for this payment are waiting'} for a second person in the approvals inbox.
        </p>
      )}
      {receipt.status === 'open' && canAsk && action === null && (
        <p className="cluster">
          <button type="button" className="rt-primary" onClick={() => setAction('allocate')}>Put on a loan or account</button>
          <button type="button" className="btn-ghost" onClick={() => setAction('refund')}>Record a refund</button>
        </p>
      )}
      {action && (
        <ReceiptActionForm
          receipt={receipt}
          action={action}
          onCancel={() => setAction(null)}
          onDone={() => {
            setAction(null);
            onAsked();
          }}
        />
      )}
    </li>
  );
}

export function UnallocatedPage() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<ReceiptStatusFilter>('open');
  const [asked, setAsked] = useState(false);
  const allowed = showUnallocated(me);
  const query = useQuery({ queryKey: ['payments', 'unallocated', status], queryFn: () => receipts.list(status), enabled: allowed });
  if (!allowed) {
    return (
      <main className="rt ln">
        <h1>Unallocated payments</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  const items = query.data?.items ?? [];
  return (
    <main className="rt ln stack">
      <h1>Unallocated payments</h1>
      <p>
        Money your members paid by mobile money or card that the loan or account would not take. It is safe in your own payment account. Put it where it belongs, or record that it
        was sent back. Either way a second person approves it before anything changes.
      </p>
      <label htmlFor="receipt-status">Show</label>
      <select id="receipt-status" value={status} onChange={(e) => setStatus(e.target.value as ReceiptStatusFilter)}>
        {STATUS_FILTERS.map((f) => (
          <option key={f.value} value={f.value}>{f.label}</option>
        ))}
      </select>
      {asked && <p role="status" className="alert alert-success">Asked. A second person now decides it in the approvals inbox.</p>}
      {query.error && <p role="alert" className="alert alert-danger">{messageOf(query.error)}</p>}
      {!query.data && !query.error && <p className="loading">Loading</p>}
      {query.data && items.length === 0 && <p className="empty-state">{status === 'open' ? 'Nothing is waiting.' : 'Nothing to show.'}</p>}
      <ul className="stack list-plain">
        {items.map((r) => (
          <Row
            key={r.id}
            receipt={r}
            canAsk={canAskAllocate(me)}
            onAsked={() => {
              setAsked(true);
              void queryClient.invalidateQueries({ queryKey: ['payments', 'unallocated'] });
            }}
          />
        ))}
      </ul>
    </main>
  );
}

export const Route = createLazyRoute('/staff/payments/unallocated')({ component: UnallocatedPage });
