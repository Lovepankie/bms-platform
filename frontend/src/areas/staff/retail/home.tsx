import { Link, createLazyRoute } from '@tanstack/react-router';
import { useStaff } from '../context';
import { screensFor, showRetail } from './permissions';
import { RetailStyles } from './ui';

// The Retail landing page: one big tile per screen the session may use. Nothing else is listed.

function RetailHome() {
  const { me } = useStaff();
  const screens = showRetail(me) ? screensFor(me) : [];
  return (
    <main className="rt">
      <RetailStyles />
      <h1>Retail</h1>
      {screens.length === 0 && <p>You do not have access to any retail pages.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {screens.map((s) => (
          <li key={s.screen} className="rt-card">
            <Link className="rt-tile" to={s.path as never} style={{ display: 'block' }}>
              <strong>{s.label}</strong>
              <br />
              {s.hint}
            </Link>
          </li>
        ))}
      </ul>
    </main>
  );
}

export const Route = createLazyRoute('/staff/retail/')({ component: RetailHome });
