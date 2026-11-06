import { provisionalNumber, type EntryKind, type OutboxEntry, type OutboxItem } from './outbox';

// The other daily forms kept on the phone while offline (issue #812, ADR-067 decision 13): what route each goes to, its name
// in plain words, and the outbox item a form queues. Pure, so the forms, the engine and the tests share it.

/** The route each kind of entry is sent to, as the form sends it online. */
export const ENTRY_PATHS: Record<EntryKind, string> = {
  expense: '/api/v1/retail/expenses',
  savings: '/api/v1/retail/savings',
  banking: '/api/v1/retail/bankings',
  withdrawal: '/api/v1/retail/withdrawals',
  purchase: '/api/v1/retail/purchases',
  transfer: '/api/v1/retail/transfers',
  usage: '/api/v1/retail/usage',
  // Add many items on Import items (issue #870): the whole list is one apply of the import, under one key.
  items: '/api/v1/retail/products/import?dry_run=false',
};

/** The name of each kind, as the list and the toasts say it. */
export const ENTRY_NAMES: Record<EntryKind, string> = {
  expense: 'Expense',
  savings: 'Savings',
  banking: 'Banking',
  withdrawal: 'Withdrawal',
  purchase: 'Delivery',
  transfer: 'Stock move',
  usage: 'Usage or damage',
  items: 'List of items',
};

/**
 * The date fields a form may send. An entry made offline sends none of them: the server dates it by when it was made (the
 * date rule of #811, judged as an offline sale is). Buy stock's purchased_on is required by its route, so it is kept, and
 * the server does not use it for an entry made offline.
 */
const DATE_FIELDS = ['business_date', 'occurred_on', 'transfer_date', 'date_reason', 'banked_at', 'withdrawn_at'];

/** The body of an entry made offline: the form's body without its dates, with when it was made. */
export function offlineBody(body: Record<string, unknown>, occurredAt: string, onlineAsOf?: string): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(body)) if (!DATE_FIELDS.includes(k)) out[k] = v;
  out.offline = { occurred_at: occurredAt, ...(onlineAsOf ? { online_as_of: onlineAsOf } : {}) };
  return out;
}

export interface EntrySpec {
  kind: EntryKind;
  /** The route when it is not the kind's usual one (savings through the sales staff routes, issue #799). */
  path?: string;
  /** The body exactly as the form sends it online. */
  body: Record<string, unknown>;
  /** The form's Idempotency-Key, kept with the entry so it is recorded once however often it is sent. */
  key: string;
  branchId: string;
  branchName: string;
  /** What it is, after its kind's name: "Transport, Fuel". */
  detail?: string;
  /** The amount, for a money entry (an expense, savings, banking, a withdrawal). */
  amountMinor?: number;
  fix?: OutboxEntry['fix'];
}

/** The body of an online request whose answer was lost: as it was sent, with when it was made. */
export function lostBody(body: Record<string, unknown>, occurredAt: string, onlineAsOf?: string): Record<string, unknown> {
  // Always with when it was made (the lead's decision on the third review of #839): the server keeps an earlier day the
  // owner chose with its reason (ADR-120) and dates anything else by when the save began.
  return { ...body, offline: { occurred_at: occurredAt, ...(onlineAsOf ? { online_as_of: onlineAsOf } : {}) } };
}

/**
 * The outbox item of an entry. Made offline (`lost` false), it is dated by when it was made. `lost`: the form sent it online
 * and the answer never came, so the server may have it under its key already: it goes again under the same key, as it was
 * sent plus when it was made (`now`, the moment the save began). The key covers the request without that block (second
 * review of #839), so a request that arrived answers once, and one that never did is dated the day it was made, not the day
 * the phone got it through.
 */
