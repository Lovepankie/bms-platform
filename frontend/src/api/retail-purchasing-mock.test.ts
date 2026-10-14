import { describe, expect, it } from 'vitest';
import { createMockRetail, mockMe } from './retail-mock';
import { MOCK_ORDER_APPROVAL_MINOR } from './retail-purchasing-mock';
import type { GoodsReceiptRequest, PurchaseOrderRequest } from './retail';

// The mock's optional purchasing follows the server's rules in small (ADR-033, issue #198): off means 404, an order is a draft
// until sent (a large one waits for a second person), goods received may not exceed the order beyond the allowance and move
// stock, a supplier invoice shows the difference from what the goods were received at and needs a reason above the tolerance,
// a payment clears invoices and credit restocks, and every void or reversal puts things back. Fabricated data.

const branch = mockMe('admin').branches?.[0]?.id ?? '';
const supplier = 's0000000-0000-4000-8000-000000000001';

async function shop() {
  const api = createMockRetail();
  const read = await api.capabilities();
  await api.changeCapabilities({ purchasing: true }, read.version);
  const [bulb] = await api.listProducts({ query: 'P003' });
  return { api, productId: bulb?.id ?? '' };
}
type Api = Awaited<ReturnType<typeof shop>>['api'];

let n = 0;
const key = () => `key-${++n}`;
const orderBody = (productId: string, qty = '10', cost = 3500): PurchaseOrderRequest => ({ supplier_id: supplier, branch_id: branch, lines: [{ product_id: productId, qty, cost_minor: cost }] });

async function sentOrder(api: Api, productId: string, qty = '10') {
  const draft = await api.createPurchaseOrder(orderBody(productId, qty), key());
  const sent = await api.sendPurchaseOrder(draft.id ?? '', key());
  return sent.order!;
}

const receiveAgainst = (api: Api, orderId: string, lineId: string, qty: string, cost?: number) =>
  api.createGoodsReceipt({ order_id: orderId, branch_id: branch, lines: [{ order_line_id: lineId, qty, ...(cost !== undefined ? { cost_minor: cost } : {}) }] }, key());

const billFor = (receiptLineId: string, qty: string, price: number, over: Record<string, unknown> = {}) =>
  ({ supplier_id: supplier, supplier_ref: `INV-${++n}`, bill_date: '2026-10-12', due_date: '2026-11-11', total_minor: Math.round((Number(qty) * price)), lines: [{ receipt_line_id: receiptLineId, qty, unit_price_minor: price }], ...over });

