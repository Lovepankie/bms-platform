// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { useEffect, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../api/client';
import { retail } from '../../api/retail';
import { retailAnalytics } from '../../api/retail-analytics';
import { mockMe } from '../../api/retail-mock';
import { router as appRouter } from '../../app/router';
import { TourProvider, useTour } from '../../tour/host';
import type { Tour } from '../../tour/model';
import { StaffContext } from './context';
import { homeSummaryKey } from './home-cards';
import { StaffBar, StaffSideNav } from './route';
import { MoneyPage, MorePage } from './tab-pages';
import { StaffTabBar, currentTab, moneyLinks, staffTabs } from './tabs';

// The bottom tabs on a phone (issue #539): Sell, My stock, Buy, Money and More, the ones the session may use, with the current one
// marked for the eye and for a screen reader, a badge only for items low on stock, no tabs from 720px, no menu button below it, and
// the root's has-tab-bar class while they show so the sticky action bars sit on top of them. Every name and figure is invented.

const TOURS_OFF = { 'tours-off': { status: 'dismissed', version: 1 } };
const admin = { ...mockMe('admin'), tours: TOURS_OFF } as unknown as Me;
const seller = { ...mockMe('sales'), tours: TOURS_OFF } as unknown as Me;
const officer = {
  user_id: '00000000-0000-4000-8000-0000000000a1',
  full_name: 'Test Officer 01',
  kind: 'staff',
  permissions: ['lending.loans.read', 'lending.members.read', 'core.approvals.read'],
  modules: ['lending'],
  branches: [],
  tours: TOURS_OFF,
} as unknown as Me;

function widthIs(px: number) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: px <= Number(/max-width: (\d+)px/.exec(query)?.[1] ?? Infinity),
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
}

function show(me: Me, { at = '/staff', children = null as ReactNode, client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } }) } = {}) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [at] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? null }}>
          <TourProvider me={me} saveProgress={false}>
            <StaffSideNav me={me} />
            <StaffBar me={me} branch={null} onBranch={() => undefined} onSignOut={() => undefined} />
            {children}
            <StaffTabBar me={me} />
          </TourProvider>
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  return { router };
}

const bar = () => screen.getByRole('navigation', { name: 'Main' });
const tabNames = () => within(bar()).getAllByRole('link').map((a) => a.querySelector('.tab-label')?.textContent);

beforeEach(() => {
  widthIs(360);
  vi.spyOn(retailAnalytics, 'home').mockResolvedValue({ low_stock: 0 } as never);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  document.documentElement.className = '';
  document.documentElement.removeAttribute('style');
});

