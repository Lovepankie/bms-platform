import type { Me } from '../../../api/client';
import type { MerchantAccount } from '../../../api/payments';

// Who sees the "Receive payments" screen and the plain words it uses (extras slice A7, issue #233). The API is the authority; this
// only decides what the menu and the page offer, from the /me permissions.

export const SETTINGS_READ = 'core.settings.read';
/** Changing where repayments are paid: the business administrator's alone (the API checks it, and asks for a code). */
export const ACCOUNT_MANAGE = 'core.payment_account.manage';

const held = (me: Pick<Me, 'permissions'>): string[] => me.permissions ?? [];

/** The page and its menu entry: a business administrator reads and changes it; a settings reader sees the status only. */
export function showReceivePayments(me: Pick<Me, 'permissions'>): boolean {
  return held(me).includes(ACCOUNT_MANAGE) || held(me).includes(SETTINGS_READ);
}

export function canChangePaymentAccount(me: Pick<Me, 'permissions'>): boolean {
  return held(me).includes(ACCOUNT_MANAGE);
}

export const STATUS_WORDS: Record<MerchantAccount['status'], string> = {
  not_connected: 'Not connected',
  verified: 'Connected and working',
  failed: 'Not working',
};

const FAILURE_WORDS: Record<string, string> = {
  account_not_allowed: 'This merchant account cannot be used for your business. Use your own Pesapal account.',
  invalid_credentials: 'Pesapal did not accept this consumer key and secret. Check them in your Pesapal account and paste them again.',
  registration_refused: 'Pesapal accepted the key and secret but refused the address it should tell us about payments on. Test again in a few minutes.',
};

export function failureWords(reason: string | null): string {
  return (reason && FAILURE_WORDS[reason]) || 'The last test did not pass. Check the key and secret and test again.';
}

/** The next step in one sentence, from where the account stands. */
export function nextStep(account: MerchantAccount): string {
  if (!account.host_ready) return 'Payments by mobile money and card are not set up on this system yet. Ask the platform operator.';
  if (account.status === 'verified') return 'Payments from your members go straight into your own Pesapal account.';
  if (account.status === 'failed') return failureWords(account.failure_reason);
  if (account.credentials_saved) return 'Saved. Now test the connection to turn collecting on.';
  return 'Enter the consumer key and secret from your own Pesapal account.';
}

/** The six digits of the authenticator app. */
export const CODE = /^\d{6}$/;

export const codeProblem = (value: string): string | null => (CODE.test(value.trim()) ? null : 'Type the 6 digits from your authenticator app.');

/** A consumer key or secret as Pesapal shows it: no spaces, 8 to 256 characters. */
export const CREDENTIAL = /^[\x21-\x7E]{8,256}$/;

export function credentialProblem(value: string): string | null {
  return CREDENTIAL.test(value.trim()) ? null : 'Paste it exactly as Pesapal shows it, with no spaces.';
}

// The unallocated payments screen (extras slice A5, issue #224). The API is the authority; this only decides what the menu and the
// page offer, from the /me permissions.

export const PAYMENTS_READ = 'core.payments.read';
/** Asking to put a payment on a loan or an account, or to record that it went back. */
export const PAYMENTS_ALLOCATE = 'core.payments.allocate';
/** Deciding such a request (done in the approvals inbox). */
export const PAYMENTS_ALLOCATE_APPROVE = 'core.payments.allocate_approve';

export const showUnallocated = (me: Pick<Me, 'permissions'>): boolean => held(me).includes(PAYMENTS_READ);
export const canAskAllocate = (me: Pick<Me, 'permissions'>): boolean => held(me).includes(PAYMENTS_ALLOCATE);

/** Why the loan or account did not take the money, in plain words. */
const REFUSAL_WORDS: Record<string, string> = {
  invalid_status_transition: 'The loan was already closed or written off',
  account_closed: 'The savings account was closed',
  account_frozen: 'The savings account was frozen',
  account_dormant: 'The savings account was dormant',
  value_date_in_future: 'The payment date was in the future',
  before_opening: 'The payment was dated before the account opened',
  value_date_closed: 'The payment was dated into a day already closed',
  below_minimum_opening: 'The first deposit was below the minimum',
  payment_method_unmapped: 'Mobile money has no account set up',
  duplicate_reference: 'The same payment reference was already used',
  period_closed: 'The accounting period was closed',
  currency_mismatch: 'The payment was in another currency',
  subject_not_payable: 'The loan or account cannot take payments',
};

export const refusalWords = (code: string | undefined): string => (code && REFUSAL_WORDS[code]) || 'The loan or account could not take it';

export const STATUS_FILTERS: { value: 'open' | 'allocated' | 'refunded' | 'reversed' | 'all'; label: string }[] = [
  { value: 'open', label: 'Waiting' },
  { value: 'allocated', label: 'Put on a loan or account' },
  { value: 'refunded', label: 'Sent back' },
  { value: 'reversed', label: 'Reversed by the provider' },
  { value: 'all', label: 'All' },
];

