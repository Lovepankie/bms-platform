import type { ReactNode } from 'react';
import { Link } from '@tanstack/react-router';
import { useOptionalStaff, useStaff } from '../context';
import { showCollateral, showMembers } from './member-permissions';
import { NoAccess } from '../../../app/ui/EmptyState';

// The frame of the member and collateral screens (issue #280): the page heading, the access check and the small menu
// between Members and Collateral. Same page classes as the loan screens.

/** The member's name as a link to the member, only for a session that may read members (otherwise the name as plain text). */
export function MemberLink({ memberId, children }: { memberId: string; children: ReactNode }) {
  const session = useOptionalStaff();
  if (!session || !showMembers(session.me)) return <>{children}</>;
  return <Link to="/staff/lending/members/$memberId" params={{ memberId }}>{children}</Link>;
}

export function MembersGate({ title, children, tourId }: { title: string; children: ReactNode; tourId?: string }) {
  const { me } = useStaff();
  return (
    <main className="rt ln" data-tour={tourId}>
      <h1>{title}</h1>
      {showMembers(me) ? children : <NoAccess />}
    </main>
  );
}

export function CollateralGate({ title, children, tourId }: { title: string; children: ReactNode; tourId?: string }) {
  const { me } = useStaff();
  return (
    <main className="rt ln" data-tour={tourId}>
      <h1>{title}</h1>
      {showCollateral(me) ? children : <NoAccess />}
    </main>
  );
}

/** Members and Collateral, as two 44px links above the list pages. */
export function CustomerNav() {
  const { me } = useStaff();
  return (
    <nav className="sv-nav" aria-label="Customers" data-tour="customers-nav">
      {showMembers(me) && <Link to="/staff/lending/members" activeOptions={{ exact: false }}>Members</Link>}
      {showCollateral(me) && <Link to="/staff/lending/collateral" activeOptions={{ exact: false }}>Collateral</Link>}
    </nav>
  );
}
