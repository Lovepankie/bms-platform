import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi, useNavigate } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { retail, type GoodsReceiptRequest, type Product, type PurchaseOrder } from '../../../api/retail';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { newIdempotencyKey, usePersistedDraft } from './idempotency';
import { UNITS, canUse, capabilityOn } from './permissions';
import {
  blankReceipt, blindLineFor, buildReceiptRequest, isReceived, overOrdered, receiptProblem, receiveFormFor, receiveLineProblem, receiveTotal, withReceiveUnit,
  type ReceiveForm, type ReceiveLineForm,
} from './purchasing-state';
import { useOrderBranches } from './purchase-order-form';
import { BranchField, SupplierField, useSuppliers, type SupplierOption } from './purchasing-ui';
import { showQty } from './maths';
import { Gate, Problem, ProductPicker, money } from './ui';

// Goods received (the purchasing capability, ADR-033, issue #198): what arrived from a supplier, against an order or with none (a
// "blind" receipt, which needs the supplier). Saving puts the goods into stock at the cost typed and, when a new selling price
// is typed, sets the item's price. The form starts with what is still to come on the order and its cost; the person changes
// what really arrived. Over-receiving is allowed only up to the allowance the business set, and the server says when it is too much.

function LineCard({ line, index, order, onChange, onRemove }: {
  line: ReceiveLineForm;
  index: number;
  order?: PurchaseOrder;
  onChange: (line: ReceiveLineForm) => void;
  onRemove?: () => void;
}) {
  const problem = receiveLineProblem(line);
  const more = overOrdered(line, order);
  return (
    <li className="rt-card" data-testid={`receive-line-${index}`}>
      <p><strong>{line.description}</strong> ({line.code})</p>
      {line.orderedText !== undefined && (
        <p className="hint">Ordered {line.orderedText} {line.unitLabel}{line.stillToCome ? `, still to come ${line.stillToCome}` : ''}.</p>
      )}
      {line.packs.length > 0 && (
        <>
          <label htmlFor={`rc-unit-${index}`}>Received by</label>
          <select id={`rc-unit-${index}`} value={line.unitId ?? ''} onChange={(e) => onChange(withReceiveUnit(line, e.target.value))}>
            <option value="">{line.baseUnit}</option>
            {line.packs.map((k) => <option key={k.unit_id} value={k.unit_id}>{k.unit} ({showQty(k.factor ?? '')} each)</option>)}
          </select>
        </>
      )}
      <div className="rt-row">
        <div>
          <label htmlFor={`rc-qty-${index}`}>Received of {line.description} ({line.unitLabel})</label>
          <input id={`rc-qty-${index}`} inputMode="decimal" value={line.qty} placeholder="0" onChange={(e) => onChange({ ...line, qty: e.target.value })} aria-invalid={problem !== null} />
        </div>
        <div>
          <label htmlFor={`rc-cost-${index}`}>Cost of one {line.unitLabel} of {line.description}</label>
          <input id={`rc-cost-${index}`} inputMode="numeric" value={line.cost} onChange={(e) => onChange({ ...line, cost: e.target.value })} aria-invalid={problem !== null} />
        </div>
      </div>
      <label htmlFor={`rc-sell-${index}`}>New selling price of {line.description}, per {line.baseUnit} (optional)</label>
      <input id={`rc-sell-${index}`} inputMode="numeric" value={line.sell} placeholder="Leave blank to keep the price" onChange={(e) => onChange({ ...line, sell: e.target.value })} />
      {more && <p role="note" className="alert alert-warning">This is more than was ordered. It is allowed only up to the amount the business has set; if it is too much, the server will say so.</p>}
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      {isReceived(line) && problem === null && <p data-testid={`receive-line-total-${index}`}>Worth <strong>{money(receiveTotal([line]))}</strong></p>}
      {onRemove && <button type="button" className="btn-ghost" onClick={onRemove} aria-label={`Remove ${line.description}`}>Remove</button>}
    </li>
  );
}

