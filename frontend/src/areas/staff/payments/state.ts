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
