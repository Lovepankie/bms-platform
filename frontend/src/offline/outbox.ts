import type { SalePayment, SaleRequest } from '../api/retail';

// The outbox of sales made offline (issue #458, ADR-067): what one queued sale holds, the order they go in, the backoff
// between tries, and how an answer from the server changes an item. Pure, so the sync engine and the tests share it.

/**
 * queued: waiting its turn; sending: on its way (sent again with the same key after a reload); failed: refused; sent: done;
 * discarded: a manager discarded it, so it is never sent again and goes after a day.
 */
export type OutboxState = 'queued' | 'sending' | 'failed' | 'sent' | 'discarded';

/** A line as the provisional receipt shows it. */
export interface OutboxLine {
  description: string;
  qty: string;
  unitPriceMinor: number;
  totalMinor: number;
}

/** The other daily forms kept on the phone while offline (issue #812, ADR-067 decision 13). */
export type EntryKind = 'expense' | 'savings' | 'banking' | 'withdrawal' | 'purchase' | 'transfer' | 'usage';

/** An entry that is not a sale: the route it goes to, the body as the form built it, and how to show and fix it. */
export interface OutboxEntry {
  kind: EntryKind;
  path: string;
  body: Record<string, unknown>;
  /** What it is in a few plain words, for the list and the toasts: "Expense, Transport, Fuel". */
  words: string;
  /** Where to fix a refused entry: the form's route, and its draft as it was saved, put back under the form's draft name. */
  fix?: { to: string; search?: Record<string, string>; draftName: string; draft: unknown };
  /** Sent online first and its answer lost: the server may record it under its key yet, so a first refusal is not final. */
  lost?: boolean;
}

export interface OutboxItem {
  /** The client sale id, made when the draft started; the server stores it on the sale. For an entry, its Idempotency-Key. */
  id: string;
  /** The business host and the person: an item is only ever seen and sent by the person who made it, on that business. */
  scope: string;
  /** The Idempotency-Key of the request. */
  key: string;
  /** A sale's request; absent for an entry, whose body is in {@link entry}. */
  request?: SaleRequest;
  /** Present for an entry that is not a sale (issue #812). */
  entry?: OutboxEntry;
  /** When the sale or entry was made on this device (ISO). */
  occurredAt: string;
  branchId: string;
  branchName: string;
  /** A sale's payment method; absent for an entry. */
  method?: SalePayment;
  totalMinor: number;
  lines: OutboxLine[];
  /** OFF- and the first eight characters of the client sale id, until the server's number replaces it. */
  provisionalNo: string;
  state: OutboxState;
  attempts: number;
  /** Tries the server answered without taking the sale (a 5xx, 409, 429): after {@link MAX_SERVER_TRIES} it is failed. */
  serverTries?: number;
  /** When the server first answered a try without taking it (epoch ms): after {@link GIVE_UP_AFTER_MS} it is failed. */
  firstServerTryAt?: number;
  /** Not sent before this time (epoch ms). */
  nextAttemptAt: number;
  /** How many times the server refused this item under its key (issue #812: a lost entry is Fixed only after two). */
  refusals?: number;
  /** The server's refusal of a failed item, or the last reason a try did not go through. */
  error?: { code?: string; message: string };
  /** A failed item reported to the server (POST /retail/offline-failures), and what a manager decided there. */
  failureId?: string;
  decision?: 'open' | 'retry' | 'discarded' | 'recorded';
  /** The server's sale, once sent. */
  sale?: { id: string; saleNo: string };
  /** An entry the server took: its id, and whether it waits for an administrator to approve it (ADR-091). */
  recorded?: { id: string; waiting: boolean };
  sentAt?: number;
  /** When the phone learnt that a manager discarded it (epoch ms). */
  discardedAt?: number;
}

/** 5 s, 15 s, 1 min, 5 min, then every 15 min (ADR-067 decision 4). */
export const BACKOFF_MS = [5_000, 15_000, 60_000, 300_000, 900_000];

/**
 * An item the server keeps answering without taking it (a 5xx, a 409, a 429) is failed after this many tries or this long,
 * reported like a refusal, so it never holds up the sales behind it (ADR-067 decision 4). A try that got no answer at all
 * (no network) does not count: a phone offline for a day still sends its sales in order.
 */
export const MAX_SERVER_TRIES = 8;
export const GIVE_UP_AFTER_MS = 24 * 60 * 60 * 1000;
export const GAVE_UP_CODE = 'not_taken_after_retries';

