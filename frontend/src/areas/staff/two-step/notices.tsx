import { Link } from '@tanstack/react-router';
import { useState } from 'react';
import type { Me } from '../../../api/client';
import { hideRecommendation, mustSetUp, recommendationHidden, recommends, reminderWords } from './state';

// The two notices of the two-step policy (issue #348; ADR-046 decision 6), drawn by the staff layout. A person the policy covers who has
// not set it up sees a reminder with the date on every staff screen until they do. An administrator sees a recommendation while the
// policy is off, on the home and set-up screens only, and can put it away for 14 days ("Not now", kept in this browser only).

const ON_SETUP_PAGES = ['/staff', '/staff/', '/staff/setup'];

export function TwoStepNotices({ me, pathname }: { me: Me; pathname: string }) {
  const [hidden, setHidden] = useState(() => recommendationHidden(me.user_id, Date.now()));
  const reminder = reminderWords(me);
  const ownPage = pathname.startsWith('/staff/two-step');
  return (
    <>
      {reminder && !ownPage && (
        <p role="status" className="alert alert-warning" data-tour="twostep-reminder">
          {reminder}{' '}
          {!mustSetUp(me) && <Link to={'/staff/two-step' as never}>Set it up now</Link>}
        </p>
      )}
      {recommends(me) && !hidden && ON_SETUP_PAGES.includes(pathname) && (
        <div role="status" className="alert alert-info stack" data-tour="twostep-recommendation">
          <p>
            <strong>Protect your business.</strong> Ask your staff to use two-step sign-in: a code from their phone as well as the password. Then
            nobody can sign in as them with a stolen password. It is optional until you choose.
          </p>
          <p className="cluster">
            <Link to={'/staff/setup/two-step' as never} className="btn-sm">
              Choose who uses it
            </Link>
            <button
              type="button"
              className="btn-ghost btn-sm"
              onClick={() => {
                hideRecommendation(me.user_id, Date.now());
                setHidden(true);
              }}
            >
              Not now
            </button>
          </p>
        </div>
      )}
    </>
  );
}
