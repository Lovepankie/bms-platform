import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type SupplierPayment } from '../../../api/retail';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { PAYABLE_MANAGE, canUse, holdsAt } from './permissions';
import { methodWords } from './payables-state';
import { Facts, MoreButton, ReasonPanel, StatusBadge, SupplierOwed, useActionKeys, useCursorList } from './purchasing-ui';
import { showDate } from './transfers';
import { WORDS } from '../../../app/glossary';
import { Gate, Problem, Success, Money, useBranchName, useToast } from './ui';
import { NoAccess } from '../../../app/ui/EmptyState';
import { Hint } from '../../../app/ui/Hint';

// Supplier payments (the purchasing capability, ADR-033, issue #198): the money paid to suppliers, newest first, and one payment with
// what it cleared. A payment pays supplier invoices and credit restocks (stock bought on credit in Restock), oldest first or as
// chosen. A payment is undone as a whole by reversing it, with a reason.

const PAYMENT_STATUS: Record<string, string> = { posted: 'Paid', reversed: 'Reversed' };

export function PaymentRow({ row, branchName }: { row: SupplierPayment; branchName?: string }) {
  return (
    <li className="rt-card" data-testid={`payment-${row.payment_no}`}>
      <p>
        <strong>{row.payment_no}</strong> <StatusBadge words={PAYMENT_STATUS[row.status ?? ''] ?? (row.status ?? '')} warn={row.status === 'reversed'} />
        <br />
        {row.supplier_name}, {showDate(row.paid_on)}, <strong><Money minor={row.amount_minor ?? 0} /></strong>
      </p>
      <p className="hint">
        By {methodWords(row.method).toLowerCase()}{row.reference ? `, reference ${row.reference}` : ''}{branchName ? `, from ${branchName}` : ''}.
      </p>
      <p>
        <Link className="btn-sm" to="/staff/retail/supplier-payments/$paymentId" params={{ paymentId: row.id ?? '' }} aria-label={`Open supplier payment ${row.payment_no}`}>Open</Link>
      </p>
    </li>
  );
}

export function SupplierPaymentsPage() {
  const search = useLiveSearch();
  const query = search.term;
  const nameOf = useBranchName();
  const [status, setStatus] = useState('');
  const list = useCursorList(['retail', 'supplier-payments', query, status], (cursor, signal) => retail.listSupplierPayments({ query, status, cursor, signal }));
  return (
    <Gate screen="supplierPayments" title="Supplier payments">
      <Hint tour="payments-help">
        Money paid to suppliers. A payment clears the oldest supplier invoices and stock bought on credit first, unless you choose what it pays.
      </Hint>
      <p className="cluster">
        <Link className="btn rt-primary" to="/staff/retail/supplier-payments/new" data-tour="payments-new">Pay a supplier</Link>
        <Link className="btn-sm" to="/staff/retail/supplier-payables">What you owe suppliers</Link>
      </p>
      {list.query.isPending && <p className="loading">Loading</p>}
      <Problem error={list.query.error} />
      <div data-tour="payments-search">
        <SearchBox id="search-payments" label="Search by payment number, reference or supplier" search={search} fetching={list.finding} count={list.query.data ? list.rows.length : undefined} more={list.query.hasNextPage} />
        <label htmlFor="payments-status">Show</label>
        <select id="payments-status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All payments</option>
          <option value="posted">Paid</option>
          <option value="reversed">Reversed</option>
        </select>
      </div>
      {list.query.data && list.rows.length === 0 && (query ? <NoResults term={query} onClear={search.clear} noun="payments" /> : <p className="empty-state">No payments to suppliers yet.</p>)}
      <div data-tour="payments-list">
        <SearchResults fetching={list.finding}>
          <ul style={{ listStyle: 'none', padding: 0 }}>
            {list.rows.map((r) => <PaymentRow key={r.id} row={r} branchName={nameOf(r.branch_id)} />)}
          </ul>
        </SearchResults>
      </div>
      <MoreButton list={list} noun="payments" />
    </Gate>
  );
}

