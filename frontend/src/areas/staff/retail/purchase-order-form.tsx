import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi, useNavigate } from '@tanstack/react-router';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { retail, type Product } from '../../../api/retail';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { PURCHASE_CREATE, UNITS, branchesWhere, canUse, capabilityOn } from './permissions';
import {
  blankOrder, buildOrderRequest, formFromOrder, orderLineFor, orderLineProblem, orderLineTotal, orderProblem, orderTotal, withOrderUnit,
  type OrderForm, type OrderLineForm,
} from './purchasing-state';
import { BranchField, SupplierField, useSuppliers, type SupplierOption } from './purchasing-ui';
import { showQty } from './maths';
import { Gate, Problem, ProductPicker, money } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';

// The purchase order form (the purchasing capability, ADR-033, issue #198): the supplier, the date it is expected, and the items
// with how many and what one costs. Saving makes a DRAFT; nothing is ordered and no stock moves until the order is sent. The cost
// is for one unit as entered: for a pack, one pack. The same form changes a draft.

/** One item of the order: its unit, how many, what one costs, and what the line comes to. */
export function OrderLineCard({ line, index, onChange, onRemove }: { line: OrderLineForm; index: number; onChange: (line: OrderLineForm) => void; onRemove: () => void }) {
  const problem = line.qty.trim() === '' && line.cost.trim() === '' ? null : orderLineProblem(line);
  return (
    <li className="rt-card" data-testid={`order-line-${index}`}>
      <p><strong>{line.description}</strong> ({line.code})</p>
      {line.packs.length > 0 && (
        <>
          <label htmlFor={`unit-${index}`}>Ordered by</label>
          <select id={`unit-${index}`} value={line.unitId ?? ''} onChange={(e) => onChange(withOrderUnit(line, e.target.value))}>
            <option value="">{line.baseUnit}</option>
            {line.packs.map((k) => <option key={k.unit_id} value={k.unit_id}>{k.unit} ({showQty(k.factor ?? '')} each)</option>)}
          </select>
        </>
      )}
      <div className="rt-row">
        <div>
          <label htmlFor={`qty-${index}`}>How many {line.description} ({line.unitLabel})</label>
          <input id={`qty-${index}`} inputMode="decimal" value={line.qty} onChange={(e) => onChange({ ...line, qty: e.target.value })} aria-invalid={problem !== null} />
        </div>
        <div>
          <label htmlFor={`cost-${index}`}>Cost of one {line.unitLabel} of {line.description}</label>
          <input id={`cost-${index}`} inputMode="numeric" value={line.cost} onChange={(e) => onChange({ ...line, cost: e.target.value })} aria-invalid={problem !== null} />
        </div>
      </div>
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      <p data-testid={`order-line-total-${index}`}>Line total <strong>{money(orderLineTotal(line))}</strong></p>
      <button type="button" className="btn-ghost" onClick={onRemove} aria-label={`Remove ${line.description}`}>Remove</button>
    </li>
  );
}

