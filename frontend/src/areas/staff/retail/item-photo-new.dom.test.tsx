// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Product } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { chooseCategory } from '../../../test-utils/category-picker';

// Issue #617: the tester asked where to upload a picture; the photo was only on Edit, under every branch's stock level. Now the
// Add an item form (Items, a category, and Add it here on Sell, Buy stock and Count stock) takes an optional photo, sent right
// after the item is made, and Edit shows the photo first. Every item, file and amount is fabricated.

vi.setConfig({ testTimeout: 20000 });

const createProduct = vi.fn();
const setProductPhoto = vi.fn();
const getProduct = vi.fn();
let items: Product[] = [];
let found: Product[] = [];
const bulbs = { id: 'c1', name: 'Bulbs', active: true, product_count: 1, version: 3, code_prefix: 'BLB', next_code: 'BLB-0002' };
const made: Product = { id: 'p9', code: 'BLB-0002', description: 'Test bulb 12W', category_id: 'c1', category: 'Bulbs', unit_id: 'u1', unit: 'piece', sell_minor: 9000, cost_minor: 0, active: true, version: 1 };

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to, className }: { children: unknown; to: string; className?: string }) => <a href={to} className={className}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCatalogue: () => Promise.resolve({ items }),
      listProducts: () => Promise.resolve(found),
      listSuppliers: () => Promise.resolve([]),
      listCategories: () => Promise.resolve([bulbs]),
      listUnits: () => Promise.resolve([{ id: 'u1', name: 'piece', active: true, product_count: 1, version: 1 }]),
      similarProducts: () => Promise.resolve({ exact: [], similar: [] }),
      nameHints: () => Promise.resolve({}),
      createProduct: (...a: unknown[]) => createProduct(...a),
      setProductPhoto: (...a: unknown[]) => setProductPhoto(...a),
      getProduct: (...a: unknown[]) => getProduct(...a),
      photo: () => Promise.resolve(new Blob(['x'], { type: 'image/png' })),
      listProductUnits: () => Promise.resolve([]),
      listBarcodes: () => Promise.resolve([]),
      stockMinimums: () => Promise.resolve([]),
    },
  };
});

const { ProductsPage } = await import('./catalogue-products');
const { RestockForm } = await import('./restock');
const { ProductPicker } = await import('./ui');
const { FirstItemsCard } = await import('./first-items');

const owner = mockMe('admin');
const picture = () => new File(['fake image bytes'], 'bulb.jpg', { type: 'image/jpeg' });

beforeEach(() => {
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:fake', revokeObjectURL: () => undefined }));
});

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  items = [];
  found = [];
  createProduct.mockReset();
  setProductPhoto.mockReset();
  getProduct.mockReset();
  vi.unstubAllGlobals();
});

function open(node: JSX.Element, me: Me = owner) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '', chooseBranch: () => undefined }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

/** Opens Add an item on Items and fills it, the photo chosen with `how` when given. */
async function fillItem(how?: 'Take a picture' | 'Choose a photo', file: File = picture()) {
  fireEvent.click(await screen.findByRole('button', { name: 'Add an item' }));
  await chooseCategory(screen.getByLabelText(/^Category/), 'Bulbs');
  fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'Test bulb 12W' } });
  await screen.findByRole('option', { name: 'piece' });
  fireEvent.change(screen.getByLabelText(/^Smallest unit you sell/), { target: { value: 'u1' } });
  fireEvent.change(screen.getByLabelText(/^Selling price/), { target: { value: '9000' } });
  if (how) fireEvent.change(screen.getByLabelText(how), { target: { files: [file] } });
}

