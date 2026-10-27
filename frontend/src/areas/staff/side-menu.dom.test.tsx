// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { type ReactNode, useEffect, useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type Me } from '../../api/client';
import { mockMe } from '../../api/retail-mock';
import { router as appRouter } from '../../app/router';
import { BusyAnnouncer } from '../../app/ui/BusyButton';
import { setAccessToken } from '../../auth/session';
import { TourProvider, useTour } from '../../tour/host';
import type { Tour } from '../../tour/model';
import { StaffContext } from './context';
import { homeSummaryKey } from './home-cards';
import { PortalFrame } from '../platform/route';
import { staffAreas, staffMenu } from './menu';
import { SCREENS, SCREEN_CAPABILITY, needsOf } from './retail/permissions';
import { StaffBar, StaffSideNav } from './route';

// The staff menu (issue #564, owner decisions D38 and D44, ADR-086): one grouped list, the same on a phone and a laptop. Home, then
// Today, Stock, Money, Lending when the business has it, and Business, each under a small muted heading, every page the session
// may open and nothing else, then a footer with Notifications, Help and feedback, the person and Sign out. From 720px it is a
// fixed sidebar; below 720px the menu button at the top left of the top bar opens it as a drawer, which keeps what #455 gave it:
// it closes on Escape, the backdrop, a left swipe or a chosen link and gives focus back to the button, keeps Tab inside, sets
// aria-current, and a tour step that points at a menu item opens it. The bottom tabs stay as they are (tabs.dom.test.tsx). Every
// name is invented.

const TOURS_OFF = { 'tours-off': { status: 'dismissed', version: 1 } };

const officer = {
  user_id: '00000000-0000-4000-8000-0000000000a1',
  full_name: 'Test Officer 01',
  kind: 'staff',
  permissions: ['lending.loans.read', 'lending.members.read', 'lending.insights.read', 'core.approvals.read'],
  modules: ['lending'],
  branches: [],
  roles: [{ role_key: 'loan_officer', role_name: 'Loan officer' }],
  tours: TOURS_OFF,
} as unknown as Me;

const admin = { ...mockMe('admin'), is_owner: true, tours: TOURS_OFF } as unknown as Me;

// A business with both modules: the shop's administrator who may also read loans and members.
const both = { ...admin, permissions: [...(admin.permissions ?? []), 'lending.loans.read', 'lending.members.read'], modules: ['lending', 'retail'] } as unknown as Me;

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

// The sidebar and the top bar as the staff route draws them: Sign out is busy from the press until signing out is over.
function Staff({ me, signOut, done, onBranch }: { me: Me; signOut: () => void; done?: Promise<void>; onBranch: (branch: string) => void }) {
  const [busy, setBusy] = useState(false);
  const onSignOut = () => {
    if (done) setBusy(true);
    signOut();
    void done?.then(() => setBusy(false));
  };
  return (
    <>
      <StaffSideNav me={me} onBranch={onBranch} busy={busy} onSignOut={onSignOut} />
      <StaffBar me={me} branch={me.branches?.[0]?.id ?? null} onBranch={onBranch} busy={busy} onSignOut={onSignOut} />
    </>
  );
}

function show(me: Me, { at = '/staff', children = null as ReactNode, signingOut = undefined as Promise<void> | undefined, counts = undefined as { open_orders?: number; low_stock?: number } | undefined } = {}) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [at] }) });
  const signOut = vi.fn();
  const onBranch = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  if (counts) client.setQueryData(homeSummaryKey(me.branches?.[0]?.id), counts);
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? null }}>
          <TourProvider me={me} saveProgress={false}>
            <Staff me={me} signOut={signOut} done={signingOut} onBranch={onBranch} />
            {children}
          </TourProvider>
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  return { router, signOut, onBranch };
}

const menuButton = () => screen.getByRole('button', { name: 'Menu' });
const drawer = () => screen.queryByRole('dialog', { name: 'Menu' });
const sidebar = () => document.querySelector('.side-nav') as HTMLElement;

function openMenu() {
  fireEvent.click(menuButton());
  const found = drawer();
  expect(found).not.toBeNull();
  return found as HTMLElement;
}