/** The whole form, controlled by the page so a draft survives a reload. `picker` finds the items to add. */
export function OrderFormView({ form, suppliers, branches, picker, busy, error, submitLabel = 'Save draft order', onChange, onSubmit }: {
  form: OrderForm;
  suppliers: SupplierOption[];
  branches: { id?: string; name?: string; code?: string }[];
  picker: ReactNode;
  busy?: boolean;
  error?: unknown;
  submitLabel?: string;
  onChange: (form: OrderForm) => void;
  onSubmit: () => void;
}) {
  const problem = orderProblem(form);
  const setLine = (i: number, line: OrderLineForm) => onChange({ ...form, lines: form.lines.map((l, n) => (n === i ? line : l)) });
  return (
    <form className="stack" onSubmit={(e) => { e.preventDefault(); if (!problem && !busy) onSubmit(); }} data-tour="order-form">
      <p className="hint">Saving makes a draft. Nothing is ordered, and no stock moves, until you send the order.</p>
      <div className="stack" data-tour="order-supplier">
        <SupplierField id="order-supplier" value={form.supplierId} suppliers={suppliers} onChange={(supplierId) => onChange({ ...form, supplierId })} />
        {branches.length > 0 && <BranchField id="order-branch" label="Goods are expected at" value={form.branchId} branches={branches} onChange={(branchId) => onChange({ ...form, branchId })} />}
      </div>
      <label htmlFor="order-expected">Expected on (optional)</label>
      <input id="order-expected" type="date" value={form.expectedOn} onChange={(e) => onChange({ ...form, expectedOn: e.target.value })} />
      <label htmlFor="order-note">Note (optional)</label>
      <input id="order-note" value={form.note} maxLength={300} onChange={(e) => onChange({ ...form, note: e.target.value })} />
      <div data-tour="order-items">{picker}</div>
      <h2>Items</h2>
      {form.lines.length === 0 && <p className="empty-state">No items yet.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }} data-tour="order-lines">
        {form.lines.map((l, i) => (
          <OrderLineCard key={`${l.productId}-${i}`} line={l} index={i} onChange={(line) => setLine(i, line)} onRemove={() => onChange({ ...form, lines: form.lines.filter((_, n) => n !== i) })} />
        ))}
      </ul>
      <p className="rt-total" aria-live="polite" data-testid="order-total">Order total {money(orderTotal(form.lines))}</p>
      {problem && form.lines.length > 0 && <p role="alert" className="rt-flag">{problem}</p>}
      <Problem error={error} />
      <BusyButton type="submit" className="rt-primary" busy={busy === true} disabled={problem !== null} data-tour="order-save">{submitLabel}</BusyButton>

    </form>
  );
}

/** The branches a purchase order may be for: where the session holds the restock permission. The form's default is the branch chosen at the top. */
export function useOrderBranches(): { branches: { id?: string; name?: string; code?: string }[]; defaultBranch: string } {
  const { me, branch } = useStaff();
  const branches = branchesWhere(me, PURCHASE_CREATE).filter((b) => b.id && b.stocks_goods !== false);
  const defaultBranch = branches.find((b) => b.id === branch)?.id ?? (branches.length === 1 ? (branches[0]?.id ?? '') : '');
  return { branches, defaultBranch };
}

function useItemPicker(onAdd: (line: OrderLineForm) => void): ReactNode {
  const { me } = useStaff();
  const unitsOn = capabilityOn(me, 'retail', UNITS);
  return (
    <ProductPicker
      id="order-search"
      showStock={false}
      onAdd={(p: Product) => onAdd(orderLineFor(p, unitsOn))}
    />
  );
}

export function PurchaseOrderNewPage() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { branches, defaultBranch } = useOrderBranches();
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<OrderForm>(`purchase-order:${me.user_id ?? ''}`, blankOrder(defaultBranch));
  const suppliers = useSuppliers(draft.supplierId);
  const save = useMutation({
    mutationFn: () => retail.createPurchaseOrder(buildOrderRequest(draft), key),
    onSuccess: (order) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail', 'purchase-orders'] });
      void navigate({ to: '/staff/retail/purchase-orders/$orderId', params: { orderId: order.id ?? '' } });
    },
  });
  const picker = useItemPicker((line) => setDraft((d) => ({ ...d, lines: [...d.lines, line] })));
  return (
    <Gate screen="purchaseOrders" title="New purchase order">
      <p><Link to="/staff/retail/purchase-orders">Back to purchase orders</Link></p>
      <OrderFormView form={draft} suppliers={suppliers} branches={branches} picker={picker} busy={save.isPending} error={save.error} onChange={(f) => setDraft(() => f)} onSubmit={() => save.mutate()} />
      {draft.lines.length > 0 && !save.isPending && <button type="button" className="btn-ghost" onClick={() => { discard(); save.reset(); }}>Clear this order</button>}
    </Gate>
  );
}

