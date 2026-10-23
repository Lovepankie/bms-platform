// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { type ReactNode, useEffect, useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type Me } from '../../api/client';
import { retail } from '../../api/retail';
import { mockMe } from '../../api/retail-mock';
import { router as appRouter } from '../../app/router';
import { BusyAnnouncer } from '../../app/ui/BusyButton';
import { setAccessToken } from '../../auth/session';
import { TourProvider, useTour } from '../../tour/host';
import type { Tour } from '../../tour/model';
import { StaffContext } from './context';
import { PortalFrame } from '../platform/route';
import { staffAreas } from './menu';
import { StaffBar, StaffSideNav } from './route';

// The side menu of the staff area (issue #455), one list of areas on every screen size. Below 720px a menu button opens a drawer
// from the left with the areas the session may open, the current one marked, the retail shortcuts as a group under Retail, and the
// name and Sign out at the bottom. It closes on Escape, the backdrop, a left swipe or a chosen link and gives focus back to the
// button. From 720px the same list is a sidebar that folds to its icons, the choice kept on the device. A tour step that points at
// a menu item opens the drawer or unfolds the sidebar. A phone has a one-tap Sell beside the menu button. Every name is invented.

const TOURS_OFF = { 'tours-off': { status: 'dismissed', version: 1 } };

const officer = {
  user_id: '00000000-0000-4000-8000-0000000000a1',
  full_name: 'Test Officer 01',
  kind: 'staff',
  permissions: ['lending.loans.read', 'lending.members.read', 'lending.insights.read', 'core.approvals.read'],
  modules: ['lending'],
  branches: [],
  tours: TOURS_OFF,
} as unknown as Me;

const admin = { ...mockMe('admin'), tours: TOURS_OFF } as unknown as Me;

// A window of the given width whose media queries answer by it; resize() changes the width and tells every listener.
let width = 360;
const listeners = new Set<() => void>();
const fits = (query: string) => width <= Number(/max-width: (\d+)px/.exec(query)?.[1] ?? Infinity);
function widthIs(px: number | boolean) {
  width = typeof px === 'number' ? px : px ? 360 : 1280;
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      get matches() {
        return fits(query);
      },
      media: query,
      addEventListener: (_: string, listener: () => void) => listeners.add(listener),
      removeEventListener: (_: string, listener: () => void) => listeners.delete(listener),
    })),
  );
}
function resize(px: number) {
  act(() => {
    width = px;
    for (const listener of [...listeners]) listener();
  });
}

// The staff bar as the staff route draws it: Sign out is busy from the press until signing out is over.
function SigningOutBar({ me, signOut, done }: { me: Me; signOut: () => void; done: Promise<void> }) {
  const [busy, setBusy] = useState(false);
  return (
    <StaffBar
      me={me}
      branch={null}
      onBranch={() => undefined}
      busy={busy}
      onSignOut={() => {
        setBusy(true);
        signOut();
        void done.then(() => setBusy(false));
      }}
    />
  );
}

function show(me: Me, { at = '/staff', children = null as ReactNode, signingOut = undefined as Promise<void> | undefined } = {}) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [at] }) });
  const signOut = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  const tree = (who: Me) => (
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: who, branch: who.branches?.[0]?.id ?? null }}>
          <TourProvider me={who} saveProgress={false}>
            <StaffSideNav me={who} />
            {signingOut ? <SigningOutBar me={who} signOut={signOut} done={signingOut} /> : <StaffBar me={who} branch={null} onBranch={() => undefined} onSignOut={signOut} />}
            {children}
          </TourProvider>
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>
  );
  const { rerender } = render(tree(me));
  // Another person signed in, without the menu leaving the page.
  const signIn = (who: Me) => rerender(tree(who));
  return { router, signOut, signIn };
}

const menuButton = () => screen.getByRole('button', { name: 'Menu' });
const drawer = () => screen.queryByRole('dialog', { name: 'Menu' });

function openMenu() {
  fireEvent.click(menuButton());
  const found = drawer();
  expect(found).not.toBeNull();
  return found as HTMLElement;
}

const labels = (dialog: HTMLElement) =>
  within(within(dialog).getByRole('navigation', { name: 'Staff areas' }))
    .getAllByRole('link')
    .map((a) => a.textContent);

beforeEach(() => {
  widthIs(true);
  window.scrollTo = vi.fn();
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  setAccessToken(null);
  vi.unstubAllGlobals();
  listeners.clear();
  document.body.className = '';
  window.localStorage.clear();
});

