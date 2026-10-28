// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type Me } from '../../api/client';
import { router as appRouter } from '../../app/router';
import { getAccessToken, logoutPending, setAccessToken } from '../../auth/session';
import { mockMe } from '../../api/retail-mock';
import { OfflineProvider } from '../../offline/offline-context';
import { provisionalNumber, type OutboxItem } from '../../offline/outbox';
import { testOffline } from '../../offline/test-kit';
import { SignOutEverywhere } from './top-bar';

// Sign out everywhere (issue #647): the person menu asks first, then ends every session of the person on the server, this
// device included, and goes to sign-in. With no answer nothing is signed out here either: other devices can only be signed
// out by the server, so the person is told to connect and stays where they are. Every name is invented.

const seller = {
  user_id: '00000000-0000-4000-8000-0000000000c1',
  full_name: 'Test Seller 05',
  kind: 'staff',
  permissions: ['lending.loans.read'],
  modules: ['lending'],
  branches: [],
  tours: { 'tours-off': { status: 'dismissed', version: 1 } },
} as unknown as Me;

function start(post: (path: string) => Promise<unknown>) {
  setAccessToken('test-token-seller-05');
  vi.spyOn(api, 'GET').mockImplementation(((path: string) =>
    path === '/api/v1/me' ? Promise.resolve({ data: seller, response: new Response() }) : new Promise(() => undefined)) as never);
  const sent = vi.spyOn(api, 'POST').mockImplementation(post as never);
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { router, sent };
}

async function openTheQuestion() {
  fireEvent.click(await screen.findByRole('button', { name: /^Menu for / }, { timeout: 5000 }));
  fireEvent.click(within(document.querySelector('.top-menu') as HTMLElement).getByRole('button', { name: 'Sign out everywhere' }));
  return screen.findByRole('dialog', { name: 'Sign out everywhere?' });
}

beforeEach(() => {
  window.scrollTo = vi.fn();
  window.localStorage.clear();
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  setAccessToken(null);
  window.localStorage.clear();
});

describe('sign out everywhere', () => {
  it('asks first, then ends every session on the server and goes to sign-in', async () => {
    const { router, sent } = start(() => Promise.resolve({ response: new Response(null, { status: 204 }) }));
    const question = await openTheQuestion();
    expect(within(question).getByText(/every phone and computer, this one too/)).toBeTruthy();
    expect(sent).not.toHaveBeenCalledWith('/api/v1/auth/logout-everywhere');

    fireEvent.click(within(question).getByRole('button', { name: 'Sign out everywhere' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-in'));
    expect(sent).toHaveBeenCalledWith('/api/v1/auth/logout-everywhere');
    expect(sent).not.toHaveBeenCalledWith('/api/v1/auth/logout');
    expect(getAccessToken()).toBeNull();
    expect(logoutPending()).toBe(false);
  });

  it('sends nothing when cancelled', async () => {
    const { router, sent } = start(() => Promise.resolve({ response: new Response(null, { status: 204 }) }));
    const question = await openTheQuestion();
    fireEvent.click(within(question).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Sign out everywhere?' })).toBeNull());
    expect(sent).not.toHaveBeenCalledWith('/api/v1/auth/logout-everywhere');
    expect(router.state.location.pathname).toBe('/staff');
    expect(getAccessToken()).toBe('test-token-seller-05');
  });

  it('with no network signs nothing out and says to connect', async () => {
    const { router } = start(() => Promise.reject(new TypeError('offline')));
    const question = await openTheQuestion();
    fireEvent.click(within(question).getByRole('button', { name: 'Sign out everywhere' }));
    expect(await screen.findByText('Connect to the internet to sign out everywhere.')).toBeTruthy();
    expect(router.state.location.pathname).toBe('/staff');
    expect(getAccessToken()).toBe('test-token-seller-05');
    expect(logoutPending()).toBe(false);
  });
});

// ADR-067 decision 9: sales waiting on this phone are never lost by signing out. Sign out everywhere says how many wait and
// runs the same forget() as Sign out, which keeps the outbox for this person.
function waitingSale(id: string, scope: string): OutboxItem {
  return {
    id,
    scope,
    key: `key-${id}`,
    request: { payment_method: 'cash', lines: [] },
    occurredAt: '2026-10-28T08:00:00Z',
    branchId: 'b1',
    branchName: 'Test Branch A',
    method: 'cash',
    totalMinor: 25000,
    lines: [],
    provisionalNo: provisionalNumber(`${id}0000000`),
    state: 'queued',
    attempts: 0,
    nextAttemptAt: 0,
  };
}

describe('sign out everywhere with sales waiting', () => {
  it('says the waiting sales stay on this phone, and they do', async () => {
    const offline = testOffline(mockMe('sales'), { online: false });
    if (!offline) throw new Error('a seller sells offline');
    for (const id of ['a', 'b', 'c']) await offline.engine.queue(waitingSale(id, offline.scope));
    const onConfirm = vi.fn();
    render(
      <QueryClientProvider client={new QueryClient()}>
        <OfflineProvider value={offline}>
          <SignOutEverywhere open waiting={3} busy={false} onConfirm={onConfirm} onClose={() => undefined} />
        </OfflineProvider>
      </QueryClientProvider>,
    );
    const question = await screen.findByRole('dialog', { name: 'Sign out everywhere?' });
    expect(within(question).getByText('3 sales are still on this phone. They stay here and are sent when you sign in here again.')).toBeTruthy();
    fireEvent.click(within(question).getByRole('button', { name: 'Sign out everywhere' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);

    // What the sign-out then does on this phone: the catalogue and the profile go, the three sales stay.
    await offline.store.putCatalogue(offline.scope, { pricesAsOf: 'x', products: {}, customers: [] });
    await offline.store.putProfile(offline.scope, mockMe('sales'));
    await offline.forget();
    expect(await offline.store.catalogue(offline.scope)).toBeNull();
    expect(await offline.store.profile(offline.scope)).toBeNull();
    expect(await offline.store.outbox(offline.scope)).toHaveLength(3);
  });
});
