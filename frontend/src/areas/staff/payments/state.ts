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
