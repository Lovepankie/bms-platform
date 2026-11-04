// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Supplier } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// A supplier's details and deliveries (issue #820, FR-RET-90): the form takes every detail with the types used before as
// suggestions and refuses a bad email or web address in words before anything is sent; the page shows the details, Call, Text,
// WhatsApp, Email and Website, the bank account only when the server sends it, and the deliveries newest first, each opening
// the purchase; the list is searched by name, phone or type. Fabricated names, numbers and addresses.

vi.setConfig({ testTimeout: 20000 });

const listSuppliers = vi.fn();
const getSupplier = vi.fn();
const listSupplierDeliveries = vi.fn();
const listSupplierTypes = vi.fn();
const createSupplier = vi.fn();
const updateSupplier = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  const Link = ({ children, to, params, className, ...rest }: { children: unknown; to: string; params?: Record<string, string>; className?: string; 'aria-label'?: string }) => (
    <a href={to.replace(/\$(\w+)/g, (_, k: string) => params?.[k] ?? '')} className={className} aria-label={rest['aria-label']}>{children as never}</a>
  );
  return { ...real, Link, useRouter: () => null };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listSuppliers: (...a: unknown[]) => listSuppliers(...a),
      getSupplier: (...a: unknown[]) => getSupplier(...a),
      listSupplierDeliveries: (...a: unknown[]) => listSupplierDeliveries(...a),
      listSupplierTypes: (...a: unknown[]) => listSupplierTypes(...a),
      createSupplier: (...a: unknown[]) => createSupplier(...a),
      updateSupplier: (...a: unknown[]) => updateSupplier(...a),
    },
  };
});

const { SupplierForm, SupplierPage, SuppliersPage, createBody, draftOf, editBody, emailProblem, websiteHref, websiteProblem } = await import('./suppliers');

afterEach(() => {
  cleanup();
  for (const f of [listSuppliers, getSupplier, listSupplierDeliveries, listSupplierTypes, createSupplier, updateSupplier]) f.mockReset();
});

const admin = mockMe('admin');
const branch = admin.branches?.[0]?.id ?? '';

const full: Supplier = {
  id: 's1', name: 'Test Supplier 01', contact: '+256700000101', active: true, version: 3, created_at: '2026-02-01T08:00:00Z',
  last_delivery_on: '2026-10-30',
  details: {
    email: 'orders@supplier-test.example', address: 'Plot 1, Test Street', country: 'Uganda', website: 'supplier-test.example',
    payment_terms: '30 days', bank_account: '0000111122', supplier_type: 'Wholesaler', notes: 'Delivers on Mondays.',
  },
};

function wrap(ui: ReactElement, me: Me = admin) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch } as never}>{ui}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

describe('the supplier form rules', () => {
  it('takes an empty email or website, and refuses a wrong one in words', () => {
    expect(emailProblem('')).toBeNull();
    expect(emailProblem('a@b.example')).toBeNull();
    expect(emailProblem('not an email')).toBe('Write the email like name@example.com.');
    expect(emailProblem('a@b')).not.toBeNull();
    expect(websiteProblem('')).toBeNull();
    expect(websiteProblem('supplier-test.example')).toBeNull();
    expect(websiteProblem('not a site')).toBe('Write the web address like example.com.');
  });

  it('opens a website over https when it has no scheme, and never another scheme', () => {
    expect(websiteHref('supplier-test.example')).toBe('https://supplier-test.example/');
    expect(websiteHref('http://supplier-test.example/shop')).toBe('http://supplier-test.example/shop');
    expect(websiteHref('javascript:alert(1)')).toBeNull();
    expect(websiteHref('')).toBeNull();
  });

  it('adds only what was typed, and an edit sends only what changed, an emptied field sent empty', () => {
    const typed = draftOf();
    typed.name = ' Test Supplier 02 ';
    typed.details.payment_terms = '30 days';
    expect(createBody(typed)).toEqual({ name: 'Test Supplier 02', details: { payment_terms: '30 days' } });
    const changed = draftOf(full);
    changed.details.notes = '';
    changed.details.country = 'Kenya';
    expect(editBody(full, changed)).toEqual({ details: { country: 'Kenya', notes: '' } });
    expect(editBody(full, draftOf(full))).toEqual({});
  });
});

