import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { type PushDevice, pushApi } from '../../../api/push';
import { THEME_LABELS } from '../../../app/shell';
import { THEME_CHOICES, useThemeChoice } from '../../../app/theme-mode';
import { InstallPanel } from '../../../install/InstallPrompt';
import { isIosDevice } from '../../../install/install';
import { type PushState, createPush } from '../../../push/push';

// Notifications and app (ADR-050). For the person using this device: install BMS as an app, choose how it looks (issue #452), and turn on notifications
// for the things that need them (a sale voided, a write-off made alone, an approval limit raised, stock sent to their branch, an
// approval waiting for them). The permission question is asked only when the switch is tapped, never when the page opens. The
// list below shows the person's own devices and lets them remove one; the server never shows an address or a key.

const defaultPush = createPush();

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Try again.');

function When({ iso }: { iso?: string | null }) {
  if (!iso) return null;
  const d = new Date(iso);
  return <>{Number.isNaN(d.getTime()) ? '' : d.toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })}</>;
}

/** What the switch says about this device, in plain words. */
export function stateWords(state: PushState | undefined, ios: boolean): string {
  switch (state) {
    case 'on':
      return 'On. This device will tell you when something needs you.';
    case 'off':
      return 'Off. Turn it on to hear when something needs you.';
    case 'blocked':
      return 'Blocked. This browser was told not to show notifications from BMS. Allow them in the browser settings for this site, then come back here.';
    case 'not_offered':
      return 'Notifications are not switched on for this server yet. Ask your administrator.';
    case 'unsupported':
      return ios
        ? 'On an iPhone or iPad, first add BMS to your home screen (see Install the app above), then open BMS from its icon and turn this on.'
        : 'This browser cannot show notifications from BMS.';
    default:
      return 'Checking this device.';
  }
}

export function PushToggle({ push = defaultPush }: { push?: ReturnType<typeof createPush> }) {
  const queryClient = useQueryClient();
  const state = useQuery({ queryKey: ['push-state'], queryFn: () => push.state(), retry: false });
  const [error, setError] = useState<string | null>(null);
  const change = useMutation({
    mutationFn: (on: boolean) => (on ? push.enable() : push.disable()),
    onSuccess: (next) => {
      setError(null);
      queryClient.setQueryData(['push-state'], next);
      void queryClient.invalidateQueries({ queryKey: ['push-devices'] });
    },
    onError: (e) => setError(messageOf(e)),
  });
  const current = state.data;
  const on = current === 'on';
  // Only a state the person can change shows an enabled switch: blocked, unsupported and not offered are explained instead.
  const usable = current === 'on' || current === 'off';
  return (
    <div className="stack">
      <label>
        <input
          type="checkbox"
          role="switch"
          checked={on}
          disabled={!usable || change.isPending}
          onChange={(e) => change.mutate(e.target.checked)}
          aria-describedby="push-state-words"
        />{' '}
        Notifications on this device
      </label>
      <p id="push-state-words" role="status">
        {change.isPending ? 'Working.' : state.isError ? 'Could not check this device. Reload the page.' : stateWords(current, isIosDevice())}
      </p>
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
    </div>
  );
}

function Devices() {
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: ['push-devices'], queryFn: () => pushApi.list(), retry: false });
  const [error, setError] = useState<string | null>(null);
  const remove = useMutation({
    mutationFn: (device: PushDevice) => pushApi.remove(device.id ?? ''),
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['push-devices'] });
      void queryClient.invalidateQueries({ queryKey: ['push-state'] });
    },
    onError: (e) => setError(messageOf(e)),
  });
  const devices = list.data ?? [];
  return (
    <div className="stack">
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
      {list.isError && <p role="alert">Could not load your devices.</p>}
      {!list.isError && devices.length === 0 && <p className="hint">No device receives notifications for you yet.</p>}
      {devices.length > 0 && (
        <ul className="stack">
          {devices.map((device) => (
            <li key={device.id} className="cluster">
              <span>
                <strong>{device.label}</strong>
                <br />
                <span className="hint">
                  Added <When iso={device.created_at} />
                  {device.last_used_at ? (
                    <>
                      , last used <When iso={device.last_used_at} />
                    </>
                  ) : null}
                </span>
              </span>
              <button type="button" className="btn-ghost btn-sm" disabled={remove.isPending} onClick={() => remove.mutate(device)}>
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Light, Dark or Same as my phone, kept on this device (issue #452): the same choice as the theme button in the header. */
export function Appearance() {
  const [choice, setChoice] = useThemeChoice();
  return (
    <div role="radiogroup" aria-labelledby="appearance-title" aria-describedby="appearance-words">
      <p id="appearance-words">How BMS looks on this device.</p>
      {THEME_CHOICES.map((value) => (
        <label key={value}>
          <input type="radio" name="appearance" value={value} checked={choice === value} onChange={() => setChoice(value)} />{' '}
          {THEME_LABELS[value]}
        </label>
      ))}
    </div>
  );
}

export function NotificationsPage({ push = defaultPush }: { push?: ReturnType<typeof createPush> }) {
  return (
    <main className="rt ln">
      <h1>Notifications and app</h1>
      <section className="rt-card stack" aria-labelledby="install-title" data-tour="install-app">
        <h2 id="install-title">Install the app</h2>
        <InstallPanel />
      </section>
      <section className="rt-card stack" aria-labelledby="appearance-title" data-tour="appearance">
        <h2 id="appearance-title">Appearance</h2>
        <Appearance />
      </section>
      <section className="rt-card stack" aria-labelledby="notify-title" data-tour="notifications-toggle">
        <h2 id="notify-title">Notifications on this device</h2>
        <p>
          You hear when a sale is voided, a debt is written off without a second person, an approval limit is raised, stock is sent to your
          branch, or an approval is waiting for you. A notification says what happened and where to look. It never shows a name, a phone
          number or an amount.
        </p>
        <PushToggle push={push} />
      </section>
      <section className="rt-card stack" aria-labelledby="devices-title" data-tour="notifications-devices">
        <h2 id="devices-title">Your devices</h2>
        <Devices />
      </section>
    </main>
  );
}

export const Route = createLazyRoute('/staff/notifications')({ component: NotificationsPage });
