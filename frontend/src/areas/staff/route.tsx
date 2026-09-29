import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { fetchHealth, listMembers } from '../../api/client';

function StaffHome() {
  const health = useQuery({ queryKey: ['health'], queryFn: fetchHealth });
  const members = useQuery({ queryKey: ['members'], queryFn: () => listMembers(50) });

  return (
    <main>
      <h1>Staff area</h1>
      <p>
        API: <strong>{health.data ?? 'checking'}</strong>
      </p>
      <h2>Members</h2>
      {members.isPending && <p>Loading members</p>}
      {members.isError && <p>Could not load members. Is the API running with a dev tenant?</p>}
      {members.data && (
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
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff')({ component: StaffHome });
