import { describe, expect, it, vi } from 'vitest';
import type { Me } from '../api/client';
import type { Product } from '../api/retail';
import type { Draft } from '../areas/staff/retail/sale-state';
import { mayKeepOnPhone, offlineProblem, offlineSale, refreshCatalogue, searchCatalogue, sellingBranchesOf, serverIso } from './catalogue';
import { PING_TIMEOUT_MS, browserConnectivity, createConnectivity } from './connectivity';
import { memoryStore, scopeOf } from './store';

// The catalogue copy, the offline sale request and the connection watch (issue #458, ADR-067 decisions 2, 3 and 4;
// NFR-OFF-05, NFR-OFF-07). Fabricated people, items and amounts.

const seller: Me = {
  user_id: 'u1',
  full_name: 'Test Seller 01',
  modules: ['retail'],
  permissions: ['retail.sale.create', 'retail.sale.read', 'retail.stock.read'],
  permission_scopes: { 'retail.sale.create': { all_branches: false, branch_ids: ['b1', 's1'] } },
  branches: [
    { id: 'b1', code: 'BR1', name: 'Test Branch A' },
    { id: 'b2', code: 'BR2', name: 'Test Branch B' },
    { id: 's1', code: 'ST1', name: 'Test Store', sells_goods: false },
  ],
} as Me;

const cable: Product = { id: 'p1', code: 'CBL-1', description: 'Test Cable 1.5mm', category: 'Test Wiring', unit: 'm', sell_minor: 2000, qty: '5.000' } as Product;
const bulb: Product = { id: 'p2', code: 'BLB-9', description: 'Test Bulb 9W', category: 'Test Lighting', unit: 'pc', sell_minor: 5000, qty: '0.000' } as Product;

describe('the catalogue copy', () => {
  it('is fetched for each branch the person may sell at, every page, and kept with its time', async () => {
    const products = vi.fn(async (branchId: string, cursor?: string) =>
      cursor ? { items: [bulb] } : { items: [cable], next_cursor: branchId === 'b1' ? 'c2' : undefined },
    );
    const store = memoryStore();
    const scope = scopeOf('test.example', 'u1');
    const now = new Date('2026-10-22T09:14:00Z');
    const c = await refreshCatalogue({ products, customers: async () => [{ id: 'c1', name: 'Test Buyer 01' }] }, seller, scope, store, now);
    expect(sellingBranchesOf(seller).map((b) => b.id)).toEqual(['b1']);
    expect(products.mock.calls).toEqual([['b1', undefined], ['b1', 'c2']]);
    expect(c.products.b1?.map((p) => p.code)).toEqual(['CBL-1', 'BLB-9']);
    expect(c.pricesAsOf).toBe('2026-10-22T09:14:00.000Z');
    expect(await store.catalogue(scope)).toEqual(c);
    expect((await store.profile(scope))?.user_id).toBe('u1');
  });

  it("dates the prices by the server's clock when it sends one, so a phone clock behind can be told", async () => {
    const store = memoryStore();
    const scope = scopeOf('test.example', 'u1');
    const products = async () => ({ items: [cable], serverTime: 'Thu, 22 Oct 2026 09:20:00 GMT' });
    const c = await refreshCatalogue({ products, customers: async () => [] }, seller, scope, store, new Date('2026-10-20T07:00:00Z'));
    expect(c.pricesAsOf).toBe('2026-10-22T09:20:00.000Z');
    expect(serverIso('not a date')).toBeUndefined();
  });

  it('keeps the last full copy when a refresh is cut half way', async () => {
    const store = memoryStore();
    const scope = scopeOf('test.example', 'u1');
    const first = await refreshCatalogue({ products: async () => ({ items: [cable] }), customers: async () => [] }, seller, scope, store, new Date());
    await expect(
      refreshCatalogue({ products: async () => Promise.reject(new TypeError('Failed to fetch')), customers: async () => [] }, seller, scope, store, new Date()),
    ).rejects.toThrow();
    expect(await store.catalogue(scope)).toEqual(first);
  });

  it('is searched by code, name or category, any case', () => {
    const c = { pricesAsOf: '2026-10-22T09:14:00Z', products: { b1: [cable, bulb] }, customers: [] };
    expect(searchCatalogue(c, 'b1', 'cable').map((p) => p.id)).toEqual(['p1']);
    expect(searchCatalogue(c, 'b1', 'blb').map((p) => p.id)).toEqual(['p2']);
    expect(searchCatalogue(c, 'b1', 'test lighting').map((p) => p.id)).toEqual(['p2']);
    expect(searchCatalogue(c, 'b2', 'cable')).toEqual([]);
  });

  it('per person: another person on the same phone has their own scope', async () => {
    const store = memoryStore();
    await store.putCatalogue(scopeOf('test.example', 'u1'), { pricesAsOf: 'x', products: {}, customers: [] });
    expect(await store.catalogue(scopeOf('test.example', 'u2'))).toBeNull();
    expect(await store.catalogue(scopeOf('other.example', 'u1'))).toBeNull();
  });
});

