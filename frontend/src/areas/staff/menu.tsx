import type { Me } from '../../api/client';
import { icons } from '../../components/icons';
import type { MenuLink, MenuSection } from '../../components/side-menu';
import { canConfigureAdvisor, showInsights } from './insights/permissions';
import { showInvestments } from './lending/investment-state';
import { showCollateral, showMembers } from './lending/member-permissions';
import { showLending } from './lending/permissions';
import { showSavings } from './lending/savings-permissions';
import { showReceivePayments } from './payments/state';
import { showReports } from './reports/permissions';
import { RetailMenuGroup, shortcutsFor } from './retail/nav';
import { showRetail } from './retail/permissions';
import { showSms } from './sms/state';
import { showUsers } from './users/state';

// The staff side menu's items and headings (issue #455, #471), apart from the layout in route.tsx so a test reads them without
// loading the staff area (issue #483).

/**
 * The two headed parts of the menu for a business with both modules (issue #471). A business with one of them gets no heading: the
 * side menu draws sections only when the links name two.
 */
export const STAFF_SECTIONS: MenuSection[] = [
  { id: 'shop', label: 'Shop', icon: icons.shop, tours: ['retail-nav'] },
  { id: 'lending', label: 'Lending', icon: icons.lending },
];

/**
 * The areas the session may open, in menu order; Retail carries its shortcuts as a group (`sells` from useSells decides Sale). The
 * sidebar and the drawer both list these, the lending and retail areas marked with their STAFF_SECTIONS id.
 */
export function staffAreas(me: Me, { sells }: { sells: boolean }): MenuLink[] {
  const permissions = me.permissions ?? [];
  const shortcuts = showRetail(me) ? shortcutsFor(me, { sells }) : [];
  const areas: (MenuLink | false)[] = [
    { to: '/staff', label: 'Home', icon: icons.home, exact: true },
    permissions.includes('core.approvals.read') && { to: '/staff/approvals', label: 'Approvals', icon: icons.approve, tour: 'nav-approvals' },
    showLending(me) && { to: '/staff/lending', label: 'Loans', icon: icons.calendar, exact: true, tour: 'nav-lending', section: 'lending' },
    showMembers(me) && { to: '/staff/lending/members', label: 'Members', icon: icons.people, tour: 'nav-members', section: 'lending' },
    !showMembers(me) && showCollateral(me) && { to: '/staff/lending/collateral', label: 'Collateral', icon: icons.lock, section: 'lending' },
    showSavings(me) && { to: '/staff/lending/savings', label: 'Savings', icon: icons.coins, section: 'lending' },
    showInvestments(me) && { to: '/staff/lending/investments', label: 'Investments', icon: icons.investments, section: 'lending' },
    showInsights(me) && { to: '/staff/insights', label: 'Insights', icon: icons.insights, tour: 'nav-insights', section: 'lending' },
    canConfigureAdvisor(me) && { to: '/staff/assistant', label: 'AI assistant', icon: icons.assistant, section: 'lending' },
    showReports(me) && { to: '/staff/reports', label: 'Reports', icon: icons.reports },
    showRetail(me) && {
      to: '/staff/retail',
      label: 'Retail',
      icon: icons.retail,
      tour: 'nav-retail',
      group: <RetailMenuGroup shortcuts={shortcuts} />,
      groupPaths: shortcuts.map((s) => s.path),
      section: 'shop',
    },
    showSms(me) && { to: '/staff/sms', label: 'SMS', icon: icons.phone },
    showReceivePayments(me) && { to: '/staff/payments', label: 'Receive payments', icon: icons.receivePayments },
    showUsers(me) && { to: '/staff/users', label: 'Users', icon: icons.user, tour: 'nav-users' },
    { to: '/staff/notifications', label: 'Notifications', icon: icons.bell, tour: 'nav-notifications' },
    { to: '/staff/feedback', label: 'Feedback', icon: icons.chat, tour: 'nav-feedback' },
    permissions.includes('core.settings.manage') && { to: '/staff/setup', label: 'Business set-up', icon: icons.settings },
  ];
  return areas.filter((a): a is MenuLink => a !== false);
}
