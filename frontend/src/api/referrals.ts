import { api, problemOf } from './client';
import type { components } from './schema';

// The business's own referrals (issue #464, ADR-069; chapter 7 section 7.11.26): its referral code, the link to the public
// sign-up with the code filled in, and the businesses it referred with the commission earned, paid and due. The owner and the
// administrators read it (core.referrals.read). The app records commissions; it never pays anyone.

export type MyReferrals = components['schemas']['MyReferrals'];
export type MyReferral = components['schemas']['MyReferral'];
export type ReferralTotal = components['schemas']['ReferralTotal'];

export class ReferralsError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

export async function fetchMyReferrals(): Promise<MyReferrals> {
  const { data, error, response } = await api.GET('/api/v1/referrals');
  const status = response.status;
  if (error || !data) {
    const problem = problemOf(error);
    throw new ReferralsError(
      status === 403 ? 'Only the owner and the administrators of the business see its referrals.' : (problem.detail ?? 'Your referrals could not be loaded. Try again.'),
      status,
    );
  }
  return data;
}
