// @vitest-environment jsdom
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { OfflineProvider } from '../../offline/offline-context';
import { figuresTime } from '../../offline/read-cache';
import { testOffline } from '../../offline/test-kit';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../api/client';
import { mockMe } from '../../api/retail-mock';
import { router as appRouter } from '../../app/router';
import { ALL_BRANCHES } from '../../auth/branch';
import { TourProvider } from '../../tour/host';
import { StaffContext } from './context';
import { StaffBar } from './route';
import { BranchTitle, OfflineBanner, TopMenu } from './top-bar';

// The staff area's one top bar (issue #526): the business and branch as the title, which switches branch, and one menu with
// who is signed in, Help, the theme and Sign out; Refresh is a button in the bar beside Search again (issue #631). The shop in use shows its own logo in the title (issue #461,
// ADR-068), and a shop without one, or All branches, shows none. Every name below is invented.

const LOGO = '/api/v1/branches/b-two/logo?v=doc-1';

const me = {
  user_id: 'u-self',
  full_name: 'Test Person One',
  all_branches: true,
  branches: [
    { id: 'b-one', code: 'HQ', name: 'Test Shop One', is_head_office: true, stocks_goods: true, sells_goods: true, logo_url: null },
    { id: 'b-two', code: 'KLA', name: 'Test Shop Two', is_head_office: false, stocks_goods: true, sells_goods: true, logo_url: LOGO },
  ],
} as unknown as Me;

const TOURS_OFF = { 'tours-off': { status: 'dismissed', version: 1 } };

function withClient(ui: ReactNode) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <TourProvider me={{ ...me, tours: TOURS_OFF } as unknown as Me} saveProgress={false}>
          {ui}
        </TourProvider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const title = (branch: string | null, who: Me = me, onChoose = () => undefined) => withClient(<BranchTitle me={who} branch={branch} onChoose={onChoose} />);

afterEach(() => cleanup());

describe('the title of the top bar', () => {
  it('names the branch in use and switches branch through a select named Branch', async () => {
    const onChoose = vi.fn();
    title('b-one', me, onChoose);
    expect(document.querySelector('.top-title-branch')?.textContent).toBe('Test Shop One (HQ)');
    const select = screen.getByRole('combobox', { name: 'Branch' }) as HTMLSelectElement;
    expect([...select.options].map((o) => o.textContent)).toEqual(['All branches', 'Test Shop One (HQ)', 'Test Shop Two (KLA)']);
    fireEvent.change(select, { target: { value: 'b-two' } });
    expect(onChoose).toHaveBeenCalledWith('b-two');
  });

  it('is plain text, with no select, for a person who works in one branch', () => {
    title('b-one', { ...me, all_branches: false, branches: me.branches?.slice(0, 1) } as Me);
    expect(document.querySelector('.top-title-branch')?.textContent).toBe('Test Shop One (HQ)');
    expect(screen.queryByRole('combobox')).toBeNull();
  });

  it('shows the logo of the shop in use', async () => {
    title('b-two');
    const image = (await screen.findByAltText('Logo of Test Shop Two (KLA)')) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(LOGO);
    expect(image.className).toBe('shop-logo');
  });

  it('shows none for a shop without its own logo, or for All branches', async () => {
    title('b-one');
    await screen.findByRole('combobox');
    expect(screen.queryByRole('img')).toBeNull();
    cleanup();
    title(ALL_BRANCHES);
    expect(document.querySelector('.top-title-branch')?.textContent).toBe('All branches');
    expect(screen.queryByRole('img')).toBeNull();
  });
});

