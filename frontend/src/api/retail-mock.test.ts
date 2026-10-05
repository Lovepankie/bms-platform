import { describe, expect, it } from 'vitest';
import { RetailError } from './retail';
import { createMockRetail, mockMe, setMockProfitAccess } from './retail-mock';

const branch = mockMe('admin').branches?.[0]?.id ?? '';
const cash = (productId: string, qty: string) => ({ branch_id: branch, payment_method: 'cash', lines: [{ product_id: productId, qty }] });

describe('retail mock adapter (real response shapes)', () => {
  it('withholds cost and profit from a session without retail.profit.read', async () => {
    setMockProfitAccess(false);
    const api = createMockRetail();
    const products = await api.listProducts({ branchId: branch });
    expect(JSON.stringify(products)).not.toMatch(/cost/i);
    expect(JSON.stringify(await api.listStock({ branchId: branch }))).not.toMatch(/cost/i);
    const sale = await api.createSale(cash(products[0]?.id ?? '', '1.000'), 'k1-sale-key');
    expect(JSON.stringify(sale)).not.toMatch(/cost|profit/i);
    expect(JSON.stringify(await api.valuation({ branchId: branch }))).not.toMatch(/cost/i);
    await expect(api.dailyProfit({ branchId: branch, from: '2026-10-01', to: '2026-10-02' })).rejects.toMatchObject({ status: 403, code: 'permission_denied' });
  });

  it('shows cost and profit with retail.profit.read', async () => {
    setMockProfitAccess(true);
    const api = createMockRetail();
    const products = await api.listProducts({ branchId: branch });
    const sale = await api.createSale(cash(products[0]?.id ?? '', '1.000'), 'k1-sale-key');
    expect(sale.profit_minor).toBeGreaterThan(0);
    expect(sale.cost_total_minor).toBeGreaterThan(0);
    expect(sale.lines?.[0]?.unit_cost_minor).toBeGreaterThan(0);
    expect((await api.valuation({ branchId: branch })).value_at_cost_minor).toBeGreaterThan(0);
  });

  it('replays an Idempotency-Key instead of posting twice, and refuses a reused key', async () => {
    setMockProfitAccess(true);
    const api = createMockRetail();
    const [p] = await api.listProducts({ branchId: branch });
    const qtyOf = async () => Number((await api.listStock({ branchId: branch })).find((r) => r.product_id === p?.id)?.qty);
    const before = await qtyOf();
    const body = cash(p?.id ?? '', '2.000');
    const a = await api.createSale(body, 'same-key-01');
    const b = await api.createSale(body, 'same-key-01');
    expect(b.id).toBe(a.id);
    expect(before - (await qtyOf())).toBe(2);
    await expect(api.createSale(cash(p?.id ?? '', '3.000'), 'same-key-01')).rejects.toMatchObject({ code: 'idempotency_key_reused' });
  });

  it('refuses a sale above the branch balance with the server code', async () => {
    const api = createMockRetail();
    const [p] = await api.listProducts({ branchId: branch });
    const error = await api.createSale(cash(p?.id ?? '', '9999.000'), 'big-sale-key').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(RetailError);
    expect(error).toMatchObject({ status: 422, code: 'insufficient_stock' });
  });

  it('updates prices on a restock and flags negative stock', async () => {
    setMockProfitAccess(true);
    const api = createMockRetail();
    const [p] = await api.listProducts({});
    await api.createPurchase({ purchased_on: '2026-10-05', payment_method: 'cash', lines: [{ product_id: p?.id ?? '', cost_minor: 1, sell_minor: 2, qty_by_branch: [{ branch_id: branch, qty: '1.000' }] }] }, 'restock-key-1');
    expect((await api.listProducts({})).find((x) => x.id === p?.id)).toMatchObject({ cost_minor: 1, sell_minor: 2 });
    expect((await api.listStock({ branchId: branch, negativeOnly: true })).every((r) => r.negative)).toBe(true);
  });

  it('works out a stock-take variance and commits it once', async () => {
    const api = createMockRetail();
    const [row] = await api.listStock({ branchId: branch });
    const draft = await api.createStocktake({ branch_id: branch, lines: [{ product_id: row?.product_id ?? '', counted_qty: '10.000' }] });
    expect(draft.lines?.[0]?.variance_qty).toBe((10 - Number(row?.qty)).toFixed(3));
    await api.commitStocktake(draft.id ?? '');
    await expect(api.commitStocktake(draft.id ?? '')).rejects.toMatchObject({ code: 'stocktake_committed' });
    expect((await api.listStock({ branchId: branch })).find((r) => r.product_id === row?.product_id)?.qty).toBe('10.000');
  });
});
