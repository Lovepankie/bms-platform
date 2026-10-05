import { describe, expect, it } from 'vitest';
import { createMockRetail, mockMe, setMockProfitAccess } from './retail-mock';

const branch = mockMe('admin').branches?.[0]?.id ?? '';

describe('retail mock adapter', () => {
  it('withholds cost and profit from a session without retail.profit.read', async () => {
    setMockProfitAccess(false);
    const api = createMockRetail();
    const products = await api.listProducts({ branchId: branch });
    expect(JSON.stringify(products)).not.toMatch(/cost/i);
    const stock = await api.listStock({ branchId: branch });
    expect(JSON.stringify(stock)).not.toMatch(/cost/i);
    const sale = await api.createSale({ branchId: branch, paymentMethod: 'cash', lines: [{ productId: products[0]?.id ?? '', qty: '1.000' }] }, 'k1');
    expect(JSON.stringify(sale)).not.toMatch(/cost|profit/i);
    await expect(api.valuation({ branchId: branch })).rejects.toThrow();
    await expect(api.dailyProfit({ branchId: branch, from: '2026-10-01', to: '2026-10-02' })).rejects.toThrow();
  });

  it('shows cost and profit with retail.profit.read', async () => {
    setMockProfitAccess(true);
    const api = createMockRetail();
    const products = await api.listProducts({ branchId: branch });
    const sale = await api.createSale({ branchId: branch, paymentMethod: 'cash', lines: [{ productId: products[0]?.id ?? '', qty: '1.000' }] }, 'k1');
    expect(sale.profitMinor).toBeGreaterThan(0);
    expect(sale.lines[0]?.unitCostMinor).toBeGreaterThan(0);
  });

  it('replays an Idempotency-Key instead of posting twice', async () => {
    setMockProfitAccess(true);
    const api = createMockRetail();
    const [p] = await api.listProducts({ branchId: branch });
    const before = Number((await api.listStock({ branchId: branch })).find((r) => r.productId === p?.id)?.qty);
    const body = { branchId: branch, paymentMethod: 'cash' as const, lines: [{ productId: p?.id ?? '', qty: '2.000' }] };
    const a = await api.createSale(body, 'same');
    const b = await api.createSale(body, 'same');
    expect(b.id).toBe(a.id);
    const after = Number((await api.listStock({ branchId: branch })).find((r) => r.productId === p?.id)?.qty);
    expect(before - after).toBe(2);
  });

  it('updates prices on a restock and flags negative stock', async () => {
    setMockProfitAccess(true);
    const api = createMockRetail();
    const [p] = await api.listProducts({});
    await api.createPurchase({ purchasedOn: '2026-10-05', paymentMethod: 'cash', lines: [{ productId: p?.id ?? '', costMinor: 1, sellMinor: 2, qtyByBranch: [{ branchId: branch, qty: '1.000' }] }] }, 'r1');
    expect((await api.listProducts({})).find((x) => x.id === p?.id)).toMatchObject({ costMinor: 1, sellMinor: 2 });
    expect((await api.listStock({ branchId: branch, negativeOnly: true })).every((r) => r.negative)).toBe(true);
  });

  it('works out a stock-take variance and commits it once', async () => {
    const api = createMockRetail();
    const [row] = await api.listStock({ branchId: branch });
    const draft = await api.createStocktake({ branchId: branch, lines: [{ productId: row?.productId ?? '', countedQty: '10.000' }] });
    expect(draft.lines[0]?.varianceQty).toBe((10 - Number(row?.qty)).toFixed(3));
    await api.commitStocktake(draft.id);
    await api.commitStocktake(draft.id);
    expect((await api.listStock({ branchId: branch })).find((r) => r.productId === row?.productId)?.qty).toBe('10.000');
  });
});
