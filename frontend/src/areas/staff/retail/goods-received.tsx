import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type GoodsReceipt } from '../../../api/retail';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { showQty } from './maths';
import { PAYABLE_MANAGE, PURCHASE_CREATE, canUse, holdsAt } from './permissions';
import { RECEIPT_STATUS_WORDS, canVoidReceipt, inLineUnit } from './purchasing-state';
import { Facts, MoreButton, ReasonPanel, StatusBadge, SupplierOwed, useActionKeys, useCursorList } from './purchasing-ui';
import { showDate } from './transfers';
import { Gate, Problem, Success, money, useBranchName, useToast } from './ui';

// Goods received (the purchasing capability, ADR-033, issue #198): the list of deliveries and one delivery. A delivery can be voided
// with a reason, which takes the goods out of stock again. It is refused once the delivery is on a supplier invoice (void the
// invoice first) or once some of the goods have been sold, used or moved.

export function ReceiptRow({ row, branchName }: { row: GoodsReceipt; branchName?: string }) {
  return (
    <li className="rt-card" data-testid={`receipt-${row.receipt_no}`}>
      <p>
        <strong>{row.receipt_no}</strong> <StatusBadge words={RECEIPT_STATUS_WORDS[row.status ?? ''] ?? (row.status ?? '')} warn={row.status === 'voided'} />
        {row.held_for_costing && row.status === 'posted' && <> <StatusBadge words="Held, not costed" warn /></>}
        <br />
        {row.supplier_name}, {showDate(row.received_on)}, <strong>{money(row.total_minor ?? 0)}</strong>
      </p>
      <p className="hint">
        {row.order_no ? `Against order ${row.order_no}` : 'No order'}{row.delivery_ref ? `, delivery note ${row.delivery_ref}` : ''}{branchName ? `, into ${branchName}` : ''}.
      </p>
      <p>
        <Link className="btn-sm" to="/staff/retail/goods-received/$receiptId" params={{ receiptId: row.id ?? '' }} aria-label={`Open goods received ${row.receipt_no}`}>Open</Link>
      </p>
    </li>
  );
}

export function GoodsReceivedPage() {
  const search = useLiveSearch();
  const query = search.term;
  const nameOf = useBranchName();
  const [status, setStatus] = useState('');
  const list = useCursorList(['retail', 'goods-receipts', query, status], (cursor, signal) => retail.listGoodsReceipts({ query, status, cursor, signal }));
  return (
    <Gate screen="goodsReceived" title="Goods received">
      <p className="hint" data-tour="goods-received-help">
        Each delivery from a supplier is recorded here. Receive against an order from the order itself, or record a delivery that came with no order.
        A wrong delivery is undone by voiding it and receiving it again correctly. Goods sent back to a supplier are recorded on a supplier credit note.
        A delivery marked held is waiting for its freight and duty to be added as a landed cost.
      </p>
      <p className="cluster">
        <Link className="btn rt-primary" to="/staff/retail/goods-received/new" data-tour="receipts-new">Receive goods without an order</Link>
        <Link className="btn-sm" to="/staff/retail/purchase-orders">Purchase orders</Link>
      </p>
      {list.query.isPending && <p className="loading">Loading</p>}
      <Problem error={list.query.error} />
      <div data-tour="receipts-search">
        <SearchBox id="search-receipts" label="Search by receipt, order, delivery note or supplier" search={search} fetching={list.finding} count={list.query.data ? list.rows.length : undefined} more={list.query.hasNextPage} />
        <label htmlFor="receipts-status">Show</label>
        <select id="receipts-status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All deliveries</option>
          <option value="posted">Standing</option>
          <option value="voided">Voided</option>
        </select>
      </div>
      {list.query.data && list.rows.length === 0 && (query ? <NoResults term={query} onClear={search.clear} noun="deliveries" /> : <p className="empty-state">No goods have been received yet.</p>)}
      <div data-tour="receipts-list">
      <SearchResults fetching={list.finding}>
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {list.rows.map((r) => <ReceiptRow key={r.id} row={r} branchName={nameOf(r.branch_id)} />)}
        </ul>
      </SearchResults>
      </div>
      <MoreButton list={list} noun="deliveries" />
    </Gate>
  );
}

