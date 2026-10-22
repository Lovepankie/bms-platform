import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { Highlight, NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { lending, type LoanListItem } from '../../../api/lending';
import { branchFilter } from '../../../auth/branch';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { money, statusBadge, words } from './loan-state';
import { canBringIn } from './existing-loans-state';
import { showLending } from './permissions';

// Loans (FR-DIS, FR-REP): find a loan by its number, the member's number, name or phone, filter by
// status, and open it. The search runs as you type (useLiveSearch, #241). The list follows the branch
// chosen at the top of the page and pages with the server's cursor ("Load more").

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

export function LoanRows({ items, term = '' }: { items: LoanListItem[]; term?: string }) {
  if (items.length === 0) return <p className="empty-state">No loans match.</p>;
  return (
    <ul className="ln-list">
      {items.map((l) => (
        <li key={l.id}>
          <Link className="ln-item" to="/staff/lending/loans/$loanId" params={{ loanId: l.id ?? '' }}>
            <span className="ln-item-head">
              <strong><Highlight text={l.loan_no ?? ''} term={term} /></strong>
              <span className={statusBadge(l.status)}>{words(l.status)}</span>
            </span>
            <span>
              <Highlight text={l.member_name ?? ''} term={term} /> (<Highlight text={l.member_no ?? ''} term={term} />)
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
  const { branch, me } = useStaff();
  const search = useLiveSearch();
  const [status, setStatus] = useState('');
  const pages = useInfiniteQuery({
    queryKey: ['lending', 'loans', branch, search.term, status],
    queryFn: ({ pageParam, signal }) => lending.listLoans({ q: search.term, status, branchIds: branchFilter(branch), cursor: pageParam, signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor || undefined,
    enabled: branch !== null,
    placeholderData: keepPreviousData,
  });
  const items = (pages.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const fetching = pages.isPlaceholderData || (pages.isFetching && !pages.isFetchingNextPage);

  return (
    <LendingGate title="Loans">
      {canBringIn(me) && (
        <p><Link to="/staff/lending/existing-loans">Bring in existing loans from a paper register</Link></p>
      )}
      <form role="search" className="ln-search" data-tour="loan-search" onSubmit={(e) => { e.preventDefault(); search.flush(); }}>
        <SearchBox id="loan-q" label="Loan number, member number, name or phone" search={search} fetching={fetching} count={pages.data ? items.length : undefined} more={pages.hasNextPage} />
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
      {pages.data && (
        <SearchResults fetching={fetching}>
          {items.length === 0 && search.term ? <NoResults term={search.term} onClear={search.clear} noun="loans" /> : <LoanRows items={items} term={search.term} />}
        </SearchResults>
      )}
      {pages.hasNextPage && (
        <button type="button" className="btn-block" disabled={pages.isFetchingNextPage || pages.isPlaceholderData} onClick={() => void pages.fetchNextPage()}>
          {pages.isFetchingNextPage ? 'Loading' : 'Load more'}
        </button>
      )}
    </LendingGate>
  );
}

export const Route = createLazyRoute('/staff/lending/')({ component: Loans });
