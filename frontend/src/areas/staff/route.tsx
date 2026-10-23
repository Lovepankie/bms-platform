import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Outlet, createLazyRoute, useNavigate, useRouterState } from '@tanstack/react-router';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { api, fetchMe, fetchSettings, type Me } from '../../api/client';
import { loadRetailMock, retailMockEnabled } from '../../api/retail';
import { BusyButton } from '../../app/ui/BusyButton';
import { endPendingLogout, getAccessToken, logoutPending, markLogoutPending, refreshFailedOffline, refreshSession, setAccessToken, subscribe } from '../../auth/session';
import { OfflineProvider, lastProfile, useOfflineSupport, waitingNow } from '../../offline/offline-context';
import { SignOutWarning } from '../../offline/sign-out-warning';
import { OthersWaitingNote, SyncStatus } from '../../offline/sync-status';
import { InstallPrompt } from '../../install/InstallPrompt';
import { NewLogoCard } from '../../install/NewIcon';
import { createPush } from '../../push/push';
import { TurnOnCard } from '../../push/TurnOnCard';
import { HelpMenu, TourProvider } from '../../tour/host';
import { CrumbProvider, StaffBreadcrumbs } from '../../components/breadcrumbs';
import { icons } from '../../components/icons';
import { SideMenu, SideNav, useIsPhone } from '../../components/side-menu';
import { BrandLoader } from '../../components/states';
import { BranchPicker, CurrentShop, useActiveBranch } from './branch-picker';
import { StaffContext, useOptionalStaff } from './context';
import { STAFF_SECTIONS, staffAreas } from './menu';
import { useSells } from './retail/nav';
import { canSeeProfit } from './retail/permissions';
import { rememberPage } from './feedback/feedback-state';
import { TwoStepNotices } from './two-step/notices';
import { OwnTwoStep } from './two-step/own';
import { mustSetUp } from './two-step/state';

// The staff area layout: restores the session from the refresh cookie, shows who is signed in,
// and holds the branch switcher (FR-BR-03). Switching the branch changes what lists show without
// signing out or changing the URL. What a user cannot do is hidden using /me; the API decides.

function useAccessToken() {
  return useSyncExternalStore(subscribe, getAccessToken);
}

// This device's notifications (ADR-050): registered again under whoever is signed in, and dropped on sign-out.
const push = createPush();

// A tenant admin who has not dismissed the set-up checklist lands on it once per page load.
let setupShown = false;

function StaffLayout() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const token = useAccessToken();
  // VITE_RETAIL_MOCK=1: a fake session so the retail screens run without a backend.
  const mock = retailMockEnabled();
  const [restoring, setRestoring] = useState(token === null && !mock);
  // ADR-067: opened with no network, the last person's saved profile lets them sell; the session is restored when online.
  const [offlineMe, setOfflineMe] = useState<Me | null>(null);
  const [askSignOut, setAskSignOut] = useState(0);
  const [signingOut, setSigningOut] = useState(false);
  const [signOutError, setSignOutError] = useState<string | null>(null);

  useEffect(() => {
    if (token !== null || mock) return;
    if (logoutPending()) {
      // ADR-067 decision 9: an offline sign-out ends its server session first; the app stays on sign-in until it has.
      void endPendingLogout().then(() => navigate({ to: '/sign-in' }));
      return;
    }
    void refreshSession().then(async (ok) => {
      const saved = !ok && refreshFailedOffline() ? await lastProfile() : null;
      setOfflineMe(saved);
      setRestoring(false);
      if (!ok && !saved) void navigate({ to: '/sign-in' });
    });
  }, [token, navigate, mock]);

  const me = useQuery({ queryKey: ['me', token], queryFn: (): Promise<Me> => (mock ? loadRetailMock().then((m) => m.mockMe(import.meta.env.VITE_RETAIL_MOCK_ROLE)) : fetchMe()),
    enabled: token !== null || mock,
  });
  const profileNow = me.data ?? offlineMe ?? undefined;
  const { branch, choose, ready } = useActiveBranch(profileNow);
  const signedIn = me.data !== undefined;
  const offline = useOfflineSupport(mock ? undefined : profileNow, signedIn);
  useEffect(() => {
    // Back online after opening offline: restore the session, or ask for a sign-in.
    if (!offline || !offlineMe || token !== null) return;
    return offline.connectivity.subscribe(() => {
      if (!offline.connectivity.online()) return;
      void refreshSession().then((ok) => {
        if (ok) setOfflineMe(null);
        else if (!refreshFailedOffline()) void navigate({ to: '/sign-in' });
      });
    });
  }, [offline, offlineMe, token, navigate]);
  useEffect(() => {
    if (signedIn && !mock) void push.sync();
  }, [signedIn, mock]);
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  // Feedback sends the page the person came from (issue #447).
  useEffect(() => rememberPage(pathname), [pathname]);
  const managesSettings = (me.data?.permissions ?? []).includes('core.settings.manage');
  const settings = useQuery({ queryKey: ['settings'], queryFn: fetchSettings, enabled: managesSettings && !mock });

  useEffect(() => {
    const home = pathname === '/staff' || pathname === '/staff/';
    if (setupShown || !home || !settings.data || settings.data.setup_dismissed) return;
    setupShown = true;
    void navigate({ to: '/staff/setup', replace: true });
  }, [pathname, settings.data, navigate]);

  if (restoring || !profileNow || !ready) {
    return me.isError ? <p role="alert" className="alert alert-danger">Could not load your profile.</p> : <BrandLoader />;
  }

  const profile = profileNow;
  if (mock) void loadRetailMock().then((m) => m.setMockProfitAccess(canSeeProfit(profile)));
  async function signOut(confirmed = false) {
    // ADR-067 decision 9: sales still on this phone are never signed out of without asking.
    if (!confirmed && waitingNow(offline) > 0) {
      setAskSignOut((n) => n + 1);
      return;
    }
    setSigningOut(true);
    setSignOutError(null);
    try {
      // This device stops hearing this person's notifications before the session ends (it needs the session to say so).
      let ended: boolean;
      try {
        if (!mock) await push.forget();
        ended = (await api.POST('/api/v1/auth/logout')).response.ok;
      } catch {
        ended = false;
      }
      // No answer: the server session (its HttpOnly cookie) is still alive. It is ended on the next start before anything
      // restores it; without a place to note that, this person stays signed in (ADR-067 decision 9).
      if (!ended && !mock && !markLogoutPending()) {
        setSignOutError('Connect to the internet to sign out.');
        return;
      }
      await offline?.forget();
      offline?.engine.stop();
      setOfflineMe(null);
      setAccessToken(null);
      queryClient.clear();
      await navigate({ to: '/sign-in' });
    } finally {
      setSigningOut(false);
    }
  }

  const recoveryCodesLeft = profile.unused_recovery_codes ?? 0;

  return (
    <StaffContext.Provider value={{ me: profile, branch, chooseBranch: choose }}>
      <OfflineProvider value={offline}>
      <TourProvider me={profile} saveProgress={!mock}>
        <CrumbProvider>
          <div className="nav-layout">
            <StaffSideNav me={profile} />
            <div className="nav-layout-main">
              <StaffBar me={profile} branch={branch} onBranch={choose} busy={signingOut} onSignOut={() => void signOut()} />
              <InstallPrompt />
              <OthersWaitingNote />
              {!mock && <NewLogoCard />}
              {!mock && <TurnOnCard push={push} />}
              {profile.mfa_enabled && recoveryCodesLeft <= 2 && (
                <p role="status" className="alert alert-warning">
                  You have {recoveryCodesLeft} recovery codes left. Ask an admin to reset your MFA if you run out.
                </p>
              )}
              {signOutError && (
                <p role="alert" className="alert alert-danger">
                  {signOutError}
                </p>
              )}
              <TwoStepNotices me={profile} pathname={pathname} />
              <StaffBreadcrumbs />
              {mustSetUp(profile) ? <OwnTwoStep blocking /> : <Outlet />}
              <SignOutWarning ask={askSignOut} onSignOut={() => void signOut(true)} />
            </div>
          </div>
        </CrumbProvider>
      </TourProvider>
      </OfflineProvider>
    </StaffContext.Provider>
  );
}

