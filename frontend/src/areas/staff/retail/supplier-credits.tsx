import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type SupplierCredit } from '../../../api/retail';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { CREDIT_STATUS, creditKindWords } from './credits-state';
import { costEffectWords } from './landed-state';
import { showQty } from './maths';
import { PAYABLE_MANAGE, canUse, holdsAt } from './permissions';
import { Facts, MoreButton, ReasonPanel, StatusBadge, SupplierOwed, useActionKeys, useCursorList } from './purchasing-ui';
import { showDate } from './transfers';
import { Gate, Problem, Success, money, useBranchName, useToast } from './ui';
import { NoAccess } from '../../../app/ui/EmptyState';

// Supplier credit notes (the purchasing capability, issue #199): goods sent back to a supplier, or money the supplier takes off an
// invoice. The list, found by our number, the supplier's credit note number or the supplier, and one credit note with its lines. A
// credit note is against one supplier invoice that stands, and what it takes comes off what that invoice leaves to pay.

export function CreditRow({ row }: { row: SupplierCredit }) {
  return (
    <li className="rt-card" data-testid={`credit-${row.credit_no}`}>
      <p>
        <strong>{row.credit_no}</strong> (credit note {row.supplier_ref}) <StatusBadge words={CREDIT_STATUS[row.status ?? ''] ?? (row.status ?? '')} warn={row.status === 'voided'} />
        <br />
        {row.supplier_name}, {showDate(row.credit_date)}, <strong>{money(row.total_minor ?? 0)}</strong>
      </p>
      <p className="hint">{creditKindWords(row.kind)}, against invoice {row.bill_no}.</p>
      <p>
        <Link className="btn-sm" to="/staff/retail/supplier-credits/$creditId" params={{ creditId: row.id ?? '' }} aria-label={`Open supplier credit note ${row.credit_no}`}>Open</Link>
      </p>
    </li>
  );
}

export function SupplierCreditsPage() {
  const search = useLiveSearch();
  const query = search.term;
  const [status, setStatus] = useState('');
  const list = useCursorList(['retail', 'supplier-credits', query, status], (cursor, signal) => retail.listSupplierCredits({ query, status, cursor, signal }));
  return (
    <Gate screen="supplierCredits" title="Supplier credit notes">
      <p className="hint" data-tour="credits-help">
        A credit note is what a supplier gives you when you send goods back, or when an invoice charged too much. It is recorded against the supplier invoice,
        and what it takes comes off what that invoice leaves you to pay. Start from the invoice itself, or from the button below.
      </p>
      <p className="cluster">
        <Link className="btn rt-primary" to="/staff/retail/supplier-credits/new" data-tour="credit-new">New credit note</Link>
        <Link className="btn-sm" to="/staff/retail/supplier-bills">Supplier invoices</Link>
      </p>
      {list.query.isPending && <p className="loading">Loading</p>}
      <Problem error={list.query.error} />
      <div data-tour="credits-search">
        <SearchBox id="search-credits" label="Search by our number, the credit note number or the supplier" search={search} fetching={list.finding} count={list.query.data ? list.rows.length : undefined} more={list.query.hasNextPage} />
        <label htmlFor="credits-status">Show</label>
        <select id="credits-status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All credit notes</option>
          <option value="posted">Standing</option>
          <option value="voided">Voided</option>
        </select>
      </div>
      {list.query.data && list.rows.length === 0 && (query ? <NoResults term={query} onClear={search.clear} noun="credit notes" /> : <p className="empty-state">No supplier credit notes yet.</p>)}
      <div data-tour="credits-list">
        <SearchResults fetching={list.finding}>
          <ul style={{ listStyle: 'none', padding: 0 }}>
            {list.rows.map((r) => <CreditRow key={r.id} row={r} />)}
          </ul>
        </SearchResults>
      </div>
      <MoreButton list={list} noun="credit notes" />
    </Gate>
  );
}

