import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { businessToday, retail, type Product } from '../../../api/retail';
import { retailOrders, type Delivered, type Order, type OrderQuery, type OrderSource } from '../../../api/retail-orders';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { EmptyState } from '../../../components/states';
import { useStaff } from '../context';
import { newIdempotencyKey, usePersistedDraft } from './idempotency';
import { showDate } from './maths';
import {
  SOURCES, blankDeliver, blankOrder, buildDeliverRequest, buildOrderRequest, deliverFigures, deliverProblem, feeOf, isOpen, itemsTotal,
  lookupKey, nextSteps, orderProblem, payChoicesFor, sourceLabel, stepOf, type DeliverForm, type OrderDraft,
} from './orders-state';
import { UNITS, capabilityOn, holdsAt } from './permissions';
import { newLine, withUnit } from './sale-state';
import { SaleLineCard } from './sale';
import { ScreenLink } from './screen-link';
import { Gate, Note, Problem, ProductPicker, money, useBranchName, useBranchView, useSingleBranch } from './ui';

// Customer orders with delivery (issue #385, ADR-052): orders that come by WhatsApp, Instagram, Facebook, a phone call or at the
// counter. Saving an order keeps its items aside so the till cannot sell them; nothing is paid yet. The order is packed, sent out
// with whoever delivers it, and on delivery the money is taken (all of it, part of it, or on credit) and it becomes a normal sale,
// with the delivery fee shown on its own. Cancelling puts the items back on the shelf and keeps the reason.

const invalidate = (client: ReturnType<typeof useQueryClient>) => {
  void client.invalidateQueries({ queryKey: ['retail', 'orders'] });
  void client.invalidateQueries({ queryKey: ['retail', 'order'] });
  void client.invalidateQueries({ queryKey: ['retail', 'stock'] });
  void client.invalidateQueries({ queryKey: ['retail', 'products'] });
  // Home's open orders, and on delivery its sales and takings (issue #485).
  void client.invalidateQueries({ queryKey: ['retail', 'home'] });
};

/** The money the customer pays on delivery, how, and what is left on credit. */
export function DeliverPanel({ order, onDone }: { order: Order; onDone: (d: Delivered) => void }) {
  const client = useQueryClient();
  const [form, setForm] = useState<DeliverForm>(blankDeliver());
  const [key, setKey] = useState(newIdempotencyKey);
  const deliver = useMutation({
    mutationFn: () => retailOrders.deliver(order.id ?? '', buildDeliverRequest(order, form), key),
    onSuccess: (d) => {
      setKey(newIdempotencyKey());
      invalidate(client);
      void client.invalidateQueries({ queryKey: ['retail', 'sales'] });
      onDone(d);
    },
  });
  const problem = deliverProblem(order, form);
  const fig = deliverFigures(order, form);
  const later = form.choice === 'part' || form.choice === 'credit' || form.choice === 'account';
  return (
    <fieldset data-testid="deliver-panel">
      <legend>Delivered: how did the customer pay?</legend>
      <p className="hint">Items {money(order.items_total_minor ?? 0)} and delivery {money(order.delivery_fee_minor ?? 0)}: <strong>{money(order.total_minor ?? 0)}</strong></p>
      {payChoicesFor(order).map((c) => (
        <label key={c.value} className="check">
          <input type="radio" name={`pay-${order.id}`} checked={form.choice === c.value} onChange={() => setForm({ ...form, choice: c.value })} /> {c.label}
        </label>
      ))}
      {(form.choice === 'part' || form.choice === 'account') && (
        <>
          <label htmlFor={`paid-${order.id}`}>{form.choice === 'part' ? 'Amount paid now (at least the delivery fee)' : 'Amount paid now for the items (optional)'}</label>
          <input id={`paid-${order.id}`} inputMode="numeric" value={form.paidNow} onChange={(e) => setForm({ ...form, paidNow: e.target.value })} />
        </>
      )}
      {later && (form.choice === 'part' || (form.choice === 'credit' && (order.delivery_fee_minor ?? 0) > 0) || (form.choice === 'account' && (feeOf(form.paidNow) ?? 0) > 0)) && (
        <>
          <label htmlFor={`paid-how-${order.id}`}>The money taken now was paid by</label>
          <select id={`paid-how-${order.id}`} value={form.paidNowMethod} onChange={(e) => setForm({ ...form, paidNowMethod: e.target.value as DeliverForm['paidNowMethod'] })}>
            <option value="cash">Cash</option>
            <option value="mobile_money">Mobile money</option>
            <option value="bank">Bank</option>
          </select>
        </>
      )}
      {later && (
        <>
          <label htmlFor={`due-${order.id}`}>The customer will pay the rest by (optional)</label>
          <input id={`due-${order.id}`} type="date" min={businessToday()} value={form.dueDate} onChange={(e) => setForm({ ...form, dueDate: e.target.value })} />
          <p className="hint">The rest goes on credit, like a credit sale: it shows in Credit sales until it is paid.</p>
        </>
      )}
      {fig && <p className="rt-total" aria-live="polite">Paid now {money(fig.now)}{fig.later > 0 ? `, on credit ${money(fig.later)}` : ''}</p>}
      <label htmlFor={`deliver-note-${order.id}`}>Note (optional)</label>
      <input id={`deliver-note-${order.id}`} value={form.note} maxLength={500} onChange={(e) => setForm({ ...form, note: e.target.value })} />
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      <Problem error={deliver.error} />
      <BusyButton busy={deliver.isPending} type="button" className="rt-primary" disabled={problem !== null} onClick={() => deliver.mutate()}>
        Save: delivered and paid
      </BusyButton>
    </fieldset>
  );
}

