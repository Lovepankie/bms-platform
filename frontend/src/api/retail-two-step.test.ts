import { afterEach, describe, expect, it } from 'vitest';
import { createMockRetail, mockMe, setMockProfitAccess, setMockTransferReceipt } from './retail-mock';

// The two-step transfer through the adapter the screens use (ADR-033, issue #193): the mock answers
// in the server's shapes and refusals, so these are also the contract the screens rely on.

const [from, to] = mockMe('admin').branches ?? [];
const A = from?.id ?? '';
const B = to?.id ?? '';

afterEach(() => {
  setMockTransferReceipt(false);
  setMockProfitAccess(true);
});

async function setup() {
  setMockTransferReceipt(true);
  setMockProfitAccess(true);
  const api = createMockRetail();
  const [p] = await api.listProducts({ branchId: A });
  const qty = async (branch: string) => Number((await api.listStock({ branchId: branch })).find((r) => r.product_id === p?.id)?.qty);
  return { api, product: p?.id ?? '', qty };
}

describe('two-step transfer (transfer_receipt on)', () => {
  it('dispatches: stock leaves the source, the destination has nothing until it is received', async () => {
    const { api, product, qty } = await setup();
    const a = await qty(A);
    const b = await qty(B);
    const t = await api.createTransfer({ from_branch_id: A, to_branch_id: B, lines: [{ product_id: product, qty: '2' }] }, 'k-dispatch-1');
    expect(t.status).toBe('in_transit');
    expect(await qty(A)).toBe(a - 2);
    expect(await qty(B)).toBe(b);
    const flight = await api.transfersInFlight();
    expect(flight.items).toHaveLength(1);
    expect(flight.items?.[0]?.id).toBe(t.id);
    expect(flight.in_transit_value_minor).toBe(t.cost_total_minor);
    expect(flight.reconciled).toBe(true);
    expect((await api.listTransfers({ status: 'in_transit' })).items).toHaveLength(1);
  });

  it('receives everything, or less with the shortage valued at cost, and refuses more than was sent', async () => {
    const { api, product, qty } = await setup();
    const b = await qty(B);
    const t = await api.createTransfer({ from_branch_id: A, to_branch_id: B, lines: [{ product_id: product, qty: '4' }] }, 'k-dispatch-2');
    await expect(api.receiveTransfer(t.id ?? '', [{ product_id: product, qty: '4.001' }], 'k-recv-over')).rejects.toMatchObject({ status: 422, code: 'received_exceeds_dispatched' });
    const done = await api.receiveTransfer(t.id ?? '', [{ product_id: product, qty: '3' }], 'k-recv-1');
    expect(done.status).toBe('received');
    expect(done.lines?.[0]?.received_qty).toBe('3.000');
    expect(done.shortage_cost_minor).toBe(done.lines?.[0]?.unit_cost_minor);
    expect(await qty(B)).toBe(b + 3);
    expect((await api.transfersInFlight()).items).toHaveLength(0);
    await expect(api.receiveTransfer(t.id ?? '', undefined, 'k-recv-2')).rejects.toMatchObject({ status: 409, code: 'transfer_not_in_transit' });
  });

  it('receives in full when no lines are named, and a replayed key receives once', async () => {
    const { api, product, qty } = await setup();
    const b = await qty(B);
    const t = await api.createTransfer({ from_branch_id: A, to_branch_id: B, lines: [{ product_id: product, qty: '2' }] }, 'k-dispatch-3');
    const first = await api.receiveTransfer(t.id ?? '', undefined, 'k-recv-same');
    const again = await api.receiveTransfer(t.id ?? '', undefined, 'k-recv-same');
    expect(again.id).toBe(first.id);
    expect(await qty(B)).toBe(b + 2);
  });

  it('takes a dispatch back: the source is whole again, and a void is refused for it', async () => {
    const { api, product, qty } = await setup();
    const a = await qty(A);
    const t = await api.createTransfer({ from_branch_id: A, to_branch_id: B, lines: [{ product_id: product, qty: '2' }] }, 'k-dispatch-4');
    await expect(api.voidTransfer(t.id ?? '', 'Test')).rejects.toMatchObject({ status: 409, code: 'transfer_in_transit' });
    const taken = await api.cancelTransfer(t.id ?? '', 'Test sent by mistake', 'k-cancel-1');
    expect(taken.status).toBe('cancelled');
    expect(await qty(A)).toBe(a);
    await expect(api.cancelTransfer(t.id ?? '', 'Again', 'k-cancel-2')).rejects.toMatchObject({ code: 'transfer_not_in_transit' });
    await expect(api.voidTransfer(t.id ?? '', 'Test')).rejects.toMatchObject({ status: 409, code: 'transfer_cancelled' });
  });

  it('voids a received transfer while the destination still holds it, returning what was sent to the source', async () => {
    const { api, product, qty } = await setup();
    const a = await qty(A);
    const b = await qty(B);
    const t = await api.createTransfer({ from_branch_id: A, to_branch_id: B, lines: [{ product_id: product, qty: '4' }] }, 'k-dispatch-5');
    await api.receiveTransfer(t.id ?? '', [{ product_id: product, qty: '3' }], 'k-recv-5');
    const voided = await api.voidTransfer(t.id ?? '', 'Test miscount');
    expect(voided.status).toBe('voided');
    expect(await qty(A)).toBe(a);
    expect(await qty(B)).toBe(b);
  });

  it('shows no cost to a session without retail.profit.read', async () => {
    const { api, product } = await setup();
    const t = await api.createTransfer({ from_branch_id: A, to_branch_id: B, lines: [{ product_id: product, qty: '1' }] }, 'k-dispatch-6');
    setMockProfitAccess(false);
    expect(JSON.stringify(await api.transfersInFlight())).not.toMatch(/cost|value|clearing/i);
    expect(JSON.stringify(await api.stockByLocation({}))).not.toMatch(/cost|value/i);
    expect(JSON.stringify(await api.receiveTransfer(t.id ?? '', [{ product_id: product, qty: '0' }], 'k-recv-6'))).not.toMatch(/cost/i);
  });
});

