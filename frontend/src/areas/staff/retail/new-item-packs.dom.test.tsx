// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Product } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { chooseCategory } from '../../../test-utils/category-picker';

// Issue #689 (ADR-106): Add an item asks for the smallest unit you sell, never a package, and takes its packages in the same
// form, sent with the item in one request. Every item, unit and amount is fabricated.

vi.setConfig({ testTimeout: 20000 });

const createProduct = vi.fn();
let items: Product[] = [];
const lighting = { id: 'c1', name: 'Test lighting', active: true, product_count: 0, version: 1, code_prefix: 'TLG', next_code: 'TLG-0001' };
const units = [
  { id: 'u1', name: 'Bulb', active: true, product_count: 0, version: 1, is_package: false },
  { id: 'u2', name: 'Carton', active: true, product_count: 0, version: 1, is_package: true },
  { id: 'u3', name: 'Box', active: true, product_count: 0, version: 1, is_package: true, usual_pack: { size: '100.000', of_unit_id: 'u1', of_unit: 'Bulb', purpose: 'buy' } },
];
const made: Product = { id: 'p1', code: 'TLG-0001', description: 'Test bulb 60W', category_id: 'c1', category: 'Test lighting', unit_id: 'u1', unit: 'Bulb', sell_minor: 5000, active: true, version: 1 };

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
      listCategories: () => Promise.resolve([lighting]),
      listUnits: () => Promise.resolve(units),
      similarProducts: () => Promise.resolve({ exact: [], similar: [] }),
      nameHints: () => Promise.resolve({}),
      createProduct: (...a: unknown[]) => createProduct(...a),
    },
  };
});

const { ProductsPage } = await import('./catalogue-products');
const { withPacksOn } = await import('./catalogue-packs');
const { PACKAGES_QUESTION, packageAsUnit, sizeProblem, withPackages, newPackageRow } = await import('./new-item-packs');

const owner = withPacksOn(mockMe('admin'));

afterEach(() => {
  cleanup();
  items = [];
  createProduct.mockReset();
});

function open(me: Me = owner) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '', chooseBranch: () => undefined }}><ProductsPage /></StaffContext.Provider>
    </QueryClientProvider>,
  );
}

async function fill(unitId: string) {
  fireEvent.click(await screen.findByRole('button', { name: 'Add an item' }));
  await chooseCategory(screen.getByLabelText(/^Category/), 'Test lighting');
  fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'Test bulb 60W' } });
  await screen.findByRole('option', { name: 'Bulb' });
  fireEvent.change(screen.getByLabelText(/^Smallest unit you sell/), { target: { value: unitId } });
  fireEvent.change(screen.getByLabelText(/^Selling price/), { target: { value: '5000' } });
}

describe('the words of Add an item', () => {
  it('names a package as the smallest unit in the words the server uses', () => {
    expect(packageAsUnit(units[2])).toBe('A box is a package. Choose the smallest unit, like piece, then add the box below.');
    expect(packageAsUnit(units[0])).toBeNull();
    expect(packageAsUnit(undefined)).toBeNull();
  });

  it('takes a whole number of 2 or more for a package', () => {
    for (const ok of ['2', '10', ' 100 ']) expect(sizeProblem(ok)).toBeNull();
    for (const bad of ['', '1', '0', '2.5', '-4', 'ten']) expect(sizeProblem(bad)).toBe('Type a whole number, 2 or more.');
  });

  it('says the packages after the item in the toast', () => {
    const carton = { ...newPackageRow(1), unitId: 'u2', factor: '10' };
    const box = { ...newPackageRow(2), unitId: 'u3', factor: '100' };
    expect(withPackages([], units)).toBe('');
    expect(withPackages([carton], units)).toBe(', with carton of 10');
    expect(withPackages([carton, box], units)).toBe(', with carton of 10 and box of 100');
  });
});

