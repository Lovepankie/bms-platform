// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { THEME_KEY, resetThemeMemory } from '../../../app/theme-mode';
import type { createPush, PushState } from '../../../push/push';

/** A button that may be pressed: not disabled, and not a primary button whose form is still not ready (issue #526). */
const ready = (b: unknown) => !(b as HTMLButtonElement).disabled && (b as HTMLElement).getAttribute('data-not-ready') !== 'true';

// The "Notifications on this device" switch and the list of the person's devices (ADR-050). The permission question belongs to the
// tap: opening the page asks nothing. A state the person cannot change (blocked in the browser, not possible on this browser, not
// switched on at the server) is explained in words and the switch is not offered. Fabricated devices; the browser and the server
// are fakes.

const list = vi.fn();
const remove = vi.fn();
vi.mock('../../../api/push', () => ({ pushApi: { list: (...a: unknown[]) => list(...a), remove: (...a: unknown[]) => remove(...a), config: vi.fn() } }));

type Push = ReturnType<typeof createPush>;

function fakePush(state: PushState) {
  const push = {
    state: vi.fn(() => Promise.resolve(state)),
    enable: vi.fn(() => Promise.resolve<PushState>('on')),
    disable: vi.fn(() => Promise.resolve<PushState>('off')),
    sync: vi.fn(() => Promise.resolve()),
    forget: vi.fn(() => Promise.resolve()),
  };
  return push as unknown as Push & typeof push;
}

async function show(push: Push) {
  const { NotificationsPage } = await import('./page');
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NotificationsPage push={push} />
    </QueryClientProvider>,
  );
}

const toggle = () => screen.getByRole('switch', { name: /Notifications on this device/ }) as HTMLInputElement;

beforeEach(() => {
  list.mockReset().mockResolvedValue([]);
  remove.mockReset().mockResolvedValue(undefined);
});

afterEach(cleanup);

describe('the switch', () => {
  it('asks nothing when the page opens, and shows the device as off', async () => {
    const push = fakePush('off');
    await show(push);
    await waitFor(() => expect(ready(toggle())).toBe(true));
    expect(toggle().checked).toBe(false);
    expect(push.enable).not.toHaveBeenCalled();
    expect(screen.getByRole('status').textContent).toContain('Off');
  });

  it('turns on with one tap, which is the only place permission is asked', async () => {
    const push = fakePush('off');
    await show(push);
    await waitFor(() => expect(ready(toggle())).toBe(true));
    fireEvent.click(toggle());
    await waitFor(() => expect(toggle().checked).toBe(true));
    expect(push.enable).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('status').textContent).toContain('On. This device will tell you');
  });

  it('turns off with one tap', async () => {
    const push = fakePush('on');
    await show(push);
    await waitFor(() => expect(toggle().checked).toBe(true));
    fireEvent.click(toggle());
    await waitFor(() => expect(toggle().checked).toBe(false));
    expect(push.disable).toHaveBeenCalledTimes(1);
  });

  it('explains a block in the browser and offers no switch', async () => {
    await show(fakePush('blocked'));
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Blocked'));
    expect(toggle().disabled).toBe(true);
  });

  it('explains that a server without push cannot send, and says what an iPhone must do first', async () => {
    await show(fakePush('not_offered'));
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('not switched on for this server'));
    expect(toggle().disabled).toBe(true);
    cleanup();

    Object.defineProperty(navigator, 'userAgent', { value: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Safari/604.1', configurable: true });
    await show(fakePush('unsupported'));
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('add BMS to your home screen'));
    expect(toggle().disabled).toBe(true);
  });

  it('shows a failure in plain words and leaves the switch where it was', async () => {
    const push = fakePush('off');
    push.enable.mockRejectedValue(new Error('Could not turn notifications on for this device.'));
    await show(push);
    await waitFor(() => expect(ready(toggle())).toBe(true));
    fireEvent.click(toggle());
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Could not turn notifications on'));
    expect(toggle().checked).toBe(false);
  });
});

describe('the devices', () => {
  it('lists the caller devices by label with no address or key, and removes one on a tap', async () => {
    list.mockResolvedValue([
      { id: 'd1', label: 'Chrome on Android', created_at: '2026-10-01T08:00:00Z', last_used_at: '2026-10-19T09:30:00Z' },
      { id: 'd2', label: 'Safari on iPhone or iPad', created_at: '2026-10-05T08:00:00Z' },
    ]);
    await show(fakePush('on'));
    expect(await screen.findByText('Chrome on Android')).toBeTruthy();
    expect(screen.getByText('Safari on iPhone or iPad')).toBeTruthy();
    expect(document.body.textContent).not.toMatch(/https:|endpoint|p256dh/);
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0] as HTMLElement);
    await waitFor(() => expect(remove).toHaveBeenCalledWith('d1'));
  });

  it('says so when no device is registered', async () => {
    await show(fakePush('off'));
    expect(await screen.findByText(/No device receives notifications for you yet/)).toBeTruthy();
  });

  it('carries the anchors of the tour on the install control, the appearance choice, the switch and the list', async () => {
    const view = await show(fakePush('off'));
    for (const id of ['install-app', 'appearance', 'notifications-toggle', 'notifications-devices']) {
      expect(view.container.querySelector(`[data-tour="${id}"]`)).not.toBeNull();
    }
  });
});

describe('the appearance (issue #452)', () => {
  beforeEach(() => {
    window.localStorage.clear();
    resetThemeMemory();
  });

  it('offers Light, Dark and Same as my phone in plain words, the current choice checked', async () => {
    window.localStorage.setItem(THEME_KEY, 'dark');
    await show(fakePush('off'));
    const group = screen.getByRole('radiogroup', { name: 'Appearance' });
    expect(group.getAttribute('aria-describedby')).toBe('appearance-words');
    expect(screen.getByText('How BMS looks on this device.')).toBeTruthy();
    const radios = within(group).getAllByRole('radio') as HTMLInputElement[];
    expect(radios.map((r) => [r.parentElement?.textContent?.trim(), r.checked])).toEqual([['Same as my phone', false], ['Light', false], ['Dark', true]]);
  });

  it('changes the theme on this device, the same choice the header button holds', async () => {
    await show(fakePush('off'));
    fireEvent.click(screen.getByRole('radio', { name: 'Dark' }));
    expect(window.localStorage.getItem(THEME_KEY)).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect((screen.getByRole('radio', { name: 'Dark' }) as HTMLInputElement).checked).toBe(true);
    fireEvent.click(screen.getByRole('radio', { name: 'Same as my phone' }));
    expect(window.localStorage.getItem(THEME_KEY)).toBeNull();
    expect((screen.getByRole('radio', { name: 'Same as my phone' }) as HTMLInputElement).checked).toBe(true);
  });
});
