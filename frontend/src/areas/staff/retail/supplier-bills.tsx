import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type SupplierBill } from '../../../api/retail';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useOptionalStaff, useStaff } from '../context';
import { canCredit } from './credits-state';
import { showQty } from './maths';
import { PAYABLE_MANAGE, canUse, holdsAt } from './permissions';
import { varianceWords } from './payables-state';
import { Facts, MoreButton, ReasonPanel, StatusBadge, SupplierOwed, useActionKeys, useCursorList } from './purchasing-ui';
import { showDate } from './transfers';
import { Gate, Problem, Success, money, useBranchName, useToast } from './ui';

// Supplier invoices (the purchasing capability, ADR-033, issue #198), which the supplier's paper calls a bill: the list, found by
// our number, the supplier's invoice number or the supplier's name, and one invoice with the goods it bills, what was paid and what
// is still owed. An invoice that has had a payment cannot be voided: reverse the payment first.

const BILL_STATUS: Record<string, string> = { posted: 'Standing', voided: 'Voided' };

export function BillRow({ row }: { row: SupplierBill }) {
  return (
    <li className="rt-card" data-testid={`bill-${row.bill_no}`}>
      <p>
        <strong>{row.bill_no}</strong> (invoice {row.supplier_ref}) <StatusBadge words={BILL_STATUS[row.status ?? ''] ?? (row.status ?? '')} warn={row.status === 'voided'} />
        {row.kind === 'landed_cost' && <> <StatusBadge words="Landed cost" /></>}
        <br />
        {row.supplier_name}, due {showDate(row.due_date)}, <strong>{money(row.total_minor ?? 0)}</strong>
      </p>
      <p className="hint">{row.status === 'voided' ? 'Voided.' : (row.open_minor ?? 0) > 0 ? `Still owed: ${money(row.open_minor ?? 0)}.` : 'Paid in full.'}</p>
      <p>
        <Link className="btn-sm" to="/staff/retail/supplier-bills/$billId" params={{ billId: row.id ?? '' }} aria-label={`Open supplier invoice ${row.bill_no}`}>Open</Link>
      </p>
    </li>
  );
}

export function SupplierBillsPage() {
  const search = useLiveSearch();
  const query = search.term;
  const [openOnly, setOpenOnly] = useState(false);
  const list = useCursorList(['retail', 'supplier-bills', query, openOnly], (cursor, signal) => retail.listSupplierBills({ query, openOnly, cursor, signal }));
  return (
    <Gate screen="supplierBills" title="Supplier invoices">
      <p className="hint" data-tour="bills-help">
        A supplier invoice says what a supplier wants to be paid for goods you received. Record it against the goods, and the page checks it against what you ordered and received.
      </p>
      <p className="cluster">
        <Link className="btn rt-primary" to="/staff/retail/supplier-bills/new" data-tour="bills-new">New supplier invoice</Link>
        <Link className="btn-sm" to="/staff/retail/supplier-payments">Supplier payments</Link>
      </p>
      {list.query.isPending && <p className="loading">Loading</p>}
      <Problem error={list.query.error} />
      <div data-tour="bills-search">
        <SearchBox id="search-bills" label="Search by our number, the supplier's invoice number or the supplier" search={search} fetching={list.finding} count={list.query.data ? list.rows.length : undefined} more={list.query.hasNextPage} />
        <label>
          <input type="checkbox" checked={openOnly} onChange={(e) => setOpenOnly(e.target.checked)} />
          <span>Only invoices that are still owed</span>
        </label>
      </div>
      {list.query.data && list.rows.length === 0 && (query ? <NoResults term={query} onClear={search.clear} noun="invoices" /> : <p className="empty-state">{openOnly ? 'Nothing is owed on any invoice.' : 'No supplier invoices yet.'}</p>)}
      <div data-tour="bills-list">
        <SearchResults fetching={list.finding}>
          <ul style={{ listStyle: 'none', padding: 0 }}>
            {list.rows.map((r) => <BillRow key={r.id} row={r} />)}
          </ul>
        </SearchResults>
      </div>
      <MoreButton list={list} noun="invoices" />
    </Gate>
  );
}

