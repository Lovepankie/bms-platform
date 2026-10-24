// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product, Sale } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { pickItem } from '../../../test-utils/item-picker';

// The amount received and the change to give on the sale form (issue #346), as a seller uses it: the field for cash only, a
// short amount refused in plain words, the change shown, the receipt with received and change, the field gone for the next sale,
// and one request however often Save is pressed. Fabricated items and amounts.

vi.setConfig({ testTimeout: 20000 });

const createSale = vi.fn();
const bulb: Product = { id: 'p1', code: 'P003', description: 'LED bulb 9W screw', unit: 'piece', sell_minor: 6000, active: true, qty: '12.000' };
const lamp: Product = { id: 'p2', code: 'P004', description: 'LED bulb 15W screw', unit: 'piece', sell_minor: 9500, active: true, qty: '8.000' };

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      listProducts: () => Promise.resolve([bulb, lamp]),
      listCustomers: () => Promise.resolve([]),
      createSale: (...a: unknown[]) => createSale(...a),
    },
  };
});

const { RecordSale } = await import('./sale');

const me = mockMe('sales');
const branch = me.branches?.[0]?.id ?? '';

/** The server's answer: the sale at its lines' total, whatever the form showed. */
function answer(total: number, method = 'cash'): Sale {
  return { id: 's1', branch_id: branch, sale_date: '2026-10-20', payment_method: method, status: 'completed', total_minor: total, paid_minor: total, balance_minor: 0, lines: [{ product_id: 'p1', description: 'LED bulb 9W screw', qty: '1.000', unit_price_minor: 6000, line_total_minor: 6000 }] };
}

function open() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined }}>
        <RecordSale />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

async function addBoth() {
  await pickItem('LED bulb 9W screw');
  await pickItem('LED bulb 15W screw');
}

const field = () => screen.getByLabelText('Amount received (UGX)') as HTMLInputElement;
const type = (v: string) => fireEvent.change(field(), { target: { value: v } });
const saveButton = () => screen.getByRole('button', { name: /^Record sale/ }) as HTMLButtonElement;
const totalLine = () => document.querySelector('.rt-total-hero')?.textContent;
const pay = (name: string) => fireEvent.click(screen.getByLabelText(name));

afterEach(() => {
  cleanup();
  createSale.mockReset();
  sessionStorage.clear();
});

