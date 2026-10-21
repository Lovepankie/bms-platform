import { describe, expect, it } from 'vitest';
import { parseLevel, showLevel } from './stock-minimum';

// The low-stock level as a person types it and as the screen shows it (issue #412). Fabricated quantities.

describe('the low-stock level', () => {
  it('takes a quantity above zero with at most three places', () => {
    expect(parseLevel('10')).toBe('10');
    expect(parseLevel(' 2,5 ')).toBe('2.5');
    expect(parseLevel('0.125')).toBe('0.125');
    expect(parseLevel('0')).toBeNull();
    expect(parseLevel('1.2345')).toBeNull();
    expect(parseLevel('-3')).toBeNull();
    expect(parseLevel('ten')).toBeNull();
  });

  it('shows the wire form without trailing zeros', () => {
    expect(showLevel('10.000')).toBe('10');
    expect(showLevel('2.500')).toBe('2.5');
    expect(showLevel('100')).toBe('100');
    expect(showLevel(null)).toBe('');
  });
});
