import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, Outlet, useNavigate } from '@tanstack/react-router';
import { type ReactNode, useEffect, useState, useSyncExternalStore } from 'react';
import { api } from '../../api/client';
import { platformMe } from '../../api/onboarding';
import { icons } from '../../components/icons';
import { type MenuLink, SideMenu, SideNav, useIsPhone } from '../../components/side-menu';
import { getAccessToken, refreshSession, setAccessToken, setSessionScope, subscribe } from '../../auth/session';
import { TourProvider } from '../../tour/host';
import { OPERATOR } from '../../tour/tours-operator';

// The operator portal (ADR-024 decision 8, spec section 8) on the platform host: a protected area
// for platform operators only. It restores the operator session from the platform refresh cookie
// and sends anyone without one to /platform/sign-in. The API decides every permission.

setSessionScope('platform');

// The portal's areas, in the same side menu as the staff area's: a sidebar from 720px, a drawer on a phone (issue #455).
const AREAS: MenuLink[] = [
  { to: '/platform', label: 'Applications', icon: icons.documents, exact: true },
  { to: '/platform/businesses', label: 'Businesses', icon: icons.building },
  { to: '/platform/plans', label: 'Plans and prices', icon: icons.priceLists },
  { to: '/platform/outbox', label: 'Messages not sent', icon: icons.bell },
  { to: '/platform/feedback', label: 'Feedback', icon: icons.chat },
];

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
  // The operator's page tours (issue #407) start only from the page's own "Show me this page" button and save nothing: an
  // operator is not a tenant user, so there is no tour progress to keep, and no staff tour may start by itself here.
  return (
    <TourProvider me={OPERATOR} saveProgress={false}>
      <PortalFrame name={me.data.full_name} onSignOut={() => void signOut()}>
        <Outlet />
      </PortalFrame>
    </TourProvider>
  );
}

/** The portal's side menu, its bar with the operator's name and Sign out, and the page beside them (issue #455). */
export function PortalFrame({ name, onSignOut, children }: { name?: string; onSignOut: () => void; children: ReactNode }) {
  const phone = useIsPhone();
  return (
    <div className="nav-layout">
      {!phone && <SideNav label="Operator portal" links={AREAS} />}
      <div className="nav-layout-main">
        <header className="staff-bar">
          <div className="staff-bar-top">
            {phone && <SideMenu label="Operator portal" links={AREAS} name={name} onSignOut={onSignOut} />}
            <span className="staff-user">
              <span className="staff-user-name">{name}</span>
            </span>
            {!phone && (
              <button type="button" className="btn-ghost btn-sm" onClick={onSignOut}>
                Sign out
              </button>
            )}
          </div>
        </header>
        {children}
      </div>
    </div>
  );
}

export const Route = createLazyRoute('/platform')({ component: PortalLayout });
