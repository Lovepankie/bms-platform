import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type Customer } from '../../../api/retail';
import { limitWords, termsWords } from './account-state';
import { Gate, Problem, money } from './ui';

// Customer accounts (customer_accounts capability, ADR-033, issue #196): the credit buyers with what each owes, the limit and
// the terms. It is the administrator's page (retail.credit.manage); a seller opens one buyer's account from Credit buyers.
// The search is the shared live search: the term is never put in the address.

/** One buyer: what is owed over the branches the session may read, the limit and terms, and the way to the account. */
export function AccountRow({ row }: { row: Customer }) {
  const owed = row.balance_minor ?? 0;
  return (
    <li className="rt-card" data-testid={`account-${row.id}`}>
      <p><strong>{row.name}</strong></p>
      <p>{owed > 0 ? <>Owes <strong>{money(owed)}</strong>.</> : 'Owes nothing.'}</p>
      <p className="hint">{limitWords({ credit_limit_minor: row.credit_limit_minor }, money)} {termsWords(row.terms_days)}</p>
      <p>
        <Link className="btn-sm" to="/staff/retail/accounts/$customerId" params={{ customerId: row.id ?? '' }} aria-label={`Open the account of ${row.name}`}>Open account</Link>
      </p>
    </li>
  );
}

function AccountsPage() {
  const search = useLiveSearch();
  const query = search.term;
  const rows = useInfiniteQuery({
    queryKey: ['retail', 'accounts', query],
    queryFn: ({ pageParam, signal }) => retail.listCustomerPage({ query, ...(pageParam ? { cursor: pageParam } : {}), signal }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
    placeholderData: keepPreviousData,
  });
  const finding = rows.isPlaceholderData || (rows.isFetching && !rows.isFetchingNextPage);
  const people = (rows.data?.pages ?? []).flatMap((p) => p.items ?? []);
  return (
    <Gate screen="customerAccounts" title="Customer accounts">
      <p className="hint" data-tour="accounts-help">Open a buyer to set their credit limit and payment terms, take a payment, or read their statement.</p>
      <p><Link to="/staff/retail/customer-ageing">See what every buyer owes, by how late it is</Link></p>
      {rows.isPending && <p className="loading">Loading</p>}
      <Problem error={rows.error} />
      <SearchBox id="search-accounts" label="Search by name or phone" search={search} fetching={finding} count={rows.data ? people.length : undefined} more={rows.hasNextPage} />
      {rows.data && people.length === 0 && (query ? <NoResults term={query} onClear={search.clear} noun="credit buyers" /> : <p className="empty-state">No credit buyers yet.</p>)}
      <SearchResults fetching={finding}>
        <ul style={{ listStyle: 'none', padding: 0 }} data-tour="accounts-list">
          {people.map((r) => <AccountRow key={r.id} row={r} />)}
        </ul>
      </SearchResults>
      {rows.hasNextPage && (
        <button type="button" onClick={() => void rows.fetchNextPage()} disabled={rows.isFetchingNextPage || rows.isPlaceholderData}>
          {rows.isFetchingNextPage ? 'Loading' : 'Show more credit buyers'}
        </button>
      )}
    </Gate>
  );
}

export { AccountsPage };

export const Route = createLazyRoute('/staff/retail/accounts')({ component: AccountsPage });