/** The whole form, controlled by the page so a draft survives a reload. `blind` means no order: the supplier is asked for, and items are picked. */
export function ReceiveFormView({ form, blind, order, suppliers, branches, picker, busy, error, onChange, onSubmit }: {
  form: ReceiveForm;
  blind: boolean;
  order?: PurchaseOrder;
  suppliers: SupplierOption[];
  branches: { id?: string; name?: string; code?: string }[];
  picker?: ReactNode;
  busy?: boolean;
  error?: unknown;
  onChange: (form: ReceiveForm) => void;
  onSubmit: () => void;
}) {
  const problem = receiptProblem(form, blind);
  const setLine = (i: number, line: ReceiveLineForm) => onChange({ ...form, lines: form.lines.map((l, n) => (n === i ? line : l)) });
  return (
    <form className="stack" onSubmit={(e) => { e.preventDefault(); if (!problem && !busy) onSubmit(); }} data-tour="receive-form">
      <div className="stack" data-tour="receive-who">
        {blind ? (
          <SupplierField id="receive-supplier" value={form.supplierId} suppliers={suppliers} onChange={(supplierId) => onChange({ ...form, supplierId })} />
        ) : (
          <p><strong>Supplier:</strong> {order?.supplier_name}</p>
        )}
        <BranchField id="receive-branch" label="The goods go into" value={form.branchId} branches={branches} onChange={(branchId) => onChange({ ...form, branchId })} />
        <label htmlFor="receive-date">Received on</label>
        <input id="receive-date" type="date" value={form.receivedOn} onChange={(e) => onChange({ ...form, receivedOn: e.target.value })} />
        <label htmlFor="receive-ref">Supplier&apos;s delivery note number (optional)</label>
        <input id="receive-ref" value={form.deliveryRef} maxLength={60} onChange={(e) => onChange({ ...form, deliveryRef: e.target.value })} />
      </div>
      <label htmlFor="receive-note">Note (optional)</label>
      <input id="receive-note" value={form.note} maxLength={300} onChange={(e) => onChange({ ...form, note: e.target.value })} />
      <div data-tour="receive-hold">
        <label>
          <input type="checkbox" checked={form.hold === true} onChange={(e) => onChange({ ...form, hold: e.target.checked })} />
          <span>Hold until the landed cost is in</span>
        </label>
        <p className="hint">Tick this when freight or duty is still to come. The delivery shows as held, not costed, until you add the landed cost or release the hold.</p>
      </div>
      {picker && <div data-tour="receive-items">{picker}</div>}
      <h2>What arrived</h2>
      {form.lines.length === 0 && <p className="empty-state">{blind ? 'No items yet. Find an item above and add it.' : 'Everything on this order has arrived.'}</p>}
      <ul style={{ listStyle: 'none', padding: 0 }} data-tour="receive-lines">
        {form.lines.map((l, i) => (
          <LineCard key={l.key} line={l} index={i} order={order} onChange={(line) => setLine(i, line)} onRemove={blind ? () => onChange({ ...form, lines: form.lines.filter((_, n) => n !== i) }) : undefined} />
        ))}
      </ul>
      <p className="rt-total" aria-live="polite" data-testid="receive-total">Worth {money(receiveTotal(form.lines))}</p>
      {problem && form.lines.length > 0 && <p role="alert" className="rt-flag">{problem}</p>}
      <Problem error={error} />
      <button type="submit" className="rt-primary" disabled={problem !== null || busy} data-tour="receive-save">{busy ? 'Saving' : 'Save goods received'}</button>
    </form>
  );
}

function useSaveReceipt(key: string, finish: () => void, build: () => GoodsReceiptRequest) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return useMutation({
    mutationFn: () => retail.createGoodsReceipt(build(), key),
    onSuccess: (receipt) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      void navigate({ to: '/staff/retail/goods-received/$receiptId', params: { receiptId: receipt.id ?? '' } });
    },
  });
}