describe('the staff side menu on a phone (issue #455)', () => {
  it('shows a menu button instead of the tabs, and the button opens the drawer as a modal dialog', () => {
    show(officer);
    expect(screen.queryByRole('navigation', { name: 'Staff areas' })).toBeNull();
    expect(drawer()).toBeNull();
    expect(menuButton().getAttribute('aria-expanded')).toBe('false');
    const dialog = openMenu();
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(menuButton().getAttribute('aria-expanded')).toBe('true');
    expect(dialog.contains(document.activeElement)).toBe(true);
    expect(document.body.classList.contains('side-menu-open')).toBe(true);
  });

  it('lists only the areas the session may open, with Notifications and Feedback, then the name and Sign out', () => {
    const { signOut } = show(officer);
    const dialog = openMenu();
    expect(labels(dialog)).toEqual(['Home', 'Approvals', 'Loans', 'Members', 'Insights', 'Notifications', 'Feedback']);
    expect(within(dialog).getByText('Test Officer 01')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Sign out' }));
    expect(signOut).toHaveBeenCalledTimes(1);
  });

  it('shows Sign out in the drawer busy while signing out, sends it once, and frees it when signing out is over', async () => {
    let finish = () => undefined as void;
    const { signOut } = show(officer, { signingOut: new Promise<void>((resolve) => (finish = resolve)) });
    const dialog = openMenu();
    const button = within(dialog).getByRole('button', { name: 'Sign out' });
    expect(button.getAttribute('aria-busy')).toBeNull();
    fireEvent.click(button);
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.getAttribute('aria-disabled')).toBe('true');
    expect(button.querySelector('.busy-spinner')).not.toBeNull();
    expect(button.textContent).toBe('Sign out');
    fireEvent.click(button);
    expect(signOut).toHaveBeenCalledTimes(1);
    await act(async () => finish());
    expect(button.getAttribute('aria-busy')).toBeNull();
  });

  it('shows Sign out busy in the real staff route while signing out has not finished', async () => {
    widthIs(false);
    setAccessToken('test-token');
    vi.spyOn(api, 'GET').mockImplementation(((path: string) =>
      path === '/api/v1/me' ? Promise.resolve({ data: officer, response: new Response() }) : new Promise(() => undefined)) as never);
    const logout = vi.spyOn(api, 'POST').mockImplementation((() => new Promise(() => undefined)) as never);
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );
    const button = await screen.findByRole('button', { name: 'Sign out' }, { timeout: 5000 });
    fireEvent.click(button);
    await waitFor(() => expect(logout).toHaveBeenCalledWith('/api/v1/auth/logout'));
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.getAttribute('aria-disabled')).toBe('true');
  });

  it('shows Sign out in the bar busy while signing out from 720px', async () => {
    widthIs(false);
    let finish = () => undefined as void;
    show(officer, { signingOut: new Promise<void>((resolve) => (finish = resolve)) });
    const button = screen.getByRole('button', { name: 'Sign out' });
    fireEvent.click(button);
    expect(button.getAttribute('aria-busy')).toBe('true');
    await act(async () => finish());
    expect(button.getAttribute('aria-busy')).toBeNull();
  });

  it('says Working inside the opened drawer, which hides the rest of the page, while Sign out runs', async () => {
    let finish = () => undefined as void;
    show(officer, { signingOut: new Promise<void>((resolve) => (finish = resolve)) });
    const dialog = openMenu();
    expect(within(dialog).getByRole('status').textContent).toBe('');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Sign out' }));
    expect(within(dialog).getByRole('status').textContent).toBe('Working');
    await act(async () => finish());
    expect(within(dialog).getByRole('status').textContent).toBe('');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(document.querySelectorAll('[data-busy-announcer]')).toHaveLength(0);
  });

  it('has one Working live region at a time: the one in the opened drawer, then the shell one again once it closes', async () => {
    let finish = () => undefined as void;
    show(officer, { signingOut: new Promise<void>((resolve) => (finish = resolve)), children: <BusyAnnouncer /> });
    const announcers = () => Array.from(document.querySelectorAll('[data-busy-announcer]'));
    expect(announcers()).toHaveLength(1);
    const dialog = openMenu();
    expect(announcers()).toHaveLength(1);
    expect(dialog.contains(announcers()[0] as Node)).toBe(true);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Sign out' }));
    expect(announcers().map((a) => a.textContent)).toEqual(['Working']);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(announcers()).toHaveLength(1);
    expect(dialog.contains(announcers()[0] as Node)).toBe(false);
    expect(announcers()[0]?.textContent).toBe('Working');
    await act(async () => finish());
    expect(announcers()[0]?.textContent).toBe('');
  });

  it('marks the current area with aria-current', () => {
    show(officer, { at: '/staff/notifications' });
    const dialog = openMenu();
    expect(within(dialog).getByRole('link', { name: 'Notifications' }).getAttribute('aria-current')).toBe('page');
    expect(within(dialog).getByRole('link', { name: 'Home' }).getAttribute('aria-current')).toBeNull();
  });

  it('closes on Escape and gives focus back to the menu button', async () => {
    show(officer);
    openMenu();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(drawer()).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(menuButton()));
    expect(document.body.classList.contains('side-menu-open')).toBe(false);
  });

  it('closes on the backdrop and gives focus back to the menu button', async () => {
    show(officer);
    openMenu();
    fireEvent.click(document.querySelector('.side-menu-backdrop') as Element);
    expect(drawer()).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(menuButton()));
  });

  it('closes on a left swipe but not on a scroll', () => {
    show(officer);
    const dialog = openMenu();
    fireEvent.touchStart(dialog, { touches: [{ clientX: 200, clientY: 300 }] });
    fireEvent.touchEnd(dialog, { changedTouches: [{ clientX: 190, clientY: 120 }] });
    expect(drawer()).not.toBeNull();
    fireEvent.touchStart(dialog, { touches: [{ clientX: 200, clientY: 300 }] });
    fireEvent.touchEnd(dialog, { changedTouches: [{ clientX: 80, clientY: 310 }] });
    expect(drawer()).toBeNull();
  });

  it('keeps Tab inside the drawer', () => {
    show(officer);
    const dialog = openMenu();
    const signOut = within(dialog).getByRole('button', { name: 'Sign out' });
    signOut.focus();
    fireEvent.keyDown(signOut, { key: 'Tab' });
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Close menu' }));
    fireEvent.keyDown(document.activeElement as Element, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(signOut);
  });

  it('goes to the chosen area and closes', async () => {
    const { router } = show(officer);
    const dialog = openMenu();
    fireEvent.click(within(dialog).getByRole('link', { name: 'Feedback' }));
    expect(drawer()).toBeNull();
    await waitFor(() => expect(router.history.location.pathname).toBe('/staff/feedback'));
    await waitFor(() => expect(document.activeElement).toBe(menuButton()));
  });

  it('shows the retail shortcuts as a group under Retail only when the session has retail', () => {
    show(admin);
    const dialog = openMenu();
    expect(labels(dialog)).toContain('Retail');
    const group = within(dialog).getByRole('list', { name: 'Retail' });
    expect(group.getAttribute('data-tour')).toBe('retail-nav');
    expect(within(group).getAllByRole('link').length).toBeGreaterThan(0);
    cleanup();
    show(officer);
    const lending = openMenu();
    expect(within(lending).queryByRole('list', { name: 'Retail' })).toBeNull();
    expect(labels(lending)).not.toContain('Retail');
  });

  it('closes on any change of page, the back button or a redirect too, and lets the page scroll again', async () => {
    const { router } = show(officer);
    openMenu();
    await act(async () => {
      await router.navigate({ to: '/staff/notifications' });
    });
    expect(drawer()).toBeNull();
    expect(document.body.classList.contains('side-menu-open')).toBe(false);
    await waitFor(() => expect(document.activeElement).toBe(menuButton()));
  });

  it('holds the page where it was while open and gives the scroll position back on close', () => {
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 300 });
    show(officer);
    openMenu();
    expect(document.body.style.getPropertyValue('--side-menu-scroll')).toBe('-300px');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(document.body.style.getPropertyValue('--side-menu-scroll')).toBe('');
    expect(window.scrollTo).toHaveBeenCalledWith(0, 300);
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 });
  });

  it('does not give the old page its scroll position back when a link is tapped', async () => {
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 300 });
    const { router } = show(officer);
    fireEvent.click(within(openMenu()).getByRole('link', { name: 'Feedback' }));
    expect(drawer()).toBeNull();
    await waitFor(() => expect(router.history.location.pathname).toBe('/staff/feedback'));
    expect(window.scrollTo).not.toHaveBeenCalledWith(0, 300);
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 });
  });

  it('gives the scroll position back when the tapped link is the page already shown', () => {
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 300 });
    show(officer, { at: '/staff/feedback' });
    fireEvent.click(within(openMenu()).getByRole('link', { name: 'Feedback' }));
    expect(drawer()).toBeNull();
    expect(window.scrollTo).toHaveBeenCalledWith(0, 300);
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 });
  });

  it.each([{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { altKey: true }, { button: 1 }])('gives the scroll position back when a link opens elsewhere (%o)', (keys) => {
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 300 });
    const { router } = show(officer);
    fireEvent.click(within(openMenu()).getByRole('link', { name: 'Feedback' }), keys);
    expect(drawer()).toBeNull();
    expect(window.scrollTo).toHaveBeenCalledWith(0, 300);
    expect(router.history.location.pathname).toBe('/staff');
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 });
  });

  it('leaves no drawer, backdrop or scroll lock behind when the window widens while it is open', async () => {
    show(officer);
    openMenu();
    resize(1280);
    expect(drawer()).toBeNull();
    expect(document.querySelector('.side-menu, .side-menu-backdrop')).toBeNull();
    expect(document.body.classList.contains('side-menu-open')).toBe(false);
    const first = document.querySelector('.side-nav .side-menu-links a');
    await waitFor(() => expect(document.activeElement).toBe(first));
  });

  it('gives the focus to the menu button when the window narrows while it is in the sidebar', async () => {
    widthIs(1280);
    show(officer);
    (document.querySelector('.side-nav .side-menu-links a') as HTMLElement).focus();
    resize(360);
    expect(document.querySelector('.side-nav')).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(menuButton()));
  });

  it('has a one-tap Sell beside the menu button for anyone who can sell, straight to the sale screen', async () => {
    const { router } = show(admin);
    const sell = screen.getByRole('link', { name: 'Sell' });
    expect(sell.getAttribute('href')).toBe('/staff/retail/sale');
    expect(menuButton().nextElementSibling).toBe(sell);
    fireEvent.click(sell);
    await waitFor(() => expect(router.history.location.pathname).toBe('/staff/retail/sale'));
    cleanup();
    show(officer);
    expect(screen.queryByRole('link', { name: 'Sell' })).toBeNull();
    cleanup();
    show({ ...admin, permissions: (admin.permissions ?? []).filter((p) => p !== 'retail.sale.create') } as Me);
    expect(screen.queryByRole('link', { name: 'Sell' })).toBeNull();
  });

  it('shows Sell only once the branch roles are read, and never when every branch is a store', async () => {
    const withRoles = { ...admin, module_capabilities: { ...admin.module_capabilities, retail: [...(admin.module_capabilities?.retail ?? []), 'branch_roles'] } } as Me;
    const roles = (role: 'store' | 'shop') =>
      (withRoles.branches ?? []).map((b) => ({ branch_id: b.id, code: b.code, name: b.name, role, can_sell: role !== 'store' }));
    let answer: (value: ReturnType<typeof roles>) => void = () => undefined;
    const read = vi.spyOn(retail, 'branchRoles').mockImplementation(() => new Promise((resolve) => (answer = resolve as typeof answer)));
    show(withRoles);
    await waitFor(() => expect(read).toHaveBeenCalled());
    expect(screen.queryByRole('link', { name: 'Sell' })).toBeNull();
    await act(async () => answer(roles('store')));
    expect(screen.queryByRole('link', { name: 'Sell' })).toBeNull();
    cleanup();
    read.mockResolvedValue(roles('shop') as never);
    show(withRoles);
    expect(screen.queryByRole('link', { name: 'Sell' })).toBeNull();
    expect(await screen.findByRole('link', { name: 'Sell' })).toBeTruthy();
    read.mockRestore();
  });

  it('keeps the tour anchors in the page while closed, so optional tour steps are not skipped', () => {
    show(officer);
    for (const id of ['nav-areas', 'nav-approvals', 'nav-lending', 'nav-members', 'nav-insights', 'nav-notifications', 'nav-feedback']) {
      expect(document.querySelector(`[data-tour="${id}"]`), id).not.toBeNull();
    }
    cleanup();
    show(admin);
    for (const id of ['nav-areas', 'nav-retail', 'retail-nav', 'nav-notifications', 'nav-feedback']) {
      expect(document.querySelector(`[data-tour="${id}"]`), id).not.toBeNull();
    }
  });

  it('opens for a tour step that points at a menu item, and closes when the tour ends', async () => {
    const tour: Tour = {
      id: 'test-menu-tour',
      version: 1,
      title: 'Test tour',
      audience: () => true,
      autoStart: false,
      steps: [{ id: 'feedback', target: 'nav-feedback', title: 'Feedback here', body: 'Tap Feedback to write to us.' }],
    };
    function Starter() {
      const { start } = useTour();
      useEffect(() => start(tour), [start]);
      return null;
    }
    // jsdom has neither CSS.escape nor scrollIntoView, which the tour runtime uses to find and show its target.
    vi.stubGlobal('CSS', { escape: (value: string) => value });
    Element.prototype.scrollIntoView = vi.fn();
    show(officer, { children: <Starter /> });
    await screen.findByRole('heading', { name: 'Feedback here' });
    const panel = document.querySelector('.side-menu') as HTMLElement;
    expect(panel.hidden).toBe(false);
    expect(panel.classList.contains('side-menu-instant')).toBe(true);
    // Shown for a tour, the page behind stays reachable: the drawer is not modal (aria-modal only when the person opened it).
    expect(panel.querySelector('[role="dialog"]')?.hasAttribute('aria-modal')).toBe(false);
    expect(document.activeElement?.textContent).toBe('Feedback here');
    act(() => {
      fireEvent.keyDown(document.activeElement as Element, { key: 'Escape' });
    });
    await waitFor(() => expect(panel.hidden).toBe(true));
  });
});