/** One delivery: facts, the lines with what was received and at what cost, and (when the server sent it) how much is already billed. */
export function GoodsReceiptView({ receipt, nameOf, payable }: { receipt: GoodsReceipt; nameOf: (branchId: string | undefined) => string; payable?: boolean }) {
  const lines = receipt.lines ?? [];
  const showBilled = lines.some((l) => l.billed_qty !== undefined);
  // The landed amount is sent only with profit access, and only for lines a landed cost has touched.
  const showLanded = lines.some((l) => l.landed_minor !== undefined);
  const held = receipt.held_for_costing === true && receipt.status === 'posted';
  return (
    <section aria-label="Goods received" data-testid="goods-receipt" data-tour="receipt-summary">
      <h2>
        {receipt.receipt_no} <StatusBadge words={RECEIPT_STATUS_WORDS[receipt.status ?? ''] ?? (receipt.status ?? '')} warn={receipt.status === 'voided'} />
        {held && <> <StatusBadge words="Held, not costed" warn /></>}
      </h2>
      {held && <p className="hint" data-testid="receipt-held">Held until the landed cost is in. While the business requires costing before sale, this quantity cannot be moved out of the branch. Other stock of the same item can still go.</p>}
      <Facts
        rows={[
          ['Supplier', <SupplierOwed key="s" id={receipt.supplier_id} name={receipt.supplier_name} payable={payable} />],
          ['Into', nameOf(receipt.branch_id)],
          ['Received on', showDate(receipt.received_on)],
          ...(receipt.order_no && receipt.order_id
            ? [['Order', <Link key="o" to="/staff/retail/purchase-orders/$orderId" params={{ orderId: receipt.order_id }}>{receipt.order_no}</Link>] as [string, ReactNode]]
            : [['Order', 'None, goods received without an order'] as [string, string]]),
          ...(receipt.delivery_ref ? [['Delivery note', receipt.delivery_ref] as [string, string]] : []),
          ...(receipt.note ? [['Note', receipt.note] as [string, string]] : []),
          ...(receipt.status === 'voided' ? [['Voided because', receipt.void_reason ?? ''] as [string, string]] : []),
        ]}
      />
      <div className="table-wrap" tabIndex={0} data-tour="receipt-lines">
        <table>
          <thead>
            <tr>
              <th>Item</th>
              <th className="num">Received</th>
              {showBilled && <th className="num">On invoices</th>}
              <th className="num">Cost of one</th>
              <th className="num">Total</th>
              {showLanded && <th className="num">Landed costs added</th>}
            </tr>
          </thead>
          <tbody>
            {lines.map((l) => (
              <tr key={l.id}>
                <td>{l.description}</td>
                <td className="num">{showQty(l.unit_qty ?? l.qty ?? '0')} {l.unit}</td>
                {showBilled && <td className="num">{inLineUnit(l.billed_qty, l)} {l.unit}</td>}
                <td className="num">{money(l.entered_cost_minor ?? 0)}</td>
                <td className="num">{money(l.line_total_minor ?? 0)}</td>
                {showLanded && <td className="num">{money(l.landed_minor ?? 0)}</td>}
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr><td colSpan={showBilled ? 4 : 3}><strong>Total received</strong></td><td className="num"><strong>{money(receipt.total_minor ?? 0)}</strong></td>{showLanded && <td />}</tr>
          </tfoot>
        </table>
      </div>
    </section>
  );
}

const route = getRouteApi('/staff/retail/goods-received/$receiptId');

export function GoodsReceiptPage() {
  const { receiptId } = route.useParams();
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const nameOf = useBranchName();
  const keys = useActionKeys();
  const [message, setMessage] = useState<string | null>(null);
  const allowed = canUse(me, 'goodsReceived');
  const receipt = useQuery({ queryKey: ['retail', 'goods-receipt', receiptId], queryFn: () => retail.getGoodsReceipt(receiptId), enabled: allowed });
  useCrumbLabel(receipt.data?.receipt_no);
  const voided = useMutation({
    mutationFn: (reason: string) => retail.voidGoodsReceipt(receiptId, reason, keys.keyFor(receiptId, reason)),
    onSuccess: (r) => {
      keys.done(receiptId);
      const text = `Voided ${r.receipt_no ?? 'the delivery'}. The goods are out of stock again.`;
      setMessage(text);
      show(text, 'success');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  // Releasing a hold is a different action from voiding, so it has keys of its own.
  const releaseKeys = useActionKeys();
  const released = useMutation({
    mutationFn: (reason: string) => retail.releaseReceiptHold(receiptId, reason, releaseKeys.keyFor(receiptId, reason)),
    onSuccess: (r) => {
      releaseKeys.done(receiptId);
      const text = `Released ${r.receipt_no ?? 'the delivery'}. It no longer waits for a landed cost.`;
      setMessage(text);
      show(text, 'success');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  if (!allowed) {
    return (
      <main className="rt">
        <h1>Goods received</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  const r = receipt.data;
  return (
    <main className="rt rt-wide">
      <h1>{r?.receipt_no ?? 'Goods received'}</h1>
      {receipt.isPending && <SkeletonList label="Loading the delivery" />}
      <Problem error={receipt.error} />
      {message && <Success>{message}</Success>}
      {r && <GoodsReceiptView receipt={r} nameOf={nameOf} payable={canUse(me, 'supplierPayments')} />}
      {r && r.status === 'posted' && canUse(me, 'landedCosts') && holdsAt(me, PAYABLE_MANAGE, r.branch_id) && (
        <p><Link className="btn-sm" to="/staff/retail/landed-costs/new" search={{ receipt: r.id } as never} data-tour="receipt-landed">Add landed cost</Link></p>
      )}
      {r && r.status === 'posted' && r.held_for_costing === true && holdsAt(me, PAYABLE_MANAGE, r.branch_id) && (
        <ReasonPanel
          fieldId="release-reason"
          openLabel="Release the hold"
          question="Why does this delivery need no landed cost?"
          hint="Use this when there is no freight or duty to add. Adding a landed cost to the delivery releases the hold by itself."
          confirmLabel="Release the hold"
          busy={released.isPending}
          error={released.error}
          onSubmit={(reason) => released.mutate(reason)}
        />
      )}
      {r && canVoidReceipt(r) && holdsAt(me, PURCHASE_CREATE, r.branch_id) && (
        <div data-tour="receipt-void">
        <ReasonPanel
          openLabel="Void this delivery"
          question="Why is it voided?"
          hint="This is how a wrong delivery is undone: void it, then receive it again correctly. Goods sent back to a supplier are recorded on a supplier credit note instead. The goods come out of stock again. This is refused if the delivery is already on a supplier invoice (void the invoice first), or if some of the goods have been sold, used or moved."
          confirmLabel="Void the delivery"
          busy={voided.isPending}
          error={voided.error}
          onSubmit={(reason) => voided.mutate(reason)}
        />
        </div>
      )}
      {toast}
    </main>
  );
}

export const Route = createLazyRoute('/staff/retail/goods-received')({ component: GoodsReceivedPage });
export const DetailRoute = createLazyRoute('/staff/retail/goods-received/$receiptId')({ component: GoodsReceiptPage });
