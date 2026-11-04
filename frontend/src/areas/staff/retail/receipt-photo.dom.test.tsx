// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState, type ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Product, Sale } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// A photo of the paper receipt on a sale, a delivery, an expense and a usage entry (issue #819, ADR-124, FR-RET-89): one
// optional Add receipt photo button on the form, the chosen picture shown small, a wrong file refused in words; the entry is
// saved first and the photo sent after it, the expense's as its receipt_document_id; offline the form says in words that the
// photo is added from the entry once online and nothing is sent; on a saved entry the photo shows small and opens full size.
// Fabricated items and amounts.

vi.setConfig({ testTimeout: 20000 });
vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))));

const calls = vi.fn();
vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  const called = (name: string) => (...a: unknown[]) => calls(name, ...a);
  return {
    ...real,
    retail: {
      ...real.retail,
      createExpense: called('createExpense'),
      createUsage: called('createUsage'),
      addReceiptPhoto: called('addReceiptPhoto'),
      sendExpenseReceiptPhoto: called('sendExpenseReceiptPhoto'),
      receiptPhoto: called('receiptPhoto'),
      entryDistances: () => Promise.resolve([]),
      listProducts: () => Promise.resolve([]),
    },
  };
});

const { ReceiptPhoto, ReceiptPhotoChoice, RECEIPT_OFFLINE, RECEIPT_LATER, bounded, sendReceiptPhoto } = await import('./receipt-photo');
const { ExpenseForm } = await import('./expenses');
const { UsageForm } = await import('./usage');
const { SaleDetail } = await import('./sales');
const { OfflineProvider } = await import('../../../offline/offline-context');
const { testOffline } = await import('../../../offline/test-kit');
const { businessToday } = await import('../../../api/retail');

const admin = mockMe('admin');
const uid = admin.user_id ?? '';
const branch = admin.branches?.[0]?.id ?? '';
const cable = { id: 'p1', code: 'TST-1', description: 'Test Cable 1', category: 'Test Wiring', unit: 'pc', qty: '40.000', active: true } as Product;
const photo = () => new File([new Uint8Array([137, 80, 78, 71])], 'receipt.png', { type: 'image/png' });

beforeEach(() => {
  // jsdom draws no blobs: each picture gets an address of its own.
  let n = 0;
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => `blob:test-${(n += 1)}`), revokeObjectURL: vi.fn() }));
});

afterEach(() => {
  cleanup();
  calls.mockReset();
  sessionStorage.clear();
});

function mount(ui: ReactElement, online = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['retail', 'expense-categories', true], [
    { id: 'c1', name: 'Test Transport', active: true, version: 1, items: [{ id: 'i1', name: 'Test Fuel', requires_explanation: false, active: true, version: 1 }] },
  ]);
  client.setQueryData(['retail', 'cash-parties'], []);
  const offline = testOffline(admin, { online });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me: admin, branch }}>
        <OfflineProvider value={offline}>{ui}</OfflineProvider>
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

function Choice() {
  const [file, setFile] = useState<File | null>(null);
  return <ReceiptPhotoChoice id="test-receipt" file={file} onChange={setFile} />;
}

const choose = (label: string | RegExp, file: File) => fireEvent.change(screen.getByLabelText(label), { target: { files: [file] } });

