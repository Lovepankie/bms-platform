// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMe, prefixBase } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { chooseCategory } from '../../../test-utils/category-picker';

/** A button that may be pressed: not disabled, and not a primary button whose form is still not ready (issue #526). */
const ready = (b: unknown) => !(b as HTMLButtonElement).disabled && (b as HTMLElement).getAttribute('data-not-ready') !== 'true';

// Items added inside a category (issue #493, owner decisions D14 and D16): the category page lists its items and adds one with the
// category fixed (no dropdown), the Items form asks for the category first, and the Categories list shows each code start and
// opens the category. Issue #562: the app always makes the Item code; every form shows it as text, never an input, and sends
// none. Fabricated items.

vi.setConfig({ testTimeout: 20000 });

const createProduct = vi.fn();
const updateCategory = vi.fn();
const listCatalogue = vi.fn();
const createCategory = vi.fn();
const updateProduct = vi.fn();
const bulbs = { id: 'c1', name: 'Bulbs', active: true, product_count: 1, version: 3, code_prefix: 'BLB', next_code: 'BLB-0002' };
const lamps = { id: 'c2', name: 'Lamps', active: true, product_count: 0, version: 1, code_prefix: 'LMP', next_code: 'LMP-0001' };
// What the server lists; an inline add (#578) puts its category here, as the server would.
let cats: Record<string, unknown>[] = [bulbs, lamps];
const bulb = { id: 'p1', code: 'BLB-0001', description: 'Test bulb 9W', category_id: 'c1', category: 'Bulbs', unit_id: 'u1', unit: 'piece', sell_minor: 5000, active: true, version: 1 };

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve(cats),
      listUnits: () => Promise.resolve([{ id: 'u1', name: 'piece', active: true, product_count: 1, version: 1 }]),
      listCatalogue: (...a: unknown[]) => listCatalogue(...a),
      getProduct: () => Promise.resolve(bulb),
      similarProducts: () => Promise.resolve({ exact: [], similar: [] }),
      createProduct: (...a: unknown[]) => createProduct(...a),
      updateProduct: (...a: unknown[]) => updateProduct(...a),
      updateCategory: (...a: unknown[]) => updateCategory(...a),
      createCategory: (...a: unknown[]) => createCategory(...a),
      suggestCategoryPrefix: () => Promise.resolve('TV'),
      listProductUnits: () => Promise.resolve([]),
      stockMinimums: () => Promise.resolve([]),
    },
  };
});

const { CategoryPage } = await import('./catalogue-category');
const { ProductForm } = await import('./catalogue-products');
const { CategoriesPage } = await import('./catalogue-lists');

afterEach(() => {
  cleanup();
  createProduct.mockReset();
  updateCategory.mockReset();
  listCatalogue.mockReset();
  createCategory.mockReset();
  updateProduct.mockReset();
  cats = [bulbs, lamps];
});

const admin = mockMe('admin');
const branch = admin.branches?.[0]?.id ?? '';
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });

function screenOf(node: ReactElement) {
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me: admin, branch }}>{node}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

