// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import type { ControlsGroup, ControlsPattern } from '../../../api/retail-controls';
import {
  CONTROLS_LEVELS, approverWords, canOpen, chainFor, csvField, isStockMetric, csvOf, dayWords, daysIn, groupLine, groupTitle, groupsCsv, orderedCards, patternSentence, periodRange, previousWords, rateWords,
  readControlsChain, recordsCsv, selectGroup, shareWords, trendWords, whoWords, writeControlsChain,
} from './controls-state';

// The rules behind the Controls page (issue #357). Fabricated names and amounts.

describe('the days of a period', () => {
  const none = { from: '', to: '' };
  it('is today, Monday to today, the 1st to today, or the days typed', () => {
    // 2026-10-21 is a Wednesday.
    expect(periodRange('today', '2026-10-21', none)).toEqual({ from: '2026-10-21', to: '2026-10-21' });
    expect(periodRange('week', '2026-10-21', none)).toEqual({ from: '2026-10-19', to: '2026-10-21' });
    expect(periodRange('week', '2026-10-19', none)).toEqual({ from: '2026-10-19', to: '2026-10-19' });
    // A Sunday belongs to the week that began on the Monday before.
    expect(periodRange('week', '2026-10-25', none)).toEqual({ from: '2026-10-19', to: '2026-10-25' });
    expect(periodRange('month', '2026-10-21', none)).toEqual({ from: '2026-10-01', to: '2026-10-21' });
    expect(periodRange('custom', '2026-10-21', { from: '2026-09-01', to: '2026-09-30' })).toEqual({ from: '2026-09-01', to: '2026-09-30' });
  });

  it('counts both ends and says what the trend is set against', () => {
    expect(daysIn({ from: '2026-10-21', to: '2026-10-21' })).toBe(1);
    expect(daysIn({ from: '2026-10-19', to: '2026-10-25' })).toBe(7);
    expect(previousWords({ from: '2026-10-21', to: '2026-10-21' })).toBe('the day before');
    expect(previousWords({ from: '2026-10-19', to: '2026-10-25' })).toBe('the 7 days before');
    expect(dayWords('2026-10-19')).toBe('Monday 19 October 2026');
  });
});

describe('the words of a card', () => {
  const before = 'the 7 days before';
  it('says up, down, the same, or none, always against the days before', () => {
    expect(trendWords(0, 0, before)).toBe('None, and none in the 7 days before');
    expect(trendWords(4, 0, before)).toBe('Up: 4, none in the 7 days before');
    expect(trendWords(5, 5, before)).toBe('Same as in the 7 days before (5)');
    expect(trendWords(8, 5, before)).toBe('Up 3, from 5 in the 7 days before');
    expect(trendWords(2, 7, before)).toBe('Down 5, from 7 in the 7 days before');
  });

  it('gives a rate per 100 sales, or says there was nothing to set it against', () => {
    expect(rateWords('2.5')).toBe('2.5 per 100 sales');
    expect(rateWords(undefined)).toBe('No sales to compare with');
    expect(shareWords(125)).toBe('1.25% of the value of sales');
    expect(shareWords(undefined)).toBe('');
  });

  it('draws the cards in the fixed order and leaves out one the server did not send', () => {
    const cards = [{ metric: 'discounts' }, { metric: 'voided_sales' }, { metric: 'returns' }];
    expect(orderedCards(cards).map((c) => c.metric)).toEqual(['voided_sales', 'returns', 'discounts']);
    expect(orderedCards(undefined)).toEqual([]);
  });
});

