import type {
  GoodsReceipt, GoodsReceiptLine, GoodsReceiptRequest, LandedCost, LandedCostLine, LandedCostPreview, LandedCostRequest, PurchaseOrder, PurchaseOrderLine, PurchaseOrderRequest,
  PurchasingSettings, RetailApi, SupplierBill, SupplierBillLine, SupplierBillMatch, SupplierBillRequest, SupplierCredit, SupplierCreditLine, SupplierCreditRequest,
  SupplierOpenItem, SupplierPayablesRow, SupplierPayment,
} from './retail';
import { creditedByLine } from '../areas/staff/retail/credits-state';
import { spreadMinor } from '../areas/staff/retail/landed-state';
import { lineTotalMinor, milliOf, parseQty, qtyString } from '../areas/staff/retail/maths';

// Fabricated optional purchasing for VITE_RETAIL_MOCK and for tests (ADR-033, issue #198): the REAL snake_case shapes of the
// purchasing routes held in memory, with the server's rules in small. An order is a draft until it is sent (a large one waits for
// a second person), goods received move stock and may not exceed the order beyond the over-receipt allowance, a supplier invoice
// bills received and unbilled quantities at the supplier's price and shows the difference against what the goods were received at
// (a reason is needed above the tolerance), a payment clears invoices and credit restocks oldest first or as chosen, and a void or
// a reversal puts everything back. Stock moves through the environment; the ledger is not simulated. Every route answers 404
// capability_not_enabled while the capability is off. Landed costs (the charges of one payee spread over received lines in exact
// integers, with the payee's invoice as a bill of kind landed_cost) and supplier credit notes (goods sent back or a price
// adjustment against an invoice that stands) follow the same rules in small (issue #199). Nothing here is real.

export interface PurchasingEnv {
  /** The purchasing capability of this mock instance. */
  on: () => boolean;
  today: () => string;
  nextId: (prefix: string) => string;
  delay: <T>(value: T) => Promise<T>;
  run: <T>(fn: () => T) => Promise<T>;
  once: <T>(key: string, request: unknown, make: () => T) => T;
  refuse: (status: number, code: string, detail: string) => never;
  suppliers: () => { id?: string; name?: string }[];
  branchCode: (branchId: string | undefined) => string;
  /** The item, or undefined: code, name, base unit and its sell price. */
  product: (id: string) => { code: string; description: string; unit: string; sellMinor: number } | undefined;
  /** A pack of the item: its unit name and factor in base units, or undefined for the base unit or an unknown pack. */
  pack: (productId: string, unitId: string) => { unit: string; factor: string } | undefined;
  /** Puts base units into (or, negative, takes them out of) a branch; `costMinor` per base unit and `sellMinor` set the item's prices. */
  move: (branchId: string, productId: string, baseMilli: number, costMinor?: number, sellMinor?: number) => void;
  balance: (branchId: string, productId: string) => number;
  /** The item's cost per base unit now, in minor units. */
  cost: (productId: string) => number;
}

export type PurchasingMock = Pick<
  RetailApi,
  | 'listPurchaseOrders' | 'getPurchaseOrder' | 'createPurchaseOrder' | 'updatePurchaseOrder' | 'sendPurchaseOrder' | 'cancelPurchaseOrder'
  | 'listGoodsReceipts' | 'getGoodsReceipt' | 'createGoodsReceipt' | 'voidGoodsReceipt'
  | 'listSupplierBills' | 'getSupplierBill' | 'createSupplierBill' | 'matchSupplierBill' | 'voidSupplierBill' | 'unbilledReceipts'
  | 'listSupplierPayments' | 'getSupplierPayment' | 'createSupplierPayment' | 'reverseSupplierPayment' | 'supplierOpenItems' | 'supplierPayables' | 'supplierStatement'
  | 'purchasingSettings' | 'changePurchasingSettings' | 'releaseReceiptHold'
  | 'listLandedCosts' | 'getLandedCost' | 'createLandedCost' | 'previewLandedCost' | 'voidLandedCost'
  | 'listSupplierCredits' | 'getSupplierCredit' | 'createSupplierCredit' | 'voidSupplierCredit'
> & {
  /** Buy stock on credit (issue #557): the main mock hands each credit purchase over, so it is owed and can be paid. */
  recordCreditRestock: (r: { id: string; purchase_no: string; supplier_id: string; date: string; total_minor: number }) => void;
};

/** An order worth at least this much waits for a second person to approve it (the mock's stand-in for the tenant's threshold). */
export const MOCK_ORDER_APPROVAL_MINOR = 5_000_000;

const MIN_REASON = 5;
const days = (from: string, to: string): number => Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
const text = (s: string | undefined): string => (s ?? '').toLowerCase();

interface Restock { id: string; purchase_no: string; supplier_id: string; date: string; total_minor: number; paid_minor: number }
interface Billed { billId: string; qty: number }

