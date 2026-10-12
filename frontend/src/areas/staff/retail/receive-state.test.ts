import { describe, expect, it } from 'vitest';
import type { TransferLine } from '../../../api/retail';
import { allArrived, buildReceive, everythingArrived, receiveLineHint, receiveLineInvalid, receiveProblem, waiting } from './receive-state';

const lines: TransferLine[] = [
  { line_no: 1, product_id: 'p1', code: 'P001', description: 'Cable roll', qty: '4.000' },
  { line_no: 2, product_id: 'p2', code: 'P002', description: 'Socket', qty: '10.500' },
];

describe('receive form (ADR-033, transfer_receipt)', () => {
  it('starts as everything that was sent, and then needs no lines in the request', () => {
    const arrived = everythingArrived(lines);
    expect(arrived).toEqual({ p1: '4', p2: '10.5' });
    expect(allArrived(lines, arrived)).toBe(true);
    expect(buildReceive(lines, arrived)).toBeUndefined();
    expect(receiveProblem(lines, arrived)).toBeNull();
  });

  it('writes less than was sent as a shortage, in plain words, and sends every line with three places', () => {
    const arrived = { p1: '3', p2: '10.5' };
    expect(receiveLineHint('4.000', '3')).toBe('1 short. The difference is written off as a loss.');
    expect(receiveLineHint('4.000', '4')).toBeNull();
    expect(receiveProblem(lines, arrived)).toBeNull();
    expect(buildReceive(lines, arrived)).toEqual([
      { product_id: 'p1', qty: '3.000' },
      { product_id: 'p2', qty: '10.500' },
    ]);
  });

  it('allows none at all, and refuses more than was sent or something that is not a quantity', () => {
    expect(receiveLineInvalid('4.000', '0')).toBe(false);
    expect(receiveLineHint('4.000', '0')).toBe('4 short. The difference is written off as a loss.');
    expect(receiveLineInvalid('4.000', '4.001')).toBe(true);
    expect(receiveLineHint('4.000', '4.001')).toBe('Only 4 were sent.');
    expect(receiveLineInvalid('4.000', '')).toBe(true);
    expect(receiveLineHint('4.000', 'abc')).toBe('Enter how many arrived, or 0 if none did.');
    expect(receiveProblem(lines, { p1: '5', p2: '1' })).toBe('Fix the items marked above.');
    expect(buildReceive(lines, { p1: '0', p2: '0' })).toEqual([
      { product_id: 'p1', qty: '0.000' },
      { product_id: 'p2', qty: '0.000' },
    ]);
  });

  it('says how long a transfer has been on its way', () => {
    expect(waiting(0)).toBe('sent today');
    expect(waiting(undefined)).toBe('sent today');
    expect(waiting(1)).toBe('sent yesterday');
    expect(waiting(5)).toBe('on its way for 5 days');
  });
});
