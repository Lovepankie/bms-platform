import { useCallback, useEffect, useReducer, useRef, type ReactNode } from 'react';
import type { UploadProgress } from '../../../api/lending-forms';
import { ACCEPT, browserCodec, PrepareError, prepareFile, type Codec, type Prepared } from './image-prep';

// Photos and scans for one purpose (issue #280): an ID front, a collateral item. "Take a photo" opens the
// phone's camera, "Choose a file" the gallery or files. Each picture is checked and shrunk in the browser
// (image-prep.ts) and then either sent at once, with a progress bar (a record that exists), or held until
// the screen sends it (a record being created). A failed send keeps the prepared picture, so "Send again"
// does not ask for it again and never resizes twice.

export type ItemState =
  | { phase: 'preparing' }
  | { phase: 'ready'; prepared: Prepared }
  | { phase: 'sending'; prepared: Prepared; sent: number; total: number }
  | { phase: 'done'; prepared: Prepared }
  | { phase: 'failed'; prepared: Prepared | null; message: string };

export interface QueueItem {
  id: number;
  name: string;
  state: ItemState;
}

export type QueueAction =
  | { type: 'add'; id: number; name: string }
  | { type: 'ready'; id: number; prepared: Prepared }
  | { type: 'rejected'; id: number; message: string }
  | { type: 'sending'; id: number }
  | { type: 'progress'; id: number; sent: number; total: number }
  | { type: 'done'; id: number }
  | { type: 'failed'; id: number; message: string }
  | { type: 'remove'; id: number };

const preparedOf = (s: ItemState): Prepared | null => ('prepared' in s ? s.prepared : null);

export function queueReducer(items: QueueItem[], action: QueueAction): QueueItem[] {
  if (action.type === 'add') return [...items, { id: action.id, name: action.name, state: { phase: 'preparing' } }];
  if (action.type === 'remove') return items.filter((i) => i.id !== action.id);
  return items.map((item) => {
    if (item.id !== action.id) return item;
    const prepared = preparedOf(item.state);
    switch (action.type) {
      case 'ready':
        return { ...item, state: { phase: 'ready', prepared: action.prepared } };
      case 'rejected':
        return { ...item, state: { phase: 'failed', prepared: null, message: action.message } };
      case 'sending':
        return prepared ? { ...item, state: { phase: 'sending', prepared, sent: 0, total: prepared.blob.size } } : item;
      case 'progress':
        return item.state.phase === 'sending' ? { ...item, state: { ...item.state, sent: action.sent, total: action.total } } : item;
      case 'done':
        return prepared ? { ...item, state: { phase: 'done', prepared } } : item;
      case 'failed':
        return { ...item, state: { phase: 'failed', prepared, message: action.message } };
    }
  });
}

/** The whole-number percentage sent, never more than 99 until the server has answered. */
export function percentSent(sent: number, total: number): number {
  if (total <= 0) return 0;
  return Math.min(99, Math.floor((sent / total) * 100));
}

export type Sender<T> = (prepared: Prepared, opts: { onProgress: UploadProgress; signal: AbortSignal }) => Promise<T>;

export interface PhotoQueue {
  items: QueueItem[];
  /** Checks and shrinks the picked file and lists it; resolves with its id, or null when the file was refused (the refusal is listed too). */
  add(file: File): Promise<number | null>;
  remove(id: number): void;
  /** Sends one held picture (a retry sends the same one). True when the server took it. */
  send(id: number, sender: Sender<unknown>): Promise<boolean>;
  /** Sends every picture not yet saved, one after another. Resolves with how many were saved and how many failed. */
  sendAll(sender: Sender<unknown>): Promise<{ saved: number; failed: number }>;
  /** Pictures held that are not saved yet. */
  waiting: number;
  /** A picture is still being shrunk or is on its way: saving the record now would leave it behind. */
  busy: boolean;
}

export function usePhotoQueue(codec: Codec = browserCodec): PhotoQueue {
  const [items, dispatch] = useReducer(queueReducer, [] as QueueItem[]);
  const held = useRef(new Map<number, Prepared>());
  const states = useRef(items);
  states.current = items;
  const next = useRef(1);
  const aborts = useRef(new Set<AbortController>());
  const inFlight = useRef(new Set<number>());
  useEffect(() => () => aborts.current.forEach((a) => a.abort()), []);

  const add = useCallback(
    async (file: File) => {
      const id = next.current++;
      dispatch({ type: 'add', id, name: file.name });
      try {
        const prepared = await prepareFile(file, codec);
        held.current.set(id, prepared);
        dispatch({ type: 'ready', id, prepared });
        return id;
      } catch (e) {
        dispatch({ type: 'rejected', id, message: e instanceof PrepareError ? e.message : 'This file could not be used. Choose another.' });
        return null;
      }
    },
    [codec],
  );

  const send = useCallback(async (id: number, sender: Sender<unknown>) => {
    const prepared = held.current.get(id);
    // A second tap on "Send again" while the first is on its way would upload the picture twice.
    if (!prepared || inFlight.current.has(id)) return false;
    inFlight.current.add(id);
    const controller = new AbortController();
    aborts.current.add(controller);
    dispatch({ type: 'sending', id });
    try {
      await sender(prepared, { onProgress: (sent, total) => dispatch({ type: 'progress', id, sent, total }), signal: controller.signal });
      dispatch({ type: 'done', id });
      return true;
    } catch (e) {
      dispatch({ type: 'failed', id, message: e instanceof Error ? e.message : 'The upload failed. Try again.' });
      return false;
    } finally {
      aborts.current.delete(controller);
      inFlight.current.delete(id);
    }
  }, []);

  const sendAll = useCallback(
    async (sender: Sender<unknown>) => {
      let saved = 0;
      let failed = 0;
      for (const id of [...held.current.keys()]) {
        const state = states.current.find((i) => i.id === id)?.state;
        if (!state || state.phase === 'done' || state.phase === 'sending') continue;
        if (await send(id, sender)) saved++;
        else failed++;
      }
      return { saved, failed };
    },
    [send],
  );

  const remove = useCallback((id: number) => {
    held.current.delete(id);
    dispatch({ type: 'remove', id });
  }, []);

  const waiting = items.filter((i) => i.state.phase === 'ready' || i.state.phase === 'failed' && i.state.prepared !== null).length;
  const busy = items.some((i) => i.state.phase === 'preparing' || i.state.phase === 'sending');
  return { items, add, remove, send, sendAll, waiting, busy };
}

