// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product, Sale } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { pickItem } from '../../../test-utils/item-picker';
import { StaffContext } from '../context';

// The customer's name on a sale paid now (issue #760) as a seller uses it: hidden behind Add customer name so the fast sale
// is unchanged, sent with the sale when typed, nothing sent when left blank or taken off, and never offered on a credit sale.
// Fabricated items, names and numbers.

vi.setConfig({ testTimeout: 20000 });

const bulb: Product = { id: 'p1', code: 'P003', description: 'Test Bulb 9W', unit: 'piece', sell_minor: 6000, active: true, qty: '12.000' };
const createSale = vi.fn();

vi.mock('../../../api/retail', async (original) => ({
  ...(await original<typeof import('../../../api/retail')>()),
  retail: {
    listProducts: () => Promise.resolve([bulb]),
    listCustomers: () => Promise.resolve([]),
    createSale: (...a: unknown[]) => createSale(...a),
  },
}));

const { RecordSale } = await import('./sale');
const { CASH_NAME_HINT } = await import('./cash-customer');
const me = mockMe('sales');
const branch = me.branches?.[0]?.id ?? '';

const answer = (over: Partial<Sale> = {}): Sale => ({
  id: 's1', sale_no: 'RS00000011', branch_id: branch, sale_date: '2026-11-02', payment_method: 'cash', status: 'completed', total_minor: 6000,
  paid_minor: 6000, balance_minor: 0, lines: [{ product_id: 'p1', description: 'Test Bulb 9W', qty: '1.000', unit_price_minor: 6000, line_total_minor: 6000 }], ...over,
});

function open() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined }}>
        <RecordSale />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const addName = () => screen.getByRole('button', { name: 'Add customer name' });
const save = () => fireEvent.click(screen.getByRole('button', { name: /^Record sale/ }));

afterEach(() => {
  cleanup();
  createSale.mockReset();
  sessionStorage.clear();
});

describe('Add customer name', () => {
  it('keeps the fast sale as it was: no name field until the button is tapped, and no name sent', async () => {
    createSale.mockResolvedValue(answer());
    open();
    await pickItem('Test Bulb 9W');
    expect(addName()).toBeTruthy();
    expect(screen.queryByLabelText('Customer name (optional)')).toBeNull();
    save();
    expect(await screen.findByLabelText('Sale receipt')).toBeTruthy();
    expect(createSale.mock.calls[0]?.[0]).not.toHaveProperty('buyer_name');
    expect(createSale.mock.calls[0]?.[0]).not.toHaveProperty('buyer_contact');
  });

  it('sends the name and phone typed for the receipt with the sale', async () => {
    createSale.mockResolvedValue(answer({ buyer_name: 'Test Customer 21', buyer_contact: '0700 000 051' }));
    open();
    await pickItem('Test Bulb 9W');
    fireEvent.click(addName());
    expect(screen.getByText(CASH_NAME_HINT)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Customer name (optional)'), { target: { value: ' Test Customer 21 ' } });
    fireEvent.change(screen.getByLabelText('Phone (optional)'), { target: { value: '0700 000 051' } });
    save();
    expect(await screen.findByLabelText('Sale receipt')).toBeTruthy();
    expect(createSale.mock.calls[0]?.[0]).toMatchObject({ payment_method: 'cash', buyer_name: 'Test Customer 21', buyer_contact: '0700 000 051' });
    expect(createSale.mock.calls[0]?.[0]).not.toHaveProperty('customer_id');
  });

  it('takes the name off again with No name on the receipt', async () => {
    createSale.mockResolvedValue(answer());
    open();
    await pickItem('Test Bulb 9W');
    fireEvent.click(addName());
    fireEvent.change(screen.getByLabelText('Customer name (optional)'), { target: { value: 'Test Customer 22' } });
    fireEvent.click(screen.getByRole('button', { name: 'No name on the receipt' }));
    expect(screen.queryByLabelText('Customer name (optional)')).toBeNull();
    save();
    expect(await screen.findByLabelText('Sale receipt')).toBeTruthy();
    expect(createSale.mock.calls[0]?.[0]).not.toHaveProperty('buyer_name');
  });

  it('is not offered on a credit sale, which names its buyer its own way', async () => {
    open();
    await pickItem('Test Bulb 9W');
    fireEvent.click(screen.getByLabelText('Credit (pay later)'));
    expect(screen.queryByRole('button', { name: 'Add customer name' })).toBeNull();
    fireEvent.click(screen.getByLabelText('Mobile money'));
    expect(addName()).toBeTruthy();
  });
});
