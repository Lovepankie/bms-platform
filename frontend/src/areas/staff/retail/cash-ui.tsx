import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useId, useRef, useState, type ReactNode } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { businessToday, daysBefore } from '../../../api/retail';
import { useStaff } from '../context';
import { Hint } from '../../../app/ui/Hint';
import { MIN_DATE_REASON, cashGroups, entryDate, enteredBy, recordsWord, rowsIn, savedFor, voidOffered, type CashFigures, type CashGroup, type EntryDate } from './cash-state';
import { newIdempotencyKey } from './idempotency';
import { ListChain, useListView, type TreeKind } from './list-chain';
import { CASH_ORDER, CASH_PLURALS, listUser, pathOf, selectIn, type CashLevel, type CashList, type ListSelection } from './list-chain-state';
import { BACKDATE, CASHBOOK_VOID, branchesWhere, holds, holdsAt } from './permissions';
import { periodTitle } from './sales-grouped-state';
import { Money, Problem, useBranchName, useBranchView } from './ui';
import { PeriodPicker } from '../../../app/ui/PeriodPicker';

// Pieces shared by the cash book screens (ADR-022): a date field that cannot be in the future, the
// date window of a list or report, the success message, the Void action and the records grouped by
// shop, year and month.

/** ADR-120 (issue #811): the date of a new entry at a branch, from the person's permission and what they chose. */
export function useEntryDate(branchId: string | null | undefined, chosen: string | undefined, reason: string | undefined): EntryDate {
  const { me } = useStaff();
  return entryDate(holdsAt(me, BACKDATE, branchId), chosen, reason, businessToday());
}

/**
 * The date of a new entry, never after today, shown only to the owner (ADR-120): the app dates it today for everyone else,
 * so nobody else sees a box. An earlier day asks why; the reason is kept in the audit log.
 */
