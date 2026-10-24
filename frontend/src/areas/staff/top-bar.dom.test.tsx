// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../api/client';
import { router as appRouter } from '../../app/router';
import { ALL_BRANCHES } from '../../auth/branch';
import { TourProvider } from '../../tour/host';
import { BranchTitle, OfflineBanner, TopMenu } from './top-bar';

// The staff area's one top bar (issue #526): the business and branch as the title, which switches branch, and one menu with
// who is signed in, Help, Refresh, the theme and Sign out. The shop in use shows its own logo in the title (issue #461,
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

  it('opens from one button and holds who is signed in, Help, Refresh, the theme and Sign out', () => {
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
    expect(screen.getByRole('button', { name: 'Refresh this page' })).toBeTruthy();
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

describe('the offline banner', () => {
  it('says nothing online and one plain line offline', () => {
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    const { container, rerender } = render(<OfflineBanner />);
    expect(container.textContent).toBe('');
    online.mockReturnValue(false);
    window.dispatchEvent(new Event('offline'));
    rerender(<OfflineBanner />);
    expect(screen.getByRole('status').textContent).toContain('You are offline.');
    online.mockRestore();
  });
});
