import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type PurchaseOrder } from '../../../api/retail';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { showQty } from './maths';
import { PURCHASE_CREATE, canUse, holdsAt } from './permissions';
import { canCancelOrder, canEditOrder, canReceiveOrder, canSendOrder, inLineUnit, orderStatusWords, sendOutcomeWords } from './purchasing-state';
import { Facts, ReasonPanel, StatusBadge, SupplierOwed, useActionKeys } from './purchasing-ui';
import { showDate } from './transfers';
import type { ToastTone } from './toast';
import { Problem, Success, money, useBranchName, useToast } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';
import { NoAccess } from '../../../app/ui/EmptyState';
import { Hint } from '../../../app/ui/Hint';

// One purchase order (the purchasing capability, ADR-033, issue #198): its lines with ordered, received and (for whoever manages
// supplier invoices) billed, its status and its receipts, and what may be done next: change a draft, send it, cancel it, or
// receive goods against it. Large orders wait for a second person: the order stays a draft until they approve it in Approvals.

/** The order: facts, lines and the receipts made against it. Billed columns show only when the server sent them. */
export function PurchaseOrderView({ order, nameOf, payable }: { order: PurchaseOrder; nameOf: (branchId: string | undefined) => string; payable?: boolean }) {
  const lines = order.lines ?? [];
  const showBilled = lines.some((l) => l.billed_qty !== undefined);
  return (
    <section aria-label="Purchase order" data-testid="purchase-order" data-tour="order-summary">
      <h2>{order.order_no} <StatusBadge words={orderStatusWords(order.status)} warn={order.status === 'cancelled'} /></h2>
      <Facts
        rows={[
          ['Supplier', <SupplierOwed key="s" id={order.supplier_id} name={order.supplier_name} payable={payable} />],
          ['Goods go to', nameOf(order.branch_id)],
          ['Ordered on', showDate(order.ordered_on)],
          ...(order.expected_on ? [['Expected on', showDate(order.expected_on)] as [string, string]] : []),
          ...(order.note ? [['Note', order.note] as [string, string]] : []),
          ...(order.status === 'cancelled' ? [['Cancelled because', order.cancel_reason ?? ''] as [string, string]] : []),
        ]}
      />
      <div className="table-wrap" tabIndex={0} data-tour="order-lines">
        <table>
          <thead>
            <tr>
              <th>Item</th>
              <th className="num">Ordered</th>
              <th className="num">Received</th>
              {showBilled && <th className="num">Billed</th>}
              <th className="num">Cost of one</th>
              <th className="num">Total</th>
            </tr>
          </thead>
          <tbody>
            {lines.map((l) => (
              <tr key={l.id}>
                <td>{l.description}</td>
                <td className="num">{showQty(l.unit_qty ?? l.qty ?? '0')} {l.unit}</td>
                <td className="num">{inLineUnit(l.received_qty, l)} {l.unit}</td>
                {showBilled && <td className="num">{inLineUnit(l.billed_qty, l)} {l.unit}</td>}
                <td className="num">{money(l.unit_cost_minor ?? 0)}</td>
                <td className="num">{money(l.line_total_minor ?? 0)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr><td colSpan={showBilled ? 5 : 4}><strong>Order total</strong></td><td className="num"><strong>{money(order.total_minor ?? 0)}</strong></td></tr>
          </tfoot>
        </table>
      </div>
      <h3 data-tour="order-receipts">Goods received against this order</h3>
      {(order.receipts ?? []).length === 0 ? (
        <p className="empty-state">Nothing has been received yet.</p>
      ) : (
        <ul style={{ listStyle: 'none', padding: 0 }} data-testid="order-receipts">
          {(order.receipts ?? []).map((r) => (
            <li key={r.id} className="rt-card">
              <Link to="/staff/retail/goods-received/$receiptId" params={{ receiptId: r.id ?? '' }}>{r.receipt_no}</Link>, {showDate(r.received_on)}, {money(r.total_minor ?? 0)}
              {r.status === 'voided' && <> <StatusBadge words="Voided" warn /></>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

const route = getRouteApi('/staff/retail/purchase-orders/$orderId');

export function PurchaseOrderPage() {
  const { orderId } = route.useParams();
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const nameOf = useBranchName();
  const keys = useActionKeys();
  const [message, setMessage] = useState<string | null>(null);
  const allowed = canUse(me, 'purchaseOrders');
  const order = useQuery({ queryKey: ['retail', 'purchase-order', orderId], queryFn: () => retail.getPurchaseOrder(orderId), enabled: allowed });
  useCrumbLabel(order.data?.order_no);
  const done = (text: string, tone: ToastTone = 'success') => {
    setMessage(text);
    show(text, tone);
    void queryClient.invalidateQueries({ queryKey: ['retail'] });
  };
  // toast-exempt: done above shows the success toast through show(text, tone), its tone 'success' unless it waits for approval
  const send = useMutation({
    mutationFn: () => retail.sendPurchaseOrder(orderId, keys.keyFor(orderId, 'send')),
    onSuccess: (r) => {
      keys.done(orderId);
      done(sendOutcomeWords(r), r.executed === false ? 'info' : 'success');
    },
  });
  // toast-exempt: done above shows the success toast through show(text, tone), its tone 'success' by default
  const cancel = useMutation({
    mutationFn: (reason: string) => retail.cancelPurchaseOrder(orderId, reason, keys.keyFor(orderId, reason)),
    onSuccess: (o) => {
      keys.done(orderId);
      done(`Cancelled ${o.order_no ?? 'the order'}.`);
    },
  });
  if (!allowed) {
    return (
      <main className="rt">
        <h1>Purchase order</h1>
        <NoAccess />
      </main>
    );
  }
  const o = order.data;
  const mayAct = o !== undefined && holdsAt(me, PURCHASE_CREATE, o.branch_id);
  return (
    <main className="rt rt-wide">
      <h1>{o?.order_no ?? 'Purchase order'}</h1>
      {order.isPending && <SkeletonList label="Loading the order" />}
      <Problem error={order.error} />
      {message && <Success>{message}</Success>}
      {o && <PurchaseOrderView order={o} nameOf={nameOf} payable={canUse(me, 'supplierPayments')} />}
      {o && mayAct && (
        <div className="stack" data-tour="order-actions">
          <p className="cluster">
            {canEditOrder(o) && <Link className="btn-sm" to="/staff/retail/purchase-orders/$orderId/edit" params={{ orderId }}>Change this order</Link>}
            {canSendOrder(o) && <BusyButton type="button" className="rt-primary" busy={send.isPending} onClick={() => send.mutate()}>Send to the supplier</BusyButton>
}
            {canReceiveOrder(o) && <Link className="btn rt-primary" to="/staff/retail/purchase-orders/$orderId/receive" params={{ orderId }}>Receive goods</Link>}
          </p>
          {canSendOrder(o) && <Hint full>Sending locks the order. A large order needs a second person to approve it first.</Hint>}
          <Problem error={send.error} />
          {canCancelOrder(o) && (
            <ReasonPanel openLabel="Cancel this order" question="Why is it cancelled?" hint="What was already received stays received. Nothing more can be received against a cancelled order." confirmLabel="Cancel the order" busy={cancel.isPending} error={cancel.error} onSubmit={(reason) => cancel.mutate(reason)} />
          )}
        </div>
      )}
      {toast}
    </main>
  );
}

export const Route = createLazyRoute('/staff/retail/purchase-orders/$orderId')({ component: PurchaseOrderPage });