describe('purchasing in the mock', () => {
  it('answers 404 capability_not_enabled on every route while the capability is off', async () => {
    const api = createMockRetail();
    await expect(api.listPurchaseOrders({})).rejects.toMatchObject({ status: 404, code: 'capability_not_enabled' });
    await expect(api.listGoodsReceipts({})).rejects.toMatchObject({ status: 404 });
    await expect(api.listSupplierBills({})).rejects.toMatchObject({ status: 404 });
    await expect(api.listSupplierPayments({})).rejects.toMatchObject({ status: 404 });
    await expect(api.supplierPayables()).rejects.toMatchObject({ status: 404 });
    await expect(api.purchasingSettings()).rejects.toMatchObject({ status: 404 });
  });

  it('keeps an order a draft with its totals, changes it under If-Match, and replays the same key', async () => {
    const { api, productId } = await shop();
    const key1 = key();
    const draft = await api.createPurchaseOrder(orderBody(productId), key1);
    expect(draft).toMatchObject({ status: 'draft', total_minor: 35000, version: 1, lines: [{ qty: '10.000', unit_cost_minor: 3500, line_total_minor: 35000 }] });
    expect((await api.createPurchaseOrder(orderBody(productId), key1)).id).toBe(draft.id);
    const edited = await api.updatePurchaseOrder(draft.id ?? '', 1, orderBody(productId, '12', 3000));
    expect(edited).toMatchObject({ total_minor: 36000, version: 2 });
    await expect(api.updatePurchaseOrder(draft.id ?? '', 1, orderBody(productId))).rejects.toMatchObject({ status: 409, code: 'version_conflict' });
    const listed = await api.listPurchaseOrders({ query: draft.order_no ?? '' });
    expect(listed.items).toHaveLength(1);
    expect(listed.items?.[0]?.lines).toBeUndefined();
    expect((await api.listPurchaseOrders({ status: 'sent' })).items).toHaveLength(0);
  });

  it('refuses to change or send a sent order, and waits for a second person above the approval threshold', async () => {
    const { api, productId } = await shop();
    const order = await sentOrder(api, productId);
    expect(order.status).toBe('sent');
    await expect(api.updatePurchaseOrder(order.id ?? '', order.version ?? 1, orderBody(productId))).rejects.toMatchObject({ code: 'order_not_draft' });
    await expect(api.sendPurchaseOrder(order.id ?? '', key())).rejects.toMatchObject({ code: 'order_not_draft' });
    const big = await api.createPurchaseOrder(orderBody(productId, '1000', MOCK_ORDER_APPROVAL_MINOR), key());
    const waiting = await api.sendPurchaseOrder(big.id ?? '', key());
    expect(waiting).toMatchObject({ executed: false });
    expect(waiting.approval_id).toBeTruthy();
    expect((await api.getPurchaseOrder(big.id ?? '')).status).toBe('draft');
  });

  it('cancels with a reason of five characters, and only while open', async () => {
    const { api, productId } = await shop();
    const order = await api.createPurchaseOrder(orderBody(productId), key());
    await expect(api.cancelPurchaseOrder(order.id ?? '', 'abc', key())).rejects.toMatchObject({ status: 422 });
    expect((await api.cancelPurchaseOrder(order.id ?? '', 'Ordered twice', key())).status).toBe('cancelled');
    await expect(api.cancelPurchaseOrder(order.id ?? '', 'Ordered twice', key())).rejects.toMatchObject({ code: 'order_closed' });
  });

  it('receives in parts, moves stock, refuses more than ordered and closes the order when everything arrived', async () => {
    const { api, productId } = await shop();
    const stockBefore = Number((await api.listProducts({ query: 'P003', branchId: branch }))[0]?.qty);
    const draft = await api.createPurchaseOrder(orderBody(productId), key());
    await expect(receiveAgainst(api, draft.id ?? '', draft.lines?.[0]?.id ?? '', '1')).rejects.toMatchObject({ code: 'order_not_sent' });
    const order = (await api.sendPurchaseOrder(draft.id ?? '', key())).order!;
    const lineId = order.lines?.[0]?.id ?? '';
    const first = await receiveAgainst(api, order.id ?? '', lineId, '4');
    expect(first).toMatchObject({ status: 'posted', total_minor: 14000, order_no: order.order_no, lines: [{ qty: '4.000', line_total_minor: 14000 }] });
    expect(Number((await api.listProducts({ query: 'P003', branchId: branch }))[0]?.qty)).toBe(stockBefore + 4);
    const partly = await api.getPurchaseOrder(order.id ?? '');
    expect(partly.status).toBe('partly_received');
    expect(partly.lines?.[0]).toMatchObject({ received_qty: '4.000', received_minor: 14000 });
    expect(partly.receipts).toHaveLength(1);
    await expect(receiveAgainst(api, order.id ?? '', lineId, '7')).rejects.toMatchObject({ code: 'receipt_exceeds_order' });
    await receiveAgainst(api, order.id ?? '', lineId, '6');
    expect((await api.getPurchaseOrder(order.id ?? '')).status).toBe('received');
    await expect(receiveAgainst(api, order.id ?? '', lineId, '1')).rejects.toMatchObject({ code: 'order_closed' });
  });

  it('lets the allowance take a receipt above the order', async () => {
    const { api, productId } = await shop();
    const read = await api.purchasingSettings();
    await api.changePurchasingSettings(read.version ?? 1, { over_receipt_bp: 1000 });
    const order = await sentOrder(api, productId);
    await expect(receiveAgainst(api, order.id ?? '', order.lines?.[0]?.id ?? '', '11')).resolves.toMatchObject({ status: 'posted' });
  });

  it('takes a blind receipt only with a supplier, and voids a receipt that put stock in', async () => {
    const { api, productId } = await shop();
    const blind: GoodsReceiptRequest = { branch_id: branch, lines: [{ product_id: productId, qty: '5', cost_minor: 3600 }] };
    await expect(api.createGoodsReceipt(blind, key())).rejects.toMatchObject({ status: 422 });
    const made = await api.createGoodsReceipt({ ...blind, supplier_id: supplier, delivery_ref: 'DN-1' }, key());
    expect(made).toMatchObject({ supplier_id: supplier, delivery_ref: 'DN-1', total_minor: 18000 });
    const voided = await api.voidGoodsReceipt(made.id ?? '', 'Wrong delivery', key());
    expect(voided.status).toBe('voided');
    await expect(api.voidGoodsReceipt(made.id ?? '', 'Wrong delivery', key())).rejects.toMatchObject({ code: 'receipt_voided' });
  });

  it('refuses to void a receipt whose stock has moved on, or that is on an invoice, and puts the order back when voided', async () => {
    const { api, productId } = await shop();
    const order = await sentOrder(api, productId, '5');
    const lineId = order.lines?.[0]?.id ?? '';
    const receipt = await receiveAgainst(api, order.id ?? '', lineId, '5');
    const onHand = Number((await api.listProducts({ query: 'P003', branchId: branch }))[0]?.qty);
    await api.createUsage({ branch_id: branch, kind: 'damage', reason: 'Dropped', lines: [{ product_id: productId, qty: String(onHand - 2) }] } as never, key());
    await expect(api.voidGoodsReceipt(receipt.id ?? '', 'Wrong delivery', key())).rejects.toMatchObject({ code: 'receipt_stock_moved' });
    const second = await sentOrder(api, productId, '2');
    const again = await receiveAgainst(api, second.id ?? '', second.lines?.[0]?.id ?? '', '2');
    const unbilled = await api.unbilledReceipts(supplier);
    const line = (unbilled.items ?? []).find((l) => l.receipt_id === again.id);
    await api.createSupplierBill(billFor(line?.receipt_line_id ?? '', '2', 3500), key());
    await expect(api.voidGoodsReceipt(again.id ?? '', 'Wrong delivery', key())).rejects.toMatchObject({ code: 'receipt_billed' });
    expect((await api.getPurchaseOrder(second.id ?? '')).status).toBe('received');
  });
});

