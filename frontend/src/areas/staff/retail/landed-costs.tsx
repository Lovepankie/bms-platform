import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type LandedCost, type LandedCostLine } from '../../../api/retail';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useOptionalStaff, useStaff } from '../context';
import { LANDED_STATUS, costEffectWords, hasProfitFigures, kindWords, lineQtyWords, methodWords } from './landed-state';
import { PAYABLE_MANAGE, canUse, holdsAt } from './permissions';
import { Facts, MoreButton, ReasonPanel, StatusBadge, SupplierOwed, useActionKeys, useCursorList } from './purchasing-ui';
import { showDate } from './transfers';
import { Gate, Problem, money, useBranchName, useToast } from './ui';
import { NoAccess } from '../../../app/ui/EmptyState';
import { Hint } from '../../../app/ui/Hint';

// Landed costs (the purchasing capability, issue #199): the freight, duty, clearing and insurance of a delivery, owed to someone
// other than the supplier and spread over the goods received so each item's cost includes them. The list, found by our number, the
// payee's invoice number or the payee's name, and one landed cost with its charges and the share each line took. The payee's invoice
// is an ordinary supplier invoice, paid on the supplier payments pages. A landed cost is undone by voiding it, not by voiding that invoice.

/** What has been paid and what is still owed on the payee's invoice, in words. */
export function owedWords(cost: LandedCost): string {
  if (cost.status === 'voided') return 'Voided.';
  const owed = (cost.total_minor ?? 0) - (cost.paid_minor ?? 0);
  return owed > 0 ? `Paid ${money(cost.paid_minor ?? 0)}, still owed ${money(owed)}.` : 'Paid in full.';
}

export function LandedCostRow({ row }: { row: LandedCost }) {
  return (
    <li className="rt-card" data-testid={`landed-${row.landed_no}`}>
      <p>
        <strong>{row.landed_no}</strong> (invoice {row.supplier_ref}) <StatusBadge words={LANDED_STATUS[row.status ?? ''] ?? (row.status ?? '')} warn={row.status === 'voided'} />
        <br />
        {row.supplier_name}, due {showDate(row.due_date)}, <strong>{money(row.total_minor ?? 0)}</strong>
      </p>
      <p className="hint">{owedWords(row)}</p>
      <p>
        <Link className="btn-sm" to="/staff/retail/landed-costs/$landedCostId" params={{ landedCostId: row.id ?? '' }} aria-label={`Open landed cost ${row.landed_no}`}>Open</Link>
      </p>
    </li>
  );
}

export function LandedCostsPage() {
  const search = useLiveSearch();
  const query = search.term;
  const [status, setStatus] = useState('');
  const list = useCursorList(['retail', 'landed-costs', query, status], (cursor, signal) => retail.listLandedCosts({ query, status, cursor, signal }));
  return (
    <Gate screen="landedCosts" title="Landed costs">
      <Hint tour="landed-help">
        Freight, import duty, clearing and insurance are part of what goods really cost you. Record them here against the deliveries they belong to,
        and they are shared over the items, so each item&apos;s cost includes its share. The person you owe is paid like any supplier.
      </Hint>
      <p className="cluster">
        <Link className="btn rt-primary" to="/staff/retail/landed-costs/new" data-tour="landed-new">New landed cost</Link>
        <Link className="btn-sm" to="/staff/retail/goods-received">Goods received</Link>
        <Link className="btn-sm" to="/staff/retail/supplier-payments">Supplier payments</Link>
      </p>
      {list.query.isPending && <p className="loading">Loading</p>}
      <Problem error={list.query.error} />
      <SearchBox id="search-landed" label="Search by our number, the invoice number or who is owed" search={search} fetching={list.finding} count={list.query.data ? list.rows.length : undefined} more={list.query.hasNextPage} />
      <label htmlFor="landed-status">Show</label>
      <select id="landed-status" value={status} onChange={(e) => setStatus(e.target.value)}>
        <option value="">All landed costs</option>
        <option value="posted">Standing</option>
        <option value="voided">Voided</option>
      </select>
      {list.query.data && list.rows.length === 0 && (query ? <NoResults term={query} onClear={search.clear} noun="landed costs" /> : <p className="empty-state">No landed costs yet.</p>)}
      <div data-tour="landed-list">
        <SearchResults fetching={list.finding}>
          <ul style={{ listStyle: 'none', padding: 0 }}>
            {list.rows.map((r) => <LandedCostRow key={r.id} row={r} />)}
          </ul>
        </SearchResults>
      </div>
      <MoreButton list={list} noun="landed costs" />
    </Gate>
  );
}

