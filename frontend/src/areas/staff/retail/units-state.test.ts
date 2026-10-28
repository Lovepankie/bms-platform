import { describe, expect, it } from 'vitest';
import type { Pack, Product, SaleLine } from '../../../api/retail';
import { buildPurchase, restockLineProblem, restockTotal, withUnit as restockWithUnit, type RestockLine } from './restock';
import { buildSaleRequest, defaultPrice, draftTotal, lineHint, newLine, withUnit, type Draft } from './sale-state';
import { factorProblem } from './catalogue-packs';
import { baseCostMinor, baseQtyMilli, enteredPriceMinor, enteredQty, packPriceMinor, packsAndPieces, packsAndPiecesMilli, packsAndPiecesProblem, samePack, standardPack } from './units-state';

// Units of measure (ADR-033, issue #194): the forms convert with the same rules as the server
// (Quantities.java) so a line is judged before it is sent. Names and amounts are fabricated.

const CARTON = '00000000-0000-4000-8000-0000000000c1';
const BOX = '00000000-0000-4000-8000-0000000000c2';

const pen: Product = {
  id: '00000000-0000-4000-8000-0000000000e1',
  code: 'T-PEN',
  description: 'Test pen',
  unit: 'piece',
  sell_minor: 100,
  cost_minor: 83,
  qty: '100.000',
  packs: [
    { id: 'k1', product_id: 'e1', unit_id: CARTON, unit: 'carton', factor: '12.000', active: true, version: 1 },
    { id: 'k2', product_id: 'e1', unit_id: BOX, unit: 'box', factor: '2.000', active: false, version: 1 },
  ],
};

const draft = (lines: Draft['lines']): Draft => ({ branchId: 'b1', method: 'cash', customerId: '', newBuyerName: '', newBuyerContact: '', dueDate: '', lines });

describe('conversions', () => {
  it('prices a pack from the base price, rounding half up once', () => {
    expect(packPriceMinor(83, '12.000')).toBe(996);
    expect(packPriceMinor(85, '12.500')).toBe(1063);
    expect(packPriceMinor(85, '12.250')).toBe(1041);
  });

  it('divides a pack cost by the factor, rounding half up', () => {
    expect(baseCostMinor(1000, '12.000')).toBe(83);
    expect(baseCostMinor(1002, '12.000')).toBe(84);
    expect(baseCostMinor(5, '2.000')).toBe(3);
    expect(baseCostMinor(1, '1000.000')).toBe(0);
  });

  it('converts a quantity exactly or not at all', () => {
    expect(baseQtyMilli(2000, '12.000')).toBe(24000);
    expect(baseQtyMilli(1, '1.500')).toBeNull(); // 0.001 of a pack of 1.5 is 0.0015
    expect(baseQtyMilli(2000, '1.500')).toBe(3000);
    expect(baseQtyMilli(99_999_999_999_999, '2.000')).toBeNull(); // does not fit numeric(14,3)
  });

  it('accepts a factor above zero with at most three places', () => {
    expect(factorProblem('12')).toBeNull();
    expect(factorProblem('12.5')).toBeNull();
    expect(factorProblem('0')).not.toBeNull();
    expect(factorProblem('0.5')).not.toBeNull(); // a pack holds at least one base unit
    expect(factorProblem('1')).toBeNull();
    expect(factorProblem('12,5')).not.toBeNull(); // a comma is refused, never dropped
    expect(factorProblem('1.2345')).not.toBeNull();
    expect(factorProblem('abc')).not.toBeNull();
  });
});

