import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { Highlight, NoResults, SearchBox, SearchResults } from '../../app/ui/SearchBox';
import { useLiveSearch } from '../../app/ui/useLiveSearch';
import { fetchHealth, listMembers } from '../../api/client';
import { branchFilter } from '../../auth/branch';
import { EmptyState, SkeletonList } from '../../components/states';
import { useStaff } from './context';
import { HomeCards } from './home-cards';

// Staff home: a card per module the business uses (issue #471, home-cards.tsx), then the member list of the active branch
// (FR-BR-04), where the user may read members.

export function StaffHome() {
  const { me, branch } = useStaff();
  const health = useQuery({ queryKey: ['health'], queryFn: fetchHealth });
  // The lending module's screens are offered only to a tenant that has it (issue #185).
  const canReadMembers = (me.permissions ?? []).includes('lending.members.read') && (!me.modules || me.modules.includes('lending'));
  const search = useLiveSearch();
  const members = useQuery({
    queryKey: ['members', branch, search.term],
    queryFn: ({ signal }) => listMembers(branchFilter(branch), 50, search.term, signal),
    enabled: canReadMembers && branch !== null,
    placeholderData: keepPreviousData,
  });
  const fetching = members.isPlaceholderData || members.isFetching;
  const found = members.data?.items ?? [];

  return (
    <main>
      <div className="page-header" data-tour="staff-home-header">
        <h1>Staff area</h1>
        <span className={health.data === 'UP' ? 'badge badge-success' : 'badge'}>
          API: {health.data ?? 'checking'}
        </span>
      </div>
      <HomeCards />
      {canReadMembers && (
        <section aria-labelledby="members-title" data-tour="lending-members">
          <h2 id="members-title">Members</h2>
          <div data-tour="staff-home-search">
          <SearchBox id="member-search" label="Member number, name, phone or national ID" search={search} fetching={fetching} count={members.data ? found.length : undefined} more={!!members.data?.next_cursor} />
          </div>
          {members.isPending && <SkeletonList label="Loading members" />}
          {members.isError && <p role="alert" className="alert alert-danger">Could not load members.</p>}
          {members.data && found.length === 0 && search.term && <NoResults term={search.term} onClear={search.clear} noun="members" />}
          {members.data && found.length === 0 && !search.term && (
            <EmptyState art="noApplications" title="No members yet.">Members of this branch appear here.</EmptyState>
          )}
          {members.data && found.length > 0 && (
            <SearchResults fetching={fetching}>
            <div className="table-wrap table-cards" tabIndex={0}>
              <table>
                <thead>
                  <tr>
                    <th>Member no</th>
                    <th>Name</th>
                    <th>Phone</th>
                    <th>KYC</th>
                  </tr>
                </thead>
                <tbody>
                  {found.map((m) => (
                    <tr key={m.id}>
                      <td data-label="Member no"><Highlight text={m.member_no ?? ''} term={search.term} /></td>
                      <td data-label="Name">
                        {m.id ? (
                          <Link to="/staff/lending/members/$memberId" params={{ memberId: m.id }}><Highlight text={m.full_name ?? ''} term={search.term} /></Link>
                        ) : (
                          <Highlight text={m.full_name ?? ''} term={search.term} />
                        )}
                      </td>
                      <td data-label="Phone">{m.phone_e164_masked}</td>
                      <td data-label="KYC">{m.kyc_status}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            </SearchResults>
          )}
        </section>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff/')({ component: StaffHome });