describe('supplier invoices in the mock', () => {
  async function received(qty = '10', cost = 3500) {
    const { api, productId } = await shop();
    const order = await sentOrder(api, productId, qty);
    const receipt = await receiveAgainst(api, order.id ?? '', order.lines?.[0]?.id ?? '', qty, cost);
    return { api, productId, order, receipt, lineId: receipt.lines?.[0]?.id ?? '' };
  }

  it('lists what is received and not yet billed, and shrinks it as invoices stand', async () => {
    const { api, lineId } = await received();
    const before = await api.unbilledReceipts(supplier);
    expect(before).toMatchObject({ supplier_id: supplier, total_minor: 35000, items: [{ receipt_line_id: lineId, unbilled_qty: '10.000', unit_cost_minor: 3500, unbilled_minor: 35000 }] });
    await api.createSupplierBill(billFor(lineId, '4', 3500), key());
    expect((await api.unbilledReceipts(supplier)).items?.[0]).toMatchObject({ unbilled_qty: '6.000', unbilled_minor: 21000 });
  });

  it('matches billed against cleared, within the tolerance and above it, without recording anything', async () => {
    const { api, lineId } = await received();
    const same = await api.matchSupplierBill(billFor(lineId, '10', 3500));
    expect(same).toMatchObject({ total_minor: 35000, cleared_minor: 35000, variance_minor: 0, reason_required: false, total_mismatch: false, tolerance_minor: 350 });
    // 1 per cent of 35,000 is 350: 350 more is inside, 351 more is not.
    expect((await api.matchSupplierBill(billFor(lineId, '10', 3535))).reason_required).toBe(false);
    const dearer = await api.matchSupplierBill(billFor(lineId, '10', 3536));
    expect(dearer).toMatchObject({ variance_minor: 360, reason_required: true, lines: [{ cleared_minor: 35000, variance_minor: 360 }] });
    expect((await api.matchSupplierBill(billFor(lineId, '10', 3500, { total_minor: 34000 }))).total_mismatch).toBe(true);
    expect((await api.listSupplierBills({})).items).toHaveLength(0);
  });

  it('needs a reason when the invoice differs by more than the tolerance, and refuses a wrong total, too much and a repeated number', async () => {
    const { api, lineId } = await received();
    const dear = billFor(lineId, '5', 3600);
    await expect(api.createSupplierBill(dear, key())).rejects.toMatchObject({ code: 'variance_reason_required' });
    await expect(api.createSupplierBill({ ...dear, total_minor: 1, variance_reason: 'Price rose' }, key())).rejects.toMatchObject({ code: 'bill_total_mismatch' });
    await expect(api.createSupplierBill(billFor(lineId, '11', 3500), key())).rejects.toMatchObject({ code: 'bill_exceeds_received' });
    const made = await api.createSupplierBill({ ...dear, variance_reason: 'Price rose' }, key());
    expect(made).toMatchObject({ status: 'posted', total_minor: 18000, cleared_minor: 17500, variance_minor: 500, paid_minor: 0, open_minor: 18000, variance_reason: 'Price rose' });
    await expect(api.createSupplierBill({ ...billFor(lineId, '0.001', 3500), supplier_ref: dear.supplier_ref, total_minor: 4 }, key())).rejects.toMatchObject({ code: 'duplicate_supplier_bill' });
    expect((await api.listSupplierBills({ openOnly: true })).items).toHaveLength(1);
  });

  it('voids an invoice that is unpaid and frees the goods to bill again', async () => {
    const { api, lineId } = await received();
    const bill = await api.createSupplierBill(billFor(lineId, '10', 3500), key());
    const voided = await api.voidSupplierBill(bill.id ?? '', 'Wrong supplier', key());
    expect(voided).toMatchObject({ status: 'voided', open_minor: 0 });
    expect((await api.unbilledReceipts(supplier)).items?.[0]?.unbilled_qty).toBe('10.000');
    await expect(api.voidSupplierBill(bill.id ?? '', 'Wrong supplier', key())).rejects.toMatchObject({ code: 'bill_voided' });
  });
});

