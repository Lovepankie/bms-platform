import { describe, expect, it } from 'vitest';
import { waivable, waiveStep } from './waive-state';

// The checks of Waive fee or interest (issue #265) before anything is sent. Figures are invented.

const row = { no: 1, interest_outstanding_minor: 120_000, fees_outstanding_minor: 10_000 };

describe('waiveStep', () => {
  it('is ready with an amount up to what is still due and a reason', () => {
    const s = waiveStep({ component: 'interest', amount: '50,000', reason: 'Hardship after a flood' }, row, 'UGX');
    expect(s).toMatchObject({ dueMinor: 120_000, amountMinor: 50_000, ready: true, decimals: false });
  });

  it('caps the amount at what is still due on the chosen component', () => {
    const s = waiveStep({ component: 'fee', amount: '10001', reason: 'Fee charged twice' }, row, 'UGX');
    expect(s.ready).toBe(false);
    expect(s.amountProblem).toBe('At most UGX 10,000 of the fees are still due.');
    expect(waiveStep({ component: 'fee', amount: '10000', reason: 'Fee charged twice' }, row, 'UGX').ready).toBe(true);
  });

  it('asks for an amount and a reason, and refuses a component with nothing due', () => {
    const empty = waiveStep({ component: 'interest', amount: '', reason: ' ' }, row, 'UGX');
    expect(empty.amountProblem).toBe('Enter the amount to waive.');
    expect(empty.reasonProblem).toBe('Say why it is waived.');
    expect(waiveStep({ component: 'interest', amount: '0', reason: 'x' }, row, 'UGX').amountProblem).toMatch(/above zero/);
    const none = waiveStep({ component: 'fee', amount: '1', reason: 'x' }, { ...row, fees_outstanding_minor: 0 }, 'UGX');
    expect(none.amountProblem).toBe('No fees are still due on this item.');
  });

  it('types a two-decimal currency with a point', () => {
    const s = waiveStep({ component: 'interest', amount: '12.50', reason: 'Goodwill' }, { interest_outstanding_minor: 5_000 }, 'KES');
    expect(s).toMatchObject({ amountMinor: 1_250, decimals: true, ready: true });
  });
});

describe('waivable', () => {
  it('offers the action only on a row with fees or interest still due', () => {
    expect(waivable(row)).toBe(true);
    expect(waivable({ interest_outstanding_minor: 0, fees_outstanding_minor: 0 })).toBe(false);
  });
});