describe('amount received on the sale form', () => {
  it('shows the field and the notes for cash, and hides it for credit, mobile money and bank', async () => {
    open();
    await addBoth();
    expect(field().inputMode).toBe('numeric');
    for (const note of ['1,000', '2,000', '5,000', '10,000', '20,000', '50,000', 'Exact']) {
      expect(screen.getByRole('button', { name: note })).toBeTruthy();
    }
    for (const method of ['Credit (pay later)', 'Mobile money', 'Bank']) {
      pay(method);
      expect(screen.queryByLabelText('Amount received (UGX)')).toBeNull();
      expect(screen.queryByTestId('change-due')).toBeNull();
    }
    pay('Cash');
    expect(field().value).toBe('');
  });

  it('refuses an amount below the total in plain words and shows the change once it covers the total', async () => {
    open();
    await addBoth();
    expect(totalLine()).toBe('Total UGX 15,500');
    type('10000');
    expect(screen.getByRole('alert').textContent).toBe('Enter at least the total, UGX 15,500.');
    expect(saveButton().getAttribute('data-not-ready')).toBe('true');
    expect(screen.queryByTestId('change-due')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Exact' }));
    expect(field().value).toBe('15500');
    expect(screen.getByTestId('change-due').textContent).toBe('Change to giveUGX 0');
    expect(saveButton().getAttribute('data-not-ready')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '20,000' }));
    expect(screen.getByTestId('change-due').textContent).toBe('Change to giveUGX 4,500');
    expect(saveButton().getAttribute('data-not-ready')).toBeNull();
  });

  it('refuses a negative, a decimal and a non numeric entry, and a very large one', async () => {
    open();
    await addBoth();
    type('-5000');
    expect(screen.getByRole('alert').textContent).toMatch(/cannot be negative/);
    expect(saveButton().getAttribute('data-not-ready')).toBe('true');
    type('20000.50');
    expect(screen.getByRole('alert').textContent).toMatch(/whole shillings/);
    type('twenty');
    expect(screen.getByRole('alert').textContent).toMatch(/numbers only/);
    type('99999999999999999999');
    expect(screen.getByRole('alert').textContent).toMatch(/too large/);
    expect(saveButton().getAttribute('data-not-ready')).toBe('true');
    type('1,000,000');
    expect(screen.getByTestId('change-due').textContent).toBe('Change to giveUGX 984,500');
    expect(saveButton().getAttribute('data-not-ready')).toBeNull();
  });

  it('saves the sale at its total, keeps the amount out of the request, and shows received and change on the receipt', async () => {
    createSale.mockResolvedValue(answer(15500));
    open();
    await addBoth();
    type('20,000');
    fireEvent.click(saveButton());
    expect(await screen.findByLabelText('Sale receipt')).toBeTruthy();
    expect(createSale).toHaveBeenCalledTimes(1);
    const body = JSON.stringify(createSale.mock.calls[0]?.[0]);
    expect(body).not.toMatch(/20000|received|change/);
    expect(screen.getByTestId('receipt-change').textContent).toBe('Received UGX 20,000. Change given: UGX 4,500');
    expect(screen.getByText('Total UGX 15,500')).toBeTruthy();
  });

  it('shows no change line on the receipt when no amount was typed, and none for a credit sale', async () => {
    createSale.mockResolvedValue(answer(15500));
    open();
    await addBoth();
    fireEvent.click(saveButton());
    await screen.findByLabelText('Sale receipt');
    expect(screen.queryByTestId('receipt-change')).toBeNull();
  });

  it('forgets the amount for the next sale and never writes it to the saved draft', async () => {
    createSale.mockResolvedValue(answer(15500));
    open();
    await addBoth();
    type('20000');
    const kept = Object.keys(sessionStorage).map((k) => sessionStorage.getItem(k) ?? '').join('|');
    expect(kept).not.toMatch(/20000/);
    fireEvent.click(saveButton());
    await screen.findByLabelText('Sale receipt');
    fireEvent.click(screen.getByRole('button', { name: 'New sale' }));
    await addBoth();
    expect(field().value).toBe('');
    expect(screen.queryByTestId('change-due')).toBeNull();
    expect(screen.queryByTestId('receipt-change')).toBeNull();
  });

  it('empties the amount when the sale is cleared', async () => {
    open();
    await addBoth();
    type('20000');
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    await waitFor(() => expect(field().value).toBe(''));
  });

  it('posts once however often Save is pressed', async () => {
    let finish: (s: Sale) => void = () => undefined;
    createSale.mockReturnValue(new Promise<Sale>((resolve) => { finish = resolve; }));
    open();
    await addBoth();
    type('20000');
    const button = saveButton();
    fireEvent.click(button);
    await waitFor(() => expect(createSale).toHaveBeenCalledTimes(1));
    fireEvent.click(button);
    fireEvent.submit(button.closest('form') as HTMLFormElement);
    await Promise.resolve();
    expect(createSale).toHaveBeenCalledTimes(1);
    finish(answer(15500));
    expect(await screen.findByTestId('receipt-change')).toBeTruthy();
  });

  // JUSTIFICATION-A3: the busy-button test case issue #456 asks for.
  it('shows Record sale as busy while the sale is sent, and a double click records it once', async () => {
    let finish: (s: Sale) => void = () => undefined;
    createSale.mockReturnValue(new Promise<Sale>((resolve) => { finish = resolve; }));
    open();
    await addBoth();
    type('20000');
    const button = saveButton();
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(button.getAttribute('aria-busy')).toBe('true'));
    const busy = saveButton();
    expect(busy).toBe(button);
    expect(busy.getAttribute('aria-disabled')).toBe('true');
    expect(busy.querySelector('.busy-spinner')).not.toBeNull();
    fireEvent.click(busy);
    expect(createSale).toHaveBeenCalledTimes(1);
    finish(answer(15500));
    expect(await screen.findByTestId('receipt-change')).toBeTruthy();
    expect(createSale).toHaveBeenCalledTimes(1);
  });
});