const draft = (patch: Partial<Draft> = {}): Draft => ({
  branchId: 'b1',
  method: 'cash',
  customerId: '',
  newBuyerName: '',
  newBuyerContact: '',
  dueDate: '',
  lines: [{ product: bulb, qty: '2', price: '5000' }],
  ...patch,
});

describe('an offline sale', () => {
  it('is not stopped by the stock on the phone, but is by a credit sale', () => {
    expect(offlineProblem(draft())).toBeNull();
    expect(offlineProblem(draft({ method: 'credit' }))).toMatch(/credit sale needs the internet/);
    expect(offlineProblem(draft({ lines: [] }))).toBe('Add at least one item.');
    expect(offlineProblem(draft({ lines: [{ product: bulb, qty: 'two', price: '5000' }] }))).toMatch(/quantity and price/);
  });

  it('follows the online price rules: the price on the phone, or a typed one only with the override and a reason', () => {
    const typed = (reason?: string) => draft({ lines: [{ product: bulb, qty: '1', price: '4000', typed: true, reason }] });
    expect(offlineProblem(typed(), false)).toMatch(/Use the price saved on this phone for Test Bulb 9W/);
    expect(offlineProblem(typed('Old stock, agreed'), false)).toMatch(/you may not change prices/);
    expect(offlineProblem(typed(), true)).toMatch(/Say why the price of Test Bulb 9W is changed/);
    expect(offlineProblem(typed('Old stock, agreed'), true)).toBeNull();
    expect(offlineProblem(draft(), false)).toBeNull();
  });

  it('queues every line at the price charged, with the client id, the time and the offline flag', () => {
    const now = new Date('2026-10-22T10:00:00Z');
    const item = offlineSale({
      draft: draft({ lines: [{ product: bulb, qty: '2', price: '4500', typed: true, reason: 'Old stock, agreed' }] }),
      clientSaleId: '0a1b2c3d-4e5f-4000-8000-000000000001',
      key: 'k-1',
      scope: 'test.example|u1',
      branchName: 'Test Branch A',
      pricesAsOf: '2026-10-22T09:14:00.000Z',
      now,
    });
    expect(item.request).toEqual({
      branch_id: 'b1',
      payment_method: 'cash',
      lines: [{ product_id: 'p2', qty: '2.000', unit_price_minor: 4500, price_override_reason: 'Old stock, agreed' }],
      client_sale_id: '0a1b2c3d-4e5f-4000-8000-000000000001',
      occurred_at: '2026-10-22T10:00:00.000Z',
      recorded_offline: true,
      prices_as_of: '2026-10-22T09:14:00.000Z',
    });
    expect(item).toMatchObject({ id: '0a1b2c3d-4e5f-4000-8000-000000000001', key: 'k-1', totalMinor: 9000, provisionalNo: 'OFF-0A1B2C3D', state: 'queued' });
  });
});

