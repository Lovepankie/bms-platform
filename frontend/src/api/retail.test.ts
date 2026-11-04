import { afterEach, describe, expect, it, vi } from 'vitest';
import { RetailError, retail, type Expense } from './retail';
import { cashGroups } from '../areas/staff/retail/cash-state';

// The test runtime has no page URL and the typed client keeps the global fetch and Request it finds
// when it is created, so both are replaced before it loads (hoisted): a relative request URL
// ('/api/v1/...') is resolved against a fixed origin, and every call goes to `network`.
const network = vi.hoisted(() => {
  const Native = globalThis.Request;
  class Resolving extends Native {
    constructor(input: RequestInfo | URL, init?: RequestInit) {
      super(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init);
    }
  }
  globalThis.Request = Resolving as typeof Request;
  const fn = vi.fn<(request: Request) => Promise<Response>>();
  globalThis.fetch = ((request: Request) => fn(request)) as typeof fetch;
  return fn;
});

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

afterEach(() => network.mockReset());

describe('real retail client', () => {
  it('posts a sale with snake_case fields and the Idempotency-Key header', async () => {
    network.mockImplementation(async () => json(200, { id: 's1', total_minor: 6000, lines: [] }));
    const sale = await retail.createSale(
      { branch_id: 'b1', payment_method: 'cash', lines: [{ product_id: 'p1', qty: '1.000' }] },
      'key-0000-0001',
    );
    expect(sale.total_minor).toBe(6000);
    const request = network.mock.calls[0]?.[0] as Request;
    expect(request.method).toBe('POST');
    expect(new URL(request.url).pathname).toBe('/api/v1/retail/sales');
    expect(request.headers.get('Idempotency-Key')).toBe('key-0000-0001');
    expect(await request.json()).toEqual({ branch_id: 'b1', payment_method: 'cash', lines: [{ product_id: 'p1', qty: '1.000' }] });
  });

  it('asks for stock and reports with the real query parameters', async () => {
    network.mockImplementation(async () => json(200, { items: [], rows: [] }));
    await retail.listStock({ branchId: 'b1', query: 'led', negativeOnly: true });
    await retail.valuation({ branchId: 'b1', asOf: '2026-10-05' });
    await retail.dailyProfit({ branchId: 'b1', from: '2026-10-01', to: '2026-10-05' });
    const urls = network.mock.calls.map((c) => new URL((c[0] as Request).url));
    expect(urls[0]?.pathname).toBe('/api/v1/retail/stock');
    expect(urls[0]?.searchParams.get('branch_id')).toBe('b1');
    expect(urls[0]?.searchParams.get('negative_only')).toBe('true');
    expect(urls[1]?.searchParams.getAll('branch_id')).toEqual(['b1']);
    expect(urls[1]?.searchParams.get('as_of')).toBe('2026-10-05');
    expect(urls[2]?.pathname).toBe('/api/v1/retail/reports/profit/daily');
  });

  it('turns a stock refusal into a plain message and keeps the code', async () => {
    network.mockImplementation(async () => json(422, { status: 422, code: 'insufficient_stock', detail: 'Product 0000 has 2 in stock at this branch.' }));
    const error = await retail.createSale({ payment_method: 'cash', lines: [{ product_id: 'p1', qty: '9' }] }, 'key-0000-0002').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(RetailError);
    expect(error).toMatchObject({ status: 422, code: 'insufficient_stock' });
    expect((error as Error).message).toMatch(/not enough stock/i);
  });

  it('shows the server message for a code it does not know, and a permission refusal in words', async () => {
    network.mockImplementation(async () => json(422, { code: 'price_floor_v2', detail: 'Price 100 is under the floor of 3500.' }));
    await expect(retail.createUsage({ kind: 'used', reason: 'x', lines: [] }, 'key-0000-0003')).rejects.toThrow('Price 100 is under the floor of 3500.');
    network.mockImplementation(async () => json(403, { code: 'permission_denied', detail: 'You do not have permission to perform this action.' }));
    await expect(retail.dailyProfit({ branchId: 'b1', from: '2026-10-01', to: '2026-10-02' })).rejects.toThrow(/do not have permission/i);
  });
});