describe('the bottom tabs on a phone (issue #539)', () => {
  it('shows the five tabs in order for an owner, as links in one labelled navigation, with no menu button and no sidebar', () => {
    show(admin);
    expect(tabNames()).toEqual(['Sell', 'My stock', 'Buy', 'Money', 'More']);
    expect(bar().tagName).toBe('NAV');
    expect(screen.queryByRole('button', { name: 'Menu' })).toBeNull();
    expect(document.querySelector('.side-nav')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Sign out' })).toBeNull();
  });

  it('goes to each tab page on a tap', async () => {
    const { router } = show(admin);
    const paths: Record<string, string> = { Sell: '/staff/retail/sale', 'My stock': '/staff/retail/stock', Buy: '/staff/retail/restock', Money: '/staff/money', More: '/staff/more' };
    for (const [name, path] of Object.entries(paths)) {
      fireEvent.click(within(bar()).getByRole('link', { name: new RegExp(`^${name}`) }));
      await waitFor(() => expect(router.history.location.pathname).toBe(path));
    }
  });

  it('marks the current tab with aria-current on its page and the pages under it, and none on Home', () => {
    show(admin, { at: '/staff/retail/stocktake' });
    const current = () => within(bar()).getAllByRole('link').filter((a) => a.getAttribute('aria-current') === 'page').map((a) => a.querySelector('.tab-label')?.textContent);
    expect(current()).toEqual(['My stock']);
    cleanup();
    show(admin, { at: '/staff/retail/banking' });
    expect(current()).toEqual(['Money']);
    cleanup();
    show(admin);
    expect(current()).toEqual([]);
  });

  it('reaches every tab with Tab, in order, each a plain link a keyboard opens with Enter', () => {
    show(admin);
    const links = within(bar()).getAllByRole('link');
    for (const link of links) {
      expect(link.tagName).toBe('A');
      expect(link.getAttribute('href')).toMatch(/^\/staff\//);
      expect(link.getAttribute('tabindex')).toBeNull();
    }
  });

  it('hides the shop tabs when retail is switched off, and Money when no money page is allowed, leaving More', () => {
    show({ ...admin, modules: ['lending'] } as Me);
    expect(tabNames()).toEqual(['More']);
    cleanup();
    show(officer);
    expect(tabNames()).toEqual(['More']);
  });

  it('follows the permissions: a seller has no Buy, and Money only for who owes them', () => {
    show(seller);
    expect(tabNames()).toEqual(['Sell', 'My stock', 'Money', 'More']);
    expect(moneyLinks(seller).map((l) => l.label)).toEqual(['Customers who owe', 'Credit control']);
  });

  it('shows Sell only once the branch roles are read, and never when every branch is a store', async () => {
    const withRoles = { ...admin, module_capabilities: { retail: ['branch_roles'] } } as Me;
    const roles = (role: 'store' | 'shop') => (withRoles.branches ?? []).map((b) => ({ branch_id: b.id, code: b.code, name: b.name, role, can_sell: role !== 'store' }));
    let answer: (value: ReturnType<typeof roles>) => void = () => undefined;
    const read = vi.spyOn(retail, 'branchRoles').mockImplementation(() => new Promise((resolve) => (answer = resolve as typeof answer)));
    show(withRoles);
    await waitFor(() => expect(read).toHaveBeenCalled());
    expect(tabNames()).not.toContain('Sell');
    await act(async () => answer(roles('store')));
    expect(tabNames()).not.toContain('Sell');
    cleanup();
    read.mockResolvedValue(roles('shop') as never);
    show(withRoles);
    await waitFor(() => expect(tabNames()[0]).toBe('Sell'));
  });

  it('shows the count of items low on stock on My stock, read out with the label, and no badge at none', async () => {
    vi.mocked(retailAnalytics.home).mockResolvedValue({ low_stock: 3 } as never);
    show(admin);
    const stock = within(bar()).getByRole('link', { name: /^My stock/ });
    await waitFor(() => expect(stock.querySelector('.tab-badge')?.textContent).toBe('3'));
    expect(stock.querySelector('.tab-badge')?.getAttribute('aria-hidden')).toBe('true');
    expect(stock.textContent).toContain('3 items low on stock');
    expect(document.querySelectorAll('.tab-badge')).toHaveLength(1);
    cleanup();
    vi.mocked(retailAnalytics.home).mockResolvedValue({ low_stock: 0 } as never);
    show(admin);
    await waitFor(() => expect(retailAnalytics.home).toHaveBeenCalled());
    expect(document.querySelector('.tab-badge')).toBeNull();
  });

  it('marks the root while it shows, so the sticky action bars sit on top of it', () => {
    show(admin);
    expect(document.documentElement.classList.contains('has-tab-bar')).toBe(true);
    cleanup();
    expect(document.documentElement.classList.contains('has-tab-bar')).toBe(false);
  });

  // A visual viewport the test sizes: 900 tall like the window, or shrunk by a keyboard. Frames run at once.
  function viewport(height: number) {
    const view = Object.assign(new EventTarget(), { height });
    vi.stubGlobal('visualViewport', view);
    vi.stubGlobal('innerHeight', 900);
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((step) => {
      step(0);
      return 0;
    });
    return {
      resize(to: number) {
        act(() => {
          view.height = to;
          view.dispatchEvent(new Event('resize'));
        });
      },
    };
  }

  it('stays while a field only has focus: a page that focuses its Scan box, or a barcode scanner typing into it', () => {
    viewport(900);
    show(admin, { children: <input aria-label="Scan here" /> });
    act(() => screen.getByRole('textbox', { name: 'Scan here' }).focus());
    expect(bar().hidden).toBe(false);
    expect(document.documentElement.classList.contains('has-tab-bar')).toBe(true);
  });

  it('steps aside while the soft keyboard is up, staying mounted, and comes back when it goes', () => {
    const view = viewport(900);
    show(admin, { children: <input aria-label="Item" /> });
    const nav = bar();
    act(() => screen.getByRole('textbox', { name: 'Item' }).focus());
    view.resize(520);
    expect(nav.isConnected).toBe(true);
    expect(nav.hidden).toBe(true);
    expect(document.documentElement.classList.contains('tab-bar-aside')).toBe(true);
    view.resize(900);
    expect(nav.hidden).toBe(false);
    expect(document.documentElement.classList.contains('tab-bar-aside')).toBe(false);
  });

  // Issue #544: dropping has-tab-bar while the keyboard opened took the body's padding away, and a page with a field near its end
  // got about 65px shorter under it. The padding and the measured height stay; only the sticky bars' offset drops.
  it('keeps the body padding and the measured height while the keyboard has it aside', () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return { height: this.classList.contains('tab-bar') && !this.hidden ? 65 : 0 } as DOMRect;
    });
    const view = viewport(900);
    show(admin, { children: <input aria-label="Item" /> });
    const root = document.documentElement;
    expect(root.style.getPropertyValue('--tab-bar')).toBe('65px');
    act(() => screen.getByRole('textbox', { name: 'Item' }).focus());
    view.resize(520);
    expect((document.querySelector('.tab-bar') as HTMLElement).hidden).toBe(true);
    expect(root.classList.contains('has-tab-bar')).toBe(true);
    expect(root.style.getPropertyValue('--tab-bar')).toBe('65px');
    expect(root.classList.contains('tab-bar-aside')).toBe(true);
  });

  it.each([
    ['an empty contenteditable', ''],
    ['a contenteditable="true"', 'true'],
    ['a contenteditable="plaintext-only"', 'plaintext-only'],
  ])('steps aside for the keyboard of %s, and not for contenteditable="false"', (_name, value) => {
    const view = viewport(900);
    show(admin, {
      children: (
        <>
          <div aria-label="Note" tabIndex={0} contentEditable={value as never} suppressContentEditableWarning />
          <div aria-label="Shut" tabIndex={0} contentEditable="false" suppressContentEditableWarning />
        </>
      ),
    });
    // React writes contentEditable="" as the attribute only through setAttribute, so the test sets the exact value.
    const note = document.querySelector('[aria-label="Note"]') as HTMLElement;
    note.setAttribute('contenteditable', value);
    const nav = bar();
    act(() => note.focus());
    view.resize(520);
    expect(nav.hidden).toBe(true);
    act(() => (document.querySelector('[aria-label="Shut"]') as HTMLElement).focus());
    view.resize(519);
    expect(nav.hidden).toBe(false);
  });

  // Under interactive-widget=resizes-content (Android) the window shrinks with the viewport, so the bar never steps aside. That is
  // accepted in docs/ui/design-system.md as long as the app never sets it: this fails the day it does, so the check is revisited.
  it('relies on the viewport meta not setting interactive-widget=resizes-content', () => {
    expect(readFileSync(join(process.cwd(), 'index.html'), 'utf8')).not.toMatch(/interactive-widget\s*=\s*resizes-content/);
  });

  it('stays while a select has focus, even with the viewport short, as its picker is not the keyboard', () => {
    const view = viewport(900);
    show(admin, { children: <select aria-label="Size"><option>Small</option></select> });
    act(() => screen.getByRole('combobox', { name: 'Size' }).focus());
    view.resize(520);
    expect(bar().hidden).toBe(false);
  });

  it('asks the Home summary once, however often the keyboard comes and goes', async () => {
    const view = viewport(900);
    show(admin, { children: <input aria-label="Item" />, client: new QueryClient({ defaultOptions: { queries: { retry: false } } }) });
    await waitFor(() => expect(retailAnalytics.home).toHaveBeenCalledTimes(1));
    const input = screen.getByRole('textbox', { name: 'Item' });
    for (let i = 0; i < 5; i++) {
      act(() => input.focus());
      view.resize(520);
      act(() => input.blur());
      view.resize(900);
    }
    expect(retailAnalytics.home).toHaveBeenCalledTimes(1);
  });

  it('reads the low stock count from the Home summary entry, so Home and the badge share one request', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(homeSummaryKey(admin.branches?.[0]?.id), { low_stock: 5 });
    show(admin, { client });
    await waitFor(() => expect(document.querySelector('.tab-badge')?.textContent).toBe('5'));
    expect(retailAnalytics.home).not.toHaveBeenCalled();
  });

  it('never asks the Home summary for a session Home would not ask for: a storekeeper reads stock but not sales', async () => {
    const storekeeper = { ...admin, permissions: ['retail.stock.read', 'retail.purchase.create', 'retail.stocktake.commit'] } as Me;
    show(storekeeper, { client: new QueryClient({ defaultOptions: { queries: { retry: false } } }) });
    expect(tabNames()).toContain('My stock');
    await act(async () => undefined);
    expect(retailAnalytics.home).not.toHaveBeenCalled();
    expect(document.querySelector('.tab-badge')).toBeNull();
  });

  it('stays through a pinch zoom with a field focused, as the viewport shrinks by its scale and no keyboard is up', () => {
    viewport(900);
    show(admin, { children: <input aria-label="Item" /> });
    act(() => screen.getByRole('textbox', { name: 'Item' }).focus());
    act(() => {
      Object.assign(window.visualViewport as object, { height: 692, scale: 1.3 });
      window.visualViewport?.dispatchEvent(new Event('resize'));
    });
    expect(bar().hidden).toBe(false);
  });

  it('sets --tab-bar to its measured height, so a bar grown by a large font still ends where the action bar begins', () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return { height: this.classList.contains('tab-bar') ? 97.4 : 0 } as DOMRect;
    });
    show(admin);
    expect(document.documentElement.style.getPropertyValue('--tab-bar')).toBe('98px');
    cleanup();
    expect(document.documentElement.style.getPropertyValue('--tab-bar')).toBe('');
  });

  it('keeps Sign out in the staff bar on a phone while two-step set-up blocks the page, which has no tabs', () => {
    const signOut = vi.fn();
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
    render(
      <RouterContextProvider router={router}>
        <QueryClientProvider client={new QueryClient()}>
          <StaffContext.Provider value={{ me: admin, branch: null }}>
            <StaffBar me={admin} branch={null} onBranch={() => undefined} onSignOut={signOut} signOutInBar />
          </StaffContext.Provider>
        </QueryClientProvider>
      </RouterContextProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: /^Menu for / }));
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(signOut).toHaveBeenCalledTimes(1);
  });

  it('is not drawn from 720px, where the sidebar holds the areas', () => {
    widthIs(1280);
    show(admin);
    expect(screen.queryByRole('navigation', { name: 'Main' })).toBeNull();
    expect(document.querySelector('.side-nav')).not.toBeNull();
    expect(document.documentElement.classList.contains('has-tab-bar')).toBe(false);
  });

  it('lights up the More tab for a tour step on a menu link, since the link lives under More on a phone', async () => {
    const tour: Tour = {
      id: 'test-tabs-tour',
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
    vi.stubGlobal('CSS', { escape: (value: string) => value });
    Element.prototype.scrollIntoView = vi.fn();
    show(admin, { children: <Starter /> });
    await screen.findByRole('heading', { name: 'Feedback here' });
    const more = within(bar()).getByRole('link', { name: 'More' });
    expect(more.getAttribute('data-tour-also')?.split(' ')).toEqual(expect.arrayContaining(['nav-areas', 'nav-feedback', 'nav-notifications', 'nav-retail']));
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
    expect(vi.mocked(Element.prototype.scrollIntoView).mock.contexts[0]).toBe(more);
  });
});

