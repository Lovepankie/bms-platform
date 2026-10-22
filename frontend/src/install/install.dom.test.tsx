// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The install offer (ADR-050): shown only when this device can install the app, waved away for 30 days on this device, never shown
// to someone already running the installed app, and, on an iPhone or iPad, three plain steps instead of a button. The module keeps
// the browser's prompt in module state and attaches its listener when first imported, so each test imports it afresh for the device
// it wants to describe.

const REAL_AGENT = navigator.userAgent;
const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1';
const DAY = 24 * 3600 * 1000;

async function load({ standalone = false, ios = false } = {}) {
  vi.resetModules();
  const media = { matches: standalone, addEventListener: vi.fn(), removeEventListener: vi.fn() };
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => media),
  );
  Object.defineProperty(navigator, 'userAgent', { value: ios ? IPHONE : REAL_AGENT, configurable: true });
  const install = await import('./install');
  const ui = await import('./InstallPrompt');
  return { install, ui };
}

/** What Chrome fires: an event the page keeps and replays on a tap. */
function firePrompt() {
  const prompt = vi.fn(() => Promise.resolve());
  const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
    prompt,
    userChoice: Promise.resolve({ outcome: 'accepted' as const }),
  });
  act(() => {
    window.dispatchEvent(event);
  });
  return { prompt, event };
}

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  Object.defineProperty(navigator, 'userAgent', { value: REAL_AGENT, configurable: true });
});

describe('the install offer', () => {
  it('shows nothing until the browser says an install is possible, then offers it', async () => {
    const { ui } = await load();
    render(<ui.InstallPrompt />);
    expect(screen.queryByText('Install the app')).toBeNull();
    firePrompt();
    expect(await screen.findByRole('heading', { name: 'Install the app' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Install' })).toBeTruthy();
  });

  it("keeps the browser's own bar away, so the prompt can be replayed on our button", async () => {
    await load();
    const { event } = firePrompt();
    expect(event.defaultPrevented).toBe(true);
  });

  it('replays the browser prompt on the tap and is gone once it is used', async () => {
    const { ui } = await load();
    render(<ui.InstallPrompt />);
    const { prompt } = firePrompt();
    fireEvent.click(await screen.findByRole('button', { name: 'Install' }));
    await vi.waitFor(() => expect(prompt).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(screen.queryByRole('button', { name: 'Install' })).toBeNull());
  });

  it('is waved away with Not now, remembered on this device, and offered again after 30 days', async () => {
    const first = await load();
    const view = render(<first.ui.InstallPrompt />);
    firePrompt();
    fireEvent.click(await screen.findByRole('button', { name: 'Not now' }));
    expect(screen.queryByRole('heading', { name: 'Install the app' })).toBeNull();
    expect(Number(localStorage.getItem(first.install.DISMISSED_KEY))).toBeGreaterThan(0);
    view.unmount();

    // A new visit on the same device: still waved away.
    const second = await load();
    render(<second.ui.InstallPrompt />);
    firePrompt();
    expect(screen.queryByRole('heading', { name: 'Install the app' })).toBeNull();
    cleanup();

    // More than 30 days later it is offered again.
    localStorage.setItem(second.install.DISMISSED_KEY, String(Date.now() - 31 * DAY));
    const third = await load();
    render(<third.ui.InstallPrompt />);
    firePrompt();
    expect(await screen.findByRole('heading', { name: 'Install the app' })).toBeTruthy();
  });

  it('is never shown when the app already runs installed, or once it has just been installed', async () => {
    const running = await load({ standalone: true });
    render(<running.ui.InstallPrompt />);
    firePrompt();
    expect(screen.queryByRole('heading', { name: 'Install the app' })).toBeNull();
    cleanup();

    const fresh = await load();
    render(<fresh.ui.InstallPrompt />);
    firePrompt();
    expect(await screen.findByRole('heading', { name: 'Install the app' })).toBeTruthy();
    act(() => {
      window.dispatchEvent(new Event('appinstalled'));
    });
    expect(screen.queryByRole('heading', { name: 'Install the app' })).toBeNull();
  });

  it('gives an iPhone an Install button that shows the Safari steps, and can be waved away', async () => {
    const { ui } = await load({ ios: true });
    render(<ui.InstallPrompt />);
    expect(screen.queryByTestId('ios-install-steps')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Install' }));
    expect(screen.getByTestId('ios-install-steps').textContent).toContain('Add to Home Screen');
    expect(screen.getByTestId('ios-install-steps').textContent).toContain('Share');
    // A pilot owner could not find Share: the steps say Safari, not a Private tab, and where Share hides.
    expect(screen.getByTestId('ios-install-steps').textContent).toContain('Private');
    expect(screen.getByTestId('ios-install-steps').textContent).toContain('⋯');
    // A pilot owner found Share but not Add to Home Screen: newer iPhones keep it behind View More.
    expect(screen.getByTestId('ios-install-steps').textContent).toContain('View More');
    expect(screen.queryByRole('button', { name: 'Install' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Not now' }));
    expect(screen.queryByTestId('ios-install-steps')).toBeNull();
  });

  it('offers nothing on a browser that cannot install and is not an iPhone', async () => {
    const { ui } = await load();
    const { container } = render(<ui.InstallPrompt />);
    expect(container.innerHTML).toBe('');
  });
});

describe('the permanent install control on the Notifications and app page', () => {
  it('stays after the offer was waved away, and installs on a tap', async () => {
    const { ui } = await load();
    render(
      <>
        <ui.InstallPrompt />
        <ui.InstallPanel />
      </>,
    );
    const { prompt } = firePrompt();
    fireEvent.click(await screen.findByRole('button', { name: 'Not now' }));
    const button = screen.getByRole('button', { name: 'Install the app' });
    fireEvent.click(button);
    await vi.waitFor(() => expect(prompt).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(screen.getByRole('status').textContent).toContain('Installed'));
  });

  it('says so when the app is installed, explains the menu when the browser gave no prompt, and the steps on an iPhone', async () => {
    const running = await load({ standalone: true });
    render(<running.ui.InstallPanel />);
    expect(screen.getByText('Installed')).toBeTruthy();
    cleanup();

    const plain = await load();
    render(<plain.ui.InstallPanel />);
    expect(screen.getByText('Not installed')).toBeTruthy();
    expect(document.body.textContent).toContain('Add to Home screen');
    cleanup();

    const phone = await load({ ios: true });
    render(<phone.ui.InstallPanel />);
    fireEvent.click(screen.getByRole('button', { name: 'Install the app' }));
    expect(screen.getByTestId('ios-install-steps')).toBeTruthy();
  });
});
