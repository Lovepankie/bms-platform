import { describe, expect, it } from 'vitest';
import { FAST_POLL_MS, SLOW_POLL_MS, flagWords, isCancellable, isSettled, pollDelay, refusalWords, statusWords, suggestedAmount } from './collect-state';

describe('how often the collect screen asks', () => {
  it('asks every 3 seconds for the first 2 minutes and then every 15 seconds', () => {
    expect(pollDelay(0)).toBe(FAST_POLL_MS);
    expect(pollDelay(119_999)).toBe(FAST_POLL_MS);
    expect(pollDelay(120_000)).toBe(SLOW_POLL_MS);
  });

  it('stops asking once the payment reached an end, and keeps asking while it can still change', () => {
    for (const s of ['booked', 'booked_unallocated', 'needs_attention', 'failed', 'cancelled', 'reversed_pending_review', 'reversed']) {
      expect(isSettled(s), s).toBe(true);
    }
    // An expired link may still be paid, and a paid one is about to be recorded.
    for (const s of ['created', 'pending', 'expired', 'paid', undefined]) expect(isSettled(s), String(s)).toBe(false);
  });

  it('offers Cancel only while nothing has been paid', () => {
    expect(['created', 'pending', 'expired'].every(isCancellable)).toBe(true);
    expect(['paid', 'booked', 'failed', 'cancelled'].some(isCancellable)).toBe(false);
  });
});

describe('what the status says', () => {
  it('says plainly where an unallocated payment is and why', () => {
    const text = statusWords({ status: 'booked_unallocated', refusal_code: 'invalid_status_transition' });
    expect(text).toContain('closed');
    expect(text).toContain('unallocated receipt');
  });

  it('has words for every status the server can send and never shows a code for a known one', () => {
    for (const s of ['created', 'pending', 'expired', 'paid', 'booked', 'booked_unallocated', 'needs_attention', 'failed', 'cancelled', 'reversed_pending_review', 'reversed']) {
      expect(statusWords({ status: s }), s).not.toMatch(/_/);
    }
    expect(refusalWords(undefined)).toBe('the loan could not take it');
    expect(refusalWords('some_new_code')).toBe('some new code');
  });

  it('explains a partial, a larger and a late payment', () => {
    expect(flagWords(['paid_less', 'late']).length).toBe(2);
    expect(flagWords(undefined)).toEqual([]);
  });
});

describe('the amount offered first', () => {
  const schedule = {
    items: [
      { no: 1, outstanding_minor: 0 },
      { no: 2, outstanding_minor: 520_000 },
      { no: 3, outstanding_minor: 520_000 },
    ],
  };

  it('is the arrears when there are any, otherwise the next instalment still owed', () => {
    expect(suggestedAmount({ arrears_minor: 700_000, total_outstanding_minor: 1_040_000 }, schedule)).toBe(700_000);
    expect(suggestedAmount({ arrears_minor: 0, total_outstanding_minor: 1_040_000 }, schedule)).toBe(520_000);
  });

  it('is never more than the loan owes and is nothing for a loan that owes nothing', () => {
    expect(suggestedAmount({ arrears_minor: 900_000, total_outstanding_minor: 300_000 }, schedule)).toBe(300_000);
    expect(suggestedAmount({ arrears_minor: 0, total_outstanding_minor: 0 }, schedule)).toBeNull();
    expect(suggestedAmount({ total_outstanding_minor: 250_000 }, undefined)).toBe(250_000);
  });
});

describe('the same words for a savings deposit', () => {
  it('names the account, not the loan', () => {
    expect(statusWords({ status: 'booked' }, 'savings')).toBe('Paid and recorded on the account.');
    expect(statusWords({ status: 'paid' }, 'savings')).toContain('deposit');
    expect(statusWords({ status: 'booked' })).toBe('Paid and recorded on the loan.');
  });

  it('says plainly why a deposit is held as an unallocated receipt', () => {
    const frozen = statusWords({ status: 'booked_unallocated', refusal_code: 'account_frozen' }, 'savings');
    expect(frozen).toContain('the account is frozen');
    expect(frozen).toContain('unallocated receipt');
    expect(refusalWords('account_closed', 'savings')).toBe('the account is closed');
    expect(refusalWords('below_minimum_opening', 'savings')).toContain('minimum opening balance');
    expect(refusalWords(undefined, 'savings')).toBe('the account could not take it');
    expect(refusalWords('invalid_status_transition')).toBe('the loan is closed or no longer takes repayments');
  });
});
