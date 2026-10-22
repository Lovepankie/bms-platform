import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, useRouter } from '@tanstack/react-router';
import { useEffect, useMemo, useRef, useState } from 'react';
import { businessToday, retail, type Product, type Transfer } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { ToStock } from './analytics-ui';
import { parseQty, showQty } from './maths';
import { buildTransfer, transferLineHint, transferProblem, type TransferDraft } from './transfer-state';
import { milliOf } from './maths';
import { TRANSFER, TRANSFER_RECEIPT, UNITS, capabilityOn, holdsAt, needsOf } from './permissions';
import { BranchRequired, Gate, NoStockHere, ProductPicker, Problem, money, useProfitAccess, useStockingBranch } from './ui';
import { ScanBar } from './scan';
import { PACK_UNUSABLE, plusOne, scanInto } from './barcode-state';
import { chosenPack } from './units-state';
import { BusyButton } from '../../../app/ui/BusyButton';

// Move stock to another branch (FR-RET-16). The branch chosen at the top of the page is the one the
// stock leaves; it moves at cost, so nothing is gained or lost. One idempotency key per draft, kept
// with the draft in this tab until the move is saved or cleared, as on the sale form (#77).

/** "Test Branch Two (BR2)" for a branch the session knows (#105), or a plain fallback. */
export function useBranchLabel(): (id: string | undefined) => string {
  const { me } = useStaff();
  return (id) => branchLabel((me.branches ?? []).find((x) => x.id === id)) || 'another branch';
}

/** What a "Sells better at" link on Stock health (issue #414) fills in: the destination and one item with its quantity. */
export interface TransferPrefill {
  to: string;
  product: string;
  code: string;
  qty: string;
}

/** The prefill in the address, or null; every value a plain string, as the link writes them. */
export function prefillOf(params: Record<string, unknown>): (TransferPrefill & { from: string }) | null {
  const text = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? String(v) : '');
  const out = { from: text(params.from), to: text(params.to), product: text(params.product), code: text(params.code), qty: text(params.qty) };
  return out.to && out.product && out.qty ? out : null;
}

export function TransferForm({ fromBranchId, onSaved, prefill }: { fromBranchId: string; onSaved?: (t: Transfer) => void; prefill?: TransferPrefill | null }) {
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
  // A scan (issue #345): the item at 1, or one more on its line; a move is counted in the item's own unit, so a barcode
  // on a pack adds the pack's factor (a carton of 12 adds 12). A pack it cannot use is refused, never moved as one piece.
  const unitsOn = capabilityOn(me, 'retail', UNITS);
  const scanAdd = (p: Product, unitId?: string) => {
    const pack = unitsOn ? chosenPack(p, unitId) : undefined;
    if (unitId && !pack) return PACK_UNUSABLE;
    const step = pack ? (parseQty(pack.factor ?? '') ?? 1000) : 1000;
    setDraft((d) => ({ ...d, lines: scanInto(d.lines, p, undefined, (l) => ({ ...l, qty: plusOne(l.qty, step) }), () => ({ product: p, qty: plusOne('', step) })) }));
    return undefined;
  };
  // Memoised so the prefill effect below does not run again on every render.
  const destinations = useMemo(
    () => (me.branches ?? []).filter((b) => b.id !== fromBranchId && b.stocks_goods !== false),
    [me.branches, fromBranchId],
  );
  // A link from Stock health fills an empty form once: the branch it sells better at and the item with the suggested quantity.
  // The item is read with this branch's stock, so the usual hint shows if the stock has gone since.
  const prefilled = useRef(false);
  useEffect(() => {
    if (!prefill || prefilled.current || draft.lines.length > 0 || draft.toBranchId !== '') return;
    prefilled.current = true;
    if (destinations.some((b) => b.id === prefill.to)) setDraft((d) => ({ ...d, toBranchId: prefill.to }));
    void retail.listProducts({ query: prefill.code, branchId: fromBranchId }).then((found) => {
      const p = found.find((x) => x.id === prefill.product);
      if (p) setDraft((d) => (d.lines.length > 0 ? d : { ...d, lines: [{ product: p, qty: prefill.qty }] }));
    }, () => undefined);
  }, [prefill, draft.lines.length, draft.toBranchId, destinations, fromBranchId, setDraft]);
  const problem = transferProblem(fromBranchId, draft);
  // With the two-step transfer on (ADR-033) the stock leaves now and is counted at the destination once received.
  const sends = capabilityOn(me, 'retail', TRANSFER_RECEIPT);

  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!problem && !save.isPending) save.mutate(); }}>
      <p>From: {label(fromBranchId)}</p>
      <NoStockHere branchId={fromBranchId} />
      <div data-tour="transfer-to">
      <label htmlFor="transfer-to">To branch</label>
      <select id="transfer-to" value={draft.toBranchId} onChange={(e) => update({ toBranchId: e.target.value })}>
        <option value="">Choose a branch</option>
        {destinations.map((b) => (
          <option key={b.id} value={b.id}>{label(b.id)}</option>
        ))}
      </select>
      </div>
      {destinations.length === 0 && <p>You have no other branch to move stock to.</p>}
      {sends && <p className="hint">The stock leaves this branch now. The other branch counts it once they receive it.</p>}

      <div data-tour="transfer-search">
      <ProductPicker id="transfer-search" branchId={fromBranchId} onAdd={add} />
      </div>
      <ScanBar id="transfer-scan" branchId={fromBranchId} onFound={scanAdd} />

      <h2 data-tour="transfer-items">Items to move</h2>
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
      <span data-tour="transfer-save">
        <BusyButton type="submit" className="rt-primary" busy={save.isPending} disabled={problem !== null}>
          {sends ? 'Send stock' : 'Move stock'}
        </BusyButton>

      </span>
      {(draft.lines.length > 0 || draft.toBranchId !== '') && !save.isPending && (
        <button type="button" onClick={() => { discard(); save.reset(); }}>Clear this form</button>
      )}
    </form>
  );
}

