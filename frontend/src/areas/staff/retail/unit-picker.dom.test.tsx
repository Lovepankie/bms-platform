// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Unit } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #619: Counted in on both item forms never leaves a person stuck. With no units it says so and adds one by its name, which
// is then chosen; with units there is still "Add a new unit"; someone who may not add one is told to ask the owner. Fabricated
// units.

const createUnit = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, createUnit: (...a: unknown[]) => createUnit(...a), listUnits: () => Promise.resolve([]) } };
});

const { UnitPicker } = await import('./unit-picker');

afterEach(() => {
  cleanup();
  createUnit.mockReset();
});

const piece: Unit = { id: 'u1', name: 'piece', active: true, version: 1 };

function Harness({ units, failed = false }: { units: Unit[]; failed?: boolean }) {
  const [value, setValue] = useState('');
  return (
    <>
      <UnitPicker id="item-unit" units={units} loading={failed} load={{ isError: failed, refetch: () => undefined }} value={value} onChange={setValue} error={value === '' ? 'Choose a unit.' : null} showError={false} />
      <output data-testid="chosen">{value}</output>
    </>
  );
}

function open(units: Unit[], me: Me = mockMe('admin')) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: '' }}><Harness units={units} /></StaffContext.Provider>
    </QueryClientProvider>,
  );
}

describe('Counted in (issue #619)', () => {
  it('says there are no units yet, and typing a name adds the unit and chooses it', async () => {
    createUnit.mockResolvedValue({ id: 'u7', name: 'tray', active: true, version: 1 });
    open([]);
    expect(screen.getByText('No units yet.')).toBeTruthy();
    expect(screen.queryByText(/Choose a unit/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add a new unit' }));
    fireEvent.change(screen.getByLabelText(/^Name of the new unit/), { target: { value: ' tray ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add unit' }));
    await waitFor(() => expect(screen.getByTestId('chosen').textContent).toBe('u7'));
    expect(createUnit).toHaveBeenCalledWith('tray');
  });

  it('adds a new unit beside the list too, and Enter there adds it without sending the item form', async () => {
    createUnit.mockResolvedValue({ id: 'u8', name: 'dozen', active: true, version: 1 });
    const submitted = vi.fn((e: Event) => e.preventDefault());
    render(
      <QueryClientProvider client={new QueryClient()}>
        <StaffContext.Provider value={{ me: mockMe('admin'), branch: '' }}>
          <form onSubmit={(e) => submitted(e.nativeEvent)}><Harness units={[piece]} /></form>
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
    expect(screen.getByRole('option', { name: 'piece' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add a new unit' }));
    const name = screen.getByLabelText(/^Name of the new unit/);
    fireEvent.change(name, { target: { value: 'dozen' } });
    fireEvent.keyDown(name, { key: 'Enter' });
    await waitFor(() => expect(screen.getByTestId('chosen').textContent).toBe('u8'));
    expect(submitted).not.toHaveBeenCalled();
  });

  it('keeps the form open with the server words when the unit cannot be added', async () => {
    createUnit.mockRejectedValue(new Error('A unit with this name already exists.'));
    open([]);
    fireEvent.click(screen.getByRole('button', { name: 'Add a new unit' }));
    fireEvent.change(screen.getByLabelText(/^Name of the new unit/), { target: { value: 'piece' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add unit' }));
    expect((await screen.findByRole('alert')).textContent).toBe('A unit with this name already exists.');
    expect(screen.getByTestId('chosen').textContent).toBe('');
  });

  it('says the units could not be loaded when their read fails, never that there are none (review of #619)', () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <StaffContext.Provider value={{ me: mockMe('admin'), branch: '' }}><Harness units={[]} failed /></StaffContext.Provider>
      </QueryClientProvider>,
    );
    expect(screen.getByRole('alert').textContent).toBe('This list could not be loaded. Check the connection.');
    expect(screen.queryByText('No units yet.')).toBeNull();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  });

  it('tells someone who may not add units to ask the owner', () => {
    open([], mockMe('sales'));
    expect(screen.getByText('No units yet. Ask the owner to add a unit first.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Add a new unit' })).toBeNull();
  });
});
