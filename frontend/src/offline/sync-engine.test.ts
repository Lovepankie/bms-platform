import { describe, expect, it, vi } from 'vitest';
import { GIVE_UP_AFTER_MS, KEEP_SENT_MS, MAX_SERVER_TRIES, provisionalNumber, type OutboxItem } from './outbox';
import { memoryStore } from './store';
import { SyncEngine, type Answer, type Decision, type SyncDeps } from './sync-engine';

// The sync engine (issue #458, ADR-067 decisions 4, 5 and 8; NFR-OFF-07): one at a time, oldest first, backoff, a refusal
// kept and reported, an expired session that stops it, a manager's decision followed, and never two sends of one sale at
// once. The clock is the test's. Fabricated ids and amounts.

const SCOPE = 'test.example|u1';

function item(id: string, minutes: number, patch: Partial<OutboxItem> = {}): OutboxItem {
  return {
    id,
    scope: SCOPE,
    key: `key-${id}`,
    request: { payment_method: 'cash', lines: [], client_sale_id: id, recorded_offline: true },
    occurredAt: new Date(Date.parse('2026-10-22T08:00:00Z') + minutes * 60_000).toISOString(),
    branchId: 'b1',
    branchName: 'Test Branch A',
    method: 'cash',
    totalMinor: 1000,
    lines: [],
    provisionalNo: provisionalNumber(id),
    state: 'queued',
    attempts: 0,
    nextAttemptAt: 0,
    ...patch,
  };
}

function setup(send: (item: OutboxItem) => Promise<Answer>, extra: Partial<SyncDeps> = {}) {
  let now = 1_000_000;
  const store = memoryStore();
  const sent: string[] = [];
  const deps: SyncDeps = {
    store,
    scope: SCOPE,
    send: async (i) => {
      sent.push(`${i.id}:${i.key}`);
      return send(i);
    },
    refreshSession: vi.fn(async () => false),
    report: vi.fn(async (i: OutboxItem): Promise<Decision> => ({ id: `f-${i.id}`, client_sale_id: i.id, status: 'open' })),
    decisions: vi.fn(async (): Promise<Decision[]> => []),
    now: () => now,
    later: () => () => {},
    ...extra,
  };
  const engine = new SyncEngine(deps);
  return { engine, store, sent, deps, tick: (ms: number) => (now += ms), now: () => now };
}

const ok = (i: OutboxItem): Answer => ({ status: 201, body: { id: `sale-${i.id}`, sale_no: `RS-${i.id}` } });

