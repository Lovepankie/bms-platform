// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type Me } from '../../api/client';
import { retailAnalytics } from '../../api/retail-analytics';
import { mockMe } from '../../api/retail-mock';
import { router as appRouter } from '../../app/router';
import { setAccessToken } from '../../auth/session';

// The bottom tabs inside the real staff layout (issue #544): the layout, not a test, decides that two-step set-up puts Sign out in
// the top bar and draws no tabs, and a screen that focuses its Scan box on load (the sale, at a till with a Bluetooth scanner)
// still shows the tabs, since no soft keyboard is up. Every name and figure is invented.

vi.setConfig({ testTimeout: 20000 });

// Every retail read answers with an empty list: no items, customers, stock or branch roles.
vi.mock('../../api/retail', async (original) => ({
  ...(await original<typeof import('../../api/retail')>()),
  retail: new Proxy({}, { get: () => () => Promise.resolve([]) }),
}));

const TOURS_OFF = { 'tours-off': { status: 'dismissed', version: 1 } };
const seller = { ...mockMe('sales'), tours: TOURS_OFF } as unknown as Me;

// A phone 360 wide with a mouse or a scanner's keyboard (pointer: fine), so the sale screen puts the cursor in its Scan box.
function phone() {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: 360 <= Number(/max-width: (\d+)px/.exec(query)?.[1] ?? Infinity),
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
  // No keyboard: the visual viewport is as tall as the window.
  vi.stubGlobal('visualViewport', Object.assign(new EventTarget(), { height: 900, scale: 1 }));
  vi.stubGlobal('innerHeight', 900);
}

function start(me: Me, at: string) {
  setAccessToken('test-token-tabs');
  vi.spyOn(api, 'GET').mockImplementation(((path: string) =>
    path === '/api/v1/me' ? Promise.resolve({ data: me, response: new Response() }) : new Promise(() => undefined)) as never);
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [at] }) });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

const tabBar = () => document.querySelector<HTMLElement>('nav.tab-bar');

beforeEach(() => {
  window.scrollTo = vi.fn();
  window.localStorage.clear();
  phone();
  vi.spyOn(retailAnalytics, 'home').mockResolvedValue({ low_stock: 0 } as never);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  setAccessToken(null);
  window.localStorage.clear();
});

describe('the staff layout wires the tabs', () => {
  it('puts Sign out in the top bar and draws no tabs while two-step set-up blocks the page (mustSetUp)', async () => {
    const signOut = vi.spyOn(api, 'POST').mockImplementation((() => new Promise(() => undefined)) as never);
    start({ ...seller, mfa_required: true, mfa_enabled: false } as Me, '/staff');
    fireEvent.click(await screen.findByRole('button', { name: /^Menu for / }, { timeout: 10000 }));
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => expect(signOut).toHaveBeenCalledWith('/api/v1/auth/logout'));
    expect(tabBar()).toBeNull();
  });

  it('keeps Sign out off the top bar on a phone once two-step is set up, where the tabs and More hold it', async () => {
    start({ ...seller, mfa_required: true, mfa_enabled: true } as Me, '/staff');
    fireEvent.click(await screen.findByRole('button', { name: /^Menu for / }, { timeout: 10000 }));
    expect(screen.getByRole('button', { name: 'Help' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Sign out' })).toBeNull();
    expect(tabBar()?.hidden).toBe(false);
  });
});

describe('a screen that focuses its Scan box on load', () => {
  it('still shows the tabs on the sale screen, as a focused field with no soft keyboard is no reason to hide them', async () => {
    start(seller, '/staff/retail/sale');
    const box = await waitFor(
      () => {
        const found = document.querySelector<HTMLInputElement>('input[data-scan-box]');
        expect(found).not.toBeNull();
        return found as HTMLInputElement;
      },
      { timeout: 10000 },
    );
    await waitFor(() => expect(document.activeElement).toBe(box));
    // Let the bar's frame-timed keyboard check run.
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(tabBar()?.hidden).toBe(false);
    expect(within(tabBar() as HTMLElement).getByRole('link', { name: 'Sell' }).getAttribute('aria-current')).toBe('page');
    expect(document.documentElement.classList.contains('has-tab-bar')).toBe(true);
    expect(document.documentElement.classList.contains('tab-bar-aside')).toBe(false);
  });
});
