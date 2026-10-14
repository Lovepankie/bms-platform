// @vitest-environment jsdom
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RetailError, type GoodsReceipt, type PurchaseOrder, type SupplierBillMatch, type SupplierOpenItem, type SupplierPayables } from '../../../api/retail';
import { retailMessage } from '../../../api/retail-errors';
import { router as appRouter } from '../../../app/router';
import { GoodsReceiptView } from './goods-received';
import { ReceiveFormView } from './goods-receive';
import { PurchaseOrderView } from './purchase-order';
import { OrderFormView } from './purchase-order-form';
import { PurchasingSettingsForm } from './purchasing-settings';
import { billLinesFrom, blankBill, blankPayment, buildBillRequest, buildPaymentRequest, type BillForm, type PaymentForm } from './payables-state';
import { blankOrder, blankReceipt, buildOrderRequest, buildReceiptRequest, orderLineFor, receiveFormFor, type OrderForm, type ReceiveForm } from './purchasing-state';
import { ReasonPanel } from './purchasing-ui';
import { BillFormView } from './supplier-bill-new';
import { PaymentFormView } from './supplier-payment-new';
import { PayablesReport } from './supplier-payables';

// The purchasing forms as a person uses them (the purchasing capability, ADR-033, issue #198): an order totals as items are added and
// waits for a supplier, a receipt starts from what is still to come and shows the server's refusal in plain words, an invoice shows
// the check against the goods received and asks why when it differs, a payment checks a chosen split, and the report totals what
// is owed. Fabricated suppliers, items and amounts.

afterEach(cleanup);

