import { useRouter } from '@tanstack/react-router';
import { useState } from 'react';
import { restoreDraft } from '../areas/staff/retail/idempotency';
import { useOffline } from './offline-context';
import type { OutboxItem } from './outbox';

// The state of an entry kept on the phone that is not a sale (issue #812, ADR-067 decision 13), as the sync sheet and the
// form's Saved on this phone panel show it.

/** Waiting, sending, sent (or sent and waiting for an administrator, ADR-091), or not recorded, in plain words. */
export function entryStateWords(item: OutboxItem): string {
  if (item.state === 'sent') return item.recorded?.waiting ? 'Sent. It waits for an administrator to approve it.' : 'Sent. It is recorded.';
  if (item.state === 'failed') return `Not recorded: ${item.error?.message ?? ''}`;
  if (item.state === 'sending') return 'Sending';
  return item.attempts > 0 ? `Waiting to try again: ${item.error?.message ?? ''}` : 'Waiting to be sent';
}

/**
 * The state, and for a refused entry what its maker can do: Fix (its form opens on it, with a new key), Send again, or
 * Discard (asked first). No manager decides an entry: the server never recorded it.
 */
export function EntryState({ item }: { item: OutboxItem }) {
  const offline = useOffline();
  const router = useRouter({ warn: false });
  const [asking, setAsking] = useState(false);
  if (item.state !== 'failed') return <p className="muted">{entryStateWords(item)}</p>;
  const fix = item.entry?.fix;
  const fixIt = async () => {
    const back = await offline?.engine.takeBack(item.id);
    if (!back || !fix) return;
    restoreDraft(fix.draftName, fix.draft);
    void router?.navigate({ to: fix.to });
  };
  return (
    <>
      <p className="sync-reason">{entryStateWords(item)}</p>
      {asking ? (
        <div className="stack">
          <p>Discard this entry? It is not recorded and is not sent again.</p>
          <div className="cluster">
            <button type="button" className="btn-danger btn-sm" onClick={() => void offline?.engine.takeBack(item.id)}>
              Discard
            </button>
            <button type="button" className="btn-ghost btn-sm" onClick={() => setAsking(false)}>
              Keep it
            </button>
          </div>
        </div>
      ) : (
        <div className="cluster">
          {fix && (
            <button type="button" className="btn-sm" onClick={() => void fixIt()}>
              Fix
            </button>
          )}
          <button type="button" className="btn-sm" onClick={() => void offline?.engine.sendAgain(item.id)}>
            Send again
          </button>
          <button type="button" className="btn-ghost btn-sm" onClick={() => setAsking(true)}>
            Discard
          </button>
        </div>
      )}
    </>
  );
}
