import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, Link } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { type BillingSettings, billingSettings, dueForSuspension, type DueItem, saveBillingSettings } from '../../api/billing';
import { BusyButton } from '../../app/ui/BusyButton';
import { Breadcrumbs } from '../../components/breadcrumbs';
import { formatMinor } from '../../components/money';
import { TourButton } from './operator-parts';
import { formatDay, formatMoment } from './operator-state';

// Due for suspension (issue #480, ADR-075, FR-SUB-09, FR-SUB-10): the businesses whose subscription is overdue past the grace
// period, most overdue first, and the grace period and the words on how to pay that every reminder and the Owner's page show.
// Nothing here suspends: the operator opens the business and suspends it there, with a reason.

export function dueLine(d: DueItem): string {
  const days = d.days_overdue === 1 ? '1 day' : `${d.days_overdue} days`;
  const amount = d.amount_due_minor == null ? 'no price entered for its plan' : `${formatMinor(d.amount_due_minor, d.currency ?? 'UGX')} a month`;
  return `Overdue ${days}, since ${formatDay(d.overdue_since)}; grace ended ${formatDay(d.grace_ended_on)}; ${amount}.`;
}

export function DueCards({ items }: { items: DueItem[] }) {
  if (items.length === 0) return <p>No business is past its grace period.</p>;
  return (
    <ul className="ln-list" aria-label="Businesses due for suspension" data-tour="due-list">
      {items.map((d) => (
        <li key={d.tenant_id} className="ln-item">
          <p className="ln-item-head">
            <Link to="/platform/businesses/$tenantId" params={{ tenantId: d.tenant_id ?? '' }}>
              <strong>{d.name}</strong>
            </Link>{' '}
            <span className="badge badge-danger">Overdue</span>
          </p>
          <p>{dueLine(d)}</p>
          <p className="hint">
            {d.plan_name}
            {d.owner_name ? `, owner ${d.owner_name}` : ''}
            {d.owner_email ? ` (${d.owner_email})` : ''}
            {d.last_sign_in_at ? `, last sign-in ${formatMoment(d.last_sign_in_at)}` : ', nobody has signed in'}
          </p>
        </li>
      ))}
    </ul>
  );
}

function SettingsForm({ current }: { current: BillingSettings }) {
  const queryClient = useQueryClient();
  const [grace, setGrace] = useState(String(current.grace_days ?? 7));
  const [howToPay, setHowToPay] = useState(current.how_to_pay ?? '');
  const [reason, setReason] = useState('');
  const [done, setDone] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: () => saveBillingSettings({ grace_days: Number(grace), how_to_pay: howToPay, reason: reason.trim() }),
    onSuccess: (saved) => {
      queryClient.setQueryData(['billing-settings'], saved);
      void queryClient.invalidateQueries({ queryKey: ['due-for-suspension'] });
      setReason('');
      setDone(`Saved. The grace period is ${saved.grace_days} days.`);
    },
  });
  const days = Number(grace);
  const valid = /^\d{1,2}$/.test(grace) && days >= 0 && days <= 60 && reason.trim().length >= 5;

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    setDone(null);
    if (valid) save.mutate();
  }

  return (
    <form className="card form-stack" onSubmit={onSubmit} aria-labelledby="billing-settings-heading" data-tour="billing-settings">
      <h2 id="billing-settings-heading">Grace period and how to pay</h2>
      <label>
        Days an overdue business keeps working before it is listed here (0 to 60)
        <input inputMode="numeric" value={grace} onChange={(e) => setGrace(e.target.value)} />
      </label>
      <label>
        How to pay, shown in every reminder and on the owner's Plan and billing page
        <textarea rows={4} maxLength={1000} value={howToPay} onChange={(e) => setHowToPay(e.target.value)} />
      </label>
      <label>
        Why (kept in the history)
        <input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} />
      </label>
      {save.isError && (
        <p role="alert" className="alert alert-danger">
          {save.error instanceof Error ? save.error.message : 'Could not save.'}
        </p>
      )}
      {done && <p role="status">{done}</p>}
      <BusyButton type="submit" className="btn-primary" busy={save.isPending} notReady={!valid}>
        Save
      </BusyButton>
    </form>
  );
}

export function DueForSuspensionPage() {
  const list = useQuery({ queryKey: ['due-for-suspension'], queryFn: dueForSuspension });
  const settings = useQuery({ queryKey: ['billing-settings'], queryFn: billingSettings });
  return (
    <main>
      <Breadcrumbs items={[{ label: 'Operator portal', to: '/platform' }, { label: 'Due for suspension' }]} />
      <div className="page-header">
        <h1>Due for suspension</h1>
        <TourButton />
      </div>
      <p className="lead" data-tour="due-intro">
        Businesses whose subscription is overdue past the grace period. Nothing is suspended automatically: open the business, check, and
        suspend it there with a reason. A business that pays comes off this list when the payment is recorded.
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
      {list.data && <DueCards items={list.data.items ?? []} />}
      {settings.data && <SettingsForm current={settings.data} />}
    </main>
  );
}

export const Route = createLazyRoute('/platform/billing')({ component: DueForSuspensionPage });
