import { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it } from 'vitest';
import { RetailError, businessToday, daysBefore, type Savings, type SavingsSuggestion } from '../../../api/retail';
import { createMockRetail, setMockProfitAccess } from '../../../api/retail-mock';
import { ALL_BRANCHES } from '../../../auth/branch';
import { branch, page, pageAs, sessionOf } from './cashbook-test-utils';
import { changedSuggestion, planSavings } from './savings-state';
import { SavingsForm, SavingsRecords } from './savings';
import { BranchRequired } from './ui';

const today = businessToday();
const withAmounts: SavingsSuggestion = { branch_id: branch, business_date: today, total_sold_minor: 100000, suggestion_token: 'tok-1', suggested_minor: 20000, daily_profit_minor: 40000 };
const withoutAmounts: SavingsSuggestion = { branch_id: branch, business_date: today, total_sold_minor: 100000, suggestion_token: 'tok-1' };
const clientWith = (s: SavingsSuggestion) => {
  const client = new QueryClient();
  client.setQueryData(['retail', 'savings-suggestion', branch, today], s);
  return client;
};

describe('savings form', () => {
  it('shows a caller without profit read the total sold and the standard amount wording, never an amount, a suggestion or the profit', () => {
    const out = page('cashier', <SavingsForm branchId={branch} />, clientWith(withoutAmounts));
    expect(out).toContain('UGX 100,000');
    expect(out).toContain('Savings will be recorded using the standard amount.');
    expect(out).not.toContain('Suggested savings');
    expect(out).not.toContain('Profit for the day');
    expect(out).not.toContain('UGX 20,000');
    expect(out).not.toContain('UGX 40,000');
    expect(out).not.toContain('savings-amount');
    expect(out).toContain('for="savings-date"');
  });

  it('shows a caller with profit read the suggestion and the profit and lets them type an amount', () => {
    const out = page('admin', <SavingsForm branchId={branch} />, clientWith(withAmounts));
    expect(out).toContain('Suggested savings UGX 20,000');
    expect(out).toContain('Profit for the day: <strong>UGX 40,000</strong>');
    expect(out).toContain('for="savings-amount"');
    // The reason field appears only once the typed amount differs from the suggestion.
    expect(out).not.toContain('savings-reason');
  });

  it('says a day already has savings and offers the void in the records', () => {
    const out = page('admin', <SavingsForm branchId={branch} />, clientWith({ ...withAmounts, existing_id: 'x' }));
    expect(out).toContain('already recorded');
  });

  it('offers the Branch buttons instead of the form with All branches', () => {
    const out = pageAs(sessionOf('admin'), <BranchRequired permissions={['retail.savings.record']} />, new QueryClient(), ALL_BRANCHES);
    expect(out).toContain('Choose a branch to continue');
    expect(out).toContain('Test Branch A');
  });
});

describe('savings records', () => {
  const rows: Savings[] = [
    { id: 'r1', branch_id: branch, business_date: today, total_sold_minor: 100000, amount_minor: 20000, overwritten: true, by_name: 'Test User 01', voided: false, historical: false },
    { id: 'r2', branch_id: branch, business_date: daysBefore(today, 40), total_sold_minor: 50000, by_name: 'Test User 02', voided: true, void_reason: 'Entered twice', historical: false },
  ];
  const seeded = () => {
    const client = new QueryClient();
    client.setQueryDefaults(['retail', 'savings'], { staleTime: Infinity });
    return client;
  };

  it('is empty before the list arrives and never infers an amount', () => {
    expect(page('cashier', <SavingsRecords />, seeded())).toContain('Savings records');
  });

  it('shows the amount only when the row carries it, and Void only for a holder of the void permission', () => {
    const client = new QueryClient();
    const key = ['retail', 'savings', undefined, daysBefore(today, 30), today];
    client.setQueryData(key, rows);
    const admin = pageAs(sessionOf('admin'), <SavingsRecords />, client, ALL_BRANCHES);
    expect(admin).toContain('Saved UGX 20,000');
    expect(admin).toContain('Savings recorded');
    expect(admin).toContain('>Void<');
    expect(admin).toContain('Voided');
    expect(admin).toContain('Entered twice');
    const cashier = pageAs(sessionOf('cashier'), <SavingsRecords />, client, ALL_BRANCHES);
    expect(cashier).not.toContain('>Void<');
  });
});

