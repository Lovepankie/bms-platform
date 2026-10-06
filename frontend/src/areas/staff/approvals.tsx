import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { type Approval, api, listPendingApprovals, problemOf } from '../../api/client';
import { branchFilter } from '../../auth/branch';
import { formatMinor } from '../../components/money';
import { useStaff } from './context';

// The approvals inbox (FR-APR-05, FR-APR-06): pending requests the user may decide or made, in
// the active branch. Approve executes the action at once; reject needs a note. The maker sees
// their own requests and may cancel them, but never decide them (FR-APR-02).

function Row({ item, id }: { item: Approval; id: string }) {
  const queryClient = useQueryClient();
  const [note, setNote] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const decide = useMutation({
    mutationFn: async (kind: 'approve' | 'reject' | 'cancel') => {
      const path = { params: { path: { approval_id: id } } };
      let error: unknown;
      if (kind === 'approve') {
        ({ error } = await api.POST('/api/v1/approvals/{approval_id}/approve', {
          ...path,
          body: { note: note || undefined },
        }));
      } else if (kind === 'reject') {
        ({ error } = await api.POST('/api/v1/approvals/{approval_id}/reject', { ...path, body: { note } }));
      } else {
        ({ error } = await api.POST('/api/v1/approvals/{approval_id}/cancel', path));
      }
      if (error) throw error;
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['approvals'] }),
    onError: (error) => setMessage(problemOf(error).detail ?? 'The decision failed.'),
  });

  return (
    <tr>
      <td>{item.action_type}</td>
      <td className="num">{item.amount_minor != null && item.currency ? formatMinor(item.amount_minor, item.currency) : ''}</td>
      <td>{item.requested_by_name ?? item.requested_by}</td>
      <td>{item.requested_at ? new Date(item.requested_at).toLocaleString() : ''}</td>
      <td>
        <div className="decision">
          {item.can_decide && (
            <>
              <input placeholder="Note (required to reject)" aria-label="Note" value={note} onChange={(e) => setNote(e.target.value)} />{' '}
              <button className="btn-primary" disabled={decide.isPending} onClick={() => decide.mutate('approve')}>
                Approve
              </button>{' '}
              <button className="btn-danger" disabled={decide.isPending || note.trim() === ''} onClick={() => decide.mutate('reject')}>
                Reject
              </button>
            </>
          )}
          {item.can_cancel && (
            <button disabled={decide.isPending} onClick={() => decide.mutate('cancel')}>
              Cancel my request
            </button>
          )}
          {item.execution_error && <p role="alert" className="alert alert-danger">Last attempt failed: {item.execution_error}</p>}
          {message && <p role="alert" className="alert alert-danger">{message}</p>}
        </div>
      </td>
    </tr>
  );
}

function Approvals() {
  const { branch } = useStaff();
  const pending = useQuery({
    queryKey: ['approvals', branch],
    queryFn: () => listPendingApprovals(branchFilter(branch)),
    enabled: branch !== null,
  });
  const items = pending.data?.items ?? [];

  return (
    <main>
      <h1>Approvals</h1>
      {pending.isPending && <p className="loading">Loading</p>}
      {pending.isError && <p role="alert" className="alert alert-danger">Could not load approvals.</p>}
      {pending.data && items.length === 0 && <p className="empty-state">Nothing waiting.</p>}
      {items.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Action</th>
                <th className="num">Amount</th>
                <th>Requested by</th>
                <th>Requested at</th>
                <th>Decision</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => item.id && <Row key={item.id} item={item} id={item.id} />)}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff/approvals')({ component: Approvals });
