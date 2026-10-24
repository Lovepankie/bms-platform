import { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it } from 'vitest';
import { RetailError, businessToday, daysBefore, type Savings, type SavingsSuggestion } from '../../../api/retail';
import { createMockRetail, setMockProfitAccess } from '../../../api/retail-mock';
import { ALL_BRANCHES } from '../../../auth/branch';
import { branch, page, pageAs, sessionOf } from './cashbook-test-utils';
import { changedSuggestion, nowAboveWords, planSavings, reasonText, savedWords } from './savings-state';
import { showDate } from './maths';
import { SavingsForm, SavingsRecords, showInRecords } from './savings';
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
    expect(out).toContain('<span class="key-figure-label">Suggested savings</span><strong class="key-figure-value">UGX 20,000</strong>');
    expect(out).toContain('Profit for the day: <strong>UGX 40,000</strong>');
    expect(out).toContain('for="savings-amount"');
    // The reason field appears only once the typed amount differs from the suggestion.
    expect(out).not.toContain('savings-reason');
  });

  it('says a day already has savings and offers the void in the records', () => {
    const out = page('admin', <SavingsForm branchId={branch} />, clientWith({ ...withAmounts, existing_id: 'x' }));
    expect(out).toContain('already recorded');
  });

  it('treats existing_id null (what the API sends when nothing is saved) as no savings yet', () => {
    // The API now leaves existing_id out on an empty day (NON_NULL), but an older server wrote every field
    // (default-property-inclusion: always) and sent existing_id: null, which the pilot tenant hit (#536).
    const out = page('admin', <SavingsForm branchId={branch} />, clientWith({ ...withAmounts, existing_id: null as unknown as undefined }));
    expect(out).not.toContain('already recorded');
    expect(out).toContain('Save savings');
    expect(out).not.toMatch(/<button[^>]*disabled[^>]*>Save savings/);
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

describe('a new record shown at once', () => {
  it('goes only into the loaded lists whose branch and window hold it, and leaves anything else under the key alone', () => {
    const client = new QueryClient();
    const saved: Savings = { id: 'sv-9', branch_id: branch, business_date: '2026-10-24' };
    const mine = ['retail', 'savings', [branch], '2026-10-01', '2026-10-31'];
    const all = ['retail', 'savings', undefined, '2026-10-01', '2026-10-31'];
    const otherShop = ['retail', 'savings', ['another-branch'], '2026-10-01', '2026-10-31'];
    const lastMonth = ['retail', 'savings', [branch], '2026-09-01', '2026-09-30'];
    const report = ['retail', 'savings', 'report'];
    const short = ['retail', 'savings', [branch]];
    // A list of rows under the same prefix that is not the records list (a report, an export) is left alone.
    const rowsReport = ['retail', 'savings', 'report', '2026-10-01', '2026-10-31'];
    const longer = ['retail', 'savings', [branch], '2026-10-01', '2026-10-31', 'by-day'];
    client.setQueryData(rowsReport, [{ id: 'r1' }]);
    client.setQueryData(longer, []);
    client.setQueryData(mine, []);
    client.setQueryData(all, [{ id: 'old' }]);
    client.setQueryData(otherShop, []);
    client.setQueryData(lastMonth, []);
    client.setQueryData(report, { items: [] });
    client.setQueryData(short, []);
    showInRecords(client, saved);
    showInRecords(client, saved);
    expect(client.getQueryData(mine)).toEqual([saved]);
    expect(client.getQueryData(all)).toEqual([saved, { id: 'old' }]);
    expect(client.getQueryData(otherShop)).toEqual([]);
    expect(client.getQueryData(lastMonth)).toEqual([]);
    expect(client.getQueryData(report)).toEqual({ items: [] });
    expect(client.getQueryData(short)).toEqual([]);
    expect(client.getQueryData(rowsReport)).toEqual([{ id: 'r1' }]);
    expect(client.getQueryData(longer)).toEqual([]);
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

  it('takes a changed amount with no reason, and sends a reason only when one is given (#538)', () => {
    const none = planSavings({ ...input, amount: '15000' });
    expect(none.problem).toBeNull();
    expect(none.overwrite).toBe(true);
    expect(none.request).toMatchObject({ amount_minor: 15000 });
    expect('overwrite_reason' in none.request).toBe(false);
    const ok = planSavings({ ...input, amount: '15,000', reason: 'Debt' });
    expect(ok.problem).toBeNull();
    expect(ok.request).toMatchObject({ amount_minor: 15000, overwrite_reason: 'Debt' });
  });

  it('turns the pick list into the reason sent: a pick, the words under Other, or nothing', () => {
    expect(reasonText('', 'ignored')).toBe('');
    expect(reasonText('Paying a debt', 'ignored')).toBe('Paying a debt');
    expect(reasonText('Other', '  Rent due  ')).toBe('Rent due');
    expect(reasonText('Other', '')).toBe('');
    expect(reasonText('Made up', '')).toBe('');
  });

  it('refuses an amount above the available in plain words, and allows it at the available (ADR-080)', () => {
    const capped = { ...input, suggestion: { ...withAmounts, day_expenses_minor: 10000, available_minor: 30000 }, today };
    const above = planSavings({ ...capped, amount: '30001' });
    expect(above.problem).toBe('You can save at most UGX 30,000 today (profit UGX 40,000 minus expenses UGX 10,000).');
    expect('amount_minor' in above.request).toBe(false);
    expect(planSavings({ ...capped, amount: '30000' }).problem).toBeNull();
    const yesterday = daysBefore(today, 1);
    expect(planSavings({ ...capped, date: yesterday, amount: '30001' }).problem).toContain(`for ${showDate(yesterday)} (profit`);
  });

  it('says when a saved day now has less than was saved, and says nothing otherwise or without profit read', () => {
    const day = { ...withAmounts, existing_id: 'sv-1', available_minor: 2000, day_expenses_minor: 38000 };
    expect(nowAboveWords({ ...day, existing_amount_minor: 5000 })).toBe('Saved UGX 5,000; profit less expenses is now UGX 2,000.');
    expect(nowAboveWords({ ...day, existing_amount_minor: 2000 })).toBeNull();
    expect(nowAboveWords({ ...withoutAmounts, existing_id: 'sv-1' })).toBeNull();
    expect(nowAboveWords({ ...day, existing_id: undefined, existing_amount_minor: 5000 })).toBeNull();
    const out = page('admin', <SavingsForm branchId={branch} />, clientWith({ ...day, existing_amount_minor: 5000 }));
    expect(out).toContain('Saved UGX 5,000; profit less expenses is now UGX 2,000.');
  });

  it('words the toast with the amount and the day, and without an amount for a caller who may not see it', () => {
    expect(savedWords({ amount_minor: 4000, business_date: '2026-10-24' })).toBe('Savings of UGX 4,000 saved for 24 Oct');
    expect(savedWords({ business_date: '2026-10-24' })).toBe('Savings saved for 24 Oct');
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

  it('takes an overwrite without a reason, refuses one above the available, and a repeated key posts once', async () => {
    const mock = createMockRetail();
    const products = await mock.listProducts({ branchId: branch });
    await mock.createSale({ branch_id: branch, payment_method: 'cash', lines: [{ product_id: products[2]?.id ?? '', qty: '1' }] }, 'sale-key-ow');
    const s = await mock.savingsSuggestion({ branchId: branch, date: today });
    expect(s.available_minor).toBeGreaterThan(7);
    const body = { branch_id: branch, business_date: today, suggestion_token: s.suggestion_token, amount_minor: 7 };
    const above = (await mock.createSavings({ ...body, amount_minor: (s.available_minor ?? 0) + 1 }, 'k2').catch((e: unknown) => e)) as RetailError;
    expect(above.code).toBe('savings_above_available');
    expect(above.message).toMatch(/^You can save at most UGX/);
    const bare = await mock.createSavings(body, 'k3');
    expect(bare.overwritten).toBe(true);
    await mock.voidSavings(bare.id ?? '', 'Redo', 'k3v');
    const one = await mock.createSavings({ ...body, overwrite_reason: 'Owner asked' }, 'k4');
    const two = await mock.createSavings({ ...body, overwrite_reason: 'Owner asked' }, 'k4');
    expect(two.id).toBe(one.id);
    expect((await mock.listSavings({})).filter((r) => r.business_date === today)).toHaveLength(1);
  });

  it('voids once, and refuses a second void', async () => {
    const mock = createMockRetail();
    const s = await mock.savingsSuggestion({ branchId: branch, date: daysBefore(today, 2) });
    const saved = await mock.createSavings({ branch_id: branch, business_date: daysBefore(today, 2), suggestion_token: s.suggestion_token }, 'k5');
    expect((await mock.voidSavings(saved.id ?? '', 'Entered twice', 'v1')).voided).toBe(true);
    expect(((await mock.voidSavings(saved.id ?? '', 'Again', 'v2').catch((e: unknown) => e)) as RetailError).code).toBe('cash_record_voided');
  });
});