/** The menu as it reads: each group's heading (none for Home) and the labels of its links, in order. */
function groupsOf(root: HTMLElement): [string, string[]][] {
  const nav = within(root).getByRole('navigation', { name: 'Staff areas' });
  return Array.from(nav.querySelectorAll<HTMLElement>('.side-menu-group')).map((g) => [
    g.querySelector('.side-menu-heading')?.textContent ?? '',
    Array.from(g.querySelectorAll('a .side-menu-label')).map((l) => l.textContent ?? ''),
  ]);
}

/** Every link of the list, by its label. */
const labels = (root: HTMLElement) => groupsOf(root).flatMap(([, links]) => links);

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

const RETAIL_ONLY: [string, string[]][] = [
  ['', ['Home', 'How do I?']],
  ['Today', ['Sell', 'Orders']],
  ['Stock', ['My stock', 'Buy stock', 'Count stock', 'Quick add', 'Move stock', 'Items and categories']],
  ['Money', ['Cash book', 'Expenses', 'Customers who owe', 'What I owe suppliers', 'Savings and advances']],
  ['Business', ['Reports', 'Controls', 'Business set-up']],
];

describe('the groups and their order (issue #564)', () => {
  it('lists a shop business as drawn: Home, Today, Stock, Money and Business, on a phone and from 720px alike', () => {
    show(admin);
    expect(groupsOf(openMenu())).toEqual(RETAIL_ONLY);
    cleanup();
    widthIs(false);
    show(admin);
    expect(groupsOf(sidebar())).toEqual(RETAIL_ONLY);
  });

  it('lists a lending business with its Lending group and no shop rows', () => {
    const lender = { ...officer, permissions: [...(officer.permissions ?? []), 'core.users.read', 'core.settings.manage'] } as unknown as Me;
    widthIs(false);
    show(lender);
    expect(groupsOf(sidebar())).toEqual([
      ['', ['Home', 'How do I?']],
      ['Today', ['Approvals']],
      ['Lending', ['Loans', 'Members', 'Insights']],
      ['Business', ['Staff', 'Business set-up']],
    ]);
  });

  it('puts the Lending group after Money for a business with both, in the same look', () => {
    widthIs(false);
    show(both);
    expect(groupsOf(sidebar())).toEqual([...RETAIL_ONLY.slice(0, 4), ['Lending', ['Loans', 'Members']], RETAIL_ONLY[4]]);
    const headings = Array.from(sidebar().querySelectorAll('.side-menu-heading'));
    expect(new Set(headings.map((h) => h.tagName + h.className)).size).toBe(1);
    // No headed Shop or Lending section that folds, and no nested Retail group: the menu of #471 is gone.
    expect(within(sidebar()).queryByRole('button', { name: 'Shop' })).toBeNull();
    expect(within(sidebar()).queryByRole('link', { name: 'Retail' })).toBeNull();
  });

  it('lists Controls under Business for a holder of retail.controls.read, current on its page and Reports not (issue #357)', () => {
    widthIs(false);
    show(admin, { at: '/staff/retail/controls' });
    const business = within(sidebar()).getByRole('group', { name: 'Business' });
    expect(within(business).getByRole('link', { name: 'Controls' }).getAttribute('aria-current')).toBe('page');
    expect(within(business).getByRole('link', { name: 'Reports' }).getAttribute('aria-current')).toBeNull();
    cleanup();
    const seller = { ...admin, permissions: (admin.permissions ?? []).filter((p) => p !== 'retail.controls.read') } as unknown as Me;
    show(seller);
    expect(within(sidebar()).queryByRole('link', { name: 'Controls' })).toBeNull();
  });

  it('adds a Wholesale group after Money while a wholesale tool is on, and none for shops only (issue #424)', () => {
    const scope = { all_branches: true, branch_ids: [] };
    const permissions = [...new Set([...(admin.permissions ?? []), ...SCREENS.flatMap((s) => needsOf(s.screen)), 'retail.catalogue.manage'])];
    const wholesaler = (tools: string[]) =>
      ({ ...admin, permissions, permission_scopes: Object.fromEntries(permissions.map((p) => [p, scope])), module_capabilities: { retail: tools } }) as unknown as Me;
    widthIs(false);
    show(wholesaler(['units', 'price_lists', 'customer_accounts', 'documents', 'purchasing']));
    const groups = groupsOf(sidebar());
    expect(groups.map(([heading]) => heading)).toEqual(['', 'Today', 'Stock', 'Money', 'Wholesale', 'Business']);
    expect(groups[4]).toEqual([
      'Wholesale',
      ['Price lists', 'Credit to shops', 'Quotations', 'Invoices and delivery notes', 'Cartons, boxes and other packs', 'Purchase orders'],
    ]);
    const wholesale = within(sidebar()).getByRole('group', { name: 'Wholesale' });
    expect(within(wholesale).getByRole('link', { name: 'Price lists' }).getAttribute('href')).toBe('/staff/retail/price-lists');
    expect(within(wholesale).getByRole('link', { name: 'Cartons, boxes and other packs' }).getAttribute('href')).toBe('/staff/retail/catalogue/products');
    cleanup();
    // The group reads the switches, never the kind: one tool on shows its row alone; a store and shop business with only the two-step
    // move on, or shops only, has no Wholesale group.
    show(wholesaler(['price_lists']));
    expect(groupsOf(sidebar()).find(([heading]) => heading === 'Wholesale')).toEqual(['Wholesale', ['Price lists']]);
    cleanup();
    show(wholesaler(['transfer_receipt']));
    expect(groupsOf(sidebar()).map(([heading]) => heading)).not.toContain('Wholesale');
    cleanup();
    show(wholesaler([]));
    expect(groupsOf(sidebar()).map(([heading]) => heading)).not.toContain('Wholesale');
  });

  it('shows a seller no Wholesale group even with every wholesale tool on', () => {
    const seller = { ...mockMe('sales'), tours: TOURS_OFF, module_capabilities: { retail: ['units', 'price_lists', 'customer_accounts', 'documents', 'purchasing'] } } as unknown as Me;
    expect(staffMenu(seller, { sells: true }).find((g) => g.id === 'wholesale')?.links).toEqual([]);
  });

  it('names each group by its heading for a screen reader', () => {
    widthIs(false);
    show(admin);
    const stock = within(sidebar()).getByRole('group', { name: 'Stock' });
    expect(within(stock).getAllByRole('link')).toHaveLength(6);
  });

  it('ends with Notifications, Help and feedback, then the initials, name and role of the person and Sign out', () => {
    const { signOut } = show(officer);
    const dialog = openMenu();
    const foot = dialog.querySelector('.side-menu-foot') as HTMLElement;
    expect(within(foot).getByRole('link', { name: 'Notifications' }).getAttribute('data-tour')).toBe('nav-notifications');
    expect(within(foot).getByRole('button', { name: 'Help and feedback' }).getAttribute('data-tour')).toBe('nav-feedback');
    expect(foot.querySelector('.side-menu-avatar')?.textContent).toBe('TO');
    expect(within(foot).getByText('Test Officer 01')).toBeTruthy();
    expect(within(foot).getByText('Loan officer')).toBeTruthy();
    fireEvent.click(within(foot).getByRole('button', { name: 'Sign out' }));
    expect(signOut).toHaveBeenCalledTimes(1);
  });

  it('heads the menu with the business, the role and the branch in use, which switches branch', () => {
    widthIs(false);
    const { onBranch } = show(admin);
    const top = sidebar().querySelector('.menu-shop') as HTMLElement;
    expect(within(top).getByText('Owner')).toBeTruthy();
    const picker = within(top).getByRole('combobox', { name: 'Switch branch' }) as HTMLSelectElement;
    const other = admin.branches?.[1]?.id as string;
    fireEvent.change(picker, { target: { value: other } });
    expect(onBranch).toHaveBeenCalledWith(other);
  });

  it('opens the Help sheet from Help and feedback, closing the drawer first', async () => {
    const showModal = vi.fn();
    HTMLDialogElement.prototype.showModal = showModal;
    show(officer);
    fireEvent.click(within(openMenu()).getByRole('button', { name: 'Help and feedback' }));
    expect(drawer()).toBeNull();
    await waitFor(() => expect(showModal).toHaveBeenCalledTimes(1));
    expect(showModal.mock.contexts[0]).toBeInstanceOf(HTMLDialogElement);
  });
});