describe('supplier payments in the mock', () => {
  async function owing() {
    const { api, productId } = await shop();
    const order = await sentOrder(api, productId, '10');
    const receipt = await receiveAgainst(api, order.id ?? '', order.lines?.[0]?.id ?? '', '10');
    const bill = await api.createSupplierBill({ ...billFor(receipt.lines?.[0]?.id ?? '', '10', 3500), due_date: '2026-01-01' }, key());
    return { api, bill };
  }
  const pay = (api: Api, over: Record<string, unknown>) => api.createSupplierPayment({ supplier_id: supplier, branch_id: branch, method: 'cash', amount_minor: 1000, ...over } as never, key());

  it('lists the invoice and the credit restock as open items with how late each is', async () => {
    const { api, bill } = await owing();
    const open = await api.supplierOpenItems(supplier);
    expect(open.total_open_minor).toBe(47000);
    const item = open.items?.find((i) => i.kind === 'bill');
    expect(item).toMatchObject({ id: bill.id, number: bill.bill_no, supplier_ref: bill.supplier_ref, open_minor: 35000 });
    expect(item?.days_past_due).toBeGreaterThan(0);
    expect(open.items?.find((i) => i.kind === 'restock')).toMatchObject({ open_minor: 12000, days_past_due: 0 });
    expect(open.items?.[0]?.kind).toBe('bill');
  });

  it('pays oldest first, refuses more than is owed, and numbers the payment', async () => {
    const { api, bill } = await owing();
    await expect(pay(api, { amount_minor: 47001 })).rejects.toMatchObject({ code: 'payment_exceeds_open' });
    const made = await pay(api, { amount_minor: 40000, reference: 'REF-1' });
    expect(made).toMatchObject({ status: 'posted', payment_no: expect.stringMatching(/^SP-/), allocation: 'oldest_first', amount_minor: 40000, reference: 'REF-1' });
    expect(made.allocations).toEqual([expect.objectContaining({ bill_id: bill.id, amount_minor: 35000 }), expect.objectContaining({ purchase_id: expect.any(String), amount_minor: 5000 })]);
    expect((await api.getSupplierBill(bill.id ?? '')).open_minor).toBe(0);
    expect((await api.supplierOpenItems(supplier)).total_open_minor).toBe(7000);
    expect((await api.listSupplierPayments({ query: 'REF-1' })).items).toHaveLength(1);
  });

  it('pays as chosen, and the amounts must add up and fit what each owes', async () => {
    const { api, bill } = await owing();
    const restock = (await api.supplierOpenItems(supplier)).items?.find((i) => i.kind === 'restock');
    const manual = (allocations: unknown[], amount: number) => pay(api, { allocation: 'manual', amount_minor: amount, allocations });
    await expect(manual([{ bill_id: bill.id, amount_minor: 900 }], 1000)).rejects.toMatchObject({ code: 'allocation_total_mismatch' });
    await expect(manual([{ bill_id: bill.id, amount_minor: 35001 }], 35001)).rejects.toMatchObject({ code: 'allocation_exceeds_open' });
    const made = await manual([{ purchase_id: restock?.id, amount_minor: 12000 }, { bill_id: bill.id, amount_minor: 3000 }], 15000);
    expect(made.allocations).toHaveLength(2);
    expect((await api.getSupplierBill(bill.id ?? '')).open_minor).toBe(32000);
  });

  it('reverses a payment once, puts what it paid back, and then lets the invoice be voided only when unpaid', async () => {
    const { api, bill } = await owing();
    const made = await pay(api, { amount_minor: 35000 });
    await expect(api.voidSupplierBill(bill.id ?? '', 'Wrong supplier', key())).rejects.toMatchObject({ code: 'bill_paid' });
    await expect(api.reverseSupplierPayment(made.id ?? '', 'abc', key())).rejects.toMatchObject({ status: 422 });
    expect((await api.reverseSupplierPayment(made.id ?? '', 'Paid twice', key())).status).toBe('reversed');
    expect((await api.getSupplierBill(bill.id ?? '')).open_minor).toBe(35000);
    await expect(api.reverseSupplierPayment(made.id ?? '', 'Paid twice', key())).rejects.toMatchObject({ code: 'payment_reversed' });
    expect((await api.voidSupplierBill(bill.id ?? '', 'Wrong supplier', key())).status).toBe('voided');
  });

  it('reports what each supplier is owed by how late it is, what is received and not invoiced, and a totals row', async () => {
    const { api, productId } = await shop();
    const order = await sentOrder(api, productId, '10');
    const receipt = await receiveAgainst(api, order.id ?? '', order.lines?.[0]?.id ?? '', '10');
    const before = await api.supplierPayables();
    expect(before.suppliers).toEqual([expect.objectContaining({ supplier_id: supplier, owed_minor: 12000, not_due_minor: 12000, unbilled_receipts_minor: 35000 })]);
    await api.createSupplierBill({ ...billFor(receipt.lines?.[0]?.id ?? '', '10', 3500), due_date: '2026-01-01' }, key());
    const report = await api.supplierPayables();
    expect(report.suppliers?.[0]).toMatchObject({ owed_minor: 47000, not_due_minor: 12000, unbilled_receipts_minor: 0 });
    expect((report.suppliers?.[0]?.over90_minor ?? 0) + (report.suppliers?.[0]?.days61_to90_minor ?? 0) + (report.suppliers?.[0]?.days31_to60_minor ?? 0) + (report.suppliers?.[0]?.days1_to30_minor ?? 0)).toBe(35000);
    expect(report.totals).toMatchObject({ owed_minor: 47000 });
    expect(report.as_of).toBeTruthy();
  });

  it('keeps the settings under If-Match', async () => {
    const { api } = await shop();
    const read = await api.purchasingSettings();
    expect(read).toMatchObject({ bill_tolerance_bp: 100, over_receipt_bp: 0, version: 1 });
    const next = await api.changePurchasingSettings(1, { bill_tolerance_bp: 250 });
    expect(next).toMatchObject({ bill_tolerance_bp: 250, over_receipt_bp: 0, version: 2 });
    await expect(api.changePurchasingSettings(1, { bill_tolerance_bp: 0 })).rejects.toMatchObject({ status: 409, code: 'version_conflict' });
  });
});