/** One credit note: facts and its lines. A goods credit shows what was sent back; a price credit shows the money taken off. */
export function SupplierCreditView({ credit, nameOf, payable }: { credit: SupplierCredit; nameOf: (branchId: string | undefined) => string; payable?: boolean }) {
  const lines = credit.lines ?? [];
  const goods = credit.kind === 'goods';
  const showCost = lines.some((l) => l.cost_before_minor !== undefined);
  return (
    <section aria-label="Supplier credit note" data-testid="supplier-credit" data-tour="credit-summary">
      <h2>{credit.credit_no} <StatusBadge words={CREDIT_STATUS[credit.status ?? ''] ?? (credit.status ?? '')} warn={credit.status === 'voided'} /></h2>
      <Facts
        rows={[
          ['Supplier', <SupplierOwed key="s" id={credit.supplier_id} name={credit.supplier_name} payable={payable} />],
          ['Their credit note number', credit.supplier_ref ?? ''],
          ['Against invoice', credit.bill_id ? <Link key="b" to="/staff/retail/supplier-bills/$billId" params={{ billId: credit.bill_id }}>{credit.bill_no}</Link> : ''],
          ['What it is', creditKindWords(credit.kind)],
          ['Branch', nameOf(credit.branch_id)],
          ['Date', showDate(credit.credit_date)],
          ['Credit total', money(credit.total_minor ?? 0)],
          ['Why', credit.reason ?? ''],
          ...(credit.note ? [['Note', credit.note] as [string, string]] : []),
          ...(credit.status === 'voided' ? [['Voided because', credit.void_reason ?? ''] as [string, string]] : []),
        ]}
      />
      <div className="table-wrap" tabIndex={0} data-tour="credit-detail-lines">
        <table>
          <thead>
            <tr>
              <th>Item</th>
              <th>Delivery</th>
              {goods && <th className="num">Sent back</th>}
              {goods && <th className="num">Credit for one</th>}
              <th className="num">Credit</th>
              {showCost && <th>What it did to the cost</th>}
            </tr>
          </thead>
          <tbody>
            {lines.map((l, i) => (
              <tr key={l.id ?? i}>
                <td>{l.description}</td>
                <td>{l.receipt_no}</td>
                {goods && <td className="num">{showQty(l.unit_qty ?? l.qty ?? '0')} {l.unit}</td>}
                {goods && <td className="num">{money(l.unit_credit_minor ?? 0)}</td>}
                <td className="num">{money(l.line_total_minor ?? 0)}</td>
                {showCost && <td>{costEffectWords(l)}</td>}
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr><td colSpan={goods ? 4 : 2}><strong>Total</strong></td><td className="num"><strong>{money(credit.total_minor ?? 0)}</strong></td>{showCost && <td />}</tr>
          </tfoot>
        </table>
      </div>
    </section>
  );
}

const route = getRouteApi('/staff/retail/supplier-credits/$creditId');

export function SupplierCreditPage() {
  const { creditId } = route.useParams();
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const nameOf = useBranchName();
  const keys = useActionKeys();
  const [message, setMessage] = useState<string | null>(null);
  const allowed = canUse(me, 'supplierCredits');
  const credit = useQuery({ queryKey: ['retail', 'supplier-credit', creditId], queryFn: () => retail.getSupplierCredit(creditId), enabled: allowed });
  useCrumbLabel(credit.data?.credit_no);
  const voided = useMutation({
    mutationFn: (reason: string) => retail.voidSupplierCredit(creditId, reason, keys.keyFor(creditId, reason)),
    onSuccess: (c) => {
      keys.done(creditId);
      const text = `Voided ${c.credit_no ?? 'the credit note'}. The invoice is owed for what the credit note took off.`;
      setMessage(text);
      show(text, 'success');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  if (!allowed) {
    return (
      <main className="rt">
        <h1>Supplier credit note</h1>
        <NoAccess />
      </main>
    );
  }
  const c = credit.data;
  return (
    <main className="rt rt-wide">
      <h1>{c?.credit_no ?? 'Supplier credit note'}</h1>
      {credit.isPending && <SkeletonList label="Loading the credit note" />}
      <Problem error={credit.error} />
      {message && <Success>{message}</Success>}
      {c && <SupplierCreditView credit={c} nameOf={nameOf} payable={canUse(me, 'supplierPayments')} />}
      {c && c.status === 'posted' && holdsAt(me, PAYABLE_MANAGE, c.branch_id) && (
        <div data-tour="credit-void">
        <ReasonPanel
          openLabel="Void this credit note"
          question="Why is it voided?"
          hint="Goods it sent back come into stock again, and the invoice is owed for what the credit note took off. The page says if it cannot be undone."
          confirmLabel="Void the credit note"
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

export const Route = createLazyRoute('/staff/retail/supplier-credits')({ component: SupplierCreditsPage });
export const DetailRoute = createLazyRoute('/staff/retail/supplier-credits/$creditId')({ component: SupplierCreditPage });
