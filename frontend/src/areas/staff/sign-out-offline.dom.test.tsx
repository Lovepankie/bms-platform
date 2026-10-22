// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type Me } from '../../api/client';
import { router as appRouter } from '../../app/router';
import { getAccessToken, logoutPending, refreshSession, setAccessToken } from '../../auth/session';

// ADR-067 decision 9: the refresh cookie is HttpOnly, so only the logout call ends a staff session. A sign-out with no
// network notes a pending logout; the next start ends that session before anything restores it and stays on sign-in until
// it has, so a shared phone never hands the next person the last one's session. Every name is invented.

const seller = {
  user_id: '00000000-0000-4000-8000-0000000000b1',
  full_name: 'Test Seller 01',
  kind: 'staff',
  permissions: ['lending.loans.read'],
  modules: ['lending'],
  branches: [],
  tours: { 'tours-off': { status: 'dismissed', version: 1 } },
} as unknown as Me;

const json = (status: number, body: unknown) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));

function start() {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

// Signed in as the seller, then Sign out pressed with no network.
async function signOutOffline() {
  setAccessToken('test-token-seller');
  vi.spyOn(api, 'GET').mockImplementation(((path: string) =>
    path === '/api/v1/me' ? Promise.resolve({ data: seller, response: new Response() }) : new Promise(() => undefined)) as never);
  vi.spyOn(api, 'POST').mockImplementation((() => Promise.reject(new TypeError('offline'))) as never);
  const router = start();
  fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }, { timeout: 5000 }));
  await waitFor(() => expect(router.state.location.pathname).toBe('/sign-in'));
  expect(getAccessToken()).toBeNull();
  expect(logoutPending()).toBe(true);
  cleanup();
  vi.restoreAllMocks();
}

beforeEach(() => {
  window.scrollTo = vi.fn();
  window.localStorage.clear();
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  setAccessToken(null);
  window.localStorage.clear();
});

describe('signing out with no network', () => {
  it('never restores the session on the next start: it ends it on the server first and stays on sign-in', async () => {
    await signOutOffline();
    // Back online, the seller's cookie is still alive on the server.
    const me = vi.spyOn(api, 'GET').mockImplementation((() => new Promise(() => undefined)) as never);
    const fetch = vi.fn((url: string) =>
      url === '/api/v1/auth/refresh' ? json(200, { access_token: 'test-token-seller-2' }) : Promise.resolve(new Response(null, { status: 204 })));
    vi.stubGlobal('fetch', fetch);
    const router = start();
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-in'));
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/auth/logout',
      expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ Authorization: 'Bearer test-token-seller-2' }) }),
    );
    expect(getAccessToken()).toBeNull();
    expect(me).not.toHaveBeenCalledWith('/api/v1/me');
    expect(logoutPending()).toBe(false);
  });

  it('stays on sign-in while the logout still cannot reach the server, and no refresh restores the session', async () => {
    await signOutOffline();
    const fetch = vi.fn(() => Promise.reject(new TypeError('offline')));
    vi.stubGlobal('fetch', fetch);
    const router = start();
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-in'));
    expect(logoutPending()).toBe(true);
    fetch.mockClear();
    expect(await refreshSession()).toBe(false);
    expect(fetch).not.toHaveBeenCalled();
    expect(getAccessToken()).toBeNull();
  });

  it('a new sign-in on this device clears the pending logout', async () => {
    await signOutOffline();
    setAccessToken('test-token-next-person');
    expect(logoutPending()).toBe(false);
  });
});
