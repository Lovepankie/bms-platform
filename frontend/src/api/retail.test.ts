import { afterEach, describe, expect, it, vi } from 'vitest';
import { RetailError, retail } from './retail';

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
