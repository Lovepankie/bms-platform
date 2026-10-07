import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Outlet, createLazyRoute, useNavigate, useRouterState } from '@tanstack/react-router';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { api, fetchMe, fetchSettings, type Me } from '../../api/client';
import { loadRetailMock, retailMockEnabled } from '../../api/retail';
import { getAccessToken, refreshSession, setAccessToken, subscribe } from '../../auth/session';
import { HelpMenu, TourProvider } from '../../tour/host';
import { icons } from '../../components/icons';
import { BrandLoader } from '../../components/states';
import { BranchPicker, useActiveBranch } from './branch-picker';
import { StaffContext } from './context';
import { showLending } from './lending/permissions';
import { showSavings } from './lending/savings-permissions';
import { RetailNav } from './retail/nav';
import { canSeeProfit, showRetail } from './retail/permissions';

// The staff area layout: restores the session from the refresh cookie, shows who is signed in,
// and holds the branch switcher (FR-BR-03). Switching the branch changes what lists show without
// signing out or changing the URL. What a user cannot do is hidden using /me; the API decides.

function useAccessToken() {
  return useSyncExternalStore(subscribe, getAccessToken);
}

// A tenant admin who has not dismissed the set-up checklist lands on it once per page load.
let setupShown = false;

function StaffLayout() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const token = useAccessToken();
  // VITE_RETAIL_MOCK=1: a fake session so the retail screens run without a backend.
  const mock = retailMockEnabled();
  const [restoring, setRestoring] = useState(token === null && !mock);

  useEffect(() => {
    if (token !== null || mock) return;
    void refreshSession().then((ok) => {
      setRestoring(false);
      if (!ok) void navigate({ to: '/sign-in' });
    });
  }, [token, navigate, mock]);

  const me = useQuery({ queryKey: ['me', token], queryFn: (): Promise<Me> => (mock ? loadRetailMock().then((m) => m.mockMe(import.meta.env.VITE_RETAIL_MOCK_ROLE)) : fetchMe()),
    enabled: token !== null || mock,
  });
  const { branch, choose, ready } = useActiveBranch(me.data);
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const managesSettings = (me.data?.permissions ?? []).includes('core.settings.manage');
  const settings = useQuery({ queryKey: ['settings'], queryFn: fetchSettings, enabled: managesSettings && !mock });

  useEffect(() => {
    const home = pathname === '/staff' || pathname === '/staff/';
    if (setupShown || !home || !settings.data || settings.data.setup_dismissed) return;
    setupShown = true;
    void navigate({ to: '/staff/setup', replace: true });
  }, [pathname, settings.data, navigate]);

  if (restoring || !me.data || !ready) {
    return me.isError ? <p role="alert" className="alert alert-danger">Could not load your profile.</p> : <BrandLoader />;
  }

  const profile = me.data;
  if (mock) void loadRetailMock().then((m) => m.setMockProfitAccess(canSeeProfit(profile)));
  async function signOut() {
    await api.POST('/api/v1/auth/logout');
    setAccessToken(null);
    queryClient.clear();
    await navigate({ to: '/sign-in' });
  }

  const recoveryCodesLeft = profile.unused_recovery_codes ?? 0;

  return (
    <StaffContext.Provider value={{ me: profile, branch, chooseBranch: choose }}>
      <TourProvider me={profile} saveProgress={!mock}>
        <StaffBar me={profile} branch={branch} onBranch={choose} onSignOut={() => void signOut()} />
        {profile.mfa_enabled && recoveryCodesLeft <= 2 && (
          <p role="status" className="alert alert-warning">
            You have {recoveryCodesLeft} recovery codes left. Ask an admin to reset your MFA if you run out.
          </p>
        )}
        {pathname.startsWith('/staff/retail') && showRetail(profile) && <RetailNav me={profile} />}
        <Outlet />
      </TourProvider>
    </StaffContext.Provider>
  );
}

/**
 * Who is signed in, Help, the branch switcher, sign out and the area tabs. Carries the data-tour
 * anchors the first-run tours light up (tour/tours.ts).
 */
export function StaffBar({ me, branch, onBranch, onSignOut }: { me: Me; branch: string | null; onBranch: (selection: string) => void; onSignOut: () => void }) {
  const permissions = me.permissions ?? [];
  return (
    <header className="staff-bar">
      <div className="staff-bar-top">
        <span className="staff-user">
          <span className="avatar">{icons.user}</span>
          <span className="staff-user-name">{me.full_name}</span>
        </span>
        <HelpMenu />
        <BranchPicker me={me} branch={branch} onChoose={onBranch} />
        <button className="btn-ghost btn-sm" onClick={onSignOut}>Sign out</button>
      </div>
      <nav className="tabs" aria-label="Staff areas" data-tour="nav-areas">
        <Link to="/staff" activeOptions={{ exact: true }}>Home</Link>
        {permissions.includes('core.approvals.read') && <Link to="/staff/approvals" data-tour="nav-approvals">Approvals</Link>}
        {showLending(me) && <Link to="/staff/lending" activeOptions={{ exact: true }} data-tour="nav-lending">Loans</Link>}
        {showSavings(me) && <Link to="/staff/lending/savings">Savings</Link>}
        {showRetail(me) && <Link to="/staff/retail" data-tour="nav-retail">Retail</Link>}
        {permissions.includes('core.settings.manage') && <Link to="/staff/setup">Business set-up</Link>}
      </nav>
    </header>
  );
}

export const Route = createLazyRoute('/staff')({ component: StaffLayout });
