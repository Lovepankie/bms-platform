import { useRef, useState } from 'react';
import type { AdminCredential } from '../../../api/sign-in-reset';
import type { StaffUser } from '../../../api/users';
import { BusyButton } from '../../../app/ui/BusyButton';
import { resetProofProblem } from './state';

// Reset someone's sign-in (issue #339; FR-IAM-15, ADR-042). The administrator proves it is still them, as the other sensitive actions
// ask: the 6 digits of their own authenticator app when they have two-step sign-in, otherwise their own password. Nothing typed is
// kept once it has been sent, and the link that comes back is shown once in the link box, never here.

export function ResetPanel({ user, mfaEnabled, onSend, onCancel }: {
  user: StaffUser;
  /** `me.mfa_enabled` of the signed-in administrator: decides whether a code or the password is asked for. */
  mfaEnabled: boolean;
  /** Sends the request; resolves with the words of a refusal, or null when it worked. */
  onSend: (credential: AdminCredential) => Promise<string | null>;
  onCancel: () => void;
}) {
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const sending = useRef(false);
  const problem = typed === '' ? null : resetProofProblem(mfaEnabled, typed);
  const ready = resetProofProblem(mfaEnabled, typed) === null;

  async function send() {
    if (!ready || sending.current) return;
    sending.current = true;
    setBusy(true);
    setError(null);
    const credential: AdminCredential = mfaEnabled ? { totpCode: typed.trim() } : { password: typed };
    // The proof is cleared as soon as it is sent: a retry needs the next code, and a password is never kept on the page.
    setTyped('');
    const failure = await onSend(credential);
    sending.current = false;
    setBusy(false);
    if (failure) setError(failure);
  }

  return (
    <form
      className="alert alert-warning stack"
      aria-label={`Reset the sign-in of ${user.full_name ?? 'this person'}`}
      autoComplete="off"
      onSubmit={(e) => {
        e.preventDefault();
        void send();
      }}
    >
      <p>
        Make a one-time link for <strong>{user.full_name}</strong> to choose a new password. When they use it, they are signed out everywhere. You
        are shown the link once, to send to them yourself.
      </p>
      {mfaEnabled ? (
        <>
          <label htmlFor={`reset-proof-${user.id}`}>Code from your authenticator app</label>
          <input
            id={`reset-proof-${user.id}`}
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            spellCheck={false}
            aria-invalid={problem !== null}
          />
        </>
      ) : (
        <>
          <label htmlFor={`reset-proof-${user.id}`}>Your own password</label>
          <input
            id={`reset-proof-${user.id}`}
            type="password"
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            autoComplete="current-password"
            spellCheck={false}
            aria-invalid={problem !== null}
          />
        </>
      )}
      {problem && <p role="alert" className="field-error">{problem}</p>}
      {error && <p role="alert" className="alert alert-danger">{error}</p>}
      <p className="cluster">
        <BusyButton type="submit" className="rt-primary" busy={busy} disabled={!ready}>Make the link</BusyButton>
        <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={busy} onClick={onCancel}>Cancel</button>
      </p>
    </form>
  );
}
