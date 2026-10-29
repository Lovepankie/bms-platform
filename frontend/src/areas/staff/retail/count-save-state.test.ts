// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { changeWords, countKey, mergeCounts, readCount, saveWords, writeCount, type SavedCount } from './count-save-state';

// Issue #681: the rules of a count kept on the device as it is typed, the merge of two tabs, the words that say where it is
// kept, and the words of the success screen. Fabricated names and amounts.

afterEach(() => localStorage.clear());

const saved: SavedCount = { counts: { 'A-1': '4' }, packCounts: {}, listed: ['A-1'], done: ['A-1'], at: { 'A-1': 100 } };

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

describe('two tabs of one count', () => {
  const tab = (counts: Record<string, string>, at: Record<string, number>, done = Object.keys(counts)): SavedCount =>
    ({ counts, packCounts: {}, listed: Object.keys(counts), done, at });

  it('takes the newer count of each item, never the whole sheet of one tab', () => {
    const mine = tab({ 'A-1': '4', 'A-2': '6' }, { 'A-1': 300, 'A-2': 100 });
    const theirs = tab({ 'A-2': '9', 'A-3': '2' }, { 'A-2': 200, 'A-3': 150 });
    const merged = mergeCounts(mine, theirs);
    expect(merged.counts).toEqual({ 'A-1': '4', 'A-2': '9', 'A-3': '2' });
    expect(merged.listed).toEqual(['A-1', 'A-2', 'A-3']);
    expect(merged.done).toEqual(['A-1', 'A-2', 'A-3']);
  });

  it('keeps an item emptied in the newer tab empty, and out of the counted ones', () => {
    const merged = mergeCounts(tab({ 'A-1': '4' }, { 'A-1': 100 }), tab({ 'A-1': '' }, { 'A-1': 200 }, []));
    expect(merged.counts['A-1']).toBe('');
    expect(merged.done).toEqual([]);
  });

  it('merges the same way from either side', () => {
    const a = tab({ 'A-1': '4', 'A-2': '6' }, { 'A-1': 300, 'A-2': 100 });
    const b = tab({ 'A-1': '5', 'A-2': '7' }, { 'A-1': 200, 'A-2': 400 });
    expect(mergeCounts(a, b).counts).toEqual(mergeCounts(b, a).counts);
  });
});

describe('the words at the top of the count', () => {
  it('says nothing before a count, and how many items are saved on this phone', () => {
    expect(saveWords(0, true)).toBe('');
    expect(saveWords(1, true)).toBe('Saved on this phone, 1 item');
    expect(saveWords(12, true)).toBe('Saved on this phone, 12 items');
  });

  it('never says saved when the phone could not keep the counts', () => {
    expect(saveWords(3, false)).toBe('Not saved on this phone. Keep this page open.');
  });

  it('uses no dashes', () => {
    const dashes = [String.fromCharCode(0x2013), String.fromCharCode(0x2014), '-'];
    for (const d of [true, false]) expect(dashes.some((x) => saveWords(2, d).includes(x))).toBe(false);
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
