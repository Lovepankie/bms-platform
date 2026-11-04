// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { lastOnline, rememberOnline } from './connectivity';
import { ENTRY_PATHS, isKept, lostBody, offlineBody, offlineEntry, sentWords } from './entries';
import { GAVE_UP_CODE, MAX_SERVER_TRIES, classifyEntry, failedCount, provisionalNumber, sentEntries, waiting, waitingWords, type OutboxItem } from './outbox';
import { memoryStore } from './store';
import { SyncEngine, type Answer } from './sync-engine';
import { statusWords } from './sync-status';

// Entries kept on the phone with sales in one outbox (issue #812, ADR-067 decision 13): sent in the order they were made,
// one at a time, each under its own key; a refused entry keeps the server's words, does not hold up the rest and is never
// reported to a manager; an entry that waits for approval counts as sent; the waiting count covers sales and entries.
// Fabricated ids and amounts.

const SCOPE = 'test.example|u1';
const AT = Date.parse('2026-11-03T08:00:00Z');

function entry(kind: 'expense' | 'transfer' | 'banking', key: string, minutes: number): OutboxItem {
  return offlineEntry({
    kind, key, scope: SCOPE, branchId: 'b1', branchName: 'Test Branch A', body: { branch_id: 'b1', business_date: '2026-11-03', amount_minor: 1000 },
    amountMinor: 1000, now: new Date(AT + minutes * 60_000),
  });
}

function sale(id: string, minutes: number): OutboxItem {
  return {
    id, scope: SCOPE, key: `key-${id}`, request: { payment_method: 'cash', lines: [], client_sale_id: id, recorded_offline: true },
    occurredAt: new Date(AT + minutes * 60_000).toISOString(), branchId: 'b1', branchName: 'Test Branch A', method: 'cash', totalMinor: 1000,
    lines: [], provisionalNo: provisionalNumber(id), state: 'queued', attempts: 0, nextAttemptAt: 0,
  };
}

function setup(answer: (item: OutboxItem) => Answer) {
  const sent: string[] = [];
  const report = vi.fn(async (i: OutboxItem) => ({ id: `f-${i.id}`, client_sale_id: i.id, status: 'open' as const }));
  const engine = new SyncEngine({
    store: memoryStore(), scope: SCOPE, refreshSession: async () => false, decisions: async () => [], report,
    now: () => AT + 3_600_000, later: () => () => {},
    send: async (i) => {
      sent.push(i.key);
      return answer(i);
    },
  });
  return { engine, sent, report };
}