/** A sent item is kept this long so its receipt can show the server's number, then dropped. */
export const KEEP_SENT_MS = 24 * 60 * 60 * 1000;

export function backoff(attempts: number): number {
  return BACKOFF_MS[Math.min(Math.max(attempts, 1), BACKOFF_MS.length) - 1] ?? BACKOFF_MS[BACKOFF_MS.length - 1] ?? 900_000;
}

export function provisionalNumber(clientSaleId: string): string {
  return `OFF-${clientSaleId.replace(/-/g, '').slice(0, 8).toUpperCase()}`;
}

/** Oldest first by when the sale happened; the id breaks a tie so the order never changes between reads. */
export function inOrder(items: OutboxItem[]): OutboxItem[] {
  return [...items].sort((a, b) => a.occurredAt.localeCompare(b.occurredAt) || a.id.localeCompare(b.id));
}

/**
 * The one item to send now, or undefined. The oldest item still to send is the head of the queue: while it backs off,
 * nothing behind it goes, so sales reach the server in the order they happened. A failed item does not block the queue.
 */
export function nextToSend(items: OutboxItem[], now: number): OutboxItem | undefined {
  const head = inOrder(items).find((i) => i.state === 'queued' || i.state === 'sending');
  return head && head.nextAttemptAt <= now ? head : undefined;
}

/** What waits to sync: queued and failed items (a failed one waits for a manager or another try). */
export function waiting(items: OutboxItem[]): OutboxItem[] {
  return inOrder(items).filter((i) => i.state !== 'sent' && i.state !== 'discarded');
}

/** The items a manager discarded, shown as such until they go after a day. */
export function discarded(items: OutboxItem[]): OutboxItem[] {
  return inOrder(items).filter((i) => i.state === 'discarded');
}

/** The refusal of a sale a manager discarded (ADR-067 decision 8): the phone stops sending it. */
export const DISCARDED_CODE = 'offline_sale_discarded';

export function failedCount(items: OutboxItem[]): number {
  return items.filter((i) => i.state === 'failed').length;
}

/** "3 waiting to sync", or an empty string when nothing waits. */
export function waitingWords(count: number): string {
  return count === 0 ? '' : `${count} waiting to sync`;
}

/** 409 codes that mean an earlier request for the same sale has not finished yet (issue #769, ADR-067 decision 4). */
export const IN_PROGRESS_CODES = ['idempotency_in_progress', 'client_sale_in_progress'];

/** The answer to one try, as the engine reads it. */
export type SendOutcome =
  | { kind: 'sent'; sale?: { id: string; saleNo: string }; recorded?: { id: string; waiting: boolean } }
  | { kind: 'retry'; code?: string; message: string; answered?: boolean }
  | { kind: 'auth' }
  | { kind: 'refused'; code?: string; message: string };

/**
 * A status and body from POST /retail/sales. 2xx sent; 401 the session; 408, 409, 425, 429 and 5xx are tried again later
 * (409 includes a key or a client sale id still in progress); every other answer is a refusal kept with its words.
 * Issue #769: a 409 saying the same sale is still in progress (an earlier request under its key or its client sale id has
 * not finished) means try again later, never a refusal, and does not count towards giving up, so the item is never failed,
 * reported or discarded while that earlier request may still record the sale.
 */
export function classify(status: number, body: unknown): SendOutcome {
  const b = (body ?? {}) as { id?: string; sale_no?: string; code?: string; detail?: string; errors?: { field?: string; message?: string }[] };
  if (status >= 200 && status < 300 && b.id) return { kind: 'sent', sale: { id: b.id, saleNo: b.sale_no ?? '' } };
  if (status === 401) return { kind: 'auth' };
  if (status === 409 && IN_PROGRESS_CODES.includes(b.code ?? '')) {
    return { kind: 'retry', code: b.code, message: 'The server is still recording this sale.', answered: false };
  }
  // 426: this app is too old for the server (#576). The sale waits in the outbox for the new app, which sends it, and the answer
  // does not count towards giving up: the server never looked at the sale.
  if (status === 408 || status === 409 || status === 425 || status === 426 || status === 429 || status >= 500 || status === 0) {
    return { kind: 'retry', message: b.detail ?? 'The server could not take it just now.', answered: status !== 0 && status !== 426 };
  }
  const fields = (b.errors ?? []).map((e) => e.message).filter(Boolean).join(' ');
  return { kind: 'refused', code: b.code, message: [b.detail, fields].filter(Boolean).join(' ') || `Refused by the server (${status}).` };
}

