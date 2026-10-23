import { describe, expect, it } from 'vitest';
import type { BillingView } from '../../../api/billing';
import { dueLine } from '../../platform/billing';
import { bannerText, priceSentence, stateBadge, stateSentence } from './billing-state';

// The words of the Plan and billing page, the overdue banner and the operator's list (issue #480, FR-SUB-09, FR-SUB-10,
// FR-SUB-12). Invented round figures.

const view = (v: Partial<BillingView>): BillingView => ({ plan_name: 'Test Plan', currency: 'UGX', grace_days: 7, payments: [], ...v });

describe('the overdue banner', () => {
  it('says since when and the last day of the grace period', () => {
    expect(bannerText({ state: 'in_grace', overdue_since: '2026-11-09', pay_by: '2026-11-15' })).toBe(
      'Your subscription is overdue since 9 Nov 2026; pay by 15 Nov 2026 to keep using the app.',
    );
  });

  it('asks to pay now once the grace period has ended', () => {
    expect(bannerText({ state: 'overdue', overdue_since: '2026-11-09', pay_by: '2026-11-15' })).toBe(
      'Your subscription is overdue since 9 Nov 2026 and the grace period has ended; pay now to keep using the app.',
    );
  });

  it('shows nothing when the business is not overdue', () => {
    expect(bannerText({ state: 'none' })).toBeNull();
    expect(bannerText(undefined)).toBeNull();
  });
});

describe('the Plan and billing page', () => {
  it('names each state in plain words', () => {
    expect(stateSentence(view({ state: 'trial', trial_ends_on: '2026-11-30' }))).toBe('Your free month ends on 30 Nov 2026.');
    expect(stateSentence(view({ state: 'active', paid_until: '2026-12-31' }))).toBe('Paid until 31 Dec 2026.');
    expect(stateSentence(view({ state: 'in_grace', overdue_since: '2026-11-19', pay_by: '2026-11-25' }))).toBe(
      'Your subscription is overdue since 19 Nov 2026. Pay by 25 Nov 2026 to keep using the app.',
    );
    expect(stateSentence(view({ state: 'suspended' }))).toContain('read and export');
    expect(stateSentence(view({ state: 'pilot' }))).toContain('free of charge');
  });

  it('colours a state by how urgent it is', () => {
    expect(stateBadge('active')).toBe('badge badge-success');
    expect(stateBadge('in_grace')).toBe('badge badge-warning');
    expect(stateBadge('overdue')).toBe('badge badge-danger');
  });

  it('gives the price a month and a year, or nothing when none was entered', () => {
    expect(priceSentence(view({ monthly_minor: 10000, annual_minor: 110000, annual_months: 11 }))).toBe(
      'UGX 10,000 a month, or UGX 110,000 for a year (11 months charged).',
    );
    expect(priceSentence(view({}))).toBeNull();
  });
});

describe('the operator list', () => {
  it('says how long, since when, when the grace ended and the price', () => {
    expect(
      dueLine({ days_overdue: 8, overdue_since: '2026-11-13', grace_ended_on: '2026-11-19', amount_due_minor: 10000, currency: 'UGX' }),
    ).toBe('Overdue 8 days, since 13 Nov 2026; grace ended 19 Nov 2026; UGX 10,000 a month.');
  });
});
