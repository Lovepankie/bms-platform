// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../api/client';
import { mockMe } from '../api/retail-mock';
import { OfflineProvider } from './offline-context';
import { provisionalNumber, type OutboxItem } from './outbox';
import { SignOutWarning, waitingSentence } from './sign-out-warning';
import { OthersWaitingNote, SyncStatus, dotState, statusWords } from './sync-status';
import { testOffline } from './test-kit';

// The status in the staff bar and sign-out with sales waiting (issue #458, ADR-067 decisions 9 and 10; NFR-OFF-05,
// NFR-OFF-08): the dot's three states and the count, the sheet of queued and failed sales with Sync now and Send again,
// nothing for a person who does not sell, and the sign-out that asks first. Fabricated people and amounts.

vi.setConfig({ testTimeout: 20000 });

const seller = mockMe('sales');

function item(id: string, minutes: number, patch: Partial<OutboxItem> = {}): OutboxItem {
  return {
    id,
    scope: '',
    key: `key-${id}`,
    request: { payment_method: 'cash', lines: [] },
    occurredAt: new Date(Date.parse('2026-10-22T08:00:00Z') + minutes * 60_000).toISOString(),
    branchId: 'b1',
    branchName: 'Test Branch A',
    method: 'cash',
    totalMinor: 25000,
    lines: [],
    provisionalNo: provisionalNumber(`${id}0000000`),
    state: 'queued',
    attempts: 0,
    nextAttemptAt: 0,
    ...patch,
  };
}

function show(offline: ReturnType<typeof testOffline>, children = <SyncStatus />) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <OfflineProvider value={offline}>{children}</OfflineProvider>
    </QueryClientProvider>,
  );
}

async function withItems(offline: NonNullable<ReturnType<typeof testOffline>>, items: OutboxItem[]) {
  for (const i of items) await offline.engine.queue({ ...i, scope: offline.scope });
}

afterEach(cleanup);

describe('the status dot', () => {
  it('reads green online, grey offline and amber while syncing, with plain words', () => {
    expect(dotState(true, false)).toBe('online');
    expect(dotState(false, false)).toBe('offline');
    expect(dotState(true, true)).toBe('syncing');
    expect(dotState(false, true)).toBe('offline');
    expect(statusWords('offline', 3, 1)).toBe('Offline, 3 waiting to sync, 1 failed');
    expect(statusWords('online', 0, 0)).toBe('Online');
  });

  it('is green with no count when nothing waits', async () => {
    const offline = testOffline(seller, { online: true });
    show(offline);
    const dot = await screen.findByTestId('sync-dot');
    expect(dot.getAttribute('data-state')).toBe('online');
    expect(screen.getByRole('button', { name: 'Online' })).toBeTruthy();
    // The state is in words beside the dot, not in its colour alone.
    expect(screen.getByTestId('sync-word').textContent).toBe('Online');
    expect(screen.queryByText(/^\d+ waiting to sync$/)).toBeNull();
  });

  it('is grey offline with the count waiting, and follows the connection', async () => {
    const offline = testOffline(seller, { online: false });
    if (!offline) throw new Error('a seller sells offline');
    await withItems(offline, [item('a', 1), item('b', 2), item('c', 3, { state: 'failed', error: { message: 'This branch does not sell.' } })]);
    show(offline);
    expect(screen.getByTestId('sync-dot').getAttribute('data-state')).toBe('offline');
    expect(screen.getByTestId('sync-word').textContent).toBe('Offline');
    expect(screen.getByText('3 waiting to sync')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Offline, 3 waiting to sync, 1 failed' })).toBeTruthy();
    act(() => offline.connectivity.set(true));
    expect(screen.getByTestId('sync-dot').getAttribute('data-state')).toBe('online');
  });

  it('is amber while a sale is on its way, then green with the count gone', async () => {
    let answer: (v: { status: number; body: unknown }) => void = () => {};
    const offline = testOffline(seller, {
      online: true,
      deps: { send: () => new Promise((resolve) => (answer = resolve)) },
    });
    if (!offline) throw new Error('a seller sells offline');
    await withItems(offline, [item('a', 1)]);
    show(offline);
    let run: Promise<void> = Promise.resolve();
    act(() => {
      run = offline.engine.sync();
    });
    await waitFor(() => expect(screen.getByTestId('sync-dot').getAttribute('data-state')).toBe('syncing'));
    await act(async () => {
      answer({ status: 201, body: { id: 's1', sale_no: 'RS00000001' } });
      await run;
    });
    expect(screen.getByTestId('sync-dot').getAttribute('data-state')).toBe('online');
    expect(screen.queryByText(/^\d+ waiting to sync$/)).toBeNull();
  });

  it('draws nothing for a person who does not sell', () => {
    const viewer = { ...seller, permissions: ['retail.stock.read'] } as Me;
    const { container } = show(testOffline(viewer));
    expect(container.innerHTML).toBe('');
  });
});

describe('the sheet', () => {
  it('lists queued and failed sales with the reason, queues a failed one again behind the older, and syncs now in order', async () => {
    const send = vi.fn(async () => ({ status: 201, body: { id: 's1', sale_no: 'RS00000009' } }));
    const offline = testOffline(seller, { online: true, deps: { send } });
    if (!offline) throw new Error('a seller sells offline');
    await withItems(offline, [
      item('a', 1, { attempts: 1, nextAttemptAt: Date.now() + 60_000, error: { message: 'No connection to the server.' } }),
      item('b', 2, { state: 'failed', error: { code: 'period_closed', message: 'The period 2026-9 is closed.' } }),
    ]);
    show(offline);
    fireEvent.click(screen.getByRole('button', { name: /waiting to sync/ }));
    const list = await screen.findByRole('list', { name: 'Waiting to sync' });
    expect(list.textContent).toContain('Waiting to try again: No connection to the server.');
    expect(list.textContent).toContain('Not recorded: The period 2026-9 is closed.');
    expect(list.textContent).toContain('A manager can have it sent again or discard it.');

    // The older sale still backs off, so the one sent again waits its turn behind it.
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Send again' }));
    });
    expect(send).not.toHaveBeenCalled();
    expect(list.textContent).toContain('Waiting its turn');
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Sync now' }));
    });
    await waitFor(() => expect(send).toHaveBeenCalledTimes(2));
    expect(send.mock.calls.map((c) => (c as unknown as [OutboxItem])[0].id)).toEqual(['a', 'b']);
    expect(await screen.findByText('Nothing is waiting to sync.')).toBeTruthy();
  });

  it('says to sign in again when the session expired', async () => {
    const offline = testOffline(seller, { online: true, deps: { send: async () => ({ status: 401, body: {} }) } });
    if (!offline) throw new Error('a seller sells offline');
    await withItems(offline, [item('a', 1)]);
    show(offline);
    await act(() => offline.engine.sync());
    fireEvent.click(screen.getByRole('button', { name: /waiting to sync/ }));
    expect(await screen.findByText('Your sign-in has expired. Sign in again to send the waiting sales.')).toBeTruthy();
  });
});

