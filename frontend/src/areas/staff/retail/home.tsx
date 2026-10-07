import { Link, createLazyRoute } from '@tanstack/react-router';
import { icons } from '../../../components/icons';
import { useStaff } from '../context';
import { screensFor, showRetail } from './permissions';

// The Retail landing page: one big tile per screen the session may use. Nothing else is listed.

export function RetailHome() {
  const { me } = useStaff();
  const screens = showRetail(me) ? screensFor(me) : [];
  return (
    <main className="rt">
      <div className="page-header">
        <div>
          <h1>Retail</h1>
          <p>What would you like to do?</p>
        </div>
      </div>
      {screens.length === 0 && <p className="empty-state">You do not have access to any retail pages.</p>}
      <ul className="tile-grid" data-tour="retail-tiles">
        {screens.map((s) => (
          <li key={s.screen}>
            <Link className="rt-tile tile" to={s.path as never} data-tour={`retail-${s.screen}`}>
              <span className="icon-chip">{icons[s.screen as keyof typeof icons]}</span>
              <strong>{s.label}</strong>
              <span className="tile-hint">{s.hint}</span>
            </Link>
          </li>
        ))}
      </ul>
    </main>
  );
}

export const Route = createLazyRoute('/staff/retail/')({ component: RetailHome });
