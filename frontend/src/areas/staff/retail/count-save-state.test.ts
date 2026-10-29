// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { changeWords, countKey, readCount, saveWords, unreachable, writeCount, type SavedCount } from './count-save-state';

// Issue #681: the rules of a count kept as it is typed, the words that say where it is kept, and the words of the success
// screen. Fabricated names and amounts.

afterEach(() => localStorage.clear());

const saved: SavedCount = { counts: { 'A-1': '4' }, packCounts: {}, listed: ['A-1'], done: ['A-1'], draftId: 'st-a', stale: ['st-0'] };

describe('the count kept on the device', () => {
  it('keeps one count per business, branch and person', () => {
    expect(countKey('shop.example.test', 'b1', 'u1')).not.toBe(countKey('shop.example.test', 'b2', 'u1'));
    expect(countKey('shop.example.test', 'b1', 'u1')).not.toBe(countKey('shop.example.test', 'b1', 'u2'));
    expect(countKey('shop.example.test', 'b1', 'u1')).not.toBe(countKey('other.example.test', 'b1', 'u1'));
  });

  it('reads back what it wrote', () => {
    expect(writeCount('k', saved)).toBe(true);
    expect(readCount('k')).toEqual(saved);
  });

  it('reads nothing when nothing, or something that is not a count, is stored', () => {
    expect(readCount('k')).toBeNull();
    localStorage.setItem('k', 'not json');
    expect(readCount('k')).toBeNull();
    localStorage.setItem('k', JSON.stringify({ counts: { 'A-1': 4 }, listed: [], done: [] }));
    expect(readCount('k')).toBeNull();
  });
});

describe('the words at the top of the count', () => {
  it('says nothing before a count, Saving while it sends, and All counts saved once the server holds them', () => {
    expect(saveWords(0, true, 'sent')).toBe('');
    expect(saveWords(1, true, 'sending')).toBe('Saving...');
    expect(saveWords(1, true, 'sent')).toBe('All counts saved, 1 item');
    expect(saveWords(12, true, 'sent')).toBe('All counts saved, 12 items');
  });

  it('says the counts are on the phone while offline, and never says saved when the phone could not keep them', () => {
    expect(saveWords(3, true, 'offline')).toBe('Saved on this phone, will send when online');
    expect(saveWords(3, true, 'failed')).toBe('Saved on this phone, 3 items. Will try sending again.');
    expect(saveWords(3, false, 'offline')).toMatch(/^Not saved yet/);
    expect(saveWords(3, false, 'failed')).toMatch(/^Not saved yet/);
  });

  it('uses no dashes', () => {
    const dashes = [String.fromCharCode(0x2013), String.fromCharCode(0x2014), '-'];
    for (const s of ['sending', 'sent', 'offline', 'failed'] as const) {
      for (const d of [true, false]) expect(dashes.some((x) => saveWords(2, d, s).includes(x))).toBe(false);
    }
  });

  it('reads a failed fetch as a lost connection, and an answer from the server as a refusal', () => {
    expect(unreachable(new TypeError('Failed to fetch'))).toBe(true);
    expect(unreachable(new Error('Conflict'))).toBe(false);
  });
});

describe('the success screen words', () => {
  const line = (id: string, description: string, variance: string) => ({ product_id: id, description, committed_variance_qty: variance });

  it('says how many went up, went down and matched, and names each change in plain words', () => {
    const units = new Map([['A-1', 'piece'], ['A-2', 'piece']]);
    const words = changeWords([line('A-1', 'Test Lamp', '2.000'), line('A-2', 'Test Plug', '-1.000'), line('A-3', 'Test Tape', '0.000')], units);
    expect(words.summary).toBe('1 went up, 1 went down, 1 matched.');
    expect(words.changes).toEqual(['Test Lamp: 2 pieces more', 'Test Plug: 1 piece less']);
  });

  it('names the first five changes and counts the rest', () => {
    const lines = Array.from({ length: 7 }, (_, i) => line(`A-${i}`, `Test Item ${i}`, '-1.000'));
    const words = changeWords(lines);
    expect(words.summary).toBe('7 went down.');
    expect(words.changes).toHaveLength(6);
    expect(words.changes[5]).toBe('and 2 more items changed');
  });
});