describe('the staff sidebar from 720px', () => {
  beforeEach(() => widthIs(false));

  const sidebar = () => document.querySelector('.side-nav') as HTMLElement;
  const toggle = () => within(sidebar()).getByRole('button', { name: 'Menu labels' });
  // Every link of the list, the retail group included, by its accessible name.
  const allLinks = (root: HTMLElement) =>
    within(within(root).getByRole('navigation', { name: 'Staff areas' }))
      .getAllByRole('link')
      .map((a) => a.textContent);

  it('shows the sidebar with the areas and no tabs, no menu button, and Sign out in the staff bar', () => {
    show(admin);
    const nav = within(sidebar()).getByRole('navigation', { name: 'Staff areas' });
    expect(nav.getAttribute('data-tour')).toBe('nav-areas');
    expect(within(nav).getByRole('link', { name: 'Retail' }).getAttribute('data-tour')).toBe('nav-retail');
    expect(within(nav).getByRole('list', { name: 'Retail' }).getAttribute('data-tour')).toBe('retail-nav');
    expect(screen.getAllByRole('navigation', { name: 'Staff areas' })).toHaveLength(1);
    expect(document.querySelector('.tabs')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Menu' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Sell' })).toBeNull();
    expect(screen.queryByRole('complementary')).toBeNull();
  });

  it('comes before the staff bar, so Tab reaches the menu before the page', () => {
    show(officer);
    const bar = document.querySelector('.staff-bar') as Element;
    expect(sidebar().compareDocumentPosition(bar) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('lists the same items as the phone drawer for the same permissions', () => {
    for (const me of [officer, admin]) {
      show(me);
      const wide = allLinks(sidebar());
      cleanup();
      widthIs(true);
      show(me);
      expect(allLinks(openMenu()), me.full_name).toEqual(wide);
      cleanup();
      widthIs(false);
    }
  });

  it('marks the current area with aria-current', () => {
    show(officer, { at: '/staff/notifications' });
    expect(within(sidebar()).getByRole('link', { name: 'Notifications' }).getAttribute('aria-current')).toBe('page');
    expect(within(sidebar()).getByRole('link', { name: 'Home' }).getAttribute('aria-current')).toBeNull();
  });

  it('keeps Retail current on a retail page without a shortcut, and draws it as the section above a current shortcut', () => {
    show(admin, { at: '/staff/retail/catalogue' });
    const retail = () => within(sidebar()).getByRole('link', { name: 'Retail' });
    expect(retail().getAttribute('aria-current')).toBe('page');
    expect(retail().classList.contains('side-menu-section')).toBe(false);
    cleanup();
    show(admin, { at: '/staff/retail/sale' });
    expect(retail().getAttribute('aria-current')).toBeNull();
    expect(retail().classList.contains('side-menu-section')).toBe(true);
    const group = within(sidebar()).getByRole('list', { name: 'Retail' });
    expect(within(group).getByRole('link', { name: 'Sale' }).getAttribute('aria-current')).toBe('page');
    cleanup();
    show(admin, { at: '/staff/retail' });
    expect(retail().getAttribute('aria-current')).toBe('page');
  });

  it('starts folded to icons between 720 and 1023px and unfolded from 1024px, until the person chooses', () => {
    widthIs(800);
    show(officer);
    expect(sidebar().classList.contains('side-nav-folded')).toBe(true);
    fireEvent.click(toggle());
    expect(sidebar().classList.contains('side-nav-folded')).toBe(false);
    expect(window.localStorage.getItem('bms.sidebar')).toBe('open');
    cleanup();
    show(officer);
    expect(sidebar().classList.contains('side-nav-folded')).toBe(false);
    cleanup();
    window.localStorage.clear();
    widthIs(1024);
    show(officer);
    expect(sidebar().classList.contains('side-nav-folded')).toBe(false);
  });

  it('folds to its icons and back, and keeps the choice on this device', () => {
    show(officer);
    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    expect(toggle().hasAttribute('aria-controls')).toBe(false);
    fireEvent.click(toggle());
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    expect(sidebar().classList.contains('side-nav-folded')).toBe(true);
    expect(window.localStorage.getItem('bms.sidebar')).toBe('folded');
    cleanup();
    show(officer);
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(toggle());
    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    expect(window.localStorage.getItem('bms.sidebar')).toBe('open');
  });

  it('keeps each folded item named, with its label as a tooltip, the retail group too', () => {
    window.localStorage.setItem('bms.sidebar', 'folded');
    show(admin);
    const retail = within(sidebar()).getByRole('link', { name: 'Retail' });
    expect(retail.getAttribute('title')).toBe('Retail');
    const group = within(sidebar()).getByRole('list', { name: 'Retail' });
    for (const link of within(group).getAllByRole('link')) expect(link.getAttribute('title')).toBe(link.textContent);
  });

  it('still works when the browser blocks storage', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    show(officer);
    fireEvent.click(toggle());
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    vi.restoreAllMocks();
  });

  it('unfolds for a tour step that points at a menu item', async () => {
    window.localStorage.setItem('bms.sidebar', 'folded');
    const tour: Tour = {
      id: 'test-sidebar-tour',
      version: 1,
      title: 'Test tour',
      audience: () => true,
      autoStart: false,
      steps: [{ id: 'feedback', target: 'nav-feedback', title: 'Feedback here', body: 'Choose Feedback to write to us.' }],
    };
    function Starter() {
      const { start } = useTour();
      useEffect(() => start(tour), [start]);
      return null;
    }
    vi.stubGlobal('CSS', { escape: (value: string) => value });
    Element.prototype.scrollIntoView = vi.fn();
    show(officer, { children: <Starter /> });
    await screen.findByRole('heading', { name: 'Feedback here' });
    expect(sidebar().classList.contains('side-nav-folded')).toBe(false);
    act(() => {
      fireEvent.keyDown(document.activeElement as Element, { key: 'Escape' });
    });
    await waitFor(() => expect(sidebar().classList.contains('side-nav-folded')).toBe(true));
  });

  it('folds by what it shows during a tour: unfolded for a step, a press folds and unfolds it', async () => {
    const tour: Tour = {
      id: 'test-sidebar-toggle-tour',
      version: 1,
      title: 'Test tour',
      audience: () => true,
      autoStart: false,
      steps: [{ id: 'feedback', target: 'nav-feedback', title: 'Feedback here', body: 'Choose Feedback to write to us.' }],
    };
    function Starter() {
      const { start } = useTour();
      useEffect(() => start(tour), [start]);
      return null;
    }
    vi.stubGlobal('CSS', { escape: (value: string) => value });
    Element.prototype.scrollIntoView = vi.fn();
    window.localStorage.setItem('bms.sidebar', 'folded');
    show(officer, { children: <Starter /> });
    await screen.findByRole('heading', { name: 'Feedback here' });
    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    fireEvent.click(toggle());
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    expect(toggle().getAttribute('title')).toBe('Show menu labels');
    expect(sidebar().classList.contains('side-nav-folded')).toBe(true);
    expect(window.localStorage.getItem('bms.sidebar')).toBe('folded');
    fireEvent.click(toggle());
    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    expect(sidebar().classList.contains('side-nav-folded')).toBe(false);
    expect(window.localStorage.getItem('bms.sidebar')).toBe('open');
    fireEvent.click(toggle());
    expect(sidebar().classList.contains('side-nav-folded')).toBe(true);
    act(() => {
      fireEvent.keyDown(screen.getByRole('heading', { name: 'Feedback here' }), { key: 'Escape' });
    });
    await waitFor(() => expect(sidebar().classList.contains('side-nav-folded')).toBe(true));
  });

  // A tour of two sidebar steps, started by a button so it can run twice.
  const twoSteps: Tour = {
    id: 'test-sidebar-two-steps',
    version: 1,
    title: 'Test tour',
    audience: () => true,
    autoStart: false,
    steps: [
      { id: 'feedback', target: 'nav-feedback', title: 'Feedback here', body: 'Choose Feedback to write to us.' },
      { id: 'notifications', target: 'nav-notifications', title: 'Notifications here', body: 'Choose Notifications to read them.' },
    ],
  };
  function TourStarter() {
    const { start } = useTour();
    return (
      <button type="button" onClick={() => start(twoSteps)}>
        Start test tour
      </button>
    );
  }
  const tourKey = (key: string, title: string) =>
    act(() => {
      fireEvent.keyDown(screen.getByRole('heading', { name: title }), { key });
    });

  it('forgets a press when the step changes: back on the pressed step it is unfolded again', async () => {
    vi.stubGlobal('CSS', { escape: (value: string) => value });
    Element.prototype.scrollIntoView = vi.fn();
    window.localStorage.setItem('bms.sidebar', 'folded');
    show(officer, { children: <TourStarter /> });
    fireEvent.click(screen.getByRole('button', { name: 'Start test tour' }));
    await screen.findByRole('heading', { name: 'Feedback here' });
    fireEvent.click(toggle());
    expect(sidebar().classList.contains('side-nav-folded')).toBe(true);
    tourKey('ArrowRight', 'Feedback here');
    await screen.findByRole('heading', { name: 'Notifications here' });
    expect(sidebar().classList.contains('side-nav-folded')).toBe(false);
    tourKey('ArrowLeft', 'Notifications here');
    await screen.findByRole('heading', { name: 'Feedback here' });
    expect(sidebar().classList.contains('side-nav-folded')).toBe(false);
    expect(toggle().getAttribute('aria-expanded')).toBe('true');
  });

  it('forgets a press when the tour ends: the next run of the tour unfolds it again', async () => {
    vi.stubGlobal('CSS', { escape: (value: string) => value });
    Element.prototype.scrollIntoView = vi.fn();
    window.localStorage.setItem('bms.sidebar', 'folded');
    show(officer, { children: <TourStarter /> });
    fireEvent.click(screen.getByRole('button', { name: 'Start test tour' }));
    await screen.findByRole('heading', { name: 'Feedback here' });
    fireEvent.click(toggle());
    expect(sidebar().classList.contains('side-nav-folded')).toBe(true);
    tourKey('Escape', 'Feedback here');
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Feedback here' })).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: 'Start test tour' }));
    await screen.findByRole('heading', { name: 'Feedback here' });
    expect(sidebar().classList.contains('side-nav-folded')).toBe(false);
  });
});

