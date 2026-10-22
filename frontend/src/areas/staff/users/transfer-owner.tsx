import { useRef, useState } from 'react';
import type { StaffUser } from '../../../api/users';
import { BusyButton } from '../../../app/ui/BusyButton';
import { resetProofProblem } from './state';

// Hand ownership to another administrator (ADR-062, FR-IAM-18). Only the Owner sees it, and only the active administrators are
// offered. It says plainly what changes before anything is sent, and asks the Owner to prove it is still them, as the reset of a
// sign-in does: the 6 digits of their authenticator app with two-step sign-in, otherwise their own password. Nothing typed is kept.

export function TransferOwner({ targets, mfaEnabled, onSend, onCancel }: {
  targets: StaffUser[];
  /** `me.mfa_enabled` of the Owner: decides whether a code or the password is asked for. */
  mfaEnabled: boolean;
  /** Sends the request; resolves with the words of a refusal, or null when it worked. */
  onSend: (to: StaffUser, credential: { totpCode?: string; password?: string }) => Promise<string | null>;
  onCancel: () => void;
}) {
  const [toId, setToId] = useState('');
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const sending = useRef(false);
  const to = targets.find((t) => t.id === toId) ?? null;
  const problem = typed === '' ? null : resetProofProblem(mfaEnabled, typed);
  const ready = to !== null && resetProofProblem(mfaEnabled, typed) === null;

  async function send() {
    if (!ready || !to || sending.current) return;
    sending.current = true;
    setBusy(true);
    setError(null);
    const credential = mfaEnabled ? { totpCode: typed.trim() } : { password: typed };
    setTyped('');
    const failure = await onSend(to, credential);
    sending.current = false;
    setBusy(false);
    if (failure) setError(failure);
  }

  if (targets.length === 0) {
    return (
      <div className="alert alert-warning stack" role="group" aria-label="Transfer ownership">
        <p>Ownership can only go to another active administrator. Make the person an administrator first, then come back here.</p>
        <p><button type="button" className="btn-ghost" onClick={onCancel}>Close</button></p>
      </div>
    );
  }

  return (
    <form
      className="alert alert-warning stack"
      aria-label="Transfer ownership"
      autoComplete="off"
      onSubmit={(e) => {
        e.preventDefault();
        void send();
      }}
    >
      <label htmlFor="transfer-to">Who becomes the owner</label>
      <select id="transfer-to" value={toId} disabled={busy} onChange={(e) => setToId(e.target.value)}>
        <option value="">Choose an administrator</option>
        {targets.map((t) => (
          <option key={t.id} value={t.id}>{t.full_name}</option>
        ))}
      </select>
      {to && (
        <p>
          <strong>{to.full_name}</strong> becomes the owner. From then on only they can add, remove or change an administrator, and nobody can
          remove them. You stay an administrator and keep everything else you can do now. You cannot take it back yourself: they would have to
          hand ownership back to you.
        </p>
      )}
      {mfaEnabled ? (
        <>
          <label htmlFor="transfer-proof">Code from your authenticator app</label>
          <input id="transfer-proof" value={typed} onChange={(e) => setTyped(e.target.value)} inputMode="numeric" autoComplete="one-time-code" maxLength={6} spellCheck={false} aria-invalid={problem !== null} />
        </>
      ) : (
        <>
          <label htmlFor="transfer-proof">Your own password</label>
          <input id="transfer-proof" type="password" value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="current-password" spellCheck={false} aria-invalid={problem !== null} />
        </>
      )}
      {problem && <p role="alert" className="field-error">{problem}</p>}
      {error && <p role="alert" className="alert alert-danger">{error}</p>}
      <p className="cluster">
        <BusyButton type="submit" className="btn-danger" busy={busy} disabled={!ready}>
          {to ? `Yes, make ${to.full_name} the owner` : 'Hand over ownership'}
        </BusyButton>
        <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={busy} onClick={onCancel}>Cancel</button>
      </p>
    </form>
  );
}