export function DateField({ id, label, entry, value, reason, onChange, onReason, min }: { id: string; label: string; entry: EntryDate; value: string; reason: string | undefined; onChange: (value: string) => void; onReason: (value: string) => void; min?: string }) {
  if (!entry.mayChoose) return null;
  return (
    <>
      <label htmlFor={id}>{label}</label>
      <input id={id} type="date" value={value} min={min} max={businessToday()} onChange={(e) => onChange(e.target.value)} />
      {entry.earlier && (
        <>
          <label htmlFor={`${id}-reason`}>Why an earlier date</label>
          <input id={`${id}-reason`} maxLength={300} value={reason ?? ''} onChange={(e) => onReason(e.target.value)} aria-invalid={entry.reasonMissing} />
          <Hint>Say why in at least {MIN_DATE_REASON} characters. It is kept in the audit log.</Hint>
        </>
      )}
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

/** The days of a list or report: the one PeriodPicker (issue #526), Today, This week, This month or Custom. */
export function WindowFields({ window }: { id?: string; window: ReturnType<typeof useWindow> }) {
  return (
    <PeriodPicker
      from={window.from}
      to={window.to}
      onChange={(range) => {
        window.setFrom(range.from);
        window.setTo(range.to);
      }}
    />
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
  // toast-exempt: said elsewhere: each screen says it through onVoided (Expenses, Banking, Savings, Withdrawals and Advances call show with success)
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
        <BusyButton busy={void_.isPending} type="submit" className="btn-sm" disabled={reason.trim() === ''}>
          Confirm void
        </BusyButton>
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

/** A group's name: the period in words for a year, month or day, the shop or category name otherwise. */
const cashTitle = (level: CashLevel, g: CashGroup) => (level === 'year' || level === 'month' || level === 'day' ? periodTitle(level, g.key) : g.label);

/** The newest records open on arrival: the first group at every level, as the list showed them before it was grouped. */
function startOpen<T extends RecordLike>(rows: T[], chain: CashLevel[], figures: CashFigures<T>, shopName: (id: string | undefined) => string): Map<string, string> {
  const open = new Map<string, string>();
  let sel: ListSelection = {};
  let parent = '';
  for (const level of chain) {
    const g = cashGroups(rows, level, sel, figures, shopName)[0];
    if (!g) break;
    const path = pathOf(parent, level, g.key);
    open.set(path, cashTitle(level, g));
    sel = selectIn(sel, level, g);
    parent = path;
  }
  return open;
}

/** A group header's figures: the total of the records counted, how many there are, and what was left out. */
function CashFiguresLine({ g, what, totalWord }: { g: CashGroup; what: string; totalWord?: string }) {
  const total = g.count > 0 ? g.totalMinor : undefined;
  return (
    <>
      {total !== undefined && <><span className="rt-group-total"><Money minor={total} /></span>{' '}</>}
      <span className="rt-group-meta">
        {g.count === 0 ? 'Nothing counted' : <>{total !== undefined && totalWord ? `${totalWord} in ` : ''}{recordsWord(g.count, what)}</>}
        {g.owedMinor !== undefined && g.owedMinor > 0 && <>, <Money minor={g.owedMinor} /> still owed</>}
        {g.imported > 0 && <>, {g.imported} imported from the old app</>}
        {g.voided > 0 && <>, {g.voided} voided (not counted)</>}
      </span>
    </>
  );
}

/**
 * Records grouped by shop, year and month (FR-RET-31), and for expenses the category and the day too, with a total at every
 * level (issue #816, as the owner's old app did), in the group tree of the sales, stock and restocks lists. The totals are
 * summed from the very rows the list holds (the cash book lists are not paged); voided records are counted apart. Each row is
 * drawn by `renderRow`; below it come who entered it, an imported or voided label and, for a holder of retail.cashbook.void in
 * that shop, the Void action when `voidWith` is given.
 */
export function RecordsByShop<T extends RecordLike>({ rows, empty, what, renderRow, voidWith, onVoided, actions, list, levels = CASH_ORDER, figures = {}, totalWord }: {
  rows: T[];
  empty: string;
  what: string;
  renderRow: (row: T) => ReactNode;
  voidWith?: (row: T, reason: string, key: string) => Promise<unknown>;
  onVoided?: () => void;
  /** The row's own buttons in place of Void (issue #830: Edit and Delete on an expense), given whether the row may be voided here. */
  actions?: (row: T, mayVoid: boolean) => ReactNode;
  /** Which list this is, so each keeps what was open apart. */
  list: CashList;
  levels?: readonly CashLevel[];
  /** What is summed: the amount the reader is shown on each row; no amount on a row, no total over it. */
  figures?: CashFigures<T>;
  /** What the total is, after the amount: "spent", "banked". */
  totalWord?: string;
}) {
  const shopName = useBranchName();
  if (rows.length === 0) return <p className="empty-state">{empty}</p>;
  // Its own component keyed by the newest records' path, so a list that first gets records, gets a newer month or a new period
  // opens its newest groups again instead of keeping the paths of rows no longer there.
  const newest = [...startOpen(rows, [...levels], figures, shopName).keys()].at(-1);
  return <RecordsTree key={newest} rows={rows} empty={empty} what={what} renderRow={renderRow} voidWith={voidWith} onVoided={onVoided} actions={actions} list={list} levels={levels} figures={figures} totalWord={totalWord} />;
}

function RecordsTree<T extends RecordLike>({ rows, empty, what, renderRow, voidWith, onVoided, actions, list, levels, figures, totalWord }: Parameters<typeof RecordsByShop<T>>[0] & { levels: readonly CashLevel[]; figures: CashFigures<T> }) {
  const { me } = useStaff();
  const shopName = useBranchName();
  const mayVoidIn = useMayVoid();
  const view = useListView<CashLevel>(listUser(me), list, levels, levels, () => startOpen(rows, [...levels], figures, shopName));
  const mayVoid = (row: T) => mayVoidIn(row.branch_id);
  const kind: TreeKind<CashLevel, CashGroup> = {
    name: `cash-${list}`,
    plurals: CASH_PLURALS,
    fetch: () => Promise.resolve({ items: [] }),
    local: (level, sel) => cashGroups(rows, level, sel, figures, shopName),
    title: cashTitle,
    figures: (_level, g) => <CashFiguresLine g={g} what={what} totalWord={totalWord} />,
    nothing: empty,
  };
  return (
    <ListChain<CashLevel, CashGroup>
      kind={kind} filterKey={list} chain={[...levels]} enabled view={view} rootLabel="All records" range={{}}
      renderRows={(sel) => (
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {rowsIn(rows, sel, figures).map((row) => (
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
              {actions?.(row, voidOffered(row, mayVoid(row)))}
            </li>
          ))}
        </ul>
      )}
    />
  );
}
