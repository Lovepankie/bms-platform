import { describe, expect, it } from 'vitest';
import { formatMinor } from './money';

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