/** The buttons of an open order: packed, out for delivery (with who takes it), delivered, cancel (with why). */
export function OrderActions({ order, onDelivered }: { order: Order; onDelivered?: (d: Delivered) => void }) {
  const { me } = useStaff();
  const client = useQueryClient();
  const [panel, setPanel] = useState<'send' | 'deliver' | 'cancel' | null>(null);
  const [rider, setRider] = useState(order.delivered_by ?? '');
  const [reason, setReason] = useState('');
  const [done, setDone] = useState<Delivered | null>(null);
  const after = () => {
    setPanel(null);
    invalidate(client);
  };
  const pack = useMutation({ mutationFn: () => retailOrders.pack(order.id ?? ''), onSuccess: after });
  const send = useMutation({ mutationFn: () => retailOrders.send(order.id ?? '', rider.trim()), onSuccess: after });
  const cancel = useMutation({ mutationFn: () => retailOrders.cancel(order.id ?? '', reason.trim()), onSuccess: after });
  const steps = nextSteps(order);
  // Delivering records a sale, so it is offered to whoever may sell at the order's shop.
  const maySell = holdsAt(me, 'retail.sale.create', order.branch_id);
  if (done) {
    return (
      <div role="status" className="alert alert-success" data-testid="delivered">
        <p>Delivered. Sale {done.sale_no} made: paid now {money(done.paid_now_minor ?? 0)}{(done.owed_minor ?? 0) > 0 ? `, on credit ${money(done.owed_minor ?? 0)}` : ''}.</p>
      </div>
    );
  }
  if (steps.length === 0) return null;
  return (
    <>
      <div className="rt-row">
        {steps.includes('pack') && <BusyButton busy={pack.isPending} type="button" className="btn-sm" onClick={() => pack.mutate()}>Packed</BusyButton>}
        {steps.includes('send') && <button type="button" className="btn-sm" onClick={() => setPanel(panel === 'send' ? null : 'send')}>Out for delivery</button>}
        {steps.includes('deliver') && maySell && <button type="button" className="btn-sm" onClick={() => setPanel(panel === 'deliver' ? null : 'deliver')}>Delivered</button>}
        {steps.includes('cancel') && <button type="button" className="btn-sm" onClick={() => setPanel(panel === 'cancel' ? null : 'cancel')}>Cancel</button>}
      </div>
      {panel === 'send' && (
        <fieldset>
          <legend>Out for delivery</legend>
          <label htmlFor={`rider-${order.id}`}>Who takes it? (for example the rider&apos;s name)</label>
          <input id={`rider-${order.id}`} value={rider} maxLength={120} onChange={(e) => setRider(e.target.value)} />
          <BusyButton busy={send.isPending} type="button" disabled={rider.trim() === ''} onClick={() => send.mutate()}>Save: out for delivery</BusyButton>
        </fieldset>
      )}
      {panel === 'deliver' && <DeliverPanel order={order} onDone={(d) => { setDone(d); setPanel(null); onDelivered?.(d); }} />}
      {panel === 'cancel' && (
        <fieldset>
          <legend>Cancel this order</legend>
          <label htmlFor={`cancel-${order.id}`}>Why? (it stays on record, and the items go back on the shelf)</label>
          <input id={`cancel-${order.id}`} value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} />
          <BusyButton busy={cancel.isPending} type="button" disabled={reason.trim().length < 3} onClick={() => cancel.mutate()}>Cancel the order</BusyButton>
        </fieldset>
      )}
      <Problem error={pack.error ?? send.error ?? cancel.error} />
    </>
  );
}

