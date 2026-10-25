// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Outlet, RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { retail } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { GoodsReceiptPage } from './goods-received';
import { ReceiveAgainstOrderPage } from './goods-receive';
import { PurchaseOrderPage } from './purchase-order';
import { PurchaseOrderEditPage, PurchaseOrderNewPage } from './purchase-order-form';
import { SupplierBillNewPage } from './supplier-bill-new';
import { SupplierPaymentNewPage } from './supplier-payment-new';
import { pickItem } from '../../../test-utils/item-picker';

// The purchasing pages end to end on the fabricated in-memory API (issue #198): the real pages, their queries, drafts and
// buttons, against the mock that follows the server's rules in small. Each test mounts one page on a small router that has the
// page's own route id, so the page finds its address parameters. Fabricated suppliers, items and amounts.

const BRANCH = mockMe('admin').branches?.[0]?.id ?? '';
const SUPPLIER = 's0000000-0000-4000-8000-000000000001';
const ADMIN = { ...mockMe('admin'), module_capabilities: { retail: ['purchasing'] } };

afterEach(cleanup);

beforeEach(async () => {
  sessionStorage.clear();
  // The pages talk to `retail`; here it is the in-memory API, with purchasing switched on.
  Object.assign(retail, createMockRetail());
  const read = await retail.capabilities();
  await retail.changeCapabilities({ purchasing: true }, read.version);
});

const NAV_TARGETS = ['/purchase-orders/$orderId', '/goods-received/$receiptId', '/supplier-bills/$billId', '/supplier-payments/$paymentId'];

function mount(path: string, Page: () => JSX.Element, url: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const root = createRootRoute({
    component: () => (
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: ADMIN, branch: BRANCH }}>
          <Outlet />
        </StaffContext.Provider>
      </QueryClientProvider>
    ),
  });
  const staff = createRoute({ getParentRoute: () => root, path: '/staff' });
  const retailRoute = createRoute({ getParentRoute: () => staff, path: '/retail' });
  const page = createRoute({ getParentRoute: () => retailRoute, path, component: Page });
  // Where a saved form goes next: a stub, so the navigation resolves.
  const stubs = NAV_TARGETS.filter((t) => t !== path).map((t) => createRoute({ getParentRoute: () => retailRoute, path: t, component: () => <p>Opened {t}</p> }));
  const router = createRouter({
    routeTree: root.addChildren([staff.addChildren([retailRoute.addChildren([page, ...stubs])])]),
    history: createMemoryHistory({ initialEntries: [url] }),
  });
  return { router, client, ...render(<RouterProvider router={router} />) };
}

