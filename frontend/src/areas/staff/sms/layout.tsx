import { Link, Outlet, createLazyRoute } from '@tanstack/react-router';
import { useStaff } from '../context';
import { showSms } from './state';
import { NoAccess } from '../../../app/ui/EmptyState';

// The SMS pages (extras slice B3, issue #218): settings, the opt-out list and the wording, under one heading with a small
// menu. Who sees what is decided by the server; the menu only hides what the session cannot open.

export function SmsLayout() {
  const { me } = useStaff();
  if (!showSms(me)) {
    return (
      <main className="rt ln">
        <h1>Text messages (SMS)</h1>
        <NoAccess />
      </main>
    );
  }
  return (
    <main className="rt ln">
      <h1>Text messages (SMS)</h1>
      <nav className="sv-nav" aria-label="SMS pages">
        <Link to="/staff/sms" activeOptions={{ exact: true }}>Settings</Link>
        <Link to="/staff/sms/wording">Wording</Link>
        <Link to="/staff/sms/opt-outs">Opt-outs</Link>
      </nav>
      <Outlet />
    </main>
  );
}

export const Route = createLazyRoute('/staff/sms')({ component: SmsLayout });
