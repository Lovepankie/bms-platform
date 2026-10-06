import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { fetchHealth, listMembers } from '../../api/client';
import { branchFilter } from '../../auth/branch';
import { useStaff } from './context';

// Staff home: the member list of the active branch (FR-BR-04), where the user may read members.

function StaffHome() {
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
        <>
          <h2>Members</h2>
          {members.isPending && <p className="loading">Loading members</p>}
          {members.isError && <p role="alert" className="alert alert-danger">Could not load members.</p>}
          {members.data && (
            <div className="table-wrap">
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
                      <td>{m.member_no}</td>
                      <td>{m.full_name}</td>
                      <td>{m.phone_e164_masked}</td>
                      <td>{m.kyc_status}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff/')({ component: StaffHome });