/**
 * The answer to an entry that is not a sale (issue #812): any 2xx took it, with its id, or with the approval it waits for
 * (ADR-091: an expense worth the business's amount waits for an administrator, as it does online). Every other answer reads
 * as a sale's does, so a refusal keeps the server's words and the same answers are tried again later.
 */
export function classifyEntry(status: number, body: unknown): SendOutcome {
  const b = (body ?? {}) as { id?: string; approval_id?: string };
  if (status >= 200 && status < 300) {
    const waiting = typeof b.approval_id === 'string' && b.id === undefined;
    return { kind: 'sent', recorded: { id: b.id ?? b.approval_id ?? '', waiting } };
  }
  const read = classify(status, body);
  if (read.kind === 'retry' && IN_PROGRESS_CODES.includes(read.code ?? '')) return { ...read, message: 'The server is still recording this entry.' };
  // Any other 409 of an entry is a clash the person must look at (the savings suggestion changed, the day is saved): a refusal.
  if (status === 409) {
    const b = (body ?? {}) as { code?: string; detail?: string };
    return { kind: 'refused', code: b.code, message: b.detail ?? 'The server refused it: something changed since it was made.' };
  }
  // A field problem's own words say what is wrong ("That day's till is closed. Ask the owner to enter it."), not the
  // general line above them.
  const fields = ((body ?? {}) as { errors?: { message?: string }[] }).errors?.map((e) => e.message).filter(Boolean).join(' ');
  if (read.kind === 'refused' && read.code === 'validation_failed' && fields) return { ...read, message: fields };
  return read;
}

/** The item after one try. */
export function applyOutcome(item: OutboxItem, outcome: SendOutcome, now: number): OutboxItem {
  switch (outcome.kind) {
    case 'sent':
      return { ...item, state: 'sent', sale: outcome.sale, recorded: outcome.recorded, sentAt: now, error: undefined };
    case 'retry': {
      const attempts = item.attempts + 1;
      if (outcome.answered) {
        const serverTries = (item.serverTries ?? 0) + 1;
        const firstServerTryAt = item.firstServerTryAt ?? now;
        if (serverTries >= MAX_SERVER_TRIES || now - firstServerTryAt >= GIVE_UP_AFTER_MS) {
          return {
            ...item,
            state: 'failed',
            attempts,
            serverTries,
            firstServerTryAt,
            error: { code: GAVE_UP_CODE, message: `The server did not take this ${item.entry ? 'entry' : 'sale'} after ${serverTries} tries: ${outcome.message}` },
            decision: undefined,
            failureId: undefined,
          };
        }
        return { ...item, state: 'queued', attempts, serverTries, firstServerTryAt, nextAttemptAt: now + backoff(attempts), error: { message: outcome.message } };
      }
      return { ...item, state: 'queued', attempts, nextAttemptAt: now + backoff(attempts), error: { code: outcome.code, message: outcome.message } };
    }
    case 'auth':
      return { ...item, state: 'queued' };
    case 'refused':
      if (outcome.code === DISCARDED_CODE) return markDiscarded(item, now, outcome.message);
      return { ...item, state: 'failed', attempts: item.attempts + 1, refusals: (item.refusals ?? 0) + 1, error: { code: outcome.code, message: outcome.message }, decision: undefined, failureId: undefined };
  }
}

/** A failed item sent again (by its seller, or because a manager asked): queued now, with the same client sale id and key. */
export function requeue(item: OutboxItem, now: number): OutboxItem {
  return { ...item, state: 'queued', attempts: 0, serverTries: 0, firstServerTryAt: undefined, nextAttemptAt: now };
}

/** An item a manager discarded: kept to show for a day, never sent again. */
export function markDiscarded(item: OutboxItem, now: number, message?: string): OutboxItem {
  return {
    ...item,
    state: 'discarded',
    decision: 'discarded',
    discardedAt: now,
    error: { code: DISCARDED_CODE, message: message ?? 'A manager discarded this sale, so it is not recorded.' },
  };
}

/** Entries (not sales) sent in the last day, newest first, so the sheet can say they were sent. */
export function sentEntries(items: OutboxItem[]): OutboxItem[] {
  return inOrder(items).filter((i) => i.entry && i.state === 'sent').reverse();
}

/** Sent and discarded items past {@link KEEP_SENT_MS} are dropped. */
export function expired(item: OutboxItem, now: number): boolean {
  if (item.state === 'sent') return (item.sentAt ?? 0) + KEEP_SENT_MS < now;
  if (item.state === 'discarded') return (item.discardedAt ?? 0) + KEEP_SENT_MS < now;
  return false;
}