describe('the connection', () => {
  it('is offline at once when the browser says so, and online only once the server answers', async () => {
    let online = true;
    let serverUp = false;
    const handlers: Record<string, () => void> = {};
    const c = createConnectivity({
      ping: async () => serverUp,
      navigatorOnline: () => online,
      addListener: (type, fn) => {
        handlers[type] = fn;
        return () => delete handlers[type];
      },
      every: () => () => {},
    });
    const seen: boolean[] = [];
    c.subscribe(() => seen.push(c.online()));
    expect(await c.check()).toBe(false);
    serverUp = true;
    expect(await c.check()).toBe(true);
    online = false;
    c.start();
    handlers.offline?.();
    expect(c.online()).toBe(false);
    online = true;
    handlers.online?.();
    await vi.waitFor(() => expect(c.online()).toBe(true));
    expect(seen).toEqual([false, true, false, true]);
  });

  // JUSTIFICATION-A3: a new test the review asks for (#769), not a mechanism.
  it("treats the browser's ping cut off by its own 3 s limit as slow, so it is pinged again (issue #769)", async () => {
    const timedOut = (): Promise<Response> => Promise.reject(new DOMException('The operation timed out.', 'TimeoutError'));
    const fetch = vi.fn(timedOut).mockImplementationOnce(timedOut).mockImplementationOnce(async () => ({ ok: true }) as Response);
    vi.stubGlobal('fetch', fetch);
    try {
      // One cut off, the second answers: still online.
      expect(await browserConnectivity().check()).toBe(true);
      expect(fetch).toHaveBeenCalledTimes(2);
      // Both cut off: offline.
      expect(await browserConnectivity().check()).toBe(false);
      expect(fetch).toHaveBeenCalledTimes(4);
      // Older engines name the timeout AbortError: slow too, pinged again.
      fetch.mockImplementationOnce(() => Promise.reject(new DOMException('The operation was aborted.', 'AbortError')));
      fetch.mockImplementationOnce(async () => ({ ok: true }) as Response);
      expect(await browserConnectivity().check()).toBe(true);
      fetch.mockClear();
      // A failure that is not slowness (no network) is offline at once, with no second ping.
      fetch.mockImplementation(() => Promise.reject(new TypeError('Failed to fetch')));
      expect(await browserConnectivity().check()).toBe(false);
      expect(fetch).toHaveBeenCalledTimes(1);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  // JUSTIFICATION-A3: a new test the issue asks for (#769), not a mechanism.
  it('counts two pings in a row with no answer within 3 seconds as offline, and one as a cold link (issue #769)', async () => {
    vi.useFakeTimers();
    try {
      let answer: (ok: boolean) => void = () => {};
      const pings = vi.fn(() => new Promise<boolean>((resolve) => (answer = resolve)));
      const c = createConnectivity({
        ping: pings,
        navigatorOnline: () => true,
        addListener: () => () => {},
        every: () => () => {},
      });
      expect(c.online()).toBe(true);
      // One slow ping is pinged again at once, and an answer in time keeps the app online.
      const cold = c.check();
      await vi.advanceTimersByTimeAsync(PING_TIMEOUT_MS);
      expect(pings).toHaveBeenCalledTimes(2);
      answer(true);
      expect(await cold).toBe(true);
      expect(c.online()).toBe(true);
      // Two slow in a row: offline.
      const checked = c.check();
      await vi.advanceTimersByTimeAsync(PING_TIMEOUT_MS);
      expect(c.online()).toBe(true);
      await vi.advanceTimersByTimeAsync(PING_TIMEOUT_MS - 1);
      expect(c.online()).toBe(true);
      await vi.advanceTimersByTimeAsync(1);
      expect(await checked).toBe(false);
      expect(c.online()).toBe(false);
      // The slow answer that comes later changes nothing; the next ping that answers in time does.
      answer(true);
      await vi.advanceTimersByTimeAsync(0);
      expect(c.online()).toBe(false);
      const again = c.check();
      answer(true);
      expect(await again).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('a sale whose answer was lost', () => {
  it('is kept on the phone, except a credit sale, which is only tried again (ADR-067 decision 1)', () => {
    expect(mayKeepOnPhone(undefined)).toBe(true);
    expect(mayKeepOnPhone({ body: { payment_method: 'cash' } as never })).toBe(true);
    expect(mayKeepOnPhone({ body: { payment_method: 'credit' } as never })).toBe(false);
    const draft = { branchId: 'b1', method: 'credit', customerId: '', newBuyerName: 'Test Buyer 07', newBuyerContact: '', dueDate: '', lines: [{ product: cable, qty: '1', price: '2000' }] } as unknown as Draft;
    expect(() =>
      offlineSale({ draft, clientSaleId: 'k1', key: 'k2', scope: 's', branchName: 'Test Branch A', sent: { body: { payment_method: 'credit' } as never, at: '2026-10-22T09:00:00Z' }, now: new Date() }),
    ).toThrow(/credit sale/);
  });
});