/** One order: who, where, when, what, how much, and where it stands. */
export function OrderSummary({ order, branchName }: { order: Order; branchName?: string }) {
  const step = stepOf(order.status);
  return (
    <>
      <p>
        <strong>{order.order_no}</strong> <span className={`badge badge-${step.tone}`}>{step.label}</span> <span className="hint">from {sourceLabel(order.source)}</span>
      </p>
      <p>
        {order.customer_name}
        {order.customer_phone && <>, <a href={`tel:${order.customer_phone}`}>{order.customer_phone}</a></>}
      </p>
      <p className="hint">
        {order.delivery_place ? `Deliver to ${order.delivery_place}` : 'Collects at the shop'}
        {order.delivery_date ? `, on ${showDate(order.delivery_date)}` : ''}
        {order.delivered_by ? `, taken by ${order.delivered_by}` : ''}
        {branchName ? `, from ${branchName}` : ''}
      </p>
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {(order.lines ?? []).map((l) => (
          <li key={l.line_no} className="hint">{l.qty} {l.unit} {l.description} at {money(l.unit_price_minor ?? 0)} = {money(l.line_total_minor ?? 0)}</li>
        ))}
      </ul>
      <p className="hint">Items {money(order.items_total_minor ?? 0)}, delivery {money(order.delivery_fee_minor ?? 0)}, <strong>total {money(order.total_minor ?? 0)}</strong></p>
      {order.notes && <p className="hint">{order.notes}</p>}
      {order.status === 'delivered' && <p className="hint">Delivered {showDate(order.delivered_on)}, sale {order.sale_no}</p>}
      {order.sale_voided && <p className="hint">The sale was voided, and its delivery fee with it.</p>}
      {order.fee_method === 'account' && !order.sale_voided && <p className="hint">{order.fee_paid_on ? `Delivery fee paid on ${showDate(order.fee_paid_on)}` : 'Delivery fee on the customer account, not paid yet'}</p>}
      {order.status === 'cancelled' && <p className="hint">Cancelled because: {order.cancel_reason}</p>}
    </>
  );
}

function OrderCard({ order, branchName }: { order: Order; branchName?: string }) {
  return (
    <li className="rt-card" data-testid={`order-${order.id}`}>
      <OrderSummary order={order} branchName={branchName} />
      <p><Link className="btn-sm" to="/staff/retail/orders/$orderId" params={{ orderId: order.id ?? '' }}>Open the order and its history</Link></p>
      {isOpen(order) && <OrderActions order={order} />}
    </li>
  );
}