describe('the same areas for the same permissions on every size', () => {
  const session = (permissions: string[], modules: string[]) => ({ ...officer, permissions, modules }) as unknown as Me;
  const names = (me: Me, sells = true) => staffAreas(me, { sells }).map((a) => a.label);
  const cases: { role: string; me: Me; has: string[]; lacks: string[] }[] = [
    { role: 'collateral only', me: session(['lending.collateral.read'], ['lending']), has: ['Collateral'], lacks: ['Members', 'Retail', 'Business set-up'] },
    { role: 'members and collateral', me: session(['lending.members.read', 'lending.collateral.read'], ['lending']), has: ['Members'], lacks: ['Collateral'] },
    { role: 'settings manager', me: session(['core.settings.manage'], []), has: ['Business set-up'], lacks: ['Retail', 'Members'] },
    { role: 'no settings', me: session(['core.approvals.read'], []), has: ['Approvals'], lacks: ['Business set-up'] },
    { role: 'retail seller', me: { ...mockMe('sales'), tours: TOURS_OFF } as unknown as Me, has: ['Retail'], lacks: ['Business set-up', 'Members'] },
    { role: 'retail admin', me: admin, has: ['Retail', 'Business set-up'], lacks: ['Collateral'] },
    { role: 'retail switched off', me: { ...admin, modules: ['lending'] } as Me, has: ['Business set-up'], lacks: ['Retail'] },
  ];

  it.each(cases)('$role', ({ me, has, lacks }) => {
    const list = names(me);
    for (const label of [...has, 'Home', 'Notifications', 'Feedback']) expect(list, label).toContain(label);
    for (const label of lacks) expect(list, label).not.toContain(label);
    widthIs(1280);
    show(me);
    const wide = within(document.querySelector('.side-nav') as HTMLElement).getAllByRole('link').map((a) => a.textContent);
    cleanup();
    widthIs(360);
    show(me);
    expect(within(openMenu()).getAllByRole('link').map((a) => a.textContent)).toEqual(wide);
  });

  it('lists Sale under Retail only for a session that can sell', () => {
    const group = (sells: boolean) => staffAreas(admin, { sells }).find((a) => a.label === 'Retail')?.groupPaths ?? [];
    expect(group(true)).toContain('/staff/retail/sale');
    expect(group(false)).not.toContain('/staff/retail/sale');
  });
});

