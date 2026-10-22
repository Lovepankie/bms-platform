import { useState, useSyncExternalStore } from 'react';
import { dismiss, getServerState, getState, isIosDevice, requestInstall, subscribe } from './install';
import { addButton, addToHomeScreen, safariTab, shareButton } from './ios-pictures';

// "Install the app" (ADR-050). Two pieces share one store (install.ts):
//   InstallPrompt: the offer. It shows after sign-in, on the sign-in page, on the invitation and first password page and on the
//     forgot and reset password pages, only when this device can actually install the app (the browser gave us a prompt, or it is
//     an iPhone or iPad, where the person is told the taps in Safari). "Not now" is remembered on this device.
//   InstallPanel: the permanent control on the Notifications and app page, which stays whatever was dismissed.

function useInstall() {
  return useSyncExternalStore(subscribe, getState, getServerState);
}

/**
 * The taps on an iPhone or iPad, in plain words. The steps name Safari because it is where these taps are the same for everyone
 * (other iOS browsers can add to the Home Screen too, from menus that differ). The Share button is often out of sight: Safari
 * folds its toolbar away when the page scrolls, and in some layouts Share sits behind the ⋯ button. A first pilot owner stopped
 * at "tap Share" because she could not see it, so the steps say what to do when it is not there, rather than which phone or
 * which iOS layout she has, which she cannot be expected to know. On an iPad Share is at the top right, not in a bottom
 * toolbar. Newer iPhones keep Add to Home Screen behind View More. Each step has a small drawing beside its words (issue #448,
 * ios-pictures.tsx), since a picture of what to look for is followed more easily than words alone.
 */
export function IosSteps() {
  return (
    <ol className="stack" data-testid="ios-install-steps">
      <li>
        <div className="ios-step">
          <span className="ios-step-text">
            Use <strong>Safari</strong>, in a normal tab, not a Private one (a Private tab forgets your sign-in).
          </span>
          {safariTab}
        </div>
      </li>
      <li>
        <div className="ios-step">
          <span className="ios-step-text">
            Tap <strong>Share</strong>: the square with an arrow. If the buttons are hidden, tap the address bar once so they
            show. If you still do not see Share, tap <strong>⋯</strong> and Share is there. On an iPad, Share is at the top
            right.
          </span>
          {shareButton}
        </div>
      </li>
      <li>
        <div className="ios-step">
          <span className="ios-step-text">
            Tap <strong>Add to Home Screen</strong>. If it is not in the list, scroll down, or tap <strong>View More</strong> and it is
            there.
          </span>
          {addToHomeScreen}
        </div>
      </li>
      <li>
        <div className="ios-step">
          <span className="ios-step-text">
            Tap <strong>Add</strong>. Then open BMS from its new icon.
          </span>
          {addButton}
        </div>
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
