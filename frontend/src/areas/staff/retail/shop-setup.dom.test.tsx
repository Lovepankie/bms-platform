// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { RetailError, type Category, type Unit } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { shopTypeById } from './shop-starters';

// Set up your shop (issue #625): the owner picks a kind of shop, sees its categories, units and packs ticked, unticks what they
// do not sell, changes a pack's size or what it is used for, and taps Add these. Names the shop has are shown as already there and not sent.
// The list is bundled, so it shows offline. Every name and number is fabricated.

vi.setConfig({ testTimeout: 20000 });

let cats: Category[] = [];
let units: Unit[] = [];
let online = true;
const createCategory = vi.fn();
const createUnit = vi.fn();
const updateUnit = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to, className }: { children: unknown; to: string; className?: string }) => <a href={to} className={className}>{children as never}</a> };
});
vi.mock('../../../offline/offline-context', async (original) => {
  const real = await original<typeof import('../../../offline/offline-context')>();
  return { ...real, useOnline: () => online };
});
vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => (online ? Promise.resolve(cats) : new Promise(() => {})),
      listUnits: () => (online ? Promise.resolve(units) : new Promise(() => {})),
      createCategory: (...a: unknown[]) => createCategory(...a),
      createUnit: (...a: unknown[]) => createUnit(...a),
      updateUnit: (...a: unknown[]) => updateUnit(...a),
    },
  };
});

const { ShopSetupPage } = await import('./shop-setup');
const owner = mockMe('admin');

function draw(me: Me = owner) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, refresh: () => Promise.resolve() } as never}>
        <ShopSetupPage />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  cats = [];
  units = [];
  online = true;
  createCategory.mockReset();
  createUnit.mockReset();
  updateUnit.mockReset();
});

