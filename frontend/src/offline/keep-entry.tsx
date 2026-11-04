import { useCallback, useState } from 'react';
import { droppedConnection } from '../api/client';
import { money, useBranchName } from '../areas/staff/retail/ui';
import { ENTRY_ANSWER_MS, NoAnswer, answerWithin, lastOnline } from './connectivity';
import { isKept, offlineEntry, type EntrySpec, type Kept } from './entries';
import { EntryState } from './entry-state';
import { useOffline, useOnline, useOutbox } from './offline-context';
import type { OutboxItem } from './outbox';

// The daily forms with no network (issue #812, ADR-067 decision 13): a form saves through `useEntrySave`, which sends it as
// always while online and keeps it on the phone when the phone is offline or the network drops with no answer. The form
// then shows `KeptOnPhone` in its place: what was kept and its state, plainly, until it is sent.

export interface EntrySave {
  /** Offline support is mounted and the phone is offline now: a save is kept on the phone. */
  offlineMode: boolean;
  /**
   * Sends `online()` while online. Offline, or when the request got no answer at all (the network dropped), the entry is
   * kept on the phone instead and `{ kept }` is returned. Any answer the server gave stays the form's to show.
   */
  save<T>(spec: Omit<EntrySpec, 'branchName'>, online: () => Promise<T>): Promise<T | Kept>;
  /** The entry this form last kept on the phone, shown in the form's place until `another` is pressed. */
  kept: OutboxItem | null;
  another(): void;
}

export function useEntrySave(): EntrySave {
  const offline = useOffline();
  const online = useOnline();
  const branchName = useBranchName();
  const [kept, setKept] = useState<OutboxItem | null>(null);
  const save = useCallback(
    async <T,>(spec: Omit<EntrySpec, 'branchName'>, send: () => Promise<T>): Promise<T | Kept> => {
      // When the save began: an entry kept on the phone is dated by it, even one whose online answer was lost (#839 review).
      const madeAt = new Date();
      const keep = async (lost: boolean): Promise<Kept> => {
        if (!offline) throw new Error('This phone cannot keep entries.');
        const item = offlineEntry({
          ...spec, branchName: branchName(spec.branchId), scope: offline.scope, now: madeAt, onlineAsOf: lastOnline(offline.catalogue?.pricesAsOf), lost,
        });
        await offline.engine.queue(item);
        if (offline.connectivity.online()) void offline.engine.sync();
        setKept(item);
        return { kept: item };
      };
      if (offline && !offline.connectivity.online()) return keep(false);
      try {
        // A save never waits longer than ENTRY_ANSWER_MS (the 8 seconds a sale waits, issue #812): with no answer by then the
        // entry is kept on the phone under the same key, with when it was made, and the late answer and the outbox's send are
        // one record (the key covers the request without its offline block).
        return await (offline ? answerWithin(send(), ENTRY_ANSWER_MS) : send());
      } catch (e) {
        if (offline && (droppedConnection(e) || e instanceof NoAnswer)) return keep(true);
        throw e;
      }
    },
    [offline, branchName],
  );
  const another = useCallback(() => setKept(null), []);
  return { offlineMode: offline !== null && !online, save, kept, another };
}

/** The note at the top of a form while offline. */
export function OfflineFormNote({ what }: { what: string }) {
  return (
    <p role="status" className="alert alert-warning" data-testid="offline-form-note">
      <strong>You are offline.</strong> This {what} is kept on this phone and sent when the phone is back online.
    </p>
  );
}

/** The note on a form that changes a saved entry (PR #832), which needs the internet. */
export function EditNeedsInternet() {
  return (
    <p role="alert" className="alert alert-warning" data-testid="edit-needs-internet">
      You are offline. Changing a saved entry needs the internet. Your change is not kept on this phone.
    </p>
  );
}

/** In place of a form whose entry was kept on the phone: what was kept, its state as it changes, and a new entry. */
export function KeptOnPhone({ item, onNew, newLabel = 'Record another' }: { item: OutboxItem; onNew: () => void; newLabel?: string }) {
  const outbox = useOutbox();
  const now = outbox.items.find((i) => i.id === item.id) ?? item;
  return (
    <section aria-label="Saved on this phone" data-testid="kept-on-phone">
      <h2>{now.state === 'sent' ? 'Sent' : 'Saved on this phone'}</h2>
      <p>
        {now.entry?.words}
        {now.totalMinor > 0 ? `, ${money(now.totalMinor)}` : ''}
      </p>
      <EntryState item={now} />
      <button type="button" className="rt-primary" onClick={onNew}>
        {newLabel}
      </button>
    </section>
  );
}

export { isKept };
