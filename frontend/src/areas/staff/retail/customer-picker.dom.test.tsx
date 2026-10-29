// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Customer } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { ADD_CHOICE } from '../../../app/ui/PickerWithAdd';
import { StaffContext } from '../context';

// Issue #688: the customer picker of Sell, Orders and Quotations ends its list with "+ Add a customer". The sheet adds one by
// name over the form, chooses it, and the form keeps what was typed; cancelled, nothing changes; hidden from whoever may not add
// customers. Fabricated customers.

const createCustomer = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, createCustomer: (...a: unknown[]) => createCustomer(...a) } };
});

const { CustomerPicker } = await import('./customer-picker');

const list: Customer[] = [{ id: 'b1', name: 'Test Customer 01' }];

function Harness({ customers }: { customers: Customer[] }) {
  const [value, setValue] = useState('');
  const [note, setNote] = useState('');
  return (
    <form onSubmit={(e) => e.preventDefault()}>
      <label htmlFor="note">Note</label>
      <input id="note" value={note} onChange={(e) => setNote(e.target.value)} />
      <CustomerPicker id="cust" label="Customer" placeholder="No customer" customers={{ data: customers, isError: false, refetch: () => undefined }} value={value} onChange={setValue} />
      <output data-testid="chosen">{value}</output>
    </form>
  );
}

function open(customers: Customer[], me: Me = mockMe('admin')) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}><Harness customers={customers} /></StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const withoutCustomers = (): Me => {
  const me = mockMe('admin');
  return { ...me, permissions: (me.permissions ?? []).filter((p) => p !== 'retail.catalogue.manage' && p !== 'retail.customer.manage') };
};

afterEach(() => {
  cleanup();
  createCustomer.mockReset();
});

describe('the customer picker (issue #688)', () => {
  it('adds a customer in a sheet over the form, chooses it and keeps what was typed', async () => {
    createCustomer.mockResolvedValue({ id: 'b9', name: 'Test Customer 09' });
    open(list);
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'Deliver on Friday' } });
    const select = screen.getByLabelText(/^Customer/) as HTMLSelectElement;
    expect([...select.options].map((o) => o.text)).toEqual(['No customer', 'Test Customer 01', '+ Add a customer']);
    fireEvent.change(select, { target: { value: ADD_CHOICE } });
    const sheet = await screen.findByRole('dialog', { name: 'Add a customer' });
    fireEvent.change(within(sheet).getByLabelText(/^Name of the new customer/), { target: { value: ' Test Customer 09 ' } });
    fireEvent.click(within(sheet).getByRole('button', { name: 'Add customer' }));
    await waitFor(() => expect(screen.getByTestId('chosen').textContent).toBe('b9'));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(createCustomer).toHaveBeenCalledWith({ name: 'Test Customer 09' });
    expect((screen.getByLabelText(/^Customer/) as HTMLSelectElement).selectedOptions[0]?.text).toBe('Test Customer 09');
    expect((screen.getByLabelText('Note') as HTMLInputElement).value).toBe('Deliver on Friday');
  });

  it('changes nothing when the sheet is cancelled', async () => {
    open(list);
    fireEvent.change(screen.getByLabelText(/^Customer/), { target: { value: ADD_CHOICE } });
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Add a customer' })).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByTestId('chosen').textContent).toBe('');
    expect(createCustomer).not.toHaveBeenCalled();
  });

  it('with no customers offers only + Add a customer, and hides it from whoever may not add customers', () => {
    open([]);
    expect(screen.getByText('No customers yet.')).toBeTruthy();
    expect(screen.getByRole('button', { name: '+ Add a customer' })).toBeTruthy();
    cleanup();
    open(list, withoutCustomers());
    const select = screen.getByLabelText(/^Customer/) as HTMLSelectElement;
    expect([...select.options].map((o) => o.text)).toEqual(['No customer', 'Test Customer 01']);
  });
});
