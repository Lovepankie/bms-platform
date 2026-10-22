// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Sale, SaleReceipt } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Share receipt and WhatsApp customer (issue #439, ADR-061) as a seller uses them: the PDF to the share sheet, the download
// where a browser cannot share files, a cancelled share sheet that shows nothing, a phone that refuses twice, a fresh PDF
// after a payment, the wa.me address, a refused number, who sees the buttons, and the optional save of a typed number.
// Fabricated names, numbers and amounts.

vi.setConfig({ testTimeout: 20000 });

const saleReceiptPdf = vi.fn();
const saleReceipt = vi.fn();
const updateCustomer = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      saleReceiptPdf: (...a: unknown[]) => saleReceiptPdf(...a),
      saleReceipt: (...a: unknown[]) => saleReceipt(...a),
      updateCustomer: (...a: unknown[]) => updateCustomer(...a),
    },
  };
});

const { ShareReceipt, DOWNLOADED, SHARE_AGAIN, SHARE_REFUSED, WHATSAPP_HINT } = await import('./receipt-share');

const seller = mockMe('sales');
const admin = mockMe('admin');
const branch = seller.branches?.[0]?.id ?? '';
const sale: Sale = {
  id: 's1', sale_no: 'RS00000007', branch_id: branch, sale_date: '2026-10-22', payment_method: 'cash', status: 'completed', version: 1,
  buyer_name: 'Test Buyer 01', buyer_contact: '0700 000 031', total_minor: 25000, paid_minor: 25000, balance_minor: 0, lines: [], created_by: seller.user_id,
};
const facts: SaleReceipt = {
  sale_id: 's1', receipt_no: 'RS00000007', business_name: 'Test Trading Ltd', buyer_name: 'Test Buyer 01', buyer_contact: '0700 000 031',
  customer_has_contact: false, payment_method: 'cash', currency: 'UGX', total_minor: 25000, paid_minor: 25000, balance_minor: 0,
  returned_minor: 0, net_minor: 25000, credit_notes: [], sale_version: 1,
};

let open: ReturnType<typeof vi.fn>;
let click: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  saleReceiptPdf.mockResolvedValue(new Blob(['%PDF-1.4 test'], { type: 'application/pdf' }));
  saleReceipt.mockResolvedValue(facts);
  open = vi.fn();
  vi.stubGlobal('open', open);
  URL.createObjectURL = vi.fn(() => 'blob:receipt');
  URL.revokeObjectURL = vi.fn();
  click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  click.mockRestore();
  saleReceiptPdf.mockReset();
  saleReceipt.mockReset();
  updateCustomer.mockReset();
  delete (navigator as { share?: unknown }).share;
  delete (navigator as { canShare?: unknown }).canShare;
});

function tree(me: ReturnType<typeof mockMe>, s: Sale, client: QueryClient) {
  return (
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <ShareReceipt sale={s} />
      </StaffContext.Provider>
    </QueryClientProvider>
  );
}

function screenOf(me: ReturnType<typeof mockMe>, s: Sale) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(tree(me, s, client));
  return { ...view, again: (next: Sale) => view.rerender(tree(me, next, client)) };
}

function sharing(canShare: boolean, share: (data: ShareData) => Promise<void> = () => Promise.resolve()) {
  const shareFn = vi.fn(share);
  Object.defineProperty(navigator, 'share', { value: shareFn, configurable: true, writable: true });
  Object.defineProperty(navigator, 'canShare', { value: vi.fn(() => canShare), configurable: true, writable: true });
  return shareFn;
}

const shareButton = () => screen.findByRole('button', { name: 'Share receipt' });
const whatsAppButton = () => screen.findByRole('button', { name: 'WhatsApp customer' });
const phoneBox = async () => (await screen.findByLabelText("Customer's WhatsApp number")) as HTMLInputElement;

