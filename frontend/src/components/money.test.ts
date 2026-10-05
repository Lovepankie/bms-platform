import { describe, expect, it } from 'vitest';
import { formatMinor, parseMinor } from './money';

describe('formatMinor', () => {
  it('formats UGX, which has no minor unit', () => {
    expect(formatMinor(1500000, 'UGX')).toBe('UGX 1,500,000');
  });

  it('formats two-decimal currencies without floating point', () => {
    expect(formatMinor(123456, 'KES')).toBe('KES 1,234.56');
    expect(formatMinor(5, 'USD')).toBe('USD 0.05');
    expect(formatMinor(-250, 'USD')).toBe('-USD 2.50');
  });

  it('refuses non-integer amounts and unknown currencies', () => {
    expect(() => formatMinor(1.5, 'UGX')).toThrow();
    expect(() => formatMinor(100, 'XYZ')).toThrow();
  });
});

describe('parseMinor', () => {
  it('reads whole UGX and refuses decimals', () => {
    expect(parseMinor('12,000', 'UGX')).toBe(12000);
    expect(parseMinor(' 500 ', 'UGX')).toBe(500);
    expect(parseMinor('12.5', 'UGX')).toBeNull();
    expect(parseMinor('', 'UGX')).toBeNull();
    expect(parseMinor('-3', 'UGX')).toBeNull();
    expect(parseMinor('abc', 'UGX')).toBeNull();
  });

  it('reads two-decimal currencies exactly', () => {
    expect(parseMinor('12.5', 'USD')).toBe(1250);
    expect(parseMinor('0.05', 'USD')).toBe(5);
    expect(parseMinor('1.234', 'USD')).toBeNull();
  });
});