const orderRoute = getRouteApi('/staff/retail/purchase-orders/$orderId/receive');

/** Receive goods against a sent order: one line for each order line that still has something to come. */
export function ReceiveAgainstOrderPage() {
  const { orderId } = orderRoute.useParams();
  const { me } = useStaff();
  const { branches, defaultBranch } = useOrderBranches();
  const allowed = canUse(me, 'goodsReceived');
  const order = useQuery({ queryKey: ['retail', 'purchase-order', orderId], queryFn: () => retail.getPurchaseOrder(orderId), enabled: allowed });
  useCrumbLabel(order.data?.order_no ? `Receive ${order.data.order_no}` : undefined);
  const { key, draft, setDraft, finish } = usePersistedDraft<ReceiveForm>(`goods-received:${me.user_id ?? ''}:${orderId}`, blankReceipt(defaultBranch));
  // Until something is edited the form is the order's own: what is still to come, at the cost ordered. The first edit keeps the whole form.
  const form = draft.lines.length === 0 && order.data ? { ...receiveFormFor(order.data, defaultBranch), ...(draft.branchId ? { branchId: draft.branchId } : {}) } : draft;
  const save = useSaveReceipt(key, finish, () => buildReceiptRequest(form, orderId));
  const open = order.data?.status === 'sent' || order.data?.status === 'partly_received';
  return (
    <Gate screen="goodsReceived" title={order.data?.order_no ? `Receive goods for ${order.data.order_no}` : 'Receive goods'}>
      <p><Link to="/staff/retail/purchase-orders/$orderId" params={{ orderId }}>Back to the order</Link></p>
      {order.isPending && <SkeletonList label="Loading the order" />}
      <Problem error={order.error} />
      {order.data && !open && <p className="empty-state">Goods can be received only against an order that has been sent and is not yet complete.</p>}
      {order.data && open && (
        <ReceiveFormView form={form} blind={false} order={order.data} suppliers={[]} branches={branches} busy={save.isPending} error={save.error} onChange={(f) => setDraft(() => f)} onSubmit={() => save.mutate()} />
      )}
    </Gate>
  );
}

/** Receive goods with no order: the supplier, then the items, how many and at what cost. */
export function ReceiveBlindPage() {
  const { me } = useStaff();
  const { branches, defaultBranch } = useOrderBranches();
  const unitsOn = capabilityOn(me, 'retail', UNITS);
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<ReceiveForm>(`goods-received:${me.user_id ?? ''}:blind`, blankReceipt(defaultBranch));
  const suppliers = useSuppliers(draft.supplierId);
  const save = useSaveReceipt(key, finish, () => buildReceiptRequest(draft));
  const picker = (
    <ProductPicker id="receive-search" showStock={false} onAdd={(p: Product) => setDraft((d) => ({ ...d, lines: [...d.lines, blindLineFor(p, unitsOn, newIdempotencyKey())] }))} />
  );
  return (
    <Gate screen="goodsReceived" title="Receive goods without an order">
      <p><Link to="/staff/retail/goods-received">Back to goods received</Link></p>
      <p className="hint">Use this when goods arrive with no purchase order. Choose the supplier, add what arrived and what it cost.</p>
      <ReceiveFormView form={draft} blind suppliers={suppliers} branches={branches} picker={picker} busy={save.isPending} error={save.error} onChange={(f) => setDraft(() => f)} onSubmit={() => save.mutate()} />
      {draft.lines.length > 0 && !save.isPending && <button type="button" className="btn-ghost" onClick={() => { discard(); save.reset(); }}>Clear this form</button>}
    </Gate>
  );
}

export const OrderReceiveRoute = createLazyRoute('/staff/retail/purchase-orders/$orderId/receive')({ component: ReceiveAgainstOrderPage });
export const BlindRoute = createLazyRoute('/staff/retail/goods-received/new')({ component: ReceiveBlindPage });

