import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { useStaff } from '../context';
import { canUse, type RetailScreen } from './permissions';
import { RowChevron } from '../../../app/ui/ListRow';

// A link to another retail screen, drawn only for a session that may open it (issue #336). `to` is a route of the retail area,
// `search` the query string some screens read, `tour` the optional data-tour anchor of a page tour step. A related screen is a
// row with a chevron; an `action` (Record an expense) is a button in the brand fill (issue #526): never an underlined word.
export function ScreenLink({ screen, to, search, children, label, tour, action = false }: { screen: RetailScreen; to: string; search?: Record<string, string>; children: ReactNode; label?: string; tour?: string; action?: boolean }) {
  const { me } = useStaff();
  if (!canUse(me, screen)) return null;
  return (
    <Link className={action ? 'btn btn-primary' : 'link-row'} to={to as never} {...(search ? { search: search as never } : {})} aria-label={label} data-tour={tour}>
      {children}
      {!action && <RowChevron />}
    </Link>
  );
}
