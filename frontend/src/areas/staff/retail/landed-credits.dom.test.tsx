// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Outlet, RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import { retail } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { stepsFor } from '../../../tour/model';
import { purchasingCapability } from '../../../tour/tours';
import { StaffContext } from '../context';
import { GoodsReceiptPage, GoodsReceivedPage } from './goods-received';
import { ReceiveFormView } from './goods-receive';
import { LandedCostPage, LandedCostsPage } from './landed-costs';
import { LandedCostNewPage } from './landed-cost-new';
import { SupplierBillPage } from './supplier-bills';
import { SupplierCreditNewPage } from './supplier-credit-new';
import { SupplierCreditPage, SupplierCreditsPage } from './supplier-credits';
import { blankReceipt, buildReceiptRequest, type ReceiveForm } from './purchasing-state';
import { screensFor } from './permissions';

// Landed costs and supplier credit notes end to end on the fabricated in-memory API (issue #199): the real pages, their queries,
// drafts and buttons, against the mock that follows the server's rules in small. Each test mounts one page on a small router that
// has the page's own route id, so the page finds its address parameters. Fabricated suppliers, items and amounts.

const BRANCH = mockMe('admin').branches?.[0]?.id ?? '';
const SUPPLIER = 's0000000-0000-4000-8000-000000000001';
const FORWARDER = 's0000000-0000-4000-8000-000000000002';
const ADMIN: Me = { ...mockMe('admin'), module_capabilities: { retail: ['purchasing'] } };

afterEach(cleanup);

beforeEach(async () => {
  sessionStorage.clear();
  Object.assign(retail, createMockRetail());
  const read = await retail.capabilities();
  await retail.changeCapabilities({ purchasing: true }, read.version);
});

const NAV_TARGETS = [
  '/landed-costs', '/landed-costs/new', '/landed-costs/$landedCostId', '/supplier-credits', '/supplier-credits/new', '/supplier-credits/$creditId',
  '/goods-received/$receiptId', '/supplier-bills/$billId',
];

function mount(path: string, Page: () => JSX.Element, url: string, me: Me = ADMIN) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const root = createRootRoute({
    component: () => (
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: BRANCH }}>
          <Outlet />
        </StaffContext.Provider>
      </QueryClientProvider>
    ),
  });
  const staff = createRoute({ getParentRoute: () => root, path: '/staff' });
  const retailRoute = createRoute({ getParentRoute: () => staff, path: '/retail' });
  const page = createRoute({ getParentRoute: () => retailRoute, path, component: Page });
  // Where a saved form goes next, and where a link points: stubs, so the navigation resolves.
  const stubs = NAV_TARGETS.filter((t) => t !== path).map((t) => createRoute({ getParentRoute: () => retailRoute, path: t, component: () => <p>Opened {t}</p> }));
  const router = createRouter({
    routeTree: root.addChildren([staff.addChildren([retailRoute.addChildren([page, ...stubs])])]),
    history: createMemoryHistory({ initialEntries: [url] }),
  });
  return { router, ...render(<RouterProvider router={router} />) };
}

