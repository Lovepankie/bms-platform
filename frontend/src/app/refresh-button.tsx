import { type Query, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useId, useState, useSyncExternalStore } from 'react';
import { icons } from '../components/icons';

// The refresh button in the header (issue #430). The installed app has no browser reload, so this refetches the queries of the
// page on screen (the active ones) through the query cache: nothing reloads, and the old figures stay up until the new ones arrive.
// It says when the data was last loaded, says so when the device is offline, and announces the result in a polite live region.

const TICK_MS = 30_000;

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;

/** How long ago `at` was, in words: "just now", "2 minutes ago". */
export function agoText(at: number, now: number): string {
  const seconds = Math.max(0, Math.floor((now - at) / 1000));
  if (seconds < 60) return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${plural(minutes, 'minute')} ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${plural(hours, 'hour')} ago`;
  return `${plural(Math.floor(hours / 24), 'day')} ago`;
}

/** A query that polls on its own: its load time says nothing about the figures the person is looking at. */
const polls = (query: Query) => query.observers.some((o) => Boolean(o.options.refetchInterval));

/** When the oldest figures on screen were loaded, ignoring background pollers; 0 while the page has no loaded query. */
export function oldestLoad(queries: Query[]): number {
  const loaded = queries.filter((q) => q.state.dataUpdatedAt > 0);
  const steady = loaded.filter((q) => !polls(q));
  const pool = steady.length > 0 ? steady : loaded;
  return pool.length === 0 ? 0 : Math.min(...pool.map((q) => q.state.dataUpdatedAt));
}

const subscribeOnline = (notify: () => void) => {
  window.addEventListener('online', notify);
  window.addEventListener('offline', notify);
  return () => {
    window.removeEventListener('online', notify);
    window.removeEventListener('offline', notify);
  };
};

/** Whether this device has a network now, kept current by the browser's online and offline events. */
export function useDeviceOnline(): boolean {
  return useSyncExternalStore(subscribeOnline, () => navigator.onLine, () => true);
}

export function RefreshButton() {
  const client = useQueryClient();
  const cache = client.getQueryCache();
  const describedBy = useId();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  // A refresh that failed, in words a sighted user sees at every width; it stays until a refresh works (or the device is offline and says so).
  const [problem, setProblem] = useState('');
  const [now, setNow] = useState(() => Date.now());
  const online = useDeviceOnline();
  // The oldest load among the queries of the page on screen (pollers aside), so the label never looks fresher than the figures; 0 while none.
  const subscribeCache = useCallback((notify: () => void) => cache.subscribe(notify), [cache]);
  const loadedAt = useSyncExternalStore(subscribeCache, () => oldestLoad(cache.findAll({ type: 'active' })), () => 0);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), TICK_MS);
    return () => window.clearInterval(timer);
  }, []);

  const ago = loadedAt > 0 ? agoText(loadedAt, Math.max(now, loadedAt)) : null;
  const older = ago ? `Showing the figures from ${ago}.` : 'Showing the figures already loaded.';
  const visible = !online ? 'Offline' : ago ? `Updated ${ago}` : '';

  async function refresh() {
    if (busy) return;
    if (!online) {
      setMessage(`You are offline, so the page was not refreshed. ${older}`);
      return;
    }
    setBusy(true);
    setMessage('Refreshing');
    try {
      await client.refetchQueries({ type: 'active' });
      setNow(Date.now());
      const active = cache.findAll({ type: 'active' });
      const failed = active.filter((q) => q.state.status === 'error').length;
      if (failed === 0) {
        setProblem('');
        setMessage('Refreshed. Updated just now.');
      } else if (failed < active.length) {
        setProblem('Partly updated');
        setMessage('Refreshed, but some figures on this page could not be updated. Those are still the old ones.');
      } else {
        setProblem('Not refreshed');
        setMessage(`Could not refresh. ${older}`);
      }
    } catch {
      setNow(Date.now());
      setProblem('Not refreshed');
      setMessage(`Could not refresh. ${older}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={`refresh${online ? '' : ' refresh-offline'}`} data-tour="refresh">
      {visible && (
        <span id={describedBy} className="refresh-status">
          {visible}
          {!online && <span className="refresh-more">{`. ${older}`}</span>}
        </span>
      )}
      <button
        type="button"
        className="btn-ghost btn-sm refresh-button"
        aria-label="Refresh"
        aria-describedby={visible ? describedBy : undefined}
        aria-busy={busy}
        disabled={busy}
        onClick={() => void refresh()}
      >
        <span className={busy ? 'refresh-icon refresh-spinning' : 'refresh-icon'}>{icons.refresh}</span>
        <span className="refresh-label">Refresh</span>
      </button>
      {problem && online && (
        <span className="refresh-problem">{problem}</span>
      )}
      <span role="status" className="visually-hidden">
        {message}
      </span>
    </div>
  );
}
