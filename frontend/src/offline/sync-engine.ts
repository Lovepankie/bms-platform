import { FORM_SLACK_MS, applyOutcome, formMayHold, classify, classifyEntry, dueAt, expired, inOrder, markDiscarded, nextToSend, requeue, type OutboxItem, type SendOutcome } from './outbox';
import type { OfflineStore } from './store';
import { ITEMS_SKIPPED_CODE, itemsSkipped } from './entries';

export { FORM_SLACK_MS };

// Sends the outbox (issue #458, ADR-067 decisions 4, 5 and 8): one sale at a time, oldest first, never two requests in
// flight; a failure of the network or the server backs off and keeps the order; an expired session is refreshed once and
// otherwise stops the engine until the person signs in; a refusal is kept with its words and reported, so a manager can
// have it sent again or discard it. Each send carries the item's Idempotency-Key and client sale id, so a send repeated
// after a lost answer, a reload or a crash is the same sale on the server. Since issue #812 (ADR-067 decision 13) the same
// queue carries the other daily entries (expenses, savings, banking, withdrawals, Buy stock, Move stock, usage), in the one
// order by when each was made; an entry is sent again under its own Idempotency-Key, and a refused entry is never reported
// to a manager: its maker fixes it in its form or discards it. Since issue #852 nothing is sent under another person's
// sign-in (a shared phone), and an item the server says another person's key holds is held for its maker, unchanged.

/** A server answer: the HTTP status and the parsed body. A thrown error means the request never got an answer. */
export interface Answer {
  status: number;
  body: unknown;
}

/** A reported refusal as the server holds it. */
export interface Decision {
  id: string;
  client_sale_id: string;
  status: 'open' | 'retry' | 'discarded' | 'recorded';
}

export interface SyncDeps {
  store: OfflineStore;
  scope: string;
  send(item: OutboxItem): Promise<Answer>;
  /**
   * The user id a request would go out under now (the access token's person), or null when unknown (issue #852). Another
   * person's: nothing of this outbox is sent. Its own person's: what was held for them goes again.
   */
  signedInAs?(): string | null;
  refreshSession(): Promise<boolean>;
  /** The last refresh got no answer at all (no network): a try to do again later, not a sign-in to ask for. */
  refreshUnreachable?(): boolean;
  report(item: OutboxItem): Promise<Decision>;
  decisions(clientSaleIds: string[]): Promise<Decision[]>;
  now(): number;
  /** Runs `fn` after `ms`; returns a cancel. */
  later(fn: () => void, ms: number): () => void;
  /**
   * Runs `fn` only while no other tab of this app syncs the same outbox, or resolves false without running it. Absent in
   * tests: there is one engine.
   */
  exclusive?(fn: () => Promise<void>): Promise<boolean>;
}

/**
 * The one-tab guard of the browser (ADR-067 decision 4): a Web Lock named for the outbox, taken only if free; where Web Locks
 * are missing, a BroadcastChannel on which the tab that syncs says so, and the others wait for its word that it is done.
 */
export function browserTabLock(name: string): (fn: () => Promise<void>) => Promise<boolean> {
  const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
  if (locks && typeof locks.request === 'function') {
    return (fn) =>
      locks
        .request(name, { ifAvailable: true }, async (lock) => {
          if (!lock) return false;
          await fn();
          return true;
        })
        .then((ran) => ran);
  }
  if (typeof BroadcastChannel === 'undefined') return async (fn) => (await fn(), true);
  const channel = new BroadcastChannel(name);
  let busyUntil = 0;
  channel.onmessage = (e: MessageEvent<{ busy: boolean; until?: number }>) => {
    busyUntil = e.data.busy ? (e.data.until ?? 0) : 0;
  };
  return async (fn) => {
    if (Date.now() < busyUntil) return false;
    // A tab that stops without saying so frees the outbox after this long.
    const say = () => channel.postMessage({ busy: true, until: Date.now() + 30_000 });
    say();
    const id = setInterval(say, 10_000);
    try {
      await fn();
    } finally {
      clearInterval(id);
      channel.postMessage({ busy: false });
    }
    return true;
  };
}

export interface SyncSnapshot {
  items: OutboxItem[];
  syncing: boolean;
  /** The session expired and could not be refreshed: nothing is sent until the person signs in again. */
  needsSignIn: boolean;
  /** The items a form of this tab is still waiting on (issue #844): never sent from here, and not counted as waiting. */
  formHeld?: string[];
}

