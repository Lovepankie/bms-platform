import { type FormEvent, type ReactNode, useState } from 'react';
import { BusyButton } from '../../app/ui/BusyButton';
import { useTour } from '../../tour/host';
import { reasonProblem } from './operator-state';

// Small parts the operator screens share (issue #407): the page tour button and the confirmation panel every change goes
// through. The panel says in plain words what will happen, asks for the reason that is kept with the change, and sends once:
// the button is disabled while the request is on its way, so a second tap sends nothing.

export function TourButton() {
  const { pageTour, start } = useTour();
  if (!pageTour) return null;
  return (
    <button type="button" className="btn-ghost btn-sm" onClick={() => start(pageTour)}>
      Show me this page
    </button>
  );
}

export interface ConfirmPanelProps {
  id: string;
  /** What will happen, in plain words. */
  sentence: string;
  /** The words on the button, for example "Yes, suspend it". */
  confirm: string;
  /** Ask for a reason (5 to 300 characters), kept with the change in the audit log. */
  needsReason?: boolean;
  danger?: boolean;
  busy: boolean;
  error?: string | null;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
  children?: ReactNode;
}

export function ConfirmPanel({ id, sentence, confirm, needsReason = true, danger, busy, error, onConfirm, onCancel, children }: ConfirmPanelProps) {
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<string | undefined>();

  function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    const found = needsReason ? reasonProblem(reason) : undefined;
    setProblem(found);
    if (found) return;
    onConfirm(reason.trim());
  }

  return (
    <form className="card form-stack" role="group" aria-labelledby={`${id}-sentence`} onSubmit={submit} aria-busy={busy}>
      <p id={`${id}-sentence`}>
        <strong>{sentence}</strong>
      </p>
      {children}
      {needsReason && (
        <div>
          <label htmlFor={`${id}-reason`}>Why (kept with the change)</label>
          <textarea
            id={`${id}-reason`}
            rows={2}
            maxLength={300}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            aria-invalid={Boolean(problem)}
            aria-describedby={problem ? `${id}-reason-problem` : undefined}
          />
          {problem && (
            <span id={`${id}-reason-problem`} className="field-error">
              {problem}
            </span>
          )}
        </div>
      )}
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
      <div className="cluster">
        <BusyButton type="submit" className={danger ? 'btn-danger' : 'btn-primary'} busy={busy}>
          {confirm}
        </BusyButton>
        <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
      </div>
    </form>
  );
}