describe('a photo while adding an item on Items', () => {
  it('offers Take a picture with the camera and Choose a photo, both optional, PNG, JPEG or WebP', async () => {
    open(<ProductsPage />);
    await fillItem();
    const camera = screen.getByLabelText('Take a picture') as HTMLInputElement;
    const file = screen.getByLabelText('Choose a photo') as HTMLInputElement;
    expect(camera.getAttribute('capture')).toBe('environment');
    expect(file.hasAttribute('capture')).toBe(false);
    for (const input of [camera, file]) {
      expect(input.type).toBe('file');
      expect(input.accept).toBe('image/png,image/jpeg,image/webp');
      expect(input.required).toBe(false);
    }
  });

  it('makes the item, then sends its photo, and the item shows the photo in the list', async () => {
    createProduct.mockImplementation(async () => { items = [made]; return made; });
    setProductPhoto.mockImplementation(async () => { items = [{ ...made, photo_id: 'ph1' }]; return items[0]; });
    open(<ProductsPage />);
    await fillItem('Take a picture');
    expect(screen.getByText('bulb.jpg')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(setProductPhoto).toHaveBeenCalled());
    expect(createProduct).toHaveBeenCalledTimes(1);
    expect(createProduct.mock.invocationCallOrder[0]!).toBeLessThan(setProductPhoto.mock.invocationCallOrder[0]!);
    expect(setProductPhoto.mock.calls[0]?.[0]).toBe('p9');
    expect((setProductPhoto.mock.calls[0]?.[1] as File).name).toBe('bulb.jpg');
    expect((await screen.findAllByText('Test bulb 12W added.')).length).toBeGreaterThan(0);
    expect(await screen.findByRole('img', { name: 'Photo of Test bulb 12W' })).toBeTruthy();
  });

  it('keeps the item when the photo upload fails, and says the photo can be added from Edit', async () => {
    createProduct.mockImplementation(async () => { items = [made]; return made; });
    setProductPhoto.mockRejectedValue(new Error('too slow'));
    open(<ProductsPage />);
    await fillItem('Choose a photo');
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    const said = 'Test bulb 12W added. The photo was not saved. Add it from Edit on the item.';
    expect((await screen.findAllByText(said)).length).toBeGreaterThan(0);
    expect(createProduct).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole('button', { name: 'Edit Test bulb 12W' })).toBeTruthy();
  });

  it('sends no photo when none is chosen, and refuses a file that is not a photo before anything is sent', async () => {
    createProduct.mockResolvedValue(made);
    open(<ProductsPage />);
    await fillItem('Choose a photo', new File(['gif'], 'moving.gif', { type: 'image/gif' }));
    expect(screen.getByRole('alert').textContent).toBe('Choose a photo: a PNG, JPEG or WebP picture.');
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalled());
    expect(setProductPhoto).not.toHaveBeenCalled();
  });
});

