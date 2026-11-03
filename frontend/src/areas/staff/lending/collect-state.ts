import type { LoanSchedule } from '../../../api/lending';
import type { PaymentIntent, PaymentLinkText } from '../../../api/payments';
import { words } from './loan-state';

// Pure helpers of "Collect by mobile money or card" (issue #216, spec 3.2 step 7 and 3.8): what the status means in plain words, how
// often the screen asks, and what amount to offer first. The server decides every status; these only present it. The same words serve
// a savings deposit (issue #219) with the subject changed.

/** What is being collected for: a loan repayment or a savings deposit. */
export type CollectKind = 'loan' | 'savings';

/** The screen asks every 3 seconds for 2 minutes, then every 15 seconds: "still waiting, we will update this screen". */
export const FAST_POLL_MS = 3000;
export const SLOW_POLL_MS = 15000;
export const FAST_WINDOW_MS = 120000;

export function pollDelay(elapsedMs: number): number {
  return elapsedMs < FAST_WINDOW_MS ? FAST_POLL_MS : SLOW_POLL_MS;
}

/** Statuses after which nothing more will change by itself in the next minutes (the screen stops asking). */
const SETTLED = new Set(['booked', 'booked_unallocated', 'needs_attention', 'failed', 'cancelled', 'reversed_pending_review', 'reversed']);

export const isSettled = (status: string | undefined): boolean => !!status && SETTLED.has(status);

/** Whether the payment may still be cancelled by staff (nothing has been paid). */
export const isCancellable = (status: string | undefined): boolean => status === 'created' || status === 'pending' || status === 'expired';

const REFUSALS: Record<string, string> = {
  invalid_status_transition: 'the loan is closed or no longer takes repayments',
  value_date_in_future: 'the payment date is in the future',
  payment_method_unmapped: 'the business has not mapped mobile money to an account',
  currency_mismatch: 'the currency differs from the account',
  subject_not_payable: 'the account cannot take it',
  duplicate_reference: 'the payment reference was already used for a different payment',
  period_closed: 'the accounting period is closed',
};

const SAVINGS_REFUSALS: Record<string, string> = {
  invalid_status_transition: 'the account no longer takes deposits',
  account_closed: 'the account is closed',
  account_frozen: 'the account is frozen',
  account_dormant: 'the account is dormant',
  below_minimum_opening: "the first deposit is below the product's minimum opening balance",
  before_opening: 'the payment date is before the account was opened',
  value_date_closed: 'the end of day has already run for the payment date',
};

export function refusalWords(code: string | undefined, kind: CollectKind = 'loan'): string {
  const fallback = kind === 'loan' ? 'the loan could not take it' : 'the account could not take it';
  if (!code) return fallback;
  if (kind === 'savings' && SAVINGS_REFUSALS[code]) return SAVINGS_REFUSALS[code];
  if (kind === 'loan' && code === 'currency_mismatch') return 'the currency differs from the loan';
  if (kind === 'loan' && code === 'subject_not_payable') return 'the loan cannot take it';
  return REFUSALS[code] ?? words(code).toLowerCase();
}

/** The one sentence the staff screen shows for an intent's status. */
export function statusWords(i: Pick<PaymentIntent, 'status' | 'refusal_code' | 'failure_reason'>, kind: CollectKind = 'loan'): string {
  const loan = kind === 'loan';
  switch (i.status) {
    case 'created':
      return 'Getting the payment link ready.';
    case 'pending':
      return 'Waiting for the payer to pay. Ask them to open the link or scan the code.';
    case 'expired':
      return 'The link has timed out. If the payer has already paid, it will still be recorded here.';
    case 'paid':
      return loan ? 'Paid. Recording the repayment on the loan.' : 'Paid. Recording the deposit on the account.';
    case 'booked':
      return loan ? 'Paid and recorded on the loan.' : 'Paid and recorded on the account.';
    case 'booked_unallocated':
      return `Paid, but it could not be put on the ${loan ? 'loan' : 'account'} because ${refusalWords(i.refusal_code, kind)}. The money is held as an unallocated receipt for a cashier to deal with.`;
    case 'needs_attention':
      return `Paid, but the ${loan ? 'repayment' : 'deposit'} could not be recorded yet. Someone needs to look at it; the money is safe with the business's own account.`;
    case 'failed':
      return 'The payment did not go through. Nothing was taken. Start a new payment.';
    case 'cancelled':
      return 'This payment was cancelled.';
    case 'reversed_pending_review':
      return `The payment provider has reversed this payment. A review of the ${loan ? 'repayment' : 'deposit'} is waiting.`;
    case 'reversed':
      return 'This payment was reversed.';
    default:
      return words(i.status);
  }
}

const FLAGS: Record<string, string> = {
  paid_less: 'The payer paid less than was asked. What was paid is recorded.',
  paid_more: 'The payer paid more than was asked. What was paid is recorded.',
  late: 'The payment arrived after the link timed out.',
};

export const flagWords = (flags: string[] | undefined): string[] => (flags ?? []).map((f) => FLAGS[f]).filter((t): t is string => !!t);

/**
 * The sentence under the link once it was sent by text message (issue #772): what the outbox says, never more. A text is "sent" only
 * when the provider took it; one that failed or expired is said plainly, and `renew` is true so the screen offers a new link.
 * JUSTIFICATION-A3: a new pure helper beside its siblings, so the words are tested without a screen.
 */
export function linkTextWords(
  text: Pick<PaymentLinkText, 'already_sent' | 'text_status'>,
  phone: string | undefined,
): { words: string; renew: boolean; pending: boolean } {
  const to = phone ?? 'the payer';
  switch (text.text_status) {
    case 'sent':
      return { words: `The link was sent by text message to ${to}.`, renew: false, pending: false };
    case 'failed':
      return { words: 'The text message with the link did not reach the payer. Make a new payment link to send it again.', renew: true, pending: false };
    case 'expired':
      return { words: 'The text message with the link ran out of time before it went. Make a new payment link to send it again.', renew: true, pending: false };
    // It may have arrived: no new link is pushed, so a link the payer may hold is not cancelled.
    case 'unconfirmed':
      return { words: 'We could not confirm that the text message reached the payer. Ask the payer, or copy the link and send it another way.', renew: false, pending: false };
    default:
      return {
        words: text.already_sent
          ? `The link was queued earlier to go by text message to ${to}. It has not gone yet.`
          : `The link is queued to go by text message to ${to}.`,
        renew: false,
        pending: true,
      };
  }
}

/**
 * The amount to offer first: the arrears if any, otherwise what is left on the first instalment not yet paid, never more than the
 * loan owes (the server refuses more than the payoff). Minor units, or null when there is nothing sensible to offer.
 */
export function suggestedAmount(
  balances: { arrears_minor?: number; total_outstanding_minor?: number } | undefined,
  schedule: Pick<LoanSchedule, 'items'> | undefined,
): number | null {
  const owed = balances?.total_outstanding_minor ?? 0;
  if (owed <= 0) return null;
  const arrears = balances?.arrears_minor ?? 0;
  const next = (schedule?.items ?? []).find((r) => (r.outstanding_minor ?? 0) > 0)?.outstanding_minor ?? 0;
  const pick = arrears > 0 ? arrears : next > 0 ? next : owed;
  return Math.min(pick, owed);
}