const type = (label: RegExp | string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
const wait = { timeout: 4000 };

async function bulbId(): Promise<string> {
  return (await retail.listProducts({ query: 'P003' }))[0]?.id ?? '';
}

async function sentOrder(qty = '10', cost = 3500) {
  const draft = await retail.createPurchaseOrder({ supplier_id: SUPPLIER, branch_id: BRANCH, lines: [{ product_id: await bulbId(), qty, cost_minor: cost }] }, `k-${Math.random()}`);
  const sent = await retail.sendPurchaseOrder(draft.id ?? '', `k-${Math.random()}`);
  return sent.order!;
}

describe('the purchase order pages', () => {
  it('saves a new order as a draft from the items found, the supplier and the quantities', async () => {
    const { router } = mount('/purchase-orders/new', PurchaseOrderNewPage, '/staff/retail/purchase-orders/new');
    await pickItem('LED bulb 9W screw');
    await screen.findByRole('option', { name: 'Test Supplier 01' }, wait);
    type('Supplier', SUPPLIER);
    type(/How many LED bulb 9W screw/, '10');
    expect(screen.getByTestId('order-total').textContent).toContain('UGX 35,000');
    click('Save draft order');
    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/staff\/retail\/purchase-orders\/[^/]+$/), wait);
    const saved = (await retail.listPurchaseOrders({})).items ?? [];
    expect(saved).toHaveLength(1);
    expect(saved[0]).toMatchObject({ status: 'draft', total_minor: 35000, supplier_id: SUPPLIER });
  });

  it('changes a draft under its version and shows the new total on the order', async () => {
    const draft = await retail.createPurchaseOrder({ supplier_id: SUPPLIER, branch_id: BRANCH, lines: [{ product_id: await bulbId(), qty: '10', cost_minor: 3500 }] }, 'k-edit');
    mount('/purchase-orders/$orderId/edit', PurchaseOrderEditPage, `/staff/retail/purchase-orders/${draft.id}/edit`);
    const qty = (await screen.findByLabelText(/How many LED bulb 9W screw/, undefined, wait)) as HTMLInputElement;
    expect(qty.value).toBe('10');
    fireEvent.change(qty, { target: { value: '12' } });
    click('Save changes');
    await waitFor(async () => expect((await retail.getPurchaseOrder(draft.id ?? '')).total_minor).toBe(42000), wait);
    expect((await retail.getPurchaseOrder(draft.id ?? '')).version).toBe(2);
  });

  it('keeps unsaved edits when someone else changed the order and a refresh brings the new version', async () => {
    const draft = await retail.createPurchaseOrder({ supplier_id: SUPPLIER, branch_id: BRANCH, lines: [{ product_id: await bulbId(), qty: '10', cost_minor: 3500 }] }, 'k-keep');
    const { client } = mount('/purchase-orders/$orderId/edit', PurchaseOrderEditPage, `/staff/retail/purchase-orders/${draft.id}/edit`);
    const qty = (await screen.findByLabelText(/How many LED bulb 9W screw/, undefined, wait)) as HTMLInputElement;
    fireEvent.change(qty, { target: { value: '12' } });
    // A colleague saves the same order, then the buyer taps Refresh (the header button refetches the active queries).
    await retail.updatePurchaseOrder(draft.id ?? '', draft.version ?? 1, { supplier_id: SUPPLIER, branch_id: BRANCH, lines: [{ product_id: await bulbId(), qty: '20', cost_minor: 3500 }] });
    await client.refetchQueries({ type: 'active' });
    expect((await screen.findByRole('alert', undefined, wait)).textContent).toMatch(/Someone else changed this order/);
    expect((screen.getByLabelText(/How many LED bulb 9W screw/) as HTMLInputElement).value).toBe('12');
    click(/Discard my changes/);
    await waitFor(() => expect((screen.getByLabelText(/How many LED bulb 9W screw/) as HTMLInputElement).value).toBe('20'), wait);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('refuses a save made over a version the buyer never read, and leaves the colleague\'s lines in place', async () => {
    const draft = await retail.createPurchaseOrder({ supplier_id: SUPPLIER, branch_id: BRANCH, lines: [{ product_id: await bulbId(), qty: '10', cost_minor: 3500 }] }, 'k-stale');
    const { client } = mount('/purchase-orders/$orderId/edit', PurchaseOrderEditPage, `/staff/retail/purchase-orders/${draft.id}/edit`);
    fireEvent.change((await screen.findByLabelText(/How many LED bulb 9W screw/, undefined, wait)) as HTMLInputElement, { target: { value: '12' } });
    await retail.updatePurchaseOrder(draft.id ?? '', draft.version ?? 1, { supplier_id: SUPPLIER, branch_id: BRANCH, lines: [{ product_id: await bulbId(), qty: '20', cost_minor: 3500 }] });
    await client.refetchQueries({ type: 'active' });
    await screen.findByText(/Someone else changed this order/, undefined, wait);
    click('Save changes');
    await waitFor(() => expect(screen.getAllByRole('alert').some((a) => /changed by someone else/i.test(a.textContent ?? ''))).toBe(true), wait);
    const saved = await retail.getPurchaseOrder(draft.id ?? '');
    expect(saved.version).toBe(2);
    expect(Number(saved.lines?.[0]?.qty)).toBe(20);
  });

  it('sends an order and then offers to receive goods against it', async () => {
    const draft = await retail.createPurchaseOrder({ supplier_id: SUPPLIER, branch_id: BRANCH, lines: [{ product_id: await bulbId(), qty: '10', cost_minor: 3500 }] }, 'k-send');
    mount('/purchase-orders/$orderId', PurchaseOrderPage, `/staff/retail/purchase-orders/${draft.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Send to the supplier' }, wait));
    await waitFor(() => expect(screen.getAllByRole('status').some((s) => /Sent .* You can now receive goods against it/.test(s.textContent ?? ''))).toBe(true), wait);
    expect(await screen.findByRole('link', { name: 'Receive goods' }, wait)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Send to the supplier' })).toBeNull();
  });

  it('says a large order waits for a second person, and keeps it a draft', async () => {
    const draft = await retail.createPurchaseOrder({ supplier_id: SUPPLIER, branch_id: BRANCH, lines: [{ product_id: await bulbId(), qty: '1000', cost_minor: 5_000_000 }] }, 'k-big');
    mount('/purchase-orders/$orderId', PurchaseOrderPage, `/staff/retail/purchase-orders/${draft.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Send to the supplier' }, wait));
    await waitFor(() => expect(screen.getAllByRole('status').some((s) => /second person.*stays a draft/.test(s.textContent ?? ''))).toBe(true), wait);

    expect((await retail.getPurchaseOrder(draft.id ?? '')).status).toBe('draft');
    expect(screen.queryByRole('link', { name: 'Receive goods' })).toBeNull();
  });

  it('cancels with a reason', async () => {
    const order = await sentOrder();
    mount('/purchase-orders/$orderId', PurchaseOrderPage, `/staff/retail/purchase-orders/${order.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel this order' }, wait));
    expect((screen.getByRole('button', { name: 'Cancel the order' }) as HTMLButtonElement).disabled).toBe(true);
    type(/Why is it cancelled/, 'Ordered twice');
    click('Cancel the order');
    await waitFor(async () => expect((await retail.getPurchaseOrder(order.id ?? '')).status).toBe('cancelled'), wait);
  });
});

describe('the receive page', () => {
  it('fills what is still to come, shows the server\'s over-receipt refusal in plain words, and then saves the real quantity', async () => {
    const order = await sentOrder('10');
    const { router } = mount('/purchase-orders/$orderId/receive', ReceiveAgainstOrderPage, `/staff/retail/purchase-orders/${order.id}/receive`);
    const qty = (await screen.findByLabelText(/Received of LED bulb 9W screw/, undefined, wait)) as HTMLInputElement;
    expect(qty.value).toBe('10');
    fireEvent.change(qty, { target: { value: '99' } });
    click('Save goods received');
    expect((await screen.findAllByRole('alert', undefined, wait)).map((a) => a.textContent).join(' ')).toMatch(/more is being received than was ordered/i);
    fireEvent.change(qty, { target: { value: '6' } });
    click('Save goods received');
    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/goods-received\/[^/]+$/), wait);
    expect((await retail.getPurchaseOrder(order.id ?? '')).status).toBe('partly_received');
    const receipts = (await retail.listGoodsReceipts({})).items ?? [];
    expect(receipts).toHaveLength(1);
    expect(receipts[0]).toMatchObject({ total_minor: 21000, order_no: order.order_no });
  });
});

describe('a delivery', () => {
  it('cannot be voided once it is on a supplier invoice, and says so in plain words', async () => {
    const order = await sentOrder('10');
    const receipt = await retail.createGoodsReceipt({ order_id: order.id, branch_id: BRANCH, lines: [{ order_line_id: order.lines?.[0]?.id, qty: '10' }] }, 'k-receipt');
    await retail.createSupplierBill({ supplier_id: SUPPLIER, supplier_ref: 'INV-1', bill_date: '2026-10-12', due_date: '2026-11-11', total_minor: 35000, lines: [{ receipt_line_id: receipt.lines?.[0]?.id ?? '', qty: '10', unit_price_minor: 3500 }] }, 'k-bill');
    mount('/goods-received/$receiptId', GoodsReceiptPage, `/staff/retail/goods-received/${receipt.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Void this delivery' }, wait));
    type(/Why is it voided/, 'Wrong delivery');
    click('Void the delivery');
    expect((await screen.findByRole('alert', undefined, wait)).textContent).toMatch(/already on a supplier invoice/i);
    expect((await retail.getGoodsReceipt(receipt.id ?? '')).status).toBe('posted');
  });

  it('is voided when nothing stands in the way', async () => {
    const receipt = await retail.createGoodsReceipt({ supplier_id: SUPPLIER, branch_id: BRANCH, lines: [{ product_id: await bulbId(), qty: '2', cost_minor: 3500 }] }, 'k-blind');
    mount('/goods-received/$receiptId', GoodsReceiptPage, `/staff/retail/goods-received/${receipt.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Void this delivery' }, wait));
    type(/Why is it voided/, 'Wrong delivery');
    click('Void the delivery');
    await waitFor(async () => expect((await retail.getGoodsReceipt(receipt.id ?? '')).status).toBe('voided'), wait);
  });
});

describe('the supplier invoice page', () => {
  it('checks the invoice against the goods received, asks why it differs, and saves it', async () => {
    const order = await sentOrder('10');
    await retail.createGoodsReceipt({ order_id: order.id, branch_id: BRANCH, lines: [{ order_line_id: order.lines?.[0]?.id, qty: '10' }] }, 'k-receipt');
    const { router } = mount('/supplier-bills/new', SupplierBillNewPage, '/staff/retail/supplier-bills/new');
    await screen.findByRole('option', { name: 'Test Supplier 01' }, wait);
    type('Supplier', SUPPLIER);
    fireEvent.click(await screen.findByLabelText(/Bill LED bulb 9W screw/, undefined, wait));
    type('Supplier\'s invoice number', 'INV-77');
    type('Due on', '2026-11-11');
    type('Invoice total, as printed', '36000');
    type(/Supplier's price for one piece/, '3600');
    // The check repeats after a short pause and shows the three figures.
    const panel = await screen.findByTestId('bill-match', undefined, wait);
    await waitFor(() => expect(panel.textContent).toContain('UGX 36,000'), wait);
    expect(panel.textContent).toContain('UGX 35,000');
    expect(await screen.findByTestId('bill-reason-required', undefined, wait)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Save supplier invoice' }) as HTMLButtonElement).getAttribute('data-not-ready')).toBe('true');
    type(/Why does the invoice differ/, 'Price rose');
    await waitFor(() => expect((screen.getByRole('button', { name: 'Save supplier invoice' }) as HTMLButtonElement).getAttribute('data-not-ready')).toBeNull(), wait);
    click('Save supplier invoice');
    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/supplier-bills\/[^/]+$/), wait);
    const bills = (await retail.listSupplierBills({})).items ?? [];
    expect(bills).toHaveLength(1);
    expect(bills[0]).toMatchObject({ supplier_ref: 'INV-77', total_minor: 36000, cleared_minor: 35000, variance_reason: 'Price rose' });
  });
});

describe('the supplier payment page', () => {
  it('opens with the supplier from the address, pays the oldest first, and saves', async () => {
    const order = await sentOrder('10');
    const receipt = await retail.createGoodsReceipt({ order_id: order.id, branch_id: BRANCH, lines: [{ order_line_id: order.lines?.[0]?.id, qty: '10' }] }, 'k-receipt');
    await retail.createSupplierBill({ supplier_id: SUPPLIER, supplier_ref: 'INV-1', bill_date: '2026-09-01', due_date: '2026-09-30', total_minor: 35000, lines: [{ receipt_line_id: receipt.lines?.[0]?.id ?? '', qty: '10', unit_price_minor: 3500 }] }, 'k-bill');
    const { router } = mount('/supplier-payments/new', SupplierPaymentNewPage, `/staff/retail/supplier-payments/new?supplier=${SUPPLIER}`);
    expect((await screen.findByTestId('owed-total', undefined, wait)).textContent).toContain('UGX 47,000');
    expect((screen.getByLabelText(/^Supplier/) as HTMLSelectElement).value).toBe(SUPPLIER);
    type(/Amount paid/, '40000');
    click('Save payment');
    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/supplier-payments\/[^/]+$/), wait);
    const payments = (await retail.listSupplierPayments({})).items ?? [];
    expect(payments).toHaveLength(1);
    const made = await retail.getSupplierPayment(payments[0]?.id ?? '');
    expect(made.allocations?.map((a) => a.amount_minor)).toEqual([35000, 5000]);
  });
});
