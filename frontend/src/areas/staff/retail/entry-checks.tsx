import { useQueries } from '@tanstack/react-query';
import { useEffect, useRef, useState, type ReactElement, type Ref } from 'react';
import { retail, type EntryNorms } from '../../../api/retail';
import { icons } from '../../../components/icons';
import type { EntryScreen, EntryWarning } from './entry-checks-state';

// The entry warnings on Buy stock, Sell, Move stock and Count stock (issue #353, ADR-093). Each item's history is read once
// (GET /retail/products/{id}/entry-norms, kept for five minutes); a failed or slow read means no warning, never a wait. Saving
// asks `check` first: with no open warning it saves at once; otherwise the warnings show next to their fields, each with
// Confirm and Change (the warning goes, the cursor goes to the field, nothing is saved). Confirm checks the screen as it is now:
// a warning whose line was edited is gone, a new one is shown, and only when none is open does the save go on, with the screen's
// current values. A confirmation is recorded only once that save has succeeded (`saved`). The save button stays enabled
// throughout: nothing here blocks.

/** The history of each item on the screen, by id; undefined while it loads, when it failed, or for no id. */
export function useEntryNorms(productIds: readonly (string | undefined)[]): (id: string | undefined) => EntryNorms | undefined {
  const ids = [...new Set(productIds.filter((id): id is string => !!id))];
  const results = useQueries({
    queries: ids.map((id) => ({
      queryKey: ['retail', 'entry-norms', id],
      queryFn: () => retail.entryNorms(id),
      staleTime: 5 * 60_000,
      retry: false,
    })),
  });
  const byId = new Map(ids.map((id, i) => [id, results[i]?.data]));
  return (id) => (id ? byId.get(id) : undefined);
}

export interface EntryChecks {
  /** Saves at once when every warning is confirmed; otherwise shows the open ones, and the last Confirm saves. */
  check: () => void;
  /**
   * Called by the screen once its save has succeeded (Count stock: once the count is committed): records the confirmations of
   * the warnings it was saved with, and forgets them, so the next entry is asked about afresh.
   */
  saved: () => void;
  /** The warnings showing for one line, as notes with Confirm and Change. */
  notes: (lineKey: string) => ReactElement | null;
  /** True while a warning waits for an answer. */
  waiting: boolean;
}

/**
 * `warnings` are the screen's warnings as it renders now and `save` its save with the values it shows now; both are read at the
 * moment Save or Confirm is pressed, never kept from an earlier render. `form` is what the lines hold: any change to it clears the
 * warnings showing and the save waiting on them.
 */
export function useEntryChecks(screen: EntryScreen, branchId: string | undefined, warnings: EntryWarning[], save: () => void, form: unknown): EntryChecks {
  const [shownKeys, setShownKeys] = useState<ReadonlySet<string>>(new Set());
  const confirmed = useRef(new Set<string>());
  const pending = useRef(false);
  const latest = useRef({ warnings, save });
  latest.current = { warnings, save };
  // The confirmations of the warnings the last save was sent with, recorded once it succeeds.
  const sentWith = useRef<EntryWarning[]>([]);
  const first = useRef<HTMLDivElement | null>(null);
  // Any change to the lines (`form`), a reset included, takes the warnings, the waiting save and what was confirmed away: Save
  // asks again about the form as it is.
  const formKey = JSON.stringify(form);
  const formWas = useRef(formKey);
  if (formWas.current !== formKey) {
    formWas.current = formKey;
    pending.current = false;
    confirmed.current = new Set();
    if (shownKeys.size > 0) setShownKeys(new Set());
  }
  // Only warnings the screen still raises show: editing a line takes its stale warning away.
  const visible = warnings.filter((w) => shownKeys.has(w.key));
  const firstKey = visible[0]?.key;
  // The first warning comes into view when it shows, so a warning on a line far up the form is seen.
  useEffect(() => {
    if (firstKey) first.current?.scrollIntoView?.({ block: 'center' });
  }, [firstKey]);

  const goOn = () => {
    const now = latest.current;
    const open = now.warnings.filter((w) => !confirmed.current.has(w.key));
    if (open.length > 0) {
      setShownKeys(new Set(open.map((w) => w.key)));
      return;
    }
    pending.current = false;
    setShownKeys(new Set());
    sentWith.current = now.warnings;
    now.save();
  };
  const check = () => {
    pending.current = true;
    goOn();
  };
  const confirm = (w: EntryWarning) => {
    confirmed.current.add(w.key);
    if (pending.current) goOn();
    else setShownKeys((s) => new Set([...s].filter((k) => k !== w.key)));
  };
  const change = (w: EntryWarning) => {
    pending.current = false;
    setShownKeys((s) => new Set([...s].filter((k) => k !== w.key)));
    document.getElementById(w.fieldId)?.focus();
  };
  const saved = () => {
    const sent = sentWith.current;
    sentWith.current = [];
    // A confirmation answers one save: the next entry, even of the same item and quantity, is asked about again.
    confirmed.current = new Set();
    // Recorded for the owner's review; a failed record changes nothing.
    for (const w of sent) {
      void retail.confirmEntryWarning({ ...w.confirmation, screen, ...(branchId ? { branch_id: branchId } : {}) }).catch(() => undefined);
    }
  };
  const notes = (lineKey: string) => {
    const mine = visible.filter((w) => w.lineKey === lineKey);
    if (mine.length === 0) return null;
    return (
      <>
        {mine.map((w) => (
          <EntryWarningNote key={w.key} warning={w} onConfirm={() => confirm(w)} onChange={() => change(w)}
            noteRef={w.key === firstKey ? first : undefined} />
        ))}
      </>
    );
  };
  return { check, saved, notes, waiting: visible.length > 0 };
}

/** One warning next to its field: a tinted note in plain words, Confirm and Change. */
export function EntryWarningNote({ warning, onConfirm, onChange, noteRef }: {
  warning: EntryWarning;
  onConfirm: () => void;
  onChange: () => void;
  noteRef?: Ref<HTMLDivElement>;
}) {
  return (
    <div ref={noteRef} role="alert" className="alert alert-warning entry-warning" data-testid={`entry-warning-${warning.kind}`}>
      <span className="entry-warning-icon" aria-hidden="true">{icons.error}</span>
      <div>
        <p>{warning.text}</p>
        <div className="entry-warning-actions">
          <button type="button" onClick={onConfirm}>Confirm</button>
          <button type="button" onClick={onChange}>Change</button>
        </div>
      </div>
    </div>
  );
}
