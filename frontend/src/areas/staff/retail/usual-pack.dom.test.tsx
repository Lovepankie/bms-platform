// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product, Unit } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #637, the follow-ups of #628: the owner changes or removes a unit's usual pack on the Units page, and the pack form of an
// item fills in a unit's usual size and Used for only where the app filled them, never a typed size, and never sends a price on a
// pack only for buying. Every name and number is fabricated.

vi.setConfig({ testTimeout: 20000 });

const piece: Unit = { id: 'u-pc', name: 'Piece', active: true, version: 1 };
const metre: Unit = { id: 'u-m', name: 'Metre', active: true, version: 1 };
const roll: Unit = { id: 'u-roll', name: 'Roll', active: true, version: 3, usual_pack: { size: '50.000', of_unit_id: 'u-m', of_unit: 'Metre', purpose: 'both' } };
const carton: Unit = { id: 'u-ctn', name: 'Carton', active: true, version: 1, usual_pack: { size: '24.000', of_unit_id: 'u-pc', of_unit: 'Piece', purpose: 'buy' } };
const box: Unit = { id: 'u-box', name: 'Box', active: true, version: 1, usual_pack: { size: '10.000', of_unit_id: 'u-pc', of_unit: 'Piece', purpose: 'sell' } };
const dozen: Unit = { id: 'u-dz', name: 'Dozen', active: true, version: 1 };
let units: Unit[] = [];
const updateUnit = vi.fn();
const addPack = vi.fn();

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
      listUnits: () => Promise.resolve(units),
      listPacks: () => Promise.resolve([]),
      updateUnit: (...a: unknown[]) => updateUnit(...a),
      addPack: (...a: unknown[]) => addPack(...a),
    },
  };
});

const { UnitsPage } = await import('./catalogue-lists');
const { PackEditor } = await import('./catalogue-packs');
const owner = { ...mockMe('admin'), module_capabilities: { retail: ['units'] } };
const branch = owner.branches?.[0]?.id ?? '';

function draw(node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: owner, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  units = [];
  updateUnit.mockReset();
  addPack.mockReset();
});

