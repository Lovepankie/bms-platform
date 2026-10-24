import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { Highlight, NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { savings, type SavingsAccount } from '../../../api/savings';
import { branchFilter } from '../../../auth/branch';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { money, words } from './loan-state';
import { mayManageProducts, showSavings } from './savings-permissions';
import { NoAccess } from '../../../app/ui/EmptyState';

// Savings accounts (FR-SAV-02): find an account by its number, the member's number, name or phone, filter
// by status, and open it. The list follows the branch chosen at the top of the page and pages with
// the server's cursor ("Load more"). Product set-up is one tap away for those who manage it.

export const SAVINGS_STATUSES = ['', 'active', 'dormant', 'frozen', 'closed'];

/** The badge class for an account status: the state is in the words too, colour only helps. */
export function savingsBadge(status: string | undefined): string {
  switch (status) {
    case 'active':
      return 'badge badge-success';
    case 'dormant':
      return 'badge badge-warning';
    case 'frozen':
      return 'badge badge-danger';
    default:
      return 'badge';
  }
}

/** Shows its children only to a session that may read savings, with the savings sub-navigation. */
export function SavingsGate({ title, children }: { title: string; children: ReactNode }) {
  const { me } = useStaff();
  return (
    <main className="rt ln">
      <h1>{title}</h1>
      {showSavings(me) ? (
        <>
          <nav className="sv-nav" aria-label="Savings" data-tour="savings-nav">
            <Link to="/staff/lending/savings" activeOptions={{ exact: true }}>Accounts</Link>
            {mayManageProducts(me) && <Link to="/staff/lending/savings/products">Products</Link>}
          </nav>
          {children}
        </>
      ) : (
        <NoAccess />
      )}
    </main>
  );
}

export function AccountRows({ items, term = '' }: { items: SavingsAccount[]; term?: string }) {
  if (items.length === 0) return <p className="empty-state">No savings accounts match.</p>;
  return (
    <ul className="ln-list">
      {items.map((a) => (
        <li key={a.id}>
          <Link className="ln-item" to="/staff/lending/savings/accounts/$accountId" params={{ accountId: a.id ?? '' }}>
            <span className="ln-item-head">
              <strong><Highlight text={a.account_no ?? ''} term={term} /></strong>
              <span className={savingsBadge(a.status)}>{words(a.status)}</span>
            </span>
            <span>
              <Highlight text={a.member_name ?? ''} term={term} /> (<Highlight text={a.member_no ?? ''} term={term} />)
            </span>
            <span className="ln-item-facts">
              <span>Balance {money(a.balance_minor ?? 0, a.currency)}</span>
              <span>{a.product_name}</span>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

export function Accounts() {
  const { branch } = useStaff();
  const search = useLiveSearch();
  const [status, setStatus] = useState('');
  const pages = useInfiniteQuery({
    queryKey: ['savings', 'accounts', branch, search.term, status],
    queryFn: ({ pageParam, signal }) => savings.listAccounts({ q: search.term, status, branchIds: branchFilter(branch), cursor: pageParam, signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor || undefined,
    enabled: branch !== null,
    placeholderData: keepPreviousData,
  });
  const items = (pages.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const fetching = pages.isPlaceholderData || (pages.isFetching && !pages.isFetchingNextPage);
  return (
    <SavingsGate title="Savings">
      <form role="search" className="ln-search" data-tour="savings-search" onSubmit={(e) => { e.preventDefault(); search.flush(); }}>
        <SearchBox id="sv-q" label="Account number, member number, name or phone" search={search} fetching={fetching} count={pages.data ? items.length : undefined} more={pages.hasNextPage} />
        <label htmlFor="sv-status">Status</label>
        <select id="sv-status" data-tour="savings-status" value={status} onChange={(e) => setStatus(e.target.value)}>
          {SAVINGS_STATUSES.map((s) => (
            <option key={s} value={s}>
              {s === '' ? 'All' : words(s)}
            </option>
          ))}
        </select>
      </form>
      {pages.isPending && branch !== null && <p className="loading">Loading savings accounts</p>}
      <Problem error={pages.error} />
      {pages.data && (
        <SearchResults fetching={fetching}>
          {items.length === 0 && search.term ? <NoResults term={search.term} onClear={search.clear} noun="savings accounts" /> : <AccountRows items={items} term={search.term} />}
        </SearchResults>
      )}
      {pages.hasNextPage && (
        <button type="button" className="btn-block" disabled={pages.isFetchingNextPage || pages.isPlaceholderData} onClick={() => void pages.fetchNextPage()}>
          {pages.isFetchingNextPage ? 'Loading' : 'Load more'}
        </button>
      )}
    </SavingsGate>
  );
}

export const Route = createLazyRoute('/staff/lending/savings')({ component: Accounts });