// Landed costs and supplier credit notes (issue #199): charges spread in exact integers over received lines with the payee's
// invoice as a bill of kind landed_cost, a hold released by a landed cost, and credit notes that send goods back or adjust a price
// without ever taking more than the invoice has left to pay. Fabricated data.
describe('landed costs in the mock', () => {
  const forwarder = 's0000000-0000-4000-8000-000000000002';

  async function delivered(hold = false) {
    const { api, productId } = await shop();
    const order = await sentOrder(api, productId, '10');
    const receipt = await api.createGoodsReceipt({ order_id: order.id, branch_id: branch, hold_for_costing: hold, lines: [{ order_line_id: order.lines?.[0]?.id, qty: '10', cost_minor: 3500 }] }, key());
    return { api, productId, receipt, lineId: receipt.lines?.[0]?.id ?? '' };
  }
  const landedBody = (lineId: string, over: Record<string, unknown> = {}) => ({
    supplier_id: forwarder, supplier_ref: `FWD-${++n}`, due_date: '2026-11-11', total_minor: 10000,
    charges: [{ kind: 'freight' as const, amount_minor: 6000 }, { kind: 'duty' as const, amount_minor: 4000 }], lines: [{ receipt_line_id: lineId }], ...over,
  });

  it('answers 404 while the capability is off', async () => {
    const api = createMockRetail();
    await expect(api.listLandedCosts({})).rejects.toMatchObject({ status: 404, code: 'capability_not_enabled' });
    await expect(api.listSupplierCredits({})).rejects.toMatchObject({ status: 404 });
  });

  it('previews the shares without recording anything, and flags a total that is not the charges', async () => {
    const { api, lineId } = await delivered();
    const preview = await api.previewLandedCost(landedBody(lineId));
    expect(preview).toMatchObject({ total_minor: 10000, total_mismatch: false, method: 'value', lines: [{ allocated_minor: 10000, to_inventory_minor: 10000, to_cogs_minor: 0, cost_applied: true }] });
    expect((await api.previewLandedCost(landedBody(lineId, { total_minor: 9000 }))).total_mismatch).toBe(true);
    expect((await api.listLandedCosts({})).items).toHaveLength(0);
  });

  it('records the landed cost, makes the payee\'s invoice a bill of kind landed_cost, and shows the landed amount on the line', async () => {
    const { api, receipt, lineId } = await delivered(true);
    expect((await api.getGoodsReceipt(receipt.id ?? '')).held_for_costing).toBe(true);
    const made = await api.createLandedCost(landedBody(lineId), key());
    expect(made).toMatchObject({ status: 'posted', total_minor: 10000, method: 'value', paid_minor: 0, landed_no: expect.stringMatching(/^LC-/), charges: [{ kind: 'freight' }, { kind: 'duty' }] });
    const bill = await api.getSupplierBill(made.bill_id ?? '');
    expect(bill).toMatchObject({ kind: 'landed_cost', total_minor: 10000, open_minor: 10000, supplier_id: forwarder });
    const after = await api.getGoodsReceipt(receipt.id ?? '');
    expect(after.held_for_costing).toBe(false);
    expect(after.lines?.[0]?.landed_minor).toBe(10000);
    expect((await api.listLandedCosts({ query: made.landed_no })).items?.[0]).toMatchObject({ id: made.id, lines: undefined });
    await expect(api.voidSupplierBill(made.bill_id ?? '', 'Wrong supplier', key())).rejects.toMatchObject({ code: 'bill_is_landed_cost' });
  });

  it('refuses a wrong total, a typed split that does not add up and a repeated invoice number', async () => {
    const { api, lineId } = await delivered();
    await expect(api.createLandedCost(landedBody(lineId, { total_minor: 9000 }), key())).rejects.toMatchObject({ code: 'landed_total_mismatch' });
    await expect(api.createLandedCost(landedBody(lineId, { method: 'manual', lines: [{ receipt_line_id: lineId, amount_minor: 9000 }] }), key())).rejects.toMatchObject({ code: 'allocation_total_mismatch' });
    await expect(api.createLandedCost(landedBody(lineId, { method: 'weight', lines: [{ receipt_line_id: lineId }] }), key())).rejects.toMatchObject({ status: 422 });
    const ok = await api.createLandedCost(landedBody(lineId, { supplier_ref: 'FWD-DUP' }), key());
    expect(ok.status).toBe('posted');
    await expect(api.createLandedCost(landedBody(lineId, { supplier_ref: 'FWD-DUP' }), key())).rejects.toMatchObject({ code: 'duplicate_supplier_bill' });
  });

  it('is paid through the ordinary supplier payment, and cannot be voided once paid', async () => {
    const { api, lineId } = await delivered();
    const made = await api.createLandedCost(landedBody(lineId), key());
    await api.createSupplierPayment({ supplier_id: forwarder, branch_id: branch, method: 'cash', amount_minor: 4000 } as never, key());
    expect((await api.getLandedCost(made.id ?? '')).paid_minor).toBe(4000);
    await expect(api.voidLandedCost(made.id ?? '', 'Wrong goods', key())).rejects.toMatchObject({ code: 'bill_paid' });
  });

  it('voids with a reason, voids the payee\'s invoice with it, and refuses a second time', async () => {
    const { api, lineId } = await delivered();
    const made = await api.createLandedCost(landedBody(lineId), key());
    await expect(api.voidLandedCost(made.id ?? '', 'abc', key())).rejects.toMatchObject({ status: 422 });
    expect((await api.voidLandedCost(made.id ?? '', 'Wrong goods', key())).status).toBe('voided');
    expect((await api.getSupplierBill(made.bill_id ?? '')).status).toBe('voided');
    await expect(api.voidLandedCost(made.id ?? '', 'Wrong goods', key())).rejects.toMatchObject({ code: 'landed_cost_voided' });
  });

  it('releases a hold with a reason, once, and not for a delivery that is not held', async () => {
    const { api, receipt } = await delivered(true);
    await expect(api.releaseReceiptHold(receipt.id ?? '', 'abc', key())).rejects.toMatchObject({ status: 422 });
    expect((await api.releaseReceiptHold(receipt.id ?? '', 'No freight on this one', key())).held_for_costing).toBe(false);
    await expect(api.releaseReceiptHold(receipt.id ?? '', 'No freight on this one', key())).rejects.toMatchObject({ code: 'not_held' });
  });

  it('keeps the costing setting under If-Match, off by default', async () => {
    const { api } = await shop();
    const read = await api.purchasingSettings();
    expect(read.require_costing_before_sale).toBe(false);
    expect(await api.changePurchasingSettings(read.version ?? 1, { require_costing_before_sale: true })).toMatchObject({ require_costing_before_sale: true, bill_tolerance_bp: 100 });
  });
});