/**
 * The sidebar at the left from 720px (issue #455), the shop in use above it (issue #461); nothing on a phone, where the staff bar's
 * menu button opens the same list.
 */
export function StaffSideNav({ me }: { me: Me }) {
  const phone = useIsPhone();
  const sells = useSells(me);
  const branch = useOptionalStaff()?.branch ?? null;
  if (phone) return null;
  return <SideNav label="Staff areas" tour="nav-areas" links={staffAreas(me, { sells })} tours={['retail-nav']} sections={STAFF_SECTIONS} person={me.user_id} head={<CurrentShop me={me} branch={branch} />} />;
}

/**
 * Who is signed in, Help, the branch switcher and Sign out; below 720px also the menu button of the side menu, which then holds
 * Sign out, and a one-tap Sell for anyone who can sell (issue #455), only while the business has retail; with Lending too the Sale
 * shortcut also stays in the menu's Shop section (issue #471). With StaffSideNav it carries the data-tour anchors the
 * first-run tours light up (tour/tours.ts).
 */
export function StaffBar({
  me,
  branch,
  onBranch,
  onSignOut,
  busy = false,
}: {
  me: Me;
  branch: string | null;
  onBranch: (selection: string) => void;
  onSignOut: () => void;
  /** True while signing out runs: Sign out shows its spinner and takes no second press. */
  busy?: boolean;
}) {
  const phone = useIsPhone();
  const sells = useSells(me);
  return (
    <header className="staff-bar">
      <div className="staff-bar-top">
        {phone && (
          <SideMenu
            label="Staff areas"
            tour="nav-areas"
            links={staffAreas(me, { sells })}
            tours={['retail-nav']}
            name={me.full_name}
            busy={busy}
            onSignOut={onSignOut}
            sections={STAFF_SECTIONS}
            person={me.user_id}
            head={<CurrentShop me={me} branch={branch} />}
          />
        )}
        {phone && sells && (
          <Link to="/staff/retail/sale" className="btn btn-primary btn-sm staff-sell">
            {icons.sale}
            <span>Sell</span>
          </Link>
        )}
        <span className="staff-user">
          <span className="avatar">{icons.user}</span>
          <span className="staff-user-name">{me.full_name}</span>
        </span>
        <SyncStatus />
        <HelpMenu feedback />
        <BranchPicker me={me} branch={branch} onChoose={onBranch} />
        {!phone && (
          <BusyButton type="button" className="btn-ghost btn-sm" busy={busy} onClick={onSignOut}>
            Sign out
          </BusyButton>
        )}
      </div>
    </header>
  );
}

export const Route = createLazyRoute('/staff')({ component: StaffLayout });
