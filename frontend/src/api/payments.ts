import { api, problemOf } from './client';
import type { components } from './schema';

// The "Receive payments" client (extras slice A7, issue #233; chapter 7 section 7.11.10; ADR-035 amendment of 2026-10-18): the
// business's own payment provider account. The secret goes in on save and never comes back: there is no field for it in any answer.
// The generated schema marks every field optional, as for the rest of the API; these are the shapes the server always sends
// (core/payments/internal/MerchantAccountService.java).

export type MerchantStatus = 'not_connected' | 'verified' | 'failed';

export interface MerchantAccount {
  status: MerchantStatus;
  /** Credentials are saved, but not yet tested */
  credentials_saved: boolean;
  /** The last four characters of the consumer key, or null (given to those who may change the account only) */
  key_hint: string | null;
  last_checked_at: string | null;
  /** invalid_credentials or registration_refused, when the last test failed */
  failure_reason: string | null;
  /** The host can make the calls a test needs */
  host_ready: boolean;
}

// Keeps the generated types honest: a change to the contract that drops one of these breaks this line, not a screen.
export type GeneratedMerchantAccount = components['schemas']['MerchantAccount'];

export class PaymentsError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

const MESSAGES: Record<string, string | undefined> = {
  account_not_saved: 'Enter the consumer key and secret first, then test the connection.',
  account_changed: 'The key or secret was changed while the test ran. Test again.',
  gateway_not_configured: 'Payments by mobile money and card are not set up on this system yet. Ask the platform operator.',
  provider_unreachable: 'Pesapal could not be reached just now. Nothing was changed. Try again in a moment.',
  provider_busy: 'Pesapal asked us to wait. Try again in a minute.',
  payments_in_flight:
    'Some payments are still being confirmed with this account. Wait until they finish, or keep the same consumer key and change only the secret.',
  permission_denied: 'Only the business administrator can change this.',
  invalid_code: 'That code did not work. Open your authenticator app and type the current code.',
  mfa_not_enrolled: 'Turn on two-step sign-in first: this change asks for a code from your authenticator app.',
  merchant_account_in_use: 'This merchant account is already connected elsewhere. Use your own Pesapal account.',
  test_too_soon: 'A test was made a moment ago. Wait half a minute before testing again.',
  collections_disabled:
    'Collecting by mobile money or card is not switched on for this business. A business administrator turns it on under Receive payments.',
  merchant_account_not_verified:
    "This business has not connected and tested its own payment account yet, so nothing can be collected. A business administrator does that under Receive payments. The money always goes to the business's own account.",
  exceeds_payoff: 'That is more than the loan still owes. Ask for the amount to be paid, up to the full payoff.',
  subject_not_payable: 'This account cannot take a payment now. Reload the page to see its status.',
  below_minimum_opening: "A first deposit must be at least the product's minimum opening balance.",
  daily_ceiling_exceeded: "The business's daily limit for collections would be exceeded. Try a smaller amount or try again tomorrow.",
  currency_not_supported: 'Collections are offered in Uganda shillings only.',
  unknown_purpose: 'This kind of payment is not available.',
  not_cancellable: 'This payment can no longer be cancelled. It may have been paid: check its status.',
  cancel_failed: 'The payment could not be cancelled. It may have been paid: check its status.',
};

function fail(error: unknown, status: number, fallback: string): never {
  const problem = problemOf(error);
  const first = (problem.errors ?? []).map((e) => e.message).find((m) => m);
  const message = (problem.code && MESSAGES[problem.code]) || first || problem.detail || fallback;
  throw new PaymentsError(message, status, problem.code);
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    fail(result.error, result.response.status, fallback);
  }
  return result.data;
}

// A collection by mobile money or card started by staff (extras slice A3, issue #216; chapter 7 section 7.11.10): the intent, its
// status and the link the payer opens. Money goes only into the business's own merchant account; a business without a verified one is
// refused (merchant_account_not_verified) before any order is made.
export type PaymentIntent = components['schemas']['PaymentIntent'];

/** The purpose of a loan repayment collected through the gateway; the subject is the loan. */
export const LOAN_REPAYMENT_PURPOSE = 'lending.loan_repayment';

/** The purpose of a savings deposit collected through the gateway (issue #219); the subject is the savings account. */
export const SAVINGS_DEPOSIT_PURPOSE = 'lending.savings_deposit';

export const payments = {
  /** Starts a collection for a loan. The same key returns the same intent, so a retry never makes a second link. */
  async startLoanRepayment(loanId: string, amountMinor: number, payerPhone: string, idempotencyKey: string): Promise<PaymentIntent> {
    return unwrap(
      await api.POST('/api/v1/payments/intents', {
        params: { header: { 'Idempotency-Key': idempotencyKey } },
        body: { purpose: LOAN_REPAYMENT_PURPOSE, subject_id: loanId, amount_minor: amountMinor, payer_phone: payerPhone },
      }),
      'Could not start the payment.',
    );
  },

  /** Starts a collection for a savings account. The same key returns the same intent, so a retry never makes a second link. */
  async startSavingsDeposit(accountId: string, amountMinor: number, payerPhone: string, idempotencyKey: string): Promise<PaymentIntent> {
    return unwrap(
      await api.POST('/api/v1/payments/intents', {
        params: { header: { 'Idempotency-Key': idempotencyKey } },
        body: { purpose: SAVINGS_DEPOSIT_PURPOSE, subject_id: accountId, amount_minor: amountMinor, payer_phone: payerPhone },
      }),
      'Could not start the payment.',
    );
  },

  async intent(id: string): Promise<PaymentIntent> {
    return unwrap(await api.GET('/api/v1/payments/intents/{intent_id}', { params: { path: { intent_id: id } } }), 'Could not load the payment.');
  },

  async cancelIntent(id: string): Promise<PaymentIntent> {
    return unwrap(await api.POST('/api/v1/payments/intents/{intent_id}/cancel', { params: { path: { intent_id: id } } }), 'Could not cancel the payment.');
  },

  async account(): Promise<MerchantAccount> {
    return unwrap(await api.GET('/api/v1/payments/merchant-account'), 'Could not load your payment account.') as MerchantAccount;
  },

  /** Saves the key and secret. No call is made to Pesapal; test the connection next. */
  async save(consumerKey: string, consumerSecret: string, totpCode: string): Promise<MerchantAccount> {
    return unwrap(
      await api.PUT('/api/v1/payments/merchant-account', {
        body: { consumer_key: consumerKey, consumer_secret: consumerSecret, totp_code: totpCode },
      }),
      'Could not save the key and secret.',
    ) as MerchantAccount;
  },

  async test(): Promise<MerchantAccount> {
    return unwrap(await api.POST('/api/v1/payments/merchant-account/test'), 'Could not test the connection.') as MerchantAccount;
  },

  async remove(totpCode: string): Promise<MerchantAccount> {
    return unwrap(
      await api.DELETE('/api/v1/payments/merchant-account', { body: { totp_code: totpCode } }),
      'Could not remove the account.',
    ) as MerchantAccount;
  },
};