export function offlineEntry(spec: EntrySpec & { scope: string; now: Date; onlineAsOf?: string; lost?: boolean }): OutboxItem {
  const occurredAt = spec.now.toISOString();
  const name = ENTRY_NAMES[spec.kind];
  return {
    id: spec.key,
    scope: spec.scope,
    key: spec.key,
    entry: {
      kind: spec.kind,
      path: spec.path ?? ENTRY_PATHS[spec.kind],
      body: spec.lost ? lostBody(spec.body, occurredAt, spec.onlineAsOf) : offlineBody(spec.body, occurredAt, spec.onlineAsOf),
      words: spec.detail ? `${name}, ${spec.detail}` : name,
      ...(spec.fix ? { fix: spec.fix } : {}),
      ...(spec.lost ? { lost: true } : {}),
    },
    occurredAt,
    branchId: spec.branchId,
    branchName: spec.branchName,
    totalMinor: spec.amountMinor ?? 0,
    lines: [],
    provisionalNo: provisionalNumber(spec.key),
    state: 'queued',
    attempts: 0,
    nextAttemptAt: spec.now.getTime(),
  };
}

// JUSTIFICATION-A3: the Lead's review of #871 asks a list's skipped rows to come back with Fix; nothing reads an answer's rows yet.
/** A list of items whose answer skipped some rows (issue #870): the entry comes back with Fix, never as sent. */
export const ITEMS_SKIPPED_CODE = 'items_skipped';

/**
 * The Lead on the review of #871: a list of items sent from the outbox whose answer skipped rows (a name already in the
 * items) is never counted sent quietly. Its words say how many and what to do, and Fix opens just those rows, each set to
 * Add to its stock of the item the answer named, with the quantity typed. Null when nothing was skipped. The draft keeps
 * `keys`, the row keys in the order the list's lines were sent (line 2 is the first).
 */
export function itemsSkipped(entry: OutboxEntry, body: unknown): { message: string; fix?: OutboxEntry['fix'] } | null {
  const rows = ((body ?? {}) as { rows?: { line?: number; outcome?: string; existing_code?: string }[] }).rows ?? [];
  const skipped = rows.filter((r) => r.outcome === 'skipped');
  if (skipped.length === 0) return null;
  const n = skipped.length;
  const message = n === 1
    ? '1 item is already in your list, so its stock was not added. Tap Fix to add it to its stock.'
    : `${n} items are already in your list, so their stock was not added. Tap Fix to add it to their stock.`;
  type Row = { key: number; name: string; refused?: unknown };
  const draft = entry.fix?.draft as { rows?: Row[]; keys?: number[] } | undefined;
  if (!entry.fix || !draft?.rows || !draft.keys) return { message };
  const back = new Map<number, string>();
  for (const r of skipped) {
    const key = draft.keys[(r.line ?? 0) - 2];
    if (key !== undefined) back.set(key, r.existing_code ?? '');
  }
  const again = draft.rows.filter((r) => back.has(r.key)).map((r) => {
    const code = back.get(r.key) ?? '';
    return { ...r, refused: undefined, ...(code ? { addTo: { id: code, code, name: r.name.trim() } } : {}) };
  });
  return { message, fix: { ...entry.fix, draft: { ...draft, rows: again, keys: undefined, pending: null } } };
}

/** What a form's save returns when the entry was kept on the phone instead of answered by the server. */
export interface Kept {
  kept: OutboxItem;
}

export function isKept(x: unknown): x is Kept {
  return typeof x === 'object' && x !== null && 'kept' in x && typeof (x as Kept).kept === 'object';
}

/** The toast when an entry is kept on the phone. */
export function keptWords(item: OutboxItem): string {
  return `${item.entry?.words ?? 'Entry'} saved on this phone. It is sent when the phone is back online.`;
}

/** The toast when an entry kept on the phone has reached the server. */
export function sentWords(item: OutboxItem): string {
  const name = item.entry ? ENTRY_NAMES[item.entry.kind] : 'Entry';
  return item.recorded?.waiting ? `${name} sent. It waits for an administrator to approve it.` : `${name} sent`;
}