describe('the sync engine', () => {
  it('sends the outbox oldest first, one at a time, and keeps the server number', async () => {
    let inFlight = 0;
    let most = 0;
    const { engine, sent } = setup(async (i) => {
      inFlight += 1;
      most = Math.max(most, inFlight);
      await Promise.resolve();
      inFlight -= 1;
      return ok(i);
    });
    await engine.queue(item('c', 3));
    await engine.queue(item('a', 1));
    await engine.queue(item('b', 2));
    await Promise.all([engine.sync(), engine.sync(), engine.sync()]);
    expect(sent).toEqual(['a:key-a', 'b:key-b', 'c:key-c']);
    expect(most).toBe(1);
    expect(engine.snapshot().items.map((i) => [i.id, i.state, i.sale?.saleNo])).toEqual([
      ['a', 'sent', 'RS-a'],
      ['b', 'sent', 'RS-b'],
      ['c', 'sent', 'RS-c'],
    ]);
  });

  it('backs off on a failure of the network and keeps the order', async () => {
    let fail = true;
    const { engine, sent, tick } = setup(async (i) => {
      if (fail) throw new TypeError('Failed to fetch');
      return ok(i);
    });
    await engine.queue(item('a', 1));
    await engine.queue(item('b', 2));
    await engine.sync();
    expect(sent).toEqual(['a:key-a']);
    expect(engine.snapshot().items.map((i) => i.state)).toEqual(['queued', 'queued']);
    expect(engine.snapshot().items[0]?.attempts).toBe(1);

    fail = false;
    tick(4_000);
    await engine.sync();
    expect(sent).toEqual(['a:key-a']);
    tick(1_000);
    await engine.sync();
    expect(sent).toEqual(['a:key-a', 'a:key-a', 'b:key-b']);
  });

  it('sends again at once on Sync now, still in order', async () => {
    let fail = true;
    const { engine, sent } = setup(async (i) => {
      if (fail) return { status: 503, body: {} };
      return ok(i);
    });
    await engine.queue(item('a', 1));
    await engine.sync();
    fail = false;
    await engine.syncNow();
    expect(sent).toEqual(['a:key-a', 'a:key-a']);
    expect(engine.snapshot().items[0]?.state).toBe('sent');
  });

  it('keeps a refused sale with its reason, reports it, and goes on with the next', async () => {
    const { engine, sent, deps } = setup(async (i) =>
      i.id === 'a' ? { status: 422, body: { code: 'period_closed', detail: 'The period 2026-9 is closed.' } } : ok(i),
    );
    await engine.queue(item('a', 1));
    await engine.queue(item('b', 2));
    await engine.sync();
    expect(sent).toEqual(['a:key-a', 'b:key-b']);
    const [a, b] = engine.snapshot().items;
    expect(a).toMatchObject({ state: 'failed', error: { code: 'period_closed', message: 'The period 2026-9 is closed.' }, failureId: 'f-a', decision: 'open' });
    expect(b?.state).toBe('sent');
    expect(deps.report).toHaveBeenCalledTimes(1);
  });

  it('fails a sale the server keeps answering without taking it after 8 tries, reports it, and goes on', async () => {
    const { engine, sent, deps, tick } = setup(async (i) => (i.id === 'a' ? { status: 503, body: { detail: 'Busy.' } } : ok(i)));
    await engine.queue(item('a', 1));
    await engine.queue(item('b', 2));
    for (let n = 0; n < MAX_SERVER_TRIES; n++) {
      await engine.syncNow();
      tick(1);
    }
    const [a, b] = engine.snapshot().items;
    expect(sent.filter((x) => x.startsWith('a:'))).toHaveLength(MAX_SERVER_TRIES);
    expect(a).toMatchObject({ state: 'failed', error: { code: 'not_taken_after_retries' }, failureId: 'f-a' });
    expect(b?.state).toBe('sent');
    expect(deps.report).toHaveBeenCalledTimes(1);
  });

  it('fails a sale the server has not taken for 24 hours, but never for a phone with no network', async () => {
    let network = false;
    const { engine, sent, tick } = setup(async () => {
      if (!network) throw new TypeError('Failed to fetch');
      return { status: 500, body: {} };
    });
    await engine.queue(item('a', 1));
    for (let n = 0; n < 12; n++) {
      await engine.syncNow();
      tick(GIVE_UP_AFTER_MS);
    }
    expect(engine.snapshot().items[0]?.state).toBe('queued');
    network = true;
    await engine.syncNow();
    tick(GIVE_UP_AFTER_MS);
    await engine.syncNow();
    expect(sent).toHaveLength(14);
    expect(engine.snapshot().items[0]).toMatchObject({ state: 'failed', serverTries: 2 });
  });

  it('treats a refresh that got no answer as a try to do again, not a sign-in to ask for', async () => {
    const { engine } = setup(async () => ({ status: 401, body: {} }), { refreshUnreachable: () => true });
    await engine.queue(item('a', 1));
    await engine.sync();
    expect(engine.snapshot().needsSignIn).toBe(false);
    expect(engine.snapshot().items[0]).toMatchObject({ state: 'queued', attempts: 1 });
  });

  it('stops on an expired session after one refresh, and asks to sign in', async () => {
    const { engine, sent, deps } = setup(async () => ({ status: 401, body: {} }));
    await engine.queue(item('a', 1));
    await engine.queue(item('b', 2));
    await engine.sync();
    expect(deps.refreshSession).toHaveBeenCalledTimes(1);
    expect(sent).toEqual(['a:key-a']);
    expect(engine.snapshot().needsSignIn).toBe(true);
    expect(engine.snapshot().items.map((i) => i.state)).toEqual(['queued', 'queued']);
    await engine.sync();
    expect(sent).toEqual(['a:key-a']);
    engine.resume();
    expect(engine.snapshot().needsSignIn).toBe(false);
  });

  it('sends again once the refresh brings a new session', async () => {
    let first = true;
    const { engine, sent } = setup(
      async (i) => {
        if (first) {
          first = false;
          return { status: 401, body: {} };
        }
        return ok(i);
      },
      { refreshSession: async () => true },
    );
    await engine.queue(item('a', 1));
    await engine.sync();
    expect(sent).toEqual(['a:key-a', 'a:key-a']);
    expect(engine.snapshot().items[0]?.state).toBe('sent');
  });

  it("follows a manager's decision: sent again on retry, removed on discard", async () => {
    const decided: Decision[] = [];
    const { engine, sent } = setup(
      async (i) => (i.id === 'a' && sent.length < 2 ? { status: 422, body: { detail: 'This branch does not sell.' } } : ok(i)),
      { decisions: async () => decided },
    );
    await engine.queue(item('a', 1));
    await engine.queue(item('b', 2));
    await engine.sync();
    expect(engine.snapshot().items.find((i) => i.id === 'a')?.state).toBe('failed');

    decided.push({ id: 'f-a', client_sale_id: 'a', status: 'retry' });
    await engine.sync();
    expect(sent.filter((s) => s.startsWith('a:'))).toEqual(['a:key-a', 'a:key-a']);
    expect(engine.snapshot().items.find((i) => i.id === 'a')?.state).toBe('sent');
  });

  it('marks a sale discarded once a manager discarded it, never sends it again, and drops it after a day', async () => {
    const decided: Decision[] = [];
    const { engine, store, sent, tick } = setup(async () => ({ status: 422, body: { detail: 'Refused.' } }), { decisions: async () => decided });
    await engine.queue(item('a', 1));
    await engine.sync();
    decided.push({ id: 'f-a', client_sale_id: 'a', status: 'discarded' });
    await engine.sync();
    expect(engine.snapshot().items.map((i) => i.state)).toEqual(['discarded']);
    await engine.sendAgain('a');
    await engine.syncNow();
    expect(sent).toEqual(['a:key-a']);
    tick(KEEP_SENT_MS + 1);
    await engine.load();
    expect(await store.outbox(SCOPE)).toEqual([]);
  });

  it('stops sending a sale the server says a manager discarded, without reporting it', async () => {
    const { engine, sent, deps } = setup(async () => ({ status: 422, body: { code: 'offline_sale_discarded', detail: 'A manager discarded this sale.' } }));
    await engine.queue(item('a', 1));
    await engine.queue(item('b', 2));
    await engine.sync();
    expect(engine.snapshot().items.map((i) => i.state)).toEqual(['discarded', 'discarded']);
    expect(deps.report).not.toHaveBeenCalled();
    await engine.syncNow();
    expect(sent).toEqual(['a:key-a', 'b:key-b']);
  });

  it('lets the seller send a failed sale again with the same key', async () => {
    let refuse = true;
    const { engine, sent } = setup(async (i) => (refuse ? { status: 422, body: { detail: 'Refused.' } } : ok(i)));
    await engine.queue(item('a', 1));
    await engine.sync();
    refuse = false;
    await engine.sendAgain('a');
    expect(sent).toEqual(['a:key-a', 'a:key-a']);
    expect(engine.snapshot().items[0]?.state).toBe('sent');
  });

  it('keeps the outbox on the device across a reload, and a sale on its way goes again with its key', async () => {
    const store = memoryStore();
    await store.putItem(item('a', 1, { state: 'sending' }));
    const sent: string[] = [];
    const engine = new SyncEngine({
      store,
      scope: SCOPE,
      send: async (i) => {
        sent.push(i.key);
        return ok(i);
      },
      refreshSession: async () => false,
      report: async (i) => ({ id: i.id, client_sale_id: i.id, status: 'open' }),
      decisions: async () => [],
      now: () => 1,
      later: () => () => {},
    });
    await engine.load();
    await engine.sync();
    expect(sent).toEqual(['key-a']);
  });

  it('adds a sale once: a second queue under its client sale id leaves the first as it is', async () => {
    const { engine, store } = setup(async (i) => ok(i));
    await engine.queue(item('a', 1, { totalMinor: 1000 }));
    await engine.queue(item('a', 1, { totalMinor: 9999, key: 'another-key' }));
    expect((await store.outbox(SCOPE)).map((i) => [i.id, i.key, i.totalMinor])).toEqual([['a', 'key-a', 1000]]);
    expect(engine.snapshot().items.map((i) => i.key)).toEqual(['key-a']);
  });

  it('sends nothing while another tab holds the outbox, and shows what that tab did', async () => {
    const { engine, store, sent } = setup(async (i) => ok(i), { exclusive: async () => false });
    await engine.queue(item('a', 1));
    await store.putItem({ ...item('a', 1), state: 'sent', sale: { id: 's-a', saleNo: 'RS-a' }, sentAt: 1_000_000 });
    await engine.sync();
    expect(sent).toEqual([]);
    expect(engine.snapshot().items[0]?.state).toBe('sent');
  });

  it('reads the outbox again before sending when it holds the tab lock', async () => {
    const { engine, store, sent } = setup(async (i) => ok(i), { exclusive: async (fn) => (await fn(), true) });
    await store.putItem(item('b', 2));
    await engine.sync();
    expect(sent).toEqual(['b:key-b']);
  });

  it("never sends another person's outbox", async () => {
    const { engine } = setup(async (i) => ok(i));
    await expect(engine.queue(item('a', 1, { scope: 'test.example|someone-else' }))).rejects.toThrow();
  });
});
