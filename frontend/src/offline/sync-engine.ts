import { applyOutcome, classify, expired, inOrder, markDiscarded, nextToSend, requeue, type OutboxItem, type SendOutcome } from './outbox';
import type { OfflineStore } from './store';

// Sends the outbox (issue #458, ADR-067 decisions 4, 5 and 8): one sale at a time, oldest first, never two requests in
// flight; a failure of the network or the server backs off and keeps the order; an expired session is refreshed once and
// otherwise stops the engine until the person signs in; a refusal is kept with its words and reported, so a manager can
// have it sent again or discard it. Each send carries the item's Idempotency-Key and client sale id, so a send repeated
// after a lost answer, a reload or a crash is the same sale on the server.

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
}

export class SyncEngine {
  private items: OutboxItem[] = [];
  private syncing = false;
  private needsSignIn = false;
  private running: Promise<void> | null = null;
  private again = false;
  private cancelTimer: (() => void) | null = null;
  private readonly listeners = new Set<() => void>();
  private snap: SyncSnapshot = { items: [], syncing: false, needsSignIn: false };

  constructor(private readonly deps: SyncDeps) {}

  snapshot = (): SyncSnapshot => this.snap;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private emit(): void {
    this.snap = { items: inOrder(this.items), syncing: this.syncing, needsSignIn: this.needsSignIn };
    this.listeners.forEach((l) => l());
  }

  /** Reads the person's outbox from the device and drops sent items past their day. */
  async load(): Promise<void> {
    const now = this.deps.now();
    const all = await this.deps.store.outbox(this.deps.scope);
    for (const old of all.filter((i) => expired(i, now))) await this.deps.store.deleteItem(old.id);
    this.items = all.filter((i) => !expired(i, now));
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
  async queue(item: OutboxItem): Promise<void> {
    if (item.scope !== this.deps.scope) throw new Error('an outbox item belongs to its own person');
    try {
      await this.deps.store.addItem(item);
    } catch (e) {
      if (!(e instanceof DOMException && e.name === 'ConstraintError')) throw e;
      await this.load();
      return;
    }
    this.items = [...this.items.filter((i) => i.id !== item.id), item];
    this.emit();
  }

  /** A new access token arrived (a sign-in or a refresh): sending may go on, at once. */
  resume(): void {
    if (!this.needsSignIn) return;
    this.needsSignIn = false;
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
    const now = this.deps.now();
    for (const item of this.items.filter((i) => (i.state === 'queued' || i.state === 'sending') && i.nextAttemptAt > now)) {
      await this.save({ ...item, nextAttemptAt: now });
    }
    await this.sync();
  }

  /** A failed item sent again now, by its seller or because a manager asked. */
  async sendAgain(id: string): Promise<void> {
    const item = this.items.find((i) => i.id === id && i.state === 'failed');
    if (!item) return;
    await this.save(requeue(item, this.deps.now()));
    await this.sync();
  }

  private async run(): Promise<void> {
    do {
      this.again = false;
      await this.followDecisions();
      for (;;) {
        if (this.needsSignIn) return;
        const item = nextToSend(this.items, this.deps.now());
        if (!item) break;
        this.syncing = true;
        this.emit();
        const outcome = await this.attempt(item);
        if (outcome.kind === 'auth') {
          this.needsSignIn = true;
          await this.save(applyOutcome(item, outcome, this.deps.now()));
          return;
        }
        const next = applyOutcome(item, outcome, this.deps.now());
        await this.save(next);
        if (next.state === 'failed') await this.reportFailure(next);
        // A failed item never holds up the ones behind it; one still backing off keeps the order.
        if (next.state === 'queued') break;
      }
    } while (this.again);
  }

  /**
   * One send, marked as on its way first, with one refresh of an expired session. A refresh that got no answer (no network)
   * is a try to do again later, not a sign-in to ask for.
   */
  private async attempt(item: OutboxItem): Promise<SendOutcome> {
    await this.save({ ...item, state: 'sending' });
    let outcome = await this.once(item);
    if (outcome.kind === 'auth') {
      if (await this.deps.refreshSession()) outcome = await this.once(item);
      else if (this.deps.refreshUnreachable?.()) outcome = { kind: 'retry', message: 'No connection to the server.' };
    }
    return outcome;
  }

  private async once(item: OutboxItem): Promise<SendOutcome> {
    try {
      const answer = await this.deps.send(item);
      return classify(answer.status, answer.body);
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
    const failed = this.items.filter((i) => i.state === 'failed');
    for (const item of failed.filter((i) => !i.failureId)) await this.reportFailure(item);
    const reported = this.items.filter((i) => i.state === 'failed' && i.failureId);
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
    if (this.needsSignIn) return;
    const head = inOrder(this.items).find((i) => i.state === 'queued' || i.state === 'sending');
    if (!head) return;
    const wait = Math.max(head.nextAttemptAt - this.deps.now(), 0);
    this.cancelTimer = this.deps.later(() => void this.sync(), wait);
  }

  /** Stops the timer (the person signed out or the page is going). */
  stop(): void {
    this.cancelTimer?.();
    this.cancelTimer = null;
  }
}
