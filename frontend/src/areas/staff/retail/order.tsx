import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, useParams } from '@tanstack/react-router';
import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { businessToday, businessZone, retail, type Product } from '../../../api/retail';
import { retailOrders, type Order } from '../../../api/retail-orders';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { useStaff } from '../context';
import { OrderActions, OrderSummary } from './orders';
import { addItem, buildItemsRequest, feeOf, isOpen, itemsDraft, itemsMayChange, itemsProblem, itemsTotal, withItemsQty } from './orders-state';
import { newIdempotencyKey } from './idempotency';
import { UNITS, canUse, capabilityOn, holdsAt } from './permissions';
import { SaleLineCard } from './sale';
import { withUnit, type DraftLine } from './sale-state';
import { OpenSale } from './sales';
import { Gate, Note, Problem, ProductPicker, money, useBranchName } from './ui';
import { Hint } from '../../../app/ui/Hint';

// One customer order (issue #385, ADR-052): what was ordered, where it stands, who did what and when, the delivery details that
// can still change, and, once delivered, the sale it became (with its receipt, payments, returns and void like any sale). Its items
// change while it is ordered or packed (issue #393): the number stays, the stock kept follows, and the history says what changed.

const STEP_WORDS: Record<string, string> = {
  ordered: 'Order taken',
  changed: 'Details changed',
  items_changed: 'Items changed',
  packed: 'Packed',
  out_for_delivery: 'Out for delivery',
  delivered: 'Delivered',
  cancelled: 'Cancelled',
  fee_paid: 'Delivery fee paid',
  sale_voided: 'Sale voided',
};

function ChangeDetails({ order }: { order: Order }) {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState(order.customer_name ?? '');
  const [phone, setPhone] = useState(order.customer_phone ?? '');
  const [place, setPlace] = useState(order.delivery_place ?? '');
  const [date, setDate] = useState(order.delivery_date ?? '');
  const [fee, setFee] = useState(String(order.delivery_fee_minor ?? 0));
  const [notes, setNotes] = useState(order.notes ?? '');
  const save = useMutation({
    mutationFn: () => retailOrders.change(order.id ?? '', order.version ?? 1, {
      customer_name: name.trim(),
      customer_phone: phone.trim(),
      delivery_place: place.trim(),
      ...(date ? { delivery_date: date } : { clear_delivery_date: true }),
      delivery_fee_minor: feeOf(fee) ?? 0,
      notes: notes.trim(),
    }),
    onSuccess: () => {
      setOpen(false);
      void client.invalidateQueries({ queryKey: ['retail', 'order', order.id] });
      void client.invalidateQueries({ queryKey: ['retail', 'orders'] });
    },
  });
  const problem = name.trim() === '' ? 'Type the name of the customer.' : feeOf(fee) === null ? 'The delivery fee is a whole number of shillings.' : null;
  if (!open) return <button type="button" className="btn-sm" data-tour="order-change" onClick={() => setOpen(true)}>Change the delivery details</button>;
  return (
    <fieldset data-tour="order-change">
      <legend>Change the delivery details</legend>
      <label htmlFor="change-name">Name</label>
      <input id="change-name" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} />
      <label htmlFor="change-phone">Phone number</label>
      <input id="change-phone" inputMode="tel" value={phone} maxLength={100} onChange={(e) => setPhone(e.target.value)} />
      <label htmlFor="change-place">Deliver to</label>
      <input id="change-place" value={place} maxLength={300} onChange={(e) => setPlace(e.target.value)} />
      <label htmlFor="change-date">Delivery day</label>
      <input id="change-date" type="date" min={businessToday()} value={date} onChange={(e) => setDate(e.target.value)} />
      <label htmlFor="change-fee">Delivery fee</label>
      <input id="change-fee" inputMode="numeric" value={fee} onChange={(e) => setFee(e.target.value)} />
      <label htmlFor="change-notes">Notes</label>
      <input id="change-notes" value={notes} maxLength={500} onChange={(e) => setNotes(e.target.value)} />
      <p className="hint">To change the items, use Change the items.</p>
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      <Problem error={save.error} />
      <BusyButton busy={save.isPending} type="button" disabled={problem !== null} onClick={() => save.mutate()}>Save the changes</BusyButton>
      <button type="button" onClick={() => setOpen(false)}>Close</button>
    </fieldset>
  );
}

