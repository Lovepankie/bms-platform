import { describe, expect, it } from 'vitest';
import type { EntryNorms, Product } from '../../../api/retail';
import { offendingWords } from '../../../app/glossary';
import { amountOf, entryWarnings, farFrom, medianMilli, roundedMilli, type LineEntry } from './entry-checks-state';

// The rules of the entry warnings (issue #353, ADR-093): a line in another unit than the usual one, a quantity ten times the
// median or a tenth of it, a typed price twice or half the last one, and a count far from the records. Names are fabricated.

const ROLL = 'u-roll';
const cable: Product = {
  id: 'p-cable', code: 'CAB-0001', description: 'Test cable 2.5 mm', unit: 'metre', cost_minor: 30, sell_minor: 60, active: true,
  packs: [{ unit_id: ROLL, unit: 'roll', factor: '100.000', active: true }],
};
/** Bought in rolls (median 350 metres, three and a half rolls), sold by the metre (median 2), last sold at 60 a metre. */
const norms: EntryNorms = {
  product_id: 'p-cable', unit: 'metre', quantity_factor: 10, price_factor: 2, min_lines: 3,
  buy: { lines_read: 6, median_qty: '350', usual_unit_id: ROLL, usual_unit: 'roll' },
  sell: { lines_read: 12, median_qty: '2', usual_unit: 'metre' },
  move: { lines_read: 1 },
  last_sell_minor: 60,
};

const buy = (over: Partial<LineEntry>): LineEntry => ({
  screen: 'buy', product: cable, lineKey: 'l0', qtyMilli: 4000, qtyFieldId: 'q', unitChoosable: true, unitFieldId: 'u',
  unitId: ROLL, unitName: 'roll', factor: '100', ...over,
});
const texts = (n: EntryNorms | undefined, e: LineEntry) => entryWarnings(n, e).map((w) => `${w.kind}: ${w.text}`);

describe('the thresholds', () => {
  it('warns at ten times or a tenth, not just inside', () => {
    expect(farFrom(3500, 350, 10)).toBe(true);
    expect(farFrom(3499, 350, 10)).toBe(false);
    expect(farFrom(35, 350, 10)).toBe(true);
    expect(farFrom(36, 350, 10)).toBe(false);
    expect(farFrom(0, 350, 10)).toBe(false);
    expect(farFrom(10, 0, 10)).toBe(false);
  });

  it('reads the median and rounds it for people', () => {
    expect(medianMilli({ lines_read: 3, median_qty: '350' })).toBe(350_000);
    expect(medianMilli({ lines_read: 2 })).toBeNull();
    expect(roundedMilli(3_456)).toBe(3_500);
    expect(roundedMilli(12_400)).toBe(12_000);
    expect(roundedMilli(25)).toBe(25);
    expect(amountOf(1000, 'roll')).toBe('1 roll');
    expect(amountOf(200_000, 'metre')).toBe('200 metres');
    expect(amountOf(2_500, 'box')).toBe('2.5 boxes');
  });
});

describe('Buy stock', () => {
  it('says nothing for a usual line, without norms, or before a quantity is typed', () => {
    expect(texts(norms, buy({}))).toEqual([]);
    expect(texts(undefined, buy({ unitId: undefined, unitName: undefined, factor: undefined, qtyMilli: 200_000 }))).toEqual([]);
    expect(texts(norms, buy({ qtyMilli: null }))).toEqual([]);
  });

  it('names the usual unit when a line is in another one', () => {
    const warnings = entryWarnings(norms, buy({ unitId: undefined, unitName: undefined, factor: undefined, qtyMilli: 200_000 }));
    expect(warnings.map((w) => w.text)).toEqual(['You usually buy this in rolls; you entered 200 metres. Is that right?']);
    expect(warnings[0]).toMatchObject({ kind: 'unit', fieldId: 'u', confirmation: { product_id: 'p-cable', screen: 'buy', kind: 'unit', entered_qty: '200', median_qty: '350' } });
    expect(offendingWords(warnings[0]?.text ?? '')).toEqual([]);
  });

  it('gives no unit warning where no unit can be chosen, and compares the quantity instead', () => {
    expect(texts(norms, buy({ unitChoosable: false, unitId: undefined, unitName: undefined, factor: undefined, qtyMilli: 3_000 }))).toEqual([
      'quantity: You usually buy about 350 metres of this at a time; you entered 3 metres. Is that right?',
    ]);
  });

  it('says the usual quantity in the unit entered, ten times over or a tenth', () => {
    expect(texts(norms, buy({ qtyMilli: 40_000 }))).toEqual([
      'quantity: You usually buy about 3.5 rolls of this at a time; you entered 40 rolls. Is that right?',
    ]);
    expect(texts(norms, buy({ qtyMilli: 300 }))).toEqual([
      'quantity: You usually buy about 3.5 rolls of this at a time; you entered 0.3 rolls. Is that right?',
    ]);
    expect(texts(norms, buy({ qtyMilli: 30_000 }))).toEqual([]);
  });

  it('gives no median under three lines', () => {
    const short: EntryNorms = { ...norms, buy: { lines_read: 2 } };
    expect(texts(short, buy({ unitId: undefined, unitName: undefined, factor: undefined, qtyMilli: 99_000_000 }))).toEqual([]);
  });

  it('keeps a warning confirmed only while the line says the same', () => {
    const a = entryWarnings(norms, buy({ qtyMilli: 40_000 }))[0];
    const b = entryWarnings(norms, buy({ qtyMilli: 50_000 }))[0];
    expect(a?.key).not.toBe(b?.key);
    expect(a?.key).toBe(entryWarnings(norms, buy({ qtyMilli: 40_000 }))[0]?.key);
  });
});

