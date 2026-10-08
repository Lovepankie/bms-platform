import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useId, useRef, useState, type ReactNode } from 'react';
import { businessToday, daysBefore } from '../../../api/retail';
import { useStaff } from '../context';
import { enteredBy, groupByShopMonth, savedFor, voidOffered } from './cash-state';
import { newIdempotencyKey } from './idempotency';
import { CASHBOOK_VOID, branchesWhere, holds } from './permissions';
import { Problem, useBranchName, useBranchView } from './ui';

// Pieces shared by the cash book screens (ADR-022): a date field that cannot be in the future, the
// date window of a list or report, the success message, the Void action and the records grouped by
// shop, year and month.

/** A date in the business zone, never after today (the server refuses a future date). */
export function DateField({ id, label, value, onChange }: { id: string; label: string; value: string; onChange: (value: string) => void }) {
  return (
    <>
      <label htmlFor={id}>{label}</label>
      <input id={id} type="date" value={value} max={businessToday()} onChange={(e) => onChange(e.target.value)} />
    </>
  );
}

/** The branches a list or report reads: the one chosen in the Branch box, or none listed (every branch in scope) for All branches. */
export function useReadScope(): string[] | undefined {
  const { branchId } = useBranchView();
  return branchId ? [branchId] : undefined;
}

/** The date window of a list or report: the last `days` days ending today in the business zone. */
export function useWindow(days: number = 30): { from: string; to: string; setFrom: (v: string) => void; setTo: (v: string) => void; query: { from: string; to: string } } {
  const [from, setFrom] = useState(() => daysBefore(businessToday(), days));
  const [to, setTo] = useState(() => businessToday());
  return { from, to, setFrom, setTo, query: { from, to } };
}

export function WindowFields({ id, window }: { id: string; window: ReturnType<typeof useWindow> }) {
  return (
    <div className="rt-row">
      <div>
        <DateField id={`${id}-from`} label="From" value={window.from} onChange={window.setFrom} />
      </div>
      <div>
        <DateField id={`${id}-to`} label="To" value={window.to} onChange={window.setTo} />
      </div>
    </div>
  );
}

/** A day or record moved across from the pilot app: shown apart and labelled, never mixed into the live figures. */
export function ImportedBadge() {
  return <span className="badge badge-info">Imported from the old app</span>;
}

/** The on-screen success message that replaces a form once it is saved (a toast is raised on top of it). */
export function Saved({ title, children, notes, again, onAgain }: { title: string; children?: ReactNode; notes?: ReactNode; again: string; onAgain: () => void }) {
  return (
    <section aria-label={title}>
      <div role="status" className="alert alert-success">
        <div className="stack">
          <strong>{title}</strong>
          {children}
        </div>
      </div>
      {notes}
      <button type="button" className="rt-primary" onClick={onAgain}>
        {again}
      </button>
    </section>
  );
}

/** The warnings of a save in words, each in a note. */
export function Warnings({ words }: { words: string[] }) {
  return (
    <>
      {words.map((w) => (
        <div key={w} role="note" className="alert alert-warning">
          <p>{w}</p>
        </div>
      ))}
    </>
  );
}

/**
 * Voids a record with a reason (FR-RET-28). One Idempotency-Key per attempt: a retry after a lost
 * answer reuses it, and changing the reason starts a fresh one.
 */
export function VoidAction({ what, run, onVoided }: { what: string; run: (reason: string, key: string) => Promise<unknown>; onVoided?: () => void }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const key = useRef(newIdempotencyKey());
  const id = useId();
  const void_ = useMutation({
    mutationFn: () => run(reason.trim(), key.current),
    onSuccess: () => {
      key.current = newIdempotencyKey();
      setOpen(false);
      setReason('');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onVoided?.();
    },
  });
  if (!open) {
    return (
      <button type="button" className="btn-sm" onClick={() => setOpen(true)}>
        Void
      </button>
    );
  }
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (reason.trim() !== '' && !void_.isPending) void_.mutate();
      }}
    >
      <label htmlFor={id}>Why is this {what} being voided?</label>
      <input id={id} maxLength={300} value={reason} onChange={(e) => { key.current = newIdempotencyKey(); setReason(e.target.value); }} />
      <Problem error={void_.error} />
      <div className="cluster">
        <button type="submit" className="btn-sm" disabled={reason.trim() === '' || void_.isPending}>
          {void_.isPending ? 'Voiding' : 'Confirm void'}
        </button>
        <button type="button" className="btn-sm" onClick={() => { setOpen(false); setReason(''); void_.reset(); }}>
          Keep it
        </button>
      </div>
    </form>
  );
}

/**
 * The panel a screen shows after a save, kept with the shop it was saved for: when the selected shop changes the
 * panel is gone, so the person never sees the last shop's figures under another shop's name.
 */
export function useSavedFor<T>(branchId: string | null): [T | null, (value: T | null) => void] {
  const [state, setState] = useState<{ branchId: string | null; value: T } | null>(null);
  return [savedFor(state, branchId), (value) => setState(value === null ? null : { branchId, value })];
}

/** Whether the session may void a record of `branchId`: it holds retail.cashbook.void there. */
export function useMayVoid(): (branchId: string | undefined) => boolean {
  const { me } = useStaff();
  return (branchId) => holds(me, CASHBOOK_VOID) && branchesWhere(me, CASHBOOK_VOID).some((b) => b.id === branchId);
}

interface RecordLike { id?: string; branch_id?: string; business_date?: string; voided?: boolean; void_reason?: string; historical?: boolean; by_name?: string; at?: string }

/**
 * Records grouped by shop, year and month (FR-RET-31). Each row is drawn by `renderRow`; below it come
 * who entered it, an imported or voided label and, for a holder of retail.cashbook.void in that shop,
 * the Void action when `voidWith` is given.
 */
export function RecordsByShop<T extends RecordLike>({ rows, empty, what, renderRow, voidWith, onVoided }: {
  rows: T[];
  empty: string;
  what: string;
  renderRow: (row: T) => ReactNode;
  voidWith?: (row: T, reason: string, key: string) => Promise<unknown>;
  onVoided?: () => void;
}) {
  const shopName = useBranchName();
  const mayVoidIn = useMayVoid();
  if (rows.length === 0) return <p className="empty-state">{empty}</p>;
  const mayVoid = (row: T) => mayVoidIn(row.branch_id);
  return (
    <>
      {groupByShopMonth(rows, shopName).map((shop) => (
        <section key={shop.branchId} aria-label={shop.shop}>
          <h3>{shop.shop}</h3>
          {shop.months.map((month) => (
            <div key={`${month.year}-${month.month}`}>
              <h4>{month.label}</h4>
              <ul style={{ listStyle: 'none', padding: 0 }}>
                {month.rows.map((row) => (
                  <li key={row.id} className="rt-card">
                    {renderRow(row)}
                    <p className="hint">
                      {enteredBy(row.by_name, row.at)}
                      {row.historical && <> <ImportedBadge /></>}
                      {row.voided && <> <span className="badge badge-warning">Voided</span>{row.void_reason ? ` because: ${row.void_reason}` : ''}</>}
                    </p>
                    {voidWith && voidOffered(row, mayVoid(row)) && (
                      <VoidAction what={what} run={(reason, key) => voidWith(row, reason, key)} onVoided={onVoided} />
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </section>
      ))}
    </>
  );
}
