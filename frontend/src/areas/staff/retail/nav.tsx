import { Link } from '@tanstack/react-router';
import type { Me } from '../../../api/client';
import { icons } from '../../../components/icons';
import { SCREENS, canUse, type RetailScreen } from './permissions';

// The retail menu (#95): the retail home and the first shortcuts the session may use, with the short
// names a phone's bottom bar has room for. The pilot's order comes first (Sales, Banked, Savings,
// Expenses, Advances, FR-RET-31), then the stock shortcuts; five fit at 360px beside Retail. Fixed to
// the bottom on a phone, a row of links above the title on a wider screen. Drawn by the staff layout.

const SHORTCUTS: Partial<Record<RetailScreen, string>> = {
  sale: 'Sale', banking: 'Banked', savings: 'Savings', expenses: 'Expenses', advances: 'Advances', stock: 'Stock', restock: 'Restock', stocktake: 'Count', usage: 'Usage',
};
const MAX_SHORTCUTS = 5;

/** The shortcuts the session may use, in the pilot's order, at most as many as the bar has room for. */
export function shortcutsFor(me: Pick<Me, 'permissions'>) {
  const order = Object.keys(SHORTCUTS) as RetailScreen[];
  return SCREENS.filter((s) => canUse(me, s.screen) && s.screen in SHORTCUTS)
    .sort((a, b) => order.indexOf(a.screen) - order.indexOf(b.screen))
    .slice(0, MAX_SHORTCUTS);
}

export function RetailNav({ me }: { me: Pick<Me, 'permissions'> }) {
  const shortcuts = shortcutsFor(me);
  return (
    <nav className="bottom-nav" aria-label="Retail">
      <Link to="/staff/retail" activeOptions={{ exact: true }}>
        {icons.home}
        <span>Retail</span>
      </Link>
      {shortcuts.map((s) => (
        <Link key={s.screen} to={s.path as never}>
          {icons[s.screen]}
          <span>{SHORTCUTS[s.screen]}</span>
        </Link>
      ))}
    </nav>
  );
}