describe('the sale form with a pack', () => {
  it('prices a carton at the sell price times the factor and totals the line in cartons', () => {
    const line = withUnit(newLine(pen), CARTON);
    expect(line.price).toBe('1200');
    expect(defaultPrice(pen, CARTON)).toBe(1200);
    expect(draftTotal([{ ...line, qty: '2' }])).toBe(2400);
  });

  it('sends the unit, and the price only when it is not the default for that unit', () => {
    const line = { ...withUnit(newLine(pen), CARTON), qty: '2' };
    expect(buildSaleRequest(draft([line])).lines[0]).toEqual({ product_id: pen.id, qty: '2.000', unit_id: CARTON });
    expect(buildSaleRequest(draft([{ ...line, price: '1100' }])).lines[0]).toEqual({ product_id: pen.id, qty: '2.000', unit_id: CARTON, unit_price_minor: 1100 });
  });

  it('sends a base unit line exactly as before', () => {
    expect(buildSaleRequest(draft([newLine(pen)])).lines[0]).toEqual({ product_id: pen.id, qty: '1.000' });
  });

  it('ignores a unit that is switched off or not a pack of the item', () => {
    const line = { ...newLine(pen), unitId: BOX };
    expect(buildSaleRequest(draft([line])).lines[0]).toEqual({ product_id: pen.id, qty: '1.000' });
  });

  it('warns in base units: stock is counted in pieces', () => {
    const line = { ...withUnit(newLine(pen), CARTON), qty: '8' }; // 96 pieces of 100
    expect(lineHint(line)).toBeNull();
    expect(lineHint({ ...line, qty: '9' })).toBe('Only 100 piece in stock at this branch.'); // 108 pieces
  });

  it('judges the price floor on the line total, not on the rounded price per piece', () => {
    const base = { ...withUnit(newLine(pen), CARTON), qty: '1' };
    expect(lineHint({ ...base, price: '996' })).toBe('The price must be above what the item cost.'); // 996 = 12 x 83
    expect(lineHint({ ...base, price: '997' })).toBeNull(); // 997 / 12 rounds to 83, still above cost
  });

  it('refuses a quantity that does not convert exactly', () => {
    const odd: Product = { ...pen, packs: [{ id: 'k3', product_id: 'e1', unit_id: BOX, unit: 'box', factor: '1.500', active: true, version: 1 }] };
    const line = { ...withUnit(newLine(odd), BOX), qty: '0.001', price: '10' };
    expect(lineHint(line)).toContain('does not convert exactly');
  });
});

describe('the receipt shows the unit used', () => {
  const packLine: SaleLine = { qty: '24.000', unit_price_minor: 100, line_total_minor: 2400, sale_unit: 'carton', sale_qty: '2.000', sale_unit_price_minor: 1200, unit_factor: '12.000' };
  it('names the pack, its quantity and its price', () => {
    expect(enteredQty(packLine)).toBe('2 carton');
    expect(enteredPriceMinor(packLine)).toBe(1200);
  });
  it('shows an older line in its base unit', () => {
    const old: SaleLine = { qty: '3.500', unit_price_minor: 100, line_total_minor: 350 };
    expect(enteredQty(old)).toBe('3.5');
    expect(enteredPriceMinor(old)).toBe(100);
  });
});

describe('the restock form with a pack', () => {
  const line = (over: Partial<RestockLine> = {}): RestockLine => ({ product: pen, cost: '83', sell: '100', qtyByBranch: { b1: '3' }, ...over });

  it('moves the cost to the pack equivalent when the unit changes', () => {
    expect(restockWithUnit(line(), CARTON).cost).toBe('996');
    expect(restockWithUnit(line({ unitId: CARTON, cost: '996' }), '').cost).toBe('83');
  });

  it('sends the unit with the cost per pack and the quantity in packs', () => {
    const request = buildPurchase({ supplierId: '', purchasedOn: '2026-01-15', method: 'cash', lines: [line({ unitId: CARTON, cost: '1000' })] });
    expect(request.lines[0]).toMatchObject({ product_id: pen.id, unit_id: CARTON, cost_minor: 1000, sell_minor: 100, qty_by_branch: [{ branch_id: 'b1', qty: '3.000' }] });
    expect(restockTotal([line({ unitId: CARTON, cost: '1000' })])).toBe(3000);
  });

  it('sends a base unit line without a unit', () => {
    const request = buildPurchase({ supplierId: '', purchasedOn: '2026-01-15', method: 'cash', lines: [line()] });
    expect(request.lines[0]).not.toHaveProperty('unit_id');
  });

  it('refuses a pack quantity that does not convert exactly', () => {
    const odd: Product = { ...pen, packs: [{ id: 'k3', product_id: 'e1', unit_id: BOX, unit: 'box', factor: '1.500', active: true, version: 1 }] };
    expect(restockLineProblem({ product: odd, unitId: BOX, cost: '100', sell: '100', qtyByBranch: { b1: '0.001' } })).toContain('does not convert exactly');
    expect(restockLineProblem({ product: odd, unitId: BOX, cost: '100', sell: '100', qtyByBranch: { b1: '3' } })).toBeNull();
  });
});

