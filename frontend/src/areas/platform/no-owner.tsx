import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, Link } from '@tanstack/react-router';
import { useState } from 'react';
import { type OwnerCandidate, type TenantWithoutOwner, tenantsWithoutOwner } from '../../api/closure';
import { makeOwner } from '../../api/operator';
import { Breadcrumbs } from '../../components/breadcrumbs';
import { ConfirmPanel, TourButton } from './operator-parts';
import { CODE_PROBLEM, formatMoment } from './operator-state';

// Businesses with no owner (issue #571, ADR-062 decision 7, FR-IAM-18): a business whose owner was never set, with its active
// administrators. Set the owner makes one of them the owner, with the operator's own code and a reason, kept in both histories.
// A business with no active administrator needs a new invitation from its Business page first.

/** The line under an administrator. */
export function candidateLine(a: OwnerCandidate): string {
  return `${a.email ?? 'no email'}, ${a.last_login_at ? `last signed in ${formatMoment(a.last_login_at)}` : 'never signed in'}`;
}

function Business({ t }: { t: TenantWithoutOwner }) {
  const queryClient = useQueryClient();
  const [chosen, setChosen] = useState<OwnerCandidate | null>(null);
  const [code, setCode] = useState('');
  const [codeProblem, setCodeProblem] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const set = useMutation({
    mutationFn: (reason: string) => makeOwner(t.tenant_id ?? '', chosen?.id ?? '', code.trim(), reason),
    onSuccess: () => {
      setDone(`${chosen?.full_name ?? 'The administrator'} is now the owner of ${t.name ?? 'the business'}.`);
      setChosen(null);
      setCode('');
      void queryClient.invalidateQueries({ queryKey: ['platform-no-owner'] });
    },
  });
  const admins = t.admins ?? [];
  return (
    <li className="ln-item">
      <p className="ln-item-head">
        <Link to="/platform/businesses/$tenantId" params={{ tenantId: t.tenant_id ?? '' }}>
          <strong>{t.name}</strong>
        </Link>{' '}
        {t.status === 'suspended' && <span className="badge">Suspended</span>}
      </p>
      {done && <p role="status">{done}</p>}
      {admins.length === 0 ? (
        <p>No active administrator. Open the business and send its first administrator a new invitation.</p>
      ) : (
        <ul className="stack" style={{ listStyle: 'none', padding: 0 }} aria-label={`Administrators of ${t.name ?? 'the business'}`}>
          {admins.map((a) => (
            <li key={a.id}>
              <p>
                <strong>{a.full_name}</strong>
              </p>
              <p className="hint">{candidateLine(a)}</p>
              {chosen?.id !== a.id && (
                <button
                  type="button" /* busy-exempt: opens the confirm, sends nothing */
                  data-tour="no-owner-set"
                  onClick={() => {
                    setChosen(a);
                    setDone(null);
                    set.reset();
                  }}
                >
                  Set as the owner
                </button>
              )}
              {chosen?.id === a.id && (
                <ConfirmPanel
                  id={`owner-${a.id}`}
                  sentence={`Make ${a.full_name ?? 'this administrator'} the owner of ${t.name ?? 'the business'}? Only the owner can then add, remove or change an administrator, change the plan or close the business.`}
                  confirm="Yes, make them the owner"
                  busy={set.isPending}
                  error={codeProblem ?? (set.error instanceof Error ? set.error.message : null)}
                  onConfirm={(reason) => {
                    if (!/^\d{6}$/.test(code.trim())) {
                      setCodeProblem(CODE_PROBLEM);
                      return;
                    }
                    setCodeProblem(null);
                    set.mutate(reason);
                  }}
                  onCancel={() => setChosen(null)}
                >
                  <div>
                    <label htmlFor={`owner-code-${a.id}`}>Code from your own authenticator app</label>
                    <input
                      id={`owner-code-${a.id}`}
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
          ))}
        </ul>
      )}
    </li>
  );
}

export function NoOwnerPage() {
  const list = useQuery({ queryKey: ['platform-no-owner'], queryFn: tenantsWithoutOwner });
  const items = list.data ?? [];
  return (
    <main>
      <Breadcrumbs items={[{ label: 'Operator portal', to: '/platform' }, { label: 'Businesses with no owner' }]} />
      <div className="page-header">
        <h1>Businesses with no owner</h1>
        <TourButton />
      </div>
      <p className="lead" data-tour="no-owner-intro">
        Until a business has an owner, nobody in it can change the plan, close it or manage its administrators safely. Set one of its
        administrators as the owner.
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
      {list.data && items.length === 0 && <p>Every business has an owner.</p>}
      {items.length > 0 && (
        <ul className="ln-list" aria-label="Businesses with no owner" data-tour="no-owner-list">
          {items.map((t) => (
            <Business key={t.tenant_id} t={t} />
          ))}
        </ul>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/platform/no-owner')({ component: NoOwnerPage });
