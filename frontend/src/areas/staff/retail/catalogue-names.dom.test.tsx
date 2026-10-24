// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// The duplicate-name handling of the Items form (issue #352): the same name is refused in the owner's words with a way to open the
// item, an administrator may add it anyway with a reason, a close name is a warning, and a press of Save sends one request.
// Fabricated items.

vi.setConfig({ testTimeout: 20000 });

const similarProducts = vi.fn();
const createProduct = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve([{ id: 'c1', name: 'Fittings', active: true, product_count: 1, version: 1 }]),
      listUnits: () => Promise.resolve([{ id: 'u1', name: 'piece', active: true, product_count: 1, version: 1 }]),
      similarProducts: (...a: unknown[]) => similarProducts(...a),
      createProduct: (...a: unknown[]) => createProduct(...a),
    },
  };
});

const { RetailError } = await import('../../../api/retail');
const { ProductForm } = await import('./catalogue-products');

afterEach(() => {
  cleanup();
  similarProducts.mockReset();
  createProduct.mockReset();
});

const admin = mockMe('admin');
const manager = { ...mockMe('admin'), permissions: (mockMe('admin').permissions ?? []).filter((p) => p !== 'core.settings.manage') };
const branch = admin.branches?.[0]?.id ?? '';
const socket = { id: 'p5', code: 'P005', description: 'Double socket 13A', unit: 'piece', active: true, distance: 0, same_unit: true };

function screenOf(me: ReturnType<typeof mockMe>, node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

async function fillIn(description: string) {
  fireEvent.change(screen.getByLabelText('Code'), { target: { value: 'NEW-1' } });
  fireEvent.change(screen.getByLabelText('Description'), { target: { value: description } });
  await waitFor(() => expect(screen.getAllByRole('option').length).toBeGreaterThan(2));
  fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c1' } });
  fireEvent.change(screen.getByLabelText('Counted in'), { target: { value: 'u1' } });
  fireEvent.change(screen.getByLabelText(/Selling price/), { target: { value: '12000' } });
}

const addButton = () => screen.getByRole('button', { name: /^Add item/ }) as HTMLButtonElement;

describe('the same name', () => {
  it('is refused in the owner\'s words with a way to open the existing item', async () => {
    similarProducts.mockResolvedValue({ exact: [socket], similar: [] });
    const onOpen = vi.fn();
    screenOf(manager, <ProductForm onSaved={() => undefined} onCancel={() => undefined} onOpen={onOpen} />);
    await fillIn('double SOCKET, 13a');
    fireEvent.click(addButton());
    const panel = await screen.findByTestId('name-exists');
    expect(panel.textContent).toContain('This product already exists: Double socket 13A (P005). Open it?');
    expect(createProduct).not.toHaveBeenCalled();
    // A manager without the administrator permission has no way to add it anyway.
    expect(screen.queryByRole('button', { name: 'Add anyway' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Open it' }));
    expect(onOpen).toHaveBeenCalledWith('p5');
  });

  it('is added anyway by an administrator with a reason of at least five characters, kept with the item', async () => {
    similarProducts.mockResolvedValue({ exact: [socket], similar: [] });
    createProduct.mockResolvedValue({ id: 'new', code: 'NEW-1', description: 'Double socket 13A', version: 1 });
    const onSaved = vi.fn();
    screenOf(admin, <ProductForm onSaved={onSaved} onCancel={() => undefined} />);
    await fillIn('Double socket 13A');
    fireEvent.click(addButton());
    await screen.findByTestId('name-exists');
    fireEvent.click(screen.getByRole('button', { name: 'Add anyway' }));
    const confirm = () => screen.getAllByRole('button', { name: 'Add anyway' }).at(-1) as HTMLButtonElement;
    expect(confirm().disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Why is this a different item/), { target: { value: 'no' } });
    expect(confirm().disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Why is this a different item/), { target: { value: 'Other brand, same name' } });
    fireEvent.click(confirm());
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    expect(createProduct.mock.calls[0]?.[0]).toMatchObject({ description: 'Double socket 13A', duplicate_reason: 'Other brand, same name' });
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
  });

  it('shows the server\'s refusal if the name was taken a moment after the check', async () => {
    similarProducts.mockResolvedValue({ exact: [], similar: [] });
    createProduct.mockRejectedValue(new RetailError('x', 422, 'duplicate_product_name', {
      code: 'duplicate_product_name', existing_id: 'p9', existing_code: 'P009', existing_name: 'Insulation tape black', may_add_anyway: false,
    }));
    screenOf(manager, <ProductForm onSaved={() => undefined} onCancel={() => undefined} />);
    await fillIn('Insulation tape black');
    fireEvent.click(addButton());
    const panel = await screen.findByTestId('name-exists');
    expect(panel.textContent).toContain('This product already exists: Insulation tape black (P009). Open it?');
  });
});

describe('a close name', () => {
  it('is a warning the person decides on, then saved once', async () => {
    similarProducts.mockResolvedValue({ exact: [], similar: [{ ...socket, description: 'Double soket 13A', distance: 1, same_unit: false }] });
    createProduct.mockResolvedValue({ id: 'new', code: 'NEW-1', description: 'Double socket 13A', version: 1 });
    screenOf(manager, <ProductForm onSaved={() => undefined} onCancel={() => undefined} />);
    await fillIn('Double socket 13A');
    fireEvent.click(addButton());
    const panel = await screen.findByTestId('name-similar');
    expect(panel.textContent).toContain('An item with a similar name exists.');
    expect(panel.textContent).toContain('Double soket 13A (P005), per piece');
    expect(createProduct).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'This is a different item, save it' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    expect(createProduct.mock.calls[0]?.[0]).not.toHaveProperty('duplicate_reason');
  });

  it('can be answered by changing the name, which asks again', async () => {
    similarProducts.mockResolvedValue({ exact: [], similar: [{ ...socket, distance: 1, same_unit: false }] });
    screenOf(manager, <ProductForm onSaved={() => undefined} onCancel={() => undefined} />);
    await fillIn('Double socket 13B');
    fireEvent.click(addButton());
    await screen.findByTestId('name-similar');
    fireEvent.click(screen.getByRole('button', { name: 'Change the name' }));
    expect(screen.queryByTestId('name-similar')).toBeNull();
    fireEvent.change(screen.getByLabelText('Description'), { target: { value: 'Triple socket 13B' } });
    similarProducts.mockResolvedValue({ exact: [], similar: [] });
    createProduct.mockResolvedValue({ id: 'new', code: 'NEW-1', description: 'Triple socket 13B', version: 1 });
    fireEvent.click(addButton());
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    expect(similarProducts).toHaveBeenCalledTimes(2);
  });
});

describe('a new name', () => {
  it('sends one request however often Add item is pressed', async () => {
    let finish: (v: unknown) => void = () => undefined;
    similarProducts.mockResolvedValue({ exact: [], similar: [] });
    createProduct.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    screenOf(manager, <ProductForm onSaved={() => undefined} onCancel={() => undefined} />);
    await fillIn('Brand new item');
    fireEvent.click(addButton());
    fireEvent.submit(addButton().closest('form') as HTMLFormElement);
    fireEvent.submit(addButton().closest('form') as HTMLFormElement);
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    expect(similarProducts).toHaveBeenCalledTimes(1);
    finish({ id: 'new', code: 'NEW-1', description: 'Brand new item', version: 1 });
  });
});
