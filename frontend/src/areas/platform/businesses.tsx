import { useQuery } from '@tanstack/react-query';
import { createLazyRoute, Link } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { type Business, listBusinesses, type SubscriptionStatus } from '../../api/operator';
import { Breadcrumbs } from '../../components/breadcrumbs';
import { TourButton } from './operator-parts';
import {
  formatDay,
  formatMoment,
  MODULES,
  businessTypeLabel,
  moduleLabel,
  STATUSES,
  statusBadge,
  statusLabel,
  subscriptionLine,
  todayInKampala,
} from './operator-state';

// Businesses (issue #407): every tenant on the platform with its status, plan, modules, where its subscription stands, its owner
// and when its staff last signed in, searched by name, address or owner email and filtered by status and module. One card per
// business, so the list reads on a phone; the name opens the Business page.

export function BusinessCards({ items, today }: { items: Business[]; today: string }) {
  if (items.length === 0) return <p>No business matches. Clear the search or the filters.</p>;
  return (
    <ul className="ln-list" aria-label="Businesses" data-tour="biz-list">
      {items.map((b, i) => (
        <li key={b.id}>
          <div className="ln-item">
            <p className="ln-item-head">
              <Link
                to="/platform/businesses/$tenantId"
                params={{ tenantId: b.id ?? '' }}
                data-tour={i === 0 ? 'biz-open' : undefined}
              >
                <strong>{b.name}</strong>
              </Link>
              <span className={statusBadge(b.subscription_status)}>{statusLabel(b.subscription_status)}</span>
            </p>
            <p className="ln-muted">{subscriptionLine(b, today)}</p>
            <p className="ln-item-facts">
              <span>Plan: {b.plan_name ?? b.plan_code}</span>
              <span>Modules: {(b.modules ?? []).map(moduleLabel).join(', ') || 'none'}</span>
              {businessTypeLabel(b) && <span>Kind of business: {businessTypeLabel(b)}</span>}
              <span>Owner: {b.owner_email ?? 'not set'}</span>
              <span>Last sign-in: {b.last_sign_in_at ? formatMoment(b.last_sign_in_at) : 'never'}</span>
              <span>Since {formatDay(b.created_at)}</span>
            </p>
            {b.address && (
              <p className="ln-muted wrap-anywhere">
                <a href={b.address} target="_blank" rel="noreferrer">
                  {b.address.replace(/^https?:\/\//, '')}
                </a>
              </p>
            )}
          </div>
        </li>
      ))}
    </ul>
  );
}

export interface FilterBarProps {
  q: string;
  status: SubscriptionStatus | 'all';
  module: string;
  onQ: (q: string) => void;
  onStatus: (s: SubscriptionStatus | 'all') => void;
  onModule: (m: string) => void;
}

export function FilterBar({ q, status, module, onQ, onStatus, onModule }: FilterBarProps) {
  return (
    <div className="form-stack">
      <div data-tour="biz-search">
        <label htmlFor="biz-q">Search by name, address or owner email</label>
        <input id="biz-q" type="search" value={q} maxLength={100} onChange={(e) => onQ(e.target.value)} autoComplete="off" />
      </div>
      <div className="cluster" data-tour="biz-filters">
        <div>
          <label htmlFor="biz-status">Status</label>
          <select id="biz-status" value={status} onChange={(e) => onStatus(e.target.value as SubscriptionStatus | 'all')}>
            <option value="all">All</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {statusLabel(s)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="biz-module">Module</label>
          <select id="biz-module" value={module} onChange={(e) => onModule(e.target.value)}>
            <option value="">All</option>
            {MODULES.map((m) => (
              <option key={m.key} value={m.key}>
                {m.label}
              </option>
            ))}
          </select>
        </div>
      </div>
    </div>
  );
}

export function Businesses() {
  const [q, setQ] = useState('');
  const [typed, setTyped] = useState('');
  const [status, setStatus] = useState<SubscriptionStatus | 'all'>('all');
  const [module, setModule] = useState('');
  useEffect(() => {
    const timer = window.setTimeout(() => setQ(typed), 300);
    return () => window.clearTimeout(timer);
  }, [typed]);
  const query = useQuery({
    queryKey: ['businesses', q, status, module],
    queryFn: () => listBusinesses({ q, statuses: status === 'all' ? [] : [status], module }),
  });
  const today = todayInKampala();
  return (
    <main>
      <Breadcrumbs items={[{ label: 'Operator portal', to: '/platform' }, { label: 'Businesses' }]} />
      <div className="page-header">
        <h1>Businesses</h1>
        <TourButton />
      </div>
      <p className="lead">
        Every business on the platform. Open one to change its plan, record a payment, suspend or resume it, or help its owner sign
        in. Prices are on <Link to="/platform/plans">Plans and prices</Link>; new businesses come from{' '}
        <Link to="/platform">Applications</Link>.
      </p>
      <FilterBar q={typed} status={status} module={module} onQ={setTyped} onStatus={setStatus} onModule={setModule} />
      {query.isPending && (
        <p role="status" className="loading">
          Loading
        </p>
      )}
      {query.isError && (
        <p role="alert" className="alert alert-danger">
          {query.error.message}
        </p>
      )}
      {query.data && (
        <>
          <p className="muted" role="status">
            {query.data.length === 1 ? '1 business' : `${query.data.length} businesses`}
          </p>
          <BusinessCards items={query.data} today={today} />
        </>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/platform/businesses')({ component: Businesses });
