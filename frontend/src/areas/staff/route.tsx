import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Outlet, createLazyRoute, useNavigate, useRouterState } from '@tanstack/react-router';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { api, fetchMe, fetchSettings, type Me } from '../../api/client';
import { loadRetailMock, retailMockEnabled } from '../../api/retail';
import { endPendingLogout, getAccessToken, logoutPending, markLogoutPending, refreshFailedOffline, refreshSession, setAccessToken, subscribe } from '../../auth/session';
import { OfflineProvider, lastProfile, useOfflineSupport, waitingNow } from '../../offline/offline-context';
import { SignOutWarning } from '../../offline/sign-out-warning';
import { OthersWaitingNote, SyncStatus } from '../../offline/sync-status';
import { InstallPrompt } from '../../install/InstallPrompt';
import { NewLogoCard } from '../../install/NewIcon';
import { createPush } from '../../push/push';
import { TurnOnCard } from '../../push/TurnOnCard';
import { TourProvider } from '../../tour/host';
import { CrumbProvider, StaffBreadcrumbs } from '../../components/breadcrumbs';
import { icons } from '../../components/icons';
import { MenuItemLink, SideMenu, SideNav, useCloseMenu, useIsPhone } from '../../components/side-menu';
import { BusyButton } from '../../app/ui/BusyButton';
import { RefreshButton } from '../../app/refresh-button';
import { useHelpSheet } from '../../tour/host';
import { BrandLoader } from '../../components/states';
import { BillingBanner } from './billing/banner';
import { MenuShop, initials, useActiveBranch } from './branch-picker';
import { BranchTitle, OfflineBanner, TopMenu } from './top-bar';
import { StaffContext, useOptionalStaff } from './context';
import { FeatureSearch } from './feature-search';
import { PlanNotice } from './plan';
import { FOOT_LINKS, roleOf, staffMenu, useMenuCounts } from './menu';
import { useSells } from './retail/nav';
import { StaffTabBar } from './tabs';
import { canSeeProfit } from './retail/permissions';
import { rememberPage } from './feedback/feedback-state';
import { useAskEnabled } from './help/ask';
import { AskButton, HowDoILink } from './help/how-do-i';
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
    <StaffContext.Provider value={{ me: profile, branch, chooseBranch: choose, signOut: () => void signOut(), signingOut }}>
      <OfflineProvider value={offline}>
      <TourProvider me={profile} saveProgress={!mock}>
        <CrumbProvider>
          <div className="nav-layout">
            <StaffSideNav me={profile} onBranch={choose} busy={signingOut} onSignOut={() => void signOut()} />
            <div className="nav-layout-main">
              <StaffBar me={profile} branch={branch} onBranch={choose} busy={signingOut} onSignOut={() => void signOut()} signOutInBar={mustSetUp(profile)} />
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
              {!mock && <BillingBanner canOpenBilling={(profile.permissions ?? []).includes('core.billing.read')} />}
              {!mock && <PlanNotice />}
              <div className="crumb-row">
                <StaffBreadcrumbs />
                {!mustSetUp(profile) && <HowDoILink pathname={pathname} me={profile} />}
              </div>
              {mustSetUp(profile) ? <OwnTwoStep blocking /> : <Outlet />}
              <SignOutWarning ask={askSignOut} onSignOut={() => void signOut(true)} />
            </div>
            {!mustSetUp(profile) && <StaffTabBar me={profile} />}
          </div>
        </CrumbProvider>
      </TourProvider>
      </OfflineProvider>
    </StaffContext.Provider>
  );
}

const [NOTIFICATIONS] = FOOT_LINKS;
// The anchors of the menu's head and foot, which the drawer opens for as it does for its links.
const FOOT_TOURS = ['nav-notifications', 'nav-feedback'];

/** The staff menu's groups for the session, with the open orders and the items low on stock (issue #564). */
function useStaffGroups(me: Me) {
  const sells = useSells(me);
  const counts = useMenuCounts(me);
  const ask = useAskEnabled();
  return staffMenu(me, { sells, counts, ask });
}

/**
 * The foot of the staff menu (issue #564): Notifications, Help and feedback, which opens the Help sheet with its tours and Send
 * feedback, then the person's initials, name and role, and Sign out. In the drawer Help closes the drawer before the sheet opens.
 */
function StaffMenuFoot({ me, busy, onSignOut, onHelp }: { me: Me; busy: boolean; onSignOut: () => void; onHelp: () => void }) {
  const close = useCloseMenu();
  const role = roleOf(me);
  return (
    <>
      <ul className="side-menu-foot-list">
        <li>
          <MenuItemLink to={NOTIFICATIONS?.to ?? '/staff/notifications'} label="Notifications" icon={icons.bell} tour="nav-notifications" />
        </li>
        <li>
          <button
            type="button"
            className="side-menu-help"
            aria-haspopup="dialog"
            data-tour="nav-feedback"
            onClick={() => {
              close();
              window.setTimeout(onHelp, 0);
            }}
          >
            {icons.help}
            <span className="side-menu-label">Help and feedback</span>
          </button>
        </li>
      </ul>
      <div className="side-menu-me">
        <span className="side-menu-avatar" aria-hidden="true">
          {initials(me.full_name ?? '')}
        </span>
        <span className="side-menu-me-text">
          <span className="side-menu-me-name">{me.full_name}</span>
          {role && <span className="side-menu-me-role">{role}</span>}
        </span>
        <BusyButton type="button" className="btn-ghost side-menu-sign-out" busy={busy} onClick={onSignOut}>
          {icons.signOut}
          Sign out
        </BusyButton>
      </div>
    </>
  );
}

