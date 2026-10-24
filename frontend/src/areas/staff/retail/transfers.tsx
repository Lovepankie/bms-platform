import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type Transfer } from '../../../api/retail';
import { useCrumbTail } from '../../../components/breadcrumbs';
import { useStaff } from '../context';
import { milliOf, showDate } from './maths';
import { canUse } from './permissions';
import { TransferDocument } from './documents-ui';
import { TransferSummary, useBranchLabel } from './transfer';
import { Gate, Problem, useSingleBranch } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';

// Stock moves (FR-RET-16): transfers from or to the branch chosen at the top of the page (every
// branch the session may read with "All branches"), newest first. Tapping one shows its items, and
// a user who may move stock from its source branch can cancel it while the destination still holds
// what arrived; the server refuses otherwise, in plain words.

export { showDate };

/** "2 items: LED bulb 9W screw, Socket" with at most two names, then how many more. */
export function whatMoved(t: Transfer): string {
  const names = (t.lines ?? []).map((l) => l.description ?? l.code ?? '');
  const n = names.length;
  const more = n > 2 ? `, and ${n - 2} more` : '';
  return `${n} ${n === 1 ? 'item' : 'items'}: ${names.slice(0, 2).join(', ')}${more}`;
}

/**
 * The status in words. A void is shown as "cancelled" (the screen's word for it since the first
 * transfers); a two-step transfer (ADR-033) is "on its way" until received, "taken back" when the
 * source cancelled it before that, and "received, some short" when less arrived than was sent.
 */
export function statusBadge(t: Transfer): string | null {
  switch (t.status) {
    case 'voided':
      return 'cancelled';
    case 'in_transit':
      return 'on its way';
    case 'cancelled':
      return 'taken back';
    case 'received':
      return (t.lines ?? []).some((l) => l.received_qty !== undefined && milliOf(l.received_qty) < milliOf(l.qty ?? '0')) ? 'received, some short' : 'received';
    default:
      return null;
  }
}

// The API gives a transfer no number of its own yet, so a row shows the date, the route, what moved
// and, for the user's own moves, "by you" (#112 item 1).
export function TransferList({ items, onOpen }: { items: Transfer[]; onOpen: (id: string) => void }) {
  const label = useBranchLabel();
  const { me } = useStaff();
  if (items.length === 0) return <p className="empty-state">No stock has been moved yet.</p>;
  return (
    <ul style={{ listStyle: 'none', padding: 0 }}>
      {items.map((t) => (
        <li key={t.id} className="rt-card">
          <button type="button" className="btn-ghost" style={{ width: '100%', textAlign: 'left', display: 'block' }} onClick={() => onOpen(t.id ?? '')}>
            <strong style={{ display: 'block', whiteSpace: 'nowrap' }}>
              {showDate(t.transfer_date)}
              {statusBadge(t) && <span className="badge badge-warning"> {statusBadge(t)}</span>}
            </strong>
            <span style={{ display: 'block' }}>From {label(t.from_branch_id)} to {label(t.to_branch_id)}</span>
            <span className="muted" style={{ display: 'block' }}>
              {whatMoved(t)}
              {t.created_by && t.created_by === me.user_id ? ', by you' : ''}
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}

function TransferDetail({ id, onClose }: { id: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  const transfer = useQuery({ queryKey: ['retail', 'transfer', id], queryFn: () => retail.getTransfer(id) });
  const [reason, setReason] = useState('');
  const cancel = useMutation({
    mutationFn: () => retail.voidTransfer(id, reason.trim()),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['retail'] }),
  });
  const t = transfer.data;
  useCrumbTail(t ? `Move of ${showDate(t.transfer_date)}` : undefined, onClose);
  const mayCancel = canUse(me, 'transfer') && (t?.status === 'completed' || t?.status === 'received');
  return (
    <section>
      <button type="button" onClick={onClose}>Back to the list</button>
      {transfer.isError && <Problem error={transfer.error} />}
      {t && <TransferSummary transfer={t} />}
      {t?.id && <TransferDocument transferId={t.id} branchId={t.from_branch_id} status={t.status} />}
      {mayCancel && (
        <form onSubmit={(e) => { e.preventDefault(); if (reason.trim() !== '' && !cancel.isPending) cancel.mutate(); }}>
          <label htmlFor="transfer-void-reason">Reason to cancel this move</label>
          <input id="transfer-void-reason" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />
          <Problem error={cancel.error} />
          <BusyButton type="submit" busy={cancel.isPending} disabled={reason.trim() === ''}>
            Cancel this move
          </BusyButton>

        </form>
      )}
    </section>
  );
}

export function StockMoves() {
  const { me } = useStaff();
  const { branchId } = useSingleBranch();
  const [open, setOpen] = useState<string | null>(null);
  const page = useQuery({
    queryKey: ['retail', 'transfers', branchId],
    queryFn: () => retail.listTransfers({ branchId: branchId ?? undefined }),
  });
  return (
    <Gate screen="transfers" title="Stock moves">
      {!open && canUse(me, 'transfer') && <p data-tour="transfers-new"><Link to="/staff/retail/transfer">Move stock to another branch</Link></p>}
      <div data-tour="transfers-list">
      {open ? (
        <TransferDetail id={open} onClose={() => setOpen(null)} />
      ) : (
        <>
          {page.isError && <Problem error={page.error} />}
          {page.data && <TransferList items={page.data.items ?? []} onOpen={setOpen} />}
        </>
      )}
      </div>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/transfers')({ component: StockMoves });