const suppliers = [{ id: 's1', name: 'Test Supplier 01', active: true }, { id: 's2', name: 'Test Supplier 02', active: true }];
const branches = [{ id: 'b1', name: 'Test Branch A', code: 'BR1' }, { id: 'b2', name: 'Test Store B', code: 'ST2' }];
const type = (label: RegExp | string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const button = (name: string | RegExp) => screen.getByRole('button', { name }) as HTMLButtonElement;
const bulb = { id: 'p1', code: 'P003', description: 'Test bulb', unit: 'piece', cost_minor: 3500, sell_minor: 6000, packs: [{ id: 'k1', unit_id: 'u-box', unit: 'box', factor: '12.000', active: true }] };

describe('the purchase order form', () => {
  function Form({ onSubmit, units = false }: { onSubmit: (form: OrderForm) => void; units?: boolean }) {
    const [form, setForm] = useState<OrderForm>(blankOrder('b1'));
    const picker = <button type="button" onClick={() => setForm((f) => ({ ...f, lines: [...f.lines, orderLineFor(bulb, units)] }))}>Add Test bulb</button>;
    return <OrderFormView form={form} suppliers={suppliers} branches={[branches[0]!]} picker={picker} onChange={setForm} onSubmit={() => onSubmit(form)} />;
  }

  it('waits for a supplier and an item, totals the lines as they are typed, and saves a draft', () => {
    const sent = vi.fn();
    render(<Form onSubmit={sent} />);
    expect(button('Save draft order').disabled).toBe(true);
    expect(screen.getByTestId('order-total').textContent).toContain('UGX 0');
    type('Supplier', 's1');
    fireEvent.click(button('Add Test bulb'));
    expect(screen.getByTestId('order-line-0')).toBeTruthy();
    // The item's own cost is suggested; the quantity is the person's to type.
    expect((screen.getByLabelText(/Cost of one piece of Test bulb/) as HTMLInputElement).value).toBe('3500');
    expect(button('Save draft order').disabled).toBe(true);
    type(/How many Test bulb/, '10');
    expect(screen.getByTestId('order-line-total-0').textContent).toContain('UGX 35,000');
    expect(screen.getByTestId('order-total').textContent).toContain('UGX 35,000');
    type(/Cost of one piece of Test bulb/, '3400');
    expect(screen.getByTestId('order-total').textContent).toContain('UGX 34,000');
    expect(button('Save draft order').disabled).toBe(false);
    fireEvent.click(button('Save draft order'));
    expect(buildOrderRequest(sent.mock.calls[0]?.[0] as OrderForm)).toEqual({ supplier_id: 's1', branch_id: 'b1', lines: [{ product_id: 'p1', qty: '10.000', cost_minor: 3400 }] });
  });

  it('adds two lines, removes one, and rounds a fractional line half up', () => {
    render(<Form onSubmit={() => undefined} />);
    fireEvent.click(button('Add Test bulb'));
    fireEvent.click(button('Add Test bulb'));
    type('Supplier', 's1');
    const qty = screen.getAllByLabelText(/How many Test bulb/);
    const cost = screen.getAllByLabelText(/Cost of one piece of Test bulb/);
    fireEvent.change(qty[0]!, { target: { value: '2.5' } });
    fireEvent.change(cost[0]!, { target: { value: '1001' } });
    fireEvent.change(qty[1]!, { target: { value: '1' } });
    fireEvent.change(cost[1]!, { target: { value: '500' } });
    expect(screen.getByTestId('order-line-total-0').textContent).toContain('UGX 2,503');
    expect(screen.getByTestId('order-total').textContent).toContain('UGX 3,003');
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove Test bulb' })[0]!);
    expect(screen.getAllByLabelText(/How many Test bulb/)).toHaveLength(1);
    expect(screen.getByTestId('order-total').textContent).toContain('UGX 500');
  });

  it('offers packs only with the units capability, and a pack suggests its own cost', () => {
    const { unmount } = render(<Form onSubmit={() => undefined} />);
    fireEvent.click(button('Add Test bulb'));
    expect(screen.queryByLabelText('Ordered by')).toBeNull();
    unmount();
    render(<Form onSubmit={() => undefined} units />);
    fireEvent.click(button('Add Test bulb'));
    type('Ordered by', 'u-box');
    expect((screen.getByLabelText(/Cost of one box of Test bulb/) as HTMLInputElement).value).toBe('42000');
    expect(screen.getByLabelText(/How many Test bulb \(box\)/)).toBeTruthy();
  });

  it('shows the server\'s refusal in plain words', () => {
    const error = new RetailError(retailMessage({ code: 'order_not_draft' }, 409), 409, 'order_not_draft');
    render(<OrderFormView form={blankOrder('b1')} suppliers={suppliers} branches={[branches[0]!]} picker={null} error={error} onChange={() => undefined} onSubmit={() => undefined} />);
    expect(screen.getByRole('alert').textContent).toMatch(/sent already/i);
  });
});

describe('the purchase order screen', () => {
  const order: PurchaseOrder = {
    id: 'o1', order_no: 'PO-0001', status: 'partly_received', supplier_name: 'Test Supplier 01', branch_id: 'b1', ordered_on: '2026-10-01', total_minor: 35000,
    lines: [{ id: 'ol1', description: 'Test bulb', unit: 'piece', qty: '10.000', unit_qty: '10.000', received_qty: '4.000', unit_cost_minor: 3500, line_total_minor: 35000 }],
    receipts: [{ id: 'r1', receipt_no: 'GR-0001', received_on: '2026-10-05', status: 'posted', total_minor: 14000 }],
  };
  const view = (o: PurchaseOrder) => (
    <RouterContextProvider router={createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) })}>
      <PurchaseOrderView order={o} nameOf={() => 'Test Branch A'} />
    </RouterContextProvider>
  );

  it('shows ordered and received, and billed columns only when the server sent them', () => {
    const { unmount } = render(view(order));
    const text = screen.getByTestId('purchase-order').textContent ?? '';
    expect(text).toContain('Partly received');
    expect(text).toContain('10 piece');
    expect(text).toContain('4 piece');
    expect(screen.queryByRole('columnheader', { name: 'Billed' })).toBeNull();
    expect(screen.getByTestId('order-receipts').textContent).toContain('GR-0001');
    unmount();
    render(view({ ...order, lines: [{ ...order.lines![0]!, billed_qty: '4.000', billed_minor: 14000 }] }));
    expect(screen.getByRole('columnheader', { name: 'Billed' })).toBeTruthy();
  });
});