describe('a group of a table', () => {
  const user = (over: Partial<ControlsGroup> = {}): ControlsGroup => ({ key: 'u1', label: 'Test Seller One', user_status: 'active', count: 5, sales_affected: 5, value_minor: 1500, sales_count: 18, rate_per_100: '27.8', ...over });

  it('names a deactivated person and one who has not signed in yet', () => {
    expect(groupTitle('user', user())).toBe('Test Seller One');
    expect(groupTitle('user', user({ user_status: 'deactivated' }))).toBe('Test Seller One (deactivated)');
    expect(groupTitle('user', user({ user_status: 'invited' }))).toBe('Test Seller One (not yet signed in)');
    expect(groupTitle('day', { key: '2026-10-19', label: '2026-10-19', day: '2026-10-19' })).toBe('Monday 19 October 2026');
    expect(whoWords({ user_name: 'Test Seller Two', user_status: 'deactivated' })).toBe('Test Seller Two (deactivated)');
    expect(whoWords({})).toBe('Unknown person');
  });

  it('writes the count, the value and the rate on one line, lines with the sales they are in', () => {
    const money = (m: number) => `UGX ${m}`;
    expect(groupLine(user(), 'voided_sales', 'user', money)).toBe('5 sales, UGX 1500, 27.8 per 100 sales');
    expect(groupLine(user({ count: 1, rate_per_100: undefined }), 'voided_sales', 'user', money)).toBe('1 sale, UGX 1500, no sales of their own to compare with');
    expect(groupLine(user({ count: 3, sales_affected: 2 }), 'price_overrides', 'branch', money)).toBe('3 lines in 2 sales, UGX 1500, 27.8 per 100 sales');
    expect(groupLine(user({ count: 1, sales_affected: 1 }), 'returns', 'branch', money)).toBe('1 return, UGX 1500, 27.8 per 100 sales');
    // An approver entered no sales of their own: no rate and no apology for one.
    expect(groupLine(user({ count: 6, rate_per_100: undefined }), 'voided_sales', 'approver', money)).toBe('6 sales, UGX 1500');
  });

  it('shows a group the server could not name but does not open it, and names an unknown person once', () => {
    expect(canOpen('user', { key: 'u1' })).toBe(true);
    expect(canOpen('user', { key: '' })).toBe(false);
    expect(canOpen('branch', {})).toBe(false);
    expect(canOpen('approver', { key: '' })).toBe(false);
    // The reason left blank is a real group: its key is empty on purpose.
    expect(canOpen('reason', { key: '' })).toBe(true);
    expect(groupTitle('user', { key: '', count: 1 })).toBe('Unknown person');
    expect(groupTitle('approver', { key: 'u9', label: 'Test Admin One', user_status: 'deactivated' })).toBe('Test Admin One (deactivated)');
  });

  it('leaves the Approver level out for the lines of a sale, which have none, and is never empty', () => {
    expect(chainFor('voided_sales', ['branch', 'approver'])).toEqual(['branch', 'approver']);
    expect(chainFor('price_overrides', ['branch', 'approver'])).toEqual(['branch']);
    expect(chainFor('below_cost', ['approver'])).toEqual(['branch']);
  });

  it('counts a restock cancelled and a quantity corrected with no rate, and a correction has no approver', () => {
    const money = (m: number) => `UGX ${m}`;
    expect(isStockMetric('restock_voids')).toBe(true);
    expect(isStockMetric('voided_sales')).toBe(false);
    expect(groupLine(user({ count: 2, rate_per_100: undefined }), 'restock_voids', 'user', money)).toBe('2 restocks, UGX 1500');
    expect(groupLine(user({ count: 1, rate_per_100: undefined }), 'quantity_corrections', 'user', money)).toBe('1 correction, UGX 1500');
    expect(chainFor('quantity_corrections', ['branch', 'approver'])).toEqual(['branch']);
    expect(chainFor('restock_voids', ['branch', 'approver'])).toEqual(['branch', 'approver']);
    expect(approverWords({ approver_id: 'u9', approver_name: 'Test Admin One' }, 'restock_voids')).toBe('Voided by Test Admin One');
  });

  it('names who cancelled or took the goods back, and nobody for a price', () => {
    const r = { approver_id: 'u9', approver_name: 'Test Admin One', approver_status: 'active' };
    expect(approverWords(r, 'voided_sales')).toBe('Cancelled by Test Admin One');
    expect(approverWords(r, 'returns')).toBe('Taken back by Test Admin One');
    expect(approverWords({ ...r, approver_status: 'deactivated' }, 'voided_sales')).toBe('Cancelled by Test Admin One (deactivated)');
    expect(approverWords({}, 'price_overrides')).toBe('');
  });

  it('passes what was opened to the level under it; a day narrows to that day', () => {
    expect(selectGroup({}, 'branch', { key: 'b1' })).toEqual({ branchId: 'b1' });
    expect(selectGroup({ branchId: 'b1' }, 'user', { key: 'u1' })).toEqual({ branchId: 'b1', userId: 'u1' });
    expect(selectGroup({}, 'reason', { key: 'wrong item' })).toEqual({ reason: 'wrong item' });
    expect(selectGroup({ branchId: 'b1' }, 'approver', { key: 'u9' })).toEqual({ branchId: 'b1', approverId: 'u9' });
    expect(selectGroup({ userId: 'u1' }, 'day', { key: '2026-10-19', day: '2026-10-19' })).toEqual({ userId: 'u1', from: '2026-10-19', to: '2026-10-19' });
  });
});

describe('a repeat pattern in plain words', () => {
  const base: ControlsPattern = { subject: 'user', key: 'u1', name: 'Test Seller One', user_status: 'active', metric: 'voided_sales', count: 5, out_of: 13, rate_per_100: '38.5', shop_rate_per_100: '14.0', times: '2.8' };
  it('says whose sales they were, out of how many, and how many times the shop', () => {
    expect(patternSentence(base, 'Test Branch Gayaza')).toBe("5 of the 13 sales Test Seller One entered at Test Branch Gayaza were cancelled (38.5 per 100). The shop's own is 14.0, so 2.8 times.");
    expect(patternSentence({ ...base, user_status: 'deactivated', metric: 'returns' }, 'Test Branch Gayaza')).toContain('5 of the 13 sales Test Seller One (deactivated) entered at Test Branch Gayaza had goods taken back');
    expect(patternSentence({ ...base, metric: 'price_overrides' }, 'Test Branch Gayaza')).toContain('had a price changed');
    expect(patternSentence({ ...base, metric: 'below_cost' }, 'Test Branch Gayaza')).toContain('were sold below cost');
  });

  it('says it of the lines of an item', () => {
    const item: ControlsPattern = { ...base, subject: 'product', name: 'Test Cable 2m', user_status: undefined, out_of: 20 };
    expect(patternSentence(item, 'Test Branch Gayaza')).toContain('5 of the 20 lines of Test Cable 2m sold at Test Branch Gayaza were on cancelled sales');
    expect(patternSentence({ ...item, metric: 'below_cost' }, 'Test Branch Gayaza')).toContain('had a price under cost');
  });
});