describe('the photo on Edit', () => {
  it('comes first, under the name, before Inactive, the packs, the barcodes and the stock levels', async () => {
    items = [made];
    getProduct.mockResolvedValue(made);
    open(<ProductsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit Test bulb 12W' }));
    const photo = await screen.findByLabelText('Add a photo');
    const form = photo.closest('form') as HTMLFormElement;
    const description = within(form).getByLabelText(/^Description/);
    const inactive = within(form).getByLabelText(/Inactive/);
    expect(description.compareDocumentPosition(photo) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(photo.compareDocumentPosition(inactive) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // Every editor after the name sits below the photo.
    const editors = [...form.querySelectorAll('fieldset, section, .rt-card, details')];
    for (const editor of editors) expect(photo.compareDocumentPosition(editor) & Node.DOCUMENT_POSITION_FOLLOWING, editor.className).toBeTruthy();
  });
});

describe('a photo with Add it here', () => {
  it('sends the photo of an item added while buying stock, and keeps the item with a plain word when it fails', async () => {
    createProduct.mockResolvedValue(made);
    setProductPhoto.mockRejectedValue(new Error('down'));
    open(<RestockForm />);
    fireEvent.click(screen.getByRole('button', { name: 'Not in the list? Add it here' }));
    const form = screen.getByRole('group', { name: 'Add a new item' });
    await chooseCategory(within(form).getByLabelText(/^Category/), 'Bulbs');
    fireEvent.change(within(form).getByLabelText(/^Description/), { target: { value: 'Test bulb 12W' } });
    await within(form).findByRole('option', { name: 'piece' });
    fireEvent.change(within(form).getByLabelText(/^Counted in/), { target: { value: 'u1' } });
    fireEvent.change(within(form).getByLabelText(/^Buying price/), { target: { value: '6000' } });
    fireEvent.change(within(form).getByLabelText(/^Selling price/), { target: { value: '9000' } });
    const qty = within(form).queryByLabelText(/^Quantity bought/);
    if (qty) fireEvent.change(qty, { target: { value: '3' } });
    fireEvent.change(within(form).getByLabelText('Take a picture'), { target: { files: [picture()] } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save the item' }));
    await waitFor(() => expect(setProductPhoto).toHaveBeenCalledWith('p9', expect.any(File)));
    expect(await screen.findByText('Test bulb 12W is saved. The photo was not saved. Add it from Edit on the item.')).toBeTruthy();
    expect(createProduct).toHaveBeenCalledTimes(1);
  });

  it('puts the item on the purchase before its photo is sent, so a slow upload never holds the line (review of #616)', async () => {
    createProduct.mockResolvedValue(made);
    let upload: (value: unknown) => void = () => undefined;
    setProductPhoto.mockReturnValue(new Promise((resolve) => { upload = resolve; }));
    open(<RestockForm />);
    fireEvent.click(screen.getByRole('button', { name: 'Not in the list? Add it here' }));
    const form = screen.getByRole('group', { name: 'Add a new item' });
    await chooseCategory(within(form).getByLabelText(/^Category/), 'Bulbs');
    fireEvent.change(within(form).getByLabelText(/^Description/), { target: { value: 'Test bulb 12W' } });
    await within(form).findByRole('option', { name: 'piece' });
    fireEvent.change(within(form).getByLabelText(/^Counted in/), { target: { value: 'u1' } });
    fireEvent.change(within(form).getByLabelText(/^Buying price/), { target: { value: '6000' } });
    fireEvent.change(within(form).getByLabelText(/^Selling price/), { target: { value: '9000' } });
    const qty = within(form).queryByLabelText(/^Quantity bought/);
    if (qty) fireEvent.change(qty, { target: { value: '3' } });
    fireEvent.change(within(form).getByLabelText('Take a picture'), { target: { files: [picture()] } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save the item' }));
    await waitFor(() => expect(setProductPhoto).toHaveBeenCalledWith('p9', expect.any(File)));
    // The upload has not answered, and the form is closed with the item already a line of the purchase.
    expect(screen.queryByRole('group', { name: 'Add a new item' })).toBeNull();
    expect(screen.getByText('Test bulb 12W')).toBeTruthy();
    upload({ id: 'ph1' });
  });
});

describe('the photo in the Sell search (review of #616, the add-a-photo article)', () => {
  it('shows the photo of an item that has one when it is found to put on a sale', async () => {
    found = [{ ...made, photo_id: 'ph1', qty: '3.000' }];
    open(<ProductPicker id="sale-search" branchId={owner.branches?.[0]?.id} showPrice onAdd={() => undefined} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'Test bulb' } });
    expect(await screen.findByRole('img', { name: 'Photo of Test bulb 12W' })).toBeTruthy();
  });
});

describe('the card of a shop with no items (review of #616)', () => {
  it('goes once an item is added on Items, not only through Add it here on Sell', async () => {
    createProduct.mockImplementation(async () => { items = [made]; return made; });
    open(<><FirstItemsCard /><ProductsPage /></>);
    expect(await screen.findByRole('heading', { name: 'Get your shop ready' })).toBeTruthy();
    await fillItem();
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Get your shop ready' })).toBeNull());
  });
});
