import { useRouter } from '@tanstack/react-router';
import { useState } from 'react';
import { draftInProgress, restoreDraft } from '../areas/staff/retail/idempotency';
import { useOffline, type Offline } from './offline-context';
import { GAVE_UP_CODE, IN_PROGRESS_CODES, heldWords, type OutboxItem } from './outbox';

// The state of an entry kept on the phone that is not a sale (issue #812, ADR-067 decision 13), as the sync sheet and the
// form's Saved on this phone panel show it.

/**
 * Waiting, sending, sent (or sent and waiting for an administrator, ADR-091), not recorded, or held for its maker (issue
 * #852, `name` theirs when known), in plain words.
 */
export function entryStateWords(item: OutboxItem, name?: string): string {
  if (item.state === 'held') return heldWords(name);
  if (item.state === 'sent') return item.recorded?.waiting ? 'Sent. It waits for an administrator to approve it.' : 'Sent. It is recorded.';
  if (item.state === 'failed') return `Not recorded: ${item.error?.message ?? ''}`;
  if (item.state === 'sending') return 'Sending';
  return item.attempts > 0 ? `Waiting to try again: ${item.error?.message ?? ''}` : 'Waiting to be sent';
}

/** The name of the person who made an item, when this phone knows it: the signed-in person's own items only. */
export function makerName(offline: Offline | null, item: OutboxItem): string | undefined {
  return offline && item.scope === offline.scope ? offline.name : undefined;
}

/**
 * The state, and for a refused entry what its maker can do: Fix (its form opens on it, with a new key), Send again, or
 * Discard (asked first). No manager decides an entry: the server never recorded it.
 */
export function EntryState({ item }: { item: OutboxItem }) {
  const offline = useOffline();
  const router = useRouter({ warn: false });
  const [asking, setAsking] = useState(false);
  const [formInUse, setFormInUse] = useState(false);
  // A held entry has nothing to Fix, Send again or Discard (issue #852): it is kept as it is and goes with its maker.
  if (item.state !== 'failed') return <p className="muted">{entryStateWords(item, makerName(offline, item))}</p>;
  // The server may have recorded it (it gave up answering, or was still recording): a new key, or discarding it and typing
  // it again, could save it twice, so only Send again, under its own key, is offered (the lead's decision 2 on the third
  // review of #839; Fix and Discard come back once the server has refused that key). An entry sent online first whose
  // answer was lost may still be recorded by that first request after a refusal of the outbox's try, so it is Fixed only
  // once the server has refused its key again, after Send again (the lead's decision on the fourth review).
  const maybeRecorded =
    item.error?.code === GAVE_UP_CODE ||
    IN_PROGRESS_CODES.includes(item.error?.code ?? '') ||
    (item.entry?.lost === true && (item.refusals ?? 0) < 2);
  const fix = maybeRecorded ? undefined : item.entry?.fix;
  const fixIt = async () => {
    if (!fix) return;
    // The form already holds other work (review of #839): nothing is written over it and the entry stays here.
    if (draftInProgress(fix.draftName)) {
      setFormInUse(true);
      return;
    }
    const back = await offline?.engine.takeBack(item.id);
    if (!back) return;
    restoreDraft(fix.draftName, fix.draft);
    void router?.navigate({ to: fix.to, ...(fix.search ? { search: fix.search } : {}) });
  };
  return (
    <>
      {maybeRecorded ? (
        <>
          <p className="sync-reason">This may already be recorded. Send again to check; it is never recorded twice.</p>
          <p className="muted">{item.error?.message}</p>
        </>
      ) : (
        <p className="sync-reason">{entryStateWords(item)}</p>
      )}
      {formInUse && <p role="alert">That form has other work in it. Save or clear it first, then tap Fix again.</p>}
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
          {!maybeRecorded && (
            <button type="button" className="btn-ghost btn-sm" onClick={() => setAsking(true)}>
              Discard
            </button>
          )}
        </div>
      )}
    </>
  );
}
