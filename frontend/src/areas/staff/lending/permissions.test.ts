import { describe, expect, it } from 'vitest';
import { actionsFor, canReverse, mayDo, showLending } from './permissions';

const READ = 'lending.loans.read';
const officer = { permissions: [READ, 'lending.disbursements.request', 'lending.repayments.create', 'lending.repayments.reverse_request'] };
const viewer = { permissions: [READ] };
const manager = { permissions: [READ, 'lending.loans.write_off_request', 'lending.repayments.reverse_request'] };

describe('lending permission gating', () => {
  it('shows the Loans entry only with lending.loans.read and, when listed, the lending module', () => {
    expect(showLending(viewer)).toBe(true);
    expect(showLending({ permissions: ['lending.repayments.create'] })).toBe(false);
    expect(showLending({ permissions: ['retail.sale.create'] })).toBe(false);
    expect(showLending({ ...viewer, modules: ['retail'] })).toBe(false);
    expect(showLending({ ...viewer, modules: ['lending'] })).toBe(true);
  });

  it('offers each action only with the permission its route declares and in the right status', () => {
    expect(actionsFor(officer, 'approved')).toEqual(['disburse']);
    expect(actionsFor(officer, 'active')).toEqual(['repay', 'payoff']);
    expect(actionsFor(officer, 'written_off')).toEqual(['repay']);
    expect(actionsFor(officer, 'closed')).toEqual([]);
    expect(actionsFor(viewer, 'active')).toEqual(['payoff']);
    expect(actionsFor(viewer, 'approved')).toEqual([]);
    expect(actionsFor(manager, 'active')).toEqual(['payoff', 'write_off']);
    expect(actionsFor({ permissions: [] }, 'active')).toEqual([]);
  });

  it('needs loan read for any action', () => {
    expect(mayDo({ permissions: ['lending.repayments.create'] }, 'repay')).toBe(false);
  });

  it('offers Reverse on a repayment or recovery not yet reversed, with the permission', () => {
    const repayment = { txn_type: 'repayment' };
    expect(canReverse(officer, 'active', repayment)).toBe(true);
    expect(canReverse(officer, 'closed', repayment)).toBe(true);
    expect(canReverse(officer, 'written_off', repayment)).toBe(false);
    expect(canReverse(officer, 'written_off', { txn_type: 'recovery' })).toBe(true);
    expect(canReverse(officer, 'active', { ...repayment, reversed_by_txn_id: 'r1' })).toBe(false);
    expect(canReverse(officer, 'active', { txn_type: 'disbursement' })).toBe(false);
    expect(canReverse(officer, 'active', { txn_type: 'reversal' })).toBe(false);
    expect(canReverse(viewer, 'active', repayment)).toBe(false);
  });
});
