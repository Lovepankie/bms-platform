import { Link, createLazyRoute } from '@tanstack/react-router';
import { icons } from '../../../components/icons';
import { useStaff } from '../context';
import { RetailDashboard } from './dashboard';
import { canUse, screensFor, showRetail } from './permissions';

// The Retail landing page: one big tile per screen the session may use. Nothing else is listed.

export function RetailHome() {
  const { me } = useStaff();
  const screens = showRetail(me) ? screensFor(me) : [];
  // The dashboard is a sales read; its profit and stock parts depend on the other permissions.
  const dashboard = showRetail(me) && canUse(me, 'salesHistory');
  // The owner reads the figures first; a seller starts from the tiles (Record a sale) and finds them below.
  const ownerFirst = canUse(me, 'profit');
  return (
    <main className={dashboard ? 'rt rt-wide' : 'rt'}>
      <div className="page-header">
        <div>
          <h1>Retail</h1>
          <p>What would you like to do?</p>
        </div>
      </div>
      {dashboard && ownerFirst && <RetailDashboard />}
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
      {dashboard && !ownerFirst && <RetailDashboard />}
    </main>
  );
}

export const Route = createLazyRoute('/staff/retail/')({ component: RetailHome });
