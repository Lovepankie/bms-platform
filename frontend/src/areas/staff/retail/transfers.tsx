import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type Transfer } from '../../../api/retail';
import { useStaff } from '../context';
import { canUse } from './permissions';
import { TransferSummary, useBranchLabel } from './transfer';
import { Gate, Problem, useSingleBranch } from './ui';

// Stock moves (FR-RET-16): transfers from or to the branch chosen at the top of the page (every
// branch the session may read with "All branches"), newest first. Tapping one shows its items, and
// a user who may move stock from its source branch can cancel it while the destination still holds
// what arrived; the server refuses otherwise, in plain words.

const itemCount = (t: Transfer): string => {
  const n = (t.lines ?? []).length;
  return `${n} ${n === 1 ? 'item' : 'items'}`;
};

export function TransferList({ items, onOpen }: { items: Transfer[]; onOpen: (id: string) => void }) {
  const label = useBranchLabel();
  if (items.length === 0) return <p>No stock has been moved yet.</p>;
  return (
    <ul style={{ listStyle: 'none', padding: 0 }}>
      {items.map((t) => (
        <li key={t.id} className="rt-card">
          <button type="button" style={{ width: '100%', textAlign: 'left' }} onClick={() => onOpen(t.id ?? '')}>
            <strong>{t.transfer_date}</strong>: {label(t.from_branch_id)} to {label(t.to_branch_id)}, {itemCount(t)}
            {t.status === 'voided' && <span className="rt-flag"> cancelled</span>}
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
  const mayCancel = canUse(me, 'transfer') && t?.status === 'completed';
  return (
    <section>
      <button type="button" onClick={onClose}>Back to the list</button>
      {transfer.isError && <Problem error={transfer.error} />}
      {t && <TransferSummary transfer={t} />}
      {mayCancel && (
        <form onSubmit={(e) => { e.preventDefault(); if (reason.trim() !== '' && !cancel.isPending) cancel.mutate(); }}>
          <label htmlFor="transfer-void-reason">Reason to cancel this move</label>
          <input id="transfer-void-reason" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />
          <Problem error={cancel.error} />
          <button type="submit" disabled={reason.trim() === '' || cancel.isPending}>
            {cancel.isPending ? 'Cancelling' : 'Cancel this move'}
          </button>
        </form>
      )}
    </section>
  );
}

function StockMoves() {
  const { branchId } = useSingleBranch();
  const [open, setOpen] = useState<string | null>(null);
  const page = useQuery({
    queryKey: ['retail', 'transfers', branchId],
    queryFn: () => retail.listTransfers({ branchId: branchId ?? undefined }),
  });
  return (
    <Gate screen="transfers" title="Stock moves">
      {open ? (
        <TransferDetail id={open} onClose={() => setOpen(null)} />
      ) : (
        <>
          {page.isError && <Problem error={page.error} />}
          {page.data && <TransferList items={page.data.items ?? []} onOpen={setOpen} />}
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/transfers')({ component: StockMoves });