describe('a usual pack on the Units page (issue #637)', () => {
  it('changes the size of a usual pack', async () => {
    units = [metre, roll];
    updateUnit.mockResolvedValue({ ...roll, version: 4 });
    draw(<UnitsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Usual pack of Roll' }));
    const sheet = await screen.findByRole('dialog');
    expect((within(sheet).getByLabelText(/^It holds/) as HTMLSelectElement).value).toBe('u-m');
    fireEvent.change(within(sheet).getByLabelText(/^How many in one Roll/), { target: { value: '100' } });
    fireEvent.click(within(sheet).getByRole('radio', { name: 'Selling' }));
    fireEvent.click(within(sheet).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updateUnit).toHaveBeenCalledTimes(1));
    expect(updateUnit).toHaveBeenCalledWith('u-roll', 3, { usual_pack: { size: '100', of_unit_id: 'u-m', purpose: 'sell' } });
  });

  it('removes a usual pack', async () => {
    units = [metre, roll];
    updateUnit.mockResolvedValue({ ...metre, id: 'u-roll', name: 'Roll', version: 4 });
    draw(<UnitsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Usual pack of Roll' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Remove usual pack' }));
    await waitFor(() => expect(updateUnit).toHaveBeenCalledWith('u-roll', 3, { clear_usual_pack: true }));
    expect(await screen.findAllByText('Roll has no usual pack now. Items keep their packs.')).not.toHaveLength(0);
  });

  it('offers only units that are not packs themselves, and sends nothing while the size is wrong', async () => {
    units = [metre, roll, dozen];
    draw(<UnitsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Usual pack of Dozen' }));
    const sheet = await screen.findByRole('dialog');
    const options = within(within(sheet).getByLabelText(/^It holds/)).getAllByRole('option').map((o) => o.textContent);
    expect(options).toEqual(['Choose a unit', 'Metre']);
    expect(within(sheet).queryByRole('button', { name: 'Remove usual pack' })).toBeNull();
    fireEvent.change(within(sheet).getByLabelText(/^It holds/), { target: { value: 'u-m' } });
    const many = within(sheet).getByLabelText(/^How many in one Dozen/);
    fireEvent.change(many, { target: { value: '0.5' } });
    fireEvent.blur(many);
    // Rule 10: the error shows on leaving the field, before Save is pressed.
    expect(within(sheet).getByText('Enter how many Metre are in one Dozen: 1 or more, for example 12 or 12.5.')).toBeTruthy();
    expect(many.getAttribute('aria-invalid')).toBe('true');
    fireEvent.click(within(sheet).getByRole('button', { name: 'Save' }));
    expect(updateUnit).not.toHaveBeenCalled();
  });

  it('offers only Remove on a unit that has a usual pack but is switched off, or that another pack counts (issue #662)', async () => {
    const bundle: Unit = { id: 'u-bdl', name: 'Bundle', active: true, version: 1, usual_pack: { size: '5.000', of_unit_id: 'u-roll', of_unit: 'Roll', purpose: 'both' } };
    units = [metre, roll, bundle, { ...carton, active: false }, piece];
    updateUnit.mockResolvedValue({ ...carton, active: false, usual_pack: undefined, version: 2 });
    draw(<UnitsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Usual pack of Roll' }));
    let sheet = await screen.findByRole('dialog');
    expect(within(sheet).getByText('Bundle is a pack of Roll, so Roll cannot be a pack too. You can remove its usual pack.')).toBeTruthy();
    expect(within(sheet).queryByRole('button', { name: 'Save' })).toBeNull();
    expect(within(sheet).queryByLabelText(/^It holds/)).toBeNull();
    expect(within(sheet).getByRole('button', { name: 'Remove usual pack' })).toBeTruthy();
    fireEvent.keyDown(sheet, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    fireEvent.click(screen.getByRole('button', { name: 'Usual pack of Carton' }));
    sheet = await screen.findByRole('dialog');
    expect(within(sheet).getByText('Carton is switched off, so its usual pack can only be removed.')).toBeTruthy();
    expect(within(sheet).queryByRole('button', { name: 'Save' })).toBeNull();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Remove usual pack' }));
    await waitFor(() => expect(updateUnit).toHaveBeenCalledWith('u-ctn', 1, { clear_usual_pack: true }));
  });

  it('names Used for once, by the choice itself, with no loose label beside it (issue #662)', async () => {
    units = [metre, roll];
    draw(<UnitsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Usual pack of Roll' }));
    const sheet = await screen.findByRole('dialog');
    expect(within(sheet).getByRole('radiogroup', { name: 'Used for' })).toBeTruthy();
    expect(within(sheet).queryByText('Used for')).toBeNull();
  });

  it('never offers a usual pack on a unit another usual pack counts, as the server refuses it', async () => {
    units = [metre, roll, dozen];
    draw(<UnitsPage />);
    await screen.findByRole('button', { name: 'Usual pack of Roll' });
    expect(screen.queryByRole('button', { name: 'Usual pack of Metre' })).toBeNull();
  });

  it('says so and offers to add a unit when the one it held is switched off, never a blank choice', async () => {
    units = [{ ...metre, active: false }, roll];
    draw(<UnitsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Usual pack of Roll' }));
    const sheet = await screen.findByRole('dialog');
    expect(within(sheet).getByText('No other unit for this pack to hold yet.')).toBeTruthy();
    expect(within(sheet).getByRole('button', { name: 'Add a new unit' })).toBeTruthy();
    expect(within(sheet).queryByRole('combobox')).toBeNull();
    // The old smaller unit counts as not chosen, so Save sends nothing and does not close the sheet quietly.
    fireEvent.click(within(sheet).getByRole('button', { name: 'Save' }));
    expect(updateUnit).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeTruthy();
  });
});

describe('the pack form of an item fills in a unit only where the app filled it (review of #628)', () => {
  const pen: Product = { id: 'p1', code: 'T-PEN', description: 'Test pen', unit: 'Piece', unit_id: 'u-pc', sell_minor: 100, cost_minor: 83, active: true, version: 1 };
  const usedFor = () => within(screen.getByRole('radiogroup', { name: 'Used for' }));
  const chosen = () => usedFor().getAllByRole('radio').find((r) => r.getAttribute('aria-checked') === 'true')?.textContent;
  const size = () => (screen.getByLabelText('Items in one pack') as HTMLInputElement).value;

  it('choosing A, then B, then typing a size, then choosing C keeps the typed size', async () => {
    units = [piece, carton, box, dozen];
    draw(<PackEditor product={pen} />);
    const unit = await screen.findByLabelText('Add a pack');
    await screen.findByRole('option', { name: 'Dozen' });
    fireEvent.change(unit, { target: { value: 'u-ctn' } });
    expect(size()).toBe('24');
    expect(chosen()).toBe('Buying');
    fireEvent.change(unit, { target: { value: 'u-box' } });
    expect(size()).toBe('10');
    expect(chosen()).toBe('Selling');
    fireEvent.change(screen.getByLabelText('Items in one pack'), { target: { value: '12' } });
    fireEvent.change(unit, { target: { value: 'u-dz' } });
    expect(size()).toBe('12');
    // Used for was filled in by Box, so Dozen, with no usual pack, makes it Both again.
    expect(chosen()).toBe('Both');
  });

  it('a price typed before choosing a unit only for buying is not sent, so the add is never refused as not sold', async () => {
    units = [piece, carton];
    addPack.mockResolvedValue({});
    draw(<PackEditor product={pen} />);
    const unit = await screen.findByLabelText('Add a pack');
    await screen.findByRole('option', { name: 'Carton' });
    fireEvent.change(screen.getByLabelText(/Selling price of one pack/), { target: { value: '2000' } });
    fireEvent.change(unit, { target: { value: 'u-ctn' } });
    expect(chosen()).toBe('Buying');
    expect(screen.queryByLabelText(/Selling price of one pack/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add pack' }));
    await waitFor(() => expect(addPack).toHaveBeenCalledTimes(1));
    expect(addPack).toHaveBeenCalledWith('p1', { unit_id: 'u-ctn', factor: '24', purpose: 'buy' });
  });
});
