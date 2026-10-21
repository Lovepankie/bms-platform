import { describe, expect, it } from 'vitest';
import type { Business, PlanPrice } from '../../api/operator';
import {
  actionSentence,
  addMonths,
  coverFor,
  EMPTY_PLAN,
  expectedAmount,
  formatDay,
  largePriceChange,
  limitsLine,
  paymentProblems,
  paymentSentence,
  planProblems,
  priceLine,
  priceProblems,
  priceSentence,
  reasonProblem,
  subscriptionLine,
  todayInKampala,
  toSavePlan,
} from './operator-state';

// The rules and words of the operator screens (issue #407). Every name and figure is invented.

const TODAY = '2026-10-21';

const base: Business = {
  id: 't-1',
  name: 'Test Shop One',
  slug: 'test-shop-one',
  currency: 'UGX',
  plan_code: 'test-small',
  plan_name: 'Test Small',
  modules: ['retail'],
};

const PRICE: PlanPrice = { currency: 'UGX', monthly_minor: 10000, annual_months: 11, annual_minor: 110000, effective_from: TODAY };

describe('dates', () => {
  it('adds months as the server does, to the last day of a short month', () => {
    expect(addMonths('2026-01-31', 1)).toBe('2026-02-28');
    expect(addMonths('2026-10-21', 12)).toBe('2027-10-21');
    expect(formatDay('2026-11-20')).toBe('20 Nov 2026');
  });

  it('reads today in Kampala, not on the device clock', () => {
    expect(todayInKampala(new Date('2026-10-21T22:30:00Z'))).toBe('2026-10-22');
  });
});

describe('the subscription line', () => {
  it('says when the free month has not started, how long it has left, and when it ended unpaid', () => {
    expect(subscriptionLine({ ...base, subscription_status: 'trial' }, TODAY)).toBe('Free month: it starts when the owner first signs in.');
    expect(subscriptionLine({ ...base, subscription_status: 'trial', trial_ends_on: '2026-11-20' }, TODAY)).toBe(
      'Free month until 20 Nov 2026 (31 days left).',
    );
    expect(subscriptionLine({ ...base, subscription_status: 'trial', trial_ends_on: '2026-10-01' }, TODAY)).toBe(
      'Free month ended on 1 Oct 2026 and nothing is paid yet.',
    );
    expect(subscriptionLine({ ...base, subscription_status: 'active', paid_until: '2026-10-21' }, TODAY)).toBe(
      'Paid until 21 Oct 2026 (1 day left).',
    );
  });
});

describe('payments', () => {
  // As the server: the start plus the months, less a day; from the 31st the next month is clamped to its last day first.
  it('covers the months from the day after a running free month, or after what is paid, or today', () => {
    expect(coverFor({ ...base, subscription_status: 'trial', trial_ends_on: '2026-10-30' }, 1, TODAY)).toEqual({
      start: '2026-10-31',
      end: '2026-11-29',
      freeMonthEnds: undefined,
    });
    expect(coverFor({ ...base, subscription_status: 'active', paid_until: '2026-12-20' }, 12, TODAY)).toMatchObject({
      start: '2026-12-21',
      end: '2027-12-20',
    });
    expect(coverFor({ ...base, subscription_status: 'past_due', paid_until: '2026-09-30' }, 1, TODAY)).toMatchObject({
      start: TODAY,
      end: '2026-11-20',
    });
  });

  it('offers the price of the months chosen, the yearly price for 12', () => {
    expect(expectedAmount(PRICE, 1)).toBe('10000');
    expect(expectedAmount(PRICE, 3)).toBe('30000');
    expect(expectedAmount(PRICE, 12)).toBe('110000');
    expect(expectedAmount(null, 1)).toBe('');
  });

  it('refuses a missing amount, a day after today and too many months', () => {
    const ok = { amount: '10000', method: 'mtn_momo', reference: '', paidOn: TODAY, months: '1', note: '' };
    expect(paymentProblems(ok, 'UGX', TODAY)).toEqual({});
    expect(Object.keys(paymentProblems({ ...ok, amount: '' }, 'UGX', TODAY))).toEqual(['amount']);
    expect(Object.keys(paymentProblems({ ...ok, paidOn: '2026-10-22' }, 'UGX', TODAY))).toEqual(['paidOn']);
    expect(Object.keys(paymentProblems({ ...ok, months: '25' }, 'UGX', TODAY))).toEqual(['months']);
    expect(Object.keys(paymentProblems({ ...ok, amount: '50000001' }, 'UGX', TODAY))).toEqual(['amount']);
  });

  it('starts the free month at a payment made before anyone signed in, and pays after it', () => {
    const early = { ...base, subscription_status: 'trial' };
    expect(coverFor(early, 1, TODAY)).toEqual({ start: '2026-11-21', end: '2026-12-20', freeMonthEnds: '2026-11-20' });
    const form = { amount: '10000', method: 'mtn_momo', reference: '', paidOn: TODAY, months: '1', note: '' };
    expect(paymentSentence(early, form, TODAY)).toContain('Its free month starts today and runs to 20 Nov 2026 first.');
    expect(coverFor({ ...early, pilot: true }, 1, TODAY)).toEqual({ start: TODAY, end: '2026-11-20', freeMonthEnds: undefined });
  });

  it('says in plain words what will be recorded, and that a suspended business stays read only', () => {
    const form = { amount: '10000', method: 'mtn_momo', reference: '', paidOn: TODAY, months: '1', note: '' };
    expect(paymentSentence({ ...base, subscription_status: 'trial', trial_ends_on: '2026-10-30' }, form, TODAY)).toBe(
      'Record UGX 10,000 by MTN Mobile Money from Test Shop One, covering 31 Oct 2026 to 29 Nov 2026 (1 month). It becomes Paying.',
    );
    expect(paymentSentence({ ...base, subscription_status: 'suspended' }, form, TODAY)).toContain('It stays read only until you resume it.');
  });
});