describe('the CSV of what is on screen', () => {
  it('quotes commas, quotes and line breaks', () => {
    expect(csvField('plain')).toBe('plain');
    expect(csvField('a, b')).toBe('"a, b"');
    expect(csvField('say "hi"')).toBe('"say ""hi"""');
    expect(csvField('two\nlines')).toBe('"two\nlines"');
    expect(csvField(1500)).toBe('1500');
    expect(csvField(undefined)).toBe('');
  });

  it('turns a text a spreadsheet would run as a formula into plain text, but leaves a number alone', () => {
    expect(csvField('=1+1')).toBe("'=1+1");
    expect(csvField('+256700000001')).toBe("'+256700000001");
    expect(csvField('-cmd')).toBe("'-cmd");
    expect(csvField('@sum')).toBe("'@sum");
    expect(csvField(-5)).toBe('-5');
  });

  it('writes the header and one line a group, with the deactivated person named', () => {
    const rows: ControlsGroup[] = [{ key: 'u2', label: 'Test Seller Two', user_status: 'deactivated', count: 1, sales_affected: 1, value_minor: 300, sales_count: 30, rate_per_100: '3.3' }];
    expect(groupsCsv('user', rows)).toBe('Person,Count,Sales with it,Value,Sales entered,Per 100 sales\r\nTest Seller Two (deactivated),1,1,300,30,3.3\r\n');
    expect(csvOf([['a', 'b']])).toBe('a,b\r\n');
  });

  it('writes the stock cards with their own headers, no sales columns and no rate', () => {
    const rows: ControlsGroup[] = [{ key: 'u1', label: 'Test Clerk One', count: 2, value_minor: 7500 }];
    expect(groupsCsv('user', rows, 'restock_voids')).toBe('Person,Count,Value\r\nTest Clerk One,2,7500\r\n');
    const rec = recordsCsv([{ sale_no: 'P-1', at: '2026-10-19T09:15:00Z', branch_name: 'Test Branch Gayaza', user_name: 'Test Clerk One', approver_id: 'u9', approver_name: 'Test Admin One', reason: 'Test wrong supplier', value_minor: 7500 }], 'restock_voids');
    expect(rec.split('\r\n')[0]).toBe('Restock,Time,Shop,Entered by,Approver,Reason,Item,Value');
    expect(rec).toContain('Voided by Test Admin One');
    expect(recordsCsv([], 'quantity_corrections').split('\r\n')[0]).toBe('Record,Time,Shop,Corrected by,Approver,Reason,Item,Value');
  });

  it('writes the records with the time as people read it, the person whose sale it was and who cancelled it', () => {
    const text = recordsCsv([{ sale_no: 'S-1', at: '2026-10-19T09:15:00Z', branch_name: 'Test Branch Gayaza', user_name: 'Test Seller One', user_status: 'active', approver_id: 'u9', approver_name: 'Test Admin One', approver_status: 'active', reason: 'Test wrong item', value_minor: 300 }], 'voided_sales');
    expect(text).toBe('Sale,Credit note,Time,Shop,Person (whose sale),Approver,Reason,Item,Value\r\nS-1,,"Monday 19 October 2026, 12:15",Test Branch Gayaza,Test Seller One,Cancelled by Test Admin One,Test wrong item,,300\r\n');
  });
});

describe('the chain a person chose', () => {
  beforeEach(() => localStorage.clear());
  it('starts with the usual order, remembers a choice per user and ignores a garbled one', () => {
    expect(readControlsChain('u1')).toEqual([...CONTROLS_LEVELS]);
    writeControlsChain('u1', ['reason', 'user']);
    expect(readControlsChain('u1')).toEqual(['reason', 'user']);
    // A chain saved before the Approver level existed is still read as it was.
    localStorage.setItem('bms.controlsChain.u5', JSON.stringify(['branch', 'user', 'day', 'reason']));
    expect(readControlsChain('u5')).toEqual(['branch', 'user', 'day', 'reason']);
    expect(readControlsChain('u2')).toEqual([...CONTROLS_LEVELS]);
    localStorage.setItem('bms.controlsChain.u3', JSON.stringify(['user', 'user']));
    expect(readControlsChain('u3')).toEqual([...CONTROLS_LEVELS]);
    localStorage.setItem('bms.controlsChain.u4', JSON.stringify(['item']));
    expect(readControlsChain('u4')).toEqual([...CONTROLS_LEVELS]);
  });
});