describe('Add an item (issue #689)', () => {
  it('calls the unit Smallest unit you sell, with its hint, and names the prices by it', async () => {
    open();
    await fill('u1');
    const unit = screen.getByLabelText(/^Smallest unit you sell/);
    expect(unit.getAttribute('aria-required')).toBe('true');
    expect(screen.getByText('The single thing a customer can buy. For example piece, bulb, metre, litre.')).toBeTruthy();
    expect(screen.getByLabelText(/^Buying price of one bulb/)).toBeTruthy();
    expect(screen.getByLabelText(/^Selling price of one bulb/)).toBeTruthy();
  });

  it('refuses Box as the smallest unit with a field message and sends nothing', async () => {
    open();
    await fill('u3');
    const said = 'A box is a package. Choose the smallest unit, like piece, then add the box below.';
    expect(screen.getByText(said)).toBeTruthy();
    expect(screen.getByLabelText(/^Smallest unit you sell/).getAttribute('aria-invalid')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await new Promise((r) => setTimeout(r, 50));
    expect(createProduct).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(screen.getByLabelText(/^Smallest unit you sell/));
  });

  it('asks No or Yes about packages, No first, and sends none on No', async () => {
    createProduct.mockImplementation(async () => { items = [made]; return made; });
    open();
    await fill('u1');
    expect(screen.getByText(PACKAGES_QUESTION)).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'No' }).getAttribute('aria-checked')).toBe('true');
    expect(screen.queryByTestId('package-row')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    expect(createProduct.mock.calls[0]?.[0]).not.toHaveProperty('packs');
    expect((await screen.findAllByText('Test bulb 60W added.')).length).toBeGreaterThan(0);
  });

  it('makes the item and two packages in one request, the words following the units, and shows it on the list', async () => {
    createProduct.mockImplementation(async () => made);
    open();
    await fill('u1');
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    const first = screen.getByTestId('package-row');
    // The smallest unit is never offered as its own package.
    expect(within(first).queryByRole('option', { name: 'Bulb' })).toBeNull();
    fireEvent.change(within(first).getByLabelText(/^Package/), { target: { value: 'u2' } });
    const size = within(first).getByLabelText(/^How many bulbs in one carton/);
    expect(size.getAttribute('aria-required')).toBe('true');
    fireEvent.change(size, { target: { value: '10' } });
    expect(within(first).getByRole('radio', { name: 'Both' }).getAttribute('aria-checked')).toBe('true');
    fireEvent.change(within(first).getByLabelText(/^Selling price of one carton/), { target: { value: '45000' } });

    fireEvent.click(screen.getByRole('button', { name: 'Add another package' }));
    const second = screen.getAllByTestId('package-row')[1]!;
    // A unit taken by the first row is not offered again.
    expect(within(second).queryByRole('option', { name: 'Carton' })).toBeNull();
    fireEvent.change(within(second).getByLabelText(/^Package/), { target: { value: 'u3' } });
    // The box's usual pack fills in its size and Used for (ADR-100).
    expect((within(second).getByLabelText(/^How many bulbs in one box/) as HTMLInputElement).value).toBe('100');
    expect(within(second).getByRole('radio', { name: 'Buying' }).getAttribute('aria-checked')).toBe('true');
    expect(within(second).queryByLabelText(/^Selling price of one box/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    expect(createProduct.mock.calls[0]?.[0]).toMatchObject({
      unit_id: 'u1',
      packs: [
        { unit_id: 'u2', factor: '10', purpose: 'both', sell_minor: 45000 },
        { unit_id: 'u3', factor: '100', purpose: 'buy' },
      ],
    });
    expect((createProduct.mock.calls[0]?.[0] as { packs: object[] }).packs[1]).not.toHaveProperty('sell_minor');
    expect((await screen.findAllByText('Test bulb 60W added, with carton of 10 and box of 100.')).length).toBeGreaterThan(0);
    // Back on the Items list, the new item is there though the list was read before it was made.
    expect(await screen.findByRole('button', { name: 'Edit Test bulb 60W' })).toBeTruthy();
  });

  it('blocks the save while a package has no unit or a size under 2', async () => {
    open();
    await fill('u1');
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    expect(await screen.findByText('Choose the package.')).toBeTruthy();
    const row = screen.getByTestId('package-row');
    fireEvent.change(within(row).getByLabelText(/^Package/), { target: { value: 'u2' } });
    fireEvent.change(within(row).getByLabelText(/^How many bulbs in one carton/), { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    expect(await screen.findByText('Type a whole number, 2 or more.')).toBeTruthy();
    expect(createProduct).not.toHaveBeenCalled();
  });

  it('shows no package question while packs are off', async () => {
    const off = { ...owner, module_capabilities: { ...owner.module_capabilities, retail: (owner.module_capabilities?.retail ?? []).filter((c) => c !== 'units') } };
    open(off);
    await fill('u1');
    expect(screen.queryByText(PACKAGES_QUESTION)).toBeNull();
  });
});
