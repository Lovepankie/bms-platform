import { describe, expect, it } from 'vitest';
import { retailMessage } from '../../../api/retail-errors';
import { amountOrNull, clockTime, enteredBy, flagWords, groupByShopMonth, monthLabel, warningWords } from './cash-state';

describe('cash book plain logic', () => {
  it('reads an amount in whole shillings and refuses anything else', () => {
    expect(amountOrNull('12,500')).toBe(12500);
    expect(amountOrNull('0')).toBeNull();
    expect(amountOrNull('-5')).toBeNull();
    expect(amountOrNull('1.5')).toBeNull();
    expect(amountOrNull('')).toBeNull();
  });

  it('names a month from a date', () => {
    expect(monthLabel('2026-10-06')).toBe('October 2026');
    expect(monthLabel('2026-01')).toBe('January 2026');
    expect(monthLabel(undefined)).toBe('');
  });

  it('groups records by shop, then months newest first, shops by name', () => {
    const rows = [
      { id: '1', branch_id: 'b', business_date: '2026-09-30' },
      { id: '2', branch_id: 'a', business_date: '2026-10-02' },
      { id: '3', branch_id: 'b', business_date: '2026-10-01' },
      { id: '4', branch_id: 'b', business_date: '2025-12-31' },
    ];
    const groups = groupByShopMonth(rows, (id) => (id === 'a' ? 'Shop A' : 'Shop B'));
    expect(groups.map((g) => g.shop)).toEqual(['Shop A', 'Shop B']);
    expect(groups[1]?.months.map((m) => m.label)).toEqual(['October 2026', 'September 2026', 'December 2025']);
    expect(groups[1]?.months[0]?.rows.map((r) => r.id)).toEqual(['3']);
  });

  it('shows the time in the business zone and who entered a record', () => {
    expect(clockTime('2026-10-06T11:05:00Z')).toBe('14:05');
    expect(clockTime('nonsense')).toBe('');
    expect(enteredBy('Test User 01', '2026-10-06T11:05:00Z')).toBe('by Test User 01 at 14:05');
    expect(enteredBy(undefined, undefined)).toBe('');
  });

  it('puts the banking flag and the warnings in words', () => {
    const show = (m: number) => `UGX ${m}`;
    expect(flagWords('shortfall', -5000, show)).toBe('Shortfall: UGX 5000 less than expected');
    expect(flagWords('surplus', 2000, show)).toBe('Surplus: UGX 2000 more than expected');
    expect(flagWords('ok', 0, show)).toMatch(/matches/i);
    expect(flagWords('not_banked', undefined, show)).toMatch(/not banked/i);
    expect(flagWords(undefined, undefined, show)).toBe('');
    expect(warningWords('cash_below_banked')).toMatch(/more than the cash/);
    expect(warningWords('bank_balance_negative')).toMatch(/bank balance/);
    expect(warningWords('something_new')).toBeNull();
  });
});

describe('cash book refusals in plain words', () => {
  it.each([
    ['suggestion_changed', 409, /sales changed/i],
    ['savings_exists', 409, /already recorded/i],
    ['reason_required', 422, /reason/i],
    ['amount_requires_profit_access', 422, /standard amount/i],
    ['cash_record_voided', 409, /already been voided/i],
    ['explanation_required', 422, /explanation/i],
    ['repayment_exceeds_balance', 422, /more than the advance/i],
    ['advance_settled', 422, /fully repaid/i],
    ['advance_has_repayments', 409, /repayments/i],
    ['version_conflict', 409, /changed this just now/i],
    ['duplicate_party', 409, /already someone/i],
  ])('maps %s', (code, status, text) => {
    expect(retailMessage({ code, detail: 'internal 00000000-0000-4000-8000-000000000001' }, status)).toMatch(text);
  });

  it('says a future date plainly', () => {
    expect(retailMessage({ code: 'validation_failed', errors: [{ field: 'business_date', code: 'future_date', message: 'x' }] }, 422)).toBe('The date cannot be in the future.');
  });
});