/** The items of an order that is not out yet: the same picker as a new order, the stock kept following when it is saved. */
function ChangeItems({ order }: { order: Order }) {
  const { me } = useStaff();
  const client = useQueryClient();
  const unitsOn = capabilityOn(me, 'retail', UNITS);
  const [open, setOpen] = useState(false);
  const [lines, setLines] = useState<DraftLine[] | null>(null);
  const [note, setNote] = useState('');
  const [reason, setReason] = useState('');
  const [key, setKey] = useState(newIdempotencyKey);
  const ids = [...new Set((order.lines ?? []).map((l) => l.product_id ?? ''))];
  const products = useQueries({
    queries: ids.map((id) => ({ queryKey: ['retail', 'product', id], queryFn: () => retail.getProduct(id), enabled: open })),
  });
  const read: Record<string, Product | undefined> = {};
  ids.forEach((id, i) => { read[id] = products[i]?.data; });
  const shown = lines ?? (open ? itemsDraft(order, read) : null);
  const save = useMutation({
    mutationFn: () => retailOrders.changeItems(
      order.id ?? '',
      order.version ?? 1,
      buildItemsRequest(order, (shown ?? []).map((l) => (reason.trim() ? { ...l, reason } : l)), note),
      key,
    ),
    onSuccess: () => {
      setKey(newIdempotencyKey());
      setOpen(false);
      setLines(null);
      void client.invalidateQueries({ queryKey: ['retail', 'order', order.id] });
      void client.invalidateQueries({ queryKey: ['retail', 'orders'] });
      void client.invalidateQueries({ queryKey: ['retail', 'stock'] });
    },
  });
  if (!open) return <button type="button" className="btn-sm" data-tour="order-items-change" onClick={() => setOpen(true)}>Change the items</button>;
  const edit = (f: (ls: DraftLine[]) => DraftLine[]) => setLines(f(shown ?? []));
  const add = (p: Product) => edit((ls) => addItem(ls, p));
  const problem = shown ? itemsProblem(shown) : null;
  return (
    <fieldset data-tour="order-items-change">
      <legend>Change the items</legend>
      <p className="hint">Add, take off or change the quantity of any item. The order keeps its number {order.order_no}.</p>
      {order.status === 'packed' && <Note>This order is packed already. Saving new items puts it back to ordered: pack it again after you save.</Note>}
      {products.some((q) => q.error) && <Problem error={products.find((q) => q.error)?.error} />}
      {!shown && !products.some((q) => q.error) && <p className="loading">Loading the items</p>}
      {shown && (
        <>
          <ProductPicker id="order-items-search" branchId={order.branch_id ?? ''} showPrice onAdd={add} />
          {shown.map((l, i) => (
            <SaleLineCard
              key={`${l.product.id}-${l.unitId ?? 'base'}-${i}`}
              line={l}
              index={i}
              unitsOn={unitsOn}
              onQty={(v) => edit((ls) => ls.map((x, n) => (n === i ? withItemsQty(order, x, v) : x)))}
              onPrice={(v) => edit((ls) => ls.map((x, n) => (n === i ? { ...x, price: v } : x)))}
              onUnit={(unitId) => edit((ls) => ls.map((x, n) => (n === i ? withUnit(x, unitId) : x)))}
              onRemove={() => edit((ls) => ls.filter((_, n) => n !== i))}
            />
          ))}
          <p className="rt-total" aria-live="polite">Items {money(itemsTotal(shown))}, delivery {money(order.delivery_fee_minor ?? 0)}, total {money(itemsTotal(shown) + (order.delivery_fee_minor ?? 0))}</p>
          <label htmlFor="items-reason">Why a price changed (needed only if you changed a price)</label>
          <input id="items-reason" value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} />
          <label htmlFor="items-note">Why the items changed (optional, it stays in the history)</label>
          <input id="items-note" value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} />
          <Hint full>Saving keeps the new items aside and puts back on the shelf what was taken off. An item left as it is on the order keeps the price agreed; a new item, or one whose quantity changed, takes today&apos;s price.</Hint>
          {problem && <Note>{problem}</Note>}
        </>
      )}
      <Problem error={save.error} />
      <BusyButton busy={save.isPending} type="button" className="rt-primary" disabled={!shown} notReady={problem !== null} onClick={() => save.mutate()}>Save the new items</BusyButton>
      <button type="button" onClick={() => { setOpen(false); setLines(null); save.reset(); }}>Close</button>
    </fieldset>
  );
}

