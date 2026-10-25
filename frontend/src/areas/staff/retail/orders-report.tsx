import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, daysBefore } from '../../../api/retail';
import { retailOrders } from '../../../api/retail-orders';
import { showDate } from './maths';
import { sourceLabel } from './orders-state';
import { Gate, Problem, money, useBranchName, useBranchView } from './ui';
import { PeriodPicker } from '../../../app/ui/PeriodPicker';
import { RowChevron } from '../../../app/ui/ListRow';

// The reports of customer orders (issue #385, ADR-052): delivery fees taken on the orders delivered, by day, by where the orders
// came from or by who delivered them, and the orders cancelled with their reasons. The sales themselves are in the sales reports.

type GroupBy = 'day' | 'source' | 'rider';

export function OrdersReportPage() {
  const { all, branchId } = useBranchView();
  const nameOf = useBranchName();
  const today = businessToday();
  const [from, setFrom] = useState(daysBefore(today, 29));
  const [to, setTo] = useState(today);
  const [groupBy, setGroupBy] = useState<GroupBy>('day');
  const scope = all ? undefined : (branchId ?? undefined);
  const valid = from !== '' && to !== '' && from <= to;
  const fees = useQuery({
    queryKey: ['retail', 'orders', 'fees', scope ?? 'all', from, to, groupBy],
    queryFn: ({ signal }) => retailOrders.fees({ branchId: scope, from, to, groupBy }, signal),
    enabled: valid && (all || branchId !== null),
    placeholderData: keepPreviousData,
  });
  const cancelled = useQuery({
    queryKey: ['retail', 'orders', 'cancellations', scope ?? 'all', from, to],
    queryFn: ({ signal }) => retailOrders.cancellations({ branchId: scope, from, to }, signal),
    enabled: valid && (all || branchId !== null),
    placeholderData: keepPreviousData,
  });
  return (
    <Gate screen="ordersReport" title="Delivery fees and cancellations" wide>
      <div className="link-rows" data-tour="orders-report-links">
        <Link className="link-row" to="/staff/retail/orders">Orders<RowChevron /></Link>
        <Link className="link-row" to="/staff/retail/sales">All sales<RowChevron /></Link>
      </div>
      <PeriodPicker from={from} to={to} label="Which days?" tour="orders-report-range" onChange={(range) => { setFrom(range.from); setTo(range.to); }} />

      <section aria-label="Delivery fees" data-tour="orders-report-fees">
        <h2>Delivery fees taken</h2>
        <label htmlFor="orders-group">Group by</label>
        <select id="orders-group" value={groupBy} onChange={(e) => setGroupBy(e.target.value as GroupBy)}>
          <option value="day">Day</option>
          <option value="source">Where the order came from</option>
          <option value="rider">Who delivered</option>
        </select>
        <Problem error={fees.error} />
        {fees.data && (
          <>
            <p className="rt-total">{fees.data.orders} delivered, items {money(fees.data.items_minor ?? 0)}, delivery fees {money(fees.data.fees_minor ?? 0)}</p>
            {(fees.data.rows ?? []).length === 0 ? <p className="empty-state">No orders delivered on these days.</p> : (
              <div className="table-wrap"><table>
                <thead><tr><th>{groupBy === 'day' ? 'Day' : groupBy === 'source' ? 'From' : 'Delivered by'}</th><th>Orders</th><th>Items</th><th>Delivery fees</th></tr></thead>
                <tbody>
                  {(fees.data.rows ?? []).map((r) => (
                    <tr key={r.key}><td>{groupBy === 'day' ? showDate(r.label) : r.label}</td><td>{r.orders}</td><td>{money(r.items_minor ?? 0)}</td><td>{money(r.fees_minor ?? 0)}</td></tr>
                  ))}
                </tbody>
              </table></div>
            )}
          </>
        )}
      </section>

      <section aria-label="Cancelled orders" data-tour="orders-report-cancelled">
        <h2>Orders cancelled, and why</h2>
        <Problem error={cancelled.error} />
        {cancelled.data && (
          <>
            <p className="rt-total">{cancelled.data.orders} cancelled, worth {money(cancelled.data.items_minor ?? 0)}</p>
            {(cancelled.data.by_source ?? []).length > 0 && (
              <p className="hint">{(cancelled.data.by_source ?? []).map((r) => `${r.label}: ${r.orders}`).join(', ')}</p>
            )}
            {(cancelled.data.items ?? []).length === 0 ? <p className="empty-state">No orders cancelled on these days.</p> : (
              <ul style={{ listStyle: 'none', padding: 0 }}>
                {(cancelled.data.items ?? []).map((c) => (
                  <li key={c.id} className="rt-card">
                    <Link to="/staff/retail/orders/$orderId" params={{ orderId: c.id ?? '' }}>{c.order_no}</Link>, {showDate(c.cancelled_on)}, {c.customer_name}, {money(c.items_total_minor ?? 0)}, from {sourceLabel(c.source)}{all ? `, ${nameOf(c.branch_id)}` : ''}
                    <p className="hint">Why: {c.reason}</p>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </section>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/orders-report')({ component: OrdersReportPage });
