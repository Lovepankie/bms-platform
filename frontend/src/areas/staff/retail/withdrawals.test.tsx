import { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it } from 'vitest';
import { RetailError, businessToday, daysBefore, type Withdrawal } from '../../../api/retail';
import { createMockRetail, setMockProfitAccess } from '../../../api/retail-mock';
import { ALL_BRANCHES } from '../../../auth/branch';
import { warningWords } from './cash-state';
import { Warnings } from './cash-ui';
import { branch, html, page, pageAs, sessionOf } from './cashbook-test-utils';
import { canUse } from './permissions';
import { BranchRequired } from './ui';
import { WithdrawalForm, WithdrawalRecords } from './withdrawals';

const today = businessToday();

describe('withdrawals screen', () => {
  it('labels every input and says where the cash goes', () => {
    const out = page('admin', <WithdrawalForm branchId={branch} />);
    for (const id of ['withdrawal-date', 'withdrawal-amount', 'withdrawal-purpose']) expect(out).toContain(`for="${id}"`);
    expect(out).toContain('The cash goes into this shop');
    expect(out).toContain('Save withdrawal');
  });

  it('is for the owner or an administrator only', () => {
    expect(canUse(sessionOf('admin'), 'withdrawals')).toBe(true);
    expect(canUse(sessionOf('cashier'), 'withdrawals')).toBe(false);
    expect(canUse(sessionOf('sales'), 'withdrawals')).toBe(false);
  });

  it('needs one branch: All branches offers the branches where it may be recorded', () => {
    const out = pageAs(sessionOf('admin'), <BranchRequired permissions={['retail.withdrawal.record']} />, new QueryClient(), ALL_BRANCHES);
    expect(out).toContain('Choose a branch to continue');
  });

  it('shows the bank balance warning in plain words', () => {
    expect(warningWords('bank_balance_negative')).toMatch(/more than the bank balance on record/);
    expect(html(<Warnings words={[warningWords('bank_balance_negative') ?? '']} />)).toContain('role="note"');
  });

  it('lists withdrawals with the purpose and a Void for a holder of the void permission', () => {
    const rows: Withdrawal[] = [{ id: 'w1', branch_id: branch, business_date: today, amount_minor: 200000, purpose: 'Stock money', by_name: 'Test Admin 01', voided: false }];
    const client = new QueryClient();
    client.setQueryData(['retail', 'withdrawals', [branch], daysBefore(today, 30), today], rows);
    const admin = page('admin', <WithdrawalRecords />, client);
    expect(admin).toContain('UGX 200,000');
    expect(admin).toContain('Stock money');
    expect(admin).toContain('>Void<');
    expect(page('cashier', <WithdrawalRecords />, client)).not.toContain('>Void<');
  });
});

describe('withdrawals against the mock', () => {
  beforeEach(() => setMockProfitAccess(true));

  it('accepts a withdrawal above the bank balance with a warning, and one within it without', async () => {
    const mock = createMockRetail();
    const date = daysBefore(today, 5);
    const over = await mock.createWithdrawal({ branch_id: branch, business_date: date, amount_minor: 500000 }, 'w1');
    expect(over.warnings).toEqual(['bank_balance_negative']);
    await mock.createBanking({ branch_id: branch, business_date: date, amount_minor: 900000 }, 'wb1');
    const fine = await mock.createWithdrawal({ branch_id: branch, business_date: date, amount_minor: 1000, purpose: 'Float' }, 'w2');
    expect(fine.warnings).toBeUndefined();
  });

  it('replays a repeated key once, refuses a future date and voids once', async () => {
    const mock = createMockRetail();
    const body = { branch_id: branch, business_date: today, amount_minor: 1000 };
    const one = await mock.createWithdrawal(body, 'w3');
    expect((await mock.createWithdrawal(body, 'w3')).id).toBe(one.id);
    expect((await mock.listWithdrawals({})).filter((w) => w.id === one.id)).toHaveLength(1);
    const future = (await mock.createWithdrawal({ ...body, business_date: '2999-01-01' }, 'w4').catch((e: unknown) => e)) as RetailError;
    expect(future.message).toBe('The date cannot be in the future.');
    await mock.voidWithdrawal(one.id ?? '', 'Wrong amount', 'wv1');
    expect(((await mock.voidWithdrawal(one.id ?? '', 'Again', 'wv2').catch((e: unknown) => e)) as RetailError).code).toBe('cash_record_voided');
    expect((await mock.listWithdrawals({})).some((w) => w.id === one.id)).toBe(false);
    expect((await mock.listWithdrawals({ includeVoided: true })).some((w) => w.id === one.id)).toBe(true);
  });
});
