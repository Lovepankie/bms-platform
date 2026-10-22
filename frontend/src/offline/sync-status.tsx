import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { api, problemOf } from '../api/client';
import { money } from '../areas/staff/retail/ui';
import { discarded, failedCount, waiting, waitingWords, type OutboxItem } from './outbox';
import { useOffline, useOnline, useOutbox } from './offline-context';

// The status in the staff bar (issue #458, ADR-067 decision 10): a dot, green online, grey offline, amber while sending,
// with "3 waiting to sync" beside it. Tapping it opens a sheet of the queued and failed sales with Sync now and, for a
// manager (retail.offline.resolve), the sales other people's phones could not record. Nothing is drawn for a person who
// does not sell.

export type DotState = 'online' | 'offline' | 'syncing';

export function dotState(online: boolean, syncing: boolean): DotState {
  if (!online) return 'offline';
  return syncing ? 'syncing' : 'online';
}

const DOT_WORDS: Record<DotState, string> = { online: 'Online', offline: 'Offline', syncing: 'Syncing' };

/** The accessible name of the button: "Offline, 3 waiting to sync, 1 failed". */
export function statusWords(state: DotState, count: number, failed: number): string {
  return [DOT_WORDS[state], waitingWords(count), failed > 0 ? `${failed} failed` : ''].filter(Boolean).join(', ');
}

function openSheet(d: HTMLDialogElement | null) {
  if (!d) return;
  if (typeof d.showModal === 'function') d.showModal();
  else d.setAttribute('open', '');
}

function closeSheet(d: HTMLDialogElement | null) {
  if (!d) return;
  if (typeof d.close === 'function') d.close();
  else d.removeAttribute('open');
}

function when(iso: string): string {
  const at = new Date(iso);
  return `${at.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })} ${at.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}`;
}

const METHOD_WORDS: Record<string, string> = { cash: 'Cash', mobile_money: 'Mobile money', bank: 'Bank', credit: 'Credit' };

export function SyncStatus() {
  const offline = useOffline();
  const online = useOnline();
  const outbox = useOutbox();
  const sheet = useRef<HTMLDialogElement>(null);
  if (!offline) return null;
  const list = waiting(outbox.items);
  const failed = failedCount(outbox.items);
  const state = dotState(online, outbox.syncing);
  const words = statusWords(state, list.length, failed);
  return (
    <div className="sync-status" data-tour="sync-status">
      <button type="button" className="btn-ghost btn-sm sync-button" aria-haspopup="dialog" aria-label={words} title={words} onClick={() => openSheet(sheet.current)}>
        <span className={`sync-dot sync-dot-${state}`} data-testid="sync-dot" data-state={state} aria-hidden="true" />
        {list.length > 0 && <span className="sync-count">{waitingWords(list.length)}</span>}
      </button>
      <dialog ref={sheet} className="sheet sync-sheet" aria-labelledby="sync-title">
        <SyncSheet online={online} onClose={() => closeSheet(sheet.current)} />
      </dialog>
    </div>
  );
}

function SyncSheet({ online, onClose }: { online: boolean; onClose: () => void }) {
  const offline = useOffline();
  const outbox = useOutbox();
  const list = waiting(outbox.items);
  const gone = discarded(outbox.items);
  if (!offline) return null;
  return (
    <div className="stack">
      <h2 id="sync-title">{online ? 'Online' : 'Offline'}</h2>
      <p className="muted">
        {online
          ? 'Sales made on this phone are sent to the server one at a time, oldest first.'
          : 'Sales you make now are kept on this phone. They are sent when the phone is back online.'}
      </p>
      {outbox.needsSignIn && (
        <p role="alert" className="alert alert-warning">
          Your sign-in has expired. Sign in again to send the waiting sales.
        </p>
      )}
      {list.length === 0 ? (
        <p>Nothing is waiting to sync.</p>
      ) : (
        <ul className="sync-list" aria-label="Waiting to sync">
          {list.map((item) => (
            <SyncRow key={item.id} item={item} onAgain={() => void offline.engine.sendAgain(item.id)} />
          ))}
        </ul>
      )}
      {gone.length > 0 && (
        <ul className="sync-list" aria-label="Discarded by a manager">
          {gone.map((item) => (
            <li key={item.id} className="sync-row sync-row-failed">
              <div>
                <strong>{item.provisionalNo}</strong> {when(item.occurredAt)}, {item.branchName}, {METHOD_WORDS[item.method] ?? item.method} {money(item.totalMinor)}
              </div>
              <p className="sync-reason">Discarded by a manager. It is not recorded and is not sent again.</p>
            </li>
          ))}
        </ul>
      )}
      <div className="form-actions">
        <button type="button" className="btn-primary" disabled={!online || outbox.syncing || list.length === 0} onClick={() => void offline.engine.syncNow()}>
          {outbox.syncing ? 'Syncing' : 'Sync now'}
        </button>
        <button type="button" className="btn-ghost" onClick={onClose}>
          Close
        </button>
      </div>
      {online && <ManagerQueue />}
    </div>
  );
}

function SyncRow({ item, onAgain }: { item: OutboxItem; onAgain: () => void }) {
  const failed = item.state === 'failed';
  return (
    <li className={failed ? 'sync-row sync-row-failed' : 'sync-row'}>
      <div>
        <strong>{item.provisionalNo}</strong> {when(item.occurredAt)}, {item.branchName}, {METHOD_WORDS[item.method] ?? item.method} {money(item.totalMinor)}
      </div>
      {failed ? (
        <>
          <p className="sync-reason">Not recorded: {item.error?.message}</p>
          <p className="muted">
            {item.decision === 'retry' ? 'A manager asked for it to be sent again.' : 'A manager can have it sent again or discard it.'}
          </p>
          <button type="button" className="btn-sm" onClick={onAgain}>
            Send again
          </button>
        </>
      ) : (
        <p className="muted">{item.state === 'sending' ? 'Sending' : item.attempts > 0 ? `Waiting to try again: ${item.error?.message ?? ''}` : 'Waiting its turn'}</p>
      )}
    </li>
  );
}