describe('the tabs and their pages, read from /me (issue #539)', () => {
  it('names the current tab by the path, a page under it included', () => {
    const tabs = staffTabs(admin, { sells: true });
    expect(currentTab(tabs, '/staff/retail/sale')).toBe('sell');
    expect(currentTab(tabs, '/staff/retail/restocks/abc')).toBe('buy');
    expect(currentTab(tabs, '/staff/more')).toBe('more');
    expect(currentTab(tabs, '/staff/retail/sales')).toBeUndefined();
  });

  it('lists only the money pages the session may use, the capability ones only while switched on', () => {
    expect(moneyLinks(admin).map((l) => l.to)).not.toContain('/staff/retail/accounts');
    const accounts = { ...admin, module_capabilities: { retail: ['customer_accounts'] } } as Me;
    expect(moneyLinks(accounts).map((l) => l.to)).toContain('/staff/retail/accounts');
    expect(moneyLinks(officer)).toEqual([]);
  });

  function page(me: Me, node: ReactNode, signOut?: () => void) {
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/more'] }) });
    render(
      <RouterContextProvider router={router}>
        <QueryClientProvider client={new QueryClient()}>
          <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? null, signOut }}>{node}</StaffContext.Provider>
        </QueryClientProvider>
      </RouterContextProvider>,
    );
  }

  it('Money lists its pages as rows', () => {
    page(admin, <MoneyPage />);
    const rows = within(screen.getByRole('list', { name: 'Money pages' })).getAllByRole('link').map((a) => a.querySelector('.list-row-title')?.textContent);
    expect(rows.slice(0, 3)).toEqual(['Cash summary', 'Banking', 'Shop savings']);
    expect(rows).toContain('Customers who owe');
  });

  // Issue #544: the shop's savings and the lending members' savings sat side by side as "Savings" and "Member savings".
  it('tells the shop savings from the member savings by name and by hint', () => {
    const both = { ...admin, permissions: [...(admin.permissions ?? []), 'lending.savings.read'], modules: ['retail', 'lending'] } as Me;
    const savings = moneyLinks(both).filter((l) => /savings/i.test(l.label));
    expect(savings.map((l) => l.label)).toEqual(['Shop savings', 'Member savings']);
    expect(savings.map((l) => l.hint)).toEqual(["The shop's money set aside each day", 'Deposits and withdrawals of lending members']);
  });

  it('More lists the areas the session may open, under Shop and Lending for a business with both, and signs out', () => {
    const signOut = vi.fn();
    const both = { ...admin, permissions: [...(admin.permissions ?? []), 'lending.loans.read', 'core.approvals.read'], modules: ['retail', 'lending'] } as Me;
    page(both, <MorePage />, signOut);
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['Shop', 'Lending', 'Business', 'You']);
    // The sidebar's order (FR-UXN-04, #544): Home and Approvals first, then Shop and Lending, then the other shared areas.
    const lists = screen.getAllByRole('list').map((l) => l.getAttribute('aria-label'));
    expect(lists.slice(0, 4)).toEqual(['Start', 'Shop', 'Lending', 'Business']);
    expect(within(screen.getByRole('list', { name: 'Start' })).getAllByRole('link').map((a) => a.textContent)).toEqual(['Home', 'Approvals']);
    expect(within(screen.getByRole('list', { name: 'Business' })).queryByRole('link', { name: 'Home' })).toBeNull();
    expect(within(screen.getByRole('list', { name: 'Lending' })).getByRole('link', { name: 'Loans' })).toBeTruthy();
    expect(within(screen.getByRole('list', { name: 'Business' })).getByRole('link', { name: 'Business set-up' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(signOut).toHaveBeenCalledTimes(1);
    cleanup();
    page(seller, <MorePage />, signOut);
    const links = within(screen.getByRole('list', { name: 'Areas' })).getAllByRole('link').map((a) => a.querySelector('.list-row-title')?.textContent);
    expect(links).toContain('Retail');
    expect(links).not.toContain('Business set-up');
    expect(links).not.toContain('Users');
  });
});