describe('actions', () => {
  it('explains suspension as read only and never deleted', () => {
    const words = actionSentence('suspend', base);
    expect(words).toContain('can still sign in, look and export');
    expect(actionSentence('resume', { ...base, subscription_status: 'suspended', suspended_from: 'trial' })).toContain(
      'goes back to Free month',
    );
    expect(subscriptionLine({ ...base, subscription_status: 'trial', pilot: true }, TODAY)).toBe('Pilot: free, with no end date.');
    expect(words).toContain('Nothing is deleted.');
  });

  it('asks for a reason of at least five characters', () => {
    expect(reasonProblem('no')).toBeDefined();
    expect(reasonProblem('Test: unpaid')).toBeUndefined();
  });
});

describe('plans and prices', () => {
  it('reads limits in plain words, empty as unlimited', () => {
    expect(limitsLine({ allowed_modules: ['retail'], max_branches: 1, max_staff_users: 3 })).toBe('1 shop or store, 3 staff users');
    expect(limitsLine({ allowed_modules: ['retail'] })).toBe('Unlimited shops and stores, unlimited staff');
    expect(limitsLine({ allowed_modules: ['lending'], max_active_members: 300 })).toBe('Unlimited staff, up to 300 members');
  });

  it('shows a price with its year, or that none is entered', () => {
    expect(priceLine(PRICE)).toBe('UGX 10,000 a month, UGX 110,000 a year (11 months charged)');
    expect(priceLine(null)).toBe('No price entered yet');
  });

  it('checks a new plan and sends empty limits as unlimited', () => {
    expect(Object.keys(planProblems(EMPTY_PLAN, true)).sort()).toEqual(['code', 'name']);
    const form = { ...EMPTY_PLAN, code: 'test-small', name: 'Test Small', branches: '1', staff: '', members: '50' };
    expect(planProblems(form, true)).toEqual({});
    expect(toSavePlan(form, '')).toMatchObject({ name: 'Test Small', max_branches: 1, max_staff_users: undefined, max_active_members: undefined });
    expect(planProblems({ ...form, branches: '0' }, true)).toEqual({});
    expect(planProblems({ ...form, branches: '-1' }, true).branches).toBeDefined();
    expect(toSavePlan(form, 'Test: new plan').reason).toBe('Test: new plan');
  });

  it('never lets a price start before today and says what it does', () => {
    const form = { monthly: '10000', annualMonths: '11', from: TODAY };
    expect(priceProblems(form, TODAY)).toEqual({});
    expect(priceProblems({ ...form, from: '2026-10-20' }, TODAY).from).toBeDefined();
    expect(priceProblems({ ...form, annualMonths: '13' }, TODAY).annualMonths).toBeDefined();
    expect(priceProblems({ ...form, monthly: '50000001' }, TODAY).monthly).toBeDefined();
    expect(priceSentence('Test Small', form, TODAY)).toBe(
      'Test Small will cost UGX 10,000 a month and UGX 110,000 a year, from today. Businesses already paid are not changed.',
    );
  });
});

describe('a large price change', () => {
  const now: PlanPrice = { currency: 'UGX', monthly_minor: 30000, annual_months: 12 };
  it('asks for a second look beyond a quarter of the price in force, in shillings', () => {
    expect(largePriceChange(now, 300000)).toBe(
      'The price in force is UGX 30,000 a month, and the new price is UGX 300,000. That is more than a quarter away. Check it, then confirm it.',
    );
    expect(largePriceChange(now, 20000)).toBeDefined();
  });
  it('lets a usual change through, and a first price', () => {
    expect(largePriceChange(now, 36000)).toBeUndefined();
    expect(largePriceChange(now, 24000)).toBeUndefined();
    expect(largePriceChange(undefined, 30000)).toBeUndefined();
  });
});