describe('the goods received form', () => {
  const order: PurchaseOrder = {
    id: 'o1', order_no: 'PO-0001', status: 'sent', supplier_id: 's1', supplier_name: 'Test Supplier 01', branch_id: 'b1',
    lines: [
      { id: 'ol1', product_id: 'p1', code: 'P003', description: 'Test bulb', unit: 'piece', qty: '10.000', unit_qty: '10.000', received_qty: '4.000', unit_cost_minor: 3500 },
      { id: 'ol2', product_id: 'p2', code: 'P004', description: 'Test cable', unit: 'roll', qty: '2.000', unit_qty: '2.000', received_qty: '0.000', unit_cost_minor: 90000 },
    ],
  };
  function Form({ onSubmit, error }: { onSubmit: (form: ReceiveForm) => void; error?: unknown }) {
    const [form, setForm] = useState<ReceiveForm>(receiveFormFor(order, 'b1'));
    return <ReceiveFormView form={form} blind={false} order={order} suppliers={[]} branches={[branches[0]!]} error={error} onChange={setForm} onSubmit={() => onSubmit(form)} />;
  }

  it('starts with what is still to come at the ordered cost, and sends only what really arrived', () => {
    const sent = vi.fn();
    render(<Form onSubmit={sent} />);
    expect((screen.getByLabelText(/Received of Test bulb/) as HTMLInputElement).value).toBe('6');
    expect((screen.getByLabelText(/Received of Test cable/) as HTMLInputElement).value).toBe('2');
    expect((screen.getByLabelText(/Cost of one piece of Test bulb/) as HTMLInputElement).value).toBe('3500');
    expect(screen.getByTestId('receive-total').textContent).toContain('UGX 201,000');
    // Only four of the six bulbs arrived, and none of the cable.
    type(/Received of Test bulb/, '4');
    type(/Received of Test cable/, '');
    expect(screen.getByTestId('receive-total').textContent).toContain('UGX 14,000');
    fireEvent.click(button('Save goods received'));
    expect(buildReceiptRequest(sent.mock.calls[0]?.[0] as ReceiveForm, 'o1')).toMatchObject({ order_id: 'o1', branch_id: 'b1', lines: [{ order_line_id: 'ol1', qty: '4.000', cost_minor: 3500 }] });
  });

  it('warns about more than was ordered, and shows the server\'s refusal of an over-receipt in plain words', () => {
    const error = new RetailError(retailMessage({ code: 'receipt_exceeds_order' }, 422), 422, 'receipt_exceeds_order');
    render(<Form onSubmit={() => undefined} error={error} />);
    type(/Received of Test bulb/, '7');
    expect(screen.getByText(/more than was ordered/i)).toBeTruthy();
    expect(screen.getAllByRole('alert').map((a) => a.textContent).join(' ')).toMatch(/more is being received than was ordered/i);
  });

  it('refuses a quantity that is not a number, and a new price that is not an amount', () => {
    render(<Form onSubmit={() => undefined} />);
    type(/Received of Test bulb/, 'many');
    expect(button('Save goods received').disabled).toBe(true);
    type(/Received of Test bulb/, '3');
    type(/New selling price of Test bulb/, 'cheap');
    expect(button('Save goods received').disabled).toBe(true);
    type(/New selling price of Test bulb/, '6500');
    expect(button('Save goods received').disabled).toBe(false);
  });

  it('asks for the supplier only when there is no order, and offers only the branches given', () => {
    const { unmount } = render(<Form onSubmit={() => undefined} />);
    expect(screen.queryByLabelText('Supplier')).toBeNull();
    expect(screen.getByText(/Test Supplier 01/)).toBeTruthy();
    unmount();
    render(<ReceiveFormView form={blankReceipt('')} blind suppliers={suppliers} branches={branches} picker={null} onChange={() => undefined} onSubmit={() => undefined} />);
    expect(screen.getByLabelText('Supplier')).toBeTruthy();
    const where = screen.getByLabelText('The goods go into') as HTMLSelectElement;
    expect(within(where).getAllByRole('option').map((o) => o.textContent)).toEqual(['Choose a branch or store', 'Test Branch A (BR1)', 'Test Store B (ST2)']);
    expect(button('Save goods received').disabled).toBe(true);
  });
});

