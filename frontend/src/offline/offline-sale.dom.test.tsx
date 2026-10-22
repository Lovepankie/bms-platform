// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Product } from '../api/retail';

// Record a sale with the network off (issue #458, ADR-067; NFR-OFF-07): the items come from the phone's copy, a credit sale
// is not offered, the sale goes to the outbox with every price as charged, the provisional receipt shows, and once the
// phone is online the server's number replaces it. A sale whose answer was lost locks the form and is kept as it was sent,
// with the same client sale id and a new key, so it is recorded once. Every request fails as with no network. Fabricated people, items and amounts.

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
const catalogue = { pricesAsOf: '2026-10-22T09:14:00.000Z', products: { [branch]: [bulb] }, customers: [] };

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

beforeEach(() => sessionStorage.clear());

afterEach(() => {
  cleanup();
  createSale.mockReset();
  listProducts.mockReset();
});

describe('Record a sale offline', () => {
  it('saves a sale on the phone from the copy and shows a provisional receipt, then the server number', async () => {
    const send = vi.fn(async () => ({ status: 201, body: { id: 'sale-1', sale_no: 'RS00000042' } }));
    const offline = testOffline(seller, { online: false, catalogue, deps: { send } });
    if (!offline) throw new Error('a seller sells offline');
    screenWith(offline);

    expect(await screen.findByTestId('offline-note')).toBeTruthy();
    expect(screen.getByText(/Prices as of/)).toBeTruthy();
    expect((screen.getByLabelText('Credit (pay later)') as HTMLInputElement).disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('Find an item by name, code or category'), { target: { value: 'bulb' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Add Test Bulb 9W' }));
    // Two on a shelf the copy says holds one: the server flags it, the phone does not stop the sale.
    const qty = screen.getAllByRole('textbox').find((i) => (i as HTMLInputElement).value === '1') as HTMLInputElement;
    fireEvent.change(qty, { target: { value: '2' } });
    const save = screen.getByRole('button', { name: 'Save on this phone' }) as HTMLButtonElement;
    await waitFor(() => expect(save.disabled).toBe(false));
    fireEvent.click(save);

    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    expect(screen.getByTestId('receipt-number').textContent).toMatch(/OFF-[0-9A-F]{8} \(provisional\)/);
    expect(screen.getByText('Not yet sent. It goes to the server when the phone is back online.')).toBeTruthy();
    const items = offline.engine.snapshot().items;
    expect(items).toHaveLength(1);
    expect(items[0]?.request).toMatchObject({
      branch_id: branch,
      payment_method: 'cash',
      recorded_offline: true,
      prices_as_of: '2026-10-22T09:14:00.000Z',
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

  it('keeps a sale whose answer was lost with the same client sale id and a new key', async () => {
    createSale.mockRejectedValue(new TypeError('Failed to fetch'));
    listProducts.mockResolvedValue([bulb]);
    const offline = testOffline(seller, { online: true, catalogue });
    if (!offline) throw new Error('a seller sells offline');
    screenWith(offline);

    fireEvent.change(screen.getByLabelText('Find an item by name, code or category'), { target: { value: 'bulb' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Add Test Bulb 9W' }));
    const save = screen.getByRole('button', { name: 'Save sale' }) as HTMLButtonElement;
    await waitFor(() => expect(save.disabled).toBe(false));
    fireEvent.click(save);

    // The answer is lost: the form is locked, so the cashier cannot change the lines and send them under the same id.
    const keepIt = await screen.findByRole('button', { name: 'Keep it on this phone' });
    const qty = screen.getAllByRole('textbox').find((i) => (i as HTMLInputElement).value === '1') as HTMLInputElement;
    expect(qty.matches(':disabled')).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await screen.findByRole('button', { name: 'Keep it on this phone' });
    expect(createSale).toHaveBeenCalledTimes(2);
    expect(createSale.mock.calls[1]).toEqual(createSale.mock.calls[0]);

    fireEvent.click(keepIt.isConnected ? keepIt : screen.getByRole('button', { name: 'Keep it on this phone' }));
    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    const [body, key] = createSale.mock.calls[0] as [{ client_sale_id?: string; lines?: unknown }, string];
    expect(body.client_sale_id).toBe(key);
    const [item] = offline.engine.snapshot().items;
    expect(item?.id).toBe(key);
    expect(item?.request.client_sale_id).toBe(key);
    expect(item?.request.lines).toEqual(body.lines);
    expect(item?.request.recorded_offline).toBe(true);
    expect(item?.key).not.toBe(key);
  });
});
