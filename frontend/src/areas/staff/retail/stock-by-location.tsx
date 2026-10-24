import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type LocationBranch, type LocationRow, type StockByLocation } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { EmptyState, SkeletonList } from '../../../components/states';
import { showQty } from './maths';
import { CategoryLabel, Gate, Problem, money, useIsPhone, useProfitAccess } from './ui';

// Stock by store and shop (ADR-033, capability branch_roles): every product's balance in each branch
// the user may read, the stores first and then the shops, a subtotal per role, the stock on its way
// (in neither branch's count, so the combined total neither counts it twice nor loses it) and the
// combined total. Value at cost appears only with retail.profit.read in every branch of the report.

const ROLE_NAME: Record<string, string> = { store: 'Stores', shop: 'Shops', both: 'Stores and shops' };
const roleName = (role: string | undefined) => ROLE_NAME[role ?? 'both'] ?? 'Stores and shops';

/** The branches grouped by role, in the order the server sent them (stores, shops, both). */
export function groupsOf(branches: LocationBranch[]): { role: string; branches: LocationBranch[] }[] {
  const groups: { role: string; branches: LocationBranch[] }[] = [];
  for (const b of branches) {
    const role = b.role ?? 'both';
    const last = groups[groups.length - 1];
    if (last && last.role === role) last.branches.push(b);
    else groups.push({ role, branches: [b] });
  }
  return groups;
}

function Qty({ qty, unit, negative }: { qty: string; unit?: string; negative?: boolean }) {
  return (
    <>
      {showQty(qty)}{unit ? ` ${unit}` : ''}
      {negative && <div className="rt-flag">Negative</div>}
    </>
  );
}

const subtotalOf = (row: LocationRow, role: string) => (row.subtotals ?? []).find((s) => s.role === role)?.qty ?? '0';

