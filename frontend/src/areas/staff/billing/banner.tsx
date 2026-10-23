import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { billingNotice } from '../../../api/billing';
import { bannerText } from './billing-state';

// The overdue banner (issue #480, ADR-075, FR-SUB-09): every signed-in staff user of an overdue business sees it, since the
// business keeps working through the grace period; those who may open Plan and billing get a link to it. Nothing when the
// notice cannot be read: a banner is never worth an error on every page. It is read again whenever the window comes back
// into focus, so a payment the operator recorded clears it without waiting out the 15 minutes.

export function BillingBanner({ canOpenBilling }: { canOpenBilling: boolean }) {
  const notice = useQuery({
    queryKey: ['billing-notice'],
    queryFn: billingNotice,
    staleTime: 15 * 60 * 1000,
    refetchOnWindowFocus: 'always',
    retry: false,
  });
  const text = bannerText(notice.data);
  if (!text) return null;
  return (
    <p role="status" className={notice.data?.state === 'overdue' ? 'alert alert-danger' : 'alert alert-warning'}>
      {text}
      {canOpenBilling && (
        <>
          {' '}
          <Link to={'/staff/setup/billing' as never}>How to pay</Link>
        </>
      )}
    </p>
  );
}
