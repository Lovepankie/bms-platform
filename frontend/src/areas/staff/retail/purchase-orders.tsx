import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type PurchaseOrder } from '../../../api/retail';
import { orderStatusWords } from './purchasing-state';
import { MoreButton, StatusBadge, useCursorList } from './purchasing-ui';
import { showDate } from './transfers';
import { Gate, Problem, money } from './ui';
import { WORDS } from '../../../app/glossary';

// Purchase orders (the purchasing capability, ADR-033, issue #198): the orders to suppliers, newest first, found by their number
// or the supplier's name. Restock is still there for buying without an order. The search is the shared live search: the term is
// never put in the address.

const STATUSES: { value: string; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'draft', label: 'Draft' },
  { value: 'sent', label: 'Sent' },
  { value: 'partly_received', label: 'Partly received' },
  { value: 'received', label: 'Received' },
  { value: 'cancelled', label: 'Cancelled' },
];

/** One order: its number, status, supplier, date, value and a way in. */
export function OrderRow({ row }: { row: PurchaseOrder }) {
  return (
    <li className="rt-card" data-testid={`order-${row.order_no}`}>
      <p>
        <strong>{row.order_no}</strong> <StatusBadge words={orderStatusWords(row.status)} warn={row.status === 'cancelled'} />
        <br />
        {row.supplier_name}, {showDate(row.ordered_on)}, <strong>{money(row.total_minor ?? 0)}</strong>
      </p>
      <p className="hint">
        {row.line_count ?? 0} {row.line_count === 1 ? 'item' : 'items'}{row.expected_on ? `, expected ${showDate(row.expected_on)}` : ''}.
      </p>
      <p>
        <Link className="btn-sm" to="/staff/retail/purchase-orders/$orderId" params={{ orderId: row.id ?? '' }} aria-label={`Open order ${row.order_no}`}>Open</Link>
      </p>
    </li>
  );
}

export function PurchaseOrdersPage() {
  const search = useLiveSearch();
  const query = search.term;
  const [status, setStatus] = useState('');
  const list = useCursorList(['retail', 'purchase-orders', query, status], (cursor, signal) => retail.listPurchaseOrders({ query, status, cursor, signal }));
  return (
    <Gate screen="purchaseOrders" title="Purchase orders">
      <p className="hint" data-tour="purchase-orders-help">
        An order tells a supplier what you want and at what cost. When the goods arrive, open the order and choose Receive goods. Buying without an order is still done in{' '}
        <Link to="/staff/retail/restock">{WORDS.buyStock}</Link>, or with Goods received.
      </p>
      <p className="cluster">
        <Link className="btn rt-primary" to="/staff/retail/purchase-orders/new" data-tour="purchase-orders-new">New purchase order</Link>
        <Link className="btn-sm" to="/staff/retail/goods-received">Goods received</Link>
      </p>
      {list.query.isPending && <p className="loading">Loading</p>}
      <Problem error={list.query.error} />
      <SearchBox id="search-orders" label="Search by order number or supplier" search={search} fetching={list.finding} count={list.query.data ? list.rows.length : undefined} more={list.query.hasNextPage} />
      <fieldset>
        <legend>Show</legend>
        <p className="cluster">
          {STATUSES.map((s) => (
            <button key={s.value} type="button" className={status === s.value ? 'btn-sm rt-primary' : 'btn-sm'} aria-pressed={status === s.value} onClick={() => setStatus(s.value)}>{s.label}</button>
          ))}
        </p>
      </fieldset>
      {list.query.data && list.rows.length === 0 && (query ? <NoResults term={query} onClear={search.clear} noun="orders" /> : <p className="empty-state">No purchase orders yet.</p>)}
      <SearchResults fetching={list.finding}>
        <ul style={{ listStyle: 'none', padding: 0 }} data-tour="purchase-orders-list">
          {list.rows.map((r) => <OrderRow key={r.id} row={r} />)}
        </ul>
      </SearchResults>
      <MoreButton list={list} noun="orders" />
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/purchase-orders')({ component: PurchaseOrdersPage });