describe('transfers with the capability off', () => {
  it('stay one step: completed at once, both branches moved', async () => {
    setMockTransferReceipt(false);
    const api = createMockRetail();
    const [p] = await api.listProducts({ branchId: A });
    const before = Number((await api.listStock({ branchId: B })).find((r) => r.product_id === p?.id)?.qty);
    const t = await api.createTransfer({ from_branch_id: A, to_branch_id: B, lines: [{ product_id: p?.id ?? '', qty: '1' }] }, 'k-one-step');
    expect(t.status).toBe('completed');
    expect(Number((await api.listStock({ branchId: B })).find((r) => r.product_id === p?.id)?.qty)).toBe(before + 1);
    await expect(api.receiveTransfer(t.id ?? '', undefined, 'k-recv-off')).rejects.toMatchObject({ code: 'transfer_not_in_transit' });
  });
});

describe('stock by location', () => {
  it('puts stores before shops, subtotals each role, and counts stock on the way once', async () => {
    const { api, product } = await setup();
    await api.setBranchRole(A, 'shop');
    await api.setBranchRole(B, 'store');
    const t = await api.createTransfer({ from_branch_id: A, to_branch_id: B, lines: [{ product_id: product, qty: '2' }] }, 'k-dispatch-7');
    const report = await api.stockByLocation({});
    expect(report.branches?.map((b) => b.role)).toEqual(['store', 'shop']);
    expect(report.branches?.[0]?.id).toBe(B);
    const row = report.items?.find((r) => r.product_id === product);
    expect(row?.subtotals?.map((s) => s.role)).toEqual(['store', 'shop']);
    const held = (row?.balances ?? []).reduce((sum, c) => sum + Number(c.qty), 0);
    expect(Number(row?.in_transit_qty)).toBe(2);
    expect(Number(row?.combined_qty)).toBe(held + 2);
    expect(report.totals?.combined_value_minor).toBe((report.totals?.value_minor ?? 0) + (t.cost_total_minor ?? 0));
  });
});