describe('a business with both Shop and Lending (issue #471)', () => {
  const both = { ...admin, permissions: [...(admin.permissions ?? []), 'lending.loans.read', 'lending.members.read'], modules: ['lending', 'retail'] } as unknown as Me;
  const headings = (root: HTMLElement) => Array.from(root.querySelectorAll('.side-menu-heading')).map((b) => b.textContent);
  const sectionLinks = (root: HTMLElement, name: string) => {
    const heading = within(root).getByRole('button', { name });
    const list = document.getElementById(heading.getAttribute('aria-controls') ?? '') as HTMLElement;
    return within(list).queryAllByRole('link').map((a) => a.textContent);
  };

  it('groups the menu under Shop and Lending with the shared areas around them, on a phone and from 720px alike', () => {
    widthIs(1280);
    show(both);
    const sidebar = document.querySelector('.side-nav') as HTMLElement;
    expect(headings(sidebar)).toEqual(['Shop', 'Lending']);
    expect(sectionLinks(sidebar, 'Lending')).toEqual(['Loans', 'Members']);
    expect(sectionLinks(sidebar, 'Shop')[0]).toBe('Retail');
    const wide = within(sidebar).getAllByRole('link').map((a) => a.textContent);
    expect(wide.slice(0, 2)).toEqual(['Home', 'Retail']);
    expect(wide.slice(-3)).toEqual(['Notifications', 'Feedback', 'Business set-up']);
    cleanup();
    widthIs(360);
    show(both);
    const dialog = openMenu();
    expect(headings(dialog)).toEqual(['Shop', 'Lending']);
    expect(labels(dialog)).toEqual(wide);
  });

  it('keeps a one-module menu flat, with no heading', () => {
    widthIs(1280);
    show(admin);
    expect(headings(document.querySelector('.side-nav') as HTMLElement)).toEqual([]);
    cleanup();
    show(officer);
    expect(headings(document.querySelector('.side-nav') as HTMLElement)).toEqual([]);
  });

  it('folds a section and remembers it for that person only', () => {
    widthIs(1280);
    show(both);
    const heading = screen.getByRole('button', { name: 'Lending' });
    expect(heading.getAttribute('aria-expanded')).toBe('true');
    fireEvent.click(heading);
    expect(heading.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('link', { name: 'Loans' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Retail' })).toBeTruthy();
    cleanup();
    show(both);
    expect(screen.getByRole('button', { name: 'Lending' }).getAttribute('aria-expanded')).toBe('false');
    cleanup();
    show({ ...both, user_id: '00000000-0000-4000-8000-0000000000f2' } as Me);
    expect(screen.getByRole('button', { name: 'Lending' }).getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByRole('link', { name: 'Loans' })).toBeTruthy();
  });

  it('reads the folded sections again when another person signs in on the device', () => {
    widthIs(1280);
    const other = { ...both, user_id: '00000000-0000-4000-8000-0000000000f3' } as Me;
    window.localStorage.setItem(`bms.menu.sections.${other.user_id}`, JSON.stringify(['lending']));
    const { signIn } = show(both);
    expect(screen.getByRole('button', { name: 'Lending' }).getAttribute('aria-expanded')).toBe('true');
    signIn(other);
    expect(screen.getByRole('button', { name: 'Lending' }).getAttribute('aria-expanded')).toBe('false');
    signIn(both);
    expect(screen.getByRole('button', { name: 'Lending' }).getAttribute('aria-expanded')).toBe('true');
  });

  it.each([
    { at: '/staff/lending/members', section: 'Lending', link: 'Members' },
    { at: '/staff/retail/sale', section: 'Shop', link: 'Sale' },
  ])('shows a folded section while the page is one of its links: $at', async ({ at, section, link }) => {
    widthIs(1280);
    window.localStorage.setItem(`bms.menu.sections.${both.user_id}`, JSON.stringify(['shop', 'lending']));
    show(both, { at });
    await waitFor(() => expect(screen.getByRole('button', { name: section }).getAttribute('aria-expanded')).toBe('true'));
    expect(screen.getByRole('link', { name: link }).getAttribute('aria-current')).toBe('page');
    const otherSection = section === 'Shop' ? 'Lending' : 'Shop';
    expect(screen.getByRole('button', { name: otherSection }).getAttribute('aria-expanded')).toBe('false');
  });

  it.each([
    { target: 'nav-lending', section: 'Lending' },
    { target: 'nav-retail', section: 'Shop' },
    { target: 'retail-nav', section: 'Shop' },
  ])('unfolds a folded section for a tour step on $target', async ({ target, section }) => {
    widthIs(1280);
    window.localStorage.setItem(`bms.menu.sections.${both.user_id}`, JSON.stringify(['shop', 'lending']));
    const tour: Tour = {
      id: `test-section-tour-${target}`,
      version: 1,
      title: 'Test tour',
      audience: () => true,
      autoStart: false,
      steps: [{ id: 'here', target, title: 'Look here', body: 'This part of the menu.' }],
    };
    function Starter() {
      const { start } = useTour();
      useEffect(() => start(tour), [start]);
      return null;
    }
    vi.stubGlobal('CSS', { escape: (value: string) => value });
    Element.prototype.scrollIntoView = vi.fn();
    show(both, { children: <Starter /> });
    await screen.findByRole('heading', { name: 'Look here' });
    const heading = within(document.querySelector('.side-nav') as HTMLElement).getByRole('button', { name: section });
    expect(heading.getAttribute('aria-expanded')).toBe('true');
    act(() => {
      fireEvent.keyDown(document.activeElement as Element, { key: 'Escape' });
    });
    await waitFor(() => expect(heading.getAttribute('aria-expanded')).toBe('false'));
  });

  it('folds a section held open for a tour step on a press, for that step only', async () => {
    widthIs(1280);
    window.localStorage.setItem(`bms.menu.sections.${both.user_id}`, JSON.stringify(['lending']));
    const tour: Tour = {
      id: 'test-section-press',
      version: 1,
      title: 'Test tour',
      audience: () => true,
      autoStart: false,
      steps: [{ id: 'here', target: 'nav-lending', title: 'Look here', body: 'This part of the menu.' }],
    };
    function Starter() {
      const { start } = useTour();
      return (
        <button type="button" onClick={() => start(tour)}>
          Start the test tour
        </button>
      );
    }
    vi.stubGlobal('CSS', { escape: (value: string) => value });
    Element.prototype.scrollIntoView = vi.fn();
    show(both, { children: <Starter /> });
    fireEvent.click(screen.getByRole('button', { name: 'Start the test tour' }));
    await screen.findByRole('heading', { name: 'Look here' });
    const heading = within(document.querySelector('.side-nav') as HTMLElement).getByRole('button', { name: 'Lending' });
    expect(heading.getAttribute('aria-expanded')).toBe('true');
    fireEvent.click(heading);
    expect(heading.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('link', { name: 'Loans' })).toBeNull();
    expect(JSON.parse(window.localStorage.getItem(`bms.menu.sections.${both.user_id}`) ?? '[]')).toEqual(['lending']);
    act(() => {
      fireEvent.keyDown(document.activeElement as Element, { key: 'Escape' });
    });
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Look here' })).toBeNull());
    expect(heading.getAttribute('aria-expanded')).toBe('false');
    // The next run of the tour holds it open again.
    fireEvent.click(screen.getByRole('button', { name: 'Start the test tour' }));
    await screen.findByRole('heading', { name: 'Look here' });
    expect(heading.getAttribute('aria-expanded')).toBe('true');
  });

  it('folds the section of the page on a press, and unfolds it on the next', () => {
    widthIs(1280);
    window.localStorage.setItem(`bms.menu.sections.${both.user_id}`, JSON.stringify(['lending']));
    show(both, { at: '/staff/lending/members' });
    const heading = screen.getByRole('button', { name: 'Lending' });
    expect(heading.getAttribute('aria-expanded')).toBe('true');
    fireEvent.click(heading);
    expect(heading.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('link', { name: 'Members' })).toBeNull();
    fireEvent.click(heading);
    expect(heading.getAttribute('aria-expanded')).toBe('true');
    expect(JSON.parse(window.localStorage.getItem(`bms.menu.sections.${both.user_id}`) ?? '[]')).toEqual([]);
  });

  it('skips the links of a folded section when tabbing round the drawer', () => {
    show(both);
    const dialog = openMenu();
    const heading = within(dialog).getByRole('button', { name: 'Lending' });
    fireEvent.click(heading);
    expect(heading.getAttribute('aria-expanded')).toBe('false');
    const lists = dialog.querySelectorAll<HTMLElement>('.side-menu-links > ul');
    const nextShared = within(lists[lists.length - 1] as HTMLElement).getAllByRole('link')[0] as HTMLElement;
    heading.focus();
    fireEvent.keyDown(heading, { key: 'Tab' });
    expect(document.activeElement).toBe(nextShared);
    fireEvent.keyDown(nextShared, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(heading);
  });

  it('keeps the one-tap Sell on a 360px phone and Sale in the Shop section', () => {
    show(both);
    expect(screen.getByRole('link', { name: 'Sell' }).getAttribute('href')).toBe('/staff/retail/sale');
    const dialog = openMenu();
    expect(sectionLinks(dialog, 'Shop')).toContain('Sale');
    expect(sectionLinks(dialog, 'Lending')).not.toContain('Sale');
    cleanup();
    show({ ...both, modules: ['lending'] } as Me);
    expect(screen.queryByRole('link', { name: 'Sell' })).toBeNull();
  });
});

describe('the operator portal side menu (issue #455)', () => {
  function portal(at = '/platform', busy = false) {
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [at] }) });
    const signOut = vi.fn();
    render(
      <RouterContextProvider router={router}>
        <TourProvider me={officer} saveProgress={false}>
          <PortalFrame name="Test Operator 01" busy={busy} onSignOut={signOut}>
            <p>Page</p>
          </PortalFrame>
        </TourProvider>
      </RouterContextProvider>,
    );
    return { signOut };
  }
  const AREAS = ['Applications', 'Businesses', 'Plans and prices', 'Messages not sent', 'Feedback'];

  it('shows the sidebar from 720px with the portal areas and Sign out in the bar', () => {
    widthIs(1280);
    const { signOut } = portal('/platform/businesses');
    const nav = within(document.querySelector('.side-nav') as HTMLElement).getByRole('navigation', { name: 'Operator portal' });
    expect(within(nav).getAllByRole('link').map((a) => a.textContent)).toEqual(AREAS);
    expect(within(nav).getByRole('link', { name: 'Businesses' }).getAttribute('aria-current')).toBe('page');
    expect(within(nav).getByRole('link', { name: 'Applications' }).getAttribute('aria-current')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Menu' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(signOut).toHaveBeenCalledTimes(1);
  });

  it('opens the same areas in a drawer on a phone, with the name and Sign out at the bottom', () => {
    const { signOut } = portal();
    expect(document.querySelector('.side-nav')).toBeNull();
    const dialog = openMenu();
    expect(within(within(dialog).getByRole('navigation', { name: 'Operator portal' })).getAllByRole('link').map((a) => a.textContent)).toEqual(AREAS);
    expect(within(dialog).getByText('Test Operator 01')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Sign out' }));
    expect(signOut).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(drawer()).toBeNull();
  });

  it('holds Sign out in the drawer and in the bar while signing out runs (issue #456)', () => {
    const { signOut } = portal('/platform', true);
    const inDrawer = within(openMenu()).getByRole('button', { name: 'Sign out' });
    expect(inDrawer.getAttribute('aria-busy')).toBe('true');
    fireEvent.click(inDrawer);
    expect(signOut).not.toHaveBeenCalled();
    cleanup();
    widthIs(1280);
    const wide = portal('/platform', true);
    const inBar = screen.getByRole('button', { name: 'Sign out' });
    expect(inBar.getAttribute('aria-busy')).toBe('true');
    fireEvent.click(inBar);
    expect(wide.signOut).not.toHaveBeenCalled();
  });
});
