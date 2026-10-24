import { useQuery } from '@tanstack/react-query';
import { Link, useRouterState } from '@tanstack/react-router';
import { type ReactNode, type Ref, useEffect, useMemo, useRef, useState } from 'react';
import type { Me } from '../../api/client';
import { icons } from '../../components/icons';
import { useIsPhone } from '../../components/side-menu';
import { useOptionalStaff } from './context';
import { homeParts, homeSummaryQuery } from './home-cards';
import { showSavings } from './lending/savings-permissions';
import { staffAreas } from './menu';
import { showReceivePayments } from './payments/state';
import { useSells } from './retail/nav';
import { SCREENS, canUse, showRetail, type RetailScreen } from './retail/permissions';

// The bottom tabs on a phone (issue #539, decisions D29 and D31): the five daily jobs, Sell, My stock, Buy, Money and More, each an
// icon above its label. Below 720px they replace the side menu's drawer; from 720px the sidebar stays and there are no tabs. A tab
// whose module or capability is off, or whose pages the session may not use, is not drawn; More is always there and lists the
// staff areas the session may open (menu.tsx). The bar is fixed to the bottom, takes the iPhone's home indicator inset, and sets
// the class has-tab-bar and its measured height on the root while it shows, so a sticky action bar (the sale's total, a save bar)
// sits on top of it and never under it (navigation.css). While the soft keyboard is up the bar steps aside.

export type TabId = 'sell' | 'stock' | 'buy' | 'money' | 'more';

export interface Tab {
  id: TabId;
  to: string;
  label: string;
  icon: ReactNode;
  /** The pages on which this tab is the current one, each with the pages under it. */
  paths: string[];
}

const pathOf = (screen: RetailScreen) => SCREENS.find((s) => s.screen === screen)?.path ?? '';

/** The money pages under the Money tab: the cash book, then who owes and what is owed, then lending savings and payments in. */
const MONEY_SCREENS: RetailScreen[] = [
  'cashSummary', 'banking', 'savings', 'expenses', 'withdrawals', 'advances', 'creditSales', 'creditControl', 'customerAccounts', 'customerAgeing', 'supplierPayables',
];

export interface MoneyLink {
  to: string;
  label: string;
  hint: string;
}

/** The money pages the session may use, in the order the Money page lists them. Empty means no Money tab. */
export function moneyLinks(me: Me): MoneyLink[] {
  const retail = showRetail(me) ? SCREENS.filter((s) => MONEY_SCREENS.includes(s.screen) && canUse(me, s.screen)) : [];
  const links: MoneyLink[] = retail
    .sort((a, b) => MONEY_SCREENS.indexOf(a.screen) - MONEY_SCREENS.indexOf(b.screen))
    .map((s) => ({ to: s.path, label: s.screen === 'creditSales' ? 'Customers who owe' : s.label, hint: s.hint }));
  if (showSavings(me)) links.push({ to: '/staff/lending/savings', label: 'Member savings', hint: 'Deposits and withdrawals of members' });
  if (showReceivePayments(me)) links.push({ to: '/staff/payments', label: 'Receive payments', hint: 'Mobile money and card payments into the business' });
  return links;
}

/** The tabs the session gets, in their fixed order. `sells` is useSells: false while every branch it may sell at is a store. */
export function staffTabs(me: Me, { sells }: { sells: boolean }): Tab[] {
  const money = moneyLinks(me);
  const tabs: (Tab | false)[] = [
    sells && { id: 'sell', to: pathOf('sale'), label: 'Sell', icon: icons.sale, paths: [pathOf('sale')] },
    showRetail(me) && canUse(me, 'stock') && { id: 'stock', to: pathOf('stock'), label: 'My stock', icon: icons.stock, paths: [pathOf('stock'), pathOf('stocktake')] },
    showRetail(me) && canUse(me, 'restock') && {
      id: 'buy',
      to: pathOf('restock'),
      label: 'Buy',
      icon: icons.restock,
      paths: [pathOf('restock'), '/staff/retail/restocks', pathOf('purchaseOrders'), pathOf('goodsReceived')],
    },
    money.length > 0 && { id: 'money', to: '/staff/money', label: 'Money', icon: icons.coins, paths: ['/staff/money', ...money.map((m) => m.to)] },
    { id: 'more', to: '/staff/more', label: 'More', icon: icons.menu, paths: ['/staff/more'] },
  ];
  return tabs.filter((t): t is Tab => t !== false);
}

const under = (pathname: string, path: string) => pathname === path || pathname.startsWith(`${path}/`);

/** The tab current on `pathname`, if any: Home and the pages only More lists have none. */
export function currentTab(tabs: Tab[], pathname: string): TabId | undefined {
  return tabs.find((t) => t.paths.some((p) => under(pathname, p)))?.id;
}

// The fields that bring up the keyboard. A select is left out: its picker is not the keyboard, and focus stays on it after a choice.
const TEXT_ENTRY = ['', 'text', 'search', 'tel', 'number', 'email', 'password', 'url'].map((t) => (t ? `input[type=${t}]` : 'input:not([type])')).concat('textarea', '[contenteditable="true"]').join(', ');
const typingIn = (el: EventTarget | null) => el instanceof Element && el.matches(TEXT_ENTRY);