const type = (label: RegExp | string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
const wait = { timeout: 4000 };
const key = () => `k-${Math.random()}`;

async function bulbId(): Promise<string> {
  return (await retail.listProducts({ query: 'P003' }))[0]?.id ?? '';
}

/** A delivery of ten bulbs at 3,500 each, received against a sent order. */
async function delivered(over: { hold?: boolean; qty?: string } = {}) {
  const draft = await retail.createPurchaseOrder({ supplier_id: SUPPLIER, branch_id: BRANCH, lines: [{ product_id: await bulbId(), qty: over.qty ?? '10', cost_minor: 3500 }] }, key());
  const order = (await retail.sendPurchaseOrder(draft.id ?? '', key())).order!;
  return retail.createGoodsReceipt({ order_id: order.id, branch_id: BRANCH, hold_for_costing: over.hold, lines: [{ order_line_id: order.lines?.[0]?.id, qty: over.qty ?? '10' }] }, key());
}

async function landedFor(receipt: Awaited<ReturnType<typeof delivered>>, over: Record<string, unknown> = {}) {
  return retail.createLandedCost({
    supplier_id: FORWARDER, supplier_ref: `FWD-${Math.random()}`, due_date: '2030-01-01', total_minor: 10000,
    charges: [{ kind: 'freight', amount_minor: 6000 }, { kind: 'duty', amount_minor: 4000 }], lines: [{ receipt_line_id: receipt.lines?.[0]?.id ?? '' }], ...over,
  }, key());
}

async function invoiced(qty = '10') {
  const receipt = await delivered({ qty });
  return retail.createSupplierBill({
    supplier_id: SUPPLIER, supplier_ref: `INV-${Math.random()}`, bill_date: '2026-10-12', due_date: '2030-01-01', total_minor: Number(qty) * 3500,
    lines: [{ receipt_line_id: receipt.lines?.[0]?.id ?? '', qty, unit_price_minor: 3500 }],
  }, key());
}

describe('the new landed cost page', () => {
  it('takes the payee, the charges and the goods, reviews each share from the server, and saves', async () => {
    const receipt = await delivered();
    const { router } = mount('/landed-costs/new', LandedCostNewPage, '/staff/retail/landed-costs/new');
    await screen.findByRole('option', { name: 'Test Forwarder 01' }, wait);
    type(/Who is owed/, FORWARDER);
    type('Their invoice number', 'FWD-77');
    type('Due on', '2030-01-01');
    type('Amount of charge 1', '6000');
    click('Add a charge');
    type('Kind of charge 2', 'duty');
    type('Amount of charge 2', '4000');
    expect(screen.getByTestId('landed-charges-total').textContent).toContain('UGX 10,000');
    // A total that is not the sum of the charges stops the review, and says so.
    type('Invoice total, as printed', '9000');
    expect(screen.getByTestId('landed-total-mismatch')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Review' }) as HTMLButtonElement).disabled).toBe(true);
    type('Invoice total, as printed', '10000');
    fireEvent.click(await screen.findByRole('button', { name: `Add ${receipt.receipt_no}` }, wait));
    const line = (await screen.findByLabelText(/Share over LED bulb 9W screw/, undefined, wait)) as HTMLInputElement;
    expect(line.checked).toBe(true);
    await waitFor(() => expect((screen.getByRole('button', { name: 'Review' }) as HTMLButtonElement).disabled).toBe(false), wait);
    click('Review');
    const review = await screen.findByTestId('landed-review', undefined, wait);
    await waitFor(() => expect(within(review).getByTestId('landed-allocation').textContent).toContain('UGX 10,000'), wait);
    // A reader of profit sees the stock and the cost effect.
    expect(within(review).getByTestId('landed-allocation').textContent).toMatch(/Added to the stock you still have/);
    expect(within(review).getByTestId('landed-allocation').textContent).toMatch(/Cost of one goes from UGX 3,500 to UGX 4,500/);
    expect(await retail.listLandedCosts({})).toMatchObject({ items: [] });
    click('Save landed cost');
    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/landed-costs\/[^/]+$/), wait);
    const saved = (await retail.listLandedCosts({})).items ?? [];
    expect(saved).toHaveLength(1);
    expect(saved[0]).toMatchObject({ supplier_ref: 'FWD-77', total_minor: 10000, method: 'value', status: 'posted' });
    const bill = await retail.getSupplierBill(saved[0]?.bill_id ?? '');
    expect(bill).toMatchObject({ kind: 'landed_cost', total_minor: 10000, open_minor: 10000 });
  });

  it('asks for weights only for the weight method and for shares only for typed amounts, and checks they add up', async () => {
    const receipt = await delivered();
    mount('/landed-costs/new', LandedCostNewPage, `/staff/retail/landed-costs/new?receipt=${receipt.id}`);
    await screen.findByLabelText(/Share over LED bulb 9W screw/, undefined, wait);
    await screen.findByRole('option', { name: 'Test Forwarder 01' }, wait);
    expect(screen.queryByLabelText(/Weight of LED bulb/)).toBeNull();
    expect(screen.queryByLabelText(/Share of LED bulb/)).toBeNull();
    fireEvent.click(screen.getByLabelText(/By weight/));
    expect(screen.getByLabelText(/Weight of LED bulb 9W screw/)).toBeTruthy();
    fireEvent.click(screen.getByLabelText(/Amounts I type/));
    expect(screen.queryByLabelText(/Weight of LED bulb/)).toBeNull();
    type('Amount of charge 1', '10000');
    type('Invoice total, as printed', '10000');
    type(/Who is owed/, FORWARDER);
    type('Their invoice number', 'FWD-78');
    type('Due on', '2030-01-01');
    type(/Share of LED bulb 9W screw/, '4000');
    expect(screen.getByTestId('landed-shares-left').textContent).toMatch(/UGX 6,000 of the total is not given to a line yet/);
    expect((screen.getByRole('button', { name: 'Review' }) as HTMLButtonElement).disabled).toBe(true);
    type(/Share of LED bulb 9W screw/, '10000');
    expect(screen.getByTestId('landed-shares-left').textContent).toMatch(/add up to the total/);
    expect((screen.getByRole('button', { name: 'Review' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('shows the server\'s refusal in plain words and keeps the form', async () => {
    const receipt = await delivered();
    mount('/landed-costs/new', LandedCostNewPage, `/staff/retail/landed-costs/new?receipt=${receipt.id}`);
    await screen.findByLabelText(/Share over LED bulb 9W screw/, undefined, wait);
    await screen.findByRole('option', { name: 'Test Forwarder 01' }, wait);
    type(/Who is owed/, FORWARDER);
    type('Their invoice number', 'FWD-79');
    type('Due on', '2030-01-01');
    type('Amount of charge 1', '5000');
    type('Invoice total, as printed', '5000');
    await waitFor(() => expect((screen.getByRole('button', { name: 'Review' }) as HTMLButtonElement).disabled).toBe(false), wait);
    // The same invoice number is recorded elsewhere first, so the save is refused.
    await landedFor(receipt, { supplier_ref: 'FWD-79' });
    click('Review');
    await screen.findByTestId('landed-review', undefined, wait);
    await waitFor(() => expect((screen.getByRole('button', { name: 'Save landed cost' }) as HTMLButtonElement).disabled).toBe(false), wait);
    click('Save landed cost');
    expect((await screen.findByRole('alert', undefined, wait)).textContent).toMatch(/already has an invoice with this number/i);
  });
});

describe('the landed costs pages', () => {
  it('lists landed costs with what is paid and owed, found by search', async () => {
    const receipt = await delivered();
    const made = await landedFor(receipt, { supplier_ref: 'FWD-LIST' });
    mount('/landed-costs', LandedCostsPage, '/staff/retail/landed-costs');
    const row = await screen.findByTestId(`landed-${made.landed_no}`, undefined, wait);
    expect(row.textContent).toContain('Test Forwarder 01');
    expect(row.textContent).toContain('UGX 10,000');
    expect(row.textContent).toContain('Paid UGX 0, still owed UGX 10,000');
    type('Search by our number, the invoice number or who is owed', 'nothing like this');
    expect(await screen.findByText(/No landed costs for "nothing like this"/, undefined, wait)).toBeTruthy();
  });

  it('shows the charges, the share of each line and the invoice, and voids with a reason', async () => {
    const receipt = await delivered();
    const made = await landedFor(receipt);
    mount('/landed-costs/$landedCostId', LandedCostPage, `/staff/retail/landed-costs/${made.id}`);
    const view = await screen.findByTestId('landed-cost', undefined, wait);
    expect(view.textContent).toContain('Import duty');
    expect(view.textContent).toContain('Freight');
    expect(view.textContent).toContain('Cost of one goes from UGX 3,500 to UGX 4,500');
    expect(within(view).getByRole('link', { name: made.bill_no ?? '' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Void this landed cost' }));
    expect((screen.getByRole('button', { name: 'Void the landed cost' }) as HTMLButtonElement).disabled).toBe(true);
    type(/Why is it voided/, 'Wrong goods');
    click('Void the landed cost');
    await waitFor(async () => expect((await retail.getLandedCost(made.id ?? '')).status).toBe('voided'), wait);
    expect((await retail.getSupplierBill(made.bill_id ?? '')).status).toBe('voided');
  });

  it('says in plain words why a void is refused once the payee\'s invoice has been paid', async () => {
    const receipt = await delivered();
    const made = await landedFor(receipt);
    await retail.createSupplierPayment({ supplier_id: FORWARDER, branch_id: BRANCH, method: 'cash', amount_minor: 4000 } as never, key());
    mount('/landed-costs/$landedCostId', LandedCostPage, `/staff/retail/landed-costs/${made.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Void this landed cost' }, wait));
    type(/Why is it voided/, 'Wrong goods');
    click('Void the landed cost');
    expect((await screen.findByRole('alert', undefined, wait)).textContent).toMatch(/This invoice has been paid.*Reverse the payment first/i);
    expect((await retail.getLandedCost(made.id ?? '')).status).toBe('posted');
  });

  it('leaves out what only a reader of profit may see', async () => {
    const receipt = await delivered();
    const made = await landedFor(receipt);
    // The mock sends the profit figures to everyone, so the page is given a body without them, as the server sends it to anyone else.
    const bare = { ...made, lines: (made.lines ?? []).map((l) => ({ ...l, allocated_minor: undefined, basis: undefined, to_inventory_minor: undefined, to_cogs_minor: undefined, cost_before_minor: undefined, cost_after_minor: undefined })) };
    Object.assign(retail, { getLandedCost: async () => bare });
    mount('/landed-costs/$landedCostId', LandedCostPage, `/staff/retail/landed-costs/${made.id}`);
    const view = await screen.findByTestId('landed-cost', undefined, wait);
    // The share of each line is for people who can read costs; the total of the charges is not.
    expect(view.textContent).toContain('Amounts are shown to people who can read costs');
    expect(view.textContent).not.toContain('Share of the charges');
    expect(view.textContent).not.toContain('Added to the stock you still have');
    expect(view.textContent).toContain('Total of the charges: UGX 10,000');
  });
});

describe('no access without the permission', () => {
  const buyer: Me = { ...ADMIN, permissions: ['retail.purchase.create', 'retail.stock.read'] };

  it('shows the no-access state on every new route, and offers no tile or tour step', async () => {
    mount('/landed-costs', LandedCostsPage, '/staff/retail/landed-costs', buyer);
    expect(await screen.findByText('You do not have access to this page.')).toBeTruthy();
    cleanup();
    mount('/supplier-credits', SupplierCreditsPage, '/staff/retail/supplier-credits', buyer);
    expect(await screen.findByText('You do not have access to this page.')).toBeTruthy();
    cleanup();
    mount('/landed-costs/new', LandedCostNewPage, '/staff/retail/landed-costs/new', buyer);
    expect(await screen.findByText('You do not have access to this page.')).toBeTruthy();
    cleanup();
    mount('/supplier-credits/new', SupplierCreditNewPage, '/staff/retail/supplier-credits/new', buyer);
    expect(await screen.findByText('You do not have access to this page.')).toBeTruthy();
    const tiles = screensFor(buyer).map((s) => s.screen);
    expect(tiles).toContain('purchaseOrders');
    expect(tiles).not.toContain('landedCosts');
    expect(tiles).not.toContain('supplierCredits');
    const steps = stepsFor(purchasingCapability, buyer).map((s) => s.id);
    expect(steps).toContain('tile');
    expect(steps).not.toContain('tile-landed');
    expect(steps).not.toContain('tile-credits');
    expect(stepsFor(purchasingCapability, ADMIN).map((s) => s.id)).toEqual(expect.arrayContaining(['tile-landed', 'tile-credits']));
  });

  it('offers neither tile nor the routes\' entry with purchasing off', () => {
    const off: Me = { ...ADMIN, module_capabilities: { retail: ['branch_roles'] } };
    const tiles = screensFor(off).map((s) => s.screen);
    expect(tiles).not.toContain('landedCosts');
    expect(tiles).not.toContain('supplierCredits');
    expect(screensFor(ADMIN).map((s) => s.screen)).toEqual(expect.arrayContaining(['landedCosts', 'supplierCredits']));
  });
});

describe('the new credit note page', () => {
  it('opens with the invoice from the address, shows what was billed, and sends goods back', async () => {
    const bill = await invoiced('10');
    const before = Number((await retail.listProducts({ query: 'P003', branchId: BRANCH }))[0]?.qty);
    const { router } = mount('/supplier-credits/new', SupplierCreditNewPage, `/staff/retail/supplier-credits/new?bill=${bill.id}`);
    const card = await screen.findByTestId('credit-line-0', undefined, wait);
    expect(card.textContent).toMatch(/billed 10 piece at UGX 3,500 each/);
    expect(card.textContent).toMatch(/Up to 10 piece can still be sent back/);
    type(/Quantity of LED bulb 9W screw sent back/, '11');
    expect(within(card).getByRole('alert').textContent).toMatch(/at most 10 piece/i);
    type(/Quantity of LED bulb 9W screw sent back/, '2');
    expect(screen.getByTestId('credit-line-total-0').textContent).toContain('UGX 7,000');
    type("Supplier's credit note number", 'CN-77');
    type(/Why is it given/, 'Damaged on arrival');
    // A total that is not the sum of the lines stops the save.
    type('Credit total, as printed', '6000');
    expect(screen.getByTestId('credit-problem').textContent).toMatch(/UGX 6,000 but the lines come to UGX 7,000/);
    expect((screen.getByRole('button', { name: 'Save credit note' }) as HTMLButtonElement).disabled).toBe(true);
    type('Credit total, as printed', '7000');
    click('Save credit note');
    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/supplier-credits\/[^/]+$/), wait);
    const credits = (await retail.listSupplierCredits({})).items ?? [];
    expect(credits).toHaveLength(1);
    expect(credits[0]).toMatchObject({ kind: 'goods', total_minor: 7000, supplier_ref: 'CN-77', bill_id: bill.id });
    expect(await retail.getSupplierBill(bill.id ?? '')).toMatchObject({ credited_minor: 7000, open_minor: 28000 });
    expect(Number((await retail.listProducts({ query: 'P003', branchId: BRANCH }))[0]?.qty)).toBe(before - 2);
  });

  it('finds an invoice by search when none was chosen, and offers only invoices that can take a credit note', async () => {
    const bill = await invoiced('4');
    const paid = await invoiced('2');
    await retail.createSupplierPayment({ supplier_id: SUPPLIER, branch_id: BRANCH, method: 'cash', amount_minor: 7000, allocation: 'manual', allocations: [{ bill_id: paid.id, amount_minor: 7000 }] } as never, key());
    expect((await retail.getSupplierBill(paid.id ?? '')).open_minor).toBe(0);
    mount('/supplier-credits/new', SupplierCreditNewPage, '/staff/retail/supplier-credits/new');
    expect(await screen.findByRole('button', { name: `Credit invoice ${bill.bill_no}` }, wait)).toBeTruthy();
    expect(screen.queryByRole('button', { name: `Credit invoice ${paid.bill_no}` })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: `Credit invoice ${bill.bill_no}` }));
    expect(await screen.findByTestId('credit-invoice', undefined, wait)).toBeTruthy();
    expect(screen.getByTestId('credit-invoice').textContent).toContain('UGX 14,000');
  });

  it('takes money off a line for a price adjustment, and shows the server\'s refusal of too much in plain words', async () => {
    const bill = await invoiced('10');
    mount('/supplier-credits/new', SupplierCreditNewPage, `/staff/retail/supplier-credits/new?bill=${bill.id}`);
    await screen.findByTestId('credit-line-0', undefined, wait);
    fireEvent.click(screen.getByLabelText(/A price adjustment/));
    type(/Amount to take off LED bulb 9W screw/, '1500');
    type("Supplier's credit note number", 'CN-PRICE');
    type(/Why is it given/, 'Wrong price');
    type('Credit total, as printed', '1500');
    // Another credit note takes most of the invoice first, so the save is refused by the server.
    await retail.createSupplierCredit({ bill_id: bill.id ?? '', kind: 'price', supplier_ref: 'CN-OTHER', total_minor: 34000, reason: 'Wrong price', lines: [{ bill_line_id: bill.lines?.[0]?.id ?? '', amount_minor: 34000 }] }, key());
    click('Save credit note');
    expect((await screen.findAllByRole('alert', undefined, wait)).map((x) => x.textContent).join(' ')).toMatch(/more than the invoice charged for it/i);
    expect((await retail.listSupplierCredits({})).items).toHaveLength(1);
  });

  it('counts what earlier credit notes already took against the limit', async () => {
    const bill = await invoiced('10');
    await retail.createSupplierCredit({ bill_id: bill.id ?? '', kind: 'goods', supplier_ref: 'CN-1', total_minor: 14000, reason: 'Damaged', lines: [{ bill_line_id: bill.lines?.[0]?.id ?? '', qty: '4' }] }, key());
    mount('/supplier-credits/new', SupplierCreditNewPage, `/staff/retail/supplier-credits/new?bill=${bill.id}`);
    const card = await screen.findByTestId('credit-line-0', undefined, wait);
    await waitFor(() => expect(card.textContent).toMatch(/Earlier credit notes took 4 piece/), wait);
    expect(card.textContent).toMatch(/Up to 6 piece can still be sent back/);
  });
});

describe('the supplier credit note pages', () => {
  it('lists credit notes and shows one with its lines', async () => {
    const bill = await invoiced('10');
    const made = await retail.createSupplierCredit({ bill_id: bill.id ?? '', kind: 'goods', supplier_ref: 'CN-LIST', total_minor: 7000, reason: 'Damaged', lines: [{ bill_line_id: bill.lines?.[0]?.id ?? '', qty: '2' }] }, key());
    mount('/supplier-credits', SupplierCreditsPage, '/staff/retail/supplier-credits');
    const row = await screen.findByTestId(`credit-${made.credit_no}`, undefined, wait);
    expect(row.textContent).toContain('Goods sent back');
    expect(row.textContent).toContain('UGX 7,000');
    cleanup();
    mount('/supplier-credits/$creditId', SupplierCreditPage, `/staff/retail/supplier-credits/${made.id}`);
    const view = await screen.findByTestId('supplier-credit', undefined, wait);
    expect(view.textContent).toContain('CN-LIST');
    expect(view.textContent).toMatch(/2 piece/);
    expect(view.textContent).toContain('Damaged');
  });

  it('voids with a reason, puts the invoice back, and refuses a second void in plain words', async () => {
    const bill = await invoiced('10');
    const made = await retail.createSupplierCredit({ bill_id: bill.id ?? '', kind: 'goods', supplier_ref: 'CN-VOID', total_minor: 7000, reason: 'Damaged', lines: [{ bill_line_id: bill.lines?.[0]?.id ?? '', qty: '2' }] }, key());
    mount('/supplier-credits/$creditId', SupplierCreditPage, `/staff/retail/supplier-credits/${made.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Void this credit note' }, wait));
    expect((screen.getByRole('button', { name: 'Void the credit note' }) as HTMLButtonElement).disabled).toBe(true);
    type(/Why is it voided/, 'Sent back by mistake');
    click('Void the credit note');
    await waitFor(async () => expect((await retail.getSupplierCredit(made.id ?? '')).status).toBe('voided'), wait);
    expect(await retail.getSupplierBill(bill.id ?? '')).toMatchObject({ credited_minor: 0, open_minor: 35000 });
  });
});

describe('the supplier invoice, delivery and receive screens with landed costs and credit notes', () => {
  it('shows credited and still owed, offers a credit note, and keeps the void for a goods invoice', async () => {
    const bill = await invoiced('10');
    await retail.createSupplierCredit({ bill_id: bill.id ?? '', kind: 'goods', supplier_ref: 'CN-1', total_minor: 7000, reason: 'Damaged', lines: [{ bill_line_id: bill.lines?.[0]?.id ?? '', qty: '2' }] }, key());
    mount('/supplier-bills/$billId', SupplierBillPage, `/staff/retail/supplier-bills/${bill.id}`);
    const view = await screen.findByTestId('supplier-bill', undefined, wait);
    expect(view.textContent).toContain('Taken off by credit notes');
    expect(view.textContent).toContain('UGX 7,000');
    expect(view.textContent).toContain('UGX 28,000');
    expect(screen.getByRole('link', { name: 'Credit note' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Void this invoice' })).toBeTruthy();
  });

  it('labels the invoice of a landed cost, links back to it, and offers no void and no credit note', async () => {
    const made = await landedFor(await delivered());
    mount('/supplier-bills/$billId', SupplierBillPage, `/staff/retail/supplier-bills/${made.bill_id}`);
    const view = await screen.findByTestId('supplier-bill', undefined, wait);
    expect(view.textContent).toContain('The invoice of a landed cost');
    expect(await screen.findByRole('link', { name: 'the landed cost' }, wait)).toBeTruthy();
    expect(screen.getByTestId('landed-bill-note').textContent).toMatch(/cannot be voided here/);
    expect(screen.queryByRole('button', { name: 'Void this invoice' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Credit note' })).toBeNull();
    expect(within(view).queryByText('Goods were received at')).toBeNull();
  });

  it('marks a held delivery, shows the landed amount, adds a landed cost, and releases a hold with a reason', async () => {
    const held = await delivered({ hold: true });
    mount('/goods-received/$receiptId', GoodsReceiptPage, `/staff/retail/goods-received/${held.id}`);
    const view = await screen.findByTestId('goods-receipt', undefined, wait);
    expect(view.textContent).toContain('Held, not costed');
    expect(screen.getByTestId('receipt-held')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Add landed cost' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Release the hold' }));
    // The panel opened: its one button has the same name, and waits for a reason.
    expect((screen.getByRole('button', { name: 'Release the hold' }) as HTMLButtonElement).disabled).toBe(true);
    type(/Why does this delivery need no landed cost/, 'No freight on this one');
    click('Release the hold');
    await waitFor(async () => expect((await retail.getGoodsReceipt(held.id ?? '')).held_for_costing).toBe(false), wait);
  });

  it('shows the amount a landed cost added to each line, and the held badge in the list', async () => {
    const receipt = await delivered({ hold: true });
    mount('/goods-received', GoodsReceivedPage, '/staff/retail/goods-received');
    const row = await screen.findByTestId(`receipt-${receipt.receipt_no}`, undefined, wait);
    expect(row.textContent).toContain('Held, not costed');
    cleanup();
    await landedFor(receipt);
    mount('/goods-received/$receiptId', GoodsReceiptPage, `/staff/retail/goods-received/${receipt.id}`);
    const view = await screen.findByTestId('goods-receipt', undefined, wait);
    expect(view.textContent).toContain('Landed costs added');
    expect(view.textContent).toContain('UGX 10,000');
    expect(view.textContent).not.toContain('Held, not costed');
    expect(screen.queryByRole('button', { name: 'Release the hold' })).toBeNull();
  });

  it('sends hold_for_costing only when the box is ticked on the receive form', () => {
    function Form({ onChange }: { onChange: (f: ReceiveForm) => void }) {
      const [form, setForm] = useState<ReceiveForm>({ ...blankReceipt('b1'), receivedOn: '2026-10-12' });
      return (
        <ReceiveFormView form={form} blind suppliers={[{ id: 's1', name: 'Test Supplier 01', active: true }]} branches={[{ id: 'b1', name: 'Test Branch A', code: 'BR1' }]} picker={null} onChange={(f) => { setForm(f); onChange(f); }} onSubmit={() => undefined} />
      );
    }
    let latest: ReceiveForm | undefined;
    render(<Form onChange={(f) => { latest = f; }} />);
    const box = screen.getByLabelText('Hold until the landed cost is in') as HTMLInputElement;
    expect(box.checked).toBe(false);
    expect(buildReceiptRequest({ ...blankReceipt('b1'), supplierId: 's1' })).not.toHaveProperty('hold_for_costing');
    fireEvent.click(box);
    expect(latest?.hold).toBe(true);
    expect(buildReceiptRequest(latest!)).toMatchObject({ hold_for_costing: true });
  });
});
