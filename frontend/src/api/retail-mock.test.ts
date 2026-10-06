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

  it('moves stock between branches at cost, refuses beyond the source balance, and voids once', async () => {
    setMockProfitAccess(true);
    const api = createMockRetail();
    const to = mockMe('admin').branches?.[1]?.id ?? '';
    const [p] = await api.listProducts({ branchId: branch });
    const held = async (b: string) => Number((await api.listStock({ branchId: b })).find((r) => r.product_id === p?.id)?.qty);
    const [a0, b0] = [await held(branch), await held(to)];
    const body = { from_branch_id: branch, to_branch_id: to, lines: [{ product_id: p?.id ?? '', qty: '2.000' }] };
    const t = await api.createTransfer(body, 'move-key-1');
    expect(t.cost_total_minor).toBeGreaterThan(0);
    expect((await api.createTransfer(body, 'move-key-1')).id).toBe(t.id);
    expect([a0 - (await held(branch)), (await held(to)) - b0]).toEqual([2, 2]);
    await expect(api.createTransfer({ ...body, lines: [{ product_id: p?.id ?? '', qty: '9999.000' }] }, 'move-key-2'))
      .rejects.toMatchObject({ status: 422, code: 'insufficient_stock' });
    expect((await api.listTransfers({ branchId: to })).items).toHaveLength(1);
    expect((await api.voidTransfer(t.id ?? '', 'Test wrong branch')).status).toBe('voided');
    expect(await held(branch)).toBe(a0);
    await expect(api.voidTransfer(t.id ?? '', 'Test')).rejects.toMatchObject({ code: 'transfer_voided' });
  });

  it('refuses a void once the destination no longer holds the stock, and hides cost without profit read', async () => {
    setMockProfitAccess(false);
    const api = createMockRetail();
    const to = mockMe('admin').branches?.[1]?.id ?? '';
    const [p] = await api.listProducts({ branchId: branch });
    const t = await api.createTransfer({ from_branch_id: branch, to_branch_id: to, lines: [{ product_id: p?.id ?? '', qty: '1.000' }] }, 'move-key-3');
    expect(JSON.stringify(t)).not.toMatch(/cost/i);
    expect(JSON.stringify(await api.getTransfer(t.id ?? ''))).not.toMatch(/cost/i);
    const all = Number((await api.listStock({ branchId: to })).find((r) => r.product_id === p?.id)?.qty);
    await api.createSale({ branch_id: to, payment_method: 'cash', lines: [{ product_id: p?.id ?? '', qty: all.toFixed(3) }] }, 'empty-the-shelf');
    await expect(api.voidTransfer(t.id ?? '', 'Test')).rejects.toMatchObject({ status: 422, code: 'transfer_stock_moved' });
    setMockProfitAccess(true);
  });

  it('gives every item a category, filters stock by it and searches by its text', async () => {
    const api = createMockRetail();
    const [cables] = await api.listCategories();
    const stock = await api.listStock({ branchId: branch });
    expect(stock.every((r) => r.category && r.category_id)).toBe(true);
    const only = await api.listStock({ branchId: branch, categoryId: cables?.id });
    expect(only.length).toBeGreaterThan(0);
    expect(only.every((r) => r.category === cables?.name)).toBe(true);
    expect(only.length).toBeLessThan(stock.length);
    const found = await api.listProducts({ query: 'lighting', branchId: branch });
    expect(found.length).toBeGreaterThan(0);
    expect(found.every((p) => p.category === 'Lighting')).toBe(true);
  });
});
