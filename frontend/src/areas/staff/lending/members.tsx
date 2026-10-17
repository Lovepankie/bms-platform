import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { Highlight, NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { useMembersApi } from '../../../api/lending-context';
import type { MemberListItem } from '../../../api/members';
import { branchFilter } from '../../../auth/branch';
import { EmptyState } from '../../../components/states';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { words } from './loan-state';
import { mayCreateMember } from './member-permissions';
import { kycBadge, kycWords } from './member-state';
import { CustomerNav, MembersGate } from './member-ui';

// Members (FR-MEM-11): find a member as you type by number, name, phone or national ID (the words never go in the address),
// filter by status, open one, or add a new one. The list follows the branch chosen at the top and pages with the server's
// cursor ("Load more"). Phone and ID show masked, as the server sends them.

const STATUS = [
  { value: '', label: 'All' },
  { value: 'active', label: 'Active' },
  { value: 'inactive', label: 'Not active' },
  { value: 'exited', label: 'Left' },
];

export function MemberRows({ items, term = '' }: { items: MemberListItem[]; term?: string }) {
  return (
    <ul className="ln-list" data-tour="members-list">
      {items.map((m) => (
        <li key={m.id}>
          <Link className="ln-item" to="/staff/lending/members/$memberId" params={{ memberId: m.id ?? '' }}>
            <span className="ln-item-head">
              <strong><Highlight text={m.full_name ?? ''} term={term} /></strong>
              <span className={kycBadge(m.kyc_status)}>{kycWords(m.kyc_status)}</span>
            </span>
            <span><Highlight text={m.member_no ?? ''} term={term} />{m.status && m.status !== 'active' ? `, ${words(m.status).toLowerCase()}` : ''}{m.is_blacklisted ? ', blacklisted' : ''}</span>
            <span className="ln-item-facts">
              <span>Phone {m.phone_e164_masked}</span>
              {m.national_id_masked && <span>ID {m.national_id_masked}</span>}
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

export function Members() {
  const { me, branch } = useStaff();
  const api = useMembersApi();
  const search = useLiveSearch();
  const [status, setStatus] = useState('');
  const pages = useInfiniteQuery({
    queryKey: ['members', 'list', branch, search.term, status],
    queryFn: ({ pageParam, signal }) => api.list({ q: search.term, status, branchIds: branchFilter(branch), cursor: pageParam, signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor || undefined,
    enabled: branch !== null,
    placeholderData: keepPreviousData,
  });
  const items = (pages.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const fetching = pages.isPlaceholderData || (pages.isFetching && !pages.isFetchingNextPage);
  const add = mayCreateMember(me);

  return (
    <MembersGate title="Members">
      <CustomerNav />
      {add && <Link to="/staff/lending/members/new" className="btn rt-primary btn-block" data-tour="member-add">Add a member</Link>}
      <form role="search" className="ln-search" data-tour="member-search" onSubmit={(e) => { e.preventDefault(); search.flush(); }}>
        <SearchBox id="member-q" label="Member number, name, phone or national ID" search={search} fetching={fetching} count={pages.data ? items.length : undefined} more={pages.hasNextPage} />
        <label htmlFor="member-status">Status</label>
        <select id="member-status" data-tour="member-status" value={status} onChange={(e) => setStatus(e.target.value)}>
          {STATUS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
        </select>
      </form>
      {pages.isPending && branch !== null && <p className="loading">Loading members</p>}
      <Problem error={pages.error} />
      {pages.data && (
        <SearchResults fetching={fetching}>
          {items.length === 0 && search.term ? (
            <NoResults term={search.term} onClear={search.clear} noun="members" />
          ) : items.length === 0 ? (
            <EmptyState art="noApplications" title="No members yet." action={add ? <Link to="/staff/lending/members/new" className="btn">Add the first member</Link> : undefined}>
              Members of this branch appear here once they are added.
            </EmptyState>
          ) : (
            <MemberRows items={items} term={search.term} />
          )}
        </SearchResults>
      )}
      {pages.hasNextPage && (
        <button type="button" className="btn-block" disabled={pages.isFetchingNextPage || pages.isPlaceholderData} onClick={() => void pages.fetchNextPage()}>
          {pages.isFetchingNextPage ? 'Loading' : 'Load more'}
        </button>
      )}
    </MembersGate>
  );
}

export const Route = createLazyRoute('/staff/lending/members')({ component: Members });
