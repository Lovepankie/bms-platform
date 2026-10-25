import { icons } from '../../components/icons';
import type { MenuLink } from '../../components/side-menu';

// The portal's areas, in the same side menu as the staff area's: a sidebar from 720px, a drawer on a phone (issue #455). Apart
// from route.tsx, so a test reads them without loading the portal (issue #483).
export const AREAS: MenuLink[] = [
  { to: '/platform', label: 'Applications', icon: icons.documents, exact: true },
  { to: '/platform/businesses', label: 'Businesses', icon: icons.building },
  { to: '/platform/plans', label: 'Plans and prices', icon: icons.priceLists },
  { to: '/platform/billing', label: 'Due for suspension', icon: icons.calendar },
  { to: '/platform/closures', label: 'Businesses closing', icon: icons.lock },
  { to: '/platform/no-owner', label: 'Businesses with no owner', icon: icons.people },
  { to: '/platform/outbox', label: 'Messages not sent', icon: icons.bell },
  { to: '/platform/feedback', label: 'Feedback', icon: icons.chat },
];
