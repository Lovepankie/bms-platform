// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { type Product, type StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { chooseCategory } from '../../../test-utils/category-picker';

// Issue #505: under the item search on Buy stock and Count stock, "Not in the list? Add it here" opens a short form in the
// AppSheet order and one Save makes the item and puts it on the purchase or the count. Only retail.catalogue.manage sees it;
// the name rules stay the server's and show inline; offline is refused in plain words. Issue #562: the Item code is the app's,
// shown as text and never typed or sent. Fabricated items and amounts.

vi.setConfig({ testTimeout: 20000 });

const createProduct = vi.fn();
const createCategory = vi.fn();
const similarProducts = vi.fn();
const getProduct = vi.fn();
const listStock = vi.fn();
const bulbs = { id: 'c1', name: 'Bulbs', active: true, product_count: 1, version: 3, code_prefix: 'BLB', next_code: 'BLB-0002' };
// What the server lists; an inline add (#578) puts its category here, as the server would.
let cats: Record<string, unknown>[] = [bulbs];
const made: Product = { id: 'p9', code: 'BLB-0002', description: 'Test bulb 12W', category_id: 'c1', category: 'Bulbs', unit_id: 'u1', unit: 'piece', sell_minor: 9000, cost_minor: 6000, active: true, version: 1 };

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve(cats),
      createCategory: (...a: unknown[]) => createCategory(...a),
      suggestCategoryPrefix: () => Promise.resolve('RCE'),
      listUnits: () => Promise.resolve([{ id: 'u1', name: 'piece', active: true, product_count: 1, version: 1 }]),
      listSuppliers: () => Promise.resolve([]),
      listProducts: () => Promise.resolve([]),
      similarProducts: (...a: unknown[]) => similarProducts(...a),
      createProduct: (...a: unknown[]) => createProduct(...a),
      getProduct: (...a: unknown[]) => getProduct(...a),
      listStock: (...a: unknown[]) => listStock(...a),
    },
  };
});

const { RestockForm } = await import('./restock');
const { StocktakePage } = await import('./stocktake');

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  // Count stock keeps a count on the device (issue #681), so each test starts with none.
  localStorage.clear();
  createProduct.mockReset();
  createCategory.mockReset();
  cats = [bulbs];
  similarProducts.mockReset();
  getProduct.mockReset();
  listStock.mockReset();
  vi.restoreAllMocks();
});

function open(node: JSX.Element, me: Me = mockMe('admin')) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const form = () => screen.getByRole('group', { name: 'Add a new item' });
const field = (label: RegExp) => within(form()).getByLabelText(label) as HTMLInputElement;

/** Opens the form and fills every field in the AppSheet order. */
async function fill(qtyLabel: RegExp, qty: string) {
  fireEvent.click(screen.getByRole('button', { name: 'Not in the list? Add it here' }));
  await within(form()).findByRole('option', { name: 'piece' });
  await chooseCategory(field(/^Category/), 'Bulbs');
  fireEvent.change(field(/^Description/), { target: { value: 'Test bulb 12W' } });
  fireEvent.change(field(/^Counted in/), { target: { value: 'u1' } });
  fireEvent.change(field(/^Buying price/), { target: { value: '6000' } });
  fireEvent.change(field(/^Selling price/), { target: { value: '9000' } });
  fireEvent.change(field(qtyLabel), { target: { value: qty } });
}

const save = () => fireEvent.click(within(form()).getByRole('button', { name: 'Save the item' }));

