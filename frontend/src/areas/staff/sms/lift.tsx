import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';

// Lifting an SMS opt-out (extras slice B3, issue #218; spec 4.14): the opt-out is the member's protection against being
// pestered, so lifting it is the tenant administrator's, needs a reason that goes into the audit row, and is never one click:
// this panel asks "are you sure" and "why" before anything is sent. The server enforces all three.

export const MIN_REASON = 3;
export const MAX_REASON = 200;

/** What is wrong with a reason as typed, or null. */
export function reasonProblem(text: string): string | null {
  const n = text.trim().length;
  if (n < MIN_REASON) return `Say why, in at least ${MIN_REASON} characters.`;
  if (n > MAX_REASON) return `At most ${MAX_REASON} characters.`;
  return null;
}

export function LiftConfirm({ what, narrow = false, busy, error, onConfirm, onCancel }: {
  /** Who it is about, in words that hold no number: "this member" or "+256*****0601". */
  what: string;
  /** Narrowing every message down to reminders only is a partial lift: same reason, same confirmation. */
  narrow?: boolean;
  busy: boolean;
  error: string | null;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
}) {
  const [reason, setReason] = useState('');
  const problem = reasonProblem(reason);
  return (
    <form
      aria-label={narrow ? 'Narrow the opt-out' : 'Lift the opt-out'}
      className="rt-card form-stack"
      onSubmit={(e) => {
        e.preventDefault();
        if (problem === null && !busy) onConfirm(reason.trim());
      }}
    >
      <h3>{narrow ? `Stop only reminders for ${what}?` : `Lift the opt-out for ${what}?`}</h3>
      <p role="note" className="alert alert-warning">
        {narrow
          ? `Receipts and other messages will reach ${what} again; only reminders stay stopped. `
          : `Text messages will reach ${what} again. `}
        Do this only when the person has asked for it. It is recorded with your name and your reason.
      </p>
      <label htmlFor="sms-lift-reason">{narrow ? 'Why is it narrowed?' : 'Why is it lifted?'}</label>
      <textarea id="sms-lift-reason" rows={2} maxLength={MAX_REASON} value={reason} onChange={(e) => setReason(e.target.value)} aria-describedby="sms-lift-hint" />
      <p id="sms-lift-hint" className="field-hint">Words about why, not names or phone numbers. {reason.trim().length} of {MAX_REASON} characters.</p>
      {error && <p role="alert" className="field-error">{error}</p>}
      <p className="cluster">
        <BusyButton type="submit" className="rt-primary" busy={busy} notReady={problem !== null}>
          {narrow ? 'Narrow the opt-out' : 'Lift the opt-out'}
        </BusyButton>
        <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={busy} onClick={onCancel}>
          Keep the opt-out
        </button>
      </p>
    </form>
  );
}
