// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product, Sale } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { pickItem } from '../../../test-utils/item-picker';
import { StaffContext } from '../context';

// Issue #824, review of #828: the sale, the busiest save of the shop, says Sale saved in the toast beside its receipt; a
// write off that went through says Debt written off, and one sent to a second administrator says so on screen with no
// success toast. The app's one DoneToast is mounted. Fabricated items, names and amounts.

vi.setConfig({ testTimeout: 20000 });

const bulb: Product = { id: 'p1', code: 'P003', description: 'Test Bulb 9W', unit: 'piece', sell_minor: 6000, active: true, qty: '12.000' };
const api = vi.hoisted(() => ({ createSale: vi.fn(), writeOffSale: vi.fn() }));

vi.mock('../../../api/retail', async (original) => ({
  ...(await original<typeof import('../../../api/retail')>()),
  retail: {
    listProducts: () => Promise.resolve([bulb]),
    listCustomers: () => Promise.resolve([]),
    createSale: (...a: unknown[]) => api.createSale(...a),
    writeOffSale: (...a: unknown[]) => api.writeOffSale(...a),
  },
}));

const { RecordSale } = await import('./sale');
const { WriteOffSaleAction } = await import('./sale-actions');
const { DoneToast, doneToasts } = await import('./toast');

afterEach(() => {
  cleanup();
  Object.values(api).forEach((f) => f.mockReset());
  sessionStorage.clear();
});

const seller = mockMe('sales');
const admin = mockMe('admin');
const branch = seller.branches?.[0]?.id ?? '';
const sold: Sale = {
  id: 's1', sale_no: 'RS00000011', branch_id: branch, sale_date: '2026-11-02', payment_method: 'cash', status: 'completed', total_minor: 6000,
  paid_minor: 6000, balance_minor: 0, lines: [{ product_id: 'p1', description: 'Test Bulb 9W', qty: '1.000', unit_price_minor: 6000, line_total_minor: 6000 }],
};
const owing: Sale = { ...sold, id: 's2', sale_no: 'RS00000012', payment_method: 'credit', buyer_name: 'Test Buyer 01', paid_minor: 1000, balance_minor: 5000 };

function screenOf(me: ReturnType<typeof mockMe>, node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } }, mutationCache: doneToasts() })}>
      <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined }}>{node}</StaffContext.Provider>
      <DoneToast />
    </QueryClientProvider>,
  );
}

const successToasts = () => Array.from(document.querySelectorAll('.toast-success')).map((t) => t.textContent ?? '');

function writeOff() {
  screenOf(admin, <WriteOffSaleAction sale={owing} />);
  fireEvent.click(screen.getByRole('button', { name: 'Write off' }));
  fireEvent.change(screen.getByLabelText(/Why will this buyer not pay/), { target: { value: 'Buyer left town' } });
  fireEvent.click(screen.getByRole('button', { name: 'Write off' }));
}

describe('the sale and the write off say it in a toast (#824)', () => {
  it('a sale says Sale saved beside its receipt', async () => {
    api.createSale.mockResolvedValue(sold);
    screenOf(seller, <RecordSale />);
    await pickItem('Test Bulb 9W');
    fireEvent.click(screen.getByRole('button', { name: /^Record sale/ }));
    expect(await screen.findByLabelText('Sale receipt')).toBeTruthy();
    await waitFor(() => expect(successToasts()).toEqual(['Sale saved']));
  });

  it('a write off that went through says Debt written off', async () => {
    api.writeOffSale.mockResolvedValue({ executed: true, write_off: { written_off_on: '2026-11-02', unpaid_minor: 5000, reason: 'Buyer left town' } });
    writeOff();
    await waitFor(() => expect(successToasts()).toEqual(['Debt written off']));
  });

  it('a write off sent to a second administrator says so on screen, with no success toast', async () => {
    api.writeOffSale.mockResolvedValue({ executed: false, approval_id: 'a1' });
    writeOff();
    expect(await screen.findByText(/Sent to a second administrator to approve/)).toBeTruthy();
    await new Promise((done) => setTimeout(done, 100));
    expect(successToasts()).toEqual([]);
  });
});