describe('a shared phone', () => {
  it('says how many sales another person left waiting on this phone', async () => {
    show(testOffline(seller, { online: true, othersWaiting: 2 }), <OthersWaitingNote />);
    expect(screen.getByTestId('others-waiting').textContent).toContain('2 sales from another person are waiting on this phone.');
    cleanup();
    show(testOffline(seller, { online: true }), <OthersWaitingNote />);
    expect(screen.queryByTestId('others-waiting')).toBeNull();
  });
});

describe('signing out with sales waiting', () => {
  it('says how many and keeps them on the phone when the person goes on', async () => {
    const offline = testOffline(seller, { online: false });
    if (!offline) throw new Error('a seller sells offline');
    await withItems(offline, [item('a', 1), item('b', 2), item('c', 3)]);
    const onSignOut = vi.fn();
    const view = show(offline, <SignOutWarning ask={0} onSignOut={onSignOut} />);
    expect(screen.getByTestId('sign-out-warning').hasAttribute('open')).toBe(false);
    view.rerender(
      <QueryClientProvider client={new QueryClient()}>
        <OfflineProvider value={offline}>
          <SignOutWarning ask={1} onSignOut={onSignOut} />
        </OfflineProvider>
      </QueryClientProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('sign-out-warning').hasAttribute('open')).toBe(true));
    expect(screen.getByText(/3 sales are waiting to sync\./)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Sync now', hidden: true }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Keep them and sign out', hidden: true }));
    expect(onSignOut).toHaveBeenCalledTimes(1);

    // The catalogue copy goes at sign-out; the outbox stays for the same person.
    await offline.store.putCatalogue(offline.scope, { pricesAsOf: 'x', products: {}, customers: [] });
    await offline.forget();
    expect(await offline.store.catalogue(offline.scope)).toBeNull();
    expect(await offline.store.outbox(offline.scope)).toHaveLength(3);
    expect(waitingSentence(1)).toBe('1 sale is waiting to sync.');
  });
});
