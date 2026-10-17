import type { ReactNode } from 'react';
import { Link } from '@tanstack/react-router';
import { useStaff } from '../context';
import { showCollateral, showMembers } from './member-permissions';

// The frame of the member and collateral screens (issue #280): the page heading, the access check and the small menu
// between Members and Collateral. Same page classes as the loan screens.

export function MembersGate({ title, children, tourId }: { title: string; children: ReactNode; tourId?: string }) {
  const { me } = useStaff();
  return (
    <main className="rt ln" data-tour={tourId}>
      <h1>{title}</h1>
      {showMembers(me) ? children : <p className="empty-state">You do not have access to this page.</p>}
    </main>
  );
}

export function CollateralGate({ title, children, tourId }: { title: string; children: ReactNode; tourId?: string }) {
  const { me } = useStaff();
  return (
    <main className="rt ln" data-tour={tourId}>
      <h1>{title}</h1>
      {showCollateral(me) ? children : <p className="empty-state">You do not have access to this page.</p>}
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
