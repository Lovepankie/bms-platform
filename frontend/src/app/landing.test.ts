import { describe, expect, it } from 'vitest';
import { landingCopy } from './landing';

// #99: the landing page follows the tenant's enabled modules, read from GET /api/v1/branding. A
// retail only tenant is never promised members or loans and gets no Member portal button.

const text = (modules: string[]) => {
  const copy = landingCopy(modules);
  return [copy.lead, ...copy.cards.flatMap((c) => [c.title, c.text])].join(' ').toLowerCase();
};

describe('landing copy by enabled modules (#99)', () => {
  it('retail only: sales and stock, no members, no loans, no Member portal', () => {
    const copy = landingCopy(['retail']);
    expect(copy.lead).toBe('Sales and stock for your business, in one place.');
    expect(copy.memberPortal).toBe(false);
    expect(copy.cards.map((c) => c.key)).toEqual(['retail', 'staff']);
    expect(text(['retail'])).not.toMatch(/member|loan|savings/);
  });

  it('lending only: members and loans, the Members card and the Member portal', () => {
    const copy = landingCopy(['lending']);
    expect(copy.lead).toBe('Members and loans for your business, in one place.');
    expect(copy.memberPortal).toBe(true);
    expect(copy.cards.map((c) => c.key)).toEqual(['lending', 'staff', 'members']);
    expect(text(['lending'])).not.toMatch(/sales|stock/);
  });

  it('both: both module cards and the Member portal', () => {
    const copy = landingCopy(['lending', 'retail']);
    expect(copy.lead).toBe('Sales, stock, members and loans for your business, in one place.');
    expect(copy.memberPortal).toBe(true);
    expect(copy.cards.map((c) => c.key)).toEqual(['retail', 'lending', 'staff', 'members']);
  });

  it('none or unknown modules: staff sign-in only, no module promised', () => {
    for (const modules of [[], ['something-new']]) {
      const copy = landingCopy(modules);
      expect(copy.lead).toBe('Your business, in one place.');
      expect(copy.memberPortal).toBe(false);
      expect(copy.cards.map((c) => c.key)).toEqual(['staff']);
    }
  });

  it('the copy names no price, figure or business', () => {
    for (const modules of [['retail'], ['lending'], ['lending', 'retail'], []]) {
      expect(text(modules)).not.toMatch(/\d|ugx|shs|price/);
    }
  });
});
