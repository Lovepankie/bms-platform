import { Link, createLazyRoute } from '@tanstack/react-router';
import { icons } from '../../../components/icons';
import { useStaff } from '../context';
import { RetailDashboard } from './dashboard';
import { OwnSales } from './own-sales';
import { ALL_SELLERS, canUse, holds, screensFor, showRetail } from './permissions';
import { useCanSellAnywhere } from './ui';

// The Retail landing page: one big tile per screen the session may use. Nothing else is listed.

export function RetailHome() {
  const { me } = useStaff();
  const sells = useCanSellAnywhere(me);
  const screens = showRetail(me) ? screensFor(me, { sells }) : [];
  // The dashboard adds up every seller's sales, so it is the owner's (ADR-055); its profit and stock parts depend on the other
  // permissions. A seller who sees only their own figures gets their own sales instead.
  const dashboard = showRetail(me) && canUse(me, 'salesHistory') && holds(me, ALL_SELLERS);
  const ownSales = showRetail(me) && canUse(me, 'salesHistory') && !holds(me, ALL_SELLERS);
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
      {ownSales && <OwnSales />}
    </main>
  );
}

export const Route = createLazyRoute('/staff/retail/')({ component: RetailHome });
