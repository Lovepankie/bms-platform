// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Supplier } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { ADD_CHOICE } from '../../../app/ui/PickerWithAdd';
import { StaffContext } from '../context';

// Issue #688: the supplier field of the purchase order, the goods received and the supplier invoice ends its list with "+ Add a
// supplier", added in a sheet over the form and chosen; a fixed supplier offers no add. Fabricated suppliers.

const createSupplier = vi.fn();
let suppliers: Supplier[] = [];

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, listSuppliers: () => Promise.resolve(suppliers), createSupplier: (...a: unknown[]) => createSupplier(...a) } };
});

const { SupplierField, useSuppliers } = await import('./purchasing-ui');

function Harness({ disabled = false }: { disabled?: boolean }) {
  const [value, setValue] = useState('');
  const [note, setNote] = useState('');
  const list = useSuppliers(value);
  return (
    <>
      <label htmlFor="po-note">Note</label>
      <input id="po-note" value={note} onChange={(e) => setNote(e.target.value)} />
      <SupplierField id="po-supplier" value={value} suppliers={list} disabled={disabled} required onChange={setValue} />
      <output data-testid="chosen">{value}</output>
    </>
  );
}

function open(disabled = false) {
  const me = mockMe('admin');
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}><Harness disabled={disabled} /></StaffContext.Provider>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  createSupplier.mockReset();
  suppliers = [];
});

describe('the purchasing supplier field (issue #688)', () => {
  it('adds a supplier in a sheet over the form, chooses it and keeps what was typed', async () => {
    suppliers = [{ id: 's1', name: 'Test Supplier 01', active: true }];
    createSupplier.mockResolvedValue({ id: 's7', name: 'Test Supplier 07', active: true });
    open();
    await screen.findByRole('option', { name: 'Test Supplier 01' });
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'Second delivery' } });
    fireEvent.change(screen.getByLabelText(/^Supplier/), { target: { value: ADD_CHOICE } });
    const sheet = await screen.findByRole('dialog', { name: 'Add a supplier' });
    fireEvent.change(within(sheet).getByLabelText(/^Name of the new supplier/), { target: { value: 'Test Supplier 07' } });
    fireEvent.click(within(sheet).getByRole('button', { name: 'Add supplier' }));
    await waitFor(() => expect(screen.getByTestId('chosen').textContent).toBe('s7'));
    expect(createSupplier).toHaveBeenCalledWith({ name: 'Test Supplier 07' });
    expect((screen.getByLabelText(/^Supplier/) as HTMLSelectElement).selectedOptions[0]?.text).toBe('Test Supplier 07');
    expect((screen.getByLabelText('Note') as HTMLInputElement).value).toBe('Second delivery');
  });

  it('offers no add while the supplier is fixed', async () => {
    suppliers = [{ id: 's1', name: 'Test Supplier 01', active: true }];
    open(true);
    await screen.findByRole('option', { name: 'Test Supplier 01' });
    const select = screen.getByLabelText(/^Supplier/) as HTMLSelectElement;
    expect(select.disabled).toBe(true);
    expect([...select.options].map((o) => o.text)).toEqual(['Choose a supplier', 'Test Supplier 01']);
  });
});