describe('an entry kept on the phone', () => {
  it('goes without its dates, with when it was made and the server time last seen', () => {
    const body = offlineBody({ branch_id: 'b1', business_date: '2026-11-01', transfer_date: '2026-11-01', occurred_on: '2026-11-01', amount_minor: 5 }, '2026-11-03T08:00:00.000Z', '2026-11-03T07:00:00.000Z');
    expect(body).toEqual({ branch_id: 'b1', amount_minor: 5, offline: { occurred_at: '2026-11-03T08:00:00.000Z', online_as_of: '2026-11-03T07:00:00.000Z' } });
    const item = entry('expense', 'k1', 0);
    expect(item.id).toBe('k1');
    expect(item.entry?.path).toBe(ENTRY_PATHS.expense);
    expect(item.entry?.words).toBe('Expense');
    expect(isKept({ kept: item })).toBe(true);
    expect(isKept({ id: 'x' })).toBe(false);
  });

  it('sends a lost online request again with when it was made, always (third review of #839)', () => {
    expect(lostBody({ amount_minor: 5 }, '2026-11-03T20:58:00.000Z', '2026-11-03T20:57:00.000Z')).toEqual({
      amount_minor: 5,
      offline: { occurred_at: '2026-11-03T20:58:00.000Z', online_as_of: '2026-11-03T20:57:00.000Z' },
    });
    // The owner's chosen earlier day (ADR-120) stays in the body; the server keeps it over the block.
    const owners = { amount_minor: 5, business_date: '2026-11-01', date_reason: 'Entered late' };
    expect(lostBody(owners, '2026-11-03T20:58:00.000Z')).toEqual({ ...owners, offline: { occurred_at: '2026-11-03T20:58:00.000Z' } });
    const lost = offlineEntry({ kind: 'expense', key: 'k-lost', scope: SCOPE, branchId: 'b1', branchName: 'Test Branch A', body: { amount_minor: 5 }, now: new Date(AT), lost: true });
    expect((lost.entry?.body.offline as { occurred_at: string }).occurred_at).toBe(lost.occurredAt);
  });

  it('is sent with the sales in the order everything was made, one key each, once', async () => {
    const { engine, sent } = setup((i) => ({ status: 201, body: { id: `rec-${i.key}`, sale_no: 'RS-1' } }));
    await engine.queue(entry('transfer', 'k-move', 3));
    await engine.queue(sale('s1', 2));
    await engine.queue(entry('expense', 'k-exp', 1));
    expect(waiting(engine.snapshot().items)).toHaveLength(3);
    expect(waitingWords(waiting(engine.snapshot().items).length)).toBe('3 waiting to sync');
    await engine.sync();
    await engine.sync();
    expect(sent).toEqual(['k-exp', 'key-s1', 'k-move']);
    expect(waiting(engine.snapshot().items)).toHaveLength(0);
    expect(sentEntries(engine.snapshot().items).map((i) => i.recorded?.id)).toEqual(['rec-k-move', 'rec-k-exp']);
  });

  it('keeps a refusal with the server words, never reports it to a manager, and sends the rest', async () => {
    const { engine, sent, report } = setup((i) =>
      i.key === 'k-bad'
        ? { status: 422, body: { code: 'validation_failed', detail: 'Check the amount.' } }
        : { status: 201, body: { id: `rec-${i.key}` } });
    await engine.queue(entry('expense', 'k-bad', 1));
    await engine.queue(entry('banking', 'k-good', 2));
    await engine.sync();
    expect(sent).toEqual(['k-bad', 'k-good']);
    const [bad, good] = engine.snapshot().items;
    expect(bad?.state).toBe('failed');
    expect(bad?.error?.message).toBe('Check the amount.');
    expect(good?.state).toBe('sent');
    expect(report).not.toHaveBeenCalled();
    // The count is what still waits: the refused one, with its own word.
    expect(statusWords('online', waiting(engine.snapshot().items).length, failedCount(engine.snapshot().items))).toBe('Online, 1 waiting to sync, 1 failed');
    // Its maker discards it: gone from the phone, nothing more sent.
    expect((await engine.takeBack('k-bad'))?.key).toBe('k-bad');
    expect(waiting(engine.snapshot().items)).toHaveLength(0);
    expect(await engine.takeBack('k-good')).toBeUndefined();
  });

  it('counts an answer that waits for an administrator as sent, as online (ADR-091)', async () => {
    expect(classifyEntry(202, { approval_id: 'a1', amount_minor: 1000 })).toEqual({ kind: 'sent', recorded: { id: 'a1', waiting: true } });
    const { engine } = setup(() => ({ status: 202, body: { approval_id: 'a1' } }));
    await engine.queue(entry('expense', 'k-big', 1));
    await engine.sync();
    const [item] = engine.snapshot().items;
    expect(item?.recorded).toEqual({ id: 'a1', waiting: true });
    expect(item && sentWords(item)).toBe('Expense sent. It waits for an administrator to approve it.');
  });

  it('sends the later of the last answered ping and the catalogue time as online_as_of, for sellers and others (review of #839)', () => {
    localStorage.clear();
    expect(lastOnline()).toBeUndefined();
    rememberOnline('Tue, 03 Nov 2026 09:00:00 GMT');
    expect(lastOnline()).toBe('2026-11-03T09:00:00.000Z');
    expect(lastOnline('2026-11-03T08:00:00.000Z')).toBe('2026-11-03T09:00:00.000Z');
    expect(lastOnline('2026-11-03T10:00:00.000Z')).toBe('2026-11-03T10:00:00.000Z');
    rememberOnline('not a date');
    expect(lastOnline()).toBe('2026-11-03T09:00:00.000Z');
  });

  it('refuses a clash (409) instead of trying it again, and tries a key still in progress again', () => {
    expect(classifyEntry(409, { code: 'suggestion_changed', detail: 'The day changed. Look again.' })).toEqual({ kind: 'refused', code: 'suggestion_changed', message: 'The day changed. Look again.' });
    expect(classifyEntry(409, { code: 'idempotency_in_progress' }).kind).toBe('retry');
    expect(classifyEntry(503, {}).kind).toBe('retry');
  });

  // JUSTIFICATION-A3: a new test the fifth review of #839 asks for (gave-up reached through real retries), not a mechanism.
  it('gives up on an entry the server keeps answering without taking it, never reports it, and Send again keeps its key', async () => {
    let now = AT;
    let busy = true;
    const sent: string[] = [];
    const report = vi.fn();
    const engine = new SyncEngine({
      store: memoryStore(), scope: SCOPE, refreshSession: async () => false, decisions: async () => [], report,
      now: () => now, later: () => () => {},
      send: async (i) => {
        sent.push(i.key);
        return busy ? { status: 503, body: { detail: 'Busy.' } } : { status: 201, body: { id: 'rec-1' } };
      },
    });
    await engine.queue(entry('expense', 'k-busy', 0));
    for (let n = 0; n < MAX_SERVER_TRIES; n++) {
      await engine.syncNow();
      now += 3_600_000;
    }
    const [item] = engine.snapshot().items;
    expect(item).toMatchObject({ state: 'failed', error: { code: GAVE_UP_CODE } });
    expect(sent).toEqual(Array(MAX_SERVER_TRIES).fill('k-busy'));
    expect(report).not.toHaveBeenCalled();
    busy = false;
    await engine.sendAgain('k-busy');
    expect(sent.at(-1)).toBe('k-busy');
    expect(engine.snapshot().items[0]?.state).toBe('sent');
  });
});