describe('Sell', () => {
  const sell = (over: Partial<LineEntry>): LineEntry => ({ screen: 'sell', product: cable, lineKey: 's0', qtyMilli: 2_000, qtyFieldId: 'q', unitChoosable: true, unitFieldId: 'u', ...over });

  it('says a roll is unusual for something sold by the metre', () => {
    expect(texts(norms, sell({ unitId: ROLL, unitName: 'roll', factor: '100', qtyMilli: 1_000 }))).toEqual([
      'unit: You usually sell this in metres; you entered 1 roll. Is that right?',
    ]);
  });

  it('warns about a quantity a hundred times the usual', () => {
    expect(texts(norms, sell({ qtyMilli: 200_000 }))).toEqual([
      'quantity: You usually sell about 2 metres of this at a time; you entered 200 metres. Is that right?',
    ]);
  });

  it('warns about a typed price twice or half the last one, never one the app filled', () => {
    expect(texts(norms, sell({ priceMinor: 600, priceFieldId: 'p' }))).toEqual([
      'price: You last sold this at UGX 60 a metre; you entered UGX 600. Is that right?',
    ]);
    expect(texts(norms, sell({ priceMinor: 30, priceFieldId: 'p' }))).toHaveLength(1);
    expect(texts(norms, sell({ priceMinor: 31, priceFieldId: 'p' }))).toEqual([]);
    expect(texts(norms, sell({ priceMinor: 119, priceFieldId: 'p' }))).toEqual([]);
    expect(texts(norms, sell({}))).toEqual([]);
    const w = entryWarnings(norms, sell({ priceMinor: 600, priceFieldId: 'p' }))[0];
    expect(w?.confirmation).toMatchObject({ kind: 'price', entered_price_minor: 600, last_price_minor: 60 });
    expect(w?.fieldId).toBe('p');
  });

  it('compares a pack price per metre and says the last price per pack', () => {
    // A roll at 6,000 is 60 a metre: as usual. A roll at 600 is 6 a metre.
    const roll = { unitId: ROLL, unitName: 'roll', factor: '100', qtyMilli: 1_000, priceFieldId: 'p' };
    const usual: EntryNorms = { ...norms, sell: { lines_read: 4, median_qty: '100', usual_unit_id: ROLL, usual_unit: 'roll' } };
    expect(texts(usual, sell({ ...roll, priceMinor: 6_000 }))).toEqual([]);
    expect(texts(usual, sell({ ...roll, priceMinor: 600 }))).toEqual(['price: You last sold this at UGX 6,000 a roll; you entered UGX 600. Is that right?']);
  });

  it('shows no price to compare with for someone who sees no price', () => {
    const { last_sell_minor: _last, ...hidden } = norms;
    expect(texts(hidden, sell({ priceMinor: 600, priceFieldId: 'p' }))).toEqual([]);
  });
});

describe('Move stock and Count stock', () => {
  it('compares a move with the moves, or with the purchases while there are too few moves', () => {
    const move = { screen: 'move' as const, product: cable, lineKey: 'm', qtyFieldId: 'tq-0' };
    expect(texts(norms, { ...move, qtyMilli: 5_000_000 })).toEqual([
      'quantity: You usually move about 350 metres of this at a time; you entered 5,000 metres. Is that right?',
    ]);
    const moved: EntryNorms = { ...norms, move: { lines_read: 5, median_qty: '50' } };
    expect(texts(moved, { ...move, qtyMilli: 5_000 })).toEqual([
      'quantity: You usually move about 50 metres of this at a time; you entered 5 metres. Is that right?',
    ]);
    // A move never warns about a unit: it is always in the item's own unit.
    expect(entryWarnings(norms, { ...move, qtyMilli: 300_000 }).filter((w) => w.kind === 'unit')).toEqual([]);
  });

  it('compares a count with the records here, or with the usual purchase when they hold nothing', () => {
    const count = { screen: 'count' as const, product: cable, lineKey: 'c', qtyFieldId: 'count-p' };
    expect(texts({ unit: 'metre', quantity_factor: 10 }, { ...count, qtyMilli: 4_000_000, recordedMilli: 40_000 })).toEqual([
      'quantity: The records show 40 metres here; you counted 4,000 metres. Is that right?',
    ]);
    expect(texts({ unit: 'metre', quantity_factor: 10 }, { ...count, qtyMilli: 38_000, recordedMilli: 40_000 })).toEqual([]);
    expect(texts(norms, { ...count, qtyMilli: 9_000_000, recordedMilli: 0 })).toEqual([
      'quantity: You usually buy about 350 metres of this at a time; you counted 9,000 metres. Is that right?',
    ]);
    // A count of 0 (the item is gone) is never questioned.
    expect(texts(norms, { ...count, qtyMilli: 0, recordedMilli: 40_000 })).toEqual([]);
  });
});
