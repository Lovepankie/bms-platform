import { describe, expect, it } from 'vitest';
import { canReverseSavings, mayManageProducts, mayOpenAccounts, savingsActionsFor, showSavings } from './savings-permissions';

const READ = 'lending.savings.read';
const cashier = { permissions: [READ, 'lending.savings.deposit', 'lending.savings.withdraw'] };
const officer = { permissions: [READ, 'lending.savings.open'] };
const manager = { permissions: [READ, 'lending.savings.deposit', 'lending.savings.withdraw', 'lending.savings.withdraw_approve'] };
const auditor = { permissions: [READ] };

describe('savings permission gating', () => {
  it('shows the Savings entry only with lending.savings.read and, when listed, the lending module', () => {
    expect(showSavings(auditor)).toBe(true);
    expect(showSavings({ permissions: ['lending.savings.deposit'] })).toBe(false);
    expect(showSavings({ ...auditor, modules: ['retail'] })).toBe(false);
  });

  it('offers money out only on an active account, deposits also on dormant and frozen ones', () => {
    expect(savingsActionsFor(cashier, 'active')).toEqual(['deposit', 'withdraw', 'close']);
    expect(savingsActionsFor(cashier, 'dormant')).toEqual(['deposit']);
    expect(savingsActionsFor(cashier, 'frozen')).toEqual(['deposit']);
    expect(savingsActionsFor(cashier, 'closed')).toEqual([]);
    expect(savingsActionsFor(auditor, 'active')).toEqual([]);
  });

  it('leaves freeze, unfreeze and reactivation to holders of lending.savings.withdraw_approve', () => {
    expect(savingsActionsFor(manager, 'active')).toEqual(['deposit', 'withdraw', 'freeze', 'close']);
    expect(savingsActionsFor(manager, 'dormant')).toEqual(['deposit', 'reactivate', 'freeze']);
    expect(savingsActionsFor(manager, 'frozen')).toEqual(['deposit', 'unfreeze']);
  });

  it('opens accounts and manages products only with their own permissions', () => {
    expect(mayOpenAccounts(officer)).toBe(true);
    expect(mayOpenAccounts(cashier)).toBe(false);
    expect(mayManageProducts(manager)).toBe(false);
    expect(mayManageProducts({ permissions: ['lending.savings_products.manage'] })).toBe(true);
  });

  it('offers reversal of a deposit or withdrawal not yet reversed, never of interest', () => {
    expect(canReverseSavings(cashier, 'active', { txn_type: 'deposit' })).toBe(true);
    expect(canReverseSavings(cashier, 'active', { txn_type: 'withdrawal' })).toBe(true);
    expect(canReverseSavings(cashier, 'active', { txn_type: 'interest' })).toBe(false);
    expect(canReverseSavings(cashier, 'active', { txn_type: 'deposit', reversed_by_txn_id: 'x' })).toBe(false);
    expect(canReverseSavings(cashier, 'closed', { txn_type: 'deposit' })).toBe(false);
    expect(canReverseSavings(officer, 'active', { txn_type: 'deposit' })).toBe(false);
  });
});
