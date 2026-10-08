import { beforeEach, describe, expect, it } from 'vitest';
import { businessToday, daysBefore, type CashDailyRow } from '../../../api/retail';
import { createMockRetail, setMockProfitAccess } from '../../../api/retail-mock';
import { DaysList } from './cash-summary';
import { showDate } from './transfers';
import { branch, page } from './cashbook-test-utils';

const today = businessToday();
// What a cashier is sent: takings, voids and the figures before restock cost and savings.
const cashierRow: CashDailyRow = {
  branch_id: branch, business_date: today, cash_takings_minor: 200000, cash_sale_voids_minor: 10000, expense_voids_minor: 0, advance_voids_minor: 0, repayment_voids_minor: 0,
  banking_voids_minor: 0, withdrawal_voids_minor: 0, expenses_minor: 30000, advances_out_minor: 0, repayments_in_minor: 5000, withdrawals_in_minor: 0, banked_minor: 100000,
  cash_expected_minor: 165000, ledger_basis: true, historical: false,
};
const adminRow: CashDailyRow = {
  ...cashierRow, opening_minor: 50000, closing_minor: 80000, other_movements_minor: 0, cash_purchases_minor: 20000, savings_minor: 25000, savings_voids_minor: 4000,
  expected_to_bank_minor: 120000, unbanked_running_minor: 20000, daily_profit_minor: 60000,
};

describe('cash summary', () => {
  it('shows a cashier the lines the server sent and none of the figures it left out', () => {
    const out = page('cashier', <DaysList rows={[cashierRow]} />);
    expect(out).toContain('Cash takings');
    expect(out).toContain('UGX 200,000');
    expect(out).toContain('Cash sales voided');
    expect(out).toContain('Expenses');
    expect(out).toContain('Advances paid out');
    expect(out).toContain('Advance repayments received');
    expect(out).toContain('Withdrawals from the bank');
    expect(out).toContain('Banked');
    expect(out).toContain('Cash expected');
    for (const hidden of ['Opening cash', 'Closing cash', 'Other movements', 'Cash restocks', 'Savings set aside', 'Expected to bank', 'Unbanked so far', 'Profit for the day', 'Savings voided']) {
      expect(out).not.toContain(hidden);
    }
  });

  it('shows the extra lines only when present', () => {
    const out = page('admin', <DaysList rows={[adminRow]} />);
    for (const shown of ['Opening cash', 'Closing cash', 'Other movements', 'Cash restocks, at cost', 'Savings set aside', 'Expected to bank', 'Unbanked so far', 'Profit for the day', 'Savings voided']) {
      expect(out).toContain(shown);
    }
    expect(out).toContain('UGX 120,000');
  });

  it('leaves out void lines of zero and shows a day newest first', () => {
    const older: CashDailyRow = { ...cashierRow, business_date: daysBefore(today, 2) };
    const out = page('cashier', <DaysList rows={[older, cashierRow]} />);
    expect(out).not.toContain('Expenses voided');
    expect(out).not.toContain('Banking voided');
    expect(out.indexOf(showDate(today))).toBeGreaterThan(-1);
    expect(out.indexOf(showDate(today))).toBeLessThan(out.indexOf(showDate(daysBefore(today, 2))));
  });

  it('labels a day that did not come from the books as imported', () => {
    const imported: CashDailyRow = { ...cashierRow, business_date: daysBefore(today, 100), ledger_basis: false, historical: true };
    const out = page('cashier', <DaysList rows={[imported, cashierRow]} />);
    expect(out).toContain('Imported from the old app');
    expect((out.match(/Imported from the old app/g) ?? []).length).toBe(1);
  });

  it('says so when there is nothing in the period', () => {
    expect(page('cashier', <DaysList rows={[]} />)).toContain('No cash recorded');
  });
});

describe('cash summary against the mock', () => {
  beforeEach(() => setMockProfitAccess(true));

  it('sends a cashier no figure that embeds savings, and an admin all of them; imported days are marked', async () => {
    const mock = createMockRetail();
    const from = daysBefore(today, 120);
    const admin = await mock.cashDaily({ from, to: today });
    const live = (admin.items ?? []).filter((r) => r.ledger_basis);
    expect(live.length).toBeGreaterThan(0);
    expect(live.every((r) => r.expected_to_bank_minor !== undefined && r.daily_profit_minor !== undefined && r.opening_minor !== undefined)).toBe(true);
    expect((admin.items ?? []).some((r) => r.ledger_basis === false && r.historical === true)).toBe(true);
    setMockProfitAccess(false);
    const cashier = await mock.cashDaily({ from, to: today });
    const hidden = ['opening_minor', 'closing_minor', 'other_movements_minor', 'cash_purchases_minor', 'savings_minor', 'savings_voids_minor', 'expected_to_bank_minor', 'unbanked_running_minor', 'daily_profit_minor'] as const;
    expect((cashier.items ?? []).every((r) => hidden.every((k) => !(k in r)))).toBe(true);
    expect((cashier.items ?? []).every((r) => r.cash_expected_minor !== undefined)).toBe(true);
  });
});