/** One invoice: facts, the goods it bills against what they were received at, and what is paid and owed. */
export function SupplierBillView({ bill, nameOf, landedCostId, payable }: { bill: SupplierBill; nameOf: (branchId: string | undefined) => string; landedCostId?: string; payable?: boolean }) {
  const landedCost = bill.kind === 'landed_cost';
  const session = useOptionalStaff();
  const openReceipts = session !== null && canUse(session.me, 'goodsReceived');
  return (
    <section aria-label="Supplier invoice" data-testid="supplier-bill" data-tour="bill-summary">
      <h2>{bill.bill_no} <StatusBadge words={BILL_STATUS[bill.status ?? ''] ?? (bill.status ?? '')} warn={bill.status === 'voided'} /></h2>
      <Facts
        rows={[
          ['Supplier', <SupplierOwed key="s" id={bill.supplier_id} name={bill.supplier_name} payable={payable} />],
          ['Supplier\'s invoice number', bill.supplier_ref ?? ''],
          ...(landedCost
            ? [['What it is', landedCostId
              ? <Link key="lc" to="/staff/retail/landed-costs/$landedCostId" params={{ landedCostId }}>The invoice of a landed cost</Link>
              : 'The invoice of a landed cost'] as [string, ReactNode]]
            : []),
          ['Branch', nameOf(bill.branch_id)],
          ['Invoice date', showDate(bill.bill_date)],
          ['Due on', showDate(bill.due_date)],
          ['Invoice total', money(bill.total_minor ?? 0)],
          ['Paid so far', money(bill.paid_minor ?? 0)],
          ['Taken off by credit notes', money(bill.credited_minor ?? 0)],
          ['Still owed', money(bill.open_minor ?? 0)],
          ...(landedCost
            ? []
            : [
              ['Goods were received at', money(bill.cleared_minor ?? 0)] as [string, string],
              ['The invoice compared with the goods', varianceWords(bill.variance_minor)] as [string, string],
            ]),
          ...(bill.variance_reason ? [['Why it differs', bill.variance_reason] as [string, string]] : []),
          ...(bill.note ? [['Note', bill.note] as [string, string]] : []),
          ...(bill.status === 'voided' ? [['Voided because', bill.void_reason ?? ''] as [string, string]] : []),
        ]}
      />
      <div className="table-wrap" tabIndex={0} data-tour="bill-lines">
        <table>
          <thead>
            <tr>
              <th>Item</th>
              <th>Delivery</th>
              <th className="num">Billed</th>
              <th className="num">Price of one</th>
              <th className="num">Invoice says</th>
              <th className="num">Received at</th>
              <th className="num">Difference</th>
            </tr>
          </thead>
          <tbody>
            {(bill.lines ?? []).map((l) => (
              <tr key={l.id}>
                <td>{l.description}</td>
                <td>{l.receipt_id && openReceipts ? <Link to="/staff/retail/goods-received/$receiptId" params={{ receiptId: l.receipt_id }}>{l.receipt_no}</Link> : l.receipt_no}</td>
                <td className="num">{showQty(l.unit_qty ?? l.qty ?? '0')} {l.unit}</td>
                <td className="num">{money(l.unit_price_minor ?? 0)}</td>
                <td className="num">{money(l.line_total_minor ?? 0)}</td>
                <td className="num">{money(l.cleared_minor ?? 0)}</td>
                <td className="num">{money(l.variance_minor ?? 0)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

const route = getRouteApi('/staff/retail/supplier-bills/$billId');

export function SupplierBillPage() {
  const { billId } = route.useParams();
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const nameOf = useBranchName();
  const keys = useActionKeys();
  const [message, setMessage] = useState<string | null>(null);
  const allowed = canUse(me, 'supplierBills');
  const bill = useQuery({ queryKey: ['retail', 'supplier-bill', billId], queryFn: () => retail.getSupplierBill(billId), enabled: allowed });
  useCrumbLabel(bill.data?.bill_no);
  // The invoice of a landed cost points back to it. The landed cost list has no filter by invoice, so the payee's own are searched.
  const isLanded = bill.data?.kind === 'landed_cost';
  const landed = useQuery({
    queryKey: ['retail', 'landed-of-bill', billId],
    queryFn: async () => (await retail.listLandedCosts({ supplierId: bill.data?.supplier_id })).items?.find((c) => c.bill_id === billId),
    enabled: allowed && isLanded && canUse(me, 'landedCosts'),
  });
  const voided = useMutation({
    mutationFn: (reason: string) => retail.voidSupplierBill(billId, reason, keys.keyFor(billId, reason)),
    onSuccess: (b) => {
      keys.done(billId);
      const text = `Voided ${b.bill_no ?? 'the invoice'}. The goods can be put on another invoice.`;
      setMessage(text);
      show(text, 'success');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  if (!allowed) {
    return (
      <main className="rt">
        <h1>Supplier invoice</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  const b = bill.data;
  return (
    <main className="rt rt-wide">
      <h1>{b?.bill_no ?? 'Supplier invoice'}</h1>
      {bill.isPending && <SkeletonList label="Loading the invoice" />}
      <Problem error={bill.error} />
      {message && <Success>{message}</Success>}
      {b && <SupplierBillView bill={b} nameOf={nameOf} landedCostId={landed.data?.id} payable={canUse(me, 'supplierPayments')} />}
      {b && b.status === 'posted' && holdsAt(me, PAYABLE_MANAGE, b.branch_id) && (
        <div className="stack">
          {(b.open_minor ?? 0) > 0 && (
            <p><Link className="btn-sm" to="/staff/retail/supplier-payments/new" search={{ supplier: b.supplier_id } as never} data-tour="bill-pay">Pay this supplier</Link></p>
          )}
          {canCredit(b) && (
            <p><Link className="btn-sm" to="/staff/retail/supplier-credits/new" search={{ bill: b.id } as never} data-tour="bill-credit">Credit note</Link></p>
          )}
          {isLanded && (
            <p className="hint" data-testid="landed-bill-note">
              This is the invoice of a landed cost, so it cannot be voided here.
              {landed.data?.id ? <> Void <Link to="/staff/retail/landed-costs/$landedCostId" params={{ landedCostId: landed.data.id }}>the landed cost</Link> instead.</> : ' Void the landed cost instead.'}
            </p>
          )}
          {!isLanded && (
            <ReasonPanel
              openLabel="Void this invoice"
              question="Why is it voided?"
              hint="This is refused once a payment has been made on it: reverse the payment first. It is also refused while a credit note stands against it. The goods it billed can then go on another invoice."
              confirmLabel="Void the invoice"
              busy={voided.isPending}
              error={voided.error}
              onSubmit={(reason) => voided.mutate(reason)}
            />
          )}
        </div>
      )}
      {toast}
    </main>
  );
}

export const Route = createLazyRoute('/staff/retail/supplier-bills')({ component: SupplierBillsPage });
export const DetailRoute = createLazyRoute('/staff/retail/supplier-bills/$billId')({ component: SupplierBillPage });