describe('the counts at the right of a row', () => {
  it('shows the open orders and the items low on stock as muted figures, read out with the row', () => {
    widthIs(false);
    show(admin, { counts: { open_orders: 3, low_stock: 5 } });
    const row = (label: string) => Array.from(sidebar().querySelectorAll('a')).find((a) => a.querySelector('.side-menu-label')?.textContent === label);
    expect(row('Orders')?.textContent).toBe('Orders, 3');
    expect(row('Orders')?.querySelector('.side-menu-count .visually-hidden')?.textContent).toBe(', ');
    expect(row('My stock')?.textContent).toBe('My stock, 5 low');
  });

  it('shows no figure while there is nothing open or low', () => {
    widthIs(false);
    show(admin, { counts: { open_orders: 0, low_stock: 0 } });
    expect(sidebar().querySelector('.side-menu-count')).toBeNull();
  });
});

describe('the menu follows the permissions', () => {
  const session = (permissions: string[], modules: string[]) => ({ ...officer, permissions, modules }) as unknown as Me;
  const names = (me: Me, sells = true) => staffAreas(me, { sells }).map((a) => a.label);
  const cases: { role: string; me: Me; has: string[]; lacks: string[] }[] = [
    { role: 'collateral only', me: session(['lending.collateral.read'], ['lending']), has: ['Collateral'], lacks: ['Members', 'Sell', 'Business set-up'] },
    { role: 'members and collateral', me: session(['lending.members.read', 'lending.collateral.read'], ['lending']), has: ['Members'], lacks: ['Collateral'] },
    { role: 'settings manager', me: session(['core.settings.manage'], []), has: ['Business set-up'], lacks: ['Reports', 'Members'] },
    { role: 'no settings', me: session(['core.approvals.read'], []), has: ['Approvals'], lacks: ['Business set-up'] },
    { role: 'retail seller', me: { ...mockMe('sales'), tours: TOURS_OFF } as unknown as Me, has: ['Sell', 'My stock', 'Reports'], lacks: ['Business set-up', 'Buy stock', 'Cash book', 'Members'] },
    { role: 'retail admin', me: admin, has: ['Sell', 'Buy stock', 'Cash book', 'Business set-up'], lacks: ['Collateral', 'Loans'] },
    { role: 'retail switched off', me: { ...admin, modules: ['lending'] } as Me, has: ['Business set-up'], lacks: ['Sell', 'My stock', 'Reports'] },
  ];

  it.each(cases)('$role', ({ me, has, lacks }) => {
    const list = names(me);
    for (const label of [...has, 'Home', 'Notifications', 'Feedback']) expect(list, label).toContain(label);
    for (const label of lacks) expect(list, label).not.toContain(label);
    widthIs(1280);
    show(me);
    const wide = labels(sidebar());
    expect(wide).toEqual(list.filter((l) => l !== 'Notifications' && l !== 'Feedback'));
    cleanup();
    widthIs(360);
    show(me);
    expect(labels(openMenu())).toEqual(wide);
  });

  it('lists Sell only for a session that can sell', () => {
    expect(staffAreas(admin, { sells: true }).map((a) => a.label)).toContain('Sell');
    expect(staffAreas(admin, { sells: false }).map((a) => a.label)).not.toContain('Sell');
  });

  it('has no cap: a shop with every page lists every row of Today, Stock and Money', () => {
    const permissions = [...new Set([...(admin.permissions ?? []), ...SCREENS.flatMap((s) => needsOf(s.screen))])];
    const scope = { all_branches: true, branch_ids: [] };
    const whole = { ...admin, permissions, permission_scopes: Object.fromEntries(permissions.map((p) => [p, scope])), module_capabilities: { retail: [...new Set(Object.values(SCREEN_CAPABILITY))] } } as Me;
    const shop = staffMenu(whole, { sells: true }).filter((g) => ['today', 'stock', 'money'].includes(g.id));
    expect(shop.flatMap((g) => g.links).length).toBe(13);
    expect(shop.find((g) => g.id === 'stock')?.links.length).toBe(6);
    expect(shop.find((g) => g.id === 'money')?.links.length).toBe(5);
  });
});