/**
 * The staff menu as a fixed sidebar at the left from 720px (issues #455, #564): the business, its role and the branch at the top,
 * the groups, then the foot; nothing on a phone, where the menu button of the top bar opens the same list in a drawer.
 */
export function StaffSideNav(props: { me: Me; onBranch?: (selection: string) => void; busy?: boolean; onSignOut?: () => void }) {
  // The phone check comes first, so on a phone the menu's reads and the Help sheet run once, in the drawer.
  return useIsPhone() ? null : <StaffSidebar {...props} />;
}

function StaffSidebar({ me, onBranch = () => undefined, busy = false, onSignOut = () => undefined }: { me: Me; onBranch?: (selection: string) => void; busy?: boolean; onSignOut?: () => void }) {
  const groups = useStaffGroups(me);
  const branch = useOptionalStaff()?.branch ?? null;
  const help = useHelpSheet(true);
  return (
    <>
      <SideNav
        label="Staff areas"
        tour="nav-areas"
        groups={groups}
        head={<MenuShop me={me} branch={branch} role={roleOf(me)} onChoose={onBranch} tour="branch-picker" />}
        foot={<StaffMenuFoot me={me} busy={busy} onSignOut={onSignOut} onHelp={help.open} />}
      />
      {help.sheet}
    </>
  );
}

/** The menu button at the top left of the phone's top bar and its drawer: the same list as the sidebar (issue #564). */
function StaffDrawer({ me, branch, onBranch, busy, onSignOut }: { me: Me; branch: string | null; onBranch: (selection: string) => void; busy: boolean; onSignOut: () => void }) {
  const groups = useStaffGroups(me);
  const help = useHelpSheet(true);
  return (
    <>
      <SideMenu
        label="Staff areas"
        tour="nav-areas"
        groups={groups}
        tours={FOOT_TOURS}
        onSignOut={onSignOut}
        busy={busy}
        head={<MenuShop me={me} branch={branch} role={roleOf(me)} onChoose={onBranch} />}
        foot={<StaffMenuFoot me={me} busy={busy} onSignOut={onSignOut} onHelp={help.open} />}
      />
      {help.sheet}
    </>
  );
}

/**
 * The one top bar of the staff area (issue #526, replacing the brand bar and the staff bar of two rows): on a phone the business
 * and the branch in use as the title, which switches branch (from 720px they are at the top of the sidebar only, #564); Search (issue #551); Refresh beside it
 * (issue #631); the Online dot only while it has something to say; and one menu holding who is signed in, Help, Light or dark and Sign out. Below 720px the menu button at its left opens
 * the staff menu's drawer (issue #564), Sign out moves to the drawer and the More tab's page (it stays in the top menu while
 * two-step set-up blocks the page, which has no tabs) and Sell is the first bottom tab (issue #539). With StaffSideNav and the tab
 * bar it carries the data-tour anchors the first-run tours light up (tour/tours.ts).
 */
export function StaffBar({
  me,
  branch,
  onBranch,
  onSignOut,
  busy = false,
  signOutInBar = false,
}: {
  me: Me;
  branch: string | null;
  onBranch: (selection: string) => void;
  onSignOut: () => void;
  /** True while signing out runs: Sign out shows its spinner and takes no second press. */
  busy?: boolean;
  /** Sign out in the menu on a phone too: while two-step set-up blocks the page there are no tabs and no More page to reach it. */
  signOutInBar?: boolean;
}) {
  const phone = useIsPhone();
  return (
    <>
      <header className="staff-bar">
        <div className="staff-bar-top">
          {phone && <StaffDrawer me={me} branch={branch} onBranch={onBranch} busy={busy} onSignOut={onSignOut} />}
          {/* From 720px the sidebar's top holds the logo, the business and the branch picker, so the bar does not repeat them (#564). */}
          {phone ? <BranchTitle me={me} branch={branch} onChoose={onBranch} /> : <span className="staff-bar-spacer" />}
          <SyncStatus />
          {/* Refresh beside Search on every staff screen (issue #631): its icon alone below 720px, a 48px target named Refresh. Ask
              keeps its place on Search's other side (issue #603). */}
          <RefreshButton />
          <FeatureSearch me={me} />
          {/* The Ask button (issue #603); none while two-step set-up blocks the page, whose header is kept minimal. */}
          {!mustSetUp(me) && <AskButton />}
          <TopMenu me={me} busy={busy} onSignOut={onSignOut} signOut={!phone || signOutInBar} />
        </div>
      </header>
      <OfflineBanner />
    </>
  );
}

export const Route = createLazyRoute('/staff')({ component: StaffLayout });
