// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { PICKER_LABEL, pickItem } from '../../../test-utils/item-picker';

// Issue #815, as the owner's old app warned: the item picker and the sale line of Record sale say Out of stock at zero and
// Only N left at or below the item's low stock level here, as a tinted badge with an icon and the words, never colour alone;
// above the level nothing is said. Offline, the same words come from the copy saved on the phone. Selling past the stock is
// not stopped by these words. Fabricated items and quantities.

vi.setConfig({ testTimeout: 20000 });

const listProducts = vi.fn();
const createSale = vi.fn();
const findByBarcode = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      listProducts: (...a: unknown[]) => listProducts(...a),
      listCustomers: () => Promise.resolve([]),
      createSale: (...a: unknown[]) => createSale(...a),
      findByBarcode: (...a: unknown[]) => findByBarcode(...a),
    },
  };
});

const { RecordSale } = await import('./sale');
const { OfflineProvider } = await import('../../../offline/offline-context');
const { testOffline } = await import('../../../offline/test-kit');
const { stockWarning } = await import('./sale-state');
const me = mockMe('sales');
const branch = me.branches?.[0]?.id ?? '';

const item = (code: string, description: string, qty: string): Product => ({
  id: `id-${code}`, code, description, unit: 'piece', category: 'Test Lighting', sell_minor: 1000, active: true, qty, low_stock_level: '10.000',
});
const none = item('W0', 'Test Bulb None', '0.000');
const atLevel = item('W10', 'Test Bulb Level', '10.000');
const above = item('W11', 'Test Bulb Plenty', '11.000');
const owed = item('WN', 'Test Bulb Owed', '-2.000');

function open(offline?: ReturnType<typeof testOffline>) {
  const sale = <RecordSale />;
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined }}>
        {offline ? <OfflineProvider value={offline}>{sale}</OfflineProvider> : sale}
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const option = (name: string) => screen.findByRole('option', { name: new RegExp(`^${name}`) });
const lineOf = (name: string) => screen.getAllByText(name, { selector: '.line-card strong' })[0]?.closest('.line-card') as HTMLElement;

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  listProducts.mockReset();
  createSale.mockReset();
  findByBarcode.mockReset();
});

describe('the words', () => {
  it('is Out of stock at zero or below, Only N left at or below the level, and nothing above it', () => {
    expect(stockWarning(none)).toBe('Out of stock');
    expect(stockWarning(owed)).toBe('Out of stock');
    expect(stockWarning(atLevel)).toBe('Only 10 left');
    expect(stockWarning({ qty: '2.500', low_stock_level: '5.000' })).toBe('Only 2.5 left');
    expect(stockWarning(above)).toBeNull();
    // A copy saved before the level was sent still says Out of stock; with no stock read nothing is said.
    expect(stockWarning({ qty: '0.000' })).toBe('Out of stock');
    expect(stockWarning({ qty: '3.000' })).toBeNull();
    expect(stockWarning({})).toBeNull();
  });
});

describe('Record sale, online', () => {
  it('says it on each match of the item picker, in words with an icon', async () => {
    listProducts.mockResolvedValue([none, atLevel, above]);
    open();
    fireEvent.change(await screen.findByRole('combobox', { name: PICKER_LABEL }), { target: { value: 'test bulb' } });
    const out = await option('Test Bulb None');
    expect(within(out).getByTestId('stock-warning').textContent).toBe('Out of stock');
    expect(within(out).getByTestId('stock-warning').className).toContain('badge-danger');
    expect(within(out).getByTestId('stock-warning').querySelector('svg')).not.toBeNull();
    const low = await option('Test Bulb Level');
    expect(within(low).getByTestId('stock-warning').textContent).toBe('Only 10 left');
    expect(within(low).getByTestId('stock-warning').className).toContain('badge-warning');
    expect(within(low).getByTestId('stock-warning').querySelector('svg')).not.toBeNull();
    expect(within(await option('Test Bulb Plenty')).queryByTestId('stock-warning')).toBeNull();
  });

  it('says it on the sale line too, and still lets the item go on the sale', async () => {
    listProducts.mockResolvedValue([none, atLevel, above]);
    open();
    await pickItem('Test Bulb None', { typed: 'test bulb' });
    await pickItem('Test Bulb Level', { typed: 'test bulb' });
    await pickItem('Test Bulb Plenty', { typed: 'test bulb' });
    await waitFor(() => expect(document.getElementById('qty-2')).not.toBeNull());
    expect(within(lineOf('Test Bulb None')).getByTestId('stock-warning').textContent).toBe('Out of stock');
    expect(within(lineOf('Test Bulb Level')).getByTestId('stock-warning').textContent).toBe('Only 10 left');
    expect(within(lineOf('Test Bulb Plenty')).queryByTestId('stock-warning')).toBeNull();
    // The words do not stop the sale: the button stays as it is with any item on it.
    expect((screen.getByRole('button', { name: /^Record sale/ }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('says it on the line of a scanned item, from the barcode lookup with the shop', async () => {
    findByBarcode.mockResolvedValue({ product: { ...atLevel, qty: '3.000' }, barcode: { id: 'b1', product_id: atLevel.id, code: 'IN00008151', format: 'code128', generated: true } });
    open();
    const box = await screen.findByLabelText('Scan here');
    fireEvent.change(box, { target: { value: 'IN00008151' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(document.getElementById('qty-0')).not.toBeNull());
    expect(findByBarcode).toHaveBeenCalledWith('IN00008151', branch);
    expect(within(lineOf('Test Bulb Level')).getByTestId('stock-warning').textContent).toBe('Only 3 left');
  });
});

describe('Record sale, offline', () => {
  it('says it from the copy saved on the phone, in the picker and on the line', async () => {
    const offline = testOffline(me, { online: false, catalogue: { pricesAsOf: '2026-10-22T09:14:00.000Z', products: { [branch]: [none, atLevel, above] }, customers: [] } });
    if (!offline) throw new Error('a seller sells offline');
    open(offline);
    expect(await screen.findByTestId('offline-note')).toBeTruthy();
    fireEvent.change(screen.getByRole('combobox', { name: PICKER_LABEL }), { target: { value: 'test bulb' } });
    expect(within(await option('Test Bulb None')).getByTestId('stock-warning').textContent).toBe('Out of stock');
    expect(within(await option('Test Bulb Level')).getByTestId('stock-warning').textContent).toBe('Only 10 left');
    expect(within(await option('Test Bulb Plenty')).queryByTestId('stock-warning')).toBeNull();
    fireEvent.click(await option('Test Bulb Level'));
    await waitFor(() => expect(document.getElementById('qty-0')).not.toBeNull());
    expect(within(lineOf('Test Bulb Level')).getByTestId('stock-warning').textContent).toBe('Only 10 left');
    expect(listProducts).not.toHaveBeenCalled();
  });
});