export class SyncEngine {
  private items: OutboxItem[] = [];
  private syncing = false;
  private needsSignIn = false;
  private running: Promise<void> | null = null;
  private again = false;
  /** Sync now was asked: the next run makes what is backing off due first, after it read the outbox again. */
  private dueNow = false;
  private cancelTimer: (() => void) | null = null;
  /** Another person is signed in (issue #852): nothing is sent until a new access token arrives. */
  private elsewhere = false;
  /** The held items went back in the queue for this sign-in of their maker; again only after another person was seen. */
  private released = false;
  private readonly listeners = new Set<() => void>();
  private snap: SyncSnapshot = { items: [], syncing: false, needsSignIn: false, formHeld: [] };
  /** The items a form of this tab wrote at save and still waits on (issue #844), known only in memory, so never stale. */
  private readonly formHeld = new Set<string>();

  constructor(private readonly deps: SyncDeps) {}

  snapshot = (): SyncSnapshot => this.snap;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private emit(): void {
    this.snap = { items: inOrder(this.items), syncing: this.syncing, needsSignIn: this.needsSignIn, formHeld: [...this.formHeld] };
    this.listeners.forEach((l) => l());
  }

  /** Reads the person's outbox from the device and drops sent items past their day. */
  async load(): Promise<void> {
    const now = this.deps.now();
    const all = await this.deps.store.outbox(this.deps.scope);
    for (const old of all.filter((i) => expired(i, now))) await this.deps.store.deleteItem(old.id);
    this.items = all.filter((i) => !expired(i, now));
    // Issue #844: a form's hold that has passed, or is further ahead than any form holds (the clock moved back), is stale
    // and goes. One still within its time stays: another tab's form may be waiting on it.
    for (const stale of this.items.filter((i) => i.formUntil !== undefined && !this.formHeld.has(i.id) && !formMayHold(i, now))) {
      const cleared = { ...stale, formUntil: undefined };
      await this.deps.store.putItem(cleared);
      this.items = this.items.map((i) => (i.id === stale.id ? cleared : i));
    }
    this.emit();
  }

  private async save(item: OutboxItem): Promise<void> {
    await this.deps.store.putItem(item);
    this.items = [...this.items.filter((i) => i.id !== item.id), item];
    this.emit();
  }

  private async drop(id: string): Promise<void> {
    await this.deps.store.deleteItem(id);
    this.items = this.items.filter((i) => i.id !== id);
    this.emit();
  }

  /**
   * Puts a sale in the outbox; it is written to the device before this resolves. It is added, never written over: a sale
   * already queued under its client sale id (a second tap, another tab) stays as it is.
   */
  async queue(item: OutboxItem): Promise<boolean> {
    if (item.scope !== this.deps.scope) throw new Error('an outbox item belongs to its own person');
    try {
      await this.deps.store.addItem(item);
    } catch (e) {
      if (!(e instanceof DOMException && e.name === 'ConstraintError')) throw e;
      await this.load();
      return false;
    }
    this.items = [...this.items.filter((i) => i.id !== item.id), item];
    this.emit();
    return true;
  }

  /**
   * Issue #844: the form keeps its item on the phone at save, before its own request goes, marked in flight from the form
   * for `waitMs` and a little more. True when it was added (false: one under its id was there already, and stays).
   */
  async hold(item: OutboxItem, waitMs: number): Promise<boolean> {
    // Held in memory first, so the item never shows as waiting, even for the moment between its write and this.
    const before = this.formHeld.has(item.id);
    this.formHeld.add(item.id);
    let added = false;
    try {
      added = await this.queue({ ...item, formUntil: this.deps.now() + waitMs + FORM_SLACK_MS });
      return added;
    } finally {
      if (!added && !before) {
        this.formHeld.delete(item.id);
        this.emit();
      }
    }
  }

  /**
   * The server answered the form (a 2xx, or a refusal the form shows): its item is taken off the phone, but only while no
   * engine has tried it. One another tab sent meanwhile (the form's tab frozen past its hold) is left to the engines, which
   * hold its answer: taking it off then could lose a sale sent or a refusal kept.
   */
  async formAnswered(id: string): Promise<void> {
    this.formHeld.delete(id);
    const now = (await this.deps.store.outbox(this.deps.scope)).find((i) => i.id === id);
    if (now && now.state === 'queued' && now.attempts === 0) return this.drop(id);
    this.items = [...this.items.filter((i) => i.id !== id), ...(now ? [now] : [])];
    this.emit();
  }

  /** The form got no answer: its item is left to the engine, as one kept on the phone, under the same key. */
  async formUnanswered(id: string): Promise<void> {
    this.formHeld.delete(id);
    // Read from the device, as another tab may have written it since.
    const item = (await this.deps.store.outbox(this.deps.scope)).find((i) => i.id === id);
    if (item?.formUntil !== undefined) await this.save({ ...item, formUntil: undefined });
    else this.emit();
  }

