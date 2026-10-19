import { describe, expect, it } from 'vitest';
import type { MerchantAccount } from '../../../api/payments';
import { canChangePaymentAccount, codeProblem, credentialProblem, failureWords, nextStep, showReceivePayments } from './state';

const none: MerchantAccount = { status: 'not_connected', credentials_saved: false, key_hint: null, last_checked_at: null, failure_reason: null, host_ready: true };

describe('Receive payments state', () => {
  it('opens for a settings reader and changes only for a settings manager', () => {
    expect(showReceivePayments({ permissions: ['core.settings.read'] })).toBe(true);
    expect(showReceivePayments({ permissions: ['core.payment_account.manage'] })).toBe(true);
    expect(showReceivePayments({ permissions: ['core.approvals.read'] })).toBe(false);
    expect(showReceivePayments({})).toBe(false);
    expect(canChangePaymentAccount({ permissions: ['core.settings.read'] })).toBe(false);
    // The general settings permission does not change the payment account.
    expect(canChangePaymentAccount({ permissions: ['core.settings.manage'] })).toBe(false);
    expect(codeProblem('123456')).toBeNull();
    expect(codeProblem('12345')).not.toBeNull();
    expect(codeProblem('12345a')).not.toBeNull();
    expect(canChangePaymentAccount({ permissions: ['core.payment_account.manage'] })).toBe(true);
  });

  it('names the next step in one sentence for each state', () => {
    expect(nextStep(none)).toMatch(/Enter the consumer key and secret/);
    expect(nextStep({ ...none, credentials_saved: true })).toMatch(/test the connection/);
    expect(nextStep({ ...none, status: 'verified', credentials_saved: true })).toMatch(/straight into your own Pesapal account/);
    expect(nextStep({ ...none, status: 'failed', credentials_saved: true, failure_reason: 'invalid_credentials' })).toMatch(/did not accept/);
    expect(nextStep({ ...none, host_ready: false })).toMatch(/not set up on this system/);
    expect(failureWords('something_new')).toMatch(/did not pass/);
  });

  it('accepts a credential as Pesapal shows it and refuses spaces, control characters and short values', () => {
    expect(credentialProblem('abcd1234')).toBeNull();
    expect(credentialProblem('  abcd1234  ')).toBeNull();
    expect(credentialProblem('abcd 1234')).not.toBeNull();
    expect(credentialProblem('short')).not.toBeNull();
    expect(credentialProblem('')).not.toBeNull();
    expect(credentialProblem('x'.repeat(257))).not.toBeNull();
  });
});
