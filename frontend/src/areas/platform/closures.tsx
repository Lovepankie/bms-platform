import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, Link } from '@tanstack/react-router';
import { useState } from 'react';
import { type PlatformClosure, platformClosures, removeClosedBusiness } from '../../api/closure';
import { Breadcrumbs } from '../../components/breadcrumbs';
import { ConfirmPanel, TourButton } from './operator-parts';
import { CODE_PROBLEM, formatDay, formatMoment } from './operator-state';

// Businesses closing (issue #571, ADR-090, FR-TEN-09): every Owner's request to close their business, the soonest removable first.
// A request is removable once its cooling-off period is over; until then its Owner may cancel it. Removing asks for the
// operator's own code and a reason, says plainly what is deleted and what is kept, and cannot be undone.

/** The state of one request, in words. */
export function closureLine(c: PlatformClosure): string {
  if (c.status === 'removed') {
    return `Removed on ${formatMoment(c.removed_at)}${c.removed_by_name ? ` by ${c.removed_by_name}` : ''}. Money records kept until ${formatDay(c.keep_until)}.`;
  }
  if (c.status === 'cancelled') return `Cancelled by the owner on ${formatMoment(c.cancelled_at)}.`;
  if (c.status === 'removing') return 'The removal was begun and did not finish. Remove it again to finish it.';
  if (c.status === 'purged') return `Removed on ${formatMoment(c.removed_at)}; the kept money records were deleted after ${formatDay(c.keep_until)}.`;
  if (c.removable) return `Asked for on ${formatMoment(c.requested_at)} by ${c.requested_by_name ?? 'the owner'}. The waiting period is over: it can be removed.`;
  return `Asked for on ${formatMoment(c.requested_at)} by ${c.requested_by_name ?? 'the owner'}. It can be removed from ${formatDay(c.remove_after)}.`;
}

/** The sentence of the confirm. */
export function removeSentence(c: PlatformClosure): string {
  return (
    `Remove ${c.tenant_name ?? 'this business'} for good? Its staff can no longer sign in. Names, phone numbers, ID numbers, emails and ` +
    'every stored file but the invoices and receipts are deleted or replaced. Money records stay read only, without names, for five years after the end of the financial year, then deleted. ' +
    'This cannot be undone.'
  );
}

function Row({ c }: { c: PlatformClosure }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState('');
  const [codeProblem, setCodeProblem] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const remove = useMutation({
    mutationFn: (reason: string) => removeClosedBusiness(c.id ?? '', code.trim(), reason),
    onSuccess: (r) => {
      setOpen(false);
      setCode('');
      setDone(`Removed. Money records are kept until ${formatDay(r.keep_until)}.`);
      void queryClient.invalidateQueries({ queryKey: ['platform-closures'] });
    },
  });
  const open_ = c.status === 'requested' || c.status === 'removing';
  const badge = open_ ? (c.removable ? 'badge badge-danger' : 'badge badge-warning') : 'badge';
  const label = open_ ? (c.removable ? 'Can be removed' : 'Waiting') : c.status === 'cancelled' ? 'Cancelled' : 'Removed';
  return (
    <li className="ln-item">
      <p className="ln-item-head">
        {c.status === 'removed' || c.status === 'purged' ? (
          <strong>{c.tenant_name}</strong>
        ) : (
          <Link to="/platform/businesses/$tenantId" params={{ tenantId: c.tenant_id ?? '' }}>
            <strong>{c.tenant_name}</strong>
          </Link>
        )}{' '}
        <span className={badge}>{label}</span>
      </p>
      <p>{closureLine(c)}</p>
      {done && <p role="status">{done}</p>}
      {c.removable && !open && (
        <p>
          <button type="button" /* busy-exempt: opens the confirm, sends nothing */ className="btn-danger" data-tour="closures-remove" onClick={() => setOpen(true)}>
            Remove the business
          </button>
        </p>
      )}
      {open && (
        <ConfirmPanel
          id={`remove-${c.id}`}
          sentence={removeSentence(c)}
          confirm="Yes, remove it"
          danger
          busy={remove.isPending}
          error={codeProblem ?? (remove.error instanceof Error ? remove.error.message : null)}
          onConfirm={(reason) => {
            if (!/^\d{6}$/.test(code.trim())) {
              setCodeProblem(CODE_PROBLEM);
              return;
            }
            setCodeProblem(null);
            remove.mutate(reason);
          }}
          onCancel={() => setOpen(false)}
        >
          <div>
            <label htmlFor={`remove-code-${c.id}`}>Code from your own authenticator app</label>
            <input
              id={`remove-code-${c.id}`}
              className="input-code"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
            />
          </div>
        </ConfirmPanel>
      )}
    </li>
  );
}

export function ClosuresPage() {
  const list = useQuery({ queryKey: ['platform-closures'], queryFn: platformClosures });
  const items = list.data ?? [];
  return (
    <main>
      <Breadcrumbs items={[{ label: 'Operator portal', to: '/platform' }, { label: 'Businesses closing' }]} />
      <div className="page-header">
        <h1>Businesses closing</h1>
        <TourButton />
      </div>
      <p className="lead" data-tour="closures-intro">
        An owner who closes their business asks here. The business is read only at once. After the waiting period you remove it; until then
        the owner can cancel.
      </p>
      {list.isPending && (
        <p role="status" className="loading">
          Loading
        </p>
      )}
      {list.isError && (
        <p role="alert" className="alert alert-danger">
          Could not load the list.
        </p>
      )}
      {list.data && items.length === 0 && <p>No business has asked to close.</p>}
      {items.length > 0 && (
        <ul className="ln-list" aria-label="Businesses closing" data-tour="closures-list">
          {items.map((c) => (
            <Row key={c.id} c={c} />
          ))}
        </ul>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/platform/closures')({ component: ClosuresPage });