// The rules of docs/ui-rules.md the bar's own styles keep, read off navigation.css; the design guard checks the colours and motion.
describe('the tab bar styles', () => {
  const read = (file: string) => readFileSync(join(process.cwd(), 'src/app/ui', file), 'utf8');
  const css = read('navigation.css');
  const rule = (selector: string) => css.match(new RegExp(`\\n${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} \\{([^}]*)\\}`))?.[1] ?? '';

  it('is fixed to the bottom over the safe-area insets, each tab a target taller than 48px', () => {
    expect(rule('.tab-bar')).toMatch(/position: fixed/);
    expect(rule('.tab-bar')).toMatch(/env\(safe-area-inset-bottom\)/);
    expect(rule('.tab-bar')).toMatch(/env\(safe-area-inset-left\)/);
    expect(rule('.tab')).toMatch(/min-height: var\(--tab-height\)/);
    expect(rule('.tab')).toMatch(/min-width: var\(--tap\)/);
    expect(rule('.has-tab-bar')).toMatch(/--tab-bar: calc\(var\(--tab-height\) \+ 1px \+ env\(safe-area-inset-bottom\)\)/);
  });

  // Issue #544: every size comes from a token, so the bar changes with the target and the spacing steps.
  it('uses no raw size but the 1px hairline and the 2px focus ring, and the badge text takes the on-danger role', () => {
    const tabRules = [...css.matchAll(/\n(\.has-tab-bar|\.tab[\w\s.:'=\[\]()-]*) \{([^}]*)\}/g)].map(([, selector, body]) => [selector, body] as const);
    expect(tabRules.length).toBeGreaterThan(8);
    for (const [selector, body] of tabRules) {
      const raw = (body ?? '').replace(/outline: 2px solid/g, '').replace(/border-top: 1px solid/g, '').replace(/\+ 1px \+/g, '').match(/-?\d+px/g);
      expect(raw, selector).toBeNull();
    }
    expect(rule('.tab-badge')).toMatch(/color: var\(--color-on-danger\)/);
    const theme = readFileSync(join(process.cwd(), 'src/app/theme.css'), 'utf8');
    expect(theme).toMatch(/--tab-height: calc\(var\(--tap\) \+ var\(--space-4\)\);/);
  });

  it('marks the current tab without a stripe: the colour, the weight and a pill behind the icon', () => {
    expect(rule(".tab[aria-current='page']")).not.toMatch(/border/);
    expect(rule(".tab[aria-current='page'] .tab-icon::before")).toMatch(/opacity: 1/);
  });

  it('lifts the sale bar and the save bar on top of it, never under it', () => {
    const kitCss = read('kit.css');
    const lendingCss = read('lending.css');
    // The sale's bar is the kit's ActionBar since issue #526.
    expect(kitCss).toMatch(/\.has-tab-bar \.action-bar \{\s*bottom: var\(--tab-bar-offset\);/);
    expect(lendingCss).toMatch(/\.has-tab-bar \.mf-savebar \{\s*bottom: var\(--tab-bar-offset\);/);
    expect(read('feedback.css')).toMatch(/var\(--tab-bar-offset, 0px\)/);
    // The offset is the bar's height, and 0 while the keyboard has the bar aside; the body's padding stays --tab-bar (#544).
    expect(rule('.has-tab-bar')).toMatch(/--tab-bar-offset: var\(--tab-bar\);/);
    expect(rule('.has-tab-bar.tab-bar-aside')).toMatch(/--tab-bar-offset: 0px;/);
    expect(rule('.has-tab-bar body')).toMatch(/padding-bottom: var\(--tab-bar\);/);
  });
});