/**
 * The shares of a landed cost, line by line: the item, the share it took and, for a person who may see profit, how much of the share
 * went into the value of the stock still on hand, how much was counted as a cost of what was already sold (or is still on the way), and what that
 * did to the cost of one. Used for a recorded landed cost and for the review before saving.
 */
export function AllocationTable({ lines, total }: { lines: LandedCostLine[]; total?: number }) {
  const session = useOptionalStaff();
  const openReceipts = session !== null && canUse(session.me, 'goodsReceived');
  const profit = hasProfitFigures(lines);
  // The share of each line is sent only with retail.profit.read in the line's branch; the total of the charges always is.
  const shares = lines.some((l) => l.allocated_minor !== undefined);
  const sum = total ?? lines.reduce((s, l) => s + (l.allocated_minor ?? 0), 0);
  return (
    <div className="table-wrap" tabIndex={0}>
      {!shares && <p className="hint" data-testid="landed-shares-hidden">Amounts are shown to people who can read costs.</p>}
      <table data-testid="landed-allocation">
        <thead>
          <tr>
            <th>Item</th>
            <th>Delivery</th>
            <th className="num">Received</th>
            {shares && <th className="num">Share of the charges</th>}
            {profit && <th className="num">Added to the stock you still have</th>}
            {profit && <th className="num">Counted against what is already sold</th>}
            {profit && <th>What happened to the cost</th>}
          </tr>
        </thead>
        <tbody>
          {lines.map((l, i) => (
            <tr key={l.receipt_line_id ?? i}>
              <td>{l.description}</td>
              <td>{l.receipt_id && openReceipts ? <Link to="/staff/retail/goods-received/$receiptId" params={{ receiptId: l.receipt_id }}>{l.receipt_no}</Link> : l.receipt_no}</td>
              <td className="num">{lineQtyWords(l.qty, '')}</td>
              {shares && <td className="num">{l.allocated_minor === undefined ? '' : money(l.allocated_minor)}</td>}
              {profit && <td className="num">{money(l.to_inventory_minor ?? 0)}</td>}
              {profit && <td className="num">{money(l.to_cogs_minor ?? 0)}</td>}
              {profit && <td>{costEffectWords(l)}</td>}
            </tr>
          ))}
        </tbody>
        <tfoot>
          {shares ? (
            <tr><td colSpan={3}><strong>Total of the charges</strong></td><td className="num"><strong>{money(sum)}</strong></td>{profit && <td colSpan={3} />}</tr>
          ) : (
            <tr><td colSpan={3}><strong>Total of the charges: {money(sum)}</strong></td></tr>
          )}
        </tfoot>
      </table>
    </div>
  );
}