describe('the current page', () => {
  beforeEach(() => widthIs(false));
  const current = () => within(sidebar()).queryAllByRole('link').filter((a) => a.getAttribute('aria-current') === 'page').map((a) => a.querySelector('.side-menu-label')?.textContent);

  it.each([
    ['/staff', 'Home'],
    ['/staff/retail/stock', 'My stock'],
    ['/staff/retail/catalogue/products', 'Items and categories'],
    ['/staff/retail/sale', 'Sell'],
    ['/staff/retail', 'Reports'],
    ['/staff/notifications', 'Notifications'],
  ])('marks one row current on %s: %s', (at, label) => {
    show(admin, { at });
    expect(current()).toEqual([label]);
  });
});

describe('the drawer on a phone (issues #455, #564)', () => {
  it('opens from the menu button at the top left of the top bar as a modal dialog, with the bottom tabs left alone', () => {
    show(officer);
    expect(screen.queryByRole('navigation', { name: 'Staff areas' })).toBeNull();
    expect(drawer()).toBeNull();
    const bar = document.querySelector('.staff-bar-top') as HTMLElement;
    expect(bar.firstElementChild).toBe(menuButton());
    expect(menuButton().getAttribute('aria-expanded')).toBe('false');
    const dialog = openMenu();
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(menuButton().getAttribute('aria-expanded')).toBe('true');
    expect(dialog.contains(document.activeElement)).toBe(true);
    expect(document.body.classList.contains('side-menu-open')).toBe(true);
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
    // From 720px Sign out is at the foot of the sidebar, and in the top bar's one menu (issue #526).
    await screen.findByRole('button', { name: /^Menu for / }, { timeout: 5000 });
    const button = within(sidebar()).getByRole('button', { name: 'Sign out' });
    fireEvent.click(button);
    await waitFor(() => expect(logout).toHaveBeenCalledWith('/api/v1/auth/logout'));
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.getAttribute('aria-disabled')).toBe('true');
  });

  it('shows Sign out in the top bar menu busy while signing out from 720px', async () => {
    widthIs(false);
    let finish = () => undefined as void;
    show(officer, { signingOut: new Promise<void>((resolve) => (finish = resolve)) });
    fireEvent.click(screen.getByRole('button', { name: /^Menu for / }));
    const button = within(document.querySelector('.top-menu') as HTMLElement).getByRole('button', { name: 'Sign out' });
    fireEvent.click(button);
    expect(button.getAttribute('aria-busy')).toBe('true');
    // The sidebar's own Sign out follows the same signing out.
    expect(within(sidebar()).getByRole('button', { name: 'Sign out' }).getAttribute('aria-busy')).toBe('true');
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

  it('marks the current page with aria-current', () => {
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

  it('closes on a tap outside it and gives focus back to the menu button', async () => {
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

  it('keeps Tab inside the drawer, from Sign out round to the close button, which the keyboard reaches first', () => {
    show(officer);
    const dialog = openMenu();
    const close = within(dialog).getByRole('button', { name: 'Close menu' });
    expect(document.activeElement).toBe(close);
    const signOut = within(dialog).getByRole('button', { name: 'Sign out' });
    signOut.focus();
    fireEvent.keyDown(signOut, { key: 'Tab' });
    expect(document.activeElement).toBe(close);
    fireEvent.keyDown(document.activeElement as Element, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(signOut);
  });

  it('goes to the chosen page and closes', async () => {
    const { router } = show(officer);
    const dialog = openMenu();
    fireEvent.click(within(dialog).getByRole('link', { name: 'Notifications' }));
    expect(drawer()).toBeNull();
    await waitFor(() => expect(router.history.location.pathname).toBe('/staff/notifications'));
    await waitFor(() => expect(document.activeElement).toBe(menuButton()));
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
    fireEvent.click(within(openMenu()).getByRole('link', { name: 'Notifications' }));
    expect(drawer()).toBeNull();
    await waitFor(() => expect(router.history.location.pathname).toBe('/staff/notifications'));
    expect(window.scrollTo).not.toHaveBeenCalledWith(0, 300);
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 });
  });

  it('gives the scroll position back when the tapped link is the page already shown', () => {
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 300 });
    show(officer, { at: '/staff/notifications' });
    fireEvent.click(within(openMenu()).getByRole('link', { name: 'Notifications' }));
    expect(drawer()).toBeNull();
    expect(window.scrollTo).toHaveBeenCalledWith(0, 300);
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 });
  });

  it.each([{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { altKey: true }, { button: 1 }])('gives the scroll position back when a link opens elsewhere (%o)', (keys) => {
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 300 });
    const { router } = show(officer);
    fireEvent.click(within(openMenu()).getByRole('link', { name: 'Notifications' }), keys);
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
    // Today carries the shop's anchor, so the selling tour's menu step lights up Sell and Orders.
    const today = document.querySelector('[data-tour="retail-nav"]') as HTMLElement;
    expect(Array.from(today.querySelectorAll('a .side-menu-label')).map((a) => a.textContent)).toEqual(['Sell', 'Orders']);
  });

  it('puts the selling tour anchor on Today only while Today holds Sell or Orders', () => {
    const anchor = (me: Me) => staffMenu(me, { sells: true }).find((g) => g.id === 'today')?.tour;
    expect(anchor(admin)).toBe('retail-nav');
    // Approvals alone in Today: the step that names Sell and Orders has no anchor there.
    expect(anchor(officer)).toBeUndefined();
    const approverInShop = { ...officer, permissions: ['core.approvals.read', 'retail.stock.read'], modules: ['retail'] } as unknown as Me;
    expect(staffMenu(approverInShop, { sells: false }).find((g) => g.id === 'today')?.links.map((l) => l.label)).toEqual(['Approvals']);
    expect(anchor(approverInShop)).toBeUndefined();
  });

  it.each(['nav-feedback', 'nav-notifications', 'nav-members'])('opens for a tour step that points at %s, and closes when the tour ends', async (target) => {
    const tour: Tour = {
      id: 'test-menu-tour',
      version: 1,
      title: 'Test tour',
      audience: () => true,
      autoStart: false,
      steps: [{ id: 'step', target, title: 'In the menu', body: 'Tap it.' }],
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
    await screen.findByRole('heading', { name: 'In the menu' });
    const panel = document.querySelector('.side-menu') as HTMLElement;
    expect(panel.hidden).toBe(false);
    expect(panel.classList.contains('side-menu-instant')).toBe(true);
    // Shown for a tour, the page behind stays reachable: the drawer is not modal (aria-modal only when the person opened it).
    expect(panel.querySelector('[role="dialog"]')?.hasAttribute('aria-modal')).toBe(false);
    expect(document.activeElement?.textContent).toBe('In the menu');
    act(() => {
      fireEvent.keyDown(document.activeElement as Element, { key: 'Escape' });
    });
    await waitFor(() => expect(panel.hidden).toBe(true));
  });
});

