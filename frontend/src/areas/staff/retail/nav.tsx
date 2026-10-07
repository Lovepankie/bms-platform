import { Link } from '@tanstack/react-router';
import type { Me } from '../../../api/client';
import { icons } from '../../../components/icons';
import { SCREENS, canUse, type RetailScreen } from './permissions';

// The retail menu (#95): the retail home and the first three shortcuts the session may use, most
// used first, with the short names a phone's bottom bar has room for. Fixed to the bottom on a
// phone, a row of links above the title on a wider screen. Drawn by the staff layout.

const SHORTCUTS: Partial<Record<RetailScreen, string>> = { sale: 'Sale', stock: 'Stock', restock: 'Restock', stocktake: 'Count', usage: 'Usage' };

export function RetailNav({ me }: { me: Pick<Me, 'permissions'> }) {
  const order = Object.keys(SHORTCUTS) as RetailScreen[];
  const shortcuts = SCREENS.filter((s) => canUse(me, s.screen) && s.screen in SHORTCUTS)
    .sort((a, b) => order.indexOf(a.screen) - order.indexOf(b.screen))
    .slice(0, 3);
  return (
    <nav className="bottom-nav" aria-label="Retail">
      <Link to="/staff/retail" activeOptions={{ exact: true }}>
        {icons.home}
        <span>Retail</span>
      </Link>
      {shortcuts.map((s) => (
        <Link key={s.screen} to={s.path as never}>
          {icons[s.screen as keyof typeof icons]}
          <span>{SHORTCUTS[s.screen]}</span>
        </Link>
      ))}
    </nav>
  );
}
