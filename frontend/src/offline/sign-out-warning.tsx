import { useEffect, useRef } from 'react';
import { waiting } from './outbox';
import { useOffline, useOnline, useOutbox } from './offline-context';

// Sign-out with sales still on the phone (issue #458, ADR-067 decision 9): it asks first. Sync now sends them; Keep them and
// sign out leaves them in this person's outbox on this phone, sent when the same person signs in here again.

/** "1 sale is waiting to sync." or "3 sales are waiting to sync." */
export function waitingSentence(count: number): string {
  return count === 1 ? '1 sale is waiting to sync.' : `${count} sales are waiting to sync.`;
}

export function SignOutWarning({ ask, onSignOut }: { ask: number; onSignOut: () => void }) {
  const offline = useOffline();
  const online = useOnline();
  const outbox = useOutbox();
  const dialog = useRef<HTMLDialogElement>(null);
  const count = waiting(outbox.items).length;

  useEffect(() => {
    const d = dialog.current;
    if (ask === 0 || !d) return;
    if (typeof d.showModal === 'function') d.showModal();
    else d.setAttribute('open', '');
  }, [ask]);

  const close = () => {
    const d = dialog.current;
    if (!d) return;
    if (typeof d.close === 'function') d.close();
    else d.removeAttribute('open');
  };

  if (!offline) return null;
  return (
    <dialog ref={dialog} className="sheet" aria-labelledby="sign-out-title" data-testid="sign-out-warning">
      <div className="stack">
        <h2 id="sign-out-title">Sales are still on this phone</h2>
        <p>{waitingSentence(count)} They have not reached the server yet.</p>
        <p className="muted">If you sign out, they stay on this phone. They are sent when you sign in here again.</p>
        <div className="form-actions dialog-actions">
          <button
            type="button"
            className="btn-primary"
            disabled={!online || outbox.syncing}
            onClick={() => {
              close();
              void offline.engine.syncNow();
            }}
          >
            Sync now
          </button>
          <button
            type="button"
            onClick={() => {
              close();
              onSignOut();
            }}
          >
            Keep them and sign out
          </button>
          <button type="button" className="btn-ghost" onClick={close}>
            Cancel
          </button>
        </div>
      </div>
    </dialog>
  );
}
