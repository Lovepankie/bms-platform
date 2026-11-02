// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Product } from '../api/retail';
import { SALE_ANSWER_MS } from './connectivity';
import type { OutboxItem } from './outbox';

/** A button that may be pressed: not disabled, and not a primary button whose form is still not ready (issue #526). */
const ready = (b: unknown) => !(b as HTMLButtonElement).disabled && (b as HTMLElement).getAttribute('data-not-ready') !== 'true';

// Record a sale with the network off (issue #458, ADR-067; NFR-OFF-07): the items come from the phone's copy, a credit sale
// is not offered, the sale goes to the outbox with every price as charged, the provisional receipt shows, and once the
// phone is online the server's number replaces it. Issue #769: online, a sale lost on the network or with no answer within
// 8 seconds goes to the outbox at once, as sent and under the same key, and the late answer makes no second sale. A sale whose
// answer was lost before a reload locks the form and is kept with the same client sale id and a new key, so it is recorded
// once. Every request fails as with no network unless a test says otherwise. Fabricated people, items and amounts.

vi.setConfig({ testTimeout: 20000 });
vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))));

const createSale = vi.fn();
const listProducts = vi.fn();

vi.mock('../api/retail', async (original) => {
  const real = await original<typeof import('../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      createSale: (...a: unknown[]) => createSale(...a),
      listProducts: (...a: unknown[]) => listProducts(...a),
      listCustomers: () => Promise.reject(new TypeError('Failed to fetch')),
      branchRoles: () => Promise.reject(new TypeError('Failed to fetch')),
      stockedBranches: () => Promise.reject(new TypeError('Failed to fetch')),
    },
  };
});

const { mockMe } = await import('../api/retail-mock');
const { StaffContext } = await import('../areas/staff/context');
const { RecordSale } = await import('../areas/staff/retail/sale');
const { OfflineProvider } = await import('./offline-context');
const { testOffline } = await import('./test-kit');

const seller = mockMe('sales');
const branch = seller.branches?.[0]?.id ?? '';
const bulb = { id: 'p2', code: 'BLB-9', description: 'Test Bulb 9W', category: 'Test Lighting', unit: 'pc', sell_minor: 5000, qty: '1.000', active: true } as Product;
const catalogue = { pricesAsOf: '2026-10-22T09:14:00.000Z', products: { [branch]: [bulb] }, customers: [{ id: 'c1', name: 'Test Customer 05' }] };

function screenWith(offline: ReturnType<typeof testOffline>) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me: seller, branch }}>
        <OfflineProvider value={offline}>
          <RecordSale />
        </OfflineProvider>
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

/** Online: finds the bulb, adds it and presses Record sale. */
async function addBulbAndRecord() {
  fireEvent.change(screen.getByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'bulb' } });
  fireEvent.click(await screen.findByRole('option', { name: /^Test Bulb 9W/ }));
  const save = screen.getByRole('button', { name: /^Record sale/ }) as HTMLButtonElement;
  await waitFor(() => expect(ready(save)).toBe(true));
  fireEvent.click(save);
}

beforeEach(() => sessionStorage.clear());

afterEach(() => {
  cleanup();
  createSale.mockReset();
  listProducts.mockReset();
});