describe('stock in packs and pieces (issue #650)', () => {
  const carton = { unit: 'Carton', factor: '24.000' };
  it('shows whole packs, the pieces left and the total', () => {
    expect(packsAndPieces('127.000', 'Piece', carton)).toBe('5 Cartons and 7 Pieces (127 Pieces)');
    expect(packsAndPieces('24.000', 'Piece', carton)).toBe('1 Carton (24 Pieces)');
    expect(packsAndPieces('25.000', 'Piece', carton)).toBe('1 Carton and 1 Piece (25 Pieces)');
    expect(packsAndPieces('48.500', 'Piece', carton)).toBe('2 Cartons and 0.5 Pieces (48.5 Pieces)');
  });
  it('reads part of a pack and nothing in the base unit only', () => {
    expect(packsAndPieces('7.000', 'Piece', carton)).toBe('7 Pieces');
    expect(packsAndPieces('1.000', 'Piece', carton)).toBe('1 Piece');
    expect(packsAndPieces('0.000', 'Piece', carton)).toBe('0 Pieces');
  });
  it('says how far short a negative stock is', () => {
    expect(packsAndPieces('-27.000', 'Piece', carton)).toBe('1 Carton and 3 Pieces short (27 Pieces short)');
    expect(packsAndPieces('-3.000', 'Piece', carton)).toBe('3 Pieces short');
  });
  it('shows an item with no pack as it always did', () => {
    expect(packsAndPieces('127.000', 'Piece', undefined)).toBe('127 Piece');
    expect(packsAndPieces('5.000', 'Piece', { unit: 'Pair', factor: '1.000' })).toBe('5 Piece');
    expect(packsAndPieces('5.000', undefined, carton)).toBe('5');
  });
  it('works with a pack of a decimal size', () => {
    expect(packsAndPieces('26.000', 'Kg', { unit: 'Sack', factor: '12.500' })).toBe('2 Sacks and 1 Kg (26 Kgs)');
  });
  it('reads packs and pieces as one quantity in pieces', () => {
    expect(packsAndPiecesMilli('5', '7', carton)).toBe(127_000);
    expect(packsAndPiecesMilli('5', '', carton)).toBe(120_000);
    expect(packsAndPiecesMilli('', '127', carton)).toBe(127_000);
    expect(packsAndPiecesMilli('0', '0', carton)).toBe(0);
    expect(packsAndPiecesMilli('2', '0.5', carton)).toBe(48_500);
    expect(packsAndPiecesMilli('', '', carton)).toBeNull();
    expect(packsAndPiecesMilli('1.5', '', carton)).toBeNull();
    expect(packsAndPiecesMilli('2', 'x', carton)).toBeNull();
    expect(packsAndPiecesMilli('99999999999999', '', carton)).toBeNull();
  });
  it('says plainly what cannot be read', () => {
    expect(packsAndPiecesProblem('1.5', '', 'Piece', 'Carton')).toBe('Type whole Cartons, and the rest in Pieces.');
    expect(packsAndPiecesProblem('2', 'x', 'Piece', 'Carton')).toBe('Type Pieces as a number, with at most three decimals.');
    expect(packsAndPiecesProblem('2', '3', 'Piece', 'Carton')).toBeNull();
  });
  it('takes the pack the server marks standard, while it is active and holds more than one', () => {
    const [carton, box] = (pen.packs ?? []) as [Pack, Pack];
    expect(standardPack(pen)).toBeUndefined();
    expect(standardPack({ ...pen, packs: [{ ...carton, standard: true }, box] })?.unit).toBe('carton');
    expect(standardPack({ ...pen, packs: [carton, { ...box, standard: true }] })).toBeUndefined();
    expect(standardPack({ ...pen, packs: [{ ...carton, factor: '1.000', standard: true }] })).toBeUndefined();
    expect(standardPack({ ...pen, packs: undefined })).toBeUndefined();
  });
  it('only reads the flag the server sets, never ranking the packs itself (#660)', () => {
    // The rule lives in the server's one subquery and RetailUnitsIT pins its cases; these are the two payloads that IT asserts.
    // A client that ranked by size would take the carton in the second; a client that ranked by usual pack could not, since
    // a pack carries no usual pack. So the PWA takes whichever pack is marked, even the smaller one.
    const box: Pack = { id: 'k2', product_id: 'e1', unit_id: BOX, unit: 'box', factor: '10.000', active: true, version: 1, purpose: 'both' };
    const carton: Pack = { id: 'k1', product_id: 'e1', unit_id: CARTON, unit: 'carton', factor: '24.000', active: true, version: 1, purpose: 'both' };
    expect(standardPack({ ...pen, packs: [box, { ...carton, standard: true }] })?.unit).toBe('carton');
    expect(standardPack({ ...pen, packs: [{ ...box, standard: true }, carton] })?.unit).toBe('box');
    expect(standardPack({ ...pen, packs: [box, carton] })).toBeUndefined();
  });
  it('tells a pack changed in unit or size from the same one', () => {
    expect(samePack({ unit: 'Carton', factor: '24' }, { unit: 'Carton', factor: '24.000' })).toBe(true);
    expect(samePack({ unit: 'Carton', factor: '24.000' }, { unit: 'Carton', factor: '30.000' })).toBe(false);
    expect(samePack({ unit: 'Carton', factor: '24.000' }, { unit: 'Dozen', factor: '24.000' })).toBe(false);
    expect(samePack({ unit: 'Carton', factor: '24.000' }, undefined)).toBe(false);
  });
});
