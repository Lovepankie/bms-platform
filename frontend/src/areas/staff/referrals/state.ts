import type { Me } from '../../../api/client';
import type { ReferralTotal } from '../../../api/referrals';
import { formatMinor } from '../../../components/money';

// Who sees the Referrals page and the plain words it uses (issue #464, ADR-069). The API is the authority; this only decides what
// Business set-up offers, from the /me permissions.

export const REFERRALS_READ = 'core.referrals.read';

export function showReferrals(me: Pick<Me, 'permissions'>): boolean {
  return (me.permissions ?? []).includes(REFERRALS_READ);
}

export const STATUS_WORDS: Record<string, string> = {
  applied: 'Applied, not started yet',
  active: 'Using BMS',
  paying: 'Paying',
  suspended: 'Suspended',
  cancelled: 'Cancelled',
  ended: 'Moved to another referrer',
};

export function statusWords(status: string | undefined): string {
  return (status && STATUS_WORDS[status]) || status || '';
}

/** The WhatsApp message with the link already typed; WhatsApp asks who to send it to. */
export function whatsappShare(business: string, link: string): string {
  const text = `${business} uses BMS to run the business. Sign up with our link and tell them we sent you: ${link}`;
  return `https://wa.me/?text=${encodeURIComponent(text)}`;
}

/** One line per currency, for example "UGX 1,500", or "Nothing yet" when there is nothing. */
export function moneyWords(totals: ReferralTotal[] | undefined, pick: 'earned_minor' | 'paid_minor' | 'due_minor'): string {
  const lines = (totals ?? []).filter((t) => t.currency).map((t) => formatMinor(t[pick] ?? 0, t.currency as string));
  return lines.length > 0 ? lines.join(', ') : 'Nothing yet';
}