function Preview({ prepared }: { prepared: Prepared }) {
  const url = useRef<string | null>(null);
  const image = prepared.blob.type.startsWith('image/');
  if (image && url.current === null && typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function') url.current = URL.createObjectURL(prepared.blob);
  useEffect(() => () => {
    if (url.current && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url.current);
  }, []);
  const kb = Math.max(1, Math.round(prepared.blob.size / 1024));
  return (
    <span className="ph-preview">
      {image && url.current ? <img src={url.current} alt="" className="ph-thumb" /> : null}
      <span>{prepared.fileName} ({kb >= 1024 ? `${(kb / 1024).toFixed(1)} MB` : `${kb} KB`})</span>
    </span>
  );
}

function Row({ item, queue, sender }: { item: QueueItem; queue: PhotoQueue; sender?: Sender<unknown> }) {
  const { state } = item;
  return (
    <li className="ph-item">
      {state.phase === 'preparing' && <p className="loading">Getting {item.name} ready</p>}
      {(state.phase === 'ready' || state.phase === 'sending' || state.phase === 'done') && <Preview prepared={state.prepared} />}
      {state.phase === 'ready' && !sender && <p className="field-hint">Ready. It is sent when you save.</p>}
      {state.phase === 'sending' && (
        <p>
          <progress value={percentSent(state.sent, state.total)} max={100} aria-label={`Sending ${item.name}`} /> Sending {percentSent(state.sent, state.total)}%
        </p>
      )}
      {state.phase === 'done' && <p role="status" className="alert alert-success">Saved.</p>}
      {state.phase === 'failed' && (
        <div role="alert" className="alert alert-danger">
          <p>{state.message}</p>
          <p className="cluster">
            {state.prepared && sender && <button type="button" className="btn-sm" onClick={() => void queue.send(item.id, sender)}>Send again</button>}
            <button type="button" className="btn-sm btn-ghost" onClick={() => queue.remove(item.id)}>{state.prepared ? 'Remove' : 'Choose another'}</button>
          </p>
        </div>
      )}
      {(state.phase === 'ready') && <button type="button" className="btn-sm btn-ghost" onClick={() => queue.remove(item.id)}>Remove</button>}
    </li>
  );
}

/**
 * The control for one purpose. With a `sender` every picture goes the moment it is ready (a record that exists); without
 * one the screen sends later with `queue.sendAll`. `max` stops the buttons once that many pictures are listed.
 */
export function PhotoQueueControl({ id, label, hint, queue, sender, max = 1, disabled, tourId }: {
  id: string;
  label: string;
  hint?: ReactNode;
  queue: PhotoQueue;
  sender?: Sender<unknown>;
  max?: number;
  disabled?: boolean;
  tourId?: string;
}) {
  const camera = useRef<HTMLInputElement>(null);
  const files = useRef<HTMLInputElement>(null);
  const listed = queue.items.filter((i) => i.state.phase !== 'failed' || i.state.prepared !== null).length;
  const full = listed >= max;
  const busy = queue.busy;
  const pick = async (input: HTMLInputElement) => {
    const picked = Array.from(input.files ?? []).slice(0, Math.max(1, max - listed));
    input.value = '';
    for (const file of picked) {
      const itemId = await queue.add(file);
      if (itemId !== null && sender) await queue.send(itemId, sender);
    }
  };
  return (
    <div className="ph-slot" role="group" aria-labelledby={`${id}-label`} data-tour={tourId}>
      <p id={`${id}-label`} className="ph-label">{label}</p>
      {hint && <p className="field-hint" id={`${id}-hint`}>{hint}</p>}
      <div className="ph-buttons">
        <button type="button" className="btn-block" disabled={disabled || busy || full} onClick={() => camera.current?.click()} aria-describedby={hint ? `${id}-hint` : undefined}>
          Take a photo
        </button>
        <button type="button" className="btn-block btn-ghost" disabled={disabled || busy || full} onClick={() => files.current?.click()}>
          Choose a file
        </button>
      </div>
      <input ref={camera} id={`${id}-camera`} type="file" accept="image/*" capture="environment" className="visually-hidden" tabIndex={-1} aria-hidden="true" onChange={(e) => void pick(e.currentTarget)} />
      <input ref={files} id={`${id}-file`} type="file" accept={ACCEPT} multiple={max > 1} className="visually-hidden" tabIndex={-1} aria-hidden="true" onChange={(e) => void pick(e.currentTarget)} />
      <ul className="ph-list" aria-live="polite">
        {queue.items.map((item) => <Row key={item.id} item={item} queue={queue} sender={sender} />)}
      </ul>
    </div>
  );
}
