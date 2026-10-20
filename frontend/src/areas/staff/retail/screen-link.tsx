import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { useStaff } from '../context';
import { canUse, type RetailScreen } from './permissions';

// A link to another retail screen, drawn only for a session that may open it (issue #336). `to` is a route of the retail area,
// `search` the query string some screens read, `tour` the optional data-tour anchor of a page tour step.
export function ScreenLink({ screen, to, search, children, label, tour }: { screen: RetailScreen; to: string; search?: Record<string, string>; children: ReactNode; label?: string; tour?: string }) {
  const { me } = useStaff();
  if (!canUse(me, screen)) return null;
  return (
    <Link className="btn-sm" to={to as never} {...(search ? { search: search as never } : {})} aria-label={label} data-tour={tour}>{children}</Link>
  );
}