describe('the Add receipt photo button (FR-RET-89)', () => {
  it('is one optional button that shows the photo chosen and takes it off again', () => {
    mount(<Choice />);
    expect(screen.getAllByText('Add receipt photo')).toHaveLength(1);
    expect(screen.getByLabelText('Add receipt photo').getAttribute('accept')).toBe('image/png,image/jpeg,image/webp');
    expect(screen.queryByRole('img')).toBeNull();

    choose('Add receipt photo', photo());
    expect(screen.getByRole('img', { name: 'The receipt photo chosen' }).getAttribute('src')).toMatch(/^blob:test-/);
    expect(screen.getByLabelText('Change receipt photo')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Take the photo off' }));
    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.getByLabelText('Add receipt photo')).toBeTruthy();
  });

  it('refuses a file that is not a picture or is over 5 MB, in words', () => {
    mount(<Choice />);
    choose('Add receipt photo', new File(['%PDF'], 'receipt.pdf', { type: 'application/pdf' }));
    expect(screen.getByRole('alert').textContent).toBe('Choose a photo: a PNG, JPEG or WebP picture.');
    choose('Add receipt photo', new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'big.jpg', { type: 'image/jpeg' }));
    expect(screen.getByRole('alert').textContent).toMatch(/larger than 5 MB/);
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('gives way offline to plain words that the photo is added from the entry once online', () => {
    mount(<Choice />, false);
    expect(screen.getByTestId('test-receipt-offline').textContent).toBe(RECEIPT_OFFLINE);
    expect(screen.queryByLabelText('Add receipt photo')).toBeNull();
  });
});

describe('a form with a receipt photo', () => {
  it('sends the expense photo first and names it as the expense receipt', async () => {
    sessionStorage.setItem(`retail-draft:expense:${uid}:${branch}`, JSON.stringify({ key: 'k-exp', draft: { date: businessToday(), categoryId: 'c1', itemId: 'i1', partyId: '', amount: '20000', explanation: '' } }));
    calls.mockImplementation((name: string) => Promise.resolve(name === 'sendExpenseReceiptPhoto' ? { photo_id: 'doc-1' } : { id: 'e1', amount_minor: 20000 }));
    mount(<ExpenseForm branchId={branch} />);
    const file = photo();
    choose('Add receipt photo', file);
    fireEvent.click(screen.getByRole('button', { name: 'Save expense' }));
    await waitFor(() => expect(calls).toHaveBeenCalledWith('createExpense', expect.objectContaining({ receipt_document_id: 'doc-1', amount_minor: 20000 }), 'k-exp'));
    expect(calls.mock.calls.map((c) => c[0])).toEqual(['sendExpenseReceiptPhoto', 'createExpense']);
    expect(calls.mock.calls[0]?.[1]).toBe(file);
  });

  it('saves the usage entry first, then adds its photo; a photo that fails leaves the entry saved', async () => {
    sessionStorage.setItem(`retail-draft:usage:${uid}:${branch}`, JSON.stringify({ key: 'k-use', draft: { kind: 'damaged', reason: 'Test breakage', lines: [{ product: cable, qty: '1' }] } }));
    calls.mockImplementation((name: string) => (name === 'addReceiptPhoto' ? Promise.reject(new Error('no network')) : Promise.resolve({ id: 'u1', kind: 'damaged', lines: [] })));
    const saved = vi.fn();
    mount(<UsageForm branchId={branch} onSaved={saved} />);
    const file = photo();
    choose('Add receipt photo', file);
    fireEvent.click(screen.getAllByRole('button', { name: 'Save' }).find((b) => b.getAttribute('type') === 'submit') as HTMLButtonElement);
    await waitFor(() => expect(saved).toHaveBeenCalled());
    await waitFor(() => expect(calls.mock.calls.map((c) => c[0])).toEqual(['createUsage', 'addReceiptPhoto']));
    expect(calls.mock.calls[1]?.slice(1, 4)).toEqual(['usage', 'u1', file]);
    expect(calls.mock.calls[1]?.[4]).toBeInstanceOf(AbortSignal);
    expect(RECEIPT_LATER).toBe('Saved. The photo did not go; add it from the record.');
  });

  // JUSTIFICATION-A3: the review of #846 asks for tests of a photo that never answers and one that never carries over.
  it('never holds the save while the photo is still going up (review of #846)', async () => {
    sessionStorage.setItem(`retail-draft:usage:${uid}:${branch}`, JSON.stringify({ key: 'k-slow', draft: { kind: 'used', reason: 'Test fitting', lines: [{ product: cable, qty: '1' }] } }));
    calls.mockImplementation((name: string) => (name === 'addReceiptPhoto' ? new Promise(() => {}) : Promise.resolve({ id: 'u2', kind: 'used', lines: [] })));
    const saved = vi.fn();
    mount(<UsageForm branchId={branch} onSaved={saved} />);
    choose('Add receipt photo', photo());
    fireEvent.click(screen.getAllByRole('button', { name: 'Save' }).find((b) => b.getAttribute('type') === 'submit') as HTMLButtonElement);
    await waitFor(() => expect(saved).toHaveBeenCalled());
    // The form is free and its photo cleared at once, though the photo has not answered.
    expect(screen.queryByRole('img', { name: 'The receipt photo chosen' })).toBeNull();
  });

  it('bounds a photo that never answers, so nothing waits on it', async () => {
    await expect(bounded(() => new Promise(() => {}), 20)).rejects.toThrow('The receipt photo took too long to send.');
    calls.mockImplementation(() => new Promise(() => {}));
    expect(await sendReceiptPhoto('sale', 's9', photo(), 20)).toBe(RECEIPT_LATER);
  });

  it('saves the expense without its photo when the photo cannot be sent, and says so', async () => {
    sessionStorage.setItem(`retail-draft:expense:${uid}:${branch}`, JSON.stringify({ key: 'k-exp2', draft: { date: businessToday(), categoryId: 'c1', itemId: 'i1', partyId: '', amount: '5000', explanation: '' } }));
    calls.mockImplementation((name: string) => (name === 'sendExpenseReceiptPhoto' ? Promise.reject(new Error('no network')) : Promise.resolve({ id: 'e2', amount_minor: 5000 })));
    mount(<ExpenseForm branchId={branch} />);
    choose('Add receipt photo', photo());
    fireEvent.click(screen.getByRole('button', { name: 'Save expense' }));
    await waitFor(() => expect(calls).toHaveBeenCalledWith('createExpense', expect.not.objectContaining({ receipt_document_id: expect.anything() }), 'k-exp2'));
  });

  // JUSTIFICATION-A3: the lead's decision on the second review of #846 asks for a test of the kept expense's copy and words.
  it('keeps an expense whose answer was lost on the phone without its photo, and says to add it from the record', async () => {
    sessionStorage.setItem(`retail-draft:expense:${uid}:${branch}`, JSON.stringify({ key: 'k-lost', draft: { date: businessToday(), categoryId: 'c1', itemId: 'i1', partyId: '', amount: '7000', explanation: '' } }));
    const { markDropped } = await import('../../../api/client');
    const { DoneToast, doneToasts } = await import('./toast');
    calls.mockImplementation((name: string) => (name === 'sendExpenseReceiptPhoto' ? Promise.resolve({ photo_id: 'doc-7' }) : Promise.reject(markDropped(new TypeError('Failed to fetch')))));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } }, mutationCache: doneToasts() });
    client.setQueryData(['retail', 'expense-categories', true], [
      { id: 'c1', name: 'Test Transport', active: true, version: 1, items: [{ id: 'i1', name: 'Test Fuel', requires_explanation: false, active: true, version: 1 }] },
    ]);
    client.setQueryData(['retail', 'cash-parties'], []);
    const offline = testOffline(admin, { online: true });
    render(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: admin, branch }}>
          <OfflineProvider value={offline}><ExpenseForm branchId={branch} /></OfflineProvider>
          <DoneToast />
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
    choose('Add receipt photo', photo());
    fireEvent.click(screen.getByRole('button', { name: 'Save expense' }));
    await screen.findByTestId('kept-on-phone');
    // The online try named the photo; the copy kept on the phone does not.
    expect(calls).toHaveBeenCalledWith('createExpense', expect.objectContaining({ receipt_document_id: 'doc-7' }), 'k-lost');
    const [item] = offline?.engine.snapshot().items ?? [];
    expect(item?.entry?.body).not.toHaveProperty('receipt_document_id');
    expect(item?.entry?.body).toMatchObject({ amount_minor: 7000 });
    await waitFor(() => expect(document.querySelector('.toast-success')?.textContent).toBe('Saved on this phone. Add the photo from the record once it is sent.'));
  });

  it('never carries a photo to the next entry after one kept on the phone when the network dropped (review of #846)', async () => {
    sessionStorage.setItem(`retail-draft:usage:${uid}:${branch}`, JSON.stringify({ key: 'k-drop', draft: { kind: 'used', reason: 'Test fitting', lines: [{ product: cable, qty: '1' }] } }));
    const { markDropped } = await import('../../../api/client');
    calls.mockImplementation(() => Promise.reject(markDropped(new TypeError('Failed to fetch'))));
    mount(<UsageForm branchId={branch} />);
    choose('Add receipt photo', photo());
    expect(screen.getByRole('img', { name: 'The receipt photo chosen' })).toBeTruthy();
    fireEvent.click(screen.getAllByRole('button', { name: 'Save' }).find((b) => b.getAttribute('type') === 'submit') as HTMLButtonElement);
    await screen.findByTestId('kept-on-phone');
    fireEvent.click(screen.getByRole('button', { name: 'Record another' }));
    expect(screen.getByLabelText('Add receipt photo')).toBeTruthy();
    expect(screen.queryByRole('img', { name: 'The receipt photo chosen' })).toBeNull();
    expect(calls.mock.calls.map((c) => c[0])).toEqual(['createUsage']);
  });

  it('keeps an entry on the phone offline without any photo and says so', async () => {
    sessionStorage.setItem(`retail-draft:usage:${uid}:${branch}`, JSON.stringify({ key: 'k-off', draft: { kind: 'used', reason: 'Test fitting', lines: [{ product: cable, qty: '2' }] } }));
    mount(<UsageForm branchId={branch} />, false);
    expect(screen.getByTestId('usage-receipt-offline').textContent).toBe(RECEIPT_OFFLINE);
    fireEvent.click(screen.getAllByRole('button', { name: 'Save' }).find((b) => b.getAttribute('type') === 'submit') as HTMLButtonElement);
    await screen.findByTestId('kept-on-phone');
    expect(calls).not.toHaveBeenCalled();
  });
});

