import { api, problemOf } from './client';
import { FormError, postFile } from './lending-forms';
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
  receipt_not_open: 'This payment was already dealt with by someone else. Reload the list.',
  payment_not_held: 'The payment provider has changed this payment, so it can no longer be allocated or refunded here. Reload the list.',
  subject_not_found: 'That loan or account was not found. Check the identifier.',
  approval_already_pending: 'Someone has already asked for this payment. It is waiting for a second person.',
  payment_method_unmapped: 'That way of paying has no account set up. Choose another, or ask your accountant to set it up under payment methods.',
  invalid_status_transition: 'That loan cannot take a payment now.',
  period_closed: 'Today\'s accounting period is closed.',
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

// The unallocated receipts queue (extras slice A5, issue #224; chapter 7 section 7.11.10, FR-PAY-04): money the provider confirmed and the
// loan or account would not take. Asking to put it somewhere or to send it back never moves money; a second person decides.
export type UnallocatedReceipt = components['schemas']['UnallocatedReceipt'];
export type UnallocatedReceiptPage = components['schemas']['UnallocatedReceiptPage'];
export type ReceiptStatusFilter = 'open' | 'allocated' | 'refunded' | 'reversed' | 'all';
export type ReceiptOutcome = components['schemas']['ReceiptActionOutcome'];

export const receipts = {
  async list(status: ReceiptStatusFilter, cursor?: string): Promise<UnallocatedReceiptPage> {
    return unwrap(
      await api.GET('/api/v1/payments/unallocated', { params: { query: { status, ...(cursor ? { cursor } : {}) } } }),
      'Could not load the unallocated payments.',
    );
  },

  /** Asks for the receipt's money to be put on a loan or a savings account; a checker decides. The same key never asks twice. */
  async allocate(id: string, purpose: string, subjectId: string, reason: string, idempotencyKey: string): Promise<ReceiptOutcome> {
    return unwrap(
      await api.POST('/api/v1/payments/unallocated/{receipt_id}/allocate', {
        params: { path: { receipt_id: id }, header: { 'Idempotency-Key': idempotencyKey } },
        body: { purpose, subject_id: subjectId, reason },
      }),
      'Could not ask for the payment to be put there.',
    );
  },

  /** Records that the money was (or will be) sent back; a checker approves the record. */
  async refund(id: string, methodKey: string, reference: string, reason: string, idempotencyKey: string): Promise<ReceiptOutcome> {
    return unwrap(
      await api.POST('/api/v1/payments/unallocated/{receipt_id}/refund', {
        params: { path: { receipt_id: id }, header: { 'Idempotency-Key': idempotencyKey } },
        body: { method_key: methodKey, reference, reason },
      }),
      'Could not record the refund.',
    );
  },
};

// The merchant statement and the daily reconciliation (extras slice A5 part 2, issue #224, task #317; FR-PAY-05, FR-PAY-11). The statement is the
// operator's CSV from the provider's dashboard; the report lists what differs between it, this system and the receipts keyed by hand.
export type PaymentStatement = components['schemas']['PaymentStatement'];
export type PaymentStatementDetail = components['schemas']['PaymentStatementDetail'];
export type PaymentStatementRow = components['schemas']['PaymentStatementRow'];
export type PaymentReconciliation = components['schemas']['PaymentReconciliation'];
export type ReconciliationFinding = components['schemas']['PaymentReconciliationFinding'];

export const statements = {
  async list(): Promise<PaymentStatement[]> {
    return unwrap(await api.GET('/api/v1/payments/statements'), 'Could not load the statements.').items ?? [];
  },

  async get(id: string): Promise<PaymentStatementDetail> {
    return unwrap(await api.GET('/api/v1/payments/statements/{statement_id}', { params: { path: { statement_id: id } } }), 'Could not load the statement.');
  },

  /** Uploads the CSV. A file with an unreadable line is refused whole: the FormError carries a message per line. */
  async upload(file: Blob, fileName: string, covered?: { from: string; to: string }): Promise<PaymentStatementDetail> {
    return postFile<PaymentStatementDetail>('/api/v1/payments/statements', file, fileName, covered ? { covered_from: covered.from, covered_to: covered.to } : {});
  },

  /** Marks a line no order of ours has as money for something outside this system; it is no longer reported. */
  async markOutside(statementId: string, rowNo: number, note: string): Promise<PaymentStatementRow> {
    return unwrap(
      await api.POST('/api/v1/payments/statements/{statement_id}/rows/{row_no}/outside-system', {
        params: { path: { statement_id: statementId, row_no: rowNo } },
        body: { note },
      }),
      'Could not mark the line.',
    );
  },
};

export const reconciliation = {
  async latest(): Promise<PaymentReconciliation> {
    return unwrap(await api.GET('/api/v1/payments/reconciliation'), 'Could not load the reconciliation.');
  },

  async run(): Promise<PaymentReconciliation> {
    return unwrap(await api.POST('/api/v1/payments/reconciliation/run'), 'Could not run the reconciliation.');
  },
};

export { FormError };

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
