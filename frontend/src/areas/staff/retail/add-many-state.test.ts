import { describe, expect, it } from 'vitest';
import type { Category, Unit } from '../../../api/retail';
import {
  COMMA_DECIMAL, NAME_NEEDED, NOT_A_NUMBER, QTY_NEEDED, SELL_BELOW, SELL_NEEDED, checkRows, emptyRow, fieldOf, fillRows, groupsOf,
  listCsv, readNumber, readPrice, readQty, resultLine, rowsFromPaste, splitPaste, startRows, type ListRow,
} from './add-many-state';

// Add many items (issue #870): the paste, the numbers and the checks of each row. Every item, group and price is fabricated.

const CATS: Category[] = [{ id: 'c1', name: 'Test Drinks', active: true } as Category, { id: 'c2', name: 'Test Soap', active: true } as Category];
const UNITS: Unit[] = [{ id: 'u1', name: 'Piece', active: true } as Unit, { id: 'u2', name: 'Bottle', active: true } as Unit, { id: 'u9', name: 'Carton', active: true, is_package: true } as Unit];
const ctx = {
  groups: [] as string[], pieceId: 'u1', profit: true, mayCount: true,
  categoryName: (id: string) => CATS.find((c) => c.id === id)?.name, unitName: (id: string) => UNITS.find((u) => u.id === id)?.name,
};
const row = (patch: Partial<ListRow>, key = 1): ListRow => ({ ...emptyRow(key), ...patch });

describe('the numbers (never rounded, a comma decimal refused as in #867)', () => {
  it('reads plain numbers and thousands commas exactly', () => {
    expect(readNumber('12')).toEqual({ value: '12' });
    expect(readNumber(' 2.5 ')).toEqual({ value: '2.5' });
    expect(readNumber('12,500')).toEqual({ value: '12500' });
    expect(readNumber('1,250,000')).toEqual({ value: '1250000' });
  });

  it('refuses a comma used as a decimal point and anything that is not a number', () => {
    expect(readNumber('1,5')).toEqual({ problem: 'comma_decimal' });
    expect(readNumber('12,50')).toEqual({ problem: 'comma_decimal' });
    expect(readNumber('12,5000')).toEqual({ problem: 'comma_decimal' });
    expect(readNumber('ten')).toEqual({ problem: 'not_number' });
    expect(readNumber('-3')).toEqual({ problem: 'not_number' });
    expect(readNumber('1.2.3')).toEqual({ problem: 'not_number' });
  });

  it('never rounds a quantity or a price', () => {
    expect(readQty('12.375', false)).toEqual({ qty: '12.375' });
    expect(readQty('1.2345', false)).toEqual({ error: 'Use at most 3 places after the point.' });
    expect(readQty('', false)).toEqual({ error: QTY_NEEDED });
    expect(readQty('0', false)).toEqual({ qty: '0' });
    expect(readQty('0', true)).toEqual({ error: 'Type how many you are adding, more than 0.' });
    // Prices are in whole shillings: 1500.50 is refused, never made 1501 or 1500.
    expect(readPrice('1500.50')).toEqual({ error: 'Type the price in whole UGX, like 1500.' });
    expect(readPrice('4,000')).toEqual({ minor: 4000 });
    expect(readPrice('')).toEqual({ minor: null });
    expect(readPrice('4,5')).toEqual({ error: COMMA_DECIMAL });
  });
});

