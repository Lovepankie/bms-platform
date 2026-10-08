import { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it } from 'vitest';
import { RetailError, businessToday, daysBefore, type Advance, type AdvanceReport } from '../../../api/retail';
import { createMockRetail, setMockProfitAccess } from '../../../api/retail-mock';
import { AdvanceDetailView, AdvanceForm, AdvanceList, OutstandingView, RepaymentForm, advanceLabel, repaymentProblem } from './advances';
import { branch, page } from './cashbook-test-utils';
import { canUse } from './permissions';
import { sessionOf } from './cashbook-test-utils';

const today = businessToday();
const owing: Advance = { id: 'a1', advance_no: 'ADV-000001', branch_id: branch, business_date: today, party_id: 'p1', party_name: 'Test Owner 01', principal_minor: 500000, repaid_minor: 100000, balance_minor: 400000, voided: false };
const settled: Advance = { ...owing, id: 'a2', advance_no: 'ADV-000002', repaid_minor: 500000, balance_minor: 0 };
const parties = [
  { id: 'p1', name: 'Test Owner 01', kind: 'owner', active: true },
  { id: 'p2', name: 'Test Company 01', kind: 'related_entity', active: true },
  { id: 'p3', name: 'Test Vendor 01', kind: 'supplier', active: true },
];

describe('advances gating', () => {
  it('opens for a recorder of advances or of repayments, and for nobody else', () => {
    expect(canUse({ permissions: ['retail.advance.create'] }, 'advances')).toBe(true);
    expect(canUse({ permissions: ['retail.advance.repay'] }, 'advances')).toBe(true);
    expect(canUse({ permissions: ['retail.cashbook.read'] }, 'advances')).toBe(false);
    expect(canUse(sessionOf('cashier'), 'advances')).toBe(false);
    expect(canUse(sessionOf('admin'), 'advances')).toBe(true);
  });
});

describe('advance form', () => {
  it('labels every input and offers only owners, staff and related companies', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'cash-parties'], parties);
    const out = page('admin', <AdvanceForm branchId={branch} />, client);
    for (const id of ['advance-date', 'advance-party', 'advance-taken-by', 'advance-principal', 'advance-purpose']) expect(out).toContain(`for="${id}"`);
    expect(out).toContain('Test Owner 01');
    expect(out).toContain('Test Company 01');
    expect(out).not.toContain('Test Vendor 01');
    expect(out).toContain('Add a new person or company');
  });
});

describe('repayment form', () => {
  it('lists the advances with a balance, with what is owed, and the three methods', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'advances', 'open'], [owing]);
    const out = page('admin', <RepaymentForm branchId={branch} />, client);
    expect(out).toContain('ADV-000001, Test Owner 01, owes UGX 400,000');
    expect(out).toContain('Cash');
    expect(out).toContain('Mobile money');
    expect(out).toContain('Bank');
    for (const id of ['repay-advance', 'repay-amount', 'repay-date']) expect(out).toContain(`for="${id}"`);
  });

  it('says so when no advance has a balance', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'advances', 'open'], []);
    expect(page('admin', <RepaymentForm branchId={branch} />, client)).toContain('No advance has a balance');
  });

  it('stops a repayment above the balance, in words, before the server is asked', () => {
    expect(advanceLabel(owing)).toBe('ADV-000001, Test Owner 01, owes UGX 400,000');
    expect(repaymentProblem(undefined, '1000')).toMatch(/choose the advance/i);
    expect(repaymentProblem(owing, '')).toMatch(/whole shillings/);
    expect(repaymentProblem(owing, '400,001')).toMatch(/more than the UGX 400,000 still owed/);
    expect(repaymentProblem(owing, '400000')).toBeNull();
  });
});