describe('Share receipt', () => {
  it('hands the PDF to the share sheet as a file, with the caption from the receipt facts', async () => {
    const share = sharing(true);
    screenOf(seller, sale);
    fireEvent.click(await shareButton());
    await waitFor(() => expect(share).toHaveBeenCalledTimes(1));
    expect(saleReceipt).toHaveBeenCalledWith('s1');
    expect(saleReceiptPdf).toHaveBeenCalledWith('s1');
    const data = share.mock.calls[0]?.[0] ?? {};
    expect(data.files?.[0]).toBeInstanceOf(File);
    expect(data.files?.[0]?.name).toBe('Receipt RS00000007.pdf');
    expect(data.files?.[0]?.type).toBe('application/pdf');
    expect(data.title).toBe('Receipt RS00000007');
    expect(data.text).toContain('*Test Trading Ltd*: Receipt RS00000007');
    expect(data.text).toContain('Fully settled, thank you!');
    expect(click).not.toHaveBeenCalled();
  });

  it('downloads the PDF and says so when the browser cannot share files', async () => {
    const share = sharing(false);
    screenOf(seller, sale);
    fireEvent.click(await shareButton());
    expect(await screen.findByText(DOWNLOADED)).toBeTruthy();
    expect(share).not.toHaveBeenCalled();
    expect(click).toHaveBeenCalledTimes(1);
  });

  it('shows nothing when the share sheet is cancelled', async () => {
    const share = sharing(true, () => Promise.reject(new DOMException('cancelled', 'AbortError')));
    screenOf(seller, sale);
    fireEvent.click(await shareButton());
    await waitFor(() => expect(share).toHaveBeenCalledTimes(1));
    await waitFor(async () => expect(((await shareButton()) as HTMLButtonElement).disabled).toBe(false));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('asks for a second tap once, and downloads after a second refusal', async () => {
    const share = sharing(true, () => Promise.reject(new DOMException('no gesture', 'NotAllowedError')));
    screenOf(seller, sale);
    fireEvent.click(await shareButton());
    expect(await screen.findByText(SHARE_AGAIN)).toBeTruthy();
    fireEvent.click(await shareButton());
    expect(await screen.findByText(SHARE_REFUSED)).toBeTruthy();
    expect(share).toHaveBeenCalledTimes(2);
    expect(saleReceiptPdf).toHaveBeenCalledTimes(1);
    expect(click).toHaveBeenCalledTimes(1);
  });

  it('fetches a fresh PDF and caption after a payment changes the sale', async () => {
    const credit: Sale = { ...sale, payment_method: 'credit', paid_minor: 0, balance_minor: 25000 };
    saleReceipt.mockResolvedValueOnce({ ...facts, payment_method: 'credit', paid_minor: 0, balance_minor: 25000 });
    const share = sharing(true);
    const view = screenOf(seller, credit);
    fireEvent.click(await shareButton());
    await waitFor(() => expect(share).toHaveBeenCalledTimes(1));
    expect(share.mock.calls[0]?.[0]?.text).toContain('Balance outstanding: UGX 25,000');
    saleReceipt.mockResolvedValueOnce({ ...facts, payment_method: 'credit', paid_minor: 25000, balance_minor: 0, sale_version: 2 });
    view.again({ ...credit, version: 2, paid_minor: 25000, balance_minor: 0 });
    await waitFor(() => expect(saleReceipt).toHaveBeenCalledTimes(2));
    fireEvent.click(await shareButton());
    await waitFor(() => expect(share).toHaveBeenCalledTimes(2));
    expect(saleReceiptPdf).toHaveBeenCalledTimes(2);
    expect(share.mock.calls[1]?.[0]?.text).toContain('Fully settled, thank you!');
  });

  it('shows the server refusal for a sale whose goods were all taken back, and no buttons', async () => {
    const { RetailError } = await import('../../../api/retail');
    saleReceipt.mockRejectedValue(new RetailError('Every item of this sale was taken back, so it has no receipt to share.', 422, 'sale_fully_returned'));
    screenOf(seller, sale);
    expect((await screen.findByRole('alert')).textContent).toMatch(/taken back/);
    expect(screen.queryByRole('button', { name: 'Share receipt' })).toBeNull();
  });
});

describe('WhatsApp customer', () => {
  it('opens wa.me with the buyer number from the receipt facts and the caption, and says the PDF goes through Share receipt', async () => {
    screenOf(seller, sale);
    expect((await phoneBox()).value).toBe('0700 000 031');
    expect(screen.getByText(WHATSAPP_HINT)).toBeTruthy();
    fireEvent.click(await whatsAppButton());
    expect(open).toHaveBeenCalledTimes(1);
    const url = String(open.mock.calls[0]?.[0]);
    expect(url.startsWith('https://wa.me/256700000031?text=')).toBe(true);
    expect(decodeURIComponent(url.split('?text=')[1] ?? '')).toContain('Customer: Test Buyer 01');
  });

  it('refuses an ambiguous number in plain words and opens nothing', async () => {
    screenOf(seller, sale);
    fireEvent.change(await phoneBox(), { target: { value: '256700000' } });
    fireEvent.click(await whatsAppButton());
    expect(screen.getByRole('alert').textContent).toMatch(/256 with digits missing/);
    expect(open).not.toHaveBeenCalled();
  });

  it('opens even when a name holds half an emoji', async () => {
    saleReceipt.mockResolvedValue({ ...facts, buyer_name: 'Test Buyer \uD83D' });
    screenOf(seller, sale);
    fireEvent.click(await whatsAppButton());
    expect(open).toHaveBeenCalledTimes(1);
  });

  it('offers to save a typed number on a buyer with no number, unticked, and saves it only when ticked', async () => {
    const onAccount: Sale = { ...sale, buyer_name: undefined, buyer_contact: undefined, customer_id: 'c1', payment_method: 'credit', paid_minor: 0, balance_minor: 25000 };
    saleReceipt.mockResolvedValue({ ...facts, buyer_name: 'Test Buyer 02', buyer_contact: undefined, customer_id: 'c1', customer_version: 3, payment_method: 'credit', paid_minor: 0, balance_minor: 25000 });
    updateCustomer.mockResolvedValue({ id: 'c1', name: 'Test Buyer 02', contact: '+256700000032', version: 4 });
    screenOf(seller, onAccount);
    fireEvent.change(await phoneBox(), { target: { value: '0700000032' } });
    const keep = (await screen.findByLabelText(/Save this number for Test Buyer 02/)) as HTMLInputElement;
    expect(keep.checked).toBe(false);
    fireEvent.click(keep);
    fireEvent.click(await whatsAppButton());
    expect(String(open.mock.calls[0]?.[0])).toContain('Balance%20outstanding%3A%20UGX%2025%2C000');
    await waitFor(() => expect(updateCustomer).toHaveBeenCalledWith('c1', 3, { contact: '+256700000032' }));
  });

  it('does not save the number when the box is left unticked', async () => {
    saleReceipt.mockResolvedValue({ ...facts, buyer_contact: undefined, customer_id: 'c1', customer_version: 3 });
    screenOf(seller, { ...sale, buyer_contact: undefined, customer_id: 'c1' });
    fireEvent.change(await phoneBox(), { target: { value: '0700000033' } });
    await screen.findByLabelText(/Save this number for/);
    fireEvent.click(await whatsAppButton());
    expect(open).toHaveBeenCalledTimes(1);
    expect(updateCustomer).not.toHaveBeenCalled();
  });

  it('does not offer the save to someone who may not edit buyers at this branch', async () => {
    saleReceipt.mockResolvedValue({ ...facts, buyer_contact: undefined, customer_id: 'c1', customer_version: 3 });
    const elsewhere = { ...seller, permission_scopes: { ...seller.permission_scopes, 'retail.customer.manage': { all_branches: false, branch_ids: ['00000000-0000-4000-8000-0000000000b9'] } } };
    screenOf(elsewhere, { ...sale, buyer_contact: undefined, customer_id: 'c1' });
    fireEvent.change(await phoneBox(), { target: { value: '0700000034' } });
    expect(screen.queryByLabelText(/Save this number for/)).toBeNull();
  });
});

describe('who sees it', () => {
  it('is not offered to a seller for a sale another seller made, and is offered to an administrator', async () => {
    const theirs: Sale = { ...sale, created_by: '00000000-0000-4000-8000-0000000000f9' };
    screenOf(seller, theirs);
    expect(screen.queryByRole('button', { name: 'Share receipt' })).toBeNull();
    expect(saleReceipt).not.toHaveBeenCalled();
    cleanup();
    screenOf(admin, theirs);
    expect(await shareButton()).toBeTruthy();
  });

  it('is not offered for a voided or a written-off sale', () => {
    screenOf(admin, { ...sale, status: 'voided' });
    expect(screen.queryByRole('button', { name: 'Share receipt' })).toBeNull();
    cleanup();
    screenOf(admin, { ...sale, write_off: { reason: 'Test reason', written_off_on: '2026-10-22', unpaid_minor: 1000 } });
    expect(screen.queryByRole('button', { name: 'Share receipt' })).toBeNull();
    expect(saleReceipt).not.toHaveBeenCalled();
  });
});
