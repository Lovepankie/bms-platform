// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product, Sale } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { PICKER_LABEL, pickItem } from '../../../test-utils/item-picker';

// Issue #534 on the retail screens: the item picker lists items only while something is typed, with what a seller needs on
// each option and no grouped browse list; and on the sale-complete screen Share receipt is the main action, with New sale
// under the receipt actions. Fabricated items, people and amounts.

vi.setConfig({ testTimeout: 20000 });

const listProducts = vi.fn();
const createSale = vi.fn();
const saleReceipt = vi.fn((..._a: unknown[]) => Promise.resolve({ receipt_no: 'R-TEST-1', buyer_name: 'Test Buyer' }));
const bulb: Product = { id: 'p1', code: 'P003', description: 'Test Bulb 9W', unit: 'piece', category: 'Lighting', sell_minor: 6000, active: true, qty: '12.000' };
const lamp: Product = { id: 'p2', code: 'P004', description: 'Test Lamp 15W', unit: 'piece', category: 'Lighting', sell_minor: 39000, active: true, qty: '-2.000' };

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      listProducts: (...a: unknown[]) => listProducts(...a),
      listCustomers: () => Promise.resolve([]),
      createSale: (...a: unknown[]) => createSale(...a),
      saleReceipt: (...a: unknown[]) => saleReceipt(...a),
    },
  };
});

const { RecordSale } = await import('./sale');
const { ProductPicker } = await import('./ui');
const me = mockMe('sales');
const branch = me.branches?.[0]?.id ?? '';

function open(node = <RecordSale />) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const box = () => screen.getByRole('combobox', { name: PICKER_LABEL }) as HTMLInputElement;
// The picker's matches, not the shop switcher's <option>s under the page title (issue #680).
const matches = () => screen.queryAllByRole('option').filter((o) => o.tagName !== 'OPTION');

function sold(extra: Partial<Sale> = {}): Sale {
  return { id: 's1', branch_id: branch, sale_no: 'S-1', sale_date: '2026-10-24', payment_method: 'cash', status: 'completed', total_minor: 6000, paid_minor: 6000, balance_minor: 0, lines: [{ product_id: 'p1', description: 'Test Bulb 9W', qty: '1.000', unit_price_minor: 6000, line_total_minor: 6000 }], ...extra };
}

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  listProducts.mockReset();
  createSale.mockReset();
  saleReceipt.mockClear();
});

describe('the item picker on the retail screens', () => {
  it('asks the server nothing and shows no list until something is typed, and has no grouped browse list', async () => {
    listProducts.mockResolvedValue([bulb, lamp]);
    open();
    await screen.findByRole('combobox', { name: PICKER_LABEL });
    await new Promise((r) => setTimeout(r, 300));
    expect(listProducts).not.toHaveBeenCalled();
    expect(matches()).toEqual([]);
    expect(screen.queryByText('Group by category')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Expand all' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Collapse all' })).toBeNull();
  });

  it('shows the name, code, category, price and the stock here on each option, with a negative balance flagged', async () => {
    listProducts.mockResolvedValue([bulb, lamp]);
    open(<ProductPicker id="t-search" branchId={branch} showPrice onAdd={() => undefined} />);
    fireEvent.change(box(), { target: { value: 'test' } });
    const lampOption = await screen.findByRole('option', { name: /^Test Lamp 15W/ });
    expect(lampOption.textContent).toContain('(P004)');
    expect(lampOption.textContent).toContain('Lighting, UGX 39,000 each, in stock here: -2 (negative) piece');
    expect(lampOption.querySelector('.rt-flag')?.textContent).toBe('-2 (negative)');
    expect(listProducts).toHaveBeenLastCalledWith(expect.objectContaining({ query: 'test', branchId: branch }));
  });

  it('on Record a sale adds the line, empties and closes the list and leaves the cursor in the search box', async () => {
    listProducts.mockResolvedValue([bulb, lamp]);
    open();
    await pickItem('Test Bulb 9W', { typed: 'bulb' });
    await waitFor(() => expect(document.getElementById('qty-0')).not.toBeNull());
    expect(box().value).toBe('');
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(document.activeElement).toBe(box());
  });

  it('never shows or picks the matches of the earlier search while the next one is on its way', async () => {
    let answer: (rows: Product[]) => void = () => undefined;
    listProducts.mockImplementation(({ query }: { query: string }) => (query === 'bulb' ? Promise.resolve([bulb]) : new Promise((r) => (answer = r))));
    open();
    await pickItem('Test Bulb 9W', { typed: 'bulb' });
    fireEvent.change(box(), { target: { value: 'lamp' } });
    await waitFor(() => expect(listProducts).toHaveBeenLastCalledWith(expect.objectContaining({ query: 'lamp' })));
    expect(matches()).toHaveLength(0);
    fireEvent.keyDown(box(), { key: 'ArrowDown' });
    fireEvent.keyDown(box(), { key: 'Enter' });
    expect(document.getElementById('qty-1')).toBeNull();
    answer([lamp]);
    expect(await screen.findByRole('option', { name: /^Test Lamp 15W/ })).toBeTruthy();
  });

  it('keeps a cached answer in view while it is refreshed in the background (review of #535)', async () => {
    listProducts.mockResolvedValueOnce([bulb]).mockImplementation(() => new Promise(() => {}));
    open();
    await pickItem('Test Bulb 9W', { typed: 'bulb' });
    fireEvent.change(box(), { target: { value: 'bulb' } });
    expect(await screen.findByRole('option', { name: /^Test Bulb 9W/ })).toBeTruthy();
    expect(listProducts).toHaveBeenCalledTimes(2);
  });

  it('says plainly when no item matches what was typed', async () => {
    listProducts.mockResolvedValue([]);
    open();
    fireEvent.change(await screen.findByRole('combobox', { name: PICKER_LABEL }), { target: { value: 'fridge' } });
    expect(await screen.findByText('No item matches "fridge".', { selector: '.cb-empty' })).toBeTruthy();
  });
});

