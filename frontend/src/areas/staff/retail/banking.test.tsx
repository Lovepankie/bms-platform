import { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it } from 'vitest';
import { businessToday, daysBefore, type Banking, type BankingDay, type BankingExpected } from '../../../api/retail';
import { createMockRetail, setMockProfitAccess } from '../../../api/retail-mock';
import { bankingPrefill } from './banking-state';
import { BankingForm, BankingReportView, BankingResult, ExpectedFigures } from './banking';
import { branch, html, page } from './cashbook-test-utils';

const today = businessToday();
const cashierView: BankingExpected = { branch_id: branch, business_date: today, cash_expected_minor: 150000, banked_so_far_minor: 0 };
const adminView: BankingExpected = { ...cashierView, cash_purchases_minor: 30000, savings_minor: 20000, expected_minor: 100000, banked_so_far_minor: 40000 };
const clientWith = (e: BankingExpected) => {
  const client = new QueryClient();
  client.setQueryData(['retail', 'banking-expected', branch, today], e);
  return client;
};

describe('banking form', () => {
  it('shows a caller without profit read the cash expected and nothing net of restocks or savings', () => {
    const out = html(<ExpectedFigures expected={cashierView} />);
    expect(out).toContain('Cash expected');
    expect(out).toContain('UGX 150,000');
    expect(out).not.toContain('Expected to bank');
    expect(out).not.toContain('Savings set aside');
    expect(out).not.toContain('Cash restocks');
  });

  it('shows the components and the net expected amount only when the server sent them', () => {
    const out = html(<ExpectedFigures expected={adminView} />);
    expect(out).toContain('Cash restocks, at cost');
    expect(out).toContain('UGX 30,000');
    expect(out).toContain('Savings set aside');
    expect(out).toContain('Expected to bank');
    expect(out).toContain('UGX 100,000');
    expect(out).toContain('Already banked today');
  });

  it('prefills the net expected amount less what is banked, or the cash expected for a cashier, never below zero', () => {
    expect(bankingPrefill(adminView)).toBe('60000');
    expect(bankingPrefill(cashierView)).toBe('150000');
    expect(bankingPrefill({ cash_expected_minor: 10000, banked_so_far_minor: 50000 })).toBe('0');
    expect(bankingPrefill(undefined)).toBe('');
    const admin = page('admin', <BankingForm branchId={branch} />, clientWith(adminView));
    expect(admin).toContain('value="60000"');
    expect(page('cashier', <BankingForm branchId={branch} />, clientWith(cashierView))).toContain('value="150000"');
  });

  it('labels every input', () => {
    const out = page('admin', <BankingForm branchId={branch} />, clientWith(adminView));
    for (const id of ['banking-date', 'banking-amount', 'banking-reference']) expect(out).toContain(`for="${id}"`);
  });
});

describe('banking result', () => {
  const saved: Banking = { id: 'b1', business_date: today, amount_minor: 90000 };

  it('shows no flag, difference or warning when the server sent none', () => {
    const out = html(<BankingResult saved={saved} />);
    expect(out).toContain('UGX 90,000');
    expect(out).not.toMatch(/Shortfall|Surplus|Expected to bank|bank balance|more than the cash/);
  });

  it('shows a shortfall, a surplus and the warning in words when present', () => {
    const short = html(<BankingResult saved={{ ...saved, expected_minor: 100000, difference_minor: -10000, flag: 'shortfall' }} />);
    expect(short).toContain('Shortfall: UGX 10,000 less than expected');
    expect(short).toContain('Expected to bank: UGX 100,000');
    expect(html(<BankingResult saved={{ ...saved, difference_minor: 5000, flag: 'surplus', warnings: ['cash_below_banked'] }} />)).toMatch(/Surplus: UGX 5,000 more than expected.*more than the cash on record/);
    expect(html(<BankingResult saved={{ ...saved, flag: 'ok', difference_minor: 0 }} />)).toContain('Matches what was expected');
    expect(html(<BankingResult saved={{ ...saved, flag: 'not_banked' }} />)).toContain('Not banked yet');
  });
});

