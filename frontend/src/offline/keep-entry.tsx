import { useCallback } from 'react';
import { droppedConnection } from '../api/client';
import { money } from '../areas/staff/retail/ui';
import { isKept, offlineEntry, type EntrySpec, type Kept } from './entries';
import { useOffline, useOnline, useOutbox } from './offline-context';
import type { OutboxItem } from './outbox';
import { EntryState } from './entry-state';

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
  save<T>(spec: EntrySpec, online: () => Promise<T>): Promise<T | Kept>;
}

export function useEntrySave(): EntrySave {
  const offline = useOffline();
  const online = useOnline();
  const offlineMode = offline !== null && !online;
  const save = useCallback(
    async <T,>(spec: EntrySpec, send: () => Promise<T>): Promise<T | Kept> => {
      const keep = async (lost: boolean): Promise<Kept> => {
        if (!offline) throw new Error('This phone cannot keep entries.');
        const item = offlineEntry({ ...spec, scope: offline.scope, now: new Date(), onlineAsOf: offline.catalogue?.pricesAsOf, lost });
        await offline.engine.queue(item);
        if (offline.connectivity.online()) void offline.engine.sync();
        return { kept: item };
      };
      if (offline && !offline.connectivity.online()) return keep(false);
      try {
        return await send();
      } catch (e) {
        if (offline && droppedConnection(e)) return keep(true);
        throw e;
      }
    },
    [offline],
  );
  return { offlineMode, save };
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
  const sent = now.state === 'sent';
  return (
    <section aria-label="Saved on this phone" data-testid="kept-on-phone">
      <h2>{sent ? 'Sent' : 'Saved on this phone'}</h2>
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
