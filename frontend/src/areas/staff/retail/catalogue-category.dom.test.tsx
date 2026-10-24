// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMe, prefixBase } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Items added inside a category (issue #493, owner decisions D14 and D16): the category page lists its items and adds one with the
// category fixed (no dropdown), the Item code may be left empty for the category's next code, the Items form asks for the
// category first, and the Categories list shows each code start and opens the category. Fabricated items.

vi.setConfig({ testTimeout: 20000 });

const createProduct = vi.fn();
const updateCategory = vi.fn();
const listCatalogue = vi.fn();
const createCategory = vi.fn();
const bulbs = { id: 'c1', name: 'Bulbs', active: true, product_count: 1, version: 3, code_prefix: 'BLB', next_code: 'BLB-0002' };
const lamps = { id: 'c2', name: 'Lamps', active: true, product_count: 0, version: 1, code_prefix: 'LMP', next_code: 'LMP-0001' };
const bulb = { id: 'p1', code: 'BLB-0001', description: 'Test bulb 9W', category_id: 'c1', category: 'Bulbs', unit_id: 'u1', unit: 'piece', sell_minor: 5000, active: true, version: 1 };

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve([bulbs, lamps]),
      listUnits: () => Promise.resolve([{ id: 'u1', name: 'piece', active: true, product_count: 1, version: 1 }]),
      listCatalogue: (...a: unknown[]) => listCatalogue(...a),
      getProduct: () => Promise.resolve(bulb),
      similarProducts: () => Promise.resolve({ exact: [], similar: [] }),
      createProduct: (...a: unknown[]) => createProduct(...a),
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
    expect(await screen.findByText('Item codes here start with BLB. The next item added without a code gets BLB-0002.')).toBeTruthy();
    expect(await screen.findByText('Test bulb 9W')).toBeTruthy();
    expect(listCatalogue).toHaveBeenCalledWith(expect.objectContaining({ categoryId: 'c1' }));
  });

  it('adds an item with the category fixed: no category dropdown, and an empty Item code is left to the app', async () => {
    listCatalogue.mockResolvedValue({ items: [] });
    createProduct.mockResolvedValue({ ...bulb, id: 'p2', code: 'BLB-0002', description: 'Test bulb 12W' });
    screenOf(<CategoryPage categoryId="c1" />);
    await screen.findByText(/^Item codes here start/);
    fireEvent.click(screen.getByRole('button', { name: 'Add an item' }));
    expect(screen.getByRole('heading', { name: 'Add an item to Bulbs' })).toBeTruthy();
    expect(screen.queryByLabelText(/^Category/)).toBeNull();
    expect(screen.getByText('Bulbs', { selector: 'strong' })).toBeTruthy();
    expect(screen.getByText('Leave it empty and the app gives it the next code of Bulbs, BLB-0002. Or type your own.')).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'Test bulb 12W' } });
    await waitFor(() => expect(screen.getAllByRole('option').length).toBeGreaterThan(1));
    fireEvent.change(screen.getByLabelText(/^Counted in/), { target: { value: 'u1' } });
    fireEvent.change(screen.getByLabelText(/^Selling price/), { target: { value: '6000' } });
    fireEvent.click(screen.getByRole('button', { name: /^Add item/ }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    const body = createProduct.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(body).not.toHaveProperty('code');
    expect(body).toMatchObject({ category_id: 'c1', description: 'Test bulb 12W', unit_id: 'u1' });
    expect((await screen.findAllByText('Added the item "Test bulb 12W" (BLB-0002).')).length).toBeGreaterThan(0);
  });

  it('keeps a typed Item code', async () => {
    listCatalogue.mockResolvedValue({ items: [] });
    createProduct.mockResolvedValue({ ...bulb, id: 'p3', code: 'OWN-1' });
    screenOf(<CategoryPage categoryId="c1" />);
    await screen.findByText(/^Item codes here start/);
    fireEvent.click(screen.getByRole('button', { name: 'Add an item' }));
    fireEvent.change(screen.getByLabelText('Item code'), { target: { value: ' OWN-1 ' } });
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'Test bulb 15W' } });
    await waitFor(() => expect(screen.getAllByRole('option').length).toBeGreaterThan(1));
    fireEvent.change(screen.getByLabelText(/^Counted in/), { target: { value: 'u1' } });
    fireEvent.change(screen.getByLabelText(/^Selling price/), { target: { value: '6000' } });
    fireEvent.click(screen.getByRole('button', { name: /^Add item/ }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    expect(createProduct.mock.calls[0]?.[0]).toMatchObject({ code: 'OWN-1' });
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
  it('asks for the category first, marks the needed fields and goes to the first one missing', async () => {
    screenOf(<ProductForm onSaved={() => undefined} onCancel={() => undefined} />);
    await waitFor(() => expect(screen.getAllByRole('option').length).toBeGreaterThan(2));
    expect(document.querySelector('form .fields-needed')?.textContent).toBe('Fields marked * are needed.');
    const category = screen.getByLabelText(/^Category/);
    const description = screen.getByLabelText(/^Description/);
    // Hatibu, 23 Oct 2026: the category comes before the description.
    expect(category.compareDocumentPosition(description) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(category.getAttribute('aria-required')).toBe('true');
    expect(description.getAttribute('aria-required')).toBe('true');
    expect(screen.getByLabelText('Item code').getAttribute('aria-required')).toBeNull();
    const add = screen.getByRole('button', { name: /^Add item/ }) as HTMLButtonElement;
    expect(add.disabled).toBe(false);
    fireEvent.click(add);
    expect(document.activeElement).toBe(category);
    expect(await screen.findByText('Choose a category.')).toBeTruthy();
    expect(createProduct).not.toHaveBeenCalled();
    // Once a category is chosen the hint names its next code.
    fireEvent.change(category, { target: { value: 'c2' } });
    expect(screen.getByText('Leave it empty and the app gives it the next code of Lamps, LMP-0001. Or type your own.')).toBeTruthy();
  });

  it('on an item\'s own page lets it move to another category and says it keeps its code', async () => {
    screenOf(<ProductForm product={bulb} onSaved={() => undefined} onCancel={() => undefined} />);
    await waitFor(() => expect(screen.getAllByRole('option').length).toBeGreaterThan(2));
    fireEvent.change(screen.getByLabelText(/^Category/), { target: { value: 'c2' } });
    expect(screen.getByText('Moving it from Bulbs keeps its item code.')).toBeTruthy();
    expect((screen.getByLabelText(/^Item code/) as HTMLInputElement).value).toBe('BLB-0001');
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

  it('suggests a code start from the name and sends a typed one', async () => {
    createCategory.mockResolvedValue({ id: 'c3', name: 'TVs', active: true, product_count: 0, version: 1, code_prefix: 'TV', next_code: 'TV-0001' });
    screenOf(<CategoriesPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Add a category' }));
    const name = screen.getByLabelText('Name of the new category');
    fireEvent.change(name, { target: { value: 'TVs' } });
    fireEvent.blur(name);
    expect(await screen.findByText('Leave it empty to use TV, so items get codes like TV-0001. Or type 2 to 4 letters.')).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/^Code start/), { target: { value: 'tvs' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add category' }));
    await waitFor(() => expect(createCategory).toHaveBeenCalledWith('TVs', 'tvs'));
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