/** For a manager: the sales other phones could not record, to send again or discard (audited), and the open clashes. */
function ManagerQueue() {
  const offline = useOffline();
  const queryClient = useQueryClient();
  const holds = offline?.manager === true;
  const failures = useQuery({
    queryKey: ['retail', 'offline-failures'],
    queryFn: async () => {
      const { data, error, response } = await api.GET('/api/v1/retail/offline-failures', { params: { query: {} } });
      if (response.status === 403) return null;
      if (error || !data) throw new Error(problemOf(error).detail ?? 'Could not load the refused sales');
      return data.items ?? [];
    },
    enabled: holds,
    retry: false,
  });
  const clashes = useQuery({
    queryKey: ['retail', 'sale-clashes'],
    queryFn: async () => {
      const { data, error, response } = await api.GET('/api/v1/retail/sale-clashes', { params: { query: {} } });
      if (response.status === 403) return null;
      if (error || !data) throw new Error(problemOf(error).detail ?? 'Could not load the clashes');
      return data.items ?? [];
    },
    enabled: holds,
    retry: false,
  });
  const [discarding, setDiscarding] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [note, setNote] = useState<Record<string, string>>({});
  const decide = useMutation({
    mutationFn: async ({ id, action }: { id: string; action: 'retry' | 'discard' }) => {
      const path = action === 'retry' ? '/api/v1/retail/offline-failures/{failure_id}/retry' : '/api/v1/retail/offline-failures/{failure_id}/discard';
      const { error } = await api.POST(path, { params: { path: { failure_id: id } }, body: action === 'discard' ? { reason: reason.trim() } : undefined } as never);
      if (error) throw new Error(problemOf(error).detail ?? 'Could not save the decision');
    },
    onSuccess: () => {
      setDiscarding(null);
      setReason('');
      void queryClient.invalidateQueries({ queryKey: ['retail', 'offline-failures'] });
    },
  });
  const resolve = useMutation({
    mutationFn: async (id: string) => {
      const { error } = await api.POST('/api/v1/retail/sale-clashes/{clash_id}/resolve', { params: { path: { clash_id: id } }, body: { note: (note[id] ?? '').trim() } });
      if (error) throw new Error(problemOf(error).detail ?? 'Could not resolve the clash');
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['retail', 'sale-clashes'] }),
  });
  const rows = failures.data ?? [];
  const open = clashes.data ?? [];
  if (!holds || (rows.length === 0 && open.length === 0)) return null;
  return (
    <section aria-labelledby="sync-manager-title" className="stack">
      <h3 id="sync-manager-title">For a manager</h3>
      {rows.length > 0 && (
        <ul className="sync-list" aria-label="Sales other phones could not record">
          {rows.map((f) => (
            <li key={f.id} className="sync-row sync-row-failed">
              <div>
                {f.made_by_name ?? 'A seller'}, {f.occurred_at ? when(f.occurred_at) : ''}, {METHOD_WORDS[f.payment_method ?? ''] ?? f.payment_method} {money(f.total_minor ?? 0)}
              </div>
              <p className="sync-reason">Not recorded: {f.message}</p>
              {f.made_by === offline?.userId ? (
                <p className="muted">Your own sale: another manager decides it. You can send it again from your phone.</p>
              ) : f.status === 'retry' ? (
                <p className="muted">Waiting for the seller's phone to send it again.</p>
              ) : discarding === f.id ? (
                <div className="stack">
                  <label htmlFor={`discard-${f.id}`}>Why discard this sale? (at least 5 characters)</label>
                  <input id={`discard-${f.id}`} value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} />
                  <p className="muted">The sale is never recorded. Your name and the reason are kept.</p>
                  <div className="cluster">
                    <button type="button" className="btn-danger btn-sm" disabled={reason.trim().length < 5 || decide.isPending} onClick={() => decide.mutate({ id: f.id ?? '', action: 'discard' })}>
                      Discard for good
                    </button>
                    <button type="button" className="btn-ghost btn-sm" onClick={() => setDiscarding(null)}>
                      Keep it
                    </button>
                  </div>
                </div>
              ) : (
                <div className="cluster">
                  <button type="button" className="btn-sm" disabled={decide.isPending} onClick={() => decide.mutate({ id: f.id ?? '', action: 'retry' })}>
                    Send it again
                  </button>
                  <button type="button" className="btn-ghost btn-sm" onClick={() => setDiscarding(f.id ?? null)}>
                    Discard
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {open.length > 0 && (
        <ul className="sync-list" aria-label="Clashes of offline sales">
          {open.map((c) => (
            <li key={c.id} className="sync-row">
              <div>
                <strong>{c.sale_no}</strong> {c.words}
              </div>
              <label htmlFor={`clash-${c.id}`}>What did you do about it?</label>
              <input id={`clash-${c.id}`} value={note[c.id ?? ''] ?? ''} maxLength={300} onChange={(e) => setNote((n) => ({ ...n, [c.id ?? '']: e.target.value }))} />
              <button type="button" className="btn-sm" disabled={(note[c.id ?? ''] ?? '').trim().length < 5 || resolve.isPending} onClick={() => resolve.mutate(c.id ?? '')}>
                Mark resolved
              </button>
            </li>
          ))}
        </ul>
      )}
      {(decide.error || resolve.error) && <p role="alert" className="alert alert-danger">{(decide.error ?? resolve.error)?.message}</p>}
    </section>
  );
}