describe('Set up your shop (issue #625)', () => {
  it('asks what kind of shop it is, lists that kind ticked, and adds what is left ticked', async () => {
    cats = [{ id: 'c1', name: 'Cement', active: true, version: 1 }];
    units = [{ id: 'u1', name: 'Piece', active: true, version: 1 }, { id: 'u2', name: 'Box', active: true, version: 2 }];
    createCategory.mockImplementation((name: string) => Promise.resolve({ id: `c-${name}`, name, active: true, version: 1, code_prefix: name.slice(0, 3).toUpperCase() }));
    createUnit.mockImplementation((name: string) => Promise.resolve({ id: `u-${name}`, name, active: true, version: 1 }));
    updateUnit.mockImplementation((id: string) => Promise.resolve({ id, name: 'Box', active: true, version: 3 }));
    draw();
    expect(screen.getByText('What kind of shop is it?')).toBeTruthy();
    fireEvent.click(screen.getByLabelText(/Hardware and building/));

    await screen.findByLabelText('Nails and screws');
    expect(screen.getByTestId('setup-there').textContent).toBe('Already in your shop: Cement, Piece.');
    expect(screen.queryByLabelText('Cement')).toBeNull();
    fireEvent.click(screen.getByLabelText('Paint'));
    expect((screen.getByLabelText('Paint') as HTMLInputElement).checked).toBe(false);

    // A pack shows its usual size and its Used for; the owner changes both.
    const roll = within(screen.getByTestId('setup-pack-roll'));
    fireEvent.change(roll.getByLabelText('Metre in one Roll'), { target: { value: '50' } });
    fireEvent.click(roll.getByRole('radio', { name: 'Selling' }));
    expect(roll.getByText(/50 Metre, for selling/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /^Add these/ }));
    await screen.findByText('Your shop is set up');
    expect(createCategory.mock.calls.map((c) => c[0])).toEqual(['Nails and screws', 'Pipes and fittings', 'Tools', 'Iron sheets', 'Wire']);
    expect(createUnit.mock.calls.filter((c) => c[1] === undefined).map((c) => c[0])).toEqual(['Kg', 'Metre', 'Litre']);
    expect(createUnit).toHaveBeenCalledWith('Roll', { size: '50', of_unit_id: 'u-Metre', purpose: 'sell' });
    // Box is a unit the shop has with no usual size: it gets one, never a second Box.
    expect(updateUnit).toHaveBeenCalledWith('u2', 2, { usual_pack: { size: '50', of_unit_id: 'u1', purpose: 'both' } });
    expect(createUnit.mock.calls.map((c) => c[0])).not.toContain('Box');
    expect(screen.getByText('Added 5 categories, 3 units and 4 packs.')).toBeTruthy();
    expect(screen.getByText('Item codes start with NAI')).toBeTruthy();
  });

  it('says there is nothing new when the shop has all of it, and offers no Add these', async () => {
    units = ['Piece', 'Pair', 'Set', 'Kg', 'Litre', 'Metre'].map((name, i) => ({ id: `u${i}`, name, active: true, version: 1 }));
    draw();
    fireEvent.click(screen.getByLabelText(/Something else/));
    expect((await screen.findByTestId('setup-nothing-new')).textContent).toBe('Your shop has all of these already.');
    expect(screen.queryByRole('button', { name: /Add these/ })).toBeNull();
  });

  it('shows the bundled list offline and adds nothing until the network is back', async () => {
    online = false;
    draw();
    fireEvent.click(screen.getByLabelText(/Phones and accessories/));
    expect(await screen.findByLabelText('Chargers')).toBeTruthy();
    expect(screen.getByTestId('setup-offline').textContent).toBe('You are offline. Connect to the internet to add these.');
    const add = screen.getByRole('button', { name: /^Add these/ }) as HTMLButtonElement;
    expect(add.disabled).toBe(true);
    fireEvent.click(add);
    await waitFor(() => expect(createCategory).not.toHaveBeenCalled());
  });

  it('leaves a pack out when its smaller unit is unticked and not in the shop, and says so', async () => {
    draw();
    fireEvent.click(screen.getByLabelText(/Supermarket and general/));
    fireEvent.click(await screen.findByLabelText('Bottle'));
    expect(within(screen.getByTestId('setup-pack-crate')).getByText(/Tick Bottle too, or this pack is left out\./)).toBeTruthy();
  });

  it('says a pack whose smaller unit is switched off is left out until it is switched on', async () => {
    units = [{ id: 'u1', name: 'Piece', active: true, version: 1 }, { id: 'u9', name: 'Metre', active: false, version: 4 }];
    draw();
    fireEvent.click(screen.getByLabelText(/Hardware and building/));
    const roll = within(await screen.findByTestId('setup-pack-roll'));
    expect(roll.getByText('Metre is switched off. Switch it on in Units, or this pack is left out.')).toBeTruthy();
    expect(roll.queryByLabelText('Metre in one Roll')).toBeNull();
  });

  it('shows what was left out and why, rather than saying the shop has everything', async () => {
    const hardware = shopTypeById('hardware')!;
    const pack = { size: '1.000', of_unit_id: 'u-Piece', of_unit: 'Piece', purpose: 'both' as const };
    cats = hardware.categories.map((name, i) => ({ id: `c${i}`, name, active: true, version: 1 }));
    units = [
      ...hardware.units.map((name) => ({ id: `u-${name}`, name, active: true, version: 1 })),
      ...hardware.packs.filter((p) => p.name !== 'Roll').map((p) => ({ id: `u-${p.name}`, name: p.name, active: true, version: 1, usual_pack: pack })),
    ];
    // Another phone added Roll after this one read the list.
    createUnit.mockRejectedValue(new RetailError('exists', 409, 'duplicate_unit'));
    draw();
    fireEvent.click(screen.getByLabelText(/Hardware and building/));
    await screen.findByTestId('setup-pack-roll');
    fireEvent.click(screen.getByRole('button', { name: /^Add these/ }));
    expect(await screen.findByText('Nothing new was added')).toBeTruthy();
    expect(screen.getByText('One was left out. See why below.')).toBeTruthy();
    expect(screen.getByText('Your shop has it already.')).toBeTruthy();
  });

  it('shows a wrong size once its field is left, and Add these sends nothing until it is right', async () => {
    draw();
    fireEvent.click(screen.getByLabelText(/Phones and accessories/));
    const box = within(await screen.findByTestId('setup-pack-box'));
    const size = box.getByLabelText('Piece in one Box');
    fireEvent.change(size, { target: { value: 'ten' } });
    expect(box.queryByText(/NaN/)).toBeNull();
    fireEvent.change(size, { target: { value: '0' } });
    expect(box.queryByText(/Enter how many/)).toBeNull();
    fireEvent.blur(size);
    expect(box.getByText(/Enter how many of the item are in one pack/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /^Add these/ }));
    await waitFor(() => expect(document.activeElement).toBe(size));
    expect(createCategory).not.toHaveBeenCalled();
  });

  it('is only for someone who may add categories and units', () => {
    draw(mockMe('sales'));
    expect(screen.queryByText('What kind of shop is it?')).toBeNull();
  });
});
