import { createLazyRoute } from '@tanstack/react-router';
import { Tile, TileGrid } from '../../../app/ui/Tile';
import { icons } from '../../../components/icons';
import { useStaff } from '../context';
import { RetailDashboard } from './dashboard';
import { OwnSales } from './own-sales';
import { ALL_SELLERS, WHOLESALE_SCREENS, canUse, holds, screensFor, showRetail } from './permissions';
import { ShopHere, useCanSellAnywhere } from './ui';
import { NoAccess } from '../../../app/ui/EmptyState';

// The Retail landing page: one big tile per screen the session may use. Nothing else is listed. The wholesale tools' pages
// follow under their own Wholesale heading (issue #424), the same words as the menu's Wholesale group.

export function RetailHome() {
  const { me } = useStaff();
  const sells = useCanSellAnywhere(me);
  const all = showRetail(me) ? screensFor(me, { sells }) : [];
  const screens = all.filter((s) => !WHOLESALE_SCREENS.includes(s.screen));
  const wholesale = all.filter((s) => WHOLESALE_SCREENS.includes(s.screen));
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
      <ShopHere />
      {dashboard && ownerFirst && <RetailDashboard />}
      {all.length === 0 && <NoAccess what="any retail pages" />}
      {screens.length > 0 && (
        <TileGrid label="Retail pages" tour="retail-tiles">
          {screens.map((s) => (
            <Tile key={s.screen} to={s.path} icon={icons[s.screen as keyof typeof icons]} label={s.label} hint={s.hint} tour={`retail-${s.screen}`} />
          ))}
        </TileGrid>
      )}
      {wholesale.length > 0 && (
        <section aria-labelledby="retail-wholesale-heading">
          <h2 id="retail-wholesale-heading">Wholesale</h2>
          <TileGrid label="Wholesale pages" tour="retail-wholesale-tiles">
            {wholesale.map((s) => (
              <Tile key={s.screen} to={s.path} icon={icons[s.screen as keyof typeof icons]} label={s.label} hint={s.hint} tour={`retail-${s.screen}`} />
            ))}
          </TileGrid>
        </section>
      )}
      {dashboard && !ownerFirst && <RetailDashboard />}
      {ownSales && <OwnSales />}
    </main>
  );
}

export const Route = createLazyRoute('/staff/retail/')({ component: RetailHome });