describe('a delivery on screen', () => {
  const receipt: GoodsReceipt = {
    id: 'r1', receipt_no: 'GR-0001', status: 'posted', supplier_name: 'Test Supplier 01', received_on: '2026-10-05', branch_id: 'b1', total_minor: 14000,
    lines: [{ id: 'l1', description: 'Test bulb', unit: 'piece', qty: '4.000', unit_qty: '4.000', entered_cost_minor: 3500, line_total_minor: 14000 }],
  };

  it('says there was no order for a blind delivery and shows what each line cost', () => {
    render(<RouterContextProvider router={createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) })}><GoodsReceiptView receipt={receipt} nameOf={() => 'Test Branch A'} /></RouterContextProvider>);
    const text = screen.getByTestId('goods-receipt').textContent ?? '';
    expect(text).toContain('None, goods received without an order');
    expect(text).toContain('UGX 3,500');
    expect(text).toContain('UGX 14,000');
  });

  it('needs a reason of five characters to void, and says why a refusal happened in plain words', () => {
    const onSubmit = vi.fn();
    const error = new RetailError(retailMessage({ code: 'receipt_billed' }, 409), 409, 'receipt_billed');
    render(<ReasonPanel openLabel="Void this delivery" question="Why is it voided?" confirmLabel="Void the delivery" error={error} onSubmit={onSubmit} />);
    fireEvent.click(button('Void this delivery'));
    const go = button('Void the delivery');
    expect(go.disabled).toBe(true);
    type(/Why is it voided/, 'abcd');
    expect(go.disabled).toBe(true);
    type(/Why is it voided/, 'Wrong delivery');
    expect(go.disabled).toBe(false);
    expect(screen.getByRole('alert').textContent).toMatch(/already on a supplier invoice/i);
    fireEvent.click(go);
    expect(onSubmit).toHaveBeenCalledWith('Wrong delivery');
  });
});

