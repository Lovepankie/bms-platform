import { describe, expect, it } from 'vitest';
import { businessToday, daysBefore } from './retail';
import { createMockRetail, mockMe } from './retail-mock';

// The mock's customer accounts follow the server's rules in small (ADR-033, issue #196): off means 404, a credit sale names
// a buyer from the list and stays within the limit, terms date the sale, a payment on account is allocated oldest first or as
// chosen and can be reversed, and the statement closes at what the buyer owes. Fabricated data.

const branch = mockMe('admin').branches?.[0]?.id ?? '';
const today = businessToday();
const buyer = 'c0000000-0000-4000-8000-000000000001';

async function trade() {
  const api = createMockRetail();
  const read = await api.capabilities();
  await api.changeCapabilities({ customer_accounts: true }, read.version);
  const [bulb] = await api.listProducts({ query: 'P003' });
  return { api, productId: bulb?.id ?? '' };
}

type Api = Awaited<ReturnType<typeof trade>>['api'];

/** A credit sale to the mock's buyer of `qty` bulbs at 6,000 each. */
const creditSale = (api: Api, productId: string, qty: string, over: Record<string, unknown> = {}, saleDate?: string) =>
  api.createSale(
    { branch_id: branch, payment_method: 'credit', customer_id: buyer, ...(saleDate ? { sale_date: saleDate } : {}), lines: [{ product_id: productId, qty }], ...over } as never,
    `key-${Math.random()}`,
  );

describe('customer accounts in the mock', () => {
  it('answers 404 capability_not_enabled while the capability is off', async () => {
    const api = createMockRetail();
    await expect(api.customerAccount(buyer)).rejects.toMatchObject({ status: 404, code: 'capability_not_enabled' });
    await expect(api.customerStatement(buyer, {})).rejects.toMatchObject({ status: 404 });
    await expect(api.payOnAccount(buyer, { amount_minor: 1, method: 'cash' }, 'k')).rejects.toMatchObject({ status: 404 });
    // And a typed name still sells on credit, as before.
    const [bulb] = await api.listProducts({ query: 'P003' });
    const sale = await api.createSale({ branch_id: branch, payment_method: 'credit', buyer_name: 'Typed Buyer 01', due_date: today, lines: [{ product_id: bulb?.id ?? '', qty: '1' }] }, 'k1');
    expect(sale.balance_minor).toBe(6000);
  });

  it('refuses a typed name and a sale above the limit, and lets a reason through', async () => {
    const { api, productId } = await trade();
    await expect(api.createSale({ branch_id: branch, payment_method: 'credit', buyer_name: 'Typed Buyer 01', lines: [{ product_id: productId, qty: '1' }] }, 'k1')).rejects.toMatchObject({ code: 'customer_required' });
    const account = await api.customerAccount(buyer);
    await api.setCustomerAccount(buyer, account.version ?? 1, { credit_limit_minor: 12000, clear_terms_days: true });
    await creditSale(api, productId, '2'); // 12,000: exactly the limit
    const refused = creditSale(api, productId, '1');
    await expect(refused).rejects.toMatchObject({ code: 'credit_limit_exceeded', problem: { exceeded_by_minor: 6000 } });
    await expect(creditSale(api, productId, '1', { credit_override_reason: 'Standing arrangement' })).resolves.toMatchObject({ balance_minor: 6000 });
    const after = await api.customerAccount(buyer);
    expect(after).toMatchObject({ balance_minor: 18000, over_limit: true, available_minor: 0 });
  });

  it('dates a credit sale from the terms unless a date is typed', async () => {
    const { api, productId } = await trade();
    await api.setCustomerAccount(buyer, (await api.customerAccount(buyer)).version ?? 1, { terms_days: 30, clear_credit_limit: true });
    const dated = await creditSale(api, productId, '1', {}, '2026-01-05');
    expect(dated.due_date).toBe('2026-02-04');
    expect((await creditSale(api, productId, '1', { due_date: '2026-03-01' }, '2026-01-05')).due_date).toBe('2026-03-01');
  });

  it('allocates a payment oldest first, refuses what cannot be placed, and takes a chosen split', async () => {
    const { api, productId } = await trade();
    const newer = await creditSale(api, productId, '1', {}, '2026-02-05');
    const older = await creditSale(api, productId, '2', {}, '2026-01-05');
    const first = await api.payOnAccount(buyer, { branch_id: branch, amount_minor: 13000, method: 'cash', paid_on: today }, 'p1');
    expect(first.allocation).toBe('oldest_first');
    expect(first.allocations?.map((a) => [a.sale_id, a.amount_minor])).toEqual([[older.id, 12000], [newer.id, 1000]]);
    await expect(api.payOnAccount(buyer, { branch_id: branch, amount_minor: 5001, method: 'cash' }, 'p2')).rejects.toMatchObject({ code: 'payment_exceeds_allocatable', problem: { allocatable_minor: 5000 } });
    const manual = await api.payOnAccount(buyer, { branch_id: branch, amount_minor: 1000, method: 'bank', allocations: [{ sale_id: newer.id ?? '', amount_minor: 1000 }] }, 'p3');
    expect(manual.allocation).toBe('manual');
    expect((await api.customerBalance(buyer)).balance_minor).toBe(4000);
    // The same key replays the first answer.
    expect((await api.payOnAccount(buyer, { branch_id: branch, amount_minor: 13000, method: 'cash', paid_on: today }, 'p1')).id).toBe(first.id);
  });

  it('reverses a payment as a whole, once, and the sales owe again', async () => {
    const { api, productId } = await trade();
    await creditSale(api, productId, '2', {}, '2026-01-05');
    const paid = await api.payOnAccount(buyer, { branch_id: branch, amount_minor: 12000, method: 'cash' }, 'p1');
    expect((await api.customerBalance(buyer)).balance_minor).toBe(0);
    const reversed = await api.reverseAccountPayment(buyer, paid.id ?? '', 'Wrong buyer', 'r1');
    expect(reversed).toMatchObject({ status: 'reversed', reverse_reason: 'Wrong buyer' });
    expect((await api.customerBalance(buyer)).balance_minor).toBe(12000);
    await expect(api.reverseAccountPayment(buyer, paid.id ?? '', 'Again', 'r2')).rejects.toMatchObject({ status: 409, code: 'receipt_reversed' });
    expect((await api.listAccountPayments(buyer)).items?.[0]?.status).toBe('reversed');
  });

  it('closes the statement at what the buyer owes, with a running balance and an opening balance', async () => {
    const { api, productId } = await trade();
    await creditSale(api, productId, '2', {}, daysBefore(today, 120));
    await creditSale(api, productId, '1', {}, daysBefore(today, 10));
    const paid = await api.payOnAccount(buyer, { branch_id: branch, amount_minor: 3000, method: 'cash', paid_on: daysBefore(today, 5) }, 'p1');
    await api.reverseAccountPayment(buyer, paid.id ?? '', 'Wrong buyer', 'r1');
    const st = await api.customerStatement(buyer, {});
    // The default range is the last 90 days: the first sale is before it and sits in the opening balance.
    expect(st.opening_minor).toBe(12000);
    expect(st.lines?.map((l) => l.kind)).toEqual(['sale', 'account_payment', 'reversal']);
    expect(st.closing_minor).toBe((await api.customerBalance(buyer)).balance_minor);
    let running = st.opening_minor ?? 0;
    for (const l of st.lines ?? []) {
      running += (l.debit_minor ?? 0) - (l.credit_minor ?? 0);
      expect(l.balance_minor).toBe(running);
    }
  });
});
