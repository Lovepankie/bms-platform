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

export interface OutboxItem {
  /** The client sale id, made when the draft started; the server stores it on the sale. */
  id: string;
  /** The business host and the person: an item is only ever seen and sent by the person who made it, on that business. */
  scope: string;
  /** The Idempotency-Key of the request. */
  key: string;
  request: SaleRequest;
  /** When the sale happened on this device (ISO). */
  occurredAt: string;
  branchId: string;
  branchName: string;
  method: SalePayment;
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
  /** The server's refusal of a failed item, or the last reason a try did not go through. */
  error?: { code?: string; message: string };
  /** A failed item reported to the server (POST /retail/offline-failures), and what a manager decided there. */
  failureId?: string;
  decision?: 'open' | 'retry' | 'discarded' | 'recorded';
  /** The server's sale, once sent. */
  sale?: { id: string; saleNo: string };
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

/** The answer to one try, as the engine reads it. */
export type SendOutcome =
  | { kind: 'sent'; sale: { id: string; saleNo: string } }
  | { kind: 'retry'; message: string; answered?: boolean }
  | { kind: 'auth' }
  | { kind: 'refused'; code?: string; message: string };

/**
 * A status and body from POST /retail/sales. 2xx sent; 401 the session; 408, 409, 425, 429 and 5xx are tried again later
 * (409 includes a key or a client sale id still in progress); every other answer is a refusal kept with its words.
 */
export function classify(status: number, body: unknown): SendOutcome {
  const b = (body ?? {}) as { id?: string; sale_no?: string; code?: string; detail?: string; errors?: { field?: string; message?: string }[] };
  if (status >= 200 && status < 300 && b.id) return { kind: 'sent', sale: { id: b.id, saleNo: b.sale_no ?? '' } };
  if (status === 401) return { kind: 'auth' };
  if (status === 408 || status === 409 || status === 425 || status === 429 || status >= 500 || status === 0) {
    return { kind: 'retry', message: b.detail ?? 'The server could not take it just now.', answered: status !== 0 };
  }
  const fields = (b.errors ?? []).map((e) => e.message).filter(Boolean).join(' ');
  return { kind: 'refused', code: b.code, message: [b.detail, fields].filter(Boolean).join(' ') || `Refused by the server (${status}).` };
}

/** The item after one try. */
export function applyOutcome(item: OutboxItem, outcome: SendOutcome, now: number): OutboxItem {
  switch (outcome.kind) {
    case 'sent':
      return { ...item, state: 'sent', sale: outcome.sale, sentAt: now, error: undefined };
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
            error: { code: GAVE_UP_CODE, message: `The server did not take this sale after ${serverTries} tries: ${outcome.message}` },
            decision: undefined,
            failureId: undefined,
          };
        }
        return { ...item, state: 'queued', attempts, serverTries, firstServerTryAt, nextAttemptAt: now + backoff(attempts), error: { message: outcome.message } };
      }
      return { ...item, state: 'queued', attempts, nextAttemptAt: now + backoff(attempts), error: { message: outcome.message } };
    }
    case 'auth':
      return { ...item, state: 'queued' };
    case 'refused':
      if (outcome.code === DISCARDED_CODE) return markDiscarded(item, now, outcome.message);
      return { ...item, state: 'failed', attempts: item.attempts + 1, error: { code: outcome.code, message: outcome.message }, decision: undefined, failureId: undefined };
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

/** Sent and discarded items past {@link KEEP_SENT_MS} are dropped. */
export function expired(item: OutboxItem, now: number): boolean {
  if (item.state === 'sent') return (item.sentAt ?? 0) + KEEP_SENT_MS < now;
  if (item.state === 'discarded') return (item.discardedAt ?? 0) + KEEP_SENT_MS < now;
  return false;
}
