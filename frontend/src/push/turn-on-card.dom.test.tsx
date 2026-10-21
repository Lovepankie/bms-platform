// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PushApi } from '../api/push';
import type { PushEnv, PushState } from './push';

// The turn-on card (issue #412): shown the first time someone opens the installed app or right after accepting the install, while
// the device can have notifications and has none; Turn on is the tap that lets the browser ask; Not now asks again after three days,
// at most three times; turning on or a refusal ends the asking; and it never asks the browser anything without a tap. The
// Notifications and app page is unchanged and shows no card. The browser and the server are fakes.

vi.mock('@tanstack/react-router', () => ({
  Link: ({ to, children }: { to: string; children: ReactNode }) => <a href={to}>{children}</a>,
  createLazyRoute: () => () => ({}),
}));

const DAY = 24 * 3600 * 1000;

async function load({ standalone = true } = {}) {
  vi.resetModules();
  const media = { matches: standalone, addEventListener: vi.fn(), removeEventListener: vi.fn() };
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => media),
  );
  const install = await import('../install/install');
  const card = await import('./TurnOnCard');
  const push = await import('./push');
  return { install, card, push };
}

function fakePush(state: PushState, after: PushState = 'on') {
  return {
    state: vi.fn(() => Promise.resolve(state)),
    turnOn: vi.fn(() => Promise.resolve(after)),
  };
}

const title = () => screen.queryByRole('heading', { name: 'Get told when something needs you?' });

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('the turn-on card', () => {
  it('shows in the installed app when this device can have notifications and has none, and asks nothing until the tap', async () => {
    const { card } = await load();
    const push = fakePush('off');
    render(<card.TurnOnCard push={push} />);
    expect(await screen.findByRole('heading', { name: 'Get told when something needs you?' })).toBeTruthy();
    expect(push.turnOn).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Turn on' }));
    await screen.findByText(/Notifications are on/);
    expect(push.turnOn).toHaveBeenCalledTimes(1);
  });

  it('does not show in a browser tab until the install is accepted, then shows', async () => {
    const { card, install } = await load({ standalone: false });
    const push = fakePush('off');
    render(<card.TurnOnCard push={push} />);
    await act(async () => {
      await Promise.resolve();
    });
    expect(title()).toBeNull();
    expect(push.state).not.toHaveBeenCalled();
    const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
      prompt: vi.fn(() => Promise.resolve()),
      userChoice: Promise.resolve({ outcome: 'accepted' as const }),
    });
    act(() => {
      window.dispatchEvent(event);
    });
    await act(async () => {
      await install.requestInstall();
    });
    expect(await screen.findByRole('heading', { name: 'Get told when something needs you?' })).toBeTruthy();
  });

  it('shows nothing when notifications are already on, blocked, impossible here or not offered by the server', async () => {
    for (const state of ['on', 'blocked', 'unsupported', 'not_offered'] as PushState[]) {
      const { card } = await load();
      const push = fakePush(state);
      render(<card.TurnOnCard push={push} />);
      await waitFor(() => expect(push.state).toHaveBeenCalled());
      await act(async () => {
        await Promise.resolve();
      });
      expect(title()).toBeNull();
      cleanup();
    }
  });

  it('Not now hides it, asks again after three days, and stops after three times', async () => {
    const { card } = await load();
    let clock = Date.UTC(2026, 9, 21, 9);
    const now = () => clock;
    const show = async () => {
      cleanup();
      render(<card.TurnOnCard push={fakePush('off')} now={now} />);
      await act(async () => {
        await Promise.resolve();
      });
    };
    for (let ask = 1; ask <= 3; ask++) {
      await show();
      expect(await screen.findByRole('heading', { name: 'Get told when something needs you?' })).toBeTruthy();
      fireEvent.click(screen.getByRole('button', { name: 'Not now' }));
      expect(title()).toBeNull();
      // The next day it stays away.
      clock += DAY;
      await show();
      expect(title()).toBeNull();
      clock += 2 * DAY;
    }
    // Three times was the most: it never asks again on this device.
    clock += 30 * DAY;
    await show();
    expect(title()).toBeNull();
  });

  it('never asks again once turned on, or once the browser said no', async () => {
    for (const answer of ['on', 'blocked'] as PushState[]) {
      localStorage.clear();
      const { card } = await load();
      render(<card.TurnOnCard push={fakePush('off', answer)} />);
      fireEvent.click(await screen.findByRole('button', { name: 'Turn on' }));
      await screen.findByRole('status');
      cleanup();
      const again = fakePush('off');
      render(<card.TurnOnCard push={again} now={() => Date.now() + 60 * DAY} />);
      await act(async () => {
        await Promise.resolve();
      });
      expect(title()).toBeNull();
      cleanup();
    }
  });

  it('with the real push module, the browser is asked for permission only inside the tap', async () => {
    const { card, push } = await load();
    const subscription = {
      endpoint: 'https://fcm.googleapis.com/fcm/send/test-1',
      options: { applicationServerKey: null },
      toJSON: () => ({ endpoint: 'https://fcm.googleapis.com/fcm/send/test-1', keys: { p256dh: 'p', auth: 'a' } }),
      unsubscribe: vi.fn(() => Promise.resolve(true)),
    };
    let permission: NotificationPermission = 'default';
    const requestPermission = vi.fn(() => {
      permission = 'granted';
      return Promise.resolve<NotificationPermission>('granted');
    });
    let current: typeof subscription | null = null;
    const env: PushEnv = {
      supported: () => true,
      permission: () => permission,
      requestPermission,
      registration: () =>
        Promise.resolve({
          pushManager: {
            getSubscription: () => Promise.resolve(current),
            subscribe: () => {
              current = subscription;
              return Promise.resolve(subscription);
            },
          },
        }),
    };
    const api: PushApi = {
      config: vi.fn(() => Promise.resolve({ enabled: true, publicKey: 'BAAA' })),
      list: vi.fn(() => Promise.resolve([])),
      subscribe: vi.fn(() => Promise.resolve({})),
      unsubscribe: vi.fn(() => Promise.resolve()),
      remove: vi.fn(() => Promise.resolve()),
    };
    render(<card.TurnOnCard push={push.createPush(env, api)} />);
    expect(await screen.findByRole('heading', { name: 'Get told when something needs you?' })).toBeTruthy();
    expect(requestPermission).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Turn on' }));
    // Asked at once, in the tap, before the server is consulted again.
    expect(requestPermission).toHaveBeenCalledTimes(1);
    await screen.findByText(/Notifications are on/);
    expect(api.subscribe).toHaveBeenCalledTimes(1);
  });
});

describe('the Notifications and app page', () => {
  it('stays as it was: its three sections and no turn-on card', async () => {
    vi.resetModules();
    const { NotificationsPage } = await import('../areas/staff/notifications/page');
    const push = {
      state: vi.fn(() => Promise.resolve<PushState>('off')),
      enable: vi.fn(),
      disable: vi.fn(),
      sync: vi.fn(),
      forget: vi.fn(),
      turnOn: vi.fn(),
    };
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <NotificationsPage push={push as never} />
      </QueryClientProvider>,
    );
    const headings = screen.getAllByRole('heading').map((h) => h.textContent);
    expect(headings).toEqual(['Notifications and app', 'Install the app', 'Notifications on this device', 'Your devices']);
    expect(title()).toBeNull();
    expect(push.turnOn).not.toHaveBeenCalled();
  });
});