/** The quick entry of an order copied from a chat: the phone first, then the items, the place and the fee. */
export function OrderForm({ branchId, onSaved }: { branchId: string; onSaved: (o: Order) => void }) {
  const { me } = useStaff();
  const today = businessToday();
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<OrderDraft>(`order:${me.user_id ?? ''}:${branchId}`, blankOrder(branchId, today));
  const unitsOn = capabilityOn(me, 'retail', UNITS);
  const phoneKey = lookupKey(draft.phone);
  const found = useQuery({
    queryKey: ['retail', 'orders', 'lookup', phoneKey],
    queryFn: ({ signal }) => retailOrders.lookup(phoneKey ?? '', signal),
    enabled: phoneKey !== null,
  });
  const customers = useQuery({ queryKey: ['retail', 'customers'], queryFn: () => retail.listCustomers(), enabled: holdsAt(me, 'retail.sale.read', branchId) });
  const save = useMutation({
    mutationFn: () => retailOrders.create(buildOrderRequest(draft), key),
    onSuccess: (o) => {
      finish();
      onSaved(o);
    },
  });
  const update = (patch: Partial<OrderDraft>) => setDraft((d) => ({ ...d, ...patch }));
  const setLine = (i: number, patch: { qty?: string; price?: string }) => setDraft((d) => ({ ...d, lines: d.lines.map((l, n) => (n === i ? { ...l, ...patch } : l)) }));
  const add = (p: Product) => setDraft((d) => (d.lines.some((l) => l.product.id === p.id) ? d : { ...d, lines: [...d.lines, newLine(p)] }));
  const problem = orderProblem(draft, today);
  const hit = found.data?.found ? found.data : null;
  const items = itemsTotal(draft.lines);
  const fee = feeOf(draft.fee) ?? 0;
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (!problem && !save.isPending) save.mutate();
      }}
    >
      <fieldset data-tour="order-customer">
        <legend>Who ordered, and from where?</legend>
        <label htmlFor="order-phone">Phone number</label>
        <input id="order-phone" inputMode="tel" autoComplete="off" value={draft.phone} maxLength={100} onChange={(e) => update({ phone: e.target.value })} />
        {hit && (
          <div className="alert alert-info" data-testid="order-found">
            <p>{hit.customer_name} ordered before ({hit.orders} {hit.orders === 1 ? 'order' : 'orders'}){hit.delivery_place ? `, delivered to ${hit.delivery_place}` : ''}.</p>
            <button type="button" className="btn-sm" onClick={() => update({
              name: hit.customer_name ?? draft.name,
              customerId: hit.customer_id ?? '',
              place: hit.delivery_place ?? draft.place,
              fee: hit.delivery_fee_minor !== undefined ? String(hit.delivery_fee_minor) : draft.fee,
            })}>Use these details</button>
          </div>
        )}
        <label htmlFor="order-name">Name</label>
        <input id="order-name" value={draft.name} maxLength={200} onChange={(e) => update({ name: e.target.value })} />
        {(customers.data ?? []).length > 0 && (
          <>
            <label htmlFor="order-buyer">Credit buyer on the list (optional, needed to sell to them on credit when customer accounts are on)</label>
            <select id="order-buyer" value={draft.customerId} onChange={(e) => update({ customerId: e.target.value })}>
              <option value="">Not on the list</option>
              {(customers.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </>
        )}
        <p id="order-source-label">Where did the order come from?</p>
        <div className="rt-row" role="radiogroup" aria-labelledby="order-source-label" data-tour="order-source">
          {SOURCES.map((s) => (
            <label key={s.value} className="check">
              <input type="radio" name="order-source" checked={draft.source === s.value} onChange={() => update({ source: s.value as OrderSource })} /> {s.label}
            </label>
          ))}
        </div>
      </fieldset>

      <div data-tour="order-items">
        <ProductPicker id="order-search" branchId={branchId} showPrice onAdd={add} />
      </div>
      <h2>Items</h2>
      {draft.lines.length === 0 && <EmptyState art="noSales" title="No items yet.">Type an item above and tap it in the list.</EmptyState>}
      {draft.lines.map((l, i) => (
        <SaleLineCard
          key={`${l.product.id}-${i}`}
          line={l}
          index={i}
          unitsOn={unitsOn}
          onQty={(v) => setLine(i, { qty: v })}
          onPrice={(v) => setLine(i, { price: v })}
          onUnit={(unitId) => setDraft((d) => ({ ...d, lines: d.lines.map((x, n) => (n === i ? withUnit(x, unitId) : x)) }))}
          onRemove={() => setDraft((d) => ({ ...d, lines: d.lines.filter((_, n) => n !== i) }))}
        />
      ))}

      <fieldset data-tour="order-delivery">
        <legend>Delivery</legend>
        <label htmlFor="order-place">Deliver to (leave empty if the customer collects at the shop)</label>
        <input id="order-place" value={draft.place} maxLength={300} onChange={(e) => update({ place: e.target.value })} />
        <label htmlFor="order-date">Delivery day</label>
        <input id="order-date" type="date" min={today} value={draft.deliveryDate} onChange={(e) => update({ deliveryDate: e.target.value })} />
        <label htmlFor="order-fee">Delivery fee (paid when the goods arrive)</label>
        <input id="order-fee" inputMode="numeric" value={draft.fee} onChange={(e) => update({ fee: e.target.value })} />
        <label htmlFor="order-notes">Notes (optional, for example the colour or the time)</label>
        <input id="order-notes" value={draft.notes} maxLength={500} onChange={(e) => update({ notes: e.target.value })} />
      </fieldset>
      <p className="rt-total" aria-live="polite">Items {money(items)}, delivery {money(fee)}, total {money(items + fee)}</p>
      <p className="hint">Saving keeps these items aside, so they are not sold at the till. Nothing is paid until the order is delivered.</p>
      {problem && draft.lines.length > 0 && <Note>{problem}</Note>}
      <Problem error={save.error} />
      <BusyButton busy={save.isPending} type="submit" className="rt-primary" disabled={problem !== null} data-tour="order-save">
        Save the order
      </BusyButton>
      {draft.lines.length > 0 && !save.isPending && <button type="button" onClick={() => { discard(); save.reset(); }}>Clear this order</button>}
    </form>
  );
}