// How far the visual viewport must shrink under the layout one before it counts as the soft keyboard, not a browser bar.
const KEYBOARD_PX = 150;

/**
 * True while the phone's soft keyboard is up: a text field has focus and the visual viewport has shrunk by a keyboard's height. A
 * field that only has focus (a page that focuses its search or Scan box, a barcode scanner or a Bluetooth keyboard typing into it)
 * leaves the tabs where they are. A browser without visualViewport never hides them.
 */
function useSoftKeyboard(): boolean {
  const [up, setUp] = useState(false);
  useEffect(() => {
    const view = window.visualViewport;
    if (!view) return;
    let frame = 0;
    // Read after the event, when the focus has moved and the viewport has its new size.
    const check = () => {
      window.cancelAnimationFrame(frame);
      // Height times scale: a pinch zoom shrinks the visual viewport in CSS pixels but not on the screen, and is no keyboard.
      frame = window.requestAnimationFrame(() => setUp(typingIn(document.activeElement) && window.innerHeight - view.height * (view.scale ?? 1) > KEYBOARD_PX));
    };
    check();
    view.addEventListener('resize', check);
    document.addEventListener('focusin', check);
    document.addEventListener('focusout', check);
    return () => {
      window.cancelAnimationFrame(frame);
      view.removeEventListener('resize', check);
      document.removeEventListener('focusin', check);
      document.removeEventListener('focusout', check);
    };
  }, []);
  return up;
}

/**
 * How many items are low on stock in the branch chosen: the Home summary's own read (homeSummaryQuery: one key, gate and freshness), so the two share one request,
 * held fresh for a minute so moving between pages does not ask the heavy summary again.
 */
function useLowStock(me: Me): number {
  const branch = useOptionalStaff()?.branch ?? null;
  const parts = homeParts(me, branch);
  // Home's own options, so the badge asks only when Home would, and only when it shows a count.
  const options = homeSummaryQuery(parts, branch);
  // A minute between reads for the badge only, so moving between pages does not re-read the summary (#539).
  const home = useQuery({ ...options, enabled: options.enabled && parts.lowStock, staleTime: 60_000 });
  return parts.lowStock ? (home.data?.low_stock ?? 0) : 0;
}

/**
 * The tab bar itself, on any screen size (the tour test draws it beside the sidebar). Each tab is a link with aria-current on the
 * current one; My stock carries a badge with the count of items low on stock, read out with its label. The More tab also answers
 * for the tour's menu anchors (data-tour-also), since on a phone the links they name live under More.
 */
export function TabBar({ me, away = false, barRef }: { me: Me; away?: boolean; barRef?: Ref<HTMLElement> }) {
  const sells = useSells(me);
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const low = useLowStock(me);
  const tabs = staffTabs(me, { sells });
  const current = currentTab(tabs, pathname);
  const underMore = useMemo(() => staffAreas(me, { sells }).flatMap((l) => (l.tour ? [l.tour] : [])), [me, sells]);
  return (
    <nav ref={barRef} className="tab-bar" aria-label="Main" data-tour="tab-bar" data-tour-also="retail-nav" hidden={away}>
      <ul>
        {tabs.map((t) => {
          const badge = t.id === 'stock' && low > 0 ? low : 0;
          return (
            <li key={t.id}>
              <Link
                to={t.to as never}
                className="tab"
                aria-current={t.id === current ? 'page' : undefined}
                data-tour={`tab-${t.id}`}
                data-tour-also={t.id === 'more' ? ['nav-areas', ...underMore].join(' ') : undefined}
              >
                <span className="tab-icon">
                  {t.icon}
                  {badge > 0 && (
                    <span className="tab-badge" aria-hidden="true">
                      {badge > 99 ? '99+' : badge}
                    </span>
                  )}
                </span>
                <span className="tab-label">{t.label}</span>
                {badge > 0 && <span className="visually-hidden">, {badge} {badge === 1 ? 'item' : 'items'} low on stock</span>}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

/**
 * The tabs on a phone only. While the soft keyboard is up they stay mounted but hidden, so the badge's read is not asked again on
 * every focus. While they show, the root carries has-tab-bar and --tab-bar is their measured height (safe-area inset and edge
 * included, at any font size), as the sale bar measures --bottom-bar; the CSS value is only the first paint's.
 */
export function StaffTabBar({ me }: { me: Me }) {
  const phone = useIsPhone();
  const keyboard = useSoftKeyboard();
  const bar = useRef<HTMLElement>(null);
  const shown = phone && !keyboard;
  useEffect(() => {
    const el = bar.current;
    if (!shown || !el) return;
    const root = document.documentElement;
    root.classList.add('has-tab-bar');
    const measure = () => {
      const height = Math.ceil(el.getBoundingClientRect().height);
      if (height > 0) root.style.setProperty('--tab-bar', `${height}px`);
      else root.style.removeProperty('--tab-bar');
    };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(el);
    return () => {
      observer?.disconnect();
      root.classList.remove('has-tab-bar');
      root.style.removeProperty('--tab-bar');
    };
  }, [shown]);
  return phone ? <TabBar me={me} away={keyboard} barRef={bar} /> : null;
}
