import { useInfiniteQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useMemo, useState } from 'react';
import { NoResults, SearchBox } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import type { CollateralItem } from '../../../api/collateral';
import { useCollateralApi } from '../../../api/lending-context';
import { branchFilter } from '../../../auth/branch';
import { EmptyState } from '../../../components/states';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { custodyBadge, custodyWords, ownerWords, TYPES, typeWords } from './collateral-state';
import { money } from './loan-state';
import { mayManageCollateral } from './member-permissions';
import { CollateralGate, CustomerNav } from './member-ui';

// The collateral register (FR-COL-01 to FR-COL-06): every item in the branch chosen at the top, filtered by kind and by who
// holds it, each opening to its own page. The server has no free-text search over collateral, so the words typed here narrow
// the loaded items by description, reference and owner on this device (the member's own page lists a member's collateral).

const CUSTODY_FILTER = [
  { value: '', label: 'All' },
  { value: 'pledged', label: 'The owner keeps it' },
  { value: 'in_custody', label: 'We hold it' },
  { value: 'seized', label: 'Taken over' },
  { value: 'released', label: 'Given back' },
  { value: 'disposed', label: 'Sold or disposed of' },
];

export function CollateralRows({ items }: { items: CollateralItem[] }) {
  if (items.length === 0) return <p className="empty-state">No items match.</p>;
  return (
    <ul className="ln-list" data-tour="collateral-list">
      {items.map((c) => (
        <li key={c.id}>
          <Link className="ln-item" to="/staff/lending/collateral/$collateralId" params={{ collateralId: c.id ?? '' }}>
            <span className="ln-item-head">
              <strong>{c.description}</strong>
              <span className={custodyBadge(c.custody_status)}>{custodyWords(c.custody_status)}</span>
            </span>
            <span>{typeWords(c.collateral_type)}{c.reference_no ? `, ${c.reference_no}` : ''}</span>
            <span className="ln-item-facts">
              <span>Worth about {money(c.collateral_value_minor ?? c.estimated_value_minor, c.currency)}</span>
              <span>Owner: {c.owner_name ?? ownerWords(c.owner_relationship)}</span>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

export function CollateralList() {
  const { me, branch } = useStaff();
  const api = useCollateralApi();
  const [type, setType] = useState('');
  const [custody, setCustody] = useState('');
  const search = useLiveSearch();
  const pages = useInfiniteQuery({
    queryKey: ['collateral', 'list', branch, type, custody],
    queryFn: ({ pageParam, signal }) => api.list({ type, custody, branchIds: branchFilter(branch), cursor: pageParam, signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor || undefined,
    enabled: branch !== null,
  });
  const all = (pages.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const items = useMemo(() => {
    const t = search.term.toLowerCase();
    return t === '' ? all : all.filter((c) => [c.description, c.reference_no, c.owner_name].some((v) => (v ?? '').toLowerCase().includes(t)));
  }, [all, search.term]);
  const add = mayManageCollateral(me);
  return (
    <CollateralGate title="Collateral">
      <CustomerNav />
      {add && <Link to="/staff/lending/collateral/new" className="btn rt-primary btn-block" data-tour="collateral-add">Register collateral</Link>}
      <form role="search" className="ln-search" data-tour="collateral-filters" onSubmit={(e) => e.preventDefault()}>
        <SearchBox id="col-q" label="Find an item (description, reference or owner)" search={search} fetching={pages.isFetching} count={pages.data ? items.length : undefined} more={pages.hasNextPage}
          hint="Looks through the items loaded below." />
        <label htmlFor="col-type">Kind</label>
        <select id="col-type" value={type} onChange={(e) => setType(e.target.value)}>
          <option value="">All</option>
          {TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
        </select>
        <label htmlFor="col-custody">Who keeps it</label>
        <select id="col-custody" value={custody} onChange={(e) => setCustody(e.target.value)}>
          {CUSTODY_FILTER.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
        </select>
      </form>
      {pages.isPending && branch !== null && <p className="loading">Loading collateral</p>}
      <Problem error={pages.error} />
      {pages.data && all.length === 0 ? (
        <EmptyState art="noProducts" title="No collateral registered." action={add ? <Link to="/staff/lending/collateral/new" className="btn">Register the first item</Link> : undefined}>
          Land titles, logbooks and other security appear here once they are registered.
        </EmptyState>
      ) : (
        pages.data && (items.length === 0 && search.term ? <NoResults term={search.term} onClear={search.clear} noun="items" /> : <CollateralRows items={items} />)
      )}
      {pages.hasNextPage && (
        <button type="button" className="btn-block" disabled={pages.isFetchingNextPage} onClick={() => void pages.fetchNextPage()}>
          {pages.isFetchingNextPage ? 'Loading' : 'Load more'}
        </button>
      )}
    </CollateralGate>
  );
}

export const Route = createLazyRoute('/staff/lending/collateral')({ component: CollateralList });
