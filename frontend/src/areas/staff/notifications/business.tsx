import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { type NotificationSettings, type NotificationSettingsChange, notificationSettingsApi } from '../../../api/notification-settings';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useStaff } from '../context';
import { Hint } from '../../../app/ui/Hint';
import { sayDone } from '../retail/toast';

// Business set-up > Notifications (issue #412, ADR-057): what the business sends by push, each everyday notice on or off, and when
// the daily closing summary goes. Everyone still turns notifications on for their own device in Notifications and app; this page only
// says what the business sends. The safeguard notices (a sale voided, a write-off alone, an approval limit raised, two-step sign-in
// relaxed, an approval waiting, stock on its way) are always sent and are not listed here.

type Switch = 'new_orders' | 'order_updates' | 'low_stock' | 'credit_due' | 'daily_summary';

export const SWITCHES: { key: Switch; title: string; words: string }[] = [
  { key: 'new_orders', title: 'New orders', words: 'An order taken by phone, WhatsApp, Instagram or Facebook, to the staff of its shop.' },
  { key: 'order_updates', title: 'Orders on their way and delivered', words: 'To the person who took the order.' },
  { key: 'low_stock', title: 'Stock running low', words: 'An item below the level you set, or about to run out at the pace it sells. Once a day per item, to that shop or store and the administrators.' },
  { key: 'credit_due', title: 'Credit payments to collect', words: 'Each morning when a credit sale falls due or becomes overdue, to the administrators and the supervisors of that shop.' },
  { key: 'daily_summary', title: 'Daily closing summary', words: 'The number of sales at each shop today, to the owner and the administrators.' },
];

/** The job wakes on every quarter hour, so the summary time is a quarter hour, at the latest 23:30. */
export const QUARTER_HOUR = /^(([01][0-9]|2[0-2]):(00|15|30|45)|23:(00|15|30))$/;

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Try again.');

export function BusinessNotificationsScreen() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const canChange = (me.permissions ?? []).includes('core.settings.manage');
  const settings = useQuery({ queryKey: ['notification-settings'], queryFn: notificationSettingsApi.get });
  const [time, setTime] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // One save at a time: a second tap before the first answer must not send a second request.
  const working = useRef(false);

  if (settings.isError) return <p role="alert">Could not load the notification settings.</p>;
  const current: NotificationSettings | undefined = settings.data;
  if (!current) return <p className="loading">Loading</p>;
  const shownTime = time ?? current.daily_summary_time ?? '20:00';

  async function save(change: NotificationSettingsChange, words: string) {
    if (working.current || !current) return;
    working.current = true;
    setBusy(true);
    setError(null);
    try {
      const next = await notificationSettingsApi.update(current.version ?? 1, change);
      queryClient.setQueryData(['notification-settings'], next);
      sayDone(words);
    } catch (e) {
      setError(messageOf(e));
      void queryClient.invalidateQueries({ queryKey: ['notification-settings'] });
    } finally {
      working.current = false;
      setBusy(false);
    }
  }

  return (
    <main className="rt ln">
      <h1>Notifications for the business</h1>
      <p data-tour="business-notify-intro">
        What BMS tells your staff on their phones. Each person turns notifications on for their own device in{' '}
        <Link to="/staff/notifications">Notifications and app</Link>. A notification never shows a customer's name, a phone number or an
        amount.
      </p>
      {!current.push_available && (
        <p role="note" className="alert alert-warning">
          Notifications are not switched on for this server yet, so nothing is sent whatever you choose here.
        </p>
      )}
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
      <section className="rt-card stack" aria-labelledby="business-notify-what" data-tour="business-notify-switches">
        <h2 id="business-notify-what">What to send</h2>
        {SWITCHES.map((s) => {
          const on = current[s.key] !== false;
          return (
            <div key={s.key} className="stack">
              <label>
                <input
                  type="checkbox"
                  role="switch"
                  checked={on}
                  disabled={!canChange || busy}
                  onChange={(e) => void save({ [s.key]: e.target.checked }, `${s.title} ${e.target.checked ? 'on' : 'off'}`)}
                />{' '}
                {s.title}
              </label>
              <p className="hint">{s.words}</p>
            </div>
          );
        })}
      </section>
      <section className="rt-card stack" aria-labelledby="business-notify-time" data-tour="business-notify-time">
        <h2 id="business-notify-time">When the daily summary goes</h2>
        <label htmlFor="summary-time">Time ({current.timezone ?? 'Africa/Kampala'})</label>
        <input id="summary-time" type="time" step={900} max="23:30" value={shownTime} disabled={!canChange || busy} onChange={(e) => setTime(e.target.value)} />
        {canChange && (
          <p>
            <BusyButton
              type="button"
              className="rt-primary"
              busy={busy}
              disabled={!QUARTER_HOUR.test(shownTime) || shownTime === current.daily_summary_time}
              onClick={() => void save({ daily_summary_time: shownTime }, `Summary now at ${shownTime}`)}
            >
              Save the time
            </BusyButton>
          </p>
        )}
        <Hint>A quarter hour, at the latest 23:30. If the server was down at that time, the summary comes the next morning as yesterday's.</Hint>
        {!canChange && <p className="hint">Only an administrator can change these.</p>}
      </section>
    </main>
  );
}

export const Route = createLazyRoute('/staff/setup/notifications')({ component: BusinessNotificationsScreen });