const editRoute = getRouteApi('/staff/retail/purchase-orders/$orderId/edit');

/** Changes a draft order: the form is filled from the order and saved with the order's version in If-Match. */
export function PurchaseOrderEditPage() {
  const { orderId } = editRoute.useParams();
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { branches } = useOrderBranches();
  const allowed = canUse(me, 'purchaseOrders');
  const order = useQuery({ queryKey: ['retail', 'purchase-order', orderId], queryFn: () => retail.getPurchaseOrder(orderId), enabled: allowed });
  useCrumbLabel(order.data?.order_no ? `Change ${order.data.order_no}` : undefined);
  const [form, setForm] = useState<OrderForm | null>(() => (order.data ? formFromOrder(order.data) : null));
  // The form is filled from the order when it is first read and again when someone else changes it (a new version), not on every refetch.
  // If the buyer has unsaved edits when a newer version arrives (a Refresh, or the window regaining focus), the edits are kept and a
  // notice offers the saved version instead of overwriting them.
  const version = order.data?.version;
  const formRef = useRef(form);
  formRef.current = form;
  const loadedRef = useRef<string | null>(null);
  // The version the form was filled from: Save sends this one, never the version of a refetch the buyer has not read, so the server refuses a stale write.
  const loadedVersionRef = useRef<number | null>(null);
  const [changedElsewhere, setChangedElsewhere] = useState(false);
  const loadSaved = () => {
    if (!order.data) return;
    const fresh = formFromOrder(order.data);
    loadedRef.current = JSON.stringify(fresh);
    loadedVersionRef.current = order.data.version ?? null;
    setForm(fresh);
    setChangedElsewhere(false);
  };
  useEffect(() => {
    if (!order.data) return;
    const dirty = loadedRef.current !== null && formRef.current !== null && JSON.stringify(formRef.current) !== loadedRef.current;
    if (dirty) setChangedElsewhere(true);
    else loadSaved();
  }, [version]);
  const suppliers = useSuppliers(form?.supplierId);
  const save = useMutation({
    mutationFn: (f: OrderForm) => retail.updatePurchaseOrder(orderId, loadedVersionRef.current ?? order.data?.version ?? 0, buildOrderRequest(f)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      void navigate({ to: '/staff/retail/purchase-orders/$orderId', params: { orderId } });
    },
  });
  const picker = useItemPicker((line) => setForm((f) => (f ? { ...f, lines: [...f.lines, line] } : f)));
  return (
    <Gate screen="purchaseOrders" title={order.data?.order_no ? `Change ${order.data.order_no}` : 'Change purchase order'}>
      <p><Link to="/staff/retail/purchase-orders/$orderId" params={{ orderId }}>Back to the order</Link></p>
      {order.isPending && <SkeletonList label="Loading the order" />}
      <Problem error={order.error} />
      {order.data && order.data.status !== 'draft' && <p className="empty-state">This order has been sent, so it can no longer be changed.</p>}
      {changedElsewhere && (
        <div role="alert" className="alert alert-warning">
          <p>Someone else changed this order while you were editing. Your changes are still here and have not been saved. Saving now would replace their changes.</p>
          <button type="button" className="btn-ghost" onClick={loadSaved}>Discard my changes and show their version</button>
        </div>
      )}
      {order.data?.status === 'draft' && form && (
        <OrderFormView form={form} suppliers={suppliers} branches={branches} picker={picker} busy={save.isPending} error={save.error} submitLabel="Save changes" onChange={setForm} onSubmit={() => save.mutate(form)} />
      )}
    </Gate>
  );
}

export const NewRoute = createLazyRoute('/staff/retail/purchase-orders/new')({ component: PurchaseOrderNewPage });
export const EditRoute = createLazyRoute('/staff/retail/purchase-orders/$orderId/edit')({ component: PurchaseOrderEditPage });
