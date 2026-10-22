import { describe, expect, it } from 'vitest';
import { BACKOFF_MS, applyOutcome, backoff, classify, expired, failedCount, inOrder, nextToSend, provisionalNumber, requeue, waiting, waitingWords, type OutboxItem } from './outbox';

// The outbox of offline sales (issue #458, ADR-067; NFR-OFF-07): the order, the backoff, the answers and the counts.
// Fabricated ids and amounts.

const T0 = Date.parse('2026-10-22T08:00:00Z');

function item(id: string, minutes: number, patch: Partial<OutboxItem> = {}): OutboxItem {
  return {
    id,
    scope: 'test.example|u1',
    key: `key-${id}`,
    request: { payment_method: 'cash', lines: [] },
    occurredAt: new Date(T0 + minutes * 60_000).toISOString(),
    branchId: 'b1',
    branchName: 'Test Branch A',
    method: 'cash',
    totalMinor: 1000,
    lines: [],
    provisionalNo: provisionalNumber(id),
    state: 'queued',
    attempts: 0,
    nextAttemptAt: T0,
    ...patch,
  };
}

describe('the order of the outbox', () => {
  it('sends the oldest sale first, whatever order they were stored in', () => {
    const items = [item('c', 3), item('a', 1), item('b', 2)];
    expect(inOrder(items).map((i) => i.id)).toEqual(['a', 'b', 'c']);
    expect(nextToSend(items, T0)?.id).toBe('a');
  });

  it('keeps the order: while the oldest backs off, nothing behind it goes', () => {
    const items = [item('a', 1, { attempts: 1, nextAttemptAt: T0 + 5_000 }), item('b', 2)];
    expect(nextToSend(items, T0)).toBeUndefined();
    expect(nextToSend(items, T0 + 5_000)?.id).toBe('a');
  });

  it('does not let a failed sale block the ones behind it', () => {
    const items = [item('a', 1, { state: 'failed' }), item('b', 2)];
    expect(nextToSend(items, T0)?.id).toBe('b');
  });

  it('sends again a sale left on its way by a reload, with the same key', () => {
    const left = item('a', 1, { state: 'sending' });
    expect(nextToSend([left], T0)).toEqual(left);
  });
});

describe('backoff', () => {
  it('waits 5 s, 15 s, 1 min, 5 min, then 15 min', () => {
    expect([1, 2, 3, 4, 5, 6, 20].map(backoff)).toEqual([5_000, 15_000, 60_000, 300_000, 900_000, 900_000, 900_000]);
    expect(BACKOFF_MS).toHaveLength(5);
  });
});

describe('an answer from the server', () => {
  it('reads a 201 as sent, with the server number', () => {
    expect(classify(201, { id: 's1', sale_no: 'RS00000042' })).toEqual({ kind: 'sent', sale: { id: 's1', saleNo: 'RS00000042' } });
  });

  it('tries again later on 5xx, 409, 429 and no answer', () => {
    for (const status of [500, 502, 503, 409, 429, 408, 0]) expect(classify(status, {}).kind).toBe('retry');
  });

  it('stops for the session on 401', () => {
    expect(classify(401, {}).kind).toBe('auth');
  });

  it('keeps a refusal with its words and the field messages', () => {
    expect(classify(422, { code: 'period_closed', detail: 'The period 2026-9 is closed.' })).toEqual({ kind: 'refused', code: 'period_closed', message: 'The period 2026-9 is closed.' });
    expect(classify(422, { code: 'validation_failed', detail: 'Check the fields.', errors: [{ field: 'occurred_at', message: 'A sale recorded offline says when it happened.' }] }).kind).toBe('refused');
    expect(classify(403, {}).kind).toBe('refused');
  });

  it('backs off on a retry and counts the attempt', () => {
    const next = applyOutcome(item('a', 1), { kind: 'retry', message: 'No connection to the server.' }, T0);
    expect(next.state).toBe('queued');
    expect(next.attempts).toBe(1);
    expect(next.nextAttemptAt).toBe(T0 + 5_000);
  });

  it('keeps a refused sale on the phone, failed, with the reason', () => {
    const next = applyOutcome(item('a', 1), { kind: 'refused', code: 'branch_does_not_sell', message: 'This branch does not sell.' }, T0);
    expect(next.state).toBe('failed');
    expect(next.error).toEqual({ code: 'branch_does_not_sell', message: 'This branch does not sell.' });
    expect(waiting([next])).toHaveLength(1);
    expect(failedCount([next])).toBe(1);
  });

  it('a failed sale sent again keeps its client id and key and goes now', () => {
    const failed = item('a', 1, { state: 'failed', attempts: 3 });
    const again = requeue(failed, T0 + 1000);
    expect(again).toMatchObject({ id: 'a', key: 'key-a', state: 'queued', attempts: 0, nextAttemptAt: T0 + 1000 });
  });
});

describe('counts and words', () => {
  it('counts queued and failed sales, not sent ones', () => {
    const items = [item('a', 1), item('b', 2, { state: 'failed' }), item('c', 3, { state: 'sent', sentAt: T0 })];
    expect(waiting(items).map((i) => i.id)).toEqual(['a', 'b']);
    expect(waitingWords(3)).toBe('3 waiting to sync');
    expect(waitingWords(0)).toBe('');
  });

  it('numbers a provisional receipt from the client sale id', () => {
    expect(provisionalNumber('0a1b2c3d-4e5f-4000-8000-000000000001')).toBe('OFF-0A1B2C3D');
  });

  it('forgets a sent sale after a day, never a waiting one', () => {
    expect(expired(item('a', 1, { state: 'sent', sentAt: T0 }), T0 + 25 * 3600_000)).toBe(true);
    expect(expired(item('a', 1, { state: 'sent', sentAt: T0 }), T0 + 3600_000)).toBe(false);
    expect(expired(item('a', 1), T0 + 30 * 24 * 3600_000)).toBe(false);
  });
});
