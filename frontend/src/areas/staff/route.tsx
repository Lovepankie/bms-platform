import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Outlet, createLazyRoute, useNavigate, useRouterState } from '@tanstack/react-router';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { api, fetchMe, fetchSettings, type Me } from '../../api/client';
import { loadRetailMock, retailMockEnabled } from '../../api/retail';
import { ALL_BRANCHES, initialBranch, loadBranch, saveBranch } from '../../auth/branch';
import { getAccessToken, refreshSession, setAccessToken, subscribe } from '../../auth/session';
import { StaffContext } from './context';
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
  const [branch, setBranch] = useState<string | null>(null);
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const managesSettings = (me.data?.permissions ?? []).includes('core.settings.manage');
  const settings = useQuery({ queryKey: ['settings'], queryFn: fetchSettings, enabled: managesSettings && !mock });

  useEffect(() => {
    const home = pathname === '/staff' || pathname === '/staff/';
    if (setupShown || !home || !settings.data || settings.data.setup_dismissed) return;
    setupShown = true;
    void navigate({ to: '/staff/setup', replace: true });
  }, [pathname, settings.data, navigate]);

  useEffect(() => {
    if (me.data) setBranch(initialBranch(me.data, loadBranch(me.data.user_id ?? '')));
  }, [me.data]);

  if (restoring || !me.data) {
    return <p>{me.isError ? 'Could not load your profile.' : 'Loading'}</p>;
  }

  const profile = me.data;
  if (mock) void loadRetailMock().then((m) => m.setMockProfitAccess(canSeeProfit(profile)));
  function choose(selection: string) {
    setBranch(selection);
    saveBranch(profile.user_id ?? '', selection);
  }

  async function signOut() {
    await api.POST('/api/v1/auth/logout');
    setAccessToken(null);
    queryClient.clear();
    await navigate({ to: '/sign-in' });
  }

  const canSeeApprovals = (profile.permissions ?? []).includes('core.approvals.read');
  const recoveryCodesLeft = profile.unused_recovery_codes ?? 0;

  return (
    <StaffContext.Provider value={{ me: profile, branch }}>
      <header style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap', marginBottom: 16 }}>
        <strong>{profile.full_name}</strong>
        <label>
          Branch{' '}
          <select value={branch ?? ''} onChange={(e) => choose(e.target.value)}>
            {profile.all_branches && <option value={ALL_BRANCHES}>All branches</option>}
            {(profile.branches ?? []).map((b) => (
              <option key={b.id} value={b.id}>
                {b.code} {b.name}
              </option>
            ))}
          </select>
        </label>
        <nav style={{ display: 'flex', gap: 12 }}>
          <Link to="/staff">Home</Link>
          {canSeeApprovals && <Link to="/staff/approvals">Approvals</Link>}
          {showRetail(profile) && <Link to="/staff/retail">Retail</Link>}
          {managesSettings && <Link to="/staff/setup">Business set-up</Link>}
        </nav>
        <button onClick={() => void signOut()}>Sign out</button>
      </header>
      {profile.mfa_enabled && recoveryCodesLeft <= 2 && (
        <p role="status">
          You have {recoveryCodesLeft} recovery codes left. Ask an admin to reset your MFA if you run out.
        </p>
      )}
      <Outlet />
    </StaffContext.Provider>
  );
}

export const Route = createLazyRoute('/staff')({ component: StaffLayout });
