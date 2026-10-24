import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useRef, useState, type ReactNode } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { retail, type SaleReturn } from '../../../api/retail';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { MIN_REASON, reasonOf, reversalKeys } from './account-state';
import { CreditNotePdf } from './documents-ui';
import { newIdempotencyKey } from './idempotency';
import { SALE_RETURN, canOpenAccount, canUse, holdsAt } from './permissions';
import { DISPOSITION_WORDS, reasonWords, returnedWords, settledWords, type Disposition } from './returns-state';
import { showDate } from './transfers';
import { Problem, Success, money, useBranchName, useToast } from './ui';

// One credit note (returns capability, ADR-033, issue #201), on screen: the goods that came back, what each was credited,
// where they went, and how the credit was settled. The printable credit note (a PDF under this number) shows while the documents capability is on. A
// return is voided as a whole, with a reason, by whoever holds retail.sale.return at its branch; the server decides again.

/** The credit note: facts, lines and totals. Cost shows only when the server sent it (retail.profit.read). */
export function CreditNoteView({ note, nameOf, buyerOpens }: { note: SaleReturn; nameOf: (branchId: string | undefined) => string; buyerOpens?: boolean }) {
  const facts: [string, ReactNode][] = [
    ['Date', showDate(note.returned_on)],
    ['Branch', nameOf(note.branch_id)],
    ['Sale', note.sale_no ?? ''],
    ...(note.customer_name
      ? [['Buyer', buyerOpens && note.customer_id ? <Link key="buyer" to="/staff/retail/accounts/$customerId" params={{ customerId: note.customer_id }}>{note.customer_name}</Link> : note.customer_name] as [string, ReactNode]]
      : []),
    ['Settled', settledWords(note)],
    ...(note.note ? [['Note', note.note] as [string, ReactNode]] : []),
    ...(note.status === 'voided' ? [['Voided because', note.void_reason ?? ''] as [string, ReactNode]] : []),
  ];
  return (
    <section aria-label="Credit note" data-testid="credit-note" data-tour="credit-note">
      <h2>
        {note.credit_note_no} {note.status === 'voided' && <span className="badge badge-warning">Voided</span>}
      </h2>
      <div className="table-wrap"><table>
        <tbody>
          {facts.map(([k, v]) => <tr key={k}><td>{k}</td><td className="num">{v}</td></tr>)}
        </tbody>
      </table></div>
      <div className="table-wrap" tabIndex={0} data-tour="credit-note-lines"><table>
        <thead>
          <tr><th>Goods</th><th>Why, and where they went</th><th className="num">Credit</th></tr>
        </thead>
        <tbody>
          {(note.lines ?? []).map((l) => (
            <tr key={l.id}>
              <td>{returnedWords(l)}</td>
              <td>
                {reasonWords(l.reason)}
                <br />
                <span className="hint">{DISPOSITION_WORDS[(l.disposition as Disposition) ?? 'restock']}{l.disposition === 'restock' && l.restock_branch_id ? `, ${nameOf(l.restock_branch_id)}` : ''}</span>
              </td>
              <td className="num">{money(l.line_total_minor ?? 0)}</td>
            </tr>
          ))}
        </tbody>
      </table></div>
      <div className="table-wrap" data-tour="credit-note-totals"><table>
        <tbody>
          <tr><td><strong>Credit note total</strong></td><td className="num"><strong>{money(note.total_minor ?? 0)}</strong></td></tr>
          {(note.account_credit_minor ?? 0) > 0 && <tr><td>Taken off what the buyer owes</td><td className="num">{money(note.account_credit_minor ?? 0)}</td></tr>}
          {(note.refund_minor ?? 0) > 0 && <tr><td>Paid back</td><td className="num">{money(note.refund_minor ?? 0)}</td></tr>}
          {note.cost_minor !== undefined && <tr><td>Cost of the goods</td><td className="num">{money(note.cost_minor)}</td></tr>}
        </tbody>
      </table></div>
    </section>
  );
}

/** Void a return: a reason of at least five characters, then one button. The goods go out of stock again and the credit is put back. */
export function VoidPanel({ busy, error, onVoid }: { busy?: boolean; error?: unknown; onVoid: (reason: string) => void }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  if (!open) return <p data-tour="credit-note-void"><button type="button" className="btn-sm" onClick={() => setOpen(true)}>Void this credit note</button></p>;
  return (
    <form
      className="stack"
      aria-label="Void this credit note"
      onSubmit={(e) => {
        e.preventDefault();
        const why = reasonOf(reason);
        if (why && !busy) onVoid(why);
      }}
    >
      <label htmlFor="void-reason">Why is it voided? (at least {MIN_REASON} characters)</label>
      <input id="void-reason" value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} />
      <p className="hint">The goods it put back come off the shelf again, and the money or the balance goes back as it was. This only works while those goods are still in stock.</p>
      <Problem error={error} />
      <p className="cluster">
        <BusyButton busy={busy ?? false} type="submit" disabled={reasonOf(reason) === null}>Void it</BusyButton>
        <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>Cancel</button>
      </p>
    </form>
  );
}

const route = getRouteApi('/staff/retail/returns/$returnId');

export function CreditNotePage() {
  const { returnId } = route.useParams();
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const nameOf = useBranchName();
  const [message, setMessage] = useState<string | null>(null);
  const keys = useRef(reversalKeys(newIdempotencyKey));
  const allowed = canUse(me, 'returns');
  const note = useQuery({ queryKey: ['retail', 'return', returnId], queryFn: () => retail.getReturn(returnId), enabled: allowed });
  useCrumbLabel(note.data?.credit_note_no);
  const voided = useMutation({
    mutationFn: (reason: string) => retail.voidReturn(returnId, reason, keys.current.keyFor(returnId, reason)),
    onSuccess: (r) => {
      keys.current.done(returnId);
      const text = r.executed ? `Voided ${r.sale_return?.credit_note_no ?? 'the credit note'}.` : 'This return is large enough to need a second administrator. It will be voided when they approve it in Approvals.';
      setMessage(text);
      show(text, r.executed ? 'success' : 'info');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  if (!allowed) {
    return (
      <main className="rt">
        <h1>Credit note</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  return (
    <main className="rt rt-wide">
      <h1>{note.data?.credit_note_no ?? 'Credit note'}</h1>
      {note.isPending && <SkeletonList label="Loading the credit note" />}
      <Problem error={note.error} />
      {message && <Success>{message}</Success>}
      {note.data && <CreditNoteView note={note.data} nameOf={nameOf} buyerOpens={canOpenAccount(me)} />}
      {note.data && <CreditNotePdf returnId={returnId} branchId={note.data.branch_id} />}
      {note.data && note.data.status === 'completed' && holdsAt(me, SALE_RETURN, note.data.branch_id) && (
        <VoidPanel busy={voided.isPending} error={voided.error} onVoid={(reason) => voided.mutate(reason)} />
      )}
      {toast}
    </main>
  );
}

export const Route = createLazyRoute('/staff/retail/returns/$returnId')({ component: CreditNotePage });