/** A delivery fee left on a credit buyer's account, paid later: the money and how it came. */
function PayFee({ order }: { order: Order }) {
  const client = useQueryClient();
  const [method, setMethod] = useState<'cash' | 'mobile_money' | 'bank'>('cash');
  const [key, setKey] = useState(newIdempotencyKey);
  const pay = useMutation({
    mutationFn: () => retailOrders.payFee(order.id ?? '', method, key),
    onSuccess: () => {
      setKey(newIdempotencyKey());
      void client.invalidateQueries({ queryKey: ['retail', 'order', order.id] });
      void client.invalidateQueries({ queryKey: ['retail', 'orders'] });
    },
  });
  return (
    <fieldset>
      <legend>The delivery fee of {money(order.delivery_fee_minor ?? 0)} is on the account</legend>
      <label htmlFor="fee-method">Paid by</label>
      <select id="fee-method" value={method} onChange={(e) => setMethod(e.target.value as typeof method)}>
        <option value="cash">Cash</option>
        <option value="mobile_money">Mobile money</option>
        <option value="bank">Bank</option>
      </select>
      <Problem error={pay.error} />
      <BusyButton busy={pay.isPending} type="button" onClick={() => pay.mutate()}>Save: the fee is paid</BusyButton>
    </fieldset>
  );
}

export function OrderPage({ orderId }: { orderId: string }) {
  const { me } = useStaff();
  const nameOf = useBranchName();
  const [saleOpen, setSaleOpen] = useState(false);
  const order = useQuery({ queryKey: ['retail', 'order', orderId], queryFn: () => retailOrders.get(orderId) });
  const o = order.data;
  useCrumbLabel(o?.order_no ? `Order ${o.order_no}` : undefined);
  return (
    <Gate screen="orders" title="Order">
      <p className="rt-row" data-tour="order-back">
        <Link className="btn-sm" to="/staff/retail/orders">Back to the orders</Link>
        {canUse(me, 'ordersReport') && <Link className="btn-sm" to="/staff/retail/orders-report">Delivery fees and cancellations</Link>}
      </p>
      {order.isPending && <p className="loading">Loading</p>}
      <Problem error={order.error} />
      <section className="rt-card" data-tour="order-summary" aria-label="The order">
        {o && <OrderSummary order={o} branchName={nameOf(o.branch_id)} />}
        {o && isOpen(o) && <OrderActions order={o} />}
      </section>
      {o && itemsMayChange(o) && <ChangeItems key={`items-${o.version}`} order={o} />}
      {o && isOpen(o) && <ChangeDetails key={o.version} order={o} />}
      {o && o.status === 'delivered' && o.fee_method === 'account' && !o.fee_paid_on && !o.sale_voided && holdsAt(me, 'retail.sale.create', o.branch_id) && <PayFee order={o} />}
      {o?.sale_id && !saleOpen && (
        <p><button type="button" className="btn-sm" onClick={() => setSaleOpen(true)}>Open sale {o.sale_no}</button></p>
      )}
      {o?.sale_id && saleOpen && <OpenSale id={o.sale_id} onClose={() => setSaleOpen(false)} />}
      <section data-tour="order-history" aria-label="History">
        <h2>History</h2>
        {o && (
          <ol>
            {(o.history ?? []).map((e, i) => (
              <li key={`${e.at}-${i}`}>
                <strong>{STEP_WORDS[e.step ?? ''] ?? e.step}</strong>, {new Date(e.at ?? '').toLocaleString(undefined, { timeZone: businessZone() })}{e.by_name ? `, by ${e.by_name}` : ''}
                {e.note ? <span className="hint">: {e.note}</span> : null}
              </li>
            ))}
          </ol>
        )}
      </section>
    </Gate>
  );
}

function OrderRoute() {
  const { orderId } = useParams({ from: '/staff/retail/orders/$orderId' });
  return <OrderPage orderId={orderId} />;
}

export const Route = createLazyRoute('/staff/retail/orders/$orderId')({ component: OrderRoute });
