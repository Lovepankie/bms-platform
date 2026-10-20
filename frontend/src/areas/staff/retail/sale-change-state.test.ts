import { describe, expect, it } from 'vitest';
import { MAX_RECEIVED, NOTES, changeDue, readReceived, receivedFor, receivedProblem, takesCash } from './sale-change-state';

// The change math, the reading of what was typed and the validation of the amount received (issue #346). Fabricated amounts.

describe('change due', () => {
  it('is the amount received less the total, in whole shillings', () => {
    expect(changeDue(15500, 20000)).toBe(4500);
    expect(changeDue(15500, 15500)).toBe(0);
    expect(changeDue(1, 50000)).toBe(49999);
  });

  it('is never negative', () => {
    expect(changeDue(15500, 10000)).toBe(0);
  });

  it('stays exact for very large amounts', () => {
    const total = 98_765_432_101;
    expect(changeDue(total, MAX_RECEIVED)).toBe(MAX_RECEIVED - total);
    expect(Number.isSafeInteger(changeDue(0, MAX_RECEIVED))).toBe(true);
  });
});

describe('reading what was typed', () => {
  it('reads whole shillings, with commas or spaces', () => {
    expect(readReceived('20000')).toEqual({ kind: 'ok', amount: 20000 });
    expect(readReceived(' 20,000 ')).toEqual({ kind: 'ok', amount: 20000 });
    expect(readReceived('1 000 000')).toEqual({ kind: 'ok', amount: 1000000 });
    expect(readReceived('0')).toEqual({ kind: 'ok', amount: 0 });
  });

  it('treats nothing typed as empty', () => {
    expect(readReceived('')).toEqual({ kind: 'empty' });
    expect(readReceived('   ')).toEqual({ kind: 'empty' });
  });

  it('refuses a negative, a decimal and anything that is not a number', () => {
    expect(readReceived('-500')).toMatchObject({ kind: 'invalid', why: expect.stringMatching(/negative/) });
    expect(readReceived('+500')).toMatchObject({ kind: 'invalid' });
    expect(readReceived('500.5')).toMatchObject({ kind: 'invalid', why: expect.stringMatching(/whole shillings/) });
    expect(readReceived('500.')).toMatchObject({ kind: 'invalid', why: expect.stringMatching(/whole shillings/) });
    expect(readReceived('abc')).toMatchObject({ kind: 'invalid', why: expect.stringMatching(/numbers only/) });
    expect(readReceived('5e3')).toMatchObject({ kind: 'invalid' });
    expect(readReceived('UGX 500')).toMatchObject({ kind: 'invalid' });
  });

  it('refuses a very large amount instead of overflowing', () => {
    expect(readReceived(String(MAX_RECEIVED))).toEqual({ kind: 'ok', amount: MAX_RECEIVED });
    expect(readReceived(String(MAX_RECEIVED + 1))).toMatchObject({ kind: 'invalid', why: expect.stringMatching(/too large/) });
    expect(readReceived('9'.repeat(40))).toMatchObject({ kind: 'invalid', why: expect.stringMatching(/too large/) });
  });
});

describe('what stops a cash sale', () => {
  it('lets an empty field through, since no change was asked for', () => {
    expect(receivedProblem('cash', 15500, '')).toBeNull();
  });

  it('refuses less than the total, in plain words with the total', () => {
    expect(receivedProblem('cash', 15500, '15499')).toBe('Enter at least the total, UGX 15,500.');
    expect(receivedProblem('cash', 15500, '0')).toBe('Enter at least the total, UGX 15,500.');
  });

  it('accepts the exact total and more', () => {
    expect(receivedProblem('cash', 15500, '15500')).toBeNull();
    expect(receivedProblem('cash', 15500, '50,000')).toBeNull();
  });

  it('refuses an unreadable entry', () => {
    expect(receivedProblem('cash', 15500, '-1')).toMatch(/negative/);
    expect(receivedProblem('cash', 15500, 'x')).toMatch(/numbers only/);
  });

  it('asks nothing of a sale paid any other way', () => {
    for (const method of ['credit', 'mobile_money', 'bank'] as const) {
      expect(takesCash(method)).toBe(false);
      expect(receivedProblem(method, 15500, '100')).toBeNull();
      expect(receivedFor(method, 15500, '20000')).toBeUndefined();
    }
  });

  it('gives the receipt only an amount that covers the total', () => {
    expect(receivedFor('cash', 15500, '20000')).toBe(20000);
    expect(receivedFor('cash', 15500, '100')).toBeUndefined();
    expect(receivedFor('cash', 15500, '')).toBeUndefined();
  });
});

describe('the quick notes', () => {
  it('are the common notes, in order', () => {
    expect(NOTES).toEqual([1000, 2000, 5000, 10000, 20000, 50000]);
  });
});