/** One payment: facts and what it cleared, invoice by invoice. */
export function SupplierPaymentView({ payment, nameOf, payable }: { payment: SupplierPayment; nameOf: (branchId: string | undefined) => string; payable?: boolean }) {
  return (
    <section aria-label="Supplier payment" data-testid="supplier-payment" data-tour="payment-summary">
      <h2>{payment.payment_no} <StatusBadge words={PAYMENT_STATUS[payment.status ?? ''] ?? (payment.status ?? '')} warn={payment.status === 'reversed'} /></h2>
      <Facts
        rows={[
          ['Supplier', <SupplierOwed key="s" id={payment.supplier_id} name={payment.supplier_name} payable={payable} />],
          ['Paid on', showDate(payment.paid_on)],
          ['Amount', <Money key="a" minor={payment.amount_minor ?? 0} />],
          [WORDS.paidHow, methodWords(payment.method)],
          ['From the till or bank of', nameOf(payment.branch_id)],
          ['What it paid', payment.allocation === 'manual' ? 'What was chosen' : 'The oldest first'],
          ...(payment.reference ? [['Reference', payment.reference] as [string, string]] : []),
          ...(payment.note ? [['Note', payment.note] as [string, string]] : []),
          ...(payment.status === 'reversed' ? [['Reversed because', payment.reverse_reason ?? ''] as [string, string]] : []),
        ]}
      />
      <div className="table-wrap" tabIndex={0} data-tour="payment-allocations">
        <table>
          <thead>
            <tr><th>Paid</th><th>Supplier&apos;s invoice</th><th className="num">Amount</th></tr>
          </thead>
          <tbody>
            {(payment.allocations ?? []).map((a, i) => (
              <tr key={a.bill_id ?? a.purchase_id ?? i}>
                <td>
                  {a.bill_id ? (
                    <Link to="/staff/retail/supplier-bills/$billId" params={{ billId: a.bill_id }}>{a.bill_no}</Link>
                  ) : (
                    <>Stock bought on credit {a.purchase_no}</>
                  )}
                </td>
                <td>{a.supplier_ref ?? ''}</td>
                <td className="num"><Money minor={a.amount_minor ?? 0} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

const route = getRouteApi('/staff/retail/supplier-payments/$paymentId');

export function SupplierPaymentPage() {
  const { paymentId } = route.useParams();
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const nameOf = useBranchName();
  const keys = useActionKeys();
  const [message, setMessage] = useState<string | null>(null);
  const allowed = canUse(me, 'supplierPayments');
  const payment = useQuery({ queryKey: ['retail', 'supplier-payment', paymentId], queryFn: () => retail.getSupplierPayment(paymentId), enabled: allowed });
  useCrumbLabel(payment.data?.payment_no);
  const reversed = useMutation({
    mutationFn: (reason: string) => retail.reverseSupplierPayment(paymentId, reason, keys.keyFor(paymentId, reason)),
    onSuccess: (p) => {
      keys.done(paymentId);
      const text = `Reversed ${p.payment_no ?? 'the payment'}. What it paid is owed again.`;
      setMessage(text);
      show(text, 'success');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  if (!allowed) {
    return (
      <main className="rt">
        <h1>Supplier payment</h1>
        <NoAccess />
      </main>
    );
  }
  const p = payment.data;
  return (
    <main className="rt rt-wide">
      <h1>{p?.payment_no ?? 'Supplier payment'}</h1>
      {payment.isPending && <SkeletonList label="Loading the payment" />}
      <Problem error={payment.error} />
      {message && <Success>{message}</Success>}
      {p && <SupplierPaymentView payment={p} nameOf={nameOf} payable />}
      {p && p.status === 'posted' && holdsAt(me, PAYABLE_MANAGE, p.branch_id) && (
        <div data-tour="payment-reverse">
        <ReasonPanel
          openLabel="Reverse this payment"
          question="Why is it reversed?"
          hint="The whole payment is undone: the money goes back to the till or bank, and the invoices it paid are owed again."
          confirmLabel="Reverse the payment"
          busy={reversed.isPending}
          error={reversed.error}
          onSubmit={(reason) => reversed.mutate(reason)}
        />
        </div>
      )}
      {toast}
    </main>
  );
}

export const Route = createLazyRoute('/staff/retail/supplier-payments')({ component: SupplierPaymentsPage });
export const DetailRoute = createLazyRoute('/staff/retail/supplier-payments/$paymentId')({ component: SupplierPaymentPage });
