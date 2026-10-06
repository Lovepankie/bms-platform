import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, retail, type Product, type Transfer } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { showQty } from './maths';
import { buildTransfer, transferLineHint, transferProblem, type TransferDraft } from './transfer-state';
import { BranchRequired, Gate, NoStockHere, ProductPicker, Problem, money, useProfitAccess, useSingleBranch } from './ui';

// Move stock to another branch (FR-RET-16). The branch chosen at the top of the page is the one the
// stock leaves; it moves at cost, so nothing is gained or lost. One idempotency key per draft, kept
// with the draft in this tab until the move is saved or cleared, as on the sale form (#77).

/** "Test Branch Two (BR2)" for a branch the session knows (#105), or a plain fallback. */
export function useBranchLabel(): (id: string | undefined) => string {
  const { me } = useStaff();
  return (id) => branchLabel((me.branches ?? []).find((x) => x.id === id)) || 'another branch';
}

export function TransferForm({ fromBranchId, onSaved }: { fromBranchId: string; onSaved?: (t: Transfer) => void }) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  const label = useBranchLabel();
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<TransferDraft>(
    `transfer:${me.user_id ?? ''}:${fromBranchId}`,
    { toBranchId: '', transferDate: businessToday(), note: '', lines: [] },
  );
  const save = useMutation({
    mutationFn: () => retail.createTransfer(buildTransfer(fromBranchId, draft), key),
    onSuccess: (t) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved?.(t);
    },
  });
  const update = (patch: Partial<TransferDraft>) => setDraft((d) => ({ ...d, ...patch }));
  const add = (p: Product) =>
    setDraft((d) => (d.lines.some((l) => l.product.id === p.id) ? d : { ...d, lines: [...d.lines, { product: p, qty: '1' }] }));
  const destinations = (me.branches ?? []).filter((b) => b.id !== fromBranchId);
  const problem = transferProblem(fromBranchId, draft);

  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!problem && !save.isPending) save.mutate(); }}>
      <p>From: {label(fromBranchId)}</p>
      <NoStockHere branchId={fromBranchId} />
      <label htmlFor="transfer-to">To branch</label>
      <select id="transfer-to" value={draft.toBranchId} onChange={(e) => update({ toBranchId: e.target.value })}>
        <option value="">Choose a branch</option>
        {destinations.map((b) => (
          <option key={b.id} value={b.id}>{label(b.id)}</option>
        ))}
      </select>
      {destinations.length === 0 && <p>You have no other branch to move stock to.</p>}

      <ProductPicker id="transfer-search" branchId={fromBranchId} onAdd={add} />

      <h2>Items to move</h2>
      {draft.lines.length === 0 && <p>No items yet. Search above and tap Add.</p>}
      {draft.lines.map((l, i) => {
        const hint = transferLineHint(l);
        return (
          <div key={l.product.id} className="rt-card">
            <strong>{l.product.description}</strong>
            <label htmlFor={`tq-${i}`}>Quantity ({l.product.unit})</label>
            <input id={`tq-${i}`} inputMode="decimal" value={l.qty} aria-invalid={hint !== null}
              onChange={(e) => setDraft((d) => ({ ...d, lines: d.lines.map((x, n) => (n === i ? { ...x, qty: e.target.value } : x)) }))} />
            {hint && <p role="alert" className="rt-flag">{hint}</p>}
            <button type="button" aria-label={`Remove ${l.product.description}`}
              onClick={() => setDraft((d) => ({ ...d, lines: d.lines.filter((_, n) => n !== i) }))}>Remove</button>
          </div>
        );
      })}

      <label htmlFor="transfer-date">Date</label>
      <input id="transfer-date" type="date" value={draft.transferDate} onChange={(e) => update({ transferDate: e.target.value })} />
      <label htmlFor="transfer-note">Note (optional)</label>
      <input id="transfer-note" maxLength={300} value={draft.note} onChange={(e) => update({ note: e.target.value })} />

      {problem && draft.lines.length > 0 && <p>{problem}</p>}
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={problem !== null || save.isPending}>
        {save.isPending ? 'Saving' : 'Move stock'}
      </button>
      {(draft.lines.length > 0 || draft.toBranchId !== '') && !save.isPending && (
        <button type="button" onClick={() => { discard(); save.reset(); }}>Clear this form</button>
      )}
    </form>
  );
}

export function TransferSummary({ transfer, onNew }: { transfer: Transfer; onNew?: () => void }) {
  const label = useBranchLabel();
  const profit = useProfitAccess();
  return (
    <section aria-label="Saved">
      <h2>{transfer.status === 'voided' ? 'Cancelled' : 'Stock moved'}</h2>
      <p>From {label(transfer.from_branch_id)} to {label(transfer.to_branch_id)} on {transfer.transfer_date}.</p>
      <ul>
        {(transfer.lines ?? []).map((l) => (
          <li key={l.line_no}>{l.description} ({l.code}): {showQty(l.qty ?? '0')}</li>
        ))}
      </ul>
      {transfer.note && <p>Note: {transfer.note}</p>}
      {profit && transfer.cost_total_minor !== undefined && <p>Value at cost: {money(transfer.cost_total_minor)}</p>}
      {transfer.status === 'voided' && <p>Cancelled: {transfer.void_reason}</p>}
      {onNew && <button type="button" className="rt-primary" onClick={onNew}>Move more stock</button>}
    </section>
  );
}

function MoveStock() {
  const { branchId } = useSingleBranch();
  const [done, setDone] = useState<Transfer | null>(null);
  const [round, setRound] = useState(0);
  return (
    <Gate screen="transfer" title="Move stock">
      {branchId === null ? (
        <BranchRequired />
      ) : done ? (
        <TransferSummary transfer={done} onNew={() => { setDone(null); setRound((n) => n + 1); }} />
      ) : (
        <TransferForm key={`${branchId}-${round}`} fromBranchId={branchId} onSaved={setDone} />
      )}
      <p><Link to={'/staff/retail/transfers' as never}>See stock moves</Link></p>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/transfer')({ component: MoveStock });