describe('the supplier form', () => {
  it('suggests the types used before and asks the bank account only of those who may edit suppliers', async () => {
    listSupplierTypes.mockResolvedValue(['Wholesaler', 'Manufacturer']);
    const { container } = wrap(<SupplierForm showBank={false} busy={false} saveLabel="Add supplier" onSave={() => undefined} />);
    await waitFor(() => expect(container.querySelectorAll('datalist option')).toHaveLength(2));
    expect(screen.getByLabelText('Supplier type').getAttribute('list')).toBe(container.querySelector('datalist')?.id);
    expect(screen.queryByLabelText('Bank account number')).toBeNull();
    cleanup();
    wrap(<SupplierForm showBank busy={false} saveLabel="Add supplier" onSave={() => undefined} />);
    expect(screen.getByLabelText('Bank account number')).toBeTruthy();
  });

  it('refuses a wrong email before anything is sent, then sends every detail typed', async () => {
    listSupplierTypes.mockResolvedValue([]);
    const onSave = vi.fn();
    wrap(<SupplierForm showBank busy={false} saveLabel="Add supplier" onSave={onSave} />);
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: 'Test Supplier 03' } });
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'not an email' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add supplier' }));
    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByText('Write the email like name@example.com.')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'orders@supplier-test.example' } });
    fireEvent.change(screen.getByLabelText('Payment terms'), { target: { value: '30 days' } });
    fireEvent.change(screen.getByLabelText('Bank account number'), { target: { value: '0000111122' } });
    fireEvent.change(screen.getByLabelText('Notes'), { target: { value: 'Delivers on Mondays.' } });
    // The busy button holds a press for a moment after a refused one (BusyButton), as a person's second tap would wait.
    await new Promise((resolve) => setTimeout(resolve, 20));
    fireEvent.click(screen.getByRole('button', { name: 'Add supplier' }));
    expect(onSave).toHaveBeenCalledTimes(1);
    expect(createBody(onSave.mock.calls[0]?.[0])).toEqual({
      name: 'Test Supplier 03',
      details: { email: 'orders@supplier-test.example', payment_terms: '30 days', bank_account: '0000111122', notes: 'Delivers on Mondays.' },
    });
  });
});

