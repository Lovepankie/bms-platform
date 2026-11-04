// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { businessToday, retail, type Purchase } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Edit a delivery as in the owner's old app (issue #830): Past purchases offers Edit to whoever may correct the purchase; it opens
// Buy stock filled in with that delivery; Save changes is one call (POST /retail/purchases/{id}/replace) that voids the old one and
// saves the new one naming it, in one transaction, with the toast "Delivery updated". A refusal (here an item switched off since)
// changes nothing: the old delivery stays recorded and the person stays on the edit form with the reason. The retail adapter is the
// fabricated mock.

vi.setConfig({ testTimeout: 20000 });

const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, useNavigate: () => navigate, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const { RestockCard } = await import('./restocks');
const { Again } = await import('./restock');
const { DoneToast, doneToasts } = await import('./toast');
const { editNote } = await import('./restock-correct-state');

const admin = mockMe('admin');
const seller = mockMe('sales');
const branch = admin.branches?.[0]?.id ?? '';

beforeEach(() => {
  Object.assign(retail, createMockRetail());
});

afterEach(() => {
  cleanup();
  navigate.mockReset();
  sessionStorage.clear();
  vi.restoreAllMocks();
});

function screenOf(me: ReturnType<typeof mockMe>, node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } }, mutationCache: doneToasts() })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
      <DoneToast />
    </QueryClientProvider>,
  );
}

const toastText = () => document.querySelector('.toast-success')?.textContent ?? null;

/** A fabricated delivery of 4 of the first mock item at the first branch, saved through the mock. */
async function delivery(): Promise<Purchase> {
  const [item] = await retail.listProducts({});
  return retail.createPurchase({ purchased_on: businessToday(), payment_method: 'cash', lines: [{ product_id: item?.id ?? '', cost_minor: 1000, qty_by_branch: [{ branch_id: branch, qty: '4' }] }] }, 'k-edit-delivery');
}

describe('Edit a delivery (issue #830)', () => {
  it('offers Edit beside Correct this purchase, and opens Buy stock with that delivery', async () => {
    const purchase = await delivery();
    screenOf(admin, <ul><RestockCard purchase={purchase} onDone={() => undefined} /></ul>);
    fireEvent.click(screen.getByRole('button', { name: `Edit delivery ${purchase.purchase_no ?? ''}` }));
    expect(navigate).toHaveBeenCalledWith({ to: '/staff/retail/restock', search: { edit: purchase.id } });
    expect(screen.getByRole('button', { name: `Correct purchase ${purchase.purchase_no ?? ''}` })).toBeTruthy();
  });

  it('offers no Edit to someone who may not correct the purchase, nor on a voided one', async () => {
    const purchase = await delivery();
    const noBuy = { ...seller, permissions: (seller.permissions ?? []).filter((p) => p !== 'retail.purchase.create'), permission_scopes: {} };
    screenOf(noBuy, <ul><RestockCard purchase={purchase} onDone={() => undefined} /></ul>);
    expect(screen.queryByRole('button', { name: /^Edit delivery/ })).toBeNull();
    cleanup();
    screenOf(admin, <ul><RestockCard purchase={{ ...purchase, status: 'voided' }} onDone={() => undefined} /></ul>);
    expect(screen.queryByRole('button', { name: /^Edit delivery/ })).toBeNull();
  });

  it('fills the form in; Save changes is one call that voids the old delivery and saves the new one naming it, with one toast', async () => {
    const purchase = await delivery();
    const replaceSpy = vi.spyOn(retail, 'replacePurchase');
    const voidSpy = vi.spyOn(retail, 'voidPurchase');
    const createSpy = vi.spyOn(retail, 'createPurchase');
    const edited = vi.fn();
    screenOf(admin, <Again purchaseId={purchase.id ?? ''} edit onEdited={edited} onSaved={() => undefined} />);
    expect(await screen.findByText(new RegExp(`You are editing delivery ${purchase.purchase_no ?? ''}`))).toBeTruthy();
    fireEvent.change((await screen.findByDisplayValue('4')) as HTMLInputElement, { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(edited).toHaveBeenCalledTimes(1));
    expect(replaceSpy).toHaveBeenCalledTimes(1);
    expect(replaceSpy.mock.calls[0]?.[0]).toBe(purchase.id);
    expect(replaceSpy.mock.calls[0]?.[1]).toMatchObject({ lines: [expect.objectContaining({ qty_by_branch: [{ branch_id: branch, qty: '3.000' }] })] });
    // The screen never voids or creates on its own: the server does both in one transaction.
    expect(voidSpy).not.toHaveBeenCalled();
    expect(createSpy).not.toHaveBeenCalled();
    await waitFor(() => expect(toastText()).toBe('Delivery updated'));
    const old = await retail.getPurchase(purchase.id ?? '');
    expect(old.status).toBe('voided');
    expect(old.void_reason).toBe('Edited: entered again with the changes');
    expect(edited.mock.calls[0]?.[0]?.note).toBe(editNote(purchase));
  });

  it('keeps the receipt photo of the delivery it replaces when no new one is chosen (issue #819, review of #846)', async () => {
    const purchase = await delivery();
    const photo = new File([new Uint8Array([137, 80, 78, 71])], 'receipt.png', { type: 'image/png' });
    await retail.addReceiptPhoto('delivery', purchase.id ?? '', photo);
    vi.spyOn(retail, 'getPurchase').mockResolvedValue({ ...purchase, has_receipt_photo: true });
    const replaceSpy = vi.spyOn(retail, 'replacePurchase');
    const addSpy = vi.spyOn(retail, 'addReceiptPhoto');
    const edited = vi.fn();
    screenOf(admin, <Again purchaseId={purchase.id ?? ''} edit onEdited={edited} onSaved={() => undefined} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(addSpy).toHaveBeenCalledTimes(1));
    const made = await replaceSpy.mock.results[0]?.value;
    expect(addSpy.mock.calls[0]?.[0]).toBe('delivery');
    expect(addSpy.mock.calls[0]?.[1]).toBe(made.id);
    expect(addSpy.mock.calls[0]?.[1]).not.toBe(purchase.id);
    expect(addSpy.mock.calls[0]?.[2]).toBeInstanceOf(File);
  });

  it('changes nothing when the new delivery is refused, and keeps the person on the edit form with the reason', async () => {
    const purchase = await delivery();
    // The review of #830: the item was deleted (switched off) after the delivery, so a new delivery of it is refused.
    const item = await retail.getProduct(purchase.lines?.[0]?.product_id ?? '');
    await retail.updateProduct(item.id ?? '', item.version ?? 1, { active: false });
    const edited = vi.fn();
    screenOf(admin, <Again purchaseId={purchase.id ?? ''} edit onEdited={edited} onSaved={() => undefined} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Save changes' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/switched off/);
    expect(edited).not.toHaveBeenCalled();
    expect((await retail.getPurchase(purchase.id ?? '')).status).toBe('posted');
    expect(screen.getByText(new RegExp(`You are editing delivery ${purchase.purchase_no ?? ''}`))).toBeTruthy();
    expect(toastText()).toBeNull();
  });

  it('enters a delivery found voided already again, ending in the saved screen rather than as an edit', async () => {
    const purchase = await delivery();
    await retail.voidPurchase(purchase.id ?? '', 'Voided by a colleague', 'k-colleague');
    const edited = vi.fn();
    const saved = vi.fn();
    screenOf(admin, <Again purchaseId={purchase.id ?? ''} edit onEdited={edited} onSaved={saved} />);
    expect(await screen.findByText(/which you voided/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Save the purchase' }));
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(edited).not.toHaveBeenCalled();
  });
});