describe('advance list, detail and outstanding report', () => {
  it('shows the balance of each advance and the filter', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'advances', undefined, false], [owing, settled]);
    const out = page('admin', <AdvanceList onOpen={() => undefined} />, client, null);
    expect(out).toContain('owes UGX 400,000');
    expect(out).toContain('fully repaid');
    expect(out).toContain('aria-pressed="true">All advances<');
    expect(out).toContain('>Still owing<');
  });

  const detail: Advance = { ...owing, by_name: 'Test Admin 01', repayments: [
    { id: 'r1', advance_id: 'a1', amount_minor: 100000, method: 'mobile_money', paid_on: today, by_name: 'Test Admin 01', voided: false },
    { id: 'r2', advance_id: 'a1', amount_minor: 5000, method: 'cash', paid_on: daysBefore(today, 1), voided: true, void_reason: 'Typing error' },
  ] };

  it('shows the repayments, and Void only to a holder of the void permission', () => {
    const admin = page('admin', <AdvanceDetailView advance={detail} />);
    expect(admin).toContain('Mobile money');
    expect(admin).toContain('Typing error');
    expect(admin).toContain('Void them first');
    expect((admin.match(/>Void</g) ?? []).length).toBe(2);
    expect(page('cashier', <AdvanceDetailView advance={detail} />)).not.toContain('>Void<');
  });

  it('shows who owes what, with the oldest date and the total', () => {
    const report: AdvanceReport = { balance_minor: 400000, items: [{ party_id: 'p1', party_name: 'Test Owner 01', count: 2, principal_minor: 600000, repaid_minor: 200000, balance_minor: 400000, oldest_advance_date: '2026-09-01' }] };
    const out = page('admin', <OutstandingView report={report} />);
    expect(out).toContain('Test Owner 01');
    expect(out).toContain('2 advances, the oldest from 1 Sep 2026');
    expect(out).toContain('Total owed UGX 400,000');
    expect(page('admin', <OutstandingView report={{ items: [] }} />)).toContain('No advance has a balance');
  });
});

describe('advances against the mock', () => {
  beforeEach(() => setMockProfitAccess(true));
  const code = async (p: Promise<unknown>) => ((await p.catch((e: unknown) => e)) as RetailError).code;

  it('keeps the balance, refuses too much, refuses a settled advance and numbers advances', async () => {
    const mock = createMockRetail();
    const owner = (await mock.listCashParties({ kind: 'owner' }))[0]!;
    const a = await mock.createAdvance({ branch_id: branch, party_id: owner.id!, principal_minor: 300000, purpose: 'Rent' }, 'a1');
    const b = await mock.createAdvance({ branch_id: branch, party_id: owner.id!, principal_minor: 100000 }, 'a2');
    expect(a.advance_no).not.toBe(b.advance_no);
    expect(a.balance_minor).toBe(300000);
    expect(await code(mock.createRepayment(a.id!, { amount_minor: 300001, method: 'cash' }, 'p1'))).toBe('repayment_exceeds_balance');
    const part = await mock.createRepayment(a.id!, { amount_minor: 100000, method: 'mobile_money' }, 'p2');
    expect(part.balance_minor).toBe(200000);
    await mock.createRepayment(a.id!, { amount_minor: 200000, method: 'bank' }, 'p3');
    expect(await code(mock.createRepayment(a.id!, { amount_minor: 1, method: 'cash' }, 'p4'))).toBe('advance_settled');
    const open = await mock.listAdvances({ openOnly: true });
    expect(open.some((x) => x.id === a.id)).toBe(false);
    expect(open.some((x) => x.id === b.id)).toBe(true);
    expect((await mock.getAdvance(a.id!)).repayments).toHaveLength(2);
  });

  it('refuses a party that cannot take an advance, and voiding an advance that has repayments', async () => {
    const mock = createMockRetail();
    const vendor = (await mock.listCashParties({ kind: 'supplier' }))[0]!;
    expect(await code(mock.createAdvance({ branch_id: branch, party_id: vendor.id!, principal_minor: 1000 }, 'b1'))).toBe('party_kind_not_allowed');
    const staff = (await mock.listCashParties({ kind: 'staff' }))[0]!;
    const adv = await mock.createAdvance({ branch_id: branch, party_id: staff.id!, principal_minor: 50000 }, 'b2');
    const rep = await mock.createRepayment(adv.id!, { amount_minor: 10000, method: 'cash' }, 'b3');
    expect(await code(mock.voidAdvance(adv.id!, 'Wrong person', 'bv1'))).toBe('advance_has_repayments');
    await mock.voidRepayment(adv.id!, rep.id!, 'Typing error', 'bv2');
    expect((await mock.voidAdvance(adv.id!, 'Wrong person', 'bv3')).voided).toBe(true);
  });

  it('reports outstanding balances by party', async () => {
    const mock = createMockRetail();
    const owner = (await mock.listCashParties({ kind: 'owner' }))[0]!;
    const a = await mock.createAdvance({ branch_id: branch, party_id: owner.id!, principal_minor: 70000 }, 'c1');
    await mock.createRepayment(a.id!, { amount_minor: 20000, method: 'cash' }, 'c2');
    const report = await mock.advancesReport({});
    const row = report.items?.find((p) => p.party_id === owner.id);
    expect(row?.balance_minor).toBe(50000);
    expect(report.balance_minor).toBe(50000);
  });
});