describe('supplier credit notes in the mock', () => {
  async function invoiced() {
    const { api, productId } = await shop();
    const order = await sentOrder(api, productId, '10');
    const receipt = await receiveAgainst(api, order.id ?? '', order.lines?.[0]?.id ?? '', '10', 3500);
    const bill = await api.createSupplierBill(billFor(receipt.lines?.[0]?.id ?? '', '10', 3500), key());
    return { api, productId, bill, lineId: bill.lines?.[0]?.id ?? '' };
  }
  const creditBody = (billId: string, lineId: string, over: Record<string, unknown> = {}) => ({
    bill_id: billId, kind: 'goods' as const, supplier_ref: `CN-${++n}`, total_minor: 7000, reason: 'Damaged on arrival', lines: [{ bill_line_id: lineId, qty: '2' }], ...over,
  });
  const onHand = async (api: Api) => Number((await api.listProducts({ query: 'P003', branchId: branch }))[0]?.qty);

  it('sends goods back out of stock and takes the credit off what the invoice leaves to pay', async () => {
    const { api, bill, lineId } = await invoiced();
    const before = await onHand(api);
    const made = await api.createSupplierCredit(creditBody(bill.id ?? '', lineId), key());
    expect(made).toMatchObject({ status: 'posted', kind: 'goods', total_minor: 7000, credit_no: expect.stringMatching(/^SC-/), lines: [{ unit_qty: '2.000', unit_credit_minor: 3500, line_total_minor: 7000 }] });
    expect(await onHand(api)).toBe(before - 2);
    expect(await api.getSupplierBill(bill.id ?? '')).toMatchObject({ credited_minor: 7000, open_minor: 28000 });
    expect((await api.supplierOpenItems(supplier)).items?.find((i) => i.kind === 'bill')).toMatchObject({ credited_minor: 7000, open_minor: 28000 });
    expect((await api.listSupplierCredits({ billId: bill.id })).items).toHaveLength(1);
  });

  it('refuses more than was billed less earlier credit notes, a wrong total and a credit for one above the billed price', async () => {
    const { api, bill, lineId } = await invoiced();
    await api.createSupplierCredit(creditBody(bill.id ?? '', lineId, { lines: [{ bill_line_id: lineId, qty: '8' }], total_minor: 28000 }), key());
    await expect(api.createSupplierCredit(creditBody(bill.id ?? '', lineId, { lines: [{ bill_line_id: lineId, qty: '3' }], total_minor: 10500 }), key())).rejects.toMatchObject({ code: 'credit_exceeds_billed' });
    await expect(api.createSupplierCredit(creditBody(bill.id ?? '', lineId, { total_minor: 6000 }), key())).rejects.toMatchObject({ code: 'credit_total_mismatch' });
    await expect(api.createSupplierCredit(creditBody(bill.id ?? '', lineId, { lines: [{ bill_line_id: lineId, qty: '1', unit_credit_minor: 3600 }], total_minor: 3600 }), key())).rejects.toMatchObject({ code: 'credit_exceeds_billed_value' });
  });

  it('refuses a credit above the open amount after a payment, and a repeated credit note number', async () => {
    const { api, bill, lineId } = await invoiced();
    await api.createSupplierPayment({ supplier_id: supplier, branch_id: branch, method: 'cash', amount_minor: 42000 } as never, key());
    await expect(api.createSupplierCredit(creditBody(bill.id ?? '', lineId), key())).rejects.toMatchObject({ code: 'credit_exceeds_open' });
    await api.createSupplierCredit(creditBody(bill.id ?? '', lineId, { supplier_ref: 'CN-DUP', lines: [{ bill_line_id: lineId, qty: '1' }], total_minor: 3500 }), key());
    await expect(api.createSupplierCredit(creditBody(bill.id ?? '', lineId, { supplier_ref: 'CN-DUP', lines: [{ bill_line_id: lineId, qty: '1' }], total_minor: 3500 }), key())).rejects.toMatchObject({ code: 'duplicate_supplier_credit' });
  });

  it('takes money off a line for a price credit and moves no stock', async () => {
    const { api, bill, lineId } = await invoiced();
    const before = await onHand(api);
    const made = await api.createSupplierCredit({ bill_id: bill.id ?? '', kind: 'price', supplier_ref: 'CN-PRICE', total_minor: 1500, reason: 'Wrong price', lines: [{ bill_line_id: lineId, amount_minor: 1500 }] }, key());
    expect(made).toMatchObject({ kind: 'price', total_minor: 1500 });
    expect(await onHand(api)).toBe(before);
    await expect(api.createSupplierCredit({ bill_id: bill.id ?? '', kind: 'price', supplier_ref: 'CN-PRICE2', total_minor: 34000, reason: 'Wrong price', lines: [{ bill_line_id: lineId, amount_minor: 34000 }] }, key())).rejects.toMatchObject({ code: 'credit_exceeds_billed_value' });
  });

  it('voids with a reason, puts the stock and the open amount back, and blocks voiding an invoice it stands against', async () => {
    const { api, bill, lineId } = await invoiced();
    const before = await onHand(api);
    const made = await api.createSupplierCredit(creditBody(bill.id ?? '', lineId), key());
    await expect(api.voidSupplierBill(bill.id ?? '', 'Wrong supplier', key())).rejects.toMatchObject({ code: 'bill_credited' });
    await expect(api.voidSupplierCredit(made.id ?? '', 'abc', key())).rejects.toMatchObject({ status: 422 });
    expect((await api.voidSupplierCredit(made.id ?? '', 'Sent back by mistake', key())).status).toBe('voided');
    expect(await onHand(api)).toBe(before);
    expect(await api.getSupplierBill(bill.id ?? '')).toMatchObject({ credited_minor: 0, open_minor: 35000 });
    await expect(api.voidSupplierCredit(made.id ?? '', 'Sent back by mistake', key())).rejects.toMatchObject({ code: 'credit_voided' });
  });
});