describe('the menu of the top bar', () => {
  const menu = (signOut = true, onSignOut = () => undefined) => withClient(<TopMenu me={me} busy={false} onSignOut={onSignOut} signOut={signOut} />);

  it('opens from one button and holds who is signed in, Help, the theme and Sign out, and no Refresh (issue #631: it is in the bar)', () => {
    const onSignOut = vi.fn();
    menu(true, onSignOut);
    const button = screen.getByRole('button', { name: 'Menu for Test Person One' });
    expect(button.getAttribute('aria-expanded')).toBe('false');
    const panel = document.getElementById(button.getAttribute('aria-controls') ?? '') as HTMLElement;
    expect(panel.hidden).toBe(true);
    fireEvent.click(button);
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(panel.hidden).toBe(false);
    expect(panel.textContent).toContain('Test Person One');
    expect(screen.getByRole('button', { name: 'Help' })).toBeTruthy();
    expect(panel.querySelector('[data-tour="refresh"]')).toBeNull();
    expect(screen.getByRole('radiogroup', { name: 'Theme' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(onSignOut).toHaveBeenCalled();
  });

  it('closes on Escape and gives focus back to its button', () => {
    menu();
    const button = screen.getByRole('button', { name: 'Menu for Test Person One' });
    fireEvent.click(button);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(button);
  });

  it('leaves Sign out to the More page on a phone', () => {
    menu(false);
    expect(screen.queryByRole('button', { name: 'Sign out', hidden: true })).toBeNull();
  });
});

describe('Refresh in the top bar (issue #631)', () => {
  const owner = { ...mockMe('admin'), tours: TOURS_OFF } as unknown as Me;

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

  function bar(path: string) {
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [path] }) });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    return render(
      <RouterContextProvider router={router}>
        <QueryClientProvider client={client}>
          <StaffContext.Provider value={{ me: owner, branch: owner.branches?.[0]?.id ?? null, signOut: () => undefined }}>
            <TourProvider me={owner} saveProgress={false}>
              <StaffBar me={owner} branch={owner.branches?.[0]?.id ?? null} onBranch={() => undefined} onSignOut={() => undefined} />
            </TourProvider>
          </StaffContext.Provider>
        </QueryClientProvider>
      </RouterContextProvider>,
    );
  }

  afterEach(() => vi.unstubAllGlobals());

  it.each([
    ['Sell', '/staff/retail/sale', 390],
    ['My stock', '/staff/retail/stock', 390],
    ['Loans', '/staff/lending', 390],
    ['Sell on a laptop', '/staff/retail/sale', 1280],
  ])('is a visible button named Refresh beside Search on %s, and not in the menu', (_screen, path, width) => {
    widthIs(width);
    const { container } = bar(path);
    const buttons = screen.getAllByRole('button', { name: 'Refresh' });
    expect(buttons).toHaveLength(1);
    const refresh = buttons[0] as HTMLButtonElement;
    const wrap = refresh.closest('[data-tour="refresh"]') as HTMLElement;
    expect(wrap.parentElement?.classList.contains('staff-bar-top')).toBe(true);
    expect(wrap.nextElementSibling).toBe(screen.getByRole('button', { name: 'Search' }));
    expect(refresh.closest('.top-menu-panel')).toBeNull();
    expect(refresh.querySelector('.refresh-icon svg')).not.toBeNull();
    expect(container.querySelectorAll('[data-tour="refresh"]')).toHaveLength(1);
  });
});

describe('the offline banner', () => {
  it('says nothing online and one plain line offline', () => {
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    const { container, rerender } = render(<OfflineBanner />);
    expect(container.textContent).toBe('');
    online.mockReturnValue(false);
    window.dispatchEvent(new Event('offline'));
    rerender(<OfflineBanner />);
    expect(screen.getByRole('status').textContent).toBe('You are offline. You see what was loaded before.');
    online.mockRestore();
  });

  // JUSTIFICATION-A3: a new test case for issue #763 (the seller's banner with the time of the figures) beside the banner test it extends.
  it('tells a seller, once, that selling still works and how old the figures are (issue #763)', async () => {
    // The browser says online, but the server cannot be reached: the offline support's ping decides.
    const seller = testOffline(mockMe('sales'), { online: false });
    const client = new QueryClient();
    const loaded = Date.now() - 5 * 60_000;
    client.setQueryData(['retail', 'home', 'all'], { sales: 1 }, { updatedAt: loaded });
    function Page() {
      useQuery({ queryKey: ['retail', 'home', 'all'], queryFn: () => new Promise(() => undefined), staleTime: Infinity });
      return <OfflineBanner me={mockMe('sales')} />;
    }
    render(
      <QueryClientProvider client={client}>
        <OfflineProvider value={seller}>
          <Page />
        </OfflineProvider>
      </QueryClientProvider>,
    );
    const banner = await screen.findByRole('status');
    expect(banner.textContent).toBe(`You are offline. You can still sell; your sales will send when you are back online. Figures are from ${figuresTime(loaded, Date.now())}.`);
    expect(screen.getAllByRole('status')).toHaveLength(1);
  });
});
