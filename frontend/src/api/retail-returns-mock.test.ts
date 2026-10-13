import { describe, expect, it } from 'vitest';
import { createMockRetail, mockMe } from './retail-mock';

// The mock's customer returns follow the server's rules in small (ADR-033, issue #201): off means 404, a return cannot exceed
// what was sold less what came back, the credit is the price charged, the last piece takes the remainder, an account refund
// needs a credit sale that owes (and money for the rest says how), numbers run per branch, and a void puts the balance back.
// Fabricated data.

const branch = mockMe('admin').branches?.[0]?.id ?? '';
const buyer = 'c0000000-0000-4000-8000-000000000001';

async function shop() {
  const api = createMockRetail();
  const read = await api.capabilities();
  await api.changeCapabilities({ returns: true, customer_accounts: true }, read.version);
  const [bulb] = await api.listProducts({ query: 'P003' });
  return { api, productId: bulb?.id ?? '' };
}

type Api = Awaited<ReturnType<typeof shop>>['api'];

const sell = (api: Api, productId: string, qty: string, method = 'cash') =>
  api.createSale(
    { branch_id: branch, payment_method: method, ...(method === 'credit' ? { customer_id: buyer } : {}), lines: [{ product_id: productId, qty }] } as never,
    `key-${Math.random()}`,
  );

const take = (api: Api, saleId: string, lineId: string, qty: string, refund: string, extra: Record<string, unknown> = {}) =>
  api.createSaleReturn(saleId, { lines: [{ sale_line_id: lineId, qty, reason: 'defective', disposition: 'restock' }], refund_method: refund, ...extra } as never, `key-${Math.random()}`);

describe('customer returns in the mock', () => {
  it('answers 404 capability_not_enabled while the capability is off', async () => {
    const api = createMockRetail();
    await expect(api.listReturns({})).rejects.toMatchObject({ status: 404, code: 'capability_not_enabled' });
    await expect(api.getReturn('x')).rejects.toMatchObject({ status: 404 });
  });

  it('credits the price charged, numbers per branch, and refuses more than was sold less what came back', async () => {
    const { api, productId } = await shop();
    const sale = await sell(api, productId, '10');
    const line = sale.lines?.[0]?.id ?? '';
    const first = await take(api, sale.id ?? '', line, '4', 'cash');
    expect(first.executed).toBe(true);
    expect(first.sale_return).toMatchObject({ credit_note_no: expect.stringMatching(/^CN\w+-000001$/), total_minor: 24000, refund_minor: 24000, status: 'completed' });
    await expect(take(api, sale.id ?? '', line, '7', 'cash')).rejects.toMatchObject({ code: 'return_exceeds_sold' });
    const rest = await take(api, sale.id ?? '', line, '6', 'cash');
    expect(rest.sale_return?.total_minor).toBe(36000);
    expect(rest.sale_return?.credit_note_no).toMatch(/-000002$/);
    expect(await api.listSaleReturns(sale.id ?? '')).toHaveLength(2);
    expect((await api.listReturns({ query: '000002' })).items).toHaveLength(1);
  });

  it('takes an account refund off the balance and asks how the rest is paid out when the balance is smaller', async () => {
    const { api, productId } = await shop();
    const sale = await sell(api, productId, '2', 'credit');
    const line = sale.lines?.[0]?.id ?? '';
    // Two bulbs are 12,000 and all of it is owed: one comes back and comes off the balance.
    const one = await take(api, sale.id ?? '', line, '1', 'account');
    expect(one.sale_return).toMatchObject({ account_credit_minor: 6000, refund_minor: 0 });
    expect((await api.getSale(sale.id ?? '')).balance_minor).toBe(6000);
    // The last bulb is the whole of what is left: nothing to pay out.
    const last = await take(api, sale.id ?? '', line, '1', 'account');
    expect(last.sale_return).toMatchObject({ account_credit_minor: 6000, refund_minor: 0 });
    expect((await api.getSale(sale.id ?? '')).balance_minor).toBe(0);
  });

  it('refuses an account refund for a cash sale and asks how the rest is paid out', async () => {
    const { api, productId } = await shop();
    const cash = await sell(api, productId, '2');
    await expect(take(api, cash.id ?? '', cash.lines?.[0]?.id ?? '', '1', 'account')).rejects.toMatchObject({ code: 'account_refund_unavailable' });
    const credit = await sell(api, productId, '1', 'credit');
    const made = await take(api, credit.id ?? '', credit.lines?.[0]?.id ?? '', '1', 'account');
    expect(made.sale_return).toMatchObject({ account_credit_minor: 6000, refund_minor: 0 });
    const id = made.sale_return?.id ?? '';
    const voided = await api.voidReturn(id, 'Taken back by mistake', 'void-1');
    expect(voided.sale_return?.status).toBe('voided');
    expect((await api.getSale(credit.id ?? '')).balance_minor).toBe(6000);
    await expect(api.voidReturn(id, 'Again for luck', 'void-2')).rejects.toMatchObject({ code: 'return_voided' });
  });

  it('replays the first answer for the same key', async () => {
    const { api, productId } = await shop();
    const sale = await sell(api, productId, '5');
    const line = sale.lines?.[0]?.id ?? '';
    const body = { lines: [{ sale_line_id: line, qty: '1', reason: 'other', disposition: 'restock' }], refund_method: 'cash' } as never;
    const a = await api.createSaleReturn(sale.id ?? '', body, 'same-key');
    const b = await api.createSaleReturn(sale.id ?? '', body, 'same-key');
    expect(b.sale_return?.id).toBe(a.sale_return?.id);
    expect(await api.listSaleReturns(sale.id ?? '')).toHaveLength(1);
  });
});