const HEADING: Record<string, string> = { voided: 'Cancelled', in_transit: 'Stock sent', received: 'Stock received', cancelled: 'Taken back' };

export function TransferSummary({ transfer, onNew }: { transfer: Transfer; onNew?: () => void }) {
  const label = useBranchLabel();
  const profit = useProfitAccess();
  return (
    <section aria-label="Saved">
      <h2>{HEADING[transfer.status ?? 'completed'] ?? 'Stock moved'}</h2>
      <p>From <ToStock>{label(transfer.from_branch_id)}</ToStock> to <ToStock>{label(transfer.to_branch_id)}</ToStock> on {transfer.transfer_date}.</p>
      {transfer.status === 'in_transit' && <p>It is on its way. It is in neither branch's stock until the other branch receives it (see On the way).</p>}
      <ul>
        {(transfer.lines ?? []).map((l) => {
          const short = l.received_qty !== undefined && milliOf(l.received_qty) < milliOf(l.qty ?? '0');
          return (
            <li key={l.line_no}>
              {l.description} ({l.code}): {showQty(l.qty ?? '0')}
              {short && <> sent, {showQty(l.received_qty ?? '0')} received</>}
            </li>
          );
        })}
      </ul>
      {transfer.note && <p>Note: {transfer.note}</p>}
      {profit && transfer.cost_total_minor !== undefined && <p>Value at cost: {money(transfer.cost_total_minor)}</p>}
      {profit && transfer.shortage_cost_minor !== undefined && transfer.shortage_cost_minor > 0 && <p>Lost on the way, at cost: {money(transfer.shortage_cost_minor)}</p>}
      {transfer.status === 'voided' && <p>Cancelled: {transfer.void_reason}</p>}
      {transfer.status === 'cancelled' && <p>Taken back: {transfer.void_reason}</p>}
      {onNew && <button type="button" className="rt-primary" onClick={onNew}>Move more stock</button>}
    </section>
  );
}

export function MoveStock() {
  const { me } = useStaff();
  const session = useStockingBranch();
  // Read once from the address this screen opened at; the form keeps its own draft after that.
  const search = useRouter({ warn: false })?.state.location.search;
  const prefill = useMemo(() => prefillOf((search ?? {}) as Record<string, unknown>), [search]);
  // The link names the branch the stock leaves; it is used when this person may move stock from there.
  const linked = prefill && (me.branches ?? []).some((b) => b.id === prefill.from && b.stocks_goods !== false) && holdsAt(me, TRANSFER, prefill.from);
  const branchId = linked ? prefill.from : session.branchId;
  const [done, setDone] = useState<Transfer | null>(null);
  const [round, setRound] = useState(0);
  return (
    <Gate screen="transfer" title="Move stock">
      {branchId === null ? (
        <BranchRequired permissions={needsOf('transfer')} stocking />
      ) : done ? (
        <TransferSummary transfer={done} onNew={() => { setDone(null); setRound((n) => n + 1); }} />
      ) : (
        <TransferForm key={`${branchId}-${round}`} fromBranchId={branchId} onSaved={setDone} prefill={round === 0 ? prefill : null} />
      )}
      <p><Link to={'/staff/retail/transfers' as never}>See stock moves</Link></p>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/transfer')({ component: MoveStock });