  /** A new access token arrived (a sign-in or a refresh): sending may go on, at once, and a held item may be its maker's to send. */
  resume(): void {
    if (!this.needsSignIn && !this.elsewhere && !this.items.some((i) => i.state === 'held')) return;
    this.needsSignIn = false;
    this.elsewhere = false;
    this.emit();
    void this.sync();
  }

  /** Sends what is due, one at a time; a call while a run is going asks for one more pass instead of a second run. */
  sync(): Promise<void> {
    if (this.running) {
      this.again = true;
      return this.running;
    }
    const exclusive = this.deps.exclusive;
    const go = exclusive
      ? exclusive(async () => {
          // Another tab may have sent or queued since this one last read the outbox.
          await this.load();
          await this.run();
        }).then(async (ran) => {
          if (!ran) await this.load();
        })
      : this.run();
    this.running = go.finally(() => {
      this.running = null;
      this.syncing = false;
      this.emit();
      this.plan();
    });
    return this.running;
  }

  /**
   * Sync now, or the device came back online: what was backing off is due at once (the order is kept), then a run.
   * Failed items stay failed: they go again only when sent again on purpose.
   */
  async syncNow(): Promise<void> {
    this.dueNow = true;
    await this.sync();
  }

  /** What was backing off is due now. Runs inside the run, after the outbox was read again, so another tab's writes stand. */
  private async makeDue(): Promise<void> {
    const now = this.deps.now();
    for (const item of this.items.filter((i) => (i.state === 'queued' || i.state === 'sending') && i.nextAttemptAt > now)) {
      await this.save({ ...item, nextAttemptAt: now });
    }
  }

  /** A failed item sent again now, by its seller or because a manager asked. */
  async sendAgain(id: string): Promise<void> {
    const item = this.items.find((i) => i.id === id && i.state === 'failed');
    if (!item) return;
    await this.save(requeue(item, this.deps.now()));
    await this.sync();
  }

  /**
   * A refused entry (not a sale) taken off the phone by the person who made it (issue #812): to fix it in its form, or to
   * discard it. No manager decides an entry: the server never heard of it. Returns the item, or undefined when it is not a
   * refused entry.
   */
  async takeBack(id: string): Promise<OutboxItem | undefined> {
    const item = this.items.find((i) => i.id === id && i.entry && i.state === 'failed');
    if (!item) return undefined;
    await this.drop(item.id);
    return item;
  }

  private async run(): Promise<void> {
    do {
      this.again = false;
      if (this.dueNow) {
        this.dueNow = false;
        await this.makeDue();
      }
      await this.followDecisions();
      for (;;) {
        if (this.needsSignIn || this.elsewhere) return;
        await this.releaseHeld();
        // An item a form of this tab still waits on never goes from here, however late the form's answer is (issue #844).
        const item = nextToSend(this.items.filter((i) => !this.formHeld.has(i.id)), this.deps.now());
        if (!item) break;
        // Read again just before it goes: a form (of this tab or another) may hold it or have taken it off meanwhile.
        const fresh = (await this.deps.store.outbox(this.deps.scope)).find((i) => i.id === item.id);
        if (!fresh || this.formHeld.has(item.id) || formMayHold(fresh, this.deps.now()) || (fresh.state !== 'queued' && fresh.state !== 'sending')) {
          this.items = [...this.items.filter((i) => i.id !== item.id), ...(fresh ? [fresh] : [])];
          this.emit();
          continue;
        }
        this.syncing = true;
        this.emit();
        const outcome = await this.attempt(item);
        if (outcome === null) {
          // Another person signed in (issue #852): the item is put back as it was, and nothing more goes now.
          await this.save(item);
          return;
        }
        if (outcome.kind === 'auth') {
          this.needsSignIn = true;
          await this.save(applyOutcome(item, outcome, this.deps.now()));
          return;
        }
        const next = applyOutcome(item, outcome, this.deps.now());
        await this.save(next);
        // A refused sale is reported for a manager; a refused entry stays with its maker, who fixes or discards it.
        if (next.state === 'failed' && !next.entry) await this.reportFailure(next);
        // A failed item never holds up the ones behind it; one still backing off keeps the order.
        if (next.state === 'queued') break;
      }
    } while (this.again);
  }