describe('the supplier page', () => {
  it('shows the details, the ways to reach them and the deliveries newest first, each opening the purchase', async () => {
    getSupplier.mockResolvedValue(full);
    listSupplierTypes.mockResolvedValue([]);
    listSupplierDeliveries
      .mockResolvedValueOnce({ items: [{ id: 'p2', purchase_no: 'RP00000002', purchased_on: '2026-10-30', voided: false, lines: 3 }, { id: 'p1', purchase_no: 'RP00000001', purchased_on: '2026-10-01', voided: true, lines: 1 }], next_cursor: 'c1' })
      .mockResolvedValueOnce({ items: [{ id: 'p0', purchase_no: 'RP00000000', purchased_on: '2026-09-01', voided: false, lines: 2 }], next_cursor: null });
    wrap(<SupplierPage supplierId="s1" />);

    await screen.findByText('Wholesaler');
    for (const text of ['30 days', 'Plot 1, Test Street', 'Uganda', '0000111122', 'Delivers on Mondays.', '1 Feb 2026', '30 Oct 2026']) {
      expect(screen.getAllByText(text).length).toBeGreaterThan(0);
    }
    expect(screen.getByRole('link', { name: 'Call Test Supplier 01' }).getAttribute('href')).toBe('tel:+256700000101');
    expect(screen.getByRole('link', { name: 'WhatsApp Test Supplier 01' }).getAttribute('href')).toBe('https://wa.me/256700000101');
    expect(screen.getByRole('link', { name: 'Email Test Supplier 01' }).getAttribute('href')).toBe('mailto:orders@supplier-test.example');
    const site = screen.getByRole('link', { name: 'Open the website of Test Supplier 01' });
    expect(site.getAttribute('href')).toBe('https://supplier-test.example/');
    expect(site.getAttribute('rel')).toBe('noopener noreferrer');

    const list = await screen.findByRole('list', { name: 'Deliveries, newest first' });
    const rows = within(list).getAllByRole('link');
    expect(rows.map((r) => r.getAttribute('href'))).toEqual(['/staff/retail/restocks/p2', '/staff/retail/restocks/p1']);
    expect(rows[0]?.textContent).toContain('30 Oct 2026, 3 items');
    expect(rows[1]?.textContent).toContain('Voided');
    fireEvent.click(screen.getByRole('button', { name: 'Show older deliveries' }));
    await waitFor(() => expect(within(list).getAllByRole('link')).toHaveLength(3));
    expect(listSupplierDeliveries).toHaveBeenLastCalledWith('s1', expect.objectContaining({ cursor: 'c1' }));
  });

  it('shows no bank account when the server sends none, and says when nothing was delivered yet', async () => {
    const { bank_account: _bank, ...open } = full.details ?? {};
    getSupplier.mockResolvedValue({ ...full, details: open, last_delivery_on: undefined });
    listSupplierDeliveries.mockResolvedValue({ items: [], next_cursor: null });
    wrap(<SupplierPage supplierId="s1" />);
    await screen.findByText('Wholesaler');
    expect(screen.queryByText('Bank account number')).toBeNull();
    expect(screen.getByText('None yet')).toBeTruthy();
    expect(await screen.findByText('No deliveries from this supplier yet.')).toBeTruthy();
  });

  it('saves only what changed under the version read', async () => {
    getSupplier.mockResolvedValue(full);
    listSupplierTypes.mockResolvedValue([]);
    listSupplierDeliveries.mockResolvedValue({ items: [], next_cursor: null });
    updateSupplier.mockResolvedValue({ ...full, version: 4, details: { ...full.details, payment_terms: 'Cash on delivery' } });
    wrap(<SupplierPage supplierId="s1" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit Test Supplier 01' }));
    fireEvent.change(screen.getByLabelText('Payment terms'), { target: { value: 'Cash on delivery' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updateSupplier).toHaveBeenCalledWith('s1', 3, { details: { payment_terms: 'Cash on delivery' } }));
    expect(await screen.findByText('Saved the changes to "Test Supplier 01".')).toBeTruthy();
  });
});

describe('the supplier list', () => {
  it('is searched by name, phone or type, and each supplier opens its page', async () => {
    listSuppliers.mockImplementation((q?: { query?: string }) => Promise.resolve(
      q?.query ? (q.query === 'wholesaler' ? [full] : []) : [full, { id: 's2', name: 'Test Supplier 02', active: false, version: 1 }],
    ));
    wrap(<SuppliersPage />);
    const card = (await screen.findByText('Test Supplier 01')).closest('li') as HTMLElement;
    expect(within(card).getByText('Wholesaler, +256700000101')).toBeTruthy();
    expect(within(card).getByText('Last delivery 30 Oct 2026')).toBeTruthy();
    expect(within(card).getByRole('link', { name: 'Open Test Supplier 01' }).getAttribute('href')).toBe('/staff/retail/catalogue/suppliers/s1');
    expect(screen.getByText('No deliveries yet.')).toBeTruthy();
    expect(screen.getByText('Switched off')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Search by name, phone or type'), { target: { value: 'wholesaler' } });
    await waitFor(() => expect(listSuppliers).toHaveBeenLastCalledWith(expect.objectContaining({ query: 'wholesaler' })));
    await waitFor(() => expect(screen.queryByText('Test Supplier 02')).toBeNull());
    fireEvent.change(screen.getByLabelText('Search by name, phone or type'), { target: { value: '0799' } });
    await waitFor(() => expect(listSuppliers).toHaveBeenLastCalledWith(expect.objectContaining({ query: '0799' })));
    await waitFor(() => expect(screen.queryByText('Test Supplier 01')).toBeNull());
  });

  it('adds a supplier with its details', async () => {
    listSuppliers.mockResolvedValue([]);
    listSupplierTypes.mockResolvedValue([]);
    createSupplier.mockResolvedValue({ id: 's3', name: 'Test Supplier 03', active: true, version: 1 });
    wrap(<SuppliersPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Add a supplier' }));
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: 'Test Supplier 03' } });
    fireEvent.change(screen.getByLabelText('Supplier type'), { target: { value: 'Wholesaler' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add supplier' }));
    await waitFor(() => expect(createSupplier).toHaveBeenCalledWith({ name: 'Test Supplier 03', details: { supplier_type: 'Wholesaler' } }));
    expect(await screen.findByText('Added the supplier "Test Supplier 03".')).toBeTruthy();
  });
});
