import { useInfiniteQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { lending, type LoanListItem } from '../../../api/lending';
import { branchFilter } from '../../../auth/branch';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { money, statusBadge, words } from './loan-state';
import { showLending } from './permissions';

// Loans (FR-DIS, FR-REP): find a loan by its number, the member's number or name, filter by
// status, and open it. The list follows the branch chosen at the top of the page and pages with
// the server's cursor ("Load more").

export const STATUSES = ['', 'approved', 'active', 'closed', 'written_off', 'submitted', 'appraised', 'draft', 'rejected', 'cancelled'];

/** Shows its children only to a session that may read loans. */
export function LendingGate({ title, children }: { title: string; children: ReactNode }) {
  const { me } = useStaff();
  return (
    <main className="rt ln">
      <h1>{title}</h1>
      {showLending(me) ? children : <p className="empty-state">You do not have access to this page.</p>}
    </main>
  );
}

export function LoanRows({ items }: { items: LoanListItem[] }) {
  if (items.length === 0) return <p className="empty-state">No loans match.</p>;
  return (
    <ul className="ln-list">
      {items.map((l) => (
        <li key={l.id}>
          <Link className="ln-item" to="/staff/lending/loans/$loanId" params={{ loanId: l.id ?? '' }}>
            <span className="ln-item-head">
              <strong>{l.loan_no}</strong>
              <span className={statusBadge(l.status)}>{words(l.status)}</span>
            </span>
            <span>
              {l.member_name} ({l.member_no})
            </span>
            <span className="ln-item-facts">
              <span>Outstanding {money(l.total_outstanding_minor ?? 0, l.currency)}</span>
              {(l.days_past_due ?? 0) > 0 ? <span className="rt-flag">{l.days_past_due} days past due</span> : <span>Not in arrears</span>}
              {l.next_due_date && <span>Next due {l.next_due_date}</span>}
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

export function Loans() {
  const { branch } = useStaff();
  const [typed, setTyped] = useState('');
  const [q, setQ] = useState('');
  const [status, setStatus] = useState('');
  const pages = useInfiniteQuery({
    queryKey: ['lending', 'loans', branch, q, status],
    queryFn: ({ pageParam }) => lending.listLoans({ q, status, branchIds: branchFilter(branch), cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor || undefined,
    enabled: branch !== null,
  });
  const items = (pages.data?.pages ?? []).flatMap((p) => p.items ?? []);

  return (
    <LendingGate title="Loans">
      <form role="search" className="ln-search" data-tour="loan-search" onSubmit={(e) => { e.preventDefault(); setQ(typed.trim()); }}>
        <label htmlFor="loan-q">Loan number, member number or name</label>
        <div className="rt-row">
          <input id="loan-q" type="search" value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
          <button type="submit">Search</button>
        </div>
        <label htmlFor="loan-status">Status</label>
        <select id="loan-status" data-tour="loan-status" value={status} onChange={(e) => setStatus(e.target.value)}>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s === '' ? 'All' : words(s)}
            </option>
          ))}
        </select>
      </form>
      {pages.isPending && branch !== null && <p className="loading">Loading loans</p>}
      <Problem error={pages.error} />
      {pages.data && <LoanRows items={items} />}
      {pages.hasNextPage && (
        <button type="button" className="btn-block" disabled={pages.isFetchingNextPage} onClick={() => void pages.fetchNextPage()}>
          {pages.isFetchingNextPage ? 'Loading' : 'Load more'}
        </button>
      )}
    </LendingGate>
  );
}

export const Route = createLazyRoute('/staff/lending/')({ component: Loans });