describe('the paste', () => {
  it('splits Excel and Sheets by tabs, WhatsApp by two or more spaces, else by commas', () => {
    expect(splitPaste('Test Soda\t10\t1500\nTest Water\t5\t1000').by).toBe('tab');
    expect(splitPaste('Test Soda   10   1,500\nTest Water  5  1,000')).toEqual({ by: 'spaces', lines: [['Test Soda', '10', '1,500'], ['Test Water', '5', '1,000']] });
    expect(splitPaste('Test Soda, 10, 1500\n"Test Rice, 5kg",2,9000')).toEqual({ by: 'comma', lines: [['Test Soda', '10', '1500'], ['Test Rice, 5kg', '2', '9000']] });
    expect(splitPaste('\n\n').lines).toEqual([]);
  });

  it('fills rows from a fabricated list of six columns, matching groups and units and keeping what does not match', () => {
    const text = 'Name\tGroup\tUnit\tHow many\tBuying\tSelling\n'
      + 'Test Soda 300ml\tTest Drinks\tbottle\t24\t1,000\t1,500\n'
      + 'Test Bar Soap\tTest Cleaning\tPiece\t10\t2000\t2500\n'
      + 'Test Candle\t\tStick\t5\t300\t500';
    const { rows } = rowsFromPaste(text, { categories: CATS, units: UNITS, profit: true, firstKey: 1 });
    expect(rows).toHaveLength(3);
    expect(rows[0]).toMatchObject({ name: 'Test Soda 300ml', categoryId: 'c1', categoryChosen: true, unitId: 'u2', qty: '24', cost: '1,000', sell: '1,500' });
    expect(rows[1]).toMatchObject({ name: 'Test Bar Soap', pastedCategory: 'Test Cleaning', unitId: 'u1' });
    expect(rows[2]).toMatchObject({ name: 'Test Candle', categoryChosen: false, pastedUnit: 'Stick' });
  });

  it('reads fewer columns in the documented order, and flags a line with too many', () => {
    const { rows } = rowsFromPaste('Test Pen, 1000\nTest Ruler, 12, 800\nA,B,C,D,E,F,G', { categories: CATS, units: UNITS, profit: true, firstKey: 1 });
    expect(rows[0]).toMatchObject({ name: 'Test Pen', sell: '1000', qty: '' });
    expect(rows[1]).toMatchObject({ name: 'Test Ruler', qty: '12', sell: '800' });
    expect(rows[2]?.extraColumns).toBe(7);
  });

  it('leaves the buying price out for someone who may not see it, and says so', () => {
    const out = rowsFromPaste('Test Cup\t4\t700\t1000', { categories: CATS, units: UNITS, profit: false, firstKey: 1 });
    expect(out.rows[0]).toMatchObject({ qty: '4', cost: '', sell: '1000' });
    expect(out.droppedCost).toBe(true);
  });

  it('puts the pasted rows in the empty rows first, then after the last row', () => {
    const rows = [row({ name: 'Test Typed' }, 1), ...startRows(2).slice(0, 2)];
    const pasted = rowsFromPaste('Test A\nTest B\nTest C\nTest D', { categories: CATS, units: UNITS, profit: true, firstKey: 10 }).rows;
    const out = fillRows(rows, pasted);
    expect(out.map((r) => r.name)).toEqual(['Test Typed', 'Test A', 'Test B', 'Test C', 'Test D']);
    expect(new Set(out.map((r) => r.key)).size).toBe(5);
  });
});

