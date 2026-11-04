import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useId, useRef, useState } from 'react';
import type { Me } from '../../../api/client';
import { RetailError, businessToday, retail, type StockMovement, type UsageUndone } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { icons } from '../../../components/icons';
import { useStaff } from '../context';
import { SALE_VOID, holdsAt } from './permissions';
import { showDate } from './maths';
import { Problem } from './ui';

// Undo a wrong "used in the shop" or damage entry (issue #818, ADR-123), the way a sale is deleted: the same permission
// (retail.sale.void at the entry's shop), a reason, and the entry stays in the list marked Undone with who, when and why.
// The server puts every item of the entry back into stock at the cost it left at; a second undo is refused.

/** Whether `me` may undo the entry this movement belongs to: an entry made in BMS, not undone yet, at a shop where they may delete a sale. */
export function undoOffered(me: Pick<Me, 'permissions' | 'permission_scopes' | 'branches'>, m: StockMovement): boolean {
  return m.source_type === 'retail.usage' && !!m.source_id && !m.historical && !m.undone && holdsAt(me, SALE_VOID, m.branch_id);
}

/** "Undone by Test Admin on 3 Nov 2026 because: Counted twice", or without the name when the server has none. */
export function undoneWords(m: StockMovement): string {
  const u = m.undone;
  if (!u) return '';
  // The business day of the undo, on the business's clock, as the list dates its rows.
  const at = new Date(u.at ?? '');
  const day = Number.isNaN(at.getTime()) ? '' : showDate(businessToday(at));
  return `Undone${u.by_name ? ` by ${u.by_name}` : ''}${day ? ` on ${day}` : ''}${u.reason ? ` because: ${u.reason}` : ''}`;
}

/** The Undone status: a tinted badge with an icon and the word, never colour alone. */
export function UndoneMark() {
  return (
    <span className="badge badge-warning usage-undone" data-testid="usage-undone">
      <span aria-hidden="true">{icons.refresh}</span>
      Undone
    </span>
  );
}

/**
 * What the form says goes back, from the server's count of the entry's lines (`entry_lines`): the list may be filtered to one
 * item or split across pages, so it never counts them itself. Without a count it names no number.
 */
export function undoWhat(entryLines: number | undefined): string {
  if (entryLines === undefined) return 'Every item of this entry goes back into stock.';
  return entryLines > 1 ? `All ${entryLines} items of this entry go back into stock.` : 'This item goes back into stock.';
}

/** The toast, only from the server's count of the items that went back (`items_back`). */
export function undoneToast(itemsBack: number | undefined): string {
  const n = itemsBack ?? 1;
  return `Entry undone, ${n === 1 ? '1 item' : `${n} items`} put back`;
}

/** The reason form: what will happen in plain words, a required reason, and one clear button. */
export function UndoFormView({ entryLines, busy, error, onUndo, onCancel }: { entryLines?: number; busy?: boolean; error?: unknown; onUndo: (reason: string) => void; onCancel: () => void }) {
  const [reason, setReason] = useState('');
  const id = useId();
  const ready = reason.trim();
  return (
    <form
      className="stack"
      aria-label="Undo this entry"
      onSubmit={(e) => {
        e.preventDefault();
        if (ready !== '' && !busy) onUndo(ready);
      }}
    >
      <p className="hint" data-testid="undo-what">{undoWhat(entryLines)}</p>
      <p className="hint">The entry stays here, marked Undone.</p>
      <label htmlFor={id}>Why is this entry wrong?</label>
      <input id={id} value={reason} maxLength={300} required onChange={(e) => setReason(e.target.value)} />
      <Problem error={error} />
      <p className="cluster">
        <BusyButton type="submit" busy={busy === true} notReady={ready === ''}>Yes, undo it</BusyButton>
        <button type="button" /* busy-exempt: keep waits for the undo */ className="btn-ghost" disabled={busy} onClick={onCancel}>Keep the entry</button>
      </p>
    </form>
  );
}

/** Undo this entry, for whoever may delete a sale at the entry's shop; nothing shows for anyone else or once it is undone. */
export function UndoUsageAction({ m }: { m: StockMovement }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  // Undone, or found undone already: the button stays away until the reloaded list says Undone.
  const [gone, setGone] = useState(false);
  const sending = useRef(false);
  const send = useMutation({
    mutationFn: (reason: string) => retail.undoUsage(m.source_id ?? '', reason),
    onSuccess: () => {
      setGone(true);
      setOpen(false);
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
    onError: (error) => {
      // An undo that already went through (a lost answer, a second person) is done, not an error.
      if (error instanceof RetailError && error.code === 'usage_undone') {
        setGone(true);
        setOpen(false);
        void queryClient.invalidateQueries({ queryKey: ['retail'] });
      }
    },
    onSettled: () => { sending.current = false; },
    meta: { success: (done: UsageUndone) => undoneToast(done.items_back) },
  });
  if (gone || !undoOffered(me, m)) return null;
  if (!open) return <p><button type="button" className="btn-sm" onClick={() => { send.reset(); setOpen(true); }}>Undo this entry</button></p>;
  return <UndoFormView entryLines={m.entry_lines} busy={send.isPending} error={send.error} onUndo={(reason) => {
    if (sending.current) return;
    sending.current = true;
    send.mutate(reason);
  }} onCancel={() => setOpen(false)} />;
}
