// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { changeWords, countKey, mergeCounts, otherTabWords, readCount, savedAt, saveWords, whenSaved, writeCount, type SavedCount } from './count-save-state';

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

  it('reads back what it wrote, the commit time included', () => {
    expect(writeCount('k', saved)).toBe(true);
    expect(readCount('k')).toEqual(saved);
    writeCount('k', { ...saved, committed: 500 });
    expect(readCount('k')?.committed).toBe(500);
  });

  it('reads nothing when nothing, or something that is not a count, is stored', () => {
    expect(readCount('k')).toBeNull();
    localStorage.setItem('k', 'not json');
    expect(readCount('k')).toBeNull();
    localStorage.setItem('k', JSON.stringify({ counts: { 'A-1': 4 }, listed: [], done: [] }));
    expect(readCount('k')).toBeNull();
  });

  it('dates a kept count from its last saved count, and has no date while nothing is counted', () => {
    const kept = (counts: Record<string, string>, at: Record<string, number>): SavedCount => ({ counts, packCounts: {}, listed: [], done: [], at });
    expect(savedAt(kept({ 'A-1': '4', 'A-2': '6', 'A-3': '' }, { 'A-1': 300, 'A-2': 200, 'A-3': 900 }))).toBe(300);
    expect(savedAt(kept({ 'A-1': '' }, { 'A-1': 50 }))).toBeNull();
    expect(savedAt(null)).toBeNull();
  });

  // Owner, round 3 of the review of #686: the day in the business's zone, so a count saved late last night is an earlier day.
  it('says the day and time a count was saved, and whether it is from an earlier business day', () => {
    const now = Date.parse('2026-10-29T10:00:00Z'); // Thursday 13:00 in Kampala.
    expect(whenSaved(Date.parse('2026-10-29T06:05:00Z'), 'Africa/Kampala', now)).toEqual({ day: 'Thursday', time: '09:05', earlier: false });
    expect(whenSaved(Date.parse('2026-10-26T11:05:00Z'), 'Africa/Kampala', now)).toEqual({ day: 'Monday', time: '14:05', earlier: true });
    // 23:30 on Wednesday in UTC is already Thursday in Kampala.
    expect(whenSaved(Date.parse('2026-10-28T23:30:00Z'), 'Africa/Kampala', now).earlier).toBe(false);
    expect(whenSaved(Date.parse('2026-10-10T11:05:00Z'), 'Africa/Kampala', now)).toEqual({ day: '10 Oct', time: '14:05', earlier: true });
  });

  it('words what another tab did', () => {
    expect(otherTabWords({ kind: 'partly', n: 2 })).toBe('2 counts were saved from another tab; your other counts are still here');
    expect(otherTabWords({ kind: 'partly', n: 1 })).toBe('1 count was saved from another tab; your other counts are still here');
    expect(otherTabWords({ kind: 'committed' })).toBe('Counted and saved in another tab');
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

  // Review of #686, round 3: a commit in one tab drops only the items it committed from the other.
  it('drops an item the newer tab committed from the found items too, keeps a newer count of this tab, and keeps the commit time', () => {
    const mine = tab({ 'A-1': '4', 'A-2': '6' }, { 'A-1': 100, 'A-2': 500 });
    const theirs: SavedCount = { counts: {}, packCounts: {}, listed: [], done: [], at: { 'A-1': 300, 'A-2': 300 }, committed: 300 };
    const merged = mergeCounts(mine, theirs);
    expect(merged.counts).toEqual({ 'A-2': '6' });
    expect(merged.listed).toEqual(['A-2']);
    expect(merged.done).toEqual(['A-2']);
    expect(merged.committed).toBe(300);
  });

  it('keeps an item found and not typed in either tab', () => {
    const merged = mergeCounts({ ...tab({}, {}), listed: ['A-9'] }, tab({ 'A-1': '4' }, { 'A-1': 100 }));
    expect(merged.listed).toEqual(['A-9', 'A-1']);
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
