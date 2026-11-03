// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Delete an item, a category or a unit as in the owner's old app (issue #830): the button says Delete and asks first; Yes switches
// the row off, never removes it, so items, history and reports keep it; it is hidden from the list and the pickers until Show
// deleted is ticked, where Bring back switches it on again. Names are fabricated.

vi.setConfig({ testTimeout: 20000 });

const updateCategory = vi.fn();
const updateUnit = vi.fn();
const updateProduct = vi.fn();
const bulbs = { id: 'c1', name: 'Bulbs', active: true, product_count: 1, version: 3, code_prefix: 'BLB', next_code: 'BLB-0002' };
const lamps = { id: 'c2', name: 'Lamps', active: true, product_count: 0, version: 1, code_prefix: 'LMP', next_code: 'LMP-0001' };
const piece = { id: 'u1', name: 'Piece', active: true, product_count: 1, version: 1 };
const metre = { id: 'u2', name: 'Metre', active: true, product_count: 0, version: 1 };
let cats: Record<string, unknown>[] = [bulbs, lamps];
let units: Record<string, unknown>[] = [piece, metre];
const bulb = { id: 'p1', code: 'BLB-0001', description: 'Test bulb 9W', category_id: 'c1', category: 'Bulbs', unit_id: 'u1', unit: 'piece', sell_minor: 5000, active: true, version: 1 };

/** A fake update that applies the change to the list and returns the row, as the API does. */
const applying = (list: () => Record<string, unknown>[], set: (rows: Record<string, unknown>[]) => void) =>
  (id: string, _version: number, body: Record<string, unknown>) => {
    set(list().map((r) => (r.id === id ? { ...r, ...body, version: Number(r.version) + 1 } : r)));
    return Promise.resolve(list().find((r) => r.id === id));
  };

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve(cats),
      listUnits: () => Promise.resolve(units),
      getProduct: () => Promise.resolve(bulb),
      similarProducts: () => Promise.resolve({ exact: [], similar: [] }),
      updateProduct: (...a: unknown[]) => updateProduct(...a),
      updateCategory: (...a: unknown[]) => updateCategory(...a),
      updateUnit: (...a: unknown[]) => updateUnit(...a),
      suggestCategoryPrefix: () => Promise.resolve('TV'),
      listProductUnits: () => Promise.resolve([]),
      stockMinimums: () => Promise.resolve([]),
    },
  };
});

const { ProductForm } = await import('./catalogue-products');
const { CategoriesPage, UnitsPage } = await import('./catalogue-lists');

afterEach(() => {
  cleanup();
  updateCategory.mockReset();
  updateUnit.mockReset();
  updateProduct.mockReset();
  cats = [bulbs, lamps];
  units = [piece, metre];
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

describe('Delete a category or a unit (issue #830)', () => {
  it('asks first; Yes switches the category off, and Show deleted brings it back into view', async () => {
    updateCategory.mockImplementation(applying(() => cats, (rows) => { cats = rows; }));
    screenOf(<CategoriesPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Delete Lamps' }));
    expect(screen.getByTestId('delete-category-question').textContent).toMatch(/^Delete Lamps\? It is hidden from lists and pickers\./);
    expect(updateCategory).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Keep it' }));
    expect(screen.queryByTestId('delete-category-question')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Delete Lamps' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, delete it' }));
    await waitFor(() => expect(updateCategory).toHaveBeenCalledWith('c2', 1, { active: false }));
    expect(await screen.findByText('Deleted "Lamps". Items that use it keep it; Show deleted brings it back.')).toBeTruthy();
    await waitFor(() => expect(screen.queryByText('Lamps', { selector: 'strong' })).toBeNull());
    fireEvent.click(screen.getByLabelText('Show deleted (1)'));
    expect(screen.getByText('Lamps', { selector: 'strong' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Bring back Lamps' }));
    await waitFor(() => expect(updateCategory).toHaveBeenLastCalledWith('c2', 2, { active: true }));
  });

  it('deletes a unit the same way, and shows no Show deleted while nothing is deleted', async () => {
    updateUnit.mockImplementation(applying(() => units, (rows) => { units = rows; }));
    screenOf(<UnitsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Delete Metre' }));
    expect(screen.queryByLabelText(/^Show deleted/)).toBeNull();
    expect(screen.getByTestId('delete-unit-question').textContent).toMatch(/^Delete Metre\?/);
    fireEvent.click(screen.getByRole('button', { name: 'Yes, delete it' }));
    await waitFor(() => expect(updateUnit).toHaveBeenCalledWith('u2', 1, { active: false }));
    await waitFor(() => expect(screen.queryByText('Metre', { selector: 'strong' })).toBeNull());
    expect(screen.getByLabelText('Show deleted (1)')).toBeTruthy();
  });

  it('hides a deleted category from the category picker of a new item', async () => {
    cats = [bulbs, { ...lamps, active: false }];
    screenOf(<ProductForm onSaved={() => undefined} onCancel={() => undefined} />);
    fireEvent.click(await screen.findByLabelText(/^Category/));
    expect(await screen.findByRole('option', { name: 'Bulbs' })).toBeTruthy();
    expect(screen.queryByRole('option', { name: /Lamps/ })).toBeNull();
  });
});

describe('Delete an item (issue #830)', () => {
  it('Delete this item asks first, then switches the item off; its history stays', async () => {
    updateProduct.mockResolvedValue({ ...bulb, active: false, version: 2 });
    const saved = vi.fn();
    screenOf(<ProductForm product={bulb} onSaved={saved} onCancel={() => undefined} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Delete this item' }));
    expect(screen.getByTestId('delete-item-question').textContent).toMatch(/^Delete Test bulb 9W\? It is hidden from lists and pickers\. Its sales, stock history and reports keep it/);
    expect(updateProduct).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, delete it' }));
    await waitFor(() => expect(updateProduct).toHaveBeenCalledWith('p1', 1, { active: false }));
    await waitFor(() => expect(saved).toHaveBeenCalledWith('Deleted "Test bulb 9W". Show deleted brings it back.'));
  });

  it('offers Bring back on a deleted item, and no Delete to someone who may not manage items', async () => {
    updateProduct.mockResolvedValue({ ...bulb, active: true, version: 3 });
    const saved = vi.fn();
    screenOf(<ProductForm product={{ ...bulb, active: false, version: 2 }} onSaved={saved} onCancel={() => undefined} />);
    expect((await screen.findByTestId('item-deleted')).textContent).toContain('Deleted');
    fireEvent.click(screen.getByRole('button', { name: 'Bring back' }));
    await waitFor(() => expect(updateProduct).toHaveBeenCalledWith('p1', 2, { active: true }));
    await waitFor(() => expect(saved).toHaveBeenCalledWith('"Test bulb 9W" is back.'));
    cleanup();
    const seller = mockMe('sales');
    render(
      <RouterContextProvider router={router}>
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <StaffContext.Provider value={{ me: seller, branch }}><ProductForm product={bulb} onSaved={() => undefined} onCancel={() => undefined} /></StaffContext.Provider>
        </QueryClientProvider>
      </RouterContextProvider>,
    );
    await screen.findByLabelText(/^Description/);
    expect(screen.queryByRole('button', { name: 'Delete this item' })).toBeNull();
  });
});