describe('banking report', () => {
  const cashierDay: BankingDay = {
    branch_id: branch, business_date: today, cash_expected_minor: 150000, banked_minor: 100000,
    entries: [{ id: 'e1', amount_minor: 100000, banked_at: `${today}T08:00:00Z`, by_name: 'Test User 01', voided: false }],
  };
  const adminDay: BankingDay = { ...cashierDay, expected_minor: 120000, difference_minor: -20000, flag: 'shortfall', unbanked_running_minor: 20000 };
  const imported: BankingDay = { branch_id: branch, business_date: daysBefore(today, 100), cash_expected_minor: 320000, banked_minor: 300000, historical: true };

  it('shows what is present for a caller without profit read, with who entered', () => {
    const out = page('cashier', <BankingReportView days={[cashierDay]} />);
    expect(out).toContain('Cash expected');
    expect(out).toContain('Banked');
    expect(out).toContain('by Test User 01');
    expect(out).not.toMatch(/Difference|Unbanked so far|Expected to bank|Shortfall/);
  });

  it('shows expected, difference, flag and the running unbanked total when present', () => {
    const out = page('admin', <BankingReportView days={[adminDay]} />);
    expect(out).toContain('Expected to bank');
    expect(out).toContain('Difference');
    expect(out).toContain('Shortfall: UGX 20,000 less than expected');
    expect(out).toContain('Unbanked so far');
    expect(out).toContain('UGX 20,000');
  });

  it('lists imported days apart and labelled', () => {
    const out = page('admin', <BankingReportView days={[adminDay, imported]} />);
    expect(out).toContain('Days imported from the old app');
    expect(out.indexOf('Days imported from the old app')).toBeGreaterThan(out.indexOf('Unbanked so far'));
    expect(out).toContain('not part of the unbanked total');
  });

  it('says so when there is nothing in the period', () => {
    expect(page('admin', <BankingReportView days={[]} />)).toContain('No cash to bank');
  });
});

describe('banking against the mock', () => {
  beforeEach(() => setMockProfitAccess(true));

  it('gives a cashier no flag, difference, warning or expected figure, and an admin all of them', async () => {
    const mock = createMockRetail();
    setMockProfitAccess(false);
    const date = daysBefore(today, 3);
    const seen = await mock.bankingExpected({ branchId: branch, date });
    expect(seen.cash_expected_minor).toBeDefined();
    for (const k of ['expected_minor', 'savings_minor', 'cash_purchases_minor'] as const) expect(k in seen).toBe(false);
    const own = await mock.createBanking({ branch_id: branch, business_date: date, amount_minor: 1000 }, 'bk1');
    for (const k of ['flag', 'difference_minor', 'expected_minor', 'warnings'] as const) expect(k in own).toBe(false);
    setMockProfitAccess(true);
    const full = await mock.createBanking({ branch_id: branch, business_date: date, amount_minor: 2000 }, 'bk2');
    expect(full.flag).toBeDefined();
    expect(full.expected_minor).toBeDefined();
    expect(full.difference_minor).toBeDefined();
  });

  it('keeps imported days apart in the report and out of the running total', async () => {
    const mock = createMockRetail();
    const report = await mock.bankingReport({ from: daysBefore(today, 120), to: today });
    const old = (report.items ?? []).filter((d) => d.historical);
    expect(old.length).toBeGreaterThan(0);
    expect(old.every((d) => d.unbanked_running_minor === undefined)).toBe(true);
    setMockProfitAccess(false);
    const plain = await mock.bankingReport({ from: daysBefore(today, 120), to: today });
    expect((plain.items ?? []).every((d) => d.expected_minor === undefined && d.flag === undefined && d.unbanked_running_minor === undefined)).toBe(true);
  });
});
