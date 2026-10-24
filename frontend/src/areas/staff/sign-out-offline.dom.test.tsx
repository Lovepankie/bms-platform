// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type Me } from '../../api/client';
import { router as appRouter } from '../../app/router';
import { endPendingLogout, getAccessToken, logoutPending, markLogoutPending, refreshSession, setAccessToken } from '../../auth/session';

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
  // Sign out is in the top bar's one menu (issue #526).
  fireEvent.click(await screen.findByRole('button', { name: /^Menu for / }, { timeout: 5000 }));
  fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
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
  vi.useRealTimers();
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

// A network that never answers (a captive portal, a very weak signal): the request only ends when its signal aborts. Made
// fresh in each test, so no mock reset between tests can leave it answering undefined.
function neverAnswers() {
  return vi.fn((_url: string, init?: RequestInit) =>
    new Promise<Response>((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(init.signal?.reason))));
}

const notice = 'Could not finish signing out. Connect to the internet and try again.';

describe('ending a pending sign-out on a connection that never answers', () => {
  // Fake timers without shouldAdvanceTime, so only the test moves the clock; vi.waitFor moves it while it polls.
  async function startStalled(network = neverAnswers()) {
    await signOutOffline();
    const me = vi.spyOn(api, 'GET').mockImplementation((() => new Promise(() => undefined)) as never);
    vi.stubGlobal('fetch', network);
    vi.useFakeTimers();
    // The test environment's AbortSignal.timeout runs on Node's own clock, which fake timers do not move: this one aborts
    // on the faked clock, as the browser's does on the page's.
    if (typeof AbortSignal.timeout === 'function') {
      vi.spyOn(AbortSignal, 'timeout').mockImplementation((ms: number) => {
        const controller = new AbortController();
        setTimeout(() => controller.abort(new DOMException('The operation timed out.', 'TimeoutError')), ms);
        return controller.signal;
      });
    }
    const router = start();
    await vi.advanceTimersByTimeAsync(9_000);
    expect(router.state.location.pathname).toBe('/staff');
    await vi.advanceTimersByTimeAsync(1_000);
    await vi.waitFor(() => expect(router.state.location.pathname).toBe('/sign-in'));
    await vi.waitFor(() => expect(screen.getByText(notice)).toBeTruthy());
    return { me, network };
  }

  // The browser has no AbortSignal.timeout: the fallback timer is the page's own, so a test can see it cleared.
  function withoutAbortSignalTimeout() {
    const timeout = Object.getOwnPropertyDescriptor(AbortSignal, 'timeout');
    Object.defineProperty(AbortSignal, 'timeout', { value: undefined, configurable: true });
    return () => {
      if (timeout) Object.defineProperty(AbortSignal, 'timeout', timeout);
    };
  }

  it('gives up after 10 seconds on the sign-in page with a notice, and never restores the session', async () => {
    const { me, network } = await startStalled();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
    expect(AbortSignal.timeout).toHaveBeenCalledWith(10_000);
    expect(network).toHaveBeenCalledWith('/api/v1/auth/refresh', expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(logoutPending()).toBe(true);
    expect(getAccessToken()).toBeNull();
    expect(me).not.toHaveBeenCalledWith('/api/v1/me');
    expect(await refreshSession()).toBe(false);
  });

  it('gives up the same way where the browser has no AbortSignal.timeout', async () => {
    const restore = withoutAbortSignalTimeout();
    try {
      await startStalled();
      expect(logoutPending()).toBe(true);
      expect(getAccessToken()).toBeNull();
    } finally {
      restore();
    }
  });

  it('Try again ends the session once the network is back, still signs nobody in, and leaves no timer running', async () => {
    const restore = withoutAbortSignalTimeout();
    try {
      const { me } = await startStalled();
      const fetch = vi.fn((url: string) =>
        url === '/api/v1/auth/refresh' ? json(200, { access_token: 'test-token-seller-3' }) : Promise.resolve(new Response(null, { status: 204 })));
      vi.stubGlobal('fetch', fetch);
      // Timers the earlier screens left are not this call's.
      vi.clearAllTimers();
      fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
      await vi.waitFor(() => expect(screen.queryByText(notice)).toBeNull());
      expect(fetch).toHaveBeenCalledWith(
        '/api/v1/auth/logout',
        expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ Authorization: 'Bearer test-token-seller-3' }) }),
      );
      expect(logoutPending()).toBe(false);
      expect(getAccessToken()).toBeNull();
      expect(me).not.toHaveBeenCalledWith('/api/v1/me');
      expect(screen.getByRole('button', { name: 'Sign in' })).toBeTruthy();
      // The busy button lets go two ticks after the press's work is over; nothing else may still be running.
      await vi.advanceTimersByTimeAsync(1);
      expect(vi.getTimerCount()).toBe(0);
    } finally {
      restore();
    }
  });

  it('shares one 10 second limit when the refresh answers late and the logout never does', async () => {
    // The refresh answers after 6 seconds; the logout then hangs, and the page still gives up at 10, not 16.
    const stalled = neverAnswers();
    const lateThenStalled = vi.fn((url: string, init?: RequestInit) =>
      url === '/api/v1/auth/refresh'
        ? new Promise<Response>((resolve) => setTimeout(() => void json(200, { access_token: 'test-token-seller-4' }).then(resolve), 6_000))
        : stalled(url, init));
    const { me } = await startStalled(lateThenStalled);
    expect(lateThenStalled).toHaveBeenCalledWith('/api/v1/auth/logout', expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(logoutPending()).toBe(true);
    expect(getAccessToken()).toBeNull();
    expect(me).not.toHaveBeenCalledWith('/api/v1/me');
  });

  it('Try again while still offline keeps the notice and the pending sign-out, and can be pressed again', async () => {
    await startStalled();
    const again = screen.getByRole('button', { name: 'Try again' });
    fireEvent.click(again);
    await vi.waitFor(() => expect(again.getAttribute('aria-busy')).toBe('true'));
    await vi.advanceTimersByTimeAsync(10_000);
    await vi.waitFor(() => expect(again.getAttribute('aria-busy')).not.toBe('true'));
    expect(screen.getByText(notice)).toBeTruthy();
    expect(logoutPending()).toBe(true);
    expect(getAccessToken()).toBeNull();
  });

  it('shuts the sign-in form while Try again runs, so a late answer never brings back the old session', async () => {
    const { me } = await startStalled();
    const login = vi.spyOn(api, 'POST');
    // The refresh stalls until the test answers it, abort or not, as a response already on its way would.
    let answerRefresh: (response: Response) => void = () => undefined;
    const fetch = vi.fn((url: string) =>
      url === '/api/v1/auth/refresh'
        ? new Promise<Response>((resolve) => (answerRefresh = resolve))
        : Promise.resolve(new Response(null, { status: 503 })));
    vi.stubGlobal('fetch', fetch);
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v1/auth/refresh', expect.anything()));

    // The next person tries to sign in meanwhile: the form is shut and a submit from code is refused.
    const loginField = screen.getByLabelText('Email or phone') as HTMLInputElement;
    expect(loginField.disabled).toBe(true);
    expect((screen.getByLabelText('Password') as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Sign in' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.submit(loginField.form as HTMLFormElement);
    expect(login).not.toHaveBeenCalled();

    // The refresh answers; the logout is refused, so the session is not over and the note stays.
    answerRefresh(new Response(JSON.stringify({ access_token: 'test-token-seller-5' }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v1/auth/logout', expect.anything()));
    await vi.waitFor(() => expect(loginField.disabled).toBe(false));
    expect(screen.getByText(notice)).toBeTruthy();
    expect(logoutPending()).toBe(true);
    expect(getAccessToken()).toBeNull();
    expect(me).not.toHaveBeenCalledWith('/api/v1/me');
    expect(await refreshSession()).toBe(false);

    // Only a logout that really succeeds clears the note.
    vi.stubGlobal('fetch', vi.fn((url: string) =>
      url === '/api/v1/auth/refresh' ? json(200, { access_token: 'test-token-seller-6' }) : Promise.resolve(new Response(null, { status: 204 }))));
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await vi.waitFor(() => expect(screen.queryByText(notice)).toBeNull());
    expect(logoutPending()).toBe(false);
    expect(getAccessToken()).toBeNull();
  });
});

describe('a sign-in while a pending sign-out call is still running', () => {
  it('aborts that call, and its late answer never touches the note the new sign-in owns', async () => {
    markLogoutPending();
    let answerRefresh: (response: Response) => void = () => undefined;
    let refreshSignal: AbortSignal | undefined;
    const fetch = vi.fn((url: string, init?: RequestInit) => {
      if (url !== '/api/v1/auth/refresh') return Promise.resolve(new Response(null, { status: 204 }));
      refreshSignal = init?.signal ?? undefined;
      return new Promise<Response>((resolve) => (answerRefresh = resolve));
    });
    vi.stubGlobal('fetch', fetch);
    const ending = endPendingLogout();
    // The next person signs in, then signs out with no network, which notes their own pending sign-out.
    setAccessToken('test-token-next-person');
    expect(refreshSignal?.aborted).toBe(true);
    setAccessToken(null);
    expect(markLogoutPending()).toBe(true);
    // The first call's answer lands anyway and its logout succeeds: the next person's note must stay.
    answerRefresh(new Response(JSON.stringify({ access_token: 'test-token-seller-7' }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    expect(await ending).toBe(false);
    expect(logoutPending()).toBe(true);
    expect(getAccessToken()).toBeNull();
  });
});