describe('the receipt photo on a saved entry', () => {
  it('shows small and opens full size', async () => {
    calls.mockImplementation(() => Promise.resolve(new Blob(['png'], { type: 'image/png' })));
    mount(<ReceiptPhoto kind="delivery" recordId="r1" has canAdd />);
    const open = await screen.findByRole('button', { name: 'Open the receipt photo' });
    expect(calls).toHaveBeenCalledWith('receiptPhoto', 'delivery', 'r1', expect.anything());
    expect(within(open).getByRole('img').getAttribute('width')).toBe('48');
    fireEvent.click(open);
    expect((await screen.findByRole('img', { name: 'Receipt photo, full size' })).getAttribute('style')).toMatch(/width: 100%/);
  });

  it('is added from the entry by whoever may add it, and only online', async () => {
    calls.mockImplementation(() => Promise.resolve({ photo_id: 'doc-2' }));
    const { unmount } = mount(<ReceiptPhoto kind="expense" recordId="e1" has={false} canAdd />);
    const file = photo();
    choose('Add receipt photo', file);
    await waitFor(() => expect(calls).toHaveBeenCalledWith('addReceiptPhoto', 'expense', 'e1', file));
    unmount();

    mount(<ReceiptPhoto kind="expense" recordId="e2" has={false} canAdd={false} />);
    expect(screen.queryByLabelText('Add receipt photo')).toBeNull();
    cleanup();

    mount(<ReceiptPhoto kind="expense" recordId="e3" has={false} canAdd />, false);
    expect(screen.getByTestId('receipt-photo-later-e3').textContent).toBe('Add the receipt photo once you are online.');
  });

  it('shows on the sale for whoever sees the sale', async () => {
    calls.mockImplementation(() => Promise.resolve(new Blob(['png'], { type: 'image/png' })));
    const sale = { id: 's1', sale_no: 'S-0001', branch_id: branch, sale_date: '2026-11-03', payment_method: 'cash', status: 'completed', total_minor: 1500, lines: [], has_receipt_photo: true } as Sale;
    mount(<SaleDetail sale={sale} today="2026-11-03" />);
    await screen.findByRole('button', { name: 'Open the receipt photo' });
    expect(calls).toHaveBeenCalledWith('receiptPhoto', 'sale', 's1', expect.anything());
  });

  it('offers a seller Add receipt photo on their own sale, never on a colleague\'s credit sale they only see (review of #846)', () => {
    const seller = mockMe('sales');
    const sale = { id: 's2', sale_no: 'S-0002', branch_id: branch, sale_date: '2026-11-03', payment_method: 'credit', status: 'completed', total_minor: 1500, balance_minor: 1500, lines: [], has_receipt_photo: false } as Sale;
    const show = (s: Sale) => render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me: seller, branch }}><SaleDetail sale={s} today="2026-11-03" /></StaffContext.Provider>
      </QueryClientProvider>,
    );
    show({ ...sale, created_by: 'someone-else' });
    expect(screen.queryByLabelText('Add receipt photo')).toBeNull();
    cleanup();
    show({ ...sale, created_by: seller.user_id });
    expect(screen.getByLabelText('Add receipt photo')).toBeTruthy();
  });
});