describe('savings rules', () => {
  const input = { branchId: branch, date: today, suggestion: withAmounts, amount: '', reason: '', canOverwrite: true };

  it('sends only the token when nothing is typed, and echoes the token always', () => {
    expect(planSavings(input).request).toEqual({ branch_id: branch, business_date: today, suggestion_token: 'tok-1' });
  });

  it('sends no amount and no reason to a caller without profit read, whatever was typed', () => {
    const plan = planSavings({ ...input, suggestion: withoutAmounts, amount: '99999', reason: 'because I can' });
    expect(plan.problem).toBeNull();
    expect(plan.request).toEqual({ branch_id: branch, business_date: today, suggestion_token: 'tok-1' });
    expect('amount_minor' in plan.request).toBe(false);
    expect('overwrite_reason' in plan.request).toBe(false);
  });

  it('needs a reason of at least 5 characters for an amount that differs', () => {
    expect(planSavings({ ...input, amount: '15000' }).problem).toMatch(/at least 5/);
    expect(planSavings({ ...input, amount: '15000', reason: 'abcd' }).problem).toMatch(/at least 5/);
    const ok = planSavings({ ...input, amount: '15,000', reason: 'Owner asked' });
    expect(ok.problem).toBeNull();
    expect(ok.request).toMatchObject({ amount_minor: 15000, overwrite_reason: 'Owner asked' });
  });

  it('sends the amount without a reason when it equals the suggestion, and allows zero', () => {
    expect(planSavings({ ...input, amount: '20000' }).request).toMatchObject({ amount_minor: 20000 });
    expect('overwrite_reason' in planSavings({ ...input, amount: '20000' }).request).toBe(false);
    expect(planSavings({ ...input, amount: '0', reason: 'No money to spare' }).request).toMatchObject({ amount_minor: 0 });
  });

  it('blocks a changed amount for a caller who may not overwrite, in plain words', () => {
    const plan = planSavings({ ...input, amount: '15000', reason: 'Owner asked', canOverwrite: false });
    expect(plan.problem).toMatch(/only an administrator/i);
    expect('amount_minor' in plan.request).toBe(false);
  });

  it('rejects an amount that is not a number', () => {
    expect(planSavings({ ...input, amount: 'abc' }).problem).toMatch(/whole shillings/);
  });

  it('reads the new token from a 409 body', () => {
    expect(changedSuggestion({ suggestion_token: 'tok-2', suggested_minor: 30000 })).toEqual({ token: 'tok-2', suggested: 30000 });
    expect(changedSuggestion({ suggestion_token: 'tok-2' })).toEqual({ token: 'tok-2' });
    expect(changedSuggestion({})).toBeNull();
  });
});

describe('savings against the mock', () => {
  beforeEach(() => setMockProfitAccess(true));

  it('refuses a stale token with the new one, and records after a refresh', async () => {
    const mock = createMockRetail();
    const first = await mock.savingsSuggestion({ branchId: branch, date: today });
    const products = await mock.listProducts({ branchId: branch });
    await mock.createSale({ branch_id: branch, payment_method: 'cash', lines: [{ product_id: products[2]?.id ?? '', qty: '1' }] }, 'sale-key-1');
    const stale = await mock.createSavings({ branch_id: branch, business_date: today, suggestion_token: first.suggestion_token }, 'save-key-1').catch((e: unknown) => e);
    expect(stale).toBeInstanceOf(RetailError);
    const error = stale as RetailError;
    expect(error.status).toBe(409);
    expect(error.code).toBe('suggestion_changed');
    expect(error.message).toMatch(/sales changed/i);
    const next = changedSuggestion(error.problem);
    expect(next?.token).toBeTruthy();
    expect(next?.token).not.toBe(first.suggestion_token);
    const saved = await mock.createSavings({ branch_id: branch, business_date: today, suggestion_token: next?.token ?? '' }, 'save-key-2');
    expect(saved.voided).toBe(false);
    const again = await mock.createSavings({ branch_id: branch, business_date: today, suggestion_token: next?.token ?? '' }, 'save-key-3').catch((e: unknown) => e);
    expect((again as RetailError).code).toBe('savings_exists');
  });

  it('gives a caller without profit read no amounts, refuses one they send, and does not echo the default', async () => {
    const mock = createMockRetail();
    setMockProfitAccess(false);
    const s = await mock.savingsSuggestion({ branchId: branch, date: today });
    expect(s.total_sold_minor).toBeDefined();
    expect('suggested_minor' in s).toBe(false);
    expect('daily_profit_minor' in s).toBe(false);
    const refused = await mock.createSavings({ branch_id: branch, business_date: today, suggestion_token: s.suggestion_token, amount_minor: 100 }, 'k1').catch((e: unknown) => e);
    expect((refused as RetailError).code).toBe('amount_requires_profit_access');
    const saved = await mock.createSavings({ branch_id: branch, business_date: today, suggestion_token: s.suggestion_token }, 'k2');
    expect('amount_minor' in saved).toBe(false);
    const list = await mock.listSavings({});
    expect(list.every((r) => !('amount_minor' in r) && !('suggested_minor' in r))).toBe(true);
  });

  it('needs a reason for an overwrite, and a repeated key posts once', async () => {
    const mock = createMockRetail();
    const s = await mock.savingsSuggestion({ branchId: branch, date: daysBefore(today, 1) });
    const body = { branch_id: branch, business_date: daysBefore(today, 1), suggestion_token: s.suggestion_token, amount_minor: 7 };
    expect(((await mock.createSavings(body, 'k3').catch((e: unknown) => e)) as RetailError).code).toBe('reason_required');
    const one = await mock.createSavings({ ...body, overwrite_reason: 'Owner asked' }, 'k4');
    const two = await mock.createSavings({ ...body, overwrite_reason: 'Owner asked' }, 'k4');
    expect(two.id).toBe(one.id);
    expect((await mock.listSavings({})).filter((r) => r.business_date === daysBefore(today, 1))).toHaveLength(1);
  });

  it('voids once, and refuses a second void', async () => {
    const mock = createMockRetail();
    const s = await mock.savingsSuggestion({ branchId: branch, date: daysBefore(today, 2) });
    const saved = await mock.createSavings({ branch_id: branch, business_date: daysBefore(today, 2), suggestion_token: s.suggestion_token }, 'k5');
    expect((await mock.voidSavings(saved.id ?? '', 'Entered twice', 'v1')).voided).toBe(true);
    expect(((await mock.voidSavings(saved.id ?? '', 'Again', 'v2').catch((e: unknown) => e)) as RetailError).code).toBe('cash_record_voided');
  });
});
