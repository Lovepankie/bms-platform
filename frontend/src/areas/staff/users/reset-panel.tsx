import { useRef, useState } from 'react';
import type { AdminCredential } from '../../../api/sign-in-reset';
import type { StaffUser } from '../../../api/users';
import { BusyButton } from '../../../app/ui/BusyButton';
import { resetProofProblem } from './state';

// Reset someone's sign-in (issue #339; FR-IAM-15, ADR-042). The administrator proves it is still them, as the other sensitive actions
// ask: the 6 digits of their own authenticator app when they have two-step sign-in, otherwise their own password. Nothing typed is
// kept once it has been sent, and the link that comes back is shown once in the link box, never here.

export function ResetPanel({ user, mfaEnabled, openLinkFor = null, onSend, onCancel }: {
  user: StaffUser;
  /** `me.mfa_enabled` of the signed-in administrator: decides whether a code or the password is asked for. */
  mfaEnabled: boolean;
  /** The name on the link box while one is open: the new link takes its place, so the administrator is told first. */
  openLinkFor?: string | null;
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
    // toast-exempt: success screen, the link box with the new link replaces this panel
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
      {openLinkFor !== null && (
        <p role="note" className="alert alert-info">
          The link for {openLinkFor} shown above closes when this one is made. Copy or send it first if you have not.
        </p>
      )}
      {mfaEnabled ? (
        <>
          <label htmlFor={`reset-proof-${user.id}`}>Code from your authenticator app</label>
          <input
            id={`reset-proof-${user.id}`}
            value={typed}
            // Only the digits are kept, so a pasted "123 456" is not cut short by a length limit before its space is gone.
            onChange={(e) => setTyped(e.target.value.replace(/\D/g, '').slice(0, 6))}
            inputMode="numeric"
            autoComplete="one-time-code"
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
        <BusyButton type="submit" className="rt-primary" busy={busy} notReady={!ready}>Make the link</BusyButton>
        <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={busy} onClick={onCancel}>Cancel</button>
      </p>
    </form>
  );
}