// JUSTIFICATION-A3: the review of 178bacf asked for proof that the cash book lists hold every record their totals add up.
// Issue #816: the cash book lists total their groups in the browser, so each must hold every record of the period. The endpoints
// are paged (limit and cursor); the client follows next_cursor to the last page, and a list that stopped at the first page would
// show totals that are too low.
describe('the cash book lists read every page', () => {
  const lists: [string, () => Promise<unknown[]>][] = [
    ['/api/v1/retail/expenses', () => retail.listExpenses({ from: '2026-10-01', to: '2026-10-31' })],
    ['/api/v1/retail/bankings', () => retail.listBankings({ from: '2026-10-01', to: '2026-10-31' })],
    ['/api/v1/retail/savings', () => retail.listSavings({ from: '2026-10-01', to: '2026-10-31' })],
    ['/api/v1/retail/withdrawals', () => retail.listWithdrawals({ from: '2026-10-01', to: '2026-10-31' })],
    ['/api/v1/retail/advances', () => retail.listAdvances({})],
  ];
  it.each(lists)('%s: three pages, every row, the cursor passed on', async (path, read) => {
    network.mockImplementation(async (request) => {
      const cursor = new URL(request.url).searchParams.get('cursor');
      const page = cursor === null ? 1 : Number(cursor.slice(1));
      return json(200, { items: [{ id: `r${page}a` }, { id: `r${page}b` }], ...(page < 3 ? { next_cursor: `c${page + 1}` } : {}) });
    });
    const rows = await read();
    expect(rows.map((r) => (r as { id: string }).id)).toEqual(['r1a', 'r1b', 'r2a', 'r2b', 'r3a', 'r3b']);
    const urls = network.mock.calls.map((c) => new URL((c[0] as Request).url));
    expect(urls.map((u) => u.pathname)).toEqual([path, path, path]);
    expect(urls.map((u) => u.searchParams.get('cursor'))).toEqual([null, 'c2', 'c3']);
  });

  // JUSTIFICATION-A3: the lead's note on the review of 178bacf asked for a test with more rows than a page that proves the totals.
  // The server gives at most 200 rows a page (CashbookSupport.MAX_LIMIT): 450 expenses come in three pages, and the shop and
  // month totals of the list are those of all 450.
  it('totals 450 expenses, more than a page holds, across two shops and two months', async () => {
    const all = Array.from({ length: 450 }, (_, i) => ({
      id: `e${i}`, branch_id: i % 2 === 0 ? 'b1' : 'b2', business_date: i < 300 ? '2026-10-05' : '2026-09-05', amount_minor: 100, category_id: 'c1', category_name: 'Test Fuel',
    }));
    network.mockImplementation(async (request) => {
      const url = new URL(request.url);
      const at = Number(url.searchParams.get('cursor') ?? '0');
      const limit = Math.min(Number(url.searchParams.get('limit') ?? '50'), 200);
      return json(200, { items: all.slice(at, at + limit), ...(at + limit < all.length ? { next_cursor: String(at + limit) } : {}) });
    });
    const rows = await retail.listExpenses({ from: '2026-09-01', to: '2026-10-31' });
    expect(rows).toHaveLength(450);
    expect(network).toHaveBeenCalledTimes(3);
    const figures = { amount: (r: Expense) => r.amount_minor };
    expect(cashGroups(rows, 'branch', {}, figures, (id) => id ?? '').map((g) => [g.key, g.count, g.totalMinor])).toEqual([['b1', 225, 22500], ['b2', 225, 22500]]);
    expect(cashGroups(rows, 'month', { branchId: 'b1' }, figures, () => '').map((g) => [g.key, g.totalMinor])).toEqual([['2026-10', 15000], ['2026-09', 7500]]);
  });
});