  /**
   * One send, marked as on its way first, with one refresh of an expired session. A refresh that got no answer (no network)
   * is a try to do again later, not a sign-in to ask for. Null, and nothing sent, while another person is signed in, before
   * the send and after a refresh, which may bring another person's session (issue #852).
   */
  private async attempt(item: OutboxItem): Promise<SendOutcome | null> {
    if (this.otherPerson()) return null;
    await this.save({ ...item, state: 'sending' });
    let outcome = await this.once(item);
    if (outcome.kind === 'auth') {
      if (await this.deps.refreshSession()) {
        if (this.otherPerson()) return null;
        outcome = await this.once(item);
      } else if (this.deps.refreshUnreachable?.()) outcome = { kind: 'retry', message: 'No connection to the server.' };
    }
    return outcome;
  }

  // JUSTIFICATION-A3: issue #852 asks for this guard; nothing in the engine knew whose sign-in a request goes out under.
  /** The request would go out under another person's sign-in (issue #852); the engine then stops until a new token. */
  private otherPerson(): boolean {
    const who = this.deps.signedInAs?.() ?? null;
    if (who === null || who === this.person()) return false;
    this.elsewhere = true;
    this.released = false;
    return true;
  }

  /** Once the maker is signed in, what was held for them goes back in the queue, unchanged: the same id and key. */
  private async releaseHeld(): Promise<void> {
    if (this.released || this.deps.signedInAs?.() !== this.person()) return;
    this.released = true;
    const now = this.deps.now();
    for (const item of this.items.filter((i) => i.state === 'held')) await this.save({ ...item, state: 'queued', nextAttemptAt: now, error: undefined });
  }

  /** The person of this outbox: the user id in its scope. */
  private person(): string {
    return this.deps.scope.slice(this.deps.scope.indexOf('|') + 1);
  }

  private async once(item: OutboxItem): Promise<SendOutcome> {
    try {
      const answer = await this.deps.send(item);
      const outcome = item.entry ? classifyEntry(answer.status, answer.body) : classify(answer.status, answer.body);
      // Issue #870: a list of items whose rows were skipped comes back with Fix, its stock never dropped quietly.
      const skipped = item.entry?.kind === 'items' && outcome.kind === 'sent' ? itemsSkipped(item.entry, answer.body) : null;
      return skipped ? { kind: 'refused', code: ITEMS_SKIPPED_CODE, message: skipped.message, fix: skipped.fix } : outcome;
    } catch {
      return { kind: 'retry', message: 'No connection to the server.' };
    }
  }

  private async reportFailure(item: OutboxItem): Promise<void> {
    try {
      const d = await this.deps.report(item);
      await this.save({ ...item, failureId: d.id, decision: d.status });
    } catch {
      // Reported on a later pass (followDecisions); the item stays failed on the device either way.
    }
  }

  /**
   * Reports failed items not yet reported, then reads what managers decided: a retry sends the item again, a discard
   * marks it discarded (shown for a day, never sent again), and a sale recorded meanwhile removes it.
   */
  private async followDecisions(): Promise<void> {
    const failed = this.items.filter((i) => i.state === 'failed' && !i.entry);
    for (const item of failed.filter((i) => !i.failureId)) await this.reportFailure(item);
    const reported = this.items.filter((i) => i.state === 'failed' && !i.entry && i.failureId);
    if (reported.length === 0) return;
    let decided: Decision[];
    try {
      decided = await this.deps.decisions(reported.map((i) => i.id));
    } catch {
      return;
    }
    for (const d of decided) {
      const item = this.items.find((i) => i.id === d.client_sale_id);
      if (!item || item.state !== 'failed') continue;
      if (d.status === 'discarded') await this.save(markDiscarded(item, this.deps.now()));
      else if (d.status === 'recorded') await this.drop(item.id);
      else if (d.status === 'retry') await this.save(requeue({ ...item, decision: 'retry' }, this.deps.now()));
      else if (d.status !== item.decision) await this.save({ ...item, decision: d.status });
    }
  }

  /** Wakes the engine when the head of the queue is due again. */
  private plan(): void {
    this.cancelTimer?.();
    this.cancelTimer = null;
    if (this.needsSignIn || this.elsewhere) return;
    // An item a form holds wakes nothing: the form gives it to the engine when it is done with it.
    const head = inOrder(this.items).find((i) => (i.state === 'queued' || i.state === 'sending') && !this.formHeld.has(i.id));
    if (!head) return;
    const wait = Math.max(dueAt(head, this.deps.now()) - this.deps.now(), 0);
    this.cancelTimer = this.deps.later(() => void this.sync(), wait);
  }

  /** Stops the timer (the person signed out or the page is going). */
  stop(): void {
    this.cancelTimer?.();
    this.cancelTimer = null;
  }
}