describe('Buy stock: add an item that is not in the list', () => {
  it('adds a category inline: the field names it and the code line gives its first code (#578)', async () => {
    const rice = { id: 'c9', name: 'Rice', active: true, product_count: 0, version: 1, code_prefix: 'RCE', next_code: 'RCE-0001' };
    createCategory.mockImplementation(async () => { cats = [...cats, rice]; return rice; });
    open(<RestockForm />);
    fireEvent.click(screen.getByRole('button', { name: 'Not in the list? Add it here' }));
    await within(form()).findByRole('option', { name: 'piece' });
    fireEvent.click(field(/^Category/));
    fireEvent.click(await screen.findByRole('button', { name: 'Add a new category' }));
    fireEvent.change(screen.getByLabelText(/^Name of the new category/), { target: { value: 'Rice' } });
    expect(await screen.findByText('RCE', { selector: 'strong' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add category' }));
    await waitFor(() => expect(field(/^Category/).textContent).toBe('Rice'));
    expect(within(form()).getByTestId('restock-new-code').textContent).toBe('Item code: RCE-0001, made by the app');
    expect(createCategory).toHaveBeenCalledWith('Rice');
    // Buy stock's own toast says it, once: the picker draws none of its own.
    expect([...document.querySelectorAll('.toast')].map((t) => t.textContent)).toEqual(['Added the category Rice. Its item codes start with RCE.']);
    expect(createProduct).not.toHaveBeenCalled();
  });

  it('shows the fields in the AppSheet order, the code as text made by the app and the category taking the cursor', async () => {
    open(<RestockForm />);
    fireEvent.click(screen.getByRole('button', { name: 'Not in the list? Add it here' }));
    await within(form()).findByRole('option', { name: 'piece' });
    const labels = [...form().querySelectorAll('label')].map((l) => l.textContent?.replace(/\s*\*$/, '').trim());
    // Issue #617: the optional photo comes last, Take a picture or Choose a photo.
    expect(labels).toEqual(['Category', 'Description', 'Counted in', 'Buying price (UGX)', 'Selling price (UGX)', expect.stringMatching(/^Quantity bought/), 'Take a picture', 'Choose a photo']);
    expect(document.activeElement).toBe(field(/^Category/));
    // Issue #562: the code comes first, as text, never a field.
    const code = within(form()).getByTestId('restock-new-code');
    expect(code.compareDocumentPosition(field(/^Category/)) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(code.textContent).toBe('Item code: made by the app once you choose the category');
    expect(within(form()).queryByLabelText(/Item code/)).toBeNull();
    await chooseCategory(field(/^Category/), 'Bulbs');
    expect(code.textContent).toBe('Item code: BLB-0002, made by the app');
    expect(within(form()).getAllByRole('button', { name: 'Save the item' })).toHaveLength(1);
  });

  it('marks the needed fields with a red asterisk and aria-required, and a Save with gaps goes to the first', async () => {
    open(<RestockForm />);
    fireEvent.click(screen.getByRole('button', { name: 'Not in the list? Add it here' }));
    await within(form()).findByRole('option', { name: 'piece' });
    expect(within(form()).getByText(/Fields marked/)).toBeTruthy();
    for (const label of [/^Category/, /^Description/, /^Counted in/, /^Buying price/, /^Selling price/, /^Quantity bought/]) {
      const control = field(label);
      expect(control.getAttribute('aria-required'), String(label)).toBe('true');
      expect(form().querySelector(`label[for="${control.id}"] .field-star`)?.getAttribute('aria-hidden')).toBe('true');
    }
    await chooseCategory(field(/^Category/), 'Bulbs');
    save();
    expect(document.activeElement).toBe(field(/^Description/));
    expect(within(form()).getByText('Type what the item is.')).toBeTruthy();
    expect(createProduct).not.toHaveBeenCalled();
  });

  it('creates the item and puts it on the purchase with its quantity in one save, leaving the code to the app', async () => {
    similarProducts.mockResolvedValue({ exact: [], similar: [] });
    createProduct.mockResolvedValue(made);
    open(<RestockForm />);
    await fill(/^Quantity bought/, '12');
    save();
    await screen.findByText('Test bulb 12W', { selector: 'strong' });
    expect(createProduct).toHaveBeenCalledTimes(1);
    expect(createProduct.mock.calls[0]?.[0]).toEqual({ description: 'Test bulb 12W', category_id: 'c1', unit_id: 'u1', sell_minor: 9000, cost_minor: 6000 });
    expect(screen.queryByRole('group', { name: 'Add a new item' })).toBeNull();
    const branch = mockMe('admin').branches?.[0]?.id ?? '';
    expect((document.getElementById('cost-0') as HTMLInputElement).value).toBe('6000');
    expect((document.getElementById('sell-0') as HTMLInputElement).value).toBe('9000');
    expect((document.getElementById(`q-0-${branch}`) as HTMLInputElement).value).toBe('12');
  });

  it('warns inline that the name exists, and Use this item puts the existing one on the purchase', async () => {
    similarProducts.mockResolvedValue({ exact: [{ id: 'p1', code: 'BLB-0001', description: 'Test bulb 12W', unit: 'piece' }], similar: [] });
    getProduct.mockResolvedValue({ ...made, id: 'p1', code: 'BLB-0001', sell_minor: 8500 });
    open(<RestockForm />);
    await fill(/^Quantity bought/, '3');
    save();
    const warning = await within(form()).findByTestId('name-exists');
    expect(warning.textContent).toMatch(/This product already exists: Test bulb 12W \(BLB-0001\)/);
    expect(createProduct).not.toHaveBeenCalled();
    fireEvent.click(within(warning).getByRole('button', { name: 'Use this item' }));
    await waitFor(() => expect(document.getElementById('cost-0')).not.toBeNull());
    expect(createProduct).not.toHaveBeenCalled();
    expect((document.getElementById('sell-0') as HTMLInputElement).value).toBe('8500');
    expect((document.getElementById('cost-0') as HTMLInputElement).value).toBe('6000');
  });

  it('warns inline about a similar name, and saves only when told it is a different item', async () => {
    similarProducts.mockResolvedValue({ exact: [], similar: [{ id: 'p2', code: 'BLB-0001', description: 'Test bulb 12 W', unit: 'piece', distance: 1 }] });
    createProduct.mockResolvedValue(made);
    open(<RestockForm />);
    await fill(/^Quantity bought/, '4');
    save();
    const warning = await within(form()).findByTestId('name-similar');
    expect(warning.textContent).toMatch(/similar name/);
    expect(createProduct).not.toHaveBeenCalled();
    fireEvent.click(within(warning).getByRole('button', { name: 'This is a different item, save it' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
  });

  it('refuses offline in plain words and sends nothing, also when the connection went after the form was filled', async () => {
    open(<RestockForm />);
    await fill(/^Quantity bought/, '5');
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    save();
    expect(within(form()).getByTestId('restock-new-offline').textContent).toBe('You are offline. Adding a new item needs the internet. Connect, then tap Save the item again.');
    expect(similarProducts).not.toHaveBeenCalled();
    expect(createProduct).not.toHaveBeenCalled();
  });

  it('refuses in the same words when the connection drops during the save', async () => {
    similarProducts.mockRejectedValue(new TypeError('Failed to fetch'));
    open(<RestockForm />);
    await fill(/^Quantity bought/, '5');
    save();
    expect((await within(form()).findByTestId('restock-new-offline')).textContent).toMatch(/You are offline/);
    expect(createProduct).not.toHaveBeenCalled();
  });

  // Review of #552, must-fix 1 and 2: an item already on the purchase keeps its line and gets the quantity added, as a scan adds,
  // and a quantity is never replaced, by a number or by nothing.
  const onThePurchase = (qty: string) => ({
    supplierId: '', newSupplier: '', purchasedOn: '2026-10-24', method: 'cash' as const,
    lines: [{ product: { ...made, id: 'p1', code: 'BLB-0001', sell_minor: 8500 }, cost: '6000', sell: '8500', qtyByBranch: { [mockMe('admin').branches?.[0]?.id ?? '']: qty } }],
  });

  it('adds the quantity to the line of an item already on the purchase when Use this item is chosen, once however often it is tapped', async () => {
    similarProducts.mockResolvedValue({ exact: [{ id: 'p1', code: 'BLB-0001', description: 'Test bulb 12W', unit: 'piece' }], similar: [] });
    let answer: (p: Product) => void = () => undefined;
    getProduct.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
    open(<RestockForm draftName="restock-test-existing" initial={onThePurchase('10')} />);
    await fill(/^Quantity bought/, '3');
    save();
    const use = within(await within(form()).findByTestId('name-exists')).getByRole('button', { name: 'Use this item' });
    fireEvent.click(use);
    fireEvent.click(use);
    await waitFor(() => expect(getProduct).toHaveBeenCalled());
    answer({ ...made, id: 'p1', code: 'BLB-0001', sell_minor: 8500 });
    await waitFor(() => expect(screen.queryByRole('group', { name: 'Add a new item' })).toBeNull());
    const branch = mockMe('admin').branches?.[0]?.id ?? '';
    expect(getProduct).toHaveBeenCalledTimes(1);
    expect(document.getElementById('cost-1')).toBeNull();
    expect((document.getElementById(`q-0-${branch}`) as HTMLInputElement).value).toBe('13');
    expect(document.activeElement?.textContent).toBe('Not in the list? Add it here');
  });

  it('never empties a quantity: without a branch at the top the form asks for none and the line keeps its own', async () => {
    similarProducts.mockResolvedValue({ exact: [{ id: 'p1', code: 'BLB-0001', description: 'Test bulb 12W', unit: 'piece' }], similar: [] });
    getProduct.mockResolvedValue({ ...made, id: 'p1', code: 'BLB-0001' });
    const me = mockMe('admin');
    const branch = me.branches?.[0]?.id ?? '';
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: '' }}><RestockForm draftName="restock-test-nobranch" initial={onThePurchase('10')} /></StaffContext.Provider>
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Not in the list? Add it here' }));
    await within(form()).findByRole('option', { name: 'piece' });
    expect(within(form()).queryByLabelText(/^Quantity bought/)).toBeNull();
    await chooseCategory(field(/^Category/), 'Bulbs');
    fireEvent.change(field(/^Description/), { target: { value: 'Test bulb 12W' } });
    fireEvent.change(field(/^Counted in/), { target: { value: 'u1' } });
    fireEvent.change(field(/^Buying price/), { target: { value: '6000' } });
    fireEvent.change(field(/^Selling price/), { target: { value: '9000' } });
    save();
    fireEvent.click(within(await within(form()).findByTestId('name-exists')).getByRole('button', { name: 'Use this item' }));
    await waitFor(() => expect(screen.queryByRole('group', { name: 'Add a new item' })).toBeNull());
    expect((document.getElementById(`q-0-${branch}`) as HTMLInputElement).value).toBe('10');
    expect(document.getElementById('cost-1')).toBeNull();
  });

  it('does not check the name again on Enter while a name warning is open', async () => {
    similarProducts.mockResolvedValue({ exact: [], similar: [{ id: 'p2', code: 'BLB-0001', description: 'Test bulb 12 W', unit: 'piece', distance: 1 }] });
    open(<RestockForm />);
    await fill(/^Quantity bought/, '4');
    save();
    await within(form()).findByTestId('name-similar');
    fireEvent.keyDown(field(/^Selling price/), { key: 'Enter' });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(similarProducts).toHaveBeenCalledTimes(1);
    expect(createProduct).not.toHaveBeenCalled();
  });

  it('is not offered without retail.catalogue.manage: the person is told to ask their manager', async () => {
    const seller = mockMe('admin');
    open(<RestockForm />, { ...seller, permissions: (seller.permissions ?? []).filter((p) => p !== 'retail.catalogue.manage') });
    expect(screen.queryByRole('button', { name: 'Not in the list? Add it here' })).toBeNull();
    expect(screen.getByTestId('restock-new-ask').textContent).toBe('Not in the list? Ask your manager to add this item.');
  });
});

describe('Count stock: add an item for its first count', () => {
  const row = (r: Partial<StockRow> & { product_id: string }): StockRow => ({ code: r.product_id, category: 'Bulbs', unit: 'piece', qty: '0.000', negative: false, sell_minor: 1500, ...r });

  it('creates the item and puts it on the count sheet with its count in one save', async () => {
    let sheet = [row({ product_id: 'p1', description: 'Test bulb 9W', qty: '5.000' })];
    listStock.mockImplementation(() => Promise.resolve(sheet));
    similarProducts.mockResolvedValue({ exact: [], similar: [] });
    createProduct.mockImplementation(() => {
      sheet = [...sheet, row({ product_id: 'p9', code: 'BLB-0002', description: 'Test bulb 12W' })];
      return Promise.resolve(made);
    });
    open(<StocktakePage />);
    await screen.findByText('Not counted yet (1)', { exact: false });
    await fill(/^How many you counted/, '7');
    save();
    await waitFor(() => expect((document.getElementById('count-p9') as HTMLInputElement | null)?.value).toBe('7'));
    expect(createProduct).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('heading', { name: 'Counted items (1)' })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Review the difference \(1 counted\)/ })).toBeTruthy();
  });

  // Review of #552, must-fix 3: an item already counted gets the count added, and one this branch's sheet does not hold is refused.
  it('adds the count to an item already counted when Use this item is chosen', async () => {
    listStock.mockResolvedValue([row({ product_id: 'p1', description: 'Test bulb 12W', qty: '5.000' })]);
    similarProducts.mockResolvedValue({ exact: [{ id: 'p1', code: 'p1', description: 'Test bulb 12W', unit: 'piece' }], similar: [] });
    getProduct.mockResolvedValue({ ...made, id: 'p1', code: 'p1' });
    open(<StocktakePage />);
    await screen.findByText('Not counted yet (1)', { exact: false });
    const count = document.getElementById('count-p1') as HTMLInputElement;
    fireEvent.change(count, { target: { value: '5' } });
    fireEvent.blur(count);
    await fill(/^How many you counted/, '7');
    save();
    fireEvent.click(within(await within(form()).findByTestId('name-exists')).getByRole('button', { name: 'Use this item' }));
    await waitFor(() => expect((document.getElementById('count-p1') as HTMLInputElement).value).toBe('12'));
    expect(createProduct).not.toHaveBeenCalled();
  });

  it('says so plainly when the new item is not on the count sheet of this branch, and counts nothing out of sight', async () => {
    listStock.mockResolvedValue([row({ product_id: 'p1', description: 'Test bulb 9W', qty: '5.000' })]);
    similarProducts.mockResolvedValue({ exact: [], similar: [] });
    createProduct.mockResolvedValue(made);
    open(<StocktakePage />);
    await screen.findByText('Not counted yet (1)', { exact: false });
    await fill(/^How many you counted/, '7');
    save();
    expect((await screen.findByTestId('count-new-note')).textContent).toBe('Test bulb 12W is saved, but it is not on the count sheet of this branch. Find it in the search to count it, or count it at its own branch.');
    expect(document.getElementById('count-p9')).toBeNull();
    expect(screen.getByRole('button', { name: /Review the difference \(0 counted\)/ })).toBeTruthy();
  });

  it('tells someone who may not add items to ask their manager', async () => {
    listStock.mockResolvedValue([]);
    const me = mockMe('admin');
    open(<StocktakePage />, { ...me, permissions: (me.permissions ?? []).filter((p) => p !== 'retail.catalogue.manage') });
    expect((await screen.findByTestId('count-new-ask')).textContent).toMatch(/Ask your manager to add this item\./);
  });
});