describe('the supplier invoice form', () => {
  const unbilled = [
    { receipt_line_id: 'rl1', receipt_no: 'GR-0001', order_no: 'PO-0001', description: 'Test bulb', unit: 'piece', unbilled_qty: '10.000', unit_cost_minor: 3500 },
    { receipt_line_id: 'rl2', receipt_no: 'GR-0002', description: 'Test cable', unit: 'roll', unbilled_qty: '2.000', unit_cost_minor: 90000 },
  ];
  const initial = (): BillForm => ({ ...blankBill('2026-10-12'), supplierId: 's1', lines: billLinesFrom(unbilled) });
  const match = (over: Partial<SupplierBillMatch> = {}): SupplierBillMatch => ({
    total_minor: 36000, cleared_minor: 35000, variance_minor: 1000, tolerance_minor: 350, reason_required: false, total_mismatch: false,
    lines: [{ receipt_line_id: 'rl1', description: 'Test bulb', line_total_minor: 36000, cleared_minor: 35000, variance_minor: 1000 }], ...over,
  });
  function Form({ m, onSubmit }: { m?: SupplierBillMatch; onSubmit: (form: BillForm) => void }) {
    const [form, setForm] = useState<BillForm>(initial());
    return <BillFormView form={form} suppliers={suppliers} match={m} onChange={setForm} onSubmit={() => onSubmit(form)} />;
  }
  const fill = () => {
    fireEvent.click(screen.getByLabelText(/Bill Test bulb/));
    type('Supplier\'s invoice number', 'INV-77');
    type('Due on', '2026-11-11');
    type(/Supplier's price for one piece/, '3600');
    type('Invoice total, as printed', '36000');
  };

  it('lists the goods waiting for an invoice, unticked, and shows the lines once ticked', () => {
    render(<Form onSubmit={() => undefined} />);
    expect(screen.getByLabelText(/Bill Test bulb/)).toBeTruthy();
    expect(screen.getByLabelText(/Bill Test cable/)).toBeTruthy();
    expect(screen.queryByLabelText(/Quantity billed/)).toBeNull();
    expect(button('Save supplier invoice').disabled).toBe(true);
    fireEvent.click(screen.getByLabelText(/Bill Test bulb/));
    expect((screen.getByLabelText(/Quantity billed of Test bulb/) as HTMLInputElement).value).toBe('10');
    expect((screen.getByLabelText(/Supplier's price for one piece/) as HTMLInputElement).value).toBe('3500');
  });

  it('shows the check line by line: what the invoice says, what the goods were received at, and the difference', () => {
    render(<Form m={match()} onSubmit={() => undefined} />);
    const panel = within(screen.getByTestId('bill-match'));
    expect(panel.getByRole('columnheader', { name: 'Invoice says' })).toBeTruthy();
    expect(panel.getByRole('columnheader', { name: 'Received at' })).toBeTruthy();
    expect(panel.getByRole('columnheader', { name: 'Difference' })).toBeTruthy();
    const text = screen.getByTestId('bill-match').textContent ?? '';
    expect(text).toContain('UGX 36,000');
    expect(text).toContain('UGX 35,000');
    expect(screen.getByTestId('bill-variance').textContent).toBe('The invoice is UGX 1,000 more than what the goods were received at.');
    expect(text).toContain('up to UGX 350');
    expect(screen.queryByTestId('bill-reason-required')).toBeNull();
  });

  it('saves a matching invoice without a reason', () => {
    const sent = vi.fn();
    render(<Form m={match({ variance_minor: 0, total_minor: 35000 })} onSubmit={sent} />);
    fill();
    type('Invoice total, as printed', '36000');
    expect(button('Save supplier invoice').disabled).toBe(false);
    fireEvent.click(button('Save supplier invoice'));
    expect(buildBillRequest(sent.mock.calls[0]?.[0] as BillForm)).toMatchObject({ supplier_ref: 'INV-77', total_minor: 36000, lines: [{ receipt_line_id: 'rl1', qty: '10.000', unit_price_minor: 3600 }] });
  });

  it('warns and asks why when the difference is above the tolerance, and waits for five characters', () => {
    const sent = vi.fn();
    render(<Form m={match({ reason_required: true })} onSubmit={sent} />);
    fill();
    expect(screen.getByTestId('bill-reason-required').textContent).toMatch(/say why/i);
    expect(button('Save supplier invoice').disabled).toBe(true);
    type(/Why does the invoice differ/, 'abcd');
    expect(button('Save supplier invoice').disabled).toBe(true);
    type(/Why does the invoice differ/, 'Price rose');
    expect(button('Save supplier invoice').disabled).toBe(false);
    fireEvent.click(button('Save supplier invoice'));
    expect(buildBillRequest(sent.mock.calls[0]?.[0] as BillForm)).toMatchObject({ variance_reason: 'Price rose' });
  });

  it('stops at a total that is not the sum of the lines', () => {
    render(<Form m={match({ total_mismatch: true })} onSubmit={() => undefined} />);
    fill();
    expect(screen.getByTestId('bill-total-mismatch').textContent).toMatch(/not the sum of the lines/i);
    expect(button('Save supplier invoice').disabled).toBe(true);
  });

  it('shows no reason box when none is needed', () => {
    render(<Form m={match()} onSubmit={() => undefined} />);
    expect(screen.queryByLabelText(/Why does the invoice differ/)).toBeNull();
  });
});

describe('the supplier payment form', () => {
  const items: SupplierOpenItem[] = [
    { id: 'b1', kind: 'bill', number: 'SB-000001', supplier_ref: 'INV-77', open_minor: 30000, due_date: '2026-10-01', days_past_due: 11 },
    { id: 'r9', kind: 'restock', number: 'P-000009', open_minor: 12000, due_date: '2026-10-12', days_past_due: 0 },
  ];
  function Form({ onSubmit }: { onSubmit: (form: PaymentForm) => void }) {
    const [form, setForm] = useState<PaymentForm>(blankPayment('s1', 'b1'));
    return <PaymentFormView form={form} suppliers={suppliers} branches={[branches[0]!]} items={items} owed={42000} onChange={setForm} onSubmit={() => onSubmit(form)} />;
  }
  const save = () => button('Save payment');

  it('pays the oldest first by default, shows what is owed, and refuses more than that', () => {
    const sent = vi.fn();
    render(<Form onSubmit={sent} />);
    expect(screen.getByTestId('owed-total').textContent).toContain('UGX 42,000');
    expect(screen.getByTestId('owed-list').textContent).toContain('11 days late');
    expect(save().disabled).toBe(true);
    type('Amount paid', '40000');
    expect(save().disabled).toBe(false);
    type('Amount paid', '42001');
    expect(save().disabled).toBe(true);
    expect(screen.getAllByRole('alert')[0]?.textContent).toMatch(/owed UGX 42,000/);
    type('Amount paid', '40000');
    fireEvent.click(save());
    expect(buildPaymentRequest(sent.mock.calls[0]?.[0] as PaymentForm, items)).toEqual({ supplier_id: 's1', branch_id: 'b1', method: 'cash', amount_minor: 40000, allocation: 'oldest_first' });
  });

  it('lets the person choose, shows what is left to give out, and saves only when it adds up', () => {
    const sent = vi.fn();
    render(<Form onSubmit={sent} />);
    type('Amount paid', '20000');
    fireEvent.click(screen.getByLabelText('Let me choose'));
    expect(screen.getByTestId('pay-manual')).toBeTruthy();
    expect(save().disabled).toBe(true);
    expect(screen.getByTestId('pay-remaining').textContent).toContain('UGX 20,000 is left to give out');
    type(/Pay on Supplier invoice SB-000001/, '8000');
    expect(screen.getByTestId('pay-remaining').textContent).toContain('UGX 12,000 is left to give out');
    expect(save().disabled).toBe(true);
    type(/Pay on Credit restock P-000009/, '13000');
    expect(screen.getByTestId('pay-remaining').textContent).toContain('UGX 1,000 too much');
    expect(screen.getAllByRole('alert')[0]?.textContent).toMatch(/at most UGX 12,000/i);
    expect(save().disabled).toBe(true);
    type(/Pay on Credit restock P-000009/, '12000');
    expect(screen.getByTestId('pay-remaining').textContent).toContain('Nothing is left to give out');
    expect(save().disabled).toBe(false);
    fireEvent.click(save());
    expect(buildPaymentRequest(sent.mock.calls[0]?.[0] as PaymentForm, items)).toMatchObject({
      allocation: 'manual', amount_minor: 20000, allocations: [{ bill_id: 'b1', amount_minor: 8000 }, { purchase_id: 'r9', amount_minor: 12000 }],
    });
  });

  it('offers cash, mobile money and bank', () => {
    render(<Form onSubmit={() => undefined} />);
    const method = screen.getByLabelText('Paid by') as HTMLSelectElement;
    expect(within(method).getAllByRole('option').map((o) => o.textContent)).toEqual(['Cash', 'Mobile money', 'Bank']);
  });
});

describe('the payables report', () => {
  const row = { supplier_id: 's1', supplier_name: 'Test Supplier 01', not_due_minor: 12000, days1_to30_minor: 20000, days31_to60_minor: 10000, days61_to90_minor: 5000, over90_minor: 3000, owed_minor: 50000, unbilled_receipts_minor: 35000 };
  const report: SupplierPayables = {
    as_of: '2026-10-12', currency: 'UGX', suppliers: [row, { ...row, supplier_id: 's2', supplier_name: 'Test Supplier 02', owed_minor: 7000, not_due_minor: 7000, days1_to30_minor: 0, days31_to60_minor: 0, days61_to90_minor: 0, over90_minor: 0, unbilled_receipts_minor: 0 }],
    totals: { owed_minor: 57000, not_due_minor: 19000, days1_to30_minor: 20000, days31_to60_minor: 10000, days61_to90_minor: 5000, over90_minor: 3000, unbilled_receipts_minor: 35000 },
  };
  const page = (r: SupplierPayables, phone = false) => (
    <RouterContextProvider router={createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) })}>
      <PayablesReport report={r} phone={phone} />
    </RouterContextProvider>
  );

  it('has one row for each supplier split by how late it is, a totals row, the date, and a way to pay each supplier', () => {
    render(page(report));
    for (const heading of ['Not due yet', '1 to 30 days late', '31 to 60 days late', '61 to 90 days late', 'Over 90 days late', 'Owed in all', 'Received, not invoiced']) {
      expect(screen.getByRole('columnheader', { name: heading })).toBeTruthy();
    }
    const first = within(screen.getByTestId('payable-s1')).getAllByRole('cell').map((c) => c.textContent);
    expect(first).toEqual(['Test Supplier 01', 'UGX 12,000', 'UGX 20,000', 'UGX 10,000', 'UGX 5,000', 'UGX 3,000', 'UGX 50,000', 'UGX 35,000']);
    expect(within(screen.getByTestId('payables-totals')).getByText('UGX 57,000')).toBeTruthy();
    expect(screen.getByTestId('payables-report').textContent).toContain('12 Oct 2026');
    expect(screen.getByRole('link', { name: 'Pay Test Supplier 01' }).getAttribute('href')).toBe('/staff/retail/supplier-payments/new?supplier=s1');
    // The accounting term appears only as a small explanation beside the plain words.
    expect(screen.getByTestId('payables-report').textContent).toMatch(/goods received not invoiced/);
  });

  it('is one card for each supplier on a phone', () => {
    render(page(report, true));
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.getByTestId('payable-s2').textContent).toContain('UGX 7,000');
    expect(screen.getByTestId('payables-totals').textContent).toContain('UGX 57,000');
  });

  it('says so when nothing is owed', () => {
    render(page({ as_of: '2026-10-12', suppliers: [], totals: {} }));
    expect(screen.getByText(/owe no supplier anything/i)).toBeTruthy();
  });
});

