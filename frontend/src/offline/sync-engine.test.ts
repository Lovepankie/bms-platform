import { describe, expect, it, vi } from 'vitest';
import { GIVE_UP_AFTER_MS, KEEP_SENT_MS, MAX_SERVER_TRIES, provisionalNumber, waiting, type OutboxItem } from './outbox';
import { memoryStore } from './store';
import { FORM_SLACK_MS, SyncEngine, type Answer, type Decision, type SyncDeps } from './sync-engine';

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

  it('tries again a sale whose key the first online request still holds, never failing it (issue #769)', async () => {
    let held = true;
    const { engine, sent, deps } = setup(async (i) =>
      held ? { status: 409, body: { code: 'idempotency_in_progress', detail: 'A request with this Idempotency-Key is still being processed; retry shortly.' } } : ok(i),
    );
    await engine.queue(item('a', 1));
    await engine.sync();
    expect(engine.snapshot().items[0]?.state).toBe('queued');
    held = false;
    await engine.syncNow();
    expect(sent).toEqual(['a:key-a', 'a:key-a']);
    expect(engine.snapshot().items[0]).toMatchObject({ state: 'sent', sale: { saleNo: 'RS-a' } });
    expect(deps.report).not.toHaveBeenCalled();
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

  // JUSTIFICATION-A3: a new test the issue asks for (#769), not a mechanism.
  it('tries a 409 in progress again later with backoff, never as a refusal, and then takes the replay (issue #769)', async () => {
    const replay = { status: 201, body: { id: 'sale-a', sale_no: 'RS-a' } };
    let busy = MAX_SERVER_TRIES + 2;
    const { engine, sent, deps, tick } = setup(async (i) => {
      if (i.id === 'a' && busy > 0) {
        busy -= 1;
        return { status: 409, body: { code: busy % 2 ? 'idempotency_in_progress' : 'client_sale_in_progress', detail: 'Still being processed.' } };
      }
      return i.id === 'a' ? replay : ok(i);
    });
    await engine.queue(item('a', 1));
    await engine.queue(item('b', 2));
    await engine.syncNow();
    // The first answer is 409: a try again later, with backoff, and the order is kept.
    expect(sent).toEqual(['a:key-a']);
    expect(engine.snapshot().items.map((i) => i.state)).toEqual(['queued', 'queued']);
    expect(engine.snapshot().items[0]).toMatchObject({ attempts: 1, error: { code: 'idempotency_in_progress' } });
    expect(engine.snapshot().items[0]?.serverTries ?? 0).toBe(0);
    await engine.sync();
    expect(sent).toHaveLength(1);
    // More 409s than the tries that fail a sale, and longer than a day: never failed, reported or offered to discard.
    for (let n = 0; n < MAX_SERVER_TRIES + 2; n++) {
      tick(GIVE_UP_AFTER_MS);
      await engine.sync();
      expect(engine.snapshot().items[0]?.state).not.toBe('failed');
    }
    expect(deps.report).not.toHaveBeenCalled();
    expect(deps.decisions).not.toHaveBeenCalled();
    expect(engine.snapshot().items.map((i) => [i.id, i.state, i.sale?.saleNo])).toEqual([
      ['a', 'sent', 'RS-a'],
      ['b', 'sent', 'RS-b'],
    ]);
    expect(sent.filter((s) => s === 'a:key-a')).toHaveLength(MAX_SERVER_TRIES + 3);
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

  it('makes the backing off due on Sync now only after reading the outbox again, so a sale another tab sent stays sent', async () => {
    let fail = true;
    const { engine, store, sent } = setup(
      async (i) => (fail ? { status: 503, body: {} } : ok(i)),
      { exclusive: async (fn) => (await fn(), true) },
    );
    await engine.queue(item('a', 1));
    await engine.sync();
    expect(engine.snapshot().items[0]?.state).toBe('queued');
    // Another tab sent it meanwhile; this tab still holds it as backing off.
    await store.putItem({ ...item('a', 1), state: 'sent', sale: { id: 's-a', saleNo: 'RS-a' }, sentAt: 1_000_000 });
    fail = false;
    await engine.syncNow();
    expect(sent).toEqual(['a:key-a']);
    expect((await store.outbox(SCOPE))[0]?.state).toBe('sent');
  });

  it('writes nothing on Sync now while another tab holds the outbox', async () => {
    const { engine, store, sent } = setup(async () => ({ status: 503, body: {} }));
    await engine.queue(item('a', 1));
    await engine.sync();
    const before = (await store.outbox(SCOPE))[0];
    const held = setup(async (i) => ok(i), { store, exclusive: async () => false });
    await held.engine.load();
    await held.engine.syncNow();
    expect(held.sent).toEqual([]);
    expect(sent).toEqual(['a:key-a']);
    expect((await store.outbox(SCOPE))[0]).toEqual(before);
  });

  // JUSTIFICATION-A3: the tests issue #852 asks for, not a mechanism.
  it("holds a sale and an entry another person's sign-in met, never sends them under that person, and sends them with their keys when the maker signs in again (issue #852)", async () => {
    // The token of the moment: null while unknown (the request may still go out under anyone), else its person.
    let who: string | null = null;
    const other = { status: 422, body: { code: 'idempotency_key_other_user', detail: 'Someone else in this business already used this Idempotency-Key.' } };
    const { engine, store, sent, deps } = setup(async (i) => (who === 'u1' ? ok(i) : other), { signedInAs: () => who });
    const entry = item('e', 2, { request: undefined, method: undefined, entry: { kind: 'expense', path: '/api/v1/retail/expenses', body: { amount_minor: 5000 }, words: 'Expense, Transport' } });
    await engine.queue(item('a', 1));
    await engine.queue(entry);
    await engine.sync();
    // Both went out under a sign-in the phone could not read and met another person's key: held, as they were.
    expect(sent).toEqual(['a:key-a', 'e:key-e']);
    for (const held of [...engine.snapshot().items, ...(await store.outbox(SCOPE))]) {
      expect(held).toMatchObject({ state: 'held', key: `key-${held.id}`, attempts: 0, error: { code: 'idempotency_key_other_user', message: 'This was saved by the person who made it; it is sent when they sign in again.' } });
      expect(held.refusals).toBeUndefined();
    }
    expect(deps.report).not.toHaveBeenCalled();
    await expect(engine.takeBack('e')).resolves.toBeUndefined();

    // Another person is signed in now: nothing of this outbox goes, not the held items and not a new one.
    who = 'u2';
    await engine.queue(item('b', 3));
    engine.resume();
    await engine.syncNow();
    expect(sent).toEqual(['a:key-a', 'e:key-e']);
    expect(engine.snapshot().items.map((i) => [i.id, i.state])).toEqual([['a', 'held'], ['e', 'held'], ['b', 'queued']]);

    // The maker signs in again: each goes once, in order, under its own key, and is recorded.
    who = 'u1';
    engine.resume();
    await engine.sync();
    expect(sent).toEqual(['a:key-a', 'e:key-e', 'a:key-a', 'e:key-e', 'b:key-b']);
    expect(engine.snapshot().items.map((i) => [i.id, i.key, i.state])).toEqual([['a', 'key-a', 'sent'], ['e', 'key-e', 'sent'], ['b', 'key-b', 'sent']]);
  });

  it('sends nothing after a refresh that brought another person\'s session, and keeps the item as it was (issue #852)', async () => {
    let who: string | null = 'u1';
    const { engine, sent } = setup(async () => ({ status: 401, body: {} }), {
      signedInAs: () => who,
      refreshSession: async () => {
        who = 'u2';
        return true;
      },
    });
    await engine.queue(item('a', 1));
    await engine.sync();
    expect(sent).toEqual(['a:key-a']);
    expect(engine.snapshot().items[0]).toMatchObject({ state: 'queued', key: 'key-a', attempts: 0 });
    expect(engine.snapshot().needsSignIn).toBe(false);
  });

  it("never sends another person's outbox", async () => {
    const { engine } = setup(async (i) => ok(i));
    await expect(engine.queue(item('a', 1, { scope: 'test.example|someone-else' }))).rejects.toThrow();
  });
});

// JUSTIFICATION-A3: issue #844 asks for these tests of the new hold; they need their own setup.
describe('an item kept at save, in flight from its form (issue #844)', () => {
  it('is never sent, reported or counted as waiting while the form waits, and goes once when the form gets no answer', async () => {
    const { engine, sent, deps } = setup(async () => ({ status: 422, body: { code: 'branch_closed', detail: 'Closed.' } }));
    // JUSTIFICATION-A3: the tests the review of #859 asks for (items 1, 2 and 7), not a mechanism.
    const counted = () => waiting(engine.snapshot().items, engine.snapshot().formHeld).map((i) => i.id);
    expect(await engine.hold(item('a', 1), 8_000)).toBe(true);
    await engine.syncNow();
    expect(sent).toEqual([]);
    expect(counted()).toEqual([]);
    // The form's tab was frozen past the hold (the seller switched app): still the form's, never sent or reported from here.
    deps.now = () => 1_000_000 + 60_000;
    await engine.syncNow();
    expect(sent).toEqual([]);
    expect(deps.report).not.toHaveBeenCalled();
    expect(counted()).toEqual([]);
    await engine.formUnanswered('a');
    expect(counted()).toEqual(['a']);
    expect(engine.snapshot().items[0]?.formUntil).toBeUndefined();
    await engine.sync();
    expect(sent).toEqual(['a:key-a']);
  });

  it('leaves an item on the phone as it is when a form holds it already (Try again after a reload)', async () => {
    const { engine, store } = setup(async (i) => ok(i));
    await engine.queue(item('a', 1));
    expect(await engine.hold(item('a', 1, { key: 'key-other' }), 8_000)).toBe(false);
    expect(engine.snapshot().formHeld).toEqual([]);
    expect((await store.outbox(SCOPE)).map((i) => [i.key, i.formUntil])).toEqual([['key-a', undefined]]);
  });

  it("leaves to the engines an item another tab sent while the form's tab was frozen past its hold", async () => {
    const tab = setup(async (i) => ok(i));
    await tab.engine.hold(item('a', 1), 8_000);
    // A second tab of the app on the same phone, its clock past the hold: it sends the item under its key.
    const other = new SyncEngine({ ...tab.deps, now: () => tab.now() + 60_000 });
    await other.load();
    await other.sync();
    expect(tab.sent).toEqual(['a:key-a']);
    // The frozen form gets its answer at last: the item the other tab sent stays, with the server's number.
    await tab.engine.formAnswered('a');
    expect((await tab.store.outbox(SCOPE)).map((i) => [i.id, i.state, i.sale?.saleNo])).toEqual([['a', 'sent', 'RS-a']]);
    expect(tab.engine.snapshot().items.map((i) => i.state)).toEqual(['sent']);
  });

  it('does not believe a hold further ahead than any form holds, as after the clock was moved back, and clears it on load', async () => {
    const { engine, store, sent } = setup(async (i) => ok(i));
    await store.addItem(item('a', 1, { formUntil: 1_000_000 + 3 * 60 * 60 * 1000 }));
    await store.addItem(item('b', 2, { formUntil: 1_000_000 - 1 }));
    await store.addItem(item('c', 3, { formUntil: 1_000_000 + 5_000 }));
    await engine.load();
    // A passed or impossible hold is stale and goes; one within its time stays, as another tab's form may wait on it.
    expect((await store.outbox(SCOPE)).map((i) => [i.id, i.formUntil])).toEqual([['a', undefined], ['b', undefined], ['c', 1_005_000]]);
    await engine.sync();
    expect(sent).toEqual(['a:key-a', 'b:key-b']);
  });

  it('reads an item again just before it goes, and leaves it when a form holds it or took it off meanwhile', async () => {
    const { engine, store, sent } = setup(async (i) => ok(i));
    await engine.queue(item('b', 1));
    await engine.queue(item('a', 2));
    // Another tab's form took b off, and holds a, since this engine read the outbox.
    await store.deleteItem('b');
    await store.putItem(item('a', 2, { formUntil: 1_000_000 + 8_000 }));
    await engine.sync();
    expect(sent).toEqual([]);
    expect(engine.snapshot().items.map((i) => [i.id, i.formUntil])).toEqual([['a', 1_008_000]]);
  });

  it('is taken off the phone when the form gets its answer, a 2xx or a refusal', async () => {
    const { engine, store, sent } = setup(async (i) => ok(i));
    await engine.hold(item('a', 1), 8_000);
    await engine.hold(item('b', 2), 8_000);
    await engine.formAnswered('a');
    await engine.formAnswered('b');
    await engine.sync();
    expect(sent).toEqual([]);
    expect(await store.outbox(SCOPE)).toEqual([]);
    expect(engine.snapshot().items).toEqual([]);
  });

  it('stays on the phone when the app is killed during the wait, and the next start sends it once', async () => {
    const first = setup(async (i) => ok(i));
    await first.engine.hold(item('a', 1), 8_000);
    // The app is killed: no answer, no release. A new start on the same phone, its own engine on the same store.
    let now = first.now();
    const woken: number[] = [];
    const sent: string[] = [];
    const next = new SyncEngine({
      ...first.deps,
      send: async (i) => {
        sent.push(`${i.id}:${i.key}`);
        return ok(i);
      },
      now: () => now,
      later: (_fn, ms) => {
        woken.push(ms);
        return () => {};
      },
    });
    await next.load();
    await next.sync();
    // Started within the wait: counted as waiting at once (no form holds it now, so sign-out asks first), not sent yet, and
    // woken when the form's time is over.
    expect(waiting(next.snapshot().items, next.snapshot().formHeld).map((i) => i.id)).toEqual(['a']);
    expect(sent).toEqual([]);
    expect(woken.at(-1)).toBe(8_000 + FORM_SLACK_MS);
    now += 8_000 + FORM_SLACK_MS;
    await next.sync();
    await next.sync();
    expect(sent).toEqual(['a:key-a']);
    expect(next.snapshot().items[0]).toMatchObject({ id: 'a', key: 'key-a', state: 'sent' });
  });
});