describe('the checks of each row', () => {
  it('ignores blank rows and sends a good row exactly as typed', () => {
    const rows = [row({ name: ' Test Juice ', qty: '12.5', cost: '1,200', sell: '1,800', unitId: 'u2' }), emptyRow(2)];
    const [good, blank] = checkRows(rows, { ...ctx, groups: ['c1', 'c1'] });
    expect(good?.errors).toEqual({});
    // As Import items takes it: the group and unit by name, the numbers as typed without their thousands commas.
    expect(good?.send).toEqual({ description: 'Test Juice', category: 'Test Drinks', unit: 'Bottle', sell: '1800', cost: '1200', qty: '12.5' });
    expect(blank).toEqual({ errors: {} });
  });

  it('says a missing name, a missing selling price and a number that is not one, on their fields', () => {
    const [c] = checkRows([row({ qty: 'lots', sell: '' })], ctx);
    expect(c?.errors).toEqual({ name: NAME_NEEDED, qty: NOT_A_NUMBER, sell: SELL_NEEDED });
    expect(c?.send).toBeUndefined();
  });

  it('refuses a comma decimal on the quantity and the prices', () => {
    const [c] = checkRows([row({ name: 'Test Rope', qty: '2,5', cost: '1,5', sell: '3,5' })], ctx);
    expect(c?.errors).toEqual({ qty: COMMA_DECIMAL, cost: COMMA_DECIMAL, sell: COMMA_DECIMAL });
  });

  it('warns, never blocks, when the selling price is below the buying price', () => {
    const [c] = checkRows([row({ name: 'Test Torch', qty: '1', cost: '5000', sell: '4000' })], ctx);
    expect(c?.warning).toBe(SELL_BELOW);
    expect(c?.send).toBeDefined();
  });

  it('finds the same name twice in the list, in the same unit', () => {
    const rows = [row({ name: 'Test Salt', qty: '1', sell: '500' }, 1), row({ name: 'test  SALT', qty: '1', sell: '500' }, 2), row({ name: 'Test Salt', qty: '1', sell: '500', unitId: 'u2' }, 3)];
    const checks = checkRows(rows, ctx);
    expect(checks[0]?.errors).toEqual({});
    expect(checks[1]?.errors.name).toBe('This name is on row 1 too. Change one of them.');
    expect(checks[2]?.errors).toEqual({});
  });

  it('sends only the quantity for a row that adds to an item already in the list', () => {
    const [c] = checkRows([row({ name: 'Test Rice', qty: '8', sell: '', addTo: { id: 'p1', code: 'GNR-0001', name: 'Test Rice' } })], ctx);
    expect(c?.send).toEqual({ description: 'Test Rice', category: 'General', unit: 'Piece', qty: '8', stockOnly: true });
  });

  it('leaves the buying price out without the profit permission, and the quantity without the count permission', () => {
    const [c] = checkRows([row({ name: 'Test Mug', qty: '3', cost: '900', sell: '1500' })], { ...ctx, profit: false, mayCount: false });
    expect(c?.send).toEqual({ description: 'Test Mug', category: 'General', unit: 'Piece', sell: '1500' });
  });

  it('says a group or unit pasted that the shop does not have is made, and sends it by its name', () => {
    const [c] = checkRows([row({ name: 'Test Bag', qty: '1', sell: '100', pastedCategory: 'Test Bags', pastedUnit: 'Sack' })], ctx);
    expect(c?.errors).toEqual({});
    expect(c?.warning).toBe('This adds the group Test Bags and the unit Sack, which your shop does not have yet.');
    expect(c?.send).toMatchObject({ category: 'Test Bags', unit: 'Sack' });
  });

  it('writes the import file, quoting a cell with a comma and the buying price only with the permission', () => {
    const rows = [{ description: 'Test Rice, 5kg', category: 'Test Food', unit: 'Bag', qty: '2', cost: '9000', sell: '12000' }, { description: 'Test Salt', category: 'General', unit: 'Piece', qty: '3', stockOnly: true }];
    expect(listCsv(rows, true, true)).toBe('description,category,unit,qty,cost_price,sell_price,stock_only\n"Test Rice, 5kg",Test Food,Bag,2,9000,12000,\nTest Salt,General,Piece,3,,,yes\n');
    expect(listCsv(rows, false, false).split('\n')[0]).toBe('description,category,unit,sell_price,stock_only');
  });
});

describe('the group of each row', () => {
  it('follows the last one chosen above it, else the one used last time', () => {
    const rows = [row({}, 1), row({ categoryId: 'c2', categoryChosen: true }, 2), row({}, 3), row({ categoryId: '', categoryChosen: true }, 4), row({}, 5)];
    expect(groupsOf(rows, 'c1')).toEqual(['c1', 'c2', 'c2', '', '']);
  });
});

describe('the words', () => {
  it('says what was added and what needs a look', () => {
    expect(resultLine(18, 2)).toBe('18 items added, 2 need a look.');
    expect(resultLine(1, 1)).toBe('1 item added, 1 needs a look.');
    expect(resultLine(3, 0)).toBe('3 items added.');
  });

  it('puts a server refusal on the field it names', () => {
    expect(fieldOf('rows[3].description')).toBe('name');
    expect(fieldOf('rows[0].sell_minor')).toBe('sell');
    expect(fieldOf('rows[1].qty')).toBe('qty');
    expect(fieldOf('rows[2]')).toBe('row');
  });
});
