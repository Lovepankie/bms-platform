import { useQuery } from '@tanstack/react-query';
import type { Me } from '../../api/client';
import { WORDS } from '../../app/glossary';
import { icons } from '../../components/icons';
import type { MenuGroup, MenuLink, MenuSection } from '../../components/side-menu';
import { useOptionalStaff } from './context';
import { homeParts, homeSummaryQuery } from './home-cards';
import { canConfigureAdvisor, showInsights } from './insights/permissions';
import { showInvestments } from './lending/investment-state';
import { showCollateral, showMembers } from './lending/member-permissions';
import { showLending } from './lending/permissions';
import { showSavings } from './lending/savings-permissions';
import { showReceivePayments } from './payments/state';
import { showReports } from './reports/permissions';
import { CATALOGUE_LINKS, SCREENS, UNITS, canUse, capabilityOn, showRetail, type RetailScreen } from './retail/permissions';
import { showSms } from './sms/state';
import { showUsers } from './users/state';

// The staff menu's groups and items (issue #564, owner decisions D38 and D44, ADR-086), apart from the layout in route.tsx so a
// test reads them without loading the staff area (issue #483). One list, grouped the way a shop owner thinks: Home, then Today,
// Stock, Money, Wholesale (when a wholesale tool is on), Lending (when the business has it) and Business, each item filtered by
// the same permission rules as before. The sidebar from 720px and the phone drawer both draw it; Notifications and Help and
// feedback are in the menu's footer.

/**
 * The modules of a business with both, Shop and Lending (issue #471). The menu no longer heads them (issue #564); the Home cards
 * and the More page still do, and a link names its module with `section`.
 */
export const STAFF_SECTIONS: MenuSection[] = [
  { id: 'shop', label: 'Shop', icon: icons.shop },
  { id: 'lending', label: 'Lending', icon: icons.lending },
];

/** The figures some rows carry at their right: the open orders and the items low on stock in the branch in use. */
export interface MenuCounts {
  orders?: number;
  low?: number;
}

const pathOf = (screen: RetailScreen) => [...SCREENS, ...CATALOGUE_LINKS].find((s) => s.screen === screen)?.path ?? '';

/** A retail row: the first of `screens` the session may use, under its menu name; none when it may use none of them. */
function shopRow(me: Me, label: string, icon: keyof typeof icons, screens: RetailScreen[], extra: Partial<MenuLink> = {}): MenuLink | false {
  const screen = showRetail(me) && screens.find((s) => canUse(me, s));
  return !!screen && { to: pathOf(screen), label, icon: icons[icon], section: 'shop', ...extra };
}

const count = (n: number | undefined, words = '') => (n && n > 0 ? `${n > 99 ? '99+' : n}${words}` : undefined);

/**
 * The menu's groups for the session, in order; a group the session has nothing in is left out by the menu. `sells` is useSells
 * (false while every branch the session may sell at is a store) and `counts` the figures of useMenuCounts.
 */
