import { formatMinor } from '../../../components/money';
import { formatDay } from '../../platform/operator-state';
import type { BillingNotice, BillingView } from '../../../api/billing';

// Plain words for the Plan and billing page and the overdue banner (issue #480, ADR-075). Pure, so the tests read them as the
// Owner does.

export const STATE_LABELS: Record<string, string> = {
  trial: 'Free month',
  active: 'Paid',
  in_grace: 'Overdue, in the grace period',
  overdue: 'Overdue',
  suspended: 'Suspended',
  cancelled: 'Cancelled',
  pilot: 'Complimentary',
};

export const METHOD_LABELS: Record<string, string> = {
  mtn_momo: 'MTN Mobile Money',
  airtel_money: 'Airtel Money',
  bank_transfer: 'Bank transfer',
  other: 'Other',
};

/** The badge class of a state: green while it works as paid, amber in grace, red past it or stopped. */
export function stateBadge(state: string): string {
  if (state === 'active' || state === 'pilot' || state === 'trial') return 'badge badge-success';
  if (state === 'in_grace') return 'badge badge-warning';
  return 'badge badge-danger';
}

/** One sentence on where the subscription stands. */
export function stateSentence(v: BillingView): string {
  switch (v.state) {
    case 'trial':
      return v.trial_ends_on ? `Your free month ends on ${formatDay(v.trial_ends_on)}.` : 'Your free month starts when your staff first sign in.';
    case 'active':
      return v.paid_until ? `Paid until ${formatDay(v.paid_until)}.` : 'Your subscription is active.';
    case 'in_grace':
      return `Your subscription is overdue since ${formatDay(v.overdue_since)}. Pay by ${formatDay(v.pay_by)} to keep using the app.`;
    case 'overdue':
      return v.overdue_since
        ? `Your subscription is overdue since ${formatDay(v.overdue_since)} and the grace period has ended. Pay now to keep using the app.`
        : 'Your subscription is overdue. Pay now to keep using the app.';
    case 'suspended':
      return 'Your business is suspended: you can read and export, but not record anything new. Pay, then ask Rincol Tech to resume it.';
    case 'cancelled':
      return 'Your subscription is cancelled: you can read and export, but not record anything new.';
    case 'pilot':
      return 'Your business uses BMS free of charge until a paid start is agreed.';
    default:
      return '';
  }
}

/** The plan's price in words, or null when no price was entered. */
export function priceSentence(v: BillingView): string | null {
  if (v.monthly_minor == null) return null;
  const currency = v.currency ?? 'UGX';
  const month = formatMinor(v.monthly_minor, currency);
  if (v.annual_minor == null || v.annual_months == null) return `${month} a month.`;
  const year = formatMinor(v.annual_minor, currency);
  return v.annual_months < 12
    ? `${month} a month, or ${year} for a year (${v.annual_months} months charged).`
    : `${month} a month, or ${year} for a year.`;
}

/** The banner's words, or null when there is nothing to show. */
export function bannerText(n: BillingNotice | undefined): string | null {
  if (!n || n.state === 'none') return null;
  if (n.state === 'in_grace' && n.overdue_since && n.pay_by) {
    return `Your subscription is overdue since ${formatDay(n.overdue_since)}; pay by ${formatDay(n.pay_by)} to keep using the app.`;
  }
  if (n.overdue_since) {
    return `Your subscription is overdue since ${formatDay(n.overdue_since)} and the grace period has ended; pay now to keep using the app.`;
  }
  return 'Your subscription is overdue; pay now to keep using the app.';
}