describe('the category page', () => {
  it('lists the items of the category and says how its codes are made', async () => {
    listCatalogue.mockResolvedValue({ items: [bulb] });
    screenOf(<CategoryPage categoryId="c1" />);
    expect(await screen.findByText('Item codes here start with BLB. The app gives the next item added BLB-0002.')).toBeTruthy();
    expect(await screen.findByText('Test bulb 9W')).toBeTruthy();
    expect(listCatalogue).toHaveBeenCalledWith(expect.objectContaining({ categoryId: 'c1' }));
  });

  it('adds an item with the category fixed: no category dropdown, and the Item code shown as made by the app', async () => {
    listCatalogue.mockResolvedValue({ items: [] });
    createProduct.mockResolvedValue({ ...bulb, id: 'p2', code: 'BLB-0002', description: 'Test bulb 12W' });
    screenOf(<CategoryPage categoryId="c1" />);
    await screen.findByText(/^Item codes here start/);
    fireEvent.click(screen.getByRole('button', { name: 'Add an item' }));
    expect(screen.getByRole('heading', { name: 'Add an item to Bulbs' })).toBeTruthy();
    expect(screen.queryByLabelText(/^Category/)).toBeNull();
    expect(screen.getByText('Bulbs', { selector: 'strong' })).toBeTruthy();
    expect(screen.getByTestId('item-code').textContent).toBe('Item code: BLB-0002, made by the app');
    expect(screen.queryByLabelText(/Item code/)).toBeNull();
    expect(document.querySelector('input#item-code')).toBeNull();
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'Test bulb 12W' } });
    await screen.findByRole('option', { name: 'piece' });
    fireEvent.change(screen.getByLabelText(/^Counted in/), { target: { value: 'u1' } });
    fireEvent.change(screen.getByLabelText(/^Selling price/), { target: { value: '6000' } });
    fireEvent.click(screen.getByRole('button', { name: /^Add item/ }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    const body = createProduct.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(body).not.toHaveProperty('code');
    expect(body).toMatchObject({ category_id: 'c1', description: 'Test bulb 12W', unit_id: 'u1' });
    expect((await screen.findAllByText('Added the item "Test bulb 12W" (BLB-0002).')).length).toBeGreaterThan(0);
  });

  it('changes the code start under If-Match, and says items made before keep their codes', async () => {
    listCatalogue.mockResolvedValue({ items: [] });
    updateCategory.mockResolvedValue({ ...bulbs, code_prefix: 'BUL', version: 4 });
    screenOf(<CategoryPage categoryId="c1" />);
    await screen.findByText(/^Item codes here start/);
    fireEvent.click(screen.getByRole('button', { name: 'Change the code start' }));
    fireEvent.change(screen.getByLabelText(/^Code start/), { target: { value: 'bul' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the code start' }));
    await waitFor(() => expect(updateCategory).toHaveBeenCalledWith('c1', 3, { code_prefix: 'BUL' }));
    expect((await screen.findAllByText('Item codes in Bulbs now start with BUL. Items made before keep their codes.')).length).toBeGreaterThan(0);
  });

  it('refuses a code start that is not 2 to 4 letters, without sending it', async () => {
    listCatalogue.mockResolvedValue({ items: [] });
    screenOf(<CategoryPage categoryId="c1" />);
    await screen.findByText(/^Item codes here start/);
    fireEvent.click(screen.getByRole('button', { name: 'Change the code start' }));
    fireEvent.change(screen.getByLabelText(/^Code start/), { target: { value: 'B1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the code start' }));
    expect(await screen.findByText('Use 2 to 4 letters, A to Z, such as BLB.')).toBeTruthy();
    expect(updateCategory).not.toHaveBeenCalled();
  });
});

describe('the item form outside a category', () => {
  it('adds a category inline from Add an item: the field names it and the code line gives its first code (#578)', async () => {
    const rice = { id: 'c9', name: 'Rice', active: true, product_count: 0, version: 1, code_prefix: 'RCE', next_code: 'RCE-0001' };
    createCategory.mockImplementation(async () => { cats = [...cats, rice]; return rice; });
    screenOf(<ProductForm onSaved={() => undefined} onCancel={() => undefined} />);
    await screen.findByRole('option', { name: 'piece' });
    const category = screen.getByRole('combobox', { name: /^Category/ });
    fireEvent.click(category);
    fireEvent.change(await screen.findByRole('combobox', { name: 'Search categories' }), { target: { value: 'Rice' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add Rice' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add category' }));
    await waitFor(() => expect(category.textContent).toBe('Rice'));
    expect(createCategory).toHaveBeenCalledWith('Rice');
    expect(screen.getByTestId('item-code').textContent).toBe('Item code: RCE-0001, made by the app');
    // Still so once the list has been fetched again.
    await waitFor(() => expect(screen.getByTestId('item-code').textContent).toBe('Item code: RCE-0001, made by the app'));
    expect(category.textContent).toBe('Rice');
  });

  it('asks for the category first, marks the needed fields and goes to the first one missing', async () => {
    screenOf(<ProductForm onSaved={() => undefined} onCancel={() => undefined} />);
    await screen.findByRole('option', { name: 'piece' });
    expect(document.querySelector('form .fields-needed')?.textContent).toBe('Fields marked * are needed.');
    const category = screen.getByLabelText(/^Category/);
    const description = screen.getByLabelText(/^Description/);
    // Hatibu, 23 Oct 2026: the category comes before the description.
    expect(category.compareDocumentPosition(description) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(category.getAttribute('aria-required')).toBe('true');
    expect(description.getAttribute('aria-required')).toBe('true');
    // Issue #562: the Item code is text, not a field, until a category gives it.
    expect(screen.queryByLabelText(/Item code/)).toBeNull();
    expect(screen.getByTestId('item-code').textContent).toBe('Item code: made by the app once you choose the category');
    const add = screen.getByRole('button', { name: /^Add item/ }) as HTMLButtonElement;
    expect(ready(add)).toBe(true);
    fireEvent.click(add);
    expect(document.activeElement).toBe(category);
    expect(await screen.findByText('Choose a category.')).toBeTruthy();
    expect(createProduct).not.toHaveBeenCalled();
    // Once a category is chosen the line names its next code.
    await chooseCategory(category, 'Lamps');
    expect(screen.getByTestId('item-code').textContent).toBe('Item code: LMP-0001, made by the app');
  });

  it('on an item\'s own page lets it move to another category and says it keeps its code', async () => {
    updateProduct.mockResolvedValue({ ...bulb, category_id: 'c2', category: 'Lamps', version: 2 });
    screenOf(<ProductForm product={bulb} onSaved={() => undefined} onCancel={() => undefined} />);
    await chooseCategory(screen.getByLabelText(/^Category/), 'Lamps');
    expect(screen.getByText('Moving it from Bulbs keeps its item code.')).toBeTruthy();
    // Issue #562: the code is shown read-only and never sent.
    expect(screen.getByTestId('item-code').textContent).toBe('Item code: BLB-0001. It never changes.');
    expect(screen.queryByLabelText(/Item code/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(updateProduct).toHaveBeenCalledTimes(1));
    expect(updateProduct.mock.calls[0]?.[2]).not.toHaveProperty('code');
  });
});

describe('the categories list', () => {
  it('shows each code start with its next code and opens the category', async () => {
    screenOf(<CategoriesPage />);
    expect(await screen.findByText('BLB')).toBeTruthy();
    expect(screen.getByText(/next BLB-0002/)).toBeTruthy();
    const open = screen.getByRole('link', { name: 'Open Bulbs and its items' });
    expect(open.getAttribute('href')).toBe('/staff/retail/catalogue/categories/c1');
  });

  it('shows the code start the app makes from the name and asks for none', async () => {
    createCategory.mockResolvedValue({ id: 'c3', name: 'TVs', active: true, product_count: 0, version: 1, code_prefix: 'TV', next_code: 'TV-0001' });
    screenOf(<CategoriesPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Add a category' }));
    const name = screen.getByLabelText('Name of the new category');
    fireEvent.change(name, { target: { value: 'TVs' } });
    fireEvent.blur(name);
    await waitFor(() => expect(screen.getByTestId('new-category-prefix').textContent)
      .toBe("Item codes here will start with TV, like TV-0001, made by the app. You can change the letters on the category's page."));
    expect(screen.queryByLabelText(/Code start/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add category' }));
    await waitFor(() => expect(createCategory).toHaveBeenCalledWith('TVs'));
    expect((await screen.findAllByText('Added the category "TVs". Its item codes start with TV.')).length).toBeGreaterThan(0);
  });
});

describe('the suggested code start (the rule of V96, kept the same in the mock)', () => {
  it.each([
    ['Bulbs', 'BLB'],
    ['TVs', 'TV'],
    ['Phone accessories', 'PHA'],
    ['Extensions', 'EXT'],
    ['BULBS', 'BLB'],
    ['LED bulbs', 'LEDB'],
    ['Oil', 'OL'],
    ['123', 'CAT'],
    ['Lamps', 'LMP'],
  ])('%s gives %s', (name, prefix) => {
    expect(prefixBase(name)).toBe(prefix);
  });
});