describe('the sale-complete screen', () => {
  async function finish(sale: Sale) {
    listProducts.mockResolvedValue([bulb]);
    createSale.mockResolvedValue(sale);
    open();
    await pickItem('Test Bulb 9W', { typed: 'bulb' });
    fireEvent.click(screen.getByRole('button', { name: /^Record sale/ }));
    await screen.findByLabelText('Sale receipt');
  }

  it('puts Share receipt first as the one main action, and New sale below the receipt actions', async () => {
    await finish(sold());
    const share = await screen.findByRole('button', { name: 'Share receipt' });
    const next = screen.getByRole('button', { name: 'New sale' });
    const whatsApp = screen.getByRole('button', { name: 'WhatsApp customer' });
    expect(share.compareDocumentPosition(next) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(whatsApp.compareDocumentPosition(next) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(share.compareDocumentPosition(screen.getByRole('button', { name: 'View receipt' })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect([...document.querySelectorAll('.rt-primary')]).toEqual([share]);
    expect(next.classList.contains('rt-next')).toBe(true);
  });

  it('keeps New sale the main action while Share receipt is not drawn yet, or when its facts cannot be read', async () => {
    saleReceipt.mockImplementationOnce(() => new Promise(() => {}));
    await finish(sold());
    expect(screen.queryByRole('button', { name: 'Share receipt' })).toBeNull();
    expect(screen.getByRole('button', { name: 'New sale' }).classList.contains('rt-primary')).toBe(true);
    cleanup();
    saleReceipt.mockImplementationOnce(() => Promise.reject(new Error('offline')));
    await finish(sold());
    await waitFor(() => expect(saleReceipt).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('button', { name: 'Share receipt' })).toBeNull();
    expect(screen.getByRole('button', { name: 'New sale' }).classList.contains('rt-primary')).toBe(true);
  });

  it('keeps New sale the main action when the sale has no receipt to share', async () => {
    await finish(sold({ status: 'voided' }));
    expect(screen.queryByRole('button', { name: 'Share receipt' })).toBeNull();
    expect(screen.getByRole('button', { name: 'New sale' }).classList.contains('rt-primary')).toBe(true);
  });

  it('starts the next sale from New sale with an empty search box', async () => {
    await finish(sold());
    fireEvent.click(screen.getByRole('button', { name: 'New sale' }));
    expect((await screen.findByRole('combobox', { name: PICKER_LABEL }) as HTMLInputElement).value).toBe('');
    expect(screen.queryByLabelText('Sale receipt')).toBeNull();
  });
});
