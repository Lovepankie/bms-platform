import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { fetchHealth, listMembers } from '../../api/client';
import { branchFilter } from '../../auth/branch';
import { EmptyState, SkeletonList } from '../../components/states';
import { useStaff } from './context';

// Staff home: the member list of the active branch (FR-BR-04), where the user may read members.

export function StaffHome() {
  const { me, branch } = useStaff();
  const health = useQuery({ queryKey: ['health'], queryFn: fetchHealth });
  const canReadMembers = (me.permissions ?? []).includes('lending.members.read');
  const members = useQuery({
    queryKey: ['members', branch],
    queryFn: () => listMembers(branchFilter(branch), 50),
    enabled: canReadMembers && branch !== null,
  });

  return (
    <main>
      <div className="page-header">
        <h1>Staff area</h1>
        <span className={health.data === 'UP' ? 'badge badge-success' : 'badge'}>
          API: {health.data ?? 'checking'}
        </span>
      </div>
      {canReadMembers && (
        <section aria-labelledby="members-title" data-tour="lending-members">
          <h2 id="members-title">Members</h2>
          {members.isPending && <SkeletonList label="Loading members" />}
          {members.isError && <p role="alert" className="alert alert-danger">Could not load members.</p>}
          {members.data && (members.data.items ?? []).length === 0 && (
            <EmptyState art="noApplications" title="No members yet.">Members of this branch appear here.</EmptyState>
          )}
          {members.data && (members.data.items ?? []).length > 0 && (
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
                  {(members.data.items ?? []).map((m) => (
                    <tr key={m.id}>
                      <td data-label="Member no">{m.member_no}</td>
                      <td data-label="Name">{m.full_name}</td>
                      <td data-label="Phone">{m.phone_e164_masked}</td>
                      <td data-label="KYC">{m.kyc_status}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff/')({ component: StaffHome });