describe('Record a sale offline', () => {
  it('says what the phone holds when no item on it matches, and when nothing is saved on it yet', async () => {
    const withCopy = testOffline(seller, { online: false, catalogue });
    if (!withCopy) throw new Error('a seller sells offline');
    screenWith(withCopy);
    fireEvent.change(await screen.findByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'fridge' } });
    expect(await screen.findByText('No item on this phone matches "fridge". Items are saved while the phone is online.', { selector: '.cb-empty' })).toBeTruthy();
    cleanup();
    const empty = testOffline(seller, { online: false, catalogue: null });
    if (!empty) throw new Error('a seller sells offline');
    screenWith(empty);
    fireEvent.change(await screen.findByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'bulb' } });
    expect(await screen.findByText('No items are saved on this phone yet. Items are saved while the phone is online.', { selector: '.cb-empty' })).toBeTruthy();
  });

  it('saves a sale on the phone from the copy and shows a provisional receipt, then the server number', async () => {
    const send = vi.fn(async () => ({ status: 201, body: { id: 'sale-1', sale_no: 'RS00000042' } }));
    const offline = testOffline(seller, { online: false, catalogue, deps: { send } });
    if (!offline) throw new Error('a seller sells offline');
    screenWith(offline);

    expect(await screen.findByTestId('offline-note')).toBeTruthy();
    expect(screen.getByText(/Prices as of/)).toBeTruthy();
    expect((screen.getByLabelText('Credit (pay later)') as HTMLInputElement).disabled).toBe(true);

    fireEvent.change(screen.getByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'bulb' } });
    fireEvent.click(await screen.findByRole('option', { name: /^Test Bulb 9W/ }));
    // Two on a shelf the copy says holds one: the server flags it, the phone does not stop the sale.
    const qty = screen.getAllByRole('textbox').find((i) => (i as HTMLInputElement).value === '1') as HTMLInputElement;
    fireEvent.change(qty, { target: { value: '2' } });
    // The buyer's name typed offline goes with the sale.
    fireEvent.change(screen.getByLabelText("Or the buyer's name"), { target: { value: 'Test Buyer 21' } });
    const save = screen.getByRole('button', { name: 'Save on this phone' }) as HTMLButtonElement;
    await waitFor(() => expect(ready(save)).toBe(true));
    fireEvent.click(save);

    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    expect(screen.getByTestId('receipt-number').textContent).toMatch(/OFF-[0-9A-F]{8} \(provisional\)/);
    expect(screen.getByText('Saved on this phone, it will send when the connection is better.')).toBeTruthy();
    const items = offline.engine.snapshot().items;
    expect(items).toHaveLength(1);
    expect(items[0]?.request).toMatchObject({
      branch_id: branch,
      payment_method: 'cash',
      recorded_offline: true,
      prices_as_of: '2026-10-22T09:14:00.000Z',
      buyer_name: 'Test Buyer 21',
      lines: [{ product_id: 'p2', qty: '2.000', unit_price_minor: 5000 }],
    });
    expect(items[0]?.request.client_sale_id).toBe(items[0]?.id);
    expect(createSale).not.toHaveBeenCalled();
    expect(send).not.toHaveBeenCalled();

    // Back online: the engine sends it and the receipt takes the server's number.
    act(() => offline.connectivity.set(true));
    await act(() => offline.engine.syncNow());
    expect(send).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('Sale sent')).toBeTruthy();
    expect(screen.getByTestId('receipt-number').textContent).toBe('Receipt RS00000042');
  });

  it('does not let a seller without retail.price.override type a price offline', async () => {
    const plain = { ...seller, permissions: (seller.permissions ?? []).filter((p) => p !== 'retail.price.override') };
    const offline = testOffline(plain, { online: false, catalogue });
    if (!offline) throw new Error('a seller sells offline');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: plain, branch }}>
          <OfflineProvider value={offline}>
            <RecordSale />
          </OfflineProvider>
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
    fireEvent.change(await screen.findByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'bulb' } });
    fireEvent.click(await screen.findByRole('option', { name: /^Test Bulb 9W/ }));
    const price = screen.getByLabelText('Selling price') as HTMLInputElement;
    expect(price.readOnly).toBe(true);
    expect(screen.getByText('Offline, the price saved on this phone is used. You do not have permission to change it.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Save on this phone' }));
    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    expect(offline.engine.snapshot().items[0]?.request.lines).toEqual([{ product_id: 'p2', qty: '1.000', unit_price_minor: 5000 }]);
  });

  it('says plainly when the items of the branch are not on the phone yet (issue #769)', async () => {
    const empty = testOffline(seller, { online: false, catalogue: null });
    if (!empty) throw new Error('a seller sells offline');
    screenWith(empty);
    expect((await screen.findByTestId('no-items-on-phone')).textContent).toBe(
      'The items are not on this phone yet, so you cannot sell offline here. Open Sell once with the internet on, and the items are saved on this phone for next time.',
    );
    cleanup();
    const other = testOffline(seller, { online: false, catalogue: { ...catalogue, products: { elsewhere: [bulb] } } });
    if (!other) throw new Error('a seller sells offline');
    screenWith(other);
    expect(await screen.findByTestId('no-items-on-phone')).toBeTruthy();
    cleanup();
    const withCopy = testOffline(seller, { online: false, catalogue });
    if (!withCopy) throw new Error('a seller sells offline');
    screenWith(withCopy);
    expect(await screen.findByText(/Prices as of/)).toBeTruthy();
    expect(screen.queryByTestId('no-items-on-phone')).toBeNull();
  });

  it('puts a sale lost on the network in the outbox at once, as it was sent and under the same key (issue #769)', async () => {
    createSale.mockRejectedValue(new TypeError('Failed to fetch'));
    listProducts.mockResolvedValue([bulb]);
    const offline = testOffline(seller, { online: true, catalogue });
    if (!offline) throw new Error('a seller sells offline');
    screenWith(offline);

    await addBulbAndRecord();
    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    expect(screen.getByText('Saved on this phone, it will send when the connection is better.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Keep it on this phone' })).toBeNull();
    expect(createSale).toHaveBeenCalledTimes(1);
    const [body, key] = createSale.mock.calls[0] as [{ client_sale_id?: string }, string];
    expect(body.client_sale_id).toBe(key);
    const [item] = offline.engine.snapshot().items;
    expect(item).toMatchObject({ id: key, key, state: 'queued' });
    expect(item?.request).toEqual(body);
  });

  it('keeps a sale with no answer within 8 seconds on the phone, and the late answer makes no second sale (issue #769)', async () => {
    // A fake server holding one sale per Idempotency-Key: a second arrival under the key is answered with the same sale.
    const recorded = new Map<string, { body: unknown; sale: { id: string; sale_no: string } }>();
    const arrive = (body: unknown, key: string) => {
      const before = recorded.get(key);
      if (before) {
        expect(before.body).toEqual(body);
        return before.sale;
      }
      const sale = { id: `sale-${recorded.size + 1}`, sale_no: `RS0000007${recorded.size + 1}` };
      recorded.set(key, { body, sale });
      return sale;
    };
    let answerLate = () => {};
    createSale.mockImplementation((body: unknown, key: string) => new Promise((resolve) => (answerLate = () => resolve(arrive(body, key)))));
    listProducts.mockResolvedValue([bulb]);
    const send = vi.fn(async (item: OutboxItem) => ({ status: 201, body: arrive(item.request, item.key) }));
    const offline = testOffline(seller, { online: true, catalogue, deps: { send } });
    if (!offline) throw new Error('a seller sells offline');
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      screenWith(offline);
      await addBulbAndRecord();
      await act(() => vi.advanceTimersByTimeAsync(SALE_ANSWER_MS - 100));
      expect(screen.queryByText('Sale saved on this phone')).toBeNull();
      await act(() => vi.advanceTimersByTimeAsync(100));
      expect(await screen.findByRole('heading', { name: /^Sale (saved on this phone|sent)$/ })).toBeTruthy();

      const [body, key] = createSale.mock.calls[0] as [unknown, string];
      expect(offline.engine.snapshot().items[0]).toMatchObject({ id: key, key });
      expect(offline.engine.snapshot().items[0]?.request).toEqual(body);

      // The server's answer to the first request comes at last: the screen keeps the receipt it shows, and the sale is one.
      await act(async () => answerLate());
      await act(() => offline.engine.syncNow());
      expect(await screen.findByText('Sale sent')).toBeTruthy();
      expect(screen.getByTestId('receipt-number').textContent).toBe('Receipt RS00000071');
      expect(recorded.size).toBe(1);
      expect(createSale).toHaveBeenCalledTimes(1);
      expect(send.mock.calls.every(([i]) => i.key === key)).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps a sale whose answer was lost before a reload with the same client sale id and a new key', async () => {
    const key = 'c0ffee00-0000-4000-8000-000000000769';
    const body = { branch_id: branch, payment_method: 'cash', lines: [{ product_id: 'p2', qty: '1.000', unit_price_minor: 5000 }], client_sale_id: key };
    const draft = { branchId: branch, method: 'cash', customerId: '', newBuyerName: '', newBuyerContact: '', dueDate: '', lines: [{ product: bulb, qty: '1', price: '5000' }], sent: { body, at: '2026-10-22T09:30:00.000Z' } };
    sessionStorage.setItem(`retail-draft:sale:${seller.user_id ?? ''}:${branch}`, JSON.stringify({ key, draft }));
    const offline = testOffline(seller, { online: true, catalogue });
    if (!offline) throw new Error('a seller sells offline');
    screenWith(offline);

    // The answer is lost: the form is locked, so the cashier cannot change the lines and send them under the same id.
    fireEvent.click(await screen.findByRole('button', { name: 'Keep it on this phone' }));
    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    const [item] = offline.engine.snapshot().items;
    expect(item?.id).toBe(key);
    expect(item?.request).toMatchObject({ ...body, recorded_offline: true, occurred_at: '2026-10-22T09:30:00.000Z' });
    expect(item?.key).not.toBe(key);
  });
  it('offers only Try again for a credit sale whose answer was lost, never Keep it on this phone', async () => {
    createSale.mockRejectedValue(new TypeError('Failed to fetch'));
    const key = 'c0ffee00-0000-4000-8000-000000000458';
    const body = { branch_id: branch, payment_method: 'credit', buyer_name: 'Test Buyer 07', lines: [{ product_id: 'p2', qty: '1' }], client_sale_id: key };
    const draft = { branchId: branch, method: 'credit', customerId: '', newBuyerName: 'Test Buyer 07', newBuyerContact: '', dueDate: '', lines: [{ product: bulb, qty: '1', price: '5000' }], sent: { body, at: '2026-10-22T09:30:00.000Z' } };
    sessionStorage.setItem(`retail-draft:sale:${seller.user_id ?? ''}:${branch}`, JSON.stringify({ key, draft }));
    const offline = testOffline(seller, { online: true, catalogue });
    if (!offline) throw new Error('a seller sells offline');
    screenWith(offline);

    expect((await screen.findByTestId('lost-answer')).textContent).toContain('A credit sale needs the internet');
    expect(screen.queryByRole('button', { name: 'Keep it on this phone' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(createSale).toHaveBeenCalledTimes(1));
    expect(createSale.mock.calls[0]?.[0]).toEqual(body);
    await screen.findByTestId('lost-answer');
    expect(screen.queryByRole('button', { name: 'Keep it on this phone' })).toBeNull();
    expect(offline.engine.snapshot().items).toHaveLength(0);

    // Clearing it is asked first: it may already be recorded.
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    expect((await screen.findByTestId('confirm-clear')).textContent).toContain('may already be saved');
    fireEvent.click(screen.getByRole('button', { name: 'Keep it' }));
    expect(screen.queryByTestId('confirm-clear')).toBeNull();
    expect(screen.getByTestId('lost-answer')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Clear it' }));
    await waitFor(() => expect(screen.queryByTestId('lost-answer')).toBeNull());
  });
});