/** One landed cost: facts, the charges, and how they were shared over the goods. */
export function LandedCostView({ cost, nameOf, payable }: { cost: LandedCost; nameOf: (branchId: string | undefined) => string; payable?: boolean }) {
  const owed = cost.status === 'voided' ? 0 : (cost.total_minor ?? 0) - (cost.paid_minor ?? 0);
  return (
    <section aria-label="Landed cost" data-testid="landed-cost" data-tour="landed-summary">
      <h2>{cost.landed_no} <StatusBadge words={LANDED_STATUS[cost.status ?? ''] ?? (cost.status ?? '')} warn={cost.status === 'voided'} /></h2>
      <Facts
        rows={[
          ['Owed to', <SupplierOwed key="s" id={cost.supplier_id} name={cost.supplier_name} payable={payable} />],
          ['Their invoice number', cost.supplier_ref ?? ''],
          ['Their invoice on our books', cost.bill_id ? <Link key="b" to="/staff/retail/supplier-bills/$billId" params={{ billId: cost.bill_id }}>{cost.bill_no}</Link> : ''],
          ['Branch', nameOf(cost.branch_id)],
          ['Date of the charges', showDate(cost.cost_date)],
          ['Due on', showDate(cost.due_date)],
          ['Shared', methodWords(cost.method)],
          ['Total', money(cost.total_minor ?? 0)],
          ['Paid so far', money(cost.paid_minor ?? 0)],
          ['Still owed', money(owed)],
          ...(cost.note ? [['Note', cost.note] as [string, string]] : []),
          ...(cost.status === 'voided' ? [['Voided because', cost.void_reason ?? ''] as [string, string]] : []),
        ]}
      />
      <h3>The charges</h3>
      <div className="table-wrap" tabIndex={0} data-tour="landed-charges-table">
        <table>
          <thead>
            <tr><th>Kind</th><th>What for</th><th className="num">Amount</th></tr>
          </thead>
          <tbody>
            {(cost.charges ?? []).map((c, i) => (
              <tr key={c.line_no ?? i}>
                <td>{kindWords(c.kind)}</td>
                <td>{c.description ?? ''}</td>
                <td className="num">{money(c.amount_minor ?? 0)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <h3>How it was shared over the goods</h3>
      <div data-tour="landed-shared">
        <AllocationTable lines={cost.lines ?? []} total={cost.total_minor} />
      </div>
    </section>
  );
}

const route = getRouteApi('/staff/retail/landed-costs/$landedCostId');

export function LandedCostPage() {
  const { landedCostId } = route.useParams();
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const nameOf = useBranchName();
  const keys = useActionKeys();
  const allowed = canUse(me, 'landedCosts');
  const cost = useQuery({ queryKey: ['retail', 'landed-cost', landedCostId], queryFn: () => retail.getLandedCost(landedCostId), enabled: allowed });
  useCrumbLabel(cost.data?.landed_no);
  const voided = useMutation({
    mutationFn: (reason: string) => retail.voidLandedCost(landedCostId, reason, keys.keyFor(landedCostId, reason)),
    onSuccess: (c) => {
      keys.done(landedCostId);
      show(`Voided ${c.landed_no ?? 'the landed cost'}. The charges no longer count towards the cost of the goods.`, 'success');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  if (!allowed) {
    return (
      <main className="rt">
        <h1>Landed cost</h1>
        <NoAccess />
      </main>
    );
  }
  const c = cost.data;
  return (
    <main className="rt rt-wide">
      <h1>{c?.landed_no ?? 'Landed cost'}</h1>
      {cost.isPending && <SkeletonList label="Loading the landed cost" />}
      <Problem error={cost.error} />
      {c && <LandedCostView cost={c} nameOf={nameOf} payable={canUse(me, 'supplierPayments')} />}
      {c && c.status === 'posted' && holdsAt(me, PAYABLE_MANAGE, c.branch_id) && (
        <div data-tour="landed-void">
        <ReasonPanel
          openLabel="Void this landed cost"
          question="Why is it voided?"
          hint="The payee's invoice is voided with it, and the charges come off the cost of the goods. It is refused if a payment was made on that invoice (reverse the payment first), if a credit note stands against it, or if the goods it was added to have moved on. The page then says which."
          confirmLabel="Void the landed cost"
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

export const Route = createLazyRoute('/staff/retail/landed-costs')({ component: LandedCostsPage });
export const DetailRoute = createLazyRoute('/staff/retail/landed-costs/$landedCostId')({ component: LandedCostPage });
