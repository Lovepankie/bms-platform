// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import type { Order } from '../../../api/retail-orders';
import { StaffContext } from '../context';

// Changing the items of an order on its page (issue #393): the button only while the order is ordered or packed, the items on
// the order at the price agreed, one request with the version read and an Idempotency-Key that a retry repeats, and a refusal
// in plain words. Names and amounts are fabricated.

vi.setConfig({ testTimeout: 20000 });

const changeItems = vi.fn();
const getProduct = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, getProduct: (...a: unknown[]) => getProduct(...a) } };
});

vi.mock('../../../api/retail-orders', async (original) => {
  const real = await original<typeof import('../../../api/retail-orders')>();
  return { ...real, retailOrders: { ...real.retailOrders, changeItems: (...a: unknown[]) => changeItems(...a) } };
});

const { RetailError } = await import('../../../api/retail');
const { retailMessage } = await import('../../../api/retail-errors');
const { OrderPage } = await import('./order');

afterEach(() => {
  cleanup();
  changeItems.mockReset();
  getProduct.mockReset();
});

const admin = mockMe('admin');
const branch = admin.default_branch_id ?? admin.branches?.[0]?.id ?? '';
const ID = '00000000-0000-4000-8000-0000000b0001';
const romper: Product = { id: 'p1', code: 'ROMPER', description: 'Test Romper', unit: 'piece', sell_minor: 10000, cost_minor: 6000, active: true };

const order = (status: string): Order => ({
  id: ID, order_no: 'OR000002', branch_id: branch, ordered_on: '2026-10-21', source: 'whatsapp', customer_name: 'Test Customer 02',
  delivery_fee_minor: 3000, currency: 'UGX', items_total_minor: 18000, total_minor: 21000, status, created_at: '2026-10-21T08:00:00Z',
  created_by: admin.user_id, version: 4,
  lines: [{ line_no: 1, product_id: 'p1', code: 'ROMPER', description: 'Test Romper', qty: '2', unit: 'piece', unit_price_minor: 9000, line_total_minor: 18000, base_qty: '2' }],
  history: [{ step: 'ordered', by_user: admin.user_id, at: '2026-10-21T08:00:00Z' }],
} as Order);

function page(o: Order) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(['retail', 'order', ID], o);
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me: admin, branch }}>
        <OrderPage orderId={ID} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const save = () => screen.getByRole('button', { name: /Save the new items|Saving/ }) as HTMLButtonElement;

describe('Change the items on the order page', () => {
  it('is offered while the order is ordered or packed, and not once it is out for delivery', () => {
    page(order('packed'));
    expect(screen.getByRole('button', { name: 'Change the items' })).toBeTruthy();
    cleanup();
    page(order('out_for_delivery'));
    expect(screen.queryByRole('button', { name: 'Change the items' })).toBeNull();
  });

  it('shows the items at the price agreed and saves a new quantity once, priced again, with the version and a key', async () => {
    getProduct.mockResolvedValue(romper);
    changeItems.mockResolvedValue({ ...order('ordered'), version: 5 });
    page(order('ordered'));
    fireEvent.click(screen.getByRole('button', { name: 'Change the items' }));
    const qty = (await screen.findByLabelText(/Quantity/)) as HTMLInputElement;
    expect(qty.value).toBe('2');
    expect((screen.getByLabelText(/Unit price/) as HTMLInputElement).value).toBe('9000');
    fireEvent.change(qty, { target: { value: '3' } });
    // A new quantity is priced again as on a new order: today's price shows, and no price is sent.
    expect((screen.getByLabelText(/Unit price/) as HTMLInputElement).value).toBe('10000');
    fireEvent.change(screen.getByLabelText(/Why the items changed/), { target: { value: 'Test bigger basket' } });
    fireEvent.click(save());
    await waitFor(() => expect(changeItems).toHaveBeenCalledTimes(1));
    const [id, version, body, key] = changeItems.mock.calls[0] as [string, number, { lines: unknown[]; note?: string }, string];
    expect(id).toBe(ID);
    expect(version).toBe(4);
    // The price shown is the sell price, so none is sent and the server prices the line itself (a price list or break may apply).
    expect(body.lines).toEqual([{ product_id: 'p1', qty: '3.000' }]);
    expect(body.note).toBe('Test bigger basket');
    expect(key).toMatch(/\w{8,}/);
  });

  it('keeps the price agreed on a line left as it was', async () => {
    getProduct.mockResolvedValue(romper);
    changeItems.mockResolvedValue({ ...order('ordered'), version: 5 });
    page(order('ordered'));
    fireEvent.click(screen.getByRole('button', { name: 'Change the items' }));
    const qty = (await screen.findByLabelText(/Quantity/)) as HTMLInputElement;
    fireEvent.change(qty, { target: { value: '3' } });
    fireEvent.change(qty, { target: { value: '2' } });
    expect((screen.getByLabelText(/Unit price/) as HTMLInputElement).value).toBe('9000');
    fireEvent.click(save());
    await waitFor(() => expect(changeItems).toHaveBeenCalledTimes(1));
    const body = changeItems.mock.calls[0]?.[2] as { lines: unknown[] };
    expect(body.lines).toEqual([{ product_id: 'p1', qty: '2.000', unit_price_minor: 9000 }]);
  });

  it('says in plain words when there is not enough free stock, and a retry repeats the same key', async () => {
    getProduct.mockResolvedValue(romper);
    changeItems.mockRejectedValue(new RetailError(retailMessage({ code: 'insufficient_stock', detail: 'x' }, 422), 422, 'insufficient_stock'));
    page(order('ordered'));
    fireEvent.click(screen.getByRole('button', { name: 'Change the items' }));
    fireEvent.change(await screen.findByLabelText(/Quantity/), { target: { value: '9' } });
    fireEvent.click(save());
    expect(await screen.findByText(/not enough stock/i)).toBeTruthy();
    fireEvent.click(save());
    await waitFor(() => expect(changeItems).toHaveBeenCalledTimes(2));
    expect(changeItems.mock.calls[1]?.[3]).toBe(changeItems.mock.calls[0]?.[3]);
  });

  it('will not save without any item', async () => {
    getProduct.mockResolvedValue(romper);
    page(order('ordered'));
    fireEvent.click(screen.getByRole('button', { name: 'Change the items' }));
    await screen.findByLabelText(/Quantity/);
    fireEvent.click(screen.getByRole('button', { name: /Remove/ }));
    expect(screen.getByText(/cancel the order/i)).toBeTruthy();
    expect(save().disabled).toBe(true);
  });
});
