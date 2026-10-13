import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type SaleReturn } from '../../../api/retail';
import { settledWords } from './returns-state';
import { showDate } from './transfers';
import { Gate, Problem, money, useBranchName } from './ui';

// Customer returns (returns capability, ADR-033, issue #201): the credit notes, newest first, found by their number, the
// number of the sale or the buyer's name. A return is made from a sale: open it in All sales or Credit sales and choose
// Return goods. The search is the shared live search: the term is never put in the address.

/** One credit note: its number, the sale, who it was for, what it came to and how it was settled. */
export function ReturnRow({ row, branchName }: { row: SaleReturn; branchName?: string }) {
  return (
    <li className="rt-card" data-testid={`return-${row.credit_note_no}`}>
      <p>
        <strong>{row.credit_note_no}</strong> {row.status === 'voided' && <span className="badge badge-warning">Voided</span>}
        <br />
        {showDate(row.returned_on)}, <strong>{money(row.total_minor ?? 0)}</strong>
      </p>
      <p className="hint">
        Sale {row.sale_no}{row.customer_name ? `, ${row.customer_name}` : ''}{branchName ? `, ${branchName}` : ''}. {settledWords(row)}.
      </p>
      <p>
        <Link className="btn-sm" to="/staff/retail/returns/$returnId" params={{ returnId: row.id ?? '' }} aria-label={`Open credit note ${row.credit_note_no}`}>Open</Link>
      </p>
    </li>
  );
}

function ReturnsPage() {
  const search = useLiveSearch();
  const query = search.term;
  const nameOf = useBranchName();
  const [status, setStatus] = useState('');
  const rows = useInfiniteQuery({
    queryKey: ['retail', 'returns', query, status],
    queryFn: ({ pageParam, signal }) => retail.listReturns({ query, status, ...(pageParam ? { cursor: pageParam } : {}), signal }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
    placeholderData: keepPreviousData,
  });
  const finding = rows.isPlaceholderData || (rows.isFetching && !rows.isFetchingNextPage);
  const notes = (rows.data?.pages ?? []).flatMap((p) => p.items ?? []);
  return (
    <Gate screen="returns" title="Customer returns">
      <p className="hint" data-tour="returns-help">
        To take goods back, open the sale in <Link to="/staff/retail/sales">All sales</Link> or <Link to="/staff/retail/credit-sales">Credit sales</Link> and choose Return goods. Each return is a numbered credit note.
      </p>
      {rows.isPending && <p className="loading">Loading</p>}
      <Problem error={rows.error} />
      <SearchBox id="search-returns" label="Search by credit note, sale or buyer" search={search} fetching={finding} count={rows.data ? notes.length : undefined} more={rows.hasNextPage} />
      <label htmlFor="returns-status">Show</label>
      <select id="returns-status" value={status} onChange={(e) => setStatus(e.target.value)}>
        <option value="">All credit notes</option>
        <option value="completed">Standing</option>
        <option value="voided">Voided</option>
      </select>
      {rows.data && notes.length === 0 && (query ? <NoResults term={query} onClear={search.clear} noun="credit notes" /> : <p className="empty-state">No goods have been taken back yet.</p>)}
      <SearchResults fetching={finding}>
        <ul style={{ listStyle: 'none', padding: 0 }} data-tour="returns-list">
          {notes.map((r) => <ReturnRow key={r.id} row={r} branchName={nameOf(r.branch_id)} />)}
        </ul>
      </SearchResults>
      {rows.hasNextPage && (
        <button type="button" onClick={() => void rows.fetchNextPage()} disabled={rows.isFetchingNextPage || rows.isPlaceholderData}>
          {rows.isFetchingNextPage ? 'Loading' : 'Show more credit notes'}
        </button>
      )}
    </Gate>
  );
}

export { ReturnsPage };

export const Route = createLazyRoute('/staff/retail/returns')({ component: ReturnsPage });