type View = 'open' | 'ordered' | 'packed' | 'out_for_delivery' | 'today' | 'overdue' | 'delivered' | 'cancelled' | 'all';

const VIEWS: { value: View; label: string }[] = [
  { value: 'open', label: 'All open orders' },
  { value: 'ordered', label: 'New (not packed)' },
  { value: 'packed', label: 'Packed' },
  { value: 'out_for_delivery', label: 'Out for delivery' },
  { value: 'today', label: 'To deliver today' },
  { value: 'overdue', label: 'Late' },
  { value: 'delivered', label: 'Delivered' },
  { value: 'cancelled', label: 'Cancelled' },
  { value: 'all', label: 'Every order' },
];

const queryOf = (view: View): Pick<OrderQuery, 'status' | 'due'> =>
  view === 'today' ? { due: 'today' } : view === 'overdue' ? { due: 'overdue' } : view === 'all' ? {} : { status: view };

export function OrdersPage() {
  const { all, branchId, branchName } = useBranchView();
  const nameOf = useBranchName();
  const single = useSingleBranch();
  const { me } = useStaff();
  const atStore = (me.branches ?? []).some((b) => b.id === single.branchId && b.sells_goods === false);
  const client = useQueryClient();
  const search = useLiveSearch();
  const [view, setView] = useState<View>('open');
  const [source, setSource] = useState<OrderSource | ''>('');
  const [making, setMaking] = useState(false);
  const [formKey, setFormKey] = useState(0);
  const [saved, setSaved] = useState<Order | null>(null);
  const scope = all ? undefined : (branchId ?? undefined);
  const board = useQuery({ queryKey: ['retail', 'orders', 'board', scope ?? 'all'], queryFn: ({ signal }) => retailOrders.board(scope, signal), enabled: all || branchId !== null });
  const list = useInfiniteQuery({
    queryKey: ['retail', 'orders', scope ?? 'all', view, source, search.term],
    queryFn: ({ pageParam, signal }) => retailOrders.list({ branchId: scope, ...queryOf(view), source: source || undefined, query: search.term, cursor: pageParam, signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor,
    enabled: all || branchId !== null,
    placeholderData: keepPreviousData,
  });
  const items = (list.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const fetching = list.isPlaceholderData || (list.isFetching && !list.isFetchingNextPage && list.fetchStatus !== 'idle');
  const b = board.data;
  const tiles: { view: View; label: string; count: number | undefined }[] = [
    { view: 'ordered', label: 'New', count: b?.ordered },
    { view: 'packed', label: 'Packed', count: b?.packed },
    { view: 'out_for_delivery', label: 'Out for delivery', count: b?.out_for_delivery },
    { view: 'today', label: 'To deliver today', count: b?.due_today },
    { view: 'overdue', label: 'Late', count: b?.overdue },
  ];
  return (
    <Gate screen="orders" title="Orders" wide>
      <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
      <p className="hint" data-tour="orders-help">Orders that come by WhatsApp, Instagram, Facebook, a phone call or at the counter. Saving an order keeps its items aside. The money is taken when it is delivered, and then it is a normal sale.</p>
      <div className="rt-row" data-tour="orders-board" aria-label="Orders at each step">
        {tiles.map((t) => (
          <button key={t.view} type="button" className={view === t.view ? 'btn-sm rt-primary' : 'btn-sm'} aria-pressed={view === t.view} onClick={() => setView(t.view)}>
            {t.label}: {t.count ?? '...'}
          </button>
        ))}
        {b && <span className="hint">Delivered today: {b.delivered_today}</span>}
      </div>
      {atStore && <p className="hint">{single.branchName} is a store, and a store does not sell. Choose a shop to take an order.</p>}
      {!making && !atStore && (
        <button type="button" className="rt-primary" disabled={single.branchId === null} data-tour="order-new" onClick={() => { setMaking(true); setSaved(null); }}>
          New order
        </button>
      )}
      {!making && !atStore && single.branchId === null && <p className="hint">Choose one shop at the top of the page to take an order.</p>}
      {saved && <p role="status" className="alert alert-success">Order {saved.order_no} saved for {saved.customer_name}. Its items are kept aside.</p>}
      {making && !atStore && single.branchId !== null && (
        <section aria-label="New order">
          <h2>New order for {single.branchName}</h2>
          <OrderForm
            key={formKey}
            branchId={single.branchId}
            onSaved={(o) => {
              setMaking(false);
              setSaved(o);
              setFormKey((k) => k + 1);
              invalidate(client);
            }}
          />
          <button type="button" onClick={() => setMaking(false)}>Close the form</button>
        </section>
      )}
      <div data-tour="orders-list">
        <SearchBox id="search-orders" label="Order number, name or phone" search={search} fetching={fetching} count={list.data ? items.length : undefined} more={list.hasNextPage} />
        <label htmlFor="orders-view">Show</label>
        <select id="orders-view" value={view} onChange={(e) => setView(e.target.value as View)}>
          {VIEWS.map((v) => <option key={v.value} value={v.value}>{v.label}</option>)}
        </select>
        <label htmlFor="orders-source">From</label>
        <select id="orders-source" value={source} onChange={(e) => setSource(e.target.value as OrderSource | '')}>
          <option value="">Every source</option>
          {SOURCES.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
        </select>
        {list.isPending && list.fetchStatus !== 'idle' && <p className="loading">Loading</p>}
        <Problem error={list.error ?? board.error} />
        {list.data && (
          <SearchResults fetching={fetching}>
            {items.length === 0 ? (
              search.term ? <NoResults term={search.term} onClear={search.clear} noun="orders" /> : <p className="empty-state">No orders here.</p>
            ) : (
              <ul style={{ listStyle: 'none', padding: 0 }}>
                {items.map((o) => <OrderCard key={o.id} order={o} branchName={all ? nameOf(o.branch_id) : undefined} />)}
              </ul>
            )}
          </SearchResults>
        )}
        {list.hasNextPage && (
          <button type="button" disabled={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
            {list.isFetchingNextPage ? 'Loading' : 'Show more'}
          </button>
        )}
      </div>
      <p className="rt-row" data-tour="orders-links">
        <ScreenLink screen="ordersReport" to="/staff/retail/orders-report">Delivery fees and cancellations</ScreenLink>
        <ScreenLink screen="stock" to="/staff/retail/stock">Stock</ScreenLink>
        <ScreenLink screen="creditSales" to="/staff/retail/credit-sales">Credit sales</ScreenLink>
      </p>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/orders')({ component: OrdersPage });