describe('the fixed sidebar from 720px (issues #455, #564)', () => {
  beforeEach(() => widthIs(false));

  it('shows the sidebar with no drawer button and no button that folds it', () => {
    show(admin);
    const nav = within(sidebar()).getByRole('navigation', { name: 'Staff areas' });
    expect(nav.getAttribute('data-tour')).toBe('nav-areas');
    expect(screen.getAllByRole('navigation', { name: 'Staff areas' })).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Menu' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Menu labels' })).toBeNull();
    expect(sidebar().className).toBe('side-nav');
  });

  it('comes before the staff bar, so Tab reaches the menu before the page', () => {
    show(officer);
    const bar = document.querySelector('.staff-bar') as Element;
    expect(sidebar().compareDocumentPosition(bar) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('lists the same items as the phone drawer for the same permissions', () => {
    for (const me of [officer, admin, both]) {
      show(me);
      const wide = groupsOf(sidebar());
      cleanup();
      widthIs(true);
      show(me);
      expect(groupsOf(openMenu()), me.full_name).toEqual(wide);
      cleanup();
      widthIs(false);
    }
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
  const AREAS = ['Applications', 'Businesses', 'Plans and prices', 'Due for suspension', 'Businesses closing', 'Businesses with no owner', 'Messages not sent', 'Feedback'];

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
