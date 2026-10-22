import { describe, expect, it } from 'vitest';
import { provisionalNumber, type OutboxItem } from './outbox';
import { indexedDbStore } from './store';

// The IndexedDB store (issue #458, ADR-067 decision 3): a write is saved only when its transaction completes. A fake
// IndexedDB whose put request succeeds and whose transaction then aborts (a full disk) must reject, never resolve.
// Fabricated ids and amounts.

type Ending = 'complete' | 'abort' | 'error';

/** The smallest IndexedDB the store uses: open, a transaction, a store with put, get and getAll. */
function fakeFactory(ending: () => Ending): IDBFactory {
  const rows = new Map<string, unknown>();
  const db = {
    objectStoreNames: { contains: () => true },
    transaction: () => {
      const t: Record<string, unknown> & { error: DOMException | null } = { error: null };
      const finish = (req: Record<string, unknown>) =>
        queueMicrotask(() => {
          (req.onsuccess as (() => void) | undefined)?.();
          queueMicrotask(() => {
            const how = ending();
            if (how !== 'complete') t.error = new DOMException('The disk is full.', 'QuotaExceededError');
            const handler = how === 'complete' ? t.oncomplete : how === 'abort' ? t.onabort : t.onerror;
            (handler as (() => void) | undefined)?.();
          });
        });
      const request = (result: unknown) => {
        const req: Record<string, unknown> = { result, error: null };
        finish(req);
        return req;
      };
      t.objectStore = () => ({
        put: (value: { id: string }) => {
          rows.set(value.id, value);
          return request(value.id);
        },
        get: (key: string) => request(rows.get(key)),
        delete: (key: string) => request(rows.delete(key)),
        getAll: () => request([...rows.values()]),
        index: () => ({ getAll: () => request([...rows.values()]) }),
      });
      return t;
    },
  };
  return {
    open: () => {
      const req: Record<string, unknown> = { result: db };
      queueMicrotask(() => (req.onsuccess as () => void)());
      return req;
    },
  } as unknown as IDBFactory;
}

const item: OutboxItem = {
  id: 'a',
  scope: 'test.example|u1',
  key: 'key-a',
  request: { payment_method: 'cash', lines: [] },
  occurredAt: '2026-10-22T08:00:00.000Z',
  branchId: 'b1',
  branchName: 'Test Branch A',
  method: 'cash',
  totalMinor: 1000,
  lines: [],
  provisionalNo: provisionalNumber('a0000000'),
  state: 'queued',
  attempts: 0,
  nextAttemptAt: 0,
};

describe('the IndexedDB store', () => {
  it('resolves a write once its transaction completes', async () => {
    const store = indexedDbStore(fakeFactory(() => 'complete'));
    await expect(store.putItem(item)).resolves.toBeUndefined();
  });

  it('rejects a write whose request succeeded but whose transaction then aborted', async () => {
    const store = indexedDbStore(fakeFactory(() => 'abort'));
    await expect(store.putItem(item)).rejects.toThrow('The disk is full.');
  });

  it('rejects a write whose transaction failed with an error', async () => {
    const store = indexedDbStore(fakeFactory(() => 'error'));
    await expect(store.putCatalogue('test.example|u1', { pricesAsOf: 'x', products: {}, customers: [] })).rejects.toThrow('The disk is full.');
  });
});