export const REFUND_METHODS: { value: string; label: string }[] = [
  { value: 'gateway', label: 'Through the payment provider' },
  { value: 'bank', label: 'Bank' },
  { value: 'cash', label: 'Cash' },
  { value: 'mtn_momo', label: 'MTN mobile money' },
  { value: 'airtel_money', label: 'Airtel money' },
];

export const ALLOCATE_PURPOSES: { value: string; label: string }[] = [
  { value: 'lending.loan_repayment', label: 'A loan repayment' },
  { value: 'lending.savings_deposit', label: 'A savings deposit' },
];

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
export const subjectProblem = (value: string): string | null => (UUID.test(value.trim()) ? null : 'Paste the loan or account identifier exactly as shown on its page.');
export const reasonProblem = (value: string): string | null => (value.trim().length >= 3 && value.length <= 300 ? null : 'Say why, in a few words (up to 300 characters).');
/** The lender's own reference for a refund: printable characters, 1 to 100. */
export const referenceProblem = (value: string): string | null => (/^[\x20-\x7E]{1,100}$/.test(value) && value.trim() !== '' ? null : 'Type the bank or wallet reference (up to 100 characters).');

// The statement upload and the reconciliation report (extras slice A5 part 2, issue #224, task #317).

export const PAYMENTS_MANAGE = 'core.payments.manage';

/** Reading the report; uploading and running are for the holders of manage. */
export const showReconciliation = (me: Pick<Me, 'permissions'>): boolean => held(me).includes(PAYMENTS_READ);
export const canManageStatements = (me: Pick<Me, 'permissions'>): boolean => held(me).includes(PAYMENTS_MANAGE);

/** What each difference means for the person reading it, in plain words. */
export const FINDING_WORDS: Record<string, { title: string; help: string }> = {
  completed_not_booked: { title: 'Paid but not recorded', help: 'The provider says this was paid and it is not on the loan or account yet. The system is trying again; if it stays, ask the person on call.' },
  pending_over_24h: { title: 'Still unpaid after a day', help: 'A payment link made over a day ago that nobody has paid.' },
  paid_differs: { title: 'Paid a different amount', help: 'The member paid more or less than the amount asked. The amount paid was recorded.' },
  reversed_awaiting_review: { title: 'Reversed by the provider, waiting for a decision', help: 'A second person decides in the approvals inbox.' },
  reversal_handed_over: { title: 'Reversed by the provider, nobody is waiting on it', help: 'It was rejected or could not be finished. Ask for it to be decided again, or leave it.' },
  reversal_blocked: { title: 'Reversal refused by the books', help: 'The reversal cannot be done (for example the deposit was already withdrawn). Record the loss by manual journal and reject the request.' },
  reversed_after_refund: { title: 'Reversed after it was already sent back', help: 'You sent this money back and the provider then reversed it too. Record the loss by manual journal.' },
  manual_receipt_duplicate: { title: 'Entered by hand and also paid online', help: 'A cashier entered this payment as well as the online payment being recorded. Reverse the one entered by hand.' },
  in_provider_not_in_bms: { title: 'On the statement, not recorded here', help: 'The statement has a payment this system does not hold as paid. It may be money for something outside this system.' },
  in_bms_not_in_provider: { title: 'Recorded here, not on the statement', help: 'Booked on a day the statement covers but missing from it. Check the statement is complete.' },
  amount_mismatch: { title: 'The amount differs from the statement', help: 'The statement and this system disagree about how much was paid.' },
  currency_mismatch: { title: 'The currency differs from the statement', help: 'The statement and this system disagree about the currency.' },
  ambiguous_statement_row: { title: 'A statement line fits several payments', help: 'Its payment code names more than one payment. Use the order tracking id column in the export.' },
};

export const findingWords = (kind: string | undefined) => (kind && FINDING_WORDS[kind]) || { title: 'A difference', help: 'Look at it with the person on call.' };

export const MATCH_WORDS: Record<string, string> = {
  matched: 'Matches',
  amount_mismatch: 'Different amount',
  currency_mismatch: 'Different currency',
  not_paid_here: 'Not recorded as paid here',
  ambiguous: 'Fits several payments',
  unmatched: 'No payment of ours',
};

export const FEE_WORDS: Record<string, string> = {
  posted: 'Fee recorded',
  already_posted: 'Fee already recorded',
  none: 'No fee',
  held: 'Fee not recorded',
  awaiting_approval: 'Fee above the automatic rate: waiting for a second person to approve',
  not_approved: 'Fee not approved, not recorded',
};

/** A file that can be sent: a CSV (or plain text), up to 1 MB. */
export function fileProblem(file: { name: string; size: number } | null | undefined): string | null {
  if (!file) return 'Choose the statement file.';
  if (file.size === 0) return 'The file is empty.';
  if (file.size > 1_000_000) return 'The file is larger than 1 MB. Export a shorter period.';
  if (!/\.(csv|txt)$/i.test(file.name)) return 'Use the CSV export of the statement.';
  return null;
}