describe('the purchasing settings', () => {
  it('shows the tolerances as percent, saves basis points, and shows the approval limit read only when there is one', () => {
    const onSave = vi.fn();
    render(<PurchasingSettingsForm settings={{ bill_tolerance_bp: 100, over_receipt_bp: 250, order_approval_threshold_minor: 5000000, version: 3 }} onSave={onSave} />);
    expect((screen.getByLabelText(/supplier invoice may differ/) as HTMLInputElement).value).toBe('1.00');
    expect((screen.getByLabelText(/more than the order may be received/) as HTMLInputElement).value).toBe('2.50');
    expect(screen.getByTestId('order-threshold').textContent).toContain('UGX 5,000,000');
    expect(button('Save purchasing settings').disabled).toBe(true);
    type(/supplier invoice may differ/, '1.5');
    expect(button('Save purchasing settings').disabled).toBe(false);
    fireEvent.click(button('Save purchasing settings'));
    expect(onSave).toHaveBeenCalledWith({ bill_tolerance_bp: 150, over_receipt_bp: 250, require_costing_before_sale: false });
  });

  it('saves the costing switch on its own, with its one-sentence explanation (issue #199)', () => {
    const onSave = vi.fn();
    render(<PurchasingSettingsForm settings={{ bill_tolerance_bp: 100, over_receipt_bp: 0, require_costing_before_sale: false, version: 2 }} onSave={onSave} />);
    const box = screen.getByLabelText(/Do not move goods out until their landed cost is in/) as HTMLInputElement;
    expect(box.checked).toBe(false);
    expect(screen.getByText(/cannot be moved out of its branch/).textContent).toMatch(/held, not costed/);
    expect(button('Save purchasing settings').disabled).toBe(true);
    fireEvent.click(box);
    expect(button('Save purchasing settings').disabled).toBe(false);
    fireEvent.click(button('Save purchasing settings'));
    expect(onSave).toHaveBeenCalledWith({ bill_tolerance_bp: 100, over_receipt_bp: 0, require_costing_before_sale: true });
  });

  it('refuses a percent that is not 0 to 100 with two decimals, and says no limit is set when the server sent none', () => {
    render(<PurchasingSettingsForm settings={{ bill_tolerance_bp: 0, over_receipt_bp: 0, version: 1 }} onSave={() => undefined} />);
    expect(screen.getByTestId('order-threshold').textContent).toMatch(/no approval limit/i);
    type(/supplier invoice may differ/, '1.234');
    expect(button('Save purchasing settings').disabled).toBe(true);
    expect(screen.getByRole('alert').textContent).toMatch(/0 to 100/);
    type(/supplier invoice may differ/, '101');
    expect(button('Save purchasing settings').disabled).toBe(true);
  });
});
