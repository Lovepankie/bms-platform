import { useInfiniteQuery } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { investments, type Investment } from '../../../api/investments';
import { branchFilter } from '../../../auth/branch';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { INVESTMENTS_OPEN, PRODUCTS_MANAGE, investmentBadge, may, percent, showInvestments } from './investment-state';
import { money, words } from './loan-state';

// Investments (FR-INV-02, FR-INV-12): find an investment by its account number, the member's
// number or name, filter by status, and open it; the member tab lists one member's investments.
// The list follows the branch chosen at the top of the page and pages with the server's cursor.

export const STATUSES = ['', 'active', 'matured', 'pending_funding', 'paid_out', 'rolled_over', 'withdrawn_early', 'cancelled'];

/** Shows its children only to a session that may read investments, with the investments sub-menu. */
export function InvestmentsGate({ title, children }: { title: string; children: ReactNode }) {
  const { me } = useStaff();
  if (!showInvestments(me)) {
    return (
      <main className="rt ln">
        <h1>{title}</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  return (
    <main className="rt ln">
      <nav className="iv-nav" aria-label="Investments">
        <Link to="/staff/lending/investments" activeOptions={{ exact: true }}>All</Link>
        <Link to="/staff/lending/investments/maturities">Maturities</Link>
        {may(me, INVESTMENTS_OPEN) && <Link to="/staff/lending/investments/new">New investment</Link>}
        <Link to="/staff/lending/investments/products">{may(me, PRODUCTS_MANAGE) ? 'Products setup' : 'Products'}</Link>
      </nav>
      <h1>{title}</h1>
      {children}
    </main>
  );
}

export function InvestmentRows({ items }: { items: Investment[] }) {
  if (items.length === 0) return <p className="empty-state">No investments match.</p>;
  return (
    <ul className="ln-list">
      {items.map((i) => (
        <li key={i.id}>
          <Link className="ln-item" to="/staff/lending/investments/$investmentId" params={{ investmentId: i.id ?? '' }}>
            <span className="ln-item-head">
              <strong>{i.account_no}</strong>
              <span className={investmentBadge(i.status)}>{words(i.status)}</span>
            </span>
            <span>
              {i.member_name} ({i.member_no})
            </span>
            <span className="ln-item-facts">
              <span>{money(i.principal_minor, i.currency)}</span>
              <span>{i.product_name}, {percent(i.return_rate_bp)} a year, {i.term_months} months</span>
              {i.maturity_date && <span>Matures {i.maturity_date}</span>}
              {(i.return_available_minor ?? 0) > 0 && <span className="rt-flag">Return due {money(i.return_available_minor, i.currency)}</span>}
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

function useInvestmentPages(q: string, status: string, memberId?: string) {
  const { branch } = useStaff();
  const pages = useInfiniteQuery({
    queryKey: ['lending', 'investments', branch, q, status, memberId ?? ''],
    queryFn: ({ pageParam }) => investments.list({ q, status, memberId, branchIds: branchFilter(branch), cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor || undefined,
    enabled: branch !== null,
  });
  return { pages, items: (pages.data?.pages ?? []).flatMap((p) => p.items ?? []), waiting: pages.isPending && branch !== null };
}

function MoreButton({ pages }: { pages: ReturnType<typeof useInvestmentPages>['pages'] }) {
  if (!pages.hasNextPage) return null;
  return (
    <button type="button" className="btn-block" disabled={pages.isFetchingNextPage} onClick={() => void pages.fetchNextPage()}>
      {pages.isFetchingNextPage ? 'Loading' : 'Load more'}
    </button>
  );
}

function Investments() {
  const [typed, setTyped] = useState('');
  const [q, setQ] = useState('');
  const [status, setStatus] = useState('');
  const { pages, items, waiting } = useInvestmentPages(q, status);
  return (
    <InvestmentsGate title="Investments">
      <form role="search" className="ln-search" onSubmit={(e) => { e.preventDefault(); setQ(typed.trim()); }}>
        <label htmlFor="inv-q">Account number, member number or name</label>
        <div className="rt-row">
          <input id="inv-q" type="search" value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
          <button type="submit">Search</button>
        </div>
        <label htmlFor="inv-status">Status</label>
        <select id="inv-status" value={status} onChange={(e) => setStatus(e.target.value)}>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s === '' ? 'All' : words(s)}
            </option>
          ))}
        </select>
      </form>
      {waiting && <p className="loading">Loading investments</p>}
      <Problem error={pages.error} />
      {pages.data && <InvestmentRows items={items} />}
      <MoreButton pages={pages} />
    </InvestmentsGate>
  );
}

const memberRoute = getRouteApi('/staff/lending/investments/member/$memberId');

/** The member investments tab: every investment of one member, and a new one for them. */
function MemberInvestments() {
  const { memberId } = memberRoute.useParams();
  const { me } = useStaff();
  const { pages, items, waiting } = useInvestmentPages('', '', memberId);
  const first = items[0];
  const held = items
    .filter((i) => ['active', 'matured'].includes(i.status ?? ''))
    .reduce((sum, i) => sum + (i.principal_held_minor ?? 0), 0);
  return (
    <InvestmentsGate title={first ? `Investments of ${first.member_name ?? ''}` : 'Member investments'}>
      {first && (
        <p className="ln-muted">
          Member {first.member_no}. Held now: <strong>{money(held, first.currency)}</strong> in {items.filter((i) => ['active', 'matured'].includes(i.status ?? '')).length} open investments.
        </p>
      )}
      {may(me, INVESTMENTS_OPEN) && (
        <Link className="btn rt-primary" to="/staff/lending/investments/member/$memberId/new" params={{ memberId }}>
          New investment for this member
        </Link>
      )}
      {waiting && <p className="loading">Loading investments</p>}
      <Problem error={pages.error} />
      {pages.data && <InvestmentRows items={items} />}
      <MoreButton pages={pages} />
    </InvestmentsGate>
  );
}

export const Route = createLazyRoute('/staff/lending/investments')({ component: Investments });
export const MemberRoute = createLazyRoute('/staff/lending/investments/member/$memberId')({ component: MemberInvestments });
