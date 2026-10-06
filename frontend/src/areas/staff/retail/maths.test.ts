import { describe, expect, it } from 'vitest';
import { lineTotalMinor, milliOf, parseCount, parseQty, qtyString, showPercent, showQty } from './maths';

describe('retail quantity maths', () => {
  it('parses quantities with up to three places', () => {
    expect(parseQty('3')).toBe(3000);
    expect(parseQty('3.5')).toBe(3500);
    expect(parseQty('0.001')).toBe(1);
    expect(parseQty('0')).toBeNull();
    expect(parseQty('1.2345')).toBeNull();
    expect(parseQty('-1')).toBeNull();
    expect(parseQty('x')).toBeNull();
  });

  it('allows zero only for counts', () => {
    expect(parseCount('0')).toBe(0);
    expect(parseCount('0.000')).toBe(0);
    expect(parseCount('2')).toBe(2000);
    expect(parseCount('')).toBeNull();
  });

  it('formats for the wire and for people', () => {
    expect(qtyString(3500)).toBe('3.500');
    expect(qtyString(-2000)).toBe('-2.000');
    expect(qtyString(5)).toBe('0.005');
    expect(showQty('3.500')).toBe('3.5');
    expect(showQty('12.000')).toBe('12');
    expect(showQty('12')).toBe('12');
    expect(milliOf('-2.500')).toBe(-2500);
  });

  it('multiplies price by quantity exactly in integer minor units, half up', () => {
    expect(lineTotalMinor(6000, 3000)).toBe(18000);
    expect(lineTotalMinor(1001, 500)).toBe(501);
    expect(lineTotalMinor(3, 500)).toBe(2);
    expect(lineTotalMinor(9_000_000_000, 9_000_000)).toBe(81_000_000_000_000);
  });
});

describe('percent from basis points', () => {
  it('shows up to two decimals, no trailing zeros, and n/a when there is none', () => {
    expect(showPercent(4872)).toBe('48.72%');
    expect(showPercent(5000)).toBe('50%');
    expect(showPercent(3333)).toBe('33.33%');
    expect(showPercent(5)).toBe('0.05%');
    expect(showPercent(-250)).toBe('-2.5%');
    expect(showPercent(0)).toBe('0%');
    expect(showPercent(undefined)).toBe('n/a');
  });
});
