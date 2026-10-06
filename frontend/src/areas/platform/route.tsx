import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, Link, Outlet, useNavigate } from '@tanstack/react-router';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { api } from '../../api/client';
import { platformMe } from '../../api/onboarding';
import { getAccessToken, refreshSession, setAccessToken, setSessionScope, subscribe } from '../../auth/session';

// The operator portal (ADR-024 decision 8, spec section 8) on the platform host: a protected area
// for platform operators only. It restores the operator session from the platform refresh cookie
// and sends anyone without one to /platform/sign-in. The API decides every permission.

setSessionScope('platform');

function PortalLayout() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const token = useSyncExternalStore(subscribe, getAccessToken);
  const [restoring, setRestoring] = useState(token === null);

  useEffect(() => {
    setSessionScope('platform');
    if (token !== null) return;
    void refreshSession().then((ok) => {
      setRestoring(false);
      if (!ok) void navigate({ to: '/platform/sign-in' });
    });
  }, [token, navigate]);

  const me = useQuery({ queryKey: ['platform-me', token], queryFn: platformMe, enabled: token !== null });

  async function signOut() {
    await api.POST('/api/v1/platform/auth/logout');
    setAccessToken(null);
    queryClient.clear();
    await navigate({ to: '/platform/sign-in' });
  }

  if (restoring || !me.data) {
    return me.isError ? (
      <p role="alert" className="alert alert-danger">
        Could not load your profile. Sign in again.
      </p>
    ) : (
      <p role="status" className="loading">
        Loading
      </p>
    );
  }
  return (
    <div>
      <header className="staff-bar">
        <div className="staff-bar-top">
          <span className="staff-user">
            <span className="staff-user-name">{me.data.full_name}</span>
          </span>
          <button type="button" className="btn-ghost btn-sm" onClick={() => void signOut()}>
            Sign out
          </button>
        </div>
        <nav className="tabs" aria-label="Operator portal">
          <Link to="/platform" activeOptions={{ exact: true }}>
            Applications
          </Link>
          <Link to="/platform/outbox">Messages not sent</Link>
        </nav>
      </header>
      <Outlet />
    </div>
  );
}

export const Route = createLazyRoute('/platform')({ component: PortalLayout });