export function staffMenu(me: Me, { sells, counts = {} }: { sells: boolean; counts?: MenuCounts }): MenuGroup[] {
  const permissions = me.permissions ?? [];
  const retail = showRetail(me);
  // Reports is the shop's page of figures and every shop page; a lending business without the shop has its own Reports page, which
  // a business with both lists in its Lending group.
  const lendingReports = showReports(me);
  const groups: { id: string; label?: string; tour?: string; links: (MenuLink | false)[] }[] = [
    { id: 'home', links: [{ to: '/staff', label: 'Home', icon: icons.home, exact: true }] },
    {
      id: 'today',
      label: 'Today',

      links: [
        sells && shopRow(me, 'Sell', 'sale', ['sale']),
        shopRow(me, 'Orders', 'orders', ['orders'], { count: count(counts.orders) }),
        permissions.includes('core.approvals.read') && { to: '/staff/approvals', label: 'Approvals', icon: icons.approve, tour: 'nav-approvals' },
      ],
    },
    {
      id: 'stock',
      label: 'Stock',
      links: [
        shopRow(me, 'My stock', 'stock', ['stock'], { count: count(counts.low, ' low') }),
        shopRow(me, WORDS.buyStock, 'restock', ['restock']),
        shopRow(me, WORDS.countStock, 'stocktake', ['stocktake']),
        shopRow(me, WORDS.moveStock, 'transfer', ['transfer']),
        shopRow(me, 'Items and categories', 'catalogue', ['catalogue']),
      ],
    },
    {
      id: 'money',
      label: 'Money',
      links: [
        shopRow(me, 'Cash book', 'cashSummary', ['cashSummary', 'banking', 'withdrawals']),
        shopRow(me, 'Expenses', 'expenses', ['expenses']),
        shopRow(me, WORDS.customersWhoOwe, 'creditSales', ['creditSales', 'customerAccounts']),
        shopRow(me, WORDS.whatIOweSuppliers, 'supplierPayables', ['supplierPayables']),
        shopRow(me, 'Savings and advances', 'savings', ['savings', 'advances']),
      ],
    },
    {
      // The wholesale tools (issue #424, ADR-092): shown while one of their switches is on, whatever kind of business was chosen,
      // since the menu reads the switches and never the kind (ADR-033). A business of shops only has none of them on, so no group.
      id: 'wholesale',
      label: 'Wholesale',
      tour: 'nav-wholesale',
      links: [
        shopRow(me, 'Price lists', 'priceLists', ['priceLists', 'customerGroups']),
        shopRow(me, 'Credit to shops', 'customerAccounts', ['customerAccounts', 'customerAgeing']),
        shopRow(me, 'Quotations', 'quotations', ['quotations']),
        shopRow(me, 'Invoices and delivery notes', 'documents', ['documents']),
        capabilityOn(me, 'retail', UNITS) && shopRow(me, WORDS.packs, 'units', ['products']),
        shopRow(me, 'Purchase orders', 'purchaseOrders', ['purchaseOrders', 'goodsReceived', 'supplierBills']),
      ],
    },
    {
      id: 'lending',
      label: 'Lending',
      links: [
        showLending(me) && { to: '/staff/lending', label: 'Loans', icon: icons.calendar, exact: true, tour: 'nav-lending', section: 'lending' },
        showMembers(me) && { to: '/staff/lending/members', label: 'Members', icon: icons.people, tour: 'nav-members', section: 'lending' },
        !showMembers(me) && showCollateral(me) && { to: '/staff/lending/collateral', label: 'Collateral', icon: icons.lock, section: 'lending' },
        showSavings(me) && { to: '/staff/lending/savings', label: 'Savings', icon: icons.coins, section: 'lending' },
        showInvestments(me) && { to: '/staff/lending/investments', label: 'Investments', icon: icons.investments, section: 'lending' },
        showInsights(me) && { to: '/staff/insights', label: 'Insights', icon: icons.insights, tour: 'nav-insights', section: 'lending' },
        retail && lendingReports && { to: '/staff/reports', label: 'Loan reports', icon: icons.documents, section: 'lending' },
      ],
    },
    {
      id: 'business',
      label: 'Business',
      links: [
        retail
          ? { to: '/staff/retail', label: 'Reports', icon: icons.reports, exact: true, tour: 'nav-retail', section: 'shop' }
          : lendingReports && { to: '/staff/reports', label: 'Reports', icon: icons.reports },
        // The Controls page (issue #357, ADR-047, ADR-087): what deserves a second look, for holders of retail.controls.read.
        shopRow(me, 'Controls', 'controls', ['controls']),
        showUsers(me) && { to: '/staff/users', label: 'Staff', icon: icons.user, tour: 'nav-users' },
        showReceivePayments(me) && { to: '/staff/payments', label: 'Receive payments', icon: icons.receivePayments },
        showSms(me) && { to: '/staff/sms', label: 'SMS', icon: icons.phone },
        canConfigureAdvisor(me) && { to: '/staff/assistant', label: 'AI assistant', icon: icons.assistant },
        permissions.includes('core.settings.manage') && { to: '/staff/setup', label: 'Business set-up', icon: icons.settings },
      ],
    },
  ];
  return groups.map((g) => {
    const links = g.links.filter((l): l is MenuLink => l !== false);
    // Today carries the shop's tour anchor only while it holds a shop row (Sell or Orders), which the selling tour's step names.
    const tour = g.id === 'today' && links.some((l) => l.section === 'shop') ? 'retail-nav' : g.tour;
    return { ...g, tour, links };
  });
}

/**
 * The pages of the menu's footer. The menu draws Notifications; Help and feedback there is a button that opens the Help sheet, whose
 * Send feedback opens the Feedback page, so the More page lists Feedback itself and the More tab answers for its tour anchor.
 */
export const FOOT_LINKS: MenuLink[] = [
  { to: '/staff/notifications', label: 'Notifications', icon: icons.bell, tour: 'nav-notifications' },
  { to: '/staff/feedback', label: 'Feedback', icon: icons.chat, tour: 'nav-feedback' },
];

/**
 * Every page the menu opens, in menu order, then the footer's: the More page, the feature search and the tours read this flat
 * list, the shop and lending pages marked with their STAFF_SECTIONS id.
 */
export function staffAreas(me: Me, { sells }: { sells: boolean }): MenuLink[] {
  return [...staffMenu(me, { sells }).flatMap((g) => g.links), ...FOOT_LINKS];
}

/**
 * The open orders and the items low on stock, from the Home summary's own read (homeSummaryQuery: one key, gate and freshness),
 * so the menu, Home and the My stock tab share one request, held fresh for a minute between pages. Zero where the session may not
 * see a figure.
 */
export function useMenuCounts(me: Me): MenuCounts {
  const branch = useOptionalStaff()?.branch ?? null;
  const parts = homeParts(me, branch);
  const options = homeSummaryQuery(parts, branch);
  const home = useQuery({ ...options, enabled: options.enabled && (parts.lowStock || parts.orders), staleTime: 60_000 });
  return {
    orders: parts.orders ? (home.data?.open_orders ?? 0) : 0,
    low: parts.lowStock ? (home.data?.low_stock ?? 0) : 0,
  };
}

/** What the menu says under a name: Owner for the owner of the business, else the person's first role. */
export function roleOf(me: Me): string {
  return me.is_owner === true ? 'Owner' : (me.roles?.[0]?.role_name ?? '');
}