/** A wide screen: a column per branch under its role, a subtotal where a role has several, then on the way and combined. */
export function LocationTable({ report }: { report: StockByLocation }) {
  const groups = groupsOf(report.branches ?? []);
  const items = report.items ?? [];
  const cost = items.some((r) => r.value_minor !== undefined);
  if (items.length === 0) return <EmptyState title="No items found.">Try another name or code.</EmptyState>;
  const columnsOf = (g: { branches: LocationBranch[] }) => g.branches.length + (g.branches.length > 1 ? 1 : 0);
  return (
    <div className="table-wrap" tabIndex={0}>
      <table className="stock-matrix">
        <thead>
          <tr>
            <th rowSpan={2}>Item</th>
            {groups.map((g) => <th key={g.role} colSpan={columnsOf(g)} className="num">{roleName(g.role)}</th>)}
            <th rowSpan={2} className="num">On the way</th>
            <th rowSpan={2} className="num">Combined</th>
            {cost && <th rowSpan={2} className="num">Value at cost</th>}
          </tr>
          <tr>
            {groups.flatMap((g) => [
              ...g.branches.map((b) => <th key={b.id} className="num">{branchLabel(b)}{b.closed && <span className="badge badge-warning"> Closed</span>}</th>),
              ...(g.branches.length > 1 ? [<th key={`${g.role}-subtotal`} className="num">Subtotal</th>] : []),
            ])}
          </tr>
        </thead>
        <tbody>
          {items.map((r) => (
            <tr key={r.product_id}>
              <td>{r.description}<br /><CategoryLabel category={r.category} /></td>
              {groups.flatMap((g) => [
                ...g.branches.map((b) => {
                  const c = (r.balances ?? []).find((x) => x.branch_id === b.id);
                  return <td key={b.id} className="num"><Qty qty={c?.qty ?? '0'} negative={c?.negative} /></td>;
                }),
                ...(g.branches.length > 1 ? [<td key={`${g.role}-subtotal`} className="num"><strong>{showQty(subtotalOf(r, g.role))}</strong></td>] : []),
              ])}
              <td className="num">{showQty(r.in_transit_qty ?? '0')}</td>
              <td className="num"><strong><Qty qty={r.combined_qty ?? '0'} unit={r.unit} negative={r.negative} /></strong></td>
              {cost && <td className="num">{r.combined_value_minor !== undefined ? money(r.combined_value_minor) : ''}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** A phone: a card per product with the combined total and what is on the way, and a button that opens the branches. */
export function LocationList({ report }: { report: StockByLocation }) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const items = report.items ?? [];
  if (items.length === 0) return <EmptyState title="No items found.">Try another name or code.</EmptyState>;
  const groups = groupsOf(report.branches ?? []);
  return (
    <ul style={{ listStyle: 'none', padding: 0 }}>
      {items.map((r) => {
        const id = r.product_id ?? '';
        const expanded = open[id] === true;
        return (
          <li key={id} className="rt-card">
            <div className="rt-row">
              <span><strong>{r.description}</strong><br /><CategoryLabel category={r.category} /></span>
              <span className="num"><strong>Combined <Qty qty={r.combined_qty ?? '0'} unit={r.unit} negative={r.negative} /></strong></span>
            </div>
            {groups.map((g) => (
              <div key={g.role} className="rt-row">
                <span>{roleName(g.role)}</span>
                <span className="num">{showQty(subtotalOf(r, g.role))} {r.unit}</span>
              </div>
            ))}
            {Number(r.in_transit_qty ?? '0') > 0 && (
              <div className="rt-row"><span>On the way</span><span className="num">{showQty(r.in_transit_qty ?? '0')} {r.unit}</span></div>
            )}
            {r.combined_value_minor !== undefined && <p className="hint">Value at cost {money(r.combined_value_minor)}</p>}
            <button type="button" className="btn-sm" aria-expanded={expanded} onClick={() => setOpen({ ...open, [id]: !expanded })}>
              {expanded ? 'Hide branches' : 'Show branches'}
            </button>
            {expanded && (r.balances ?? []).map((c) => {
              const b = (report.branches ?? []).find((x) => x.id === c.branch_id);
              return (
                <div key={c.branch_id} className="rt-row">
                  <span>{branchLabel(b)}{b?.closed && <span className="badge badge-warning"> Closed</span>}</span>
                  <span className="num"><Qty qty={c.qty ?? '0'} unit={r.unit} negative={c.negative} /></span>
                </div>
              );
            })}
          </li>
        );
      })}
    </ul>
  );
}

/** Value at cost by role, on the way and combined: present only for a user who may read cost in every branch. */
export function LocationTotals({ report }: { report: StockByLocation }) {
  const t = report.totals;
  if (!t || t.combined_value_minor === undefined) return null;
  return (
    <section aria-label="Totals">
      <h2>Value at cost</h2>
      {(t.by_role ?? []).map((r) => (
        <div key={r.role} className="rt-row"><span>{roleName(r.role)}</span><span className="num">{money(r.value_minor ?? 0)}</span></div>
      ))}
      {t.in_transit_value_minor !== undefined && t.in_transit_value_minor > 0 && (
        <div className="rt-row"><span>On the way</span><span className="num">{money(t.in_transit_value_minor)}</span></div>
      )}
      <div className="rt-row"><strong>Combined</strong><span className="num"><strong>{money(t.combined_value_minor)}</strong></span></div>
    </section>
  );
}

export function StockByLocationPage() {
  const canProfit = useProfitAccess();
  const phone = useIsPhone();
  const search = useLiveSearch();
  const query = search.term;
  const [negativeOnly, setNegativeOnly] = useState(false);
  const report = useQuery({
    queryKey: ['retail', 'stock-by-location', query, negativeOnly],
    queryFn: ({ signal }) => retail.stockByLocation({ query, negativeOnly, signal }),
    placeholderData: keepPreviousData,
  });
  const finding = report.isPlaceholderData || report.isFetching;
  return (
    <Gate screen="stockByLocation" title="Stores and shops" wide>
      <p className="hint">Every store and shop you can read, the stores first. Stock on its way is in neither count; it has its own column.</p>
      <div data-tour="location-search">
        <SearchBox id="location-search" label="Search by name, code or category" search={search} fetching={finding} count={report.data?.items?.length} />
      </div>
      <label style={{ fontWeight: 400 }}>
        <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={negativeOnly} onChange={(e) => setNegativeOnly(e.target.checked)} />
        Show only negative stock
      </label>
      <div data-tour="location-report">
        {report.isPending && <SkeletonList label="Loading stock" />}
        <Problem error={report.error} />
        {report.data && report.data.items?.length === 0 && query && <NoResults term={query} onClear={search.clear} noun="items" />}
        <SearchResults fetching={finding}>
          {report.data && (phone ? <LocationList report={report.data} /> : <LocationTable report={report.data} />)}
        </SearchResults>
        {report.data && canProfit && <LocationTotals report={report.data} />}
      </div>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stock-by-location')({ component: StockByLocationPage });