export function createPurchasingMock(env: PurchasingEnv): PurchasingMock {
  const orders: PurchaseOrder[] = [];
  const receipts: GoodsReceipt[] = [];
  const bills: SupplierBill[] = [];
  const payments: SupplierPayment[] = [];
  const billedBy = new Map<string, Billed[]>();
  const movedBy = new Map<string, { branchId: string; productId: string; baseMilli: number }[]>();
  const sequence = new Map<string, number>();
  let settings: PurchasingSettings = { bill_tolerance_bp: 100, over_receipt_bp: 0, require_costing_before_sale: false, version: 1 };
  const landed: LandedCost[] = [];
  const credits: SupplierCredit[] = [];
  const creditMoves = new Map<string, { branchId: string; productId: string; baseMilli: number }[]>();
  // One fabricated credit restock, so a payment can clear both kinds of open item.
  const restocks: Restock[] = [{ id: 'restock0-0000-4000-8000-000000000001', purchase_no: 'P-900001', supplier_id: env.suppliers()[0]?.id ?? '', date: env.today(), total_minor: 12_000, paid_minor: 0 }];

  const gate = <T>(fn: () => T): Promise<T> => env.run(() => (env.on() ? fn() : env.refuse(404, 'capability_not_enabled', 'This feature is not switched on for your business.')));
  // Paying suppliers and what is owed need no capability (issue #557, ADR-084): a shop that buys on credit in Buy stock uses them.
  const open = <T>(fn: () => T): Promise<T> => env.run(fn);
  const next = (prefix: string): string => {
    const n = (sequence.get(prefix) ?? 0) + 1;
    sequence.set(prefix, n);
    return `${prefix}-${String(n).padStart(6, '0')}`;
  };
  const notFound = (): never => env.refuse(404, 'not_found', 'That could not be found.');
  const supplierName = (id: string | undefined): string => env.suppliers().find((s) => s.id === id)?.name ?? '';
  const reasonOf = (reason: string): string => (reason.trim().length >= MIN_REASON ? reason.trim() : env.refuse(422, 'validation_failed', 'Give a reason of at least 5 characters.'));
  const orderOf = (id: string): PurchaseOrder => orders.find((o) => o.id === id) ?? notFound();
  const receiptOf = (id: string): GoodsReceipt => receipts.find((r) => r.id === id) ?? notFound();
  const billOf = (id: string): SupplierBill => bills.find((b) => b.id === id) ?? notFound();
  const paymentOf = (id: string): SupplierPayment => payments.find((p) => p.id === id) ?? notFound();

  /** The unit of a request line: its name and the base units in one (1 for the base unit); a quantity that is not exact is refused. */
  const unitOf = (productId: string, unitId: string | undefined, qty: string): { unit: string; factor: number; baseMilli: number; unitId?: string; factorText: string } => {
    const p = env.product(productId) ?? env.refuse(422, 'validation_failed', 'No such product.');
    const pack = unitId ? env.pack(productId, unitId) : undefined;
    const factor = pack ? milliOf(pack.factor) : 1000;
    const qtyMilli = parseQty(qty) ?? env.refuse(422, 'validation_failed', 'Enter a quantity above zero.');
    if ((qtyMilli * factor) % 1000 !== 0) env.refuse(422, 'quantity_not_representable', 'That quantity does not convert exactly.');
    return { unit: pack?.unit ?? p.unit, factor, baseMilli: (qtyMilli * factor) / 1000, ...(pack ? { unitId } : {}), factorText: qtyString(factor) };
  };

  const rank = (o: PurchaseOrder): PurchaseOrder => {
    const lines = o.lines ?? [];
    const received = lines.map((l) => milliOf(l.received_qty ?? '0'));
    const ordered = lines.map((l) => milliOf(l.qty ?? '0'));
    if (o.status === 'cancelled' || o.status === 'draft') return o;
    o.status = received.every((r, i) => r >= (ordered[i] ?? 0)) ? 'received' : received.some((r) => r > 0) ? 'partly_received' : 'sent';
    return o;
  };

  const withReceipts = (o: PurchaseOrder): PurchaseOrder => ({
    ...o,
    receipts: receipts.filter((r) => r.order_id === o.id).map((r) => ({ id: r.id, receipt_no: r.receipt_no, received_on: r.received_on, status: r.status, total_minor: r.total_minor })),
  });

  // ---- Orders -------------------------------------------------------------------------------------------------------

  const orderLines = (body: PurchaseOrderRequest, previous: PurchaseOrderLine[] = []): PurchaseOrderLine[] =>
    body.lines.map((l, i) => {
      const p = env.product(l.product_id) ?? env.refuse(422, 'validation_failed', 'No such product.');
      const u = unitOf(l.product_id, l.unit_id, l.qty);
      const unitQty = qtyString(parseQty(l.qty) ?? 0);
      const kept = previous[i];
      return {
        id: kept?.id ?? env.nextId('purchase-order-line'), line_no: i + 1, product_id: l.product_id, code: p.code, description: p.description, unit: u.unit,
        ...(u.unitId ? { unit_id: u.unitId, unit_factor: u.factorText } : {}), unit_qty: unitQty, qty: qtyString(u.baseMilli),
        unit_cost_minor: l.cost_minor, cost_minor: Math.round((l.cost_minor * 1000) / u.factor), line_total_minor: lineTotalMinor(l.cost_minor, parseQty(l.qty) ?? 0),
        received_qty: '0.000', received_minor: 0, billed_qty: '0.000', billed_minor: 0,
      };
    });

  const record = (body: PurchaseOrderRequest): PurchaseOrder => {
    if (!env.suppliers().some((s) => s.id === body.supplier_id)) env.refuse(422, 'validation_failed', 'No such supplier.');
    if (body.lines.length === 0) env.refuse(422, 'validation_failed', 'An order needs at least one line.');
    const lines = orderLines(body);
    const branchId = body.branch_id ?? '';
    const order: PurchaseOrder = {
      id: env.nextId('purchase-order'), order_no: `PO${env.branchCode(branchId)}-${String((sequence.get('po') ?? 0) + 1).padStart(6, '0')}`, status: 'draft', supplier_id: body.supplier_id,
      supplier_name: supplierName(body.supplier_id), branch_id: branchId, currency: 'UGX', ordered_on: body.ordered_on ?? env.today(),
      ...(body.expected_on ? { expected_on: body.expected_on } : {}), ...(body.note ? { note: body.note } : {}), line_count: lines.length, lines,
      total_minor: lines.reduce((sum, l) => sum + (l.line_total_minor ?? 0), 0), created_at: new Date().toISOString(), created_by: 'mock-user', version: 1,
    };
    sequence.set('po', (sequence.get('po') ?? 0) + 1);
    orders.push(order);
    return order;
  };

  const stale = (row: { version?: number }, version: number): void => {
    if ((row.version ?? 1) !== version) env.refuse(409, 'version_conflict', 'This was changed by someone else. Reload and try again.');
  };

  // ---- Goods received -----------------------------------------------------------------------------------------------

  const receive = (body: GoodsReceiptRequest): GoodsReceipt => {
    const order = body.order_id ? orderOf(body.order_id) : undefined;
    if (order && order.status === 'draft') env.refuse(409, 'order_not_sent', 'This order has not been sent yet.');
    if (order && (order.status === 'received' || order.status === 'cancelled')) env.refuse(409, 'order_closed', 'This order is closed.');
    if (!order && !body.supplier_id) env.refuse(422, 'validation_failed', 'A receipt with no order needs a supplier.');
    const branchId = body.branch_id ?? env.refuse(422, 'validation_failed', 'Choose where the goods go.');
    const moved: { branchId: string; productId: string; baseMilli: number }[] = [];
    const lines: GoodsReceiptLine[] = body.lines.map((l, i) => {
      const orderLine = order && (order.lines ?? []).find((x) => x.id === l.order_line_id);
      if (order && !orderLine) env.refuse(422, 'validation_failed', 'That line is not on this order.');
      const productId = orderLine?.product_id ?? l.product_id ?? env.refuse(422, 'validation_failed', 'Name the item.');
      const p = env.product(productId) ?? env.refuse(422, 'validation_failed', 'No such product.');
      const u = unitOf(productId, orderLine ? orderLine.unit_id : l.unit_id, l.qty);
      if (orderLine) {
        const allowed = Math.floor((milliOf(orderLine.qty ?? '0') * (10_000 + (settings.over_receipt_bp ?? 0))) / 10_000);
        if (milliOf(orderLine.received_qty ?? '0') + u.baseMilli > allowed) env.refuse(422, 'receipt_exceeds_order', 'More is being received than was ordered.');
      }
      const entered = l.cost_minor ?? orderLine?.unit_cost_minor ?? 0;
      moved.push({ branchId, productId, baseMilli: u.baseMilli });
      return {
        id: env.nextId('goods-receipt-line'), line_no: i + 1, ...(orderLine ? { order_line_id: orderLine.id } : {}), product_id: productId, code: p.code, description: p.description,
        unit: u.unit, ...(u.unitId ? { unit_id: u.unitId, unit_factor: u.factorText } : {}), unit_qty: qtyString(parseQty(l.qty) ?? 0), qty: qtyString(u.baseMilli),
        entered_cost_minor: entered, cost_minor: Math.round((entered * 1000) / u.factor), line_total_minor: lineTotalMinor(entered, parseQty(l.qty) ?? 0), ...(l.sell_minor !== undefined ? { sell_minor: l.sell_minor } : {}),
        billed_qty: '0.000',
      };
    });
    const id = env.nextId('goods-receipt');
    lines.forEach((l, i) => {
      const m = moved[i];
      if (m) env.move(m.branchId, m.productId, m.baseMilli, l.cost_minor, l.sell_minor);
      const orderLine = (order?.lines ?? []).find((x) => x.id === l.order_line_id);
      if (orderLine) {
        orderLine.received_qty = qtyString(milliOf(orderLine.received_qty ?? '0') + (m?.baseMilli ?? 0));
        orderLine.received_minor = (orderLine.received_minor ?? 0) + (l.line_total_minor ?? 0);
      }
    });
    movedBy.set(id, moved);
    const made: GoodsReceipt = {
      id, receipt_no: next(`GR${env.branchCode(branchId)}`), status: 'posted', branch_id: branchId, supplier_id: order?.supplier_id ?? body.supplier_id,
      supplier_name: supplierName(order?.supplier_id ?? body.supplier_id), ...(order ? { order_id: order.id, order_no: order.order_no } : {}), currency: 'UGX',
      received_on: body.received_on ?? env.today(), ...(body.delivery_ref ? { delivery_ref: body.delivery_ref } : {}), ...(body.note ? { note: body.note } : {}),
      held_for_costing: body.hold_for_costing === true,
      line_count: lines.length, lines, total_minor: lines.reduce((sum, l) => sum + (l.line_total_minor ?? 0), 0), created_at: new Date().toISOString(), created_by: 'mock-user', version: 1,
    };
    receipts.push(made);
    if (order) rank(order);
    return made;
  };

  const billedQty = (receiptLineId: string | undefined): number =>
    (billedBy.get(receiptLineId ?? '') ?? []).filter((b) => bills.find((x) => x.id === b.billId)?.status === 'posted').reduce((sum, b) => sum + b.qty, 0);

  const unbilledLines = (supplierId: string) =>
    receipts
      .filter((r) => r.supplier_id === supplierId && r.status === 'posted')
      .flatMap((r) =>
        (r.lines ?? []).map((l) => {
          const factor = milliOf(l.unit_factor ?? '1.000');
          const leftBase = milliOf(l.qty ?? '0') - billedQty(l.id);
          const leftUnit = (leftBase * 1000) / factor;
          return { r, l, leftBase, leftUnit, factor };
        }),
      )
      .filter((x) => x.leftBase > 0);

  // ---- Supplier invoices --------------------------------------------------------------------------------------------

  const tolerance = (cleared: number): number => Math.floor((cleared * (settings.bill_tolerance_bp ?? 0)) / 10_000);

  const work = (body: SupplierBillRequest): SupplierBillMatch & { lines: SupplierBillLine[] } => {
    if (!env.suppliers().some((s) => s.id === body.supplier_id)) env.refuse(422, 'validation_failed', 'No such supplier.');
    const lines: SupplierBillLine[] = body.lines.map((l, i) => {
      const found = unbilledLines(body.supplier_id).find((x) => x.l.id === l.receipt_line_id) ?? env.refuse(422, 'bill_exceeds_received', 'Nothing of that is left to bill.');
      const qty = parseQty(l.qty) ?? env.refuse(422, 'validation_failed', 'Enter a quantity above zero.');
      if (qty > found.leftUnit) env.refuse(422, 'bill_exceeds_received', 'More is billed than was received and not yet billed.');
      const billed = lineTotalMinor(l.unit_price_minor, qty);
      const cleared = lineTotalMinor(found.l.entered_cost_minor ?? 0, qty);
      return {
        id: env.nextId('supplier-bill-line'), line_no: i + 1, receipt_id: found.r.id, receipt_no: found.r.receipt_no, receipt_line_id: found.l.id, product_id: found.l.product_id, code: found.l.code,
        description: found.l.description, unit: found.l.unit, unit_qty: qtyString(qty), qty: qtyString(Math.round((qty * found.factor) / 1000)), unit_price_minor: l.unit_price_minor,
        line_total_minor: billed, cleared_minor: cleared, variance_minor: billed - cleared,
      };
    });
    const total = lines.reduce((sum, l) => sum + (l.line_total_minor ?? 0), 0);
    const cleared = lines.reduce((sum, l) => sum + (l.cleared_minor ?? 0), 0);
    const variance = total - cleared;
    return { lines, total_minor: total, cleared_minor: cleared, variance_minor: variance, tolerance_minor: tolerance(cleared), reason_required: Math.abs(variance) > tolerance(cleared), total_mismatch: body.total_minor !== total };
  };

  const openBills = (supplierId: string) => bills.filter((b) => b.supplier_id === supplierId && b.status === 'posted' && (b.open_minor ?? 0) > 0);

  const openItems = (supplierId: string): SupplierOpenItem[] => {
    const asOf = env.today();
    const fromBills = openBills(supplierId).map((b): SupplierOpenItem => ({
      id: b.id, kind: 'bill', number: b.bill_no, supplier_ref: b.supplier_ref, date: b.bill_date, due_date: b.due_date, days_past_due: days(b.due_date ?? asOf, asOf),
      total_minor: b.total_minor, paid_minor: b.paid_minor, credited_minor: b.credited_minor ?? 0, open_minor: b.open_minor,
    }));
    const fromRestocks = restocks.filter((r) => r.supplier_id === supplierId && r.total_minor > r.paid_minor).map((r): SupplierOpenItem => ({
      id: r.id, kind: 'restock', number: r.purchase_no, date: r.date, due_date: r.date, days_past_due: days(r.date, asOf), total_minor: r.total_minor, paid_minor: r.paid_minor, open_minor: r.total_minor - r.paid_minor,
    }));
    return [...fromBills, ...fromRestocks].sort((a, b) => (a.due_date ?? '').localeCompare(b.due_date ?? ''));
  };

  const settle = (item: { id?: string; kind?: string }, minor: number): void => {
    if (item.kind === 'restock') {
      const r = restocks.find((x) => x.id === item.id);
      if (r) r.paid_minor += minor;
      return;
    }
    const b = bills.find((x) => x.id === item.id);
    if (b) Object.assign(b, { paid_minor: (b.paid_minor ?? 0) + minor, open_minor: (b.open_minor ?? 0) - minor });
  };

  const bucket = (daysLate: number): 'not_due_minor' | 'days1_to30_minor' | 'days31_to60_minor' | 'days61_to90_minor' | 'over90_minor' =>
    daysLate <= 0 ? 'not_due_minor' : daysLate <= 30 ? 'days1_to30_minor' : daysLate <= 60 ? 'days31_to60_minor' : daysLate <= 90 ? 'days61_to90_minor' : 'over90_minor';

  const emptyRow = (): SupplierPayablesRow => ({ not_due_minor: 0, days1_to30_minor: 0, days31_to60_minor: 0, days61_to90_minor: 0, over90_minor: 0, owed_minor: 0, unbilled_receipts_minor: 0 });

  const listed = <T extends { supplier_name?: string; status?: string }>(rows: T[], q: { query?: string; status?: string; supplierId?: string }, label: (r: T) => string): T[] => {
    const term = text(q.query).trim();
    return [...rows].reverse().filter((r) => (!q.status || r.status === q.status) && (!q.supplierId || (r as { supplier_id?: string }).supplier_id === q.supplierId) && (term === '' || text(`${label(r)} ${r.supplier_name ?? ''}`).includes(term)));
  };
  const withoutLines = <T extends { lines?: unknown }>(r: T): T => ({ ...r, lines: undefined });

  // ---- Landed costs (issue #199) -------------------------------------------------------------------------------------

  const landedOf = (id: string): LandedCost => landed.find((x) => x.id === id) ?? notFound();
  /** What standing landed costs have allocated to a received line. */
  const landedOn = (receiptLineId: string | undefined): number =>
    landed.filter((x) => x.status === 'posted').flatMap((x) => x.lines ?? []).filter((l) => l.receipt_line_id === receiptLineId).reduce((sum, l) => sum + (l.allocated_minor ?? 0), 0);
  const receiptLineOf = (id: string) => {
    for (const r of receipts) {
      const l = (r.lines ?? []).find((x) => x.id === id);
      if (l) return { r, l };
    }
    return undefined;
  };

  const landedWork = (body: LandedCostRequest): LandedCostPreview & { lines: LandedCostLine[]; method: string } => {
    if (!env.suppliers().some((s) => s.id === body.supplier_id)) env.refuse(422, 'validation_failed', 'No such supplier.');
    if (body.charges.length === 0 || body.lines.length === 0) env.refuse(422, 'validation_failed', 'A landed cost needs charges and goods.');
    const method = body.method ?? 'value';
    const total = body.charges.reduce((sum, c) => sum + c.amount_minor, 0);
    const found = body.lines.map((l) => {
      const hit = receiptLineOf(l.receipt_line_id) ?? env.refuse(422, 'validation_failed', 'One of the lines is not on a delivery.');
      if (hit.r.status !== 'posted') env.refuse(422, 'validation_failed', 'One of the deliveries has been voided.');
      if (body.cost_date && body.cost_date < (hit.r.received_on ?? '')) env.refuse(422, 'validation_failed', 'The charges cannot be dated before the goods were received.');
      return { req: l, ...hit };
    });
    const bases = found.map((f) => {
      if (method === 'value') return f.l.line_total_minor ?? 0;
      if (method === 'quantity') return milliOf(f.l.qty ?? '0');
      if (method === 'weight') return parseQty(f.req.weight ?? '') ?? env.refuse(422, 'validation_failed', 'Enter the weight of every line.');
      return f.req.amount_minor ?? env.refuse(422, 'validation_failed', 'Enter the share of every line.');
    });
    let shares: number[];
    if (method === 'manual') {
      if (bases.reduce((a, b) => a + b, 0) !== total) env.refuse(422, 'allocation_total_mismatch', 'The shares must add up to the total.');
      shares = bases;
    } else {
      shares = spreadMinor(total, bases) ?? env.refuse(422, 'allocation_basis_zero', 'These goods add up to nothing by this method.');
    }
    const lines = found.map((f, i): LandedCostLine => {
      const share = shares[i] ?? 0;
      const qtyMilli = milliOf(f.l.qty ?? '0');
      const productId = f.l.product_id ?? '';
      const onHand = Math.max(0, Math.min(env.balance(f.r.branch_id ?? '', productId), qtyMilli));
      const toInventory = qtyMilli > 0 ? Number((BigInt(share) * BigInt(onHand)) / BigInt(qtyMilli)) : 0;
      const before = env.cost(productId);
      const applied = toInventory > 0 && onHand > 0;
      return {
        line_no: i + 1, receipt_id: f.r.id, receipt_no: f.r.receipt_no, receipt_line_id: f.l.id, product_id: productId, branch_id: f.r.branch_id, code: f.l.code, description: f.l.description,
        qty: f.l.qty, basis: String(bases[i] ?? 0), ...(method === 'weight' ? { weight: qtyString(bases[i] ?? 0) } : {}), allocated_minor: share, on_hand_qty: qtyString(onHand),
        to_inventory_minor: toInventory, to_cogs_minor: share - toInventory, cost_before_minor: before,
        cost_after_minor: applied ? before + Math.round((toInventory * 1000) / onHand) : before, cost_applied: applied,
        ...(applied ? {} : { cost_note: 'The goods were all sold, used or moved, so the whole share is counted as a cost of sales and the cost is not changed.' }),
      };
    });
    return { total_minor: total, total_mismatch: body.total_minor !== total, method, lines };
  };

  const postLanded = (body: LandedCostRequest): LandedCost => {
    const w = landedWork(body);
    if (w.total_mismatch) env.refuse(422, 'landed_total_mismatch', 'The total does not match the charges.');
    if (bills.some((b) => b.supplier_id === body.supplier_id && b.supplier_ref === body.supplier_ref && b.status === 'posted')) env.refuse(409, 'duplicate_supplier_bill', 'Already recorded.');
    const billId = env.nextId('supplier-bill');
    const bill: SupplierBill = {
      id: billId, bill_no: next('SB'), supplier_id: body.supplier_id, supplier_name: supplierName(body.supplier_id), supplier_ref: body.supplier_ref, kind: 'landed_cost', status: 'posted', currency: 'UGX',
      bill_date: body.cost_date ?? env.today(), due_date: body.due_date, line_count: 0, lines: [], total_minor: w.total_minor, cleared_minor: 0, variance_minor: 0, paid_minor: 0, credited_minor: 0,
      open_minor: w.total_minor, branch_id: w.lines[0]?.branch_id, created_at: new Date().toISOString(), created_by: 'mock-user', version: 1,
    };
    bills.push(bill);
    w.lines.forEach((l) => {
      const r = receipts.find((x) => x.id === l.receipt_id);
      if (r) r.held_for_costing = false;
      if (l.cost_applied) env.move(l.branch_id ?? '', l.product_id ?? '', 0, l.cost_after_minor);
    });
    const made: LandedCost = {
      id: env.nextId('landed-cost'), landed_no: next('LC'), bill_id: billId, bill_no: bill.bill_no, branch_id: bill.branch_id, status: 'posted', supplier_id: body.supplier_id,
      supplier_name: supplierName(body.supplier_id), supplier_ref: body.supplier_ref, currency: 'UGX', method: w.method as LandedCost['method'], cost_date: bill.bill_date, due_date: body.due_date,
      ...(body.note ? { note: body.note } : {}), total_minor: w.total_minor, paid_minor: 0, line_count: w.lines.length, lines: w.lines,
      charges: body.charges.map((c, i) => ({ line_no: i + 1, kind: c.kind, ...(c.description ? { description: c.description } : {}), amount_minor: c.amount_minor })),
      created_at: new Date().toISOString(), created_by: 'mock-user', version: 1,
    };
    landed.push(made);
    return made;
  };

  /** A landed cost as read: what has been paid on the payee's invoice is the invoice's own figure. */
  const landedView = (x: LandedCost): LandedCost => ({ ...x, paid_minor: bills.find((b) => b.id === x.bill_id)?.paid_minor ?? 0 });

  // ---- Supplier credit notes (issue #199) ----------------------------------------------------------------------------

  const creditOf = (id: string): SupplierCredit => credits.find((x) => x.id === id) ?? notFound();

  const postCredit = (body: SupplierCreditRequest): SupplierCredit => {
    const bill = billOf(body.bill_id);
    if (bill.status !== 'posted') env.refuse(409, 'bill_voided', 'That invoice has been voided.');
    if (bill.kind === 'landed_cost') env.refuse(409, 'bill_is_landed_cost', 'This is the invoice of a landed cost.');
    if (body.credit_date && body.credit_date < (bill.bill_date ?? '')) env.refuse(422, 'validation_failed', 'The credit note cannot be dated before the invoice.');
    if (body.reason.trim().length < MIN_REASON) env.refuse(422, 'validation_failed', 'Give a reason of at least 5 characters.');
    if (credits.some((c) => c.supplier_id === bill.supplier_id && c.supplier_ref === body.supplier_ref && c.status === 'posted')) env.refuse(409, 'duplicate_supplier_credit', 'Already recorded.');
    const taken = creditedByLine(credits.filter((c) => c.bill_id === bill.id));
    const moves: { branchId: string; productId: string; baseMilli: number }[] = [];
    const lines = body.lines.map((l, i): SupplierCreditLine => {
      const bl = (bill.lines ?? []).find((x) => x.id === l.bill_line_id) ?? env.refuse(422, 'validation_failed', 'That line is not on the invoice.');
      const held = taken[bl.id ?? ''] ?? { qtyMilli: 0, minor: 0 };
      const common = { line_no: i + 1, bill_line_id: bl.id, receipt_no: bl.receipt_no, receipt_line_id: bl.receipt_line_id, product_id: bl.product_id, code: bl.code, description: bl.description, unit: bl.unit };
      if (body.kind === 'price') {
        const amount = l.amount_minor ?? env.refuse(422, 'validation_failed', 'Enter the amount to take off.');
        if (amount <= 0 || amount > (bl.line_total_minor ?? 0) - held.minor) env.refuse(422, 'credit_exceeds_billed_value', 'That is more than the line is still worth.');
        return { ...common, line_total_minor: amount };
      }
      const qty = parseQty(l.qty ?? '') ?? env.refuse(422, 'validation_failed', 'Enter a quantity above zero.');
      if (qty > milliOf(bl.unit_qty ?? bl.qty ?? '0') - held.qtyMilli) env.refuse(422, 'credit_exceeds_billed', 'More is sent back than was billed and not yet credited.');
      const each = l.unit_credit_minor ?? bl.unit_price_minor ?? 0;
      if (each > (bl.unit_price_minor ?? 0)) env.refuse(422, 'credit_exceeds_billed_value', 'The credit for one is more than the invoice charged.');
      const baseMilli = Math.round((qty * milliOf(bl.qty ?? '0')) / Math.max(1, milliOf(bl.unit_qty ?? bl.qty ?? '0')));
      const receipt = receipts.find((r) => r.id === bl.receipt_id);
      moves.push({ branchId: receipt?.branch_id ?? '', productId: bl.product_id ?? '', baseMilli });
      return { ...common, unit_qty: qtyString(qty), qty: qtyString(baseMilli), unit_credit_minor: each, line_total_minor: lineTotalMinor(each, qty) };
    });
    const total = lines.reduce((sum, l) => sum + (l.line_total_minor ?? 0), 0);
    if (body.total_minor !== total) env.refuse(422, 'credit_total_mismatch', 'The total does not match the lines.');
    if (total > (bill.open_minor ?? 0)) env.refuse(422, 'credit_exceeds_open', 'That is more than the invoice still has to pay.');
    if (moves.some((m) => env.balance(m.branchId, m.productId) < m.baseMilli)) env.refuse(422, 'insufficient_stock', 'There is not enough stock to send back.');
    moves.forEach((m) => env.move(m.branchId, m.productId, -m.baseMilli));
    Object.assign(bill, { credited_minor: (bill.credited_minor ?? 0) + total, open_minor: (bill.open_minor ?? 0) - total });
    const id = env.nextId('supplier-credit');
    creditMoves.set(id, moves);
    const made: SupplierCredit = {
      id, credit_no: next('SC'), bill_id: bill.id, bill_no: bill.bill_no, bill_supplier_ref: bill.supplier_ref, branch_id: bill.branch_id, supplier_id: bill.supplier_id, supplier_name: bill.supplier_name,
      supplier_ref: body.supplier_ref, kind: body.kind, status: 'posted', currency: 'UGX', credit_date: body.credit_date ?? env.today(), total_minor: total, reason: body.reason.trim(),
      ...(body.note ? { note: body.note } : {}), line_count: lines.length, lines, created_at: new Date().toISOString(), created_by: 'mock-user', version: 1,
    };
    credits.push(made);
    return made;
  };

  return {
    purchasingSettings: () => gate(() => ({ ...settings })),
    changePurchasingSettings: (version, body) =>
      gate(() => {
        stale(settings, version);
        settings = { ...settings, ...(body.bill_tolerance_bp !== undefined ? { bill_tolerance_bp: body.bill_tolerance_bp } : {}), ...(body.over_receipt_bp !== undefined ? { over_receipt_bp: body.over_receipt_bp } : {}), ...(body.require_costing_before_sale !== undefined ? { require_costing_before_sale: body.require_costing_before_sale } : {}), version: (settings.version ?? 1) + 1 };
        return { ...settings };
      }),

    listPurchaseOrders: (q) => gate(() => ({ items: listed(orders, q, (o) => o.order_no ?? '').map(withoutLines) })),
    getPurchaseOrder: (id) => gate(() => withReceipts(orderOf(id))),
    createPurchaseOrder: (body, key) => gate(() => env.once(`po|${key}`, body, () => record(body))),
    updatePurchaseOrder: (id, version, body) =>
      gate(() => {
        const o = orderOf(id);
        stale(o, version);
        if (o.status !== 'draft') env.refuse(409, 'order_not_draft', 'Only a draft can be changed.');
        const lines = orderLines(body, o.lines);
        Object.assign(o, { supplier_id: body.supplier_id, supplier_name: supplierName(body.supplier_id), expected_on: body.expected_on, note: body.note, lines, line_count: lines.length, total_minor: lines.reduce((sum, l) => sum + (l.line_total_minor ?? 0), 0), version: (o.version ?? 1) + 1 });
        return o;
      }),
    sendPurchaseOrder: (id, key) =>
      gate(() =>
        env.once(`po-send|${key}`, { id }, () => {
          const o = orderOf(id);
          if (o.status !== 'draft') env.refuse(409, 'order_not_draft', 'This order has been sent already.');
          if ((o.total_minor ?? 0) >= MOCK_ORDER_APPROVAL_MINOR) return { executed: false, approval_id: env.nextId('approval') };
          Object.assign(o, { status: 'sent', sent_at: new Date().toISOString(), sent_by: 'mock-user', version: (o.version ?? 1) + 1 });
          return { executed: true, order: o };
        }),
      ),
    cancelPurchaseOrder: (id, reason, key) =>
      gate(() =>
        env.once(`po-cancel|${key}`, { id, reason }, () => {
          const o = orderOf(id);
          const why = reasonOf(reason);
          if (o.status === 'received' || o.status === 'cancelled') env.refuse(409, 'order_closed', 'This order is closed.');
          Object.assign(o, { status: 'cancelled', cancel_reason: why, cancelled_at: new Date().toISOString(), cancelled_by: 'mock-user', version: (o.version ?? 1) + 1 });
          return o;
        }),
      ),

    listGoodsReceipts: (q) => gate(() => ({ items: listed(receipts, q, (r) => `${r.receipt_no} ${r.order_no ?? ''} ${r.delivery_ref ?? ''}`).filter((r) => !(q.orderId) || r.order_id === q.orderId).map(withoutLines) })),
    getGoodsReceipt: (id) =>
      gate(() => {
        const r = receiptOf(id);
        return { ...r, lines: (r.lines ?? []).map((l) => ({ ...l, billed_qty: qtyString(billedQty(l.id)), ...(landedOn(l.id) > 0 ? { landed_minor: landedOn(l.id) } : {}) })) };
      }),
    createGoodsReceipt: (body, key) => gate(() => env.once(`receipt|${key}`, body, () => receive(body))),
    voidGoodsReceipt: (id, reason, key) =>
      gate(() =>
        env.once(`receipt-void|${key}`, { id, reason }, () => {
          const r = receiptOf(id);
          const why = reasonOf(reason);
          if (r.status === 'voided') env.refuse(409, 'receipt_voided', 'This receipt has been voided already.');
          if ((r.lines ?? []).some((l) => billedQty(l.id) > 0)) env.refuse(409, 'receipt_billed', 'This receipt is on a supplier invoice.');
          const moved = movedBy.get(id) ?? [];
          if (moved.some((m) => env.balance(m.branchId, m.productId) < m.baseMilli)) env.refuse(409, 'receipt_stock_moved', 'Some of these goods have moved on.');
          moved.forEach((m) => env.move(m.branchId, m.productId, -m.baseMilli));
          const order = orders.find((o) => o.id === r.order_id);
          (r.lines ?? []).forEach((l) => {
            const ol = (order?.lines ?? []).find((x) => x.id === l.order_line_id);
            if (ol) {
              ol.received_qty = qtyString(milliOf(ol.received_qty ?? '0') - milliOf(l.qty ?? '0'));
              ol.received_minor = (ol.received_minor ?? 0) - (l.line_total_minor ?? 0);
            }
          });
          if (order) rank(order);
          Object.assign(r, { status: 'voided', void_reason: why, voided_on: env.today(), voided_at: new Date().toISOString(), voided_by: 'mock-user', version: (r.version ?? 1) + 1 });
          return r;
        }),
      ),

    unbilledReceipts: (supplierId) =>
      gate(() => {
        const lines = unbilledLines(supplierId).map(({ r, l, leftUnit }) => ({
          branch_id: r.branch_id, code: l.code, description: l.description, ...(r.order_no ? { order_no: r.order_no } : {}), product_id: l.product_id, receipt_id: r.id, receipt_line_id: l.id,
          receipt_no: r.receipt_no, received_on: r.received_on, unbilled_qty: qtyString(leftUnit), unbilled_minor: lineTotalMinor(l.entered_cost_minor ?? 0, leftUnit), unit: l.unit, unit_id: l.unit_id,
          unit_cost_minor: l.entered_cost_minor,
        }));
        return { supplier_id: supplierId, supplier_name: supplierName(supplierId), items: lines, total_minor: lines.reduce((sum, l) => sum + (l.unbilled_minor ?? 0), 0) };
      }),
    matchSupplierBill: (body) =>
      gate(() => {
        const { lines, ...match } = work(body);
        return { ...match, lines };
      }),
    createSupplierBill: (body, key) =>
      gate(() =>
        env.once(`bill|${key}`, body, (): SupplierBill => {
          const w = work(body);
          if (bills.some((b) => b.supplier_id === body.supplier_id && b.supplier_ref === body.supplier_ref && b.status === 'posted')) env.refuse(409, 'duplicate_supplier_bill', 'Already recorded.');
          if (w.total_mismatch) env.refuse(422, 'bill_total_mismatch', 'The total does not match its lines.');
          if (w.reason_required && (body.variance_reason ?? '').trim().length < MIN_REASON) env.refuse(422, 'variance_reason_required', 'Say why the invoice differs.');
          const id = env.nextId('supplier-bill');
          w.lines.forEach((l) => {
            const held = billedBy.get(l.receipt_line_id ?? '') ?? [];
            billedBy.set(l.receipt_line_id ?? '', [...held, { billId: id, qty: milliOf(l.qty ?? '0') }]);
          });
          const made: SupplierBill = {
            id, bill_no: next('SB'), supplier_id: body.supplier_id, supplier_name: supplierName(body.supplier_id), supplier_ref: body.supplier_ref, kind: 'goods', status: 'posted', currency: 'UGX',
            bill_date: body.bill_date, due_date: body.due_date, ...(body.note ? { note: body.note } : {}), line_count: w.lines.length, lines: w.lines, total_minor: w.total_minor, cleared_minor: w.cleared_minor,
            variance_minor: w.variance_minor, ...(body.variance_reason ? { variance_reason: body.variance_reason } : {}), paid_minor: 0, credited_minor: 0, open_minor: w.total_minor, created_at: new Date().toISOString(), created_by: 'mock-user', version: 1,
          };
          bills.push(made);
          return made;
        }),
      ),
    listSupplierBills: (q) => gate(() => ({ items: listed(bills, q, (b) => `${b.bill_no} ${b.supplier_ref}`).filter((b) => !q.openOnly || (b.status === 'posted' && (b.open_minor ?? 0) > 0)).map(withoutLines) })),
    getSupplierBill: (id) => gate(() => billOf(id)),
    voidSupplierBill: (id, reason, key) =>
      gate(() =>
        env.once(`bill-void|${key}`, { id, reason }, () => {
          const b = billOf(id);
          const why = reasonOf(reason);
          if (b.status === 'voided') env.refuse(409, 'bill_voided', 'This invoice has been voided already.');
          if (b.kind === 'landed_cost') env.refuse(409, 'bill_is_landed_cost', 'This is the invoice of a landed cost.');
          if ((b.credited_minor ?? 0) > 0) env.refuse(409, 'bill_credited', 'A credit note stands against this invoice.');
          if ((b.paid_minor ?? 0) > 0) env.refuse(409, 'bill_paid', 'Reverse the payment first.');
          Object.assign(b, { status: 'voided', open_minor: 0, void_reason: why, voided_on: env.today(), voided_at: new Date().toISOString(), voided_by: 'mock-user', version: (b.version ?? 1) + 1 });
          return b;
        }),
      ),

    supplierOpenItems: (supplierId) =>
      open(() => {
        const items = openItems(supplierId);
        return { as_of: env.today(), supplier_id: supplierId, supplier_name: supplierName(supplierId), items, total_open_minor: items.reduce((sum, i) => sum + (i.open_minor ?? 0), 0) };
      }),
    createSupplierPayment: (body, key) =>
      open(() =>
        env.once(`payment|${key}`, body, (): SupplierPayment => {
          const paidOn = body.paid_on ?? env.today();
          if (paidOn > env.today()) env.refuse(422, 'future_date', 'A payment cannot be dated in the future.');
          const items = openItems(body.supplier_id).filter((i) => (i.date ?? '') <= paidOn);
          const owed = items.reduce((sum, i) => sum + (i.open_minor ?? 0), 0);
          if (body.amount_minor <= 0 || body.amount_minor > 1_000_000_000_000) env.refuse(422, 'amount_out_of_range', 'That amount is out of range.');
          if (body.amount_minor > owed) env.refuse(422, 'payment_exceeds_open', 'That is more than the supplier is owed.');
          let picks: { item: SupplierOpenItem; minor: number }[] = [];
          if (body.allocation === 'manual') {
            picks = (body.allocations ?? []).map((a) => {
              const item = items.find((i) => i.id === (a.bill_id ?? a.purchase_id)) ?? env.refuse(422, 'allocation_exceeds_open', 'Nothing is owed on that one.');
              if (a.amount_minor > (item.open_minor ?? 0)) env.refuse(422, 'allocation_exceeds_open', 'That is more than is owed on it.');
              return { item, minor: a.amount_minor };
            });
            if (picks.reduce((sum, p) => sum + p.minor, 0) !== body.amount_minor) env.refuse(422, 'allocation_total_mismatch', 'The amounts must add up to the payment.');
          } else {
            let left = body.amount_minor;
            for (const item of items) {
              const part = Math.min(left, item.open_minor ?? 0);
              if (part > 0) picks.push({ item, minor: part });
              left -= part;
            }
          }
          picks.forEach((p) => settle(p.item, p.minor));
          const made: SupplierPayment = {
            id: env.nextId('supplier-payment'), payment_no: next('SP'), status: 'posted', supplier_id: body.supplier_id, supplier_name: supplierName(body.supplier_id), branch_id: body.branch_id, currency: 'UGX',
            method: body.method, amount_minor: body.amount_minor, allocation: body.allocation ?? 'oldest_first', ...(body.reference ? { reference: body.reference } : {}), ...(body.note ? { note: body.note } : {}),
            paid_on: paidOn, allocations: picks.map((p) => ({ amount_minor: p.minor, ...(p.item.kind === 'restock' ? { purchase_id: p.item.id, purchase_no: p.item.number } : { bill_id: p.item.id, bill_no: p.item.number, supplier_ref: p.item.supplier_ref }) })),
            created_at: new Date().toISOString(), created_by: 'mock-user', version: 1,
          };
          payments.push(made);
          return made;
        }),
      ),
    listSupplierPayments: (q) => open(() => ({ items: listed(payments, q, (p) => `${p.payment_no} ${p.reference ?? ''}`).map((p) => ({ ...p, allocations: undefined })) })),
    getSupplierPayment: (id) => open(() => paymentOf(id)),
    reverseSupplierPayment: (id, reason, key) =>
      open(() =>
        env.once(`payment-reverse|${key}`, { id, reason }, () => {
          const p = paymentOf(id);
          const why = reasonOf(reason);
          if (p.status === 'reversed') env.refuse(409, 'payment_reversed', 'This payment has been reversed already.');
          (p.allocations ?? []).forEach((a) => settle(a.purchase_id ? { id: a.purchase_id, kind: 'restock' } : { id: a.bill_id, kind: 'bill' }, -(a.amount_minor ?? 0)));
          Object.assign(p, { status: 'reversed', reverse_reason: why, reversed_on: env.today(), reversed_at: new Date().toISOString(), reversed_by: 'mock-user', version: (p.version ?? 1) + 1 });
          return p;
        }),
      ),

    supplierPayables: () =>
      open(() => {
        const totals = emptyRow();
        const rows = env.suppliers().flatMap((s): SupplierPayablesRow[] => {
          const row = emptyRow();
          for (const i of openItems(s.id ?? '')) {
            row[bucket(i.days_past_due ?? 0)] = (row[bucket(i.days_past_due ?? 0)] ?? 0) + (i.open_minor ?? 0);
            row.owed_minor = (row.owed_minor ?? 0) + (i.open_minor ?? 0);
          }
          row.unbilled_receipts_minor = unbilledLines(s.id ?? '').reduce((sum, x) => sum + lineTotalMinor(x.l.entered_cost_minor ?? 0, x.leftUnit), 0);
          if ((row.owed_minor ?? 0) === 0 && (row.unbilled_receipts_minor ?? 0) === 0) return [];
          for (const k of Object.keys(row) as (keyof SupplierPayablesRow)[]) if (typeof row[k] === 'number') (totals[k] as number) += row[k] as number;
          return [{ ...row, supplier_id: s.id, supplier_name: s.name }];
        });
        return { as_of: env.today(), currency: 'UGX', suppliers: rows, totals };
      }),

    supplierStatement: (supplierId, { from, to }) =>
      open(() => {
        const end = to || env.today();
        // As the server: the last 90 days by default.
        const start = from || new Date(Date.parse(`${end}T00:00:00Z`) - 89 * 86_400_000).toISOString().slice(0, 10);
        type Event = { date: string; kind: string; number: string; reference?: string; source_id: string; method?: string; amount: number };
        const events: Event[] = [
          ...restocks.filter((r) => r.supplier_id === supplierId).map((r) => ({ date: r.date, kind: 'purchase', number: r.purchase_no, source_id: r.id, amount: r.total_minor })),
          ...bills.filter((b) => b.supplier_id === supplierId).flatMap((b): Event[] => [
            { date: b.bill_date ?? '', kind: 'bill', number: b.bill_no ?? '', reference: b.supplier_ref, source_id: b.id ?? '', amount: b.total_minor ?? 0 },
            ...(b.status === 'voided' ? [{ date: b.voided_on ?? '', kind: 'bill_void', number: b.bill_no ?? '', reference: b.supplier_ref, source_id: b.id ?? '', amount: -(b.total_minor ?? 0) }] : []),
          ]),
          ...payments.filter((p) => p.supplier_id === supplierId).flatMap((p): Event[] => [
            { date: p.paid_on ?? '', kind: 'payment', number: p.payment_no ?? '', reference: p.reference, source_id: p.id ?? '', method: p.method, amount: -(p.amount_minor ?? 0) },
            ...(p.status === 'reversed' ? [{ date: p.reversed_on ?? '', kind: 'payment_reversed', number: p.payment_no ?? '', reference: p.reference, source_id: p.id ?? '', method: p.method, amount: p.amount_minor ?? 0 }] : []),
          ]),
        ].filter((e) => e.date <= end).sort((a, b) => a.date.localeCompare(b.date));
        let running = 0;
        let opening = 0;
        const lines = events.flatMap((e) => {
          running += e.amount;
          if (e.date < start) { opening = running; return []; }
          return [{ date: e.date, kind: e.kind, number: e.number, ...(e.reference ? { reference: e.reference } : {}), source_id: e.source_id, ...(e.method ? { method: e.method } : {}), increase_minor: Math.max(e.amount, 0), decrease_minor: Math.max(-e.amount, 0), balance_minor: running }];
        });
        return { supplier_id: supplierId, supplier_name: supplierName(supplierId), currency: 'UGX', from: start, to: end, opening_minor: opening, lines, closing_minor: running };
      }),
    recordCreditRestock: (r) => {
      restocks.push({ ...r, paid_minor: 0 });
    },

    releaseReceiptHold: (id, reason, key) =>
      gate(() =>
        env.once(`receipt-release|${key}`, { id, reason }, () => {
          const r = receiptOf(id);
          reasonOf(reason);
          if (r.held_for_costing !== true) env.refuse(409, 'not_held', 'This delivery is not held.');
          if ((r.lines ?? []).some((l) => landedOn(l.id) > 0)) env.refuse(409, 'receipt_costed', 'A landed cost covers this delivery.');
          Object.assign(r, { held_for_costing: false, version: (r.version ?? 1) + 1 });
          return r;
        }),
      ),

    previewLandedCost: (body) =>
      gate(() => {
        const { lines, ...rest } = landedWork(body);
        return { ...rest, lines };
      }),
    createLandedCost: (body, key) => gate(() => env.once(`landed|${key}`, body, () => landedView(postLanded(body)))),
    listLandedCosts: (q) =>
      gate(() => ({
        items: listed(landed, q, (x) => `${x.landed_no} ${x.supplier_ref} ${x.bill_no}`)
          .filter((x) => !q.receiptId || (x.lines ?? []).some((l) => l.receipt_id === q.receiptId))
          .map((x) => ({ ...landedView(x), lines: undefined, charges: undefined })),
      })),
    getLandedCost: (id) => gate(() => landedView(landedOf(id))),
    voidLandedCost: (id, reason, key) =>
      gate(() =>
        env.once(`landed-void|${key}`, { id, reason }, () => {
          const x = landedOf(id);
          const why = reasonOf(reason);
          if (x.status === 'voided') env.refuse(409, 'landed_cost_voided', 'This landed cost has been voided already.');
          const bill = billOf(x.bill_id ?? '');
          if ((bill.paid_minor ?? 0) > 0) env.refuse(409, 'bill_paid', 'Reverse the payment first.');
          (x.lines ?? []).forEach((l) => {
            if (l.cost_applied && env.cost(l.product_id ?? '') === l.cost_after_minor) env.move(l.branch_id ?? '', l.product_id ?? '', 0, l.cost_before_minor);
          });
          Object.assign(bill, { status: 'voided', open_minor: 0, void_reason: why, voided_on: env.today(), voided_at: new Date().toISOString(), voided_by: 'mock-user', version: (bill.version ?? 1) + 1 });
          Object.assign(x, { status: 'voided', void_reason: why, voided_on: env.today(), voided_at: new Date().toISOString(), voided_by: 'mock-user', version: (x.version ?? 1) + 1 });
          return landedView(x);
        }),
      ),

    createSupplierCredit: (body, key) => gate(() => env.once(`credit|${key}`, body, () => postCredit(body))),
    listSupplierCredits: (q) =>
      gate(() => ({
        items: listed(credits, q, (c) => `${c.credit_no} ${c.supplier_ref} ${c.bill_no}`)
          .filter((c) => !q.billId || c.bill_id === q.billId)
          .map((c) => ({ ...c, lines: undefined })),
      })),
    getSupplierCredit: (id) => gate(() => creditOf(id)),
    voidSupplierCredit: (id, reason, key) =>
      gate(() =>
        env.once(`credit-void|${key}`, { id, reason }, () => {
          const c = creditOf(id);
          const why = reasonOf(reason);
          if (c.status === 'voided') env.refuse(409, 'credit_voided', 'This credit note has been voided already.');
          (creditMoves.get(id) ?? []).forEach((m) => env.move(m.branchId, m.productId, m.baseMilli));
          const bill = billOf(c.bill_id ?? '');
          Object.assign(bill, { credited_minor: (bill.credited_minor ?? 0) - (c.total_minor ?? 0), open_minor: (bill.open_minor ?? 0) + (c.total_minor ?? 0) });
          Object.assign(c, { status: 'voided', void_reason: why, voided_on: env.today(), voided_at: new Date().toISOString(), voided_by: 'mock-user', version: (c.version ?? 1) + 1 });
          return c;
        }),
      ),
  };
}
