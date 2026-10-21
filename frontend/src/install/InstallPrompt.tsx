import { useState, useSyncExternalStore } from 'react';
import { dismiss, getServerState, getState, isIosDevice, requestInstall, subscribe } from './install';

// "Install the app" (ADR-050). Two pieces share one store (install.ts):
//   InstallPrompt: the offer. It shows after sign-in, on the sign-in page, on the invitation and first password page and on the
//     forgot and reset password pages, only when this device can actually install the app (the browser gave us a prompt, or it is
//     an iPhone or iPad, where the person is told the three taps). "Not now" is remembered on this device.
//   InstallPanel: the permanent control on the Notifications and app page, which stays whatever was dismissed.

function useInstall() {
  return useSyncExternalStore(subscribe, getState, getServerState);
}

/** The three taps on an iPhone or iPad, in plain words. */
export function IosSteps() {
  return (
    <ol className="stack" data-testid="ios-install-steps">
      <li>
        Tap the <strong>Share</strong> button: the square with an arrow, at the bottom of Safari (at the top on an iPad).
      </li>
      <li>
        Scroll down and tap <strong>Add to Home Screen</strong>.
      </li>
      <li>
        Tap <strong>Add</strong>. Then open BMS from its new icon.
      </li>
    </ol>
  );
}

export function InstallPrompt() {
  const { installed, canPrompt, dismissed } = useInstall();
  const [message, setMessage] = useState<string | null>(null);
  const [showSteps, setShowSteps] = useState(false);
  const ios = isIosDevice();
  if (installed || dismissed || (!canPrompt && !ios)) return null;

  async function install() {
    const outcome = await requestInstall();
    if (outcome === 'accepted') setMessage('Installed. Open BMS from your home screen or your app list.');
    else if (outcome === 'unavailable') setMessage('Your browser did not offer the install. Open its menu and choose Install app.');
  }

  return (
    <aside className="card stack" aria-label="Install the app" data-tour="install-prompt">
      <h2>Install the app</h2>
      <p>
        {ios
          ? 'Add BMS to your home screen. It opens like any other app and can tell you when something needs you.'
          : 'Add BMS to your phone or computer. It opens like any other app, starts faster and can tell you when something needs you.'}
      </p>
      {ios && !canPrompt && showSteps && <IosSteps />}
      {message && <p role="status">{message}</p>}
      <p className="cluster">
        {canPrompt && (
          <button type="button" className="btn-primary" onClick={() => void install()}>
            Install
          </button>
        )}
        {ios && !canPrompt && !showSteps && (
          // An iPhone cannot install from a button (Apple allows only Share, then Add to Home Screen), so the button shows the taps.
          <button type="button" className="btn-primary" onClick={() => setShowSteps(true)}>
            Install
          </button>
        )}
        <button type="button" className="btn-ghost" onClick={dismiss}>
          Not now
        </button>
      </p>
    </aside>
  );
}

/** The permanent control: whether the app is installed, and how to install it on this device. */
export function InstallPanel() {
  const { installed, canPrompt } = useInstall();
  const [message, setMessage] = useState<string | null>(null);
  const [showSteps, setShowSteps] = useState(false);
  const ios = isIosDevice();

  async function install() {
    const outcome = await requestInstall();
    if (outcome === 'accepted') setMessage('Installed. Open BMS from your home screen or your app list.');
    else if (outcome === 'unavailable') setMessage('Your browser did not offer the install. Open its menu and choose Install app.');
  }

  if (installed) {
    return (
      <p>
        <span className="badge badge-success">Installed</span> You are using the installed app on this device.
      </p>
    );
  }
  return (
    <div className="stack">
      <p>
        <span className="badge">Not installed</span> Installing puts BMS on your home screen or app list, opens it without the browser bars,
        and lets it tell you when something needs you.
      </p>
      {canPrompt && (
        <p>
          <button type="button" className="btn-primary" onClick={() => void install()}>
            Install the app
          </button>
        </p>
      )}
      {!canPrompt && ios && !showSteps && (
        <p>
          <button type="button" className="btn-primary" onClick={() => setShowSteps(true)}>
            Install the app
          </button>
        </p>
      )}
      {!canPrompt && ios && showSteps && <IosSteps />}
      {!canPrompt && !ios && (
        <p>
          Open your browser's menu and choose <strong>Install app</strong> or <strong>Add to Home screen</strong>. If you do not see it, this
          browser cannot install the app, and BMS keeps working as a normal page.
        </p>
      )}
      {message && <p role="status">{message}</p>}
    </div>
  );
}
