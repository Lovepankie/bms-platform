import { Link } from '@tanstack/react-router';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { BusyButton } from '../app/ui/BusyButton';
import { getServerState, getState, subscribe } from '../install/install';
import { type PushState, createPush } from './push';
import { dueToAsk, readAsk, recordDone, recordNotNow } from './turn-on';

// "Get told when something needs you?" (issue #412, ADR-057). A one-tap card in the staff area, shown the first time someone opens
// the installed app or right after they accept the install, while this device can have notifications and does not yet. The Turn on
// button is the tap a browser requires before it may ask for permission: nothing is asked when the card appears. Not now asks again
// after a few days, at most a few times (turn-on.ts). Once allowed, the staff area re-registers the device quietly on every visit
// (push.sync), so the card is not needed again. The Notifications and app page keeps its own switch, unchanged.

const defaultPush = createPush();

type Push = Pick<ReturnType<typeof createPush>, 'state' | 'turnOn'>;

export function TurnOnCard({ push = defaultPush, now = () => Date.now() }: { push?: Push; now?: () => number }) {
  const install = useSyncExternalStore(subscribe, getState, getServerState);
  const moment = install.installed || install.accepted;
  const [asking, setAsking] = useState(() => dueToAsk(readAsk(), now()));
  const [state, setState] = useState<PushState | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);

  useEffect(() => {
    if (!moment || !asking) return;
    let live = true;
    // Only reads what the device can do; never asks the browser anything.
    push
      .state()
      .then((s) => {
        if (live) setState(s);
      })
      .catch(() => {
        if (live) setState(null);
      });
    return () => {
      live = false;
    };
  }, [moment, asking, push]);

  if (done) {
    return (
      <aside className="card stack" aria-label="Notifications" data-tour="turn-on-card">
        <p role="status">{done}</p>
        <p className="cluster">
          <button type="button" className="btn-ghost btn-sm" onClick={() => setDone(null)}>
            Close
          </button>
        </p>
      </aside>
    );
  }
  if (!moment || !asking || state !== 'off') return null;

  async function turnOn() {
    setBusy(true);
    try {
      const next = await push.turnOn();
      if (next === 'on') {
        recordDone();
        setDone('Notifications are on. This device will tell you when something needs you.');
      } else if (next === 'blocked') {
        recordDone();
        setDone('The browser said no. You can allow notifications later in the browser settings, then turn them on in Notifications and app.');
      } else {
        // The question was closed without an answer: the same as Not now.
        recordNotNow(now());
      }
      setAsking(false);
    } catch {
      recordNotNow(now());
      setAsking(false);
      setDone('Could not turn notifications on. You can try again in Notifications and app.');
    } finally {
      setBusy(false);
    }
  }

  function notNow() {
    recordNotNow(now());
    setAsking(false);
  }

  return (
    <aside className="card stack" aria-labelledby="turn-on-title" data-tour="turn-on-card">
      <h2 id="turn-on-title">Get told when something needs you?</h2>
      <p>
        A new order for your shop, an order you took on its way or delivered, stock running low, credit payments to collect, and the day's
        sales at closing. A notification never shows a customer's name, a phone number or an amount.
      </p>
      <p className="cluster">
        <BusyButton type="button" className="btn-primary" busy={busy} onClick={() => void turnOn()}>
          Turn on
        </BusyButton>
        <button type="button" /* busy-exempt: not now is local only and waits for turn on */ className="btn-ghost" disabled={busy} onClick={notNow}>
          Not now
        </button>
      </p>
      <p className="hint">
        You can change this any time in <Link to="/staff/notifications">Notifications and app</Link>.
      </p>
    </aside>
  );
}
