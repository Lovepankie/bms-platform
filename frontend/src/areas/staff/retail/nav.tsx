import type { Me } from '../../../api/client';
import { icons } from '../../../components/icons';
import { MenuItemLink } from '../../../components/side-menu';
import { SCREENS, canUse, showRetail, type RetailScreen } from './permissions';
import { useCanSellAnywhere } from './ui';

// The retail shortcuts (#95): the first retail screens the session may use, with short names. The pilot's order comes first (Sales,
// Banked, Savings, Expenses, Advances, FR-RET-31), with customer orders right after Sale (issue #385), then the stock shortcuts.
// Since issue #455 they are a group under Retail in the side menu on every screen size, not a bar of their own, and a phone has a
// one-tap Sell button in the staff bar for anyone who can sell.

const SHORTCUTS: Partial<Record<RetailScreen, string>> = {
  sale: 'Sale', orders: 'Orders', banking: 'Banked', savings: 'Savings', expenses: 'Expenses', advances: 'Advances', stock: 'Stock', restock: 'Restock', stocktake: 'Count', usage: 'Usage',
};
const MAX_SHORTCUTS = 5;

/** The shortcuts the session may use, in the pilot's order, at most as many as the bar has room for. */
export function shortcutsFor(me: Pick<Me, 'permissions' | 'module_capabilities'>, opts: { sells?: boolean } = {}) {
  const order = Object.keys(SHORTCUTS) as RetailScreen[];
  return SCREENS.filter((s) => canUse(me, s.screen) && s.screen in SHORTCUTS && (opts.sells !== false || s.screen !== 'sale'))
    .sort((a, b) => order.indexOf(a.screen) - order.indexOf(b.screen))
    .slice(0, MAX_SHORTCUTS);
}

/**
 * True when the session may record a sale somewhere: retail is on, the sale screen is allowed and, with branch roles, some branch it
 * sells at is not a store. The branch roles are read only when the first two hold.
 */
export function useSells(me: Me): boolean {
  const may = showRetail(me) && canUse(me, 'sale');
  const anywhere = useCanSellAnywhere(me, may);
  return may && anywhere;
}

/** The shortcuts as a group under Retail in the side menu, the sidebar and the phone drawer alike (issue #455). */
export function RetailMenuGroup({ shortcuts }: { shortcuts: ReturnType<typeof shortcutsFor> }) {
  if (shortcuts.length === 0) return null;
  return (
    <ul className="side-menu-group" aria-label="Retail" data-tour="retail-nav">
      {shortcuts.map((s) => (
        <li key={s.screen}>
          <MenuItemLink to={s.path} label={SHORTCUTS[s.screen] ?? s.screen} icon={icons[s.screen as keyof typeof icons]} />
        </li>
      ))}
    </ul>
  );
}
