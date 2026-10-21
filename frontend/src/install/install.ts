// Installing the app (ADR-050). The browser offers an install through the `beforeinstallprompt` event on Android and on desktop
// Chrome and Edge; iPhones and iPads have no such event, so there the person is told, in plain words, to use Share and Add to
// Home Screen. This module keeps the captured prompt until a person asks for it, knows whether the app already runs installed, and
// remembers on this device that the offer was waved away. The approach follows the sister app's install module.
//
// The listener is attached when this module is first imported, not when a component mounts: `beforeinstallprompt` fires once and
// early, and a prompt that was missed cannot be shown again until the next page load.

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

export interface InstallState {
  /** Running as an installed app: nothing to offer and no control belongs anywhere. */
  installed: boolean;
  /** The browser gave us a prompt that can be shown on a tap. */
  canPrompt: boolean;
  /** The offer was waved away on this device, recently enough that it is not shown again. */
  dismissed: boolean;
  /** The person accepted the install in this page (issue #412: the moment to offer notifications). */
  accepted: boolean;
}

/** After "Not now" the banner stays away for this long on this device; the Notifications and app page always has the control. */
export const DISMISS_DAYS = 30;
export const DISMISSED_KEY = 'bms.install.dismissed';

/** Absent in old browsers and in jsdom, so its absence must not stop the app loading. */
function displayModeQuery(): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null;
  return window.matchMedia('(display-mode: standalone)');
}

function standalone(): boolean {
  if (typeof window === 'undefined') return false;
  return displayModeQuery()?.matches === true || (navigator as Navigator & { standalone?: boolean }).standalone === true;
}

/**
 * An iPhone or iPad, the devices that never fire `beforeinstallprompt`. iPadOS says it is a Mac, so a Mac with a touch screen
 * counts. Chrome and Firefox on iOS can add to the home screen too, with the same Share menu.
 */
export function isIosDevice(): boolean {
  if (typeof navigator === 'undefined') return false;
  if (/iPad|iPhone|iPod/.test(navigator.userAgent)) return true;
  return navigator.platform === 'MacIntel' && (navigator.maxTouchPoints ?? 0) > 1;
}

function readDismissed(): boolean {
  try {
    const at = Number(localStorage.getItem(DISMISSED_KEY));
    return at > 0 && Date.now() - at < DISMISS_DAYS * 24 * 3600 * 1000;
  } catch {
    // Private mode and blocked storage both throw: the offer simply shows again.
    return false;
  }
}

let prompt: BeforeInstallPromptEvent | null = null;
let state: InstallState = { installed: standalone(), canPrompt: false, dismissed: readDismissed(), accepted: false };

const listeners = new Set<() => void>();

function update(next: Partial<InstallState>) {
  const merged = { ...state, ...next };
  if (
    merged.installed === state.installed &&
    merged.canPrompt === state.canPrompt &&
    merged.dismissed === state.dismissed &&
    merged.accepted === state.accepted
  )
    return;
  state = merged;
  for (const listener of listeners) listener();
}

if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (event) => {
    // Without this the browser shows its own bar and the event cannot be replayed on our button.
    event.preventDefault();
    prompt = event as BeforeInstallPromptEvent;
    update({ canPrompt: true });
  });
  window.addEventListener('appinstalled', () => {
    prompt = null;
    update({ installed: true, canPrompt: false });
  });
  displayModeQuery()?.addEventListener?.('change', (event) => update({ installed: event.matches }));
}

export const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

export const getState = () => state;

/** Server snapshot: nothing is installable while there is no browser to install into. */
export const getServerState = (): InstallState => ({ installed: false, canPrompt: false, dismissed: false, accepted: false });

/** "Not now": remembered on this device for {@link DISMISS_DAYS} days. */
export function dismiss() {
  try {
    localStorage.setItem(DISMISSED_KEY, String(Date.now()));
  } catch {
    // Not being able to remember it is not a reason to keep showing it now.
  }
  update({ dismissed: true });
}

/** Replays the captured prompt. It is spent either way, so it is cleared before it is shown. */
export async function requestInstall(): Promise<'accepted' | 'dismissed' | 'unavailable'> {
  if (prompt === null) return 'unavailable';
  const pending = prompt;
  prompt = null;
  update({ canPrompt: false });
  await pending.prompt();
  const { outcome } = await pending.userChoice;
  if (outcome === 'accepted') update({ accepted: true });
  return outcome;
}
