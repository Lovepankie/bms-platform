import { useQuery } from '@tanstack/react-query';
import { createLazyRoute, Link } from '@tanstack/react-router';
import { useState } from 'react';
import { type Application, APPLICATION_STATUSES, type ApplicationStatus, listApplications, statusLabel } from '../../api/onboarding';

// The applications queue (FR-ONB-05): newest first, filtered by status, with the count per status.

export function ApplicationsTable({
  items,
  counts,
  filter,
  onFilter,
}: {
  items: Application[];
  counts: Record<string, number>;
  filter: ApplicationStatus | 'open';
  onFilter: (f: ApplicationStatus | 'open') => void;
}) {
  const open = (counts.submitted ?? 0) + (counts.needs_info ?? 0) + (counts.verified ?? 0);
  return (
    <>
      <div role="group" aria-label="Filter by status" className="cluster filters">
        <button type="button" aria-pressed={filter === 'open'} onClick={() => onFilter('open')}>
          Open ({open})
        </button>
        {APPLICATION_STATUSES.map((s) => (
          <button key={s} type="button" aria-pressed={filter === s} onClick={() => onFilter(s)}>
            {statusLabel(s)} ({counts[s] ?? 0})
          </button>
        ))}
      </div>
      {items.length === 0 ? (
        <p>No applications here.</p>
      ) : (
        <div className="table-wrap">
        <table aria-label="Applications">
          <thead>
            <tr>
              <th scope="col">Reference</th>
              <th scope="col">Business</th>
              <th scope="col">Contact</th>
              <th scope="col">Wanted</th>
              <th scope="col">Status</th>
              <th scope="col">Applied</th>
            </tr>
          </thead>
          <tbody>
            {items.map((a) => (
              <tr key={a.id}>
                <td>
                  <Link to="/platform/applications/$applicationId" params={{ applicationId: a.id ?? '' }}>
                    {a.reference}
                  </Link>
                </td>
                <td>{a.business_name}</td>
                <td>
                  {a.contact_name}
                  <br />
                  <small>{a.contact_phone}</small>
                </td>
                <td>
                  {(a.modules ?? []).join(', ')}, {a.way_in === 'paid' ? 'subscribe now' : 'free month'}
                </td>
                <td>{statusLabel(a.status)}</td>
                <td>{a.created_at?.slice(0, 10)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}
    </>
  );
}

function Applications() {
  const [filter, setFilter] = useState<ApplicationStatus | 'open'>('open');
  const statuses: ApplicationStatus[] = filter === 'open' ? ['submitted', 'needs_info', 'verified'] : [filter];
  const query = useQuery({ queryKey: ['applications', filter], queryFn: () => listApplications(statuses) });
  return (
    <main>
      <h1>Applications</h1>
      {query.isPending && <p role="status" className="loading">Loading</p>}
      {query.isError && (
        <p role="alert" className="alert alert-danger">
          {query.error.message}
        </p>
      )}
      {query.data && (
        <ApplicationsTable
          items={query.data.items ?? []}
          counts={(query.data.counts ?? {}) as Record<string, number>}
          filter={filter}
          onFilter={setFilter}
        />
      )}
    </main>
  );
}

export const Route = createLazyRoute('/platform/')({ component: Applications });
