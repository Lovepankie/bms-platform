import { describe, expect, it } from 'vitest';
import type { Product } from '../../../api/retail';
import { buildTransfer, transferLineHint, transferProblem, type TransferDraft } from './transfer-state';

const cable: Product = { id: 'p1', code: 'P001', description: '2.5mm twin cable 100m roll', unit: 'roll', qty: '4.000' };
const draft = (patch: Partial<TransferDraft>): TransferDraft => ({ toBranchId: 'b2', transferDate: '', note: '', lines: [], ...patch });

describe('stock move form (FR-RET-16)', () => {
  it('hints when a line is more than the source branch holds, in the words of the sale screen (#112 item 2), and never for what it holds', () => {
    expect(transferLineHint({ product: cable, qty: '4' })).toBeNull();
    expect(transferLineHint({ product: cable, qty: '4.001' })).toBe('Only 4 roll in stock at this branch.');
    expect(transferLineHint({ product: { ...cable, qty: '-2.000' }, qty: '1' })).toBe('Only 0 roll in stock at this branch.');
    expect(transferLineHint({ product: cable, qty: '0' })).toBe('Enter a quantity above zero.');
  });

  it('says too many, not too few, only for numbers that read but are too large to hold (reviews of #654 and #659)', () => {
    const soap: Product = {
      ...cable, unit: 'Piece', qty: '130.000',
      packs: [{ id: 'k1', product_id: 'p1', unit_id: 'u1', unit: 'Carton', factor: '24.000', active: true, version: 1, standard: true }],
    };
    const tooMany = 'That is more than BMS can move at once. Check the number.';
    const tooManyPieces = 'That is more pieces than BMS can move at once. Check the number.';
    const tooFew = 'Enter a quantity above zero.';
    // Too many pieces, alone or after whole cartons; too many cartons, or cartons and pieces that add up to too many.
    expect(transferLineHint({ product: cable, qty: '99999999999999' })).toBe(tooManyPieces);
    expect(transferLineHint({ product: soap, qty: '99999999999999', packs: '1' })).toBe(tooManyPieces);
    expect(transferLineHint({ product: soap, qty: '', packs: '99999999999999' })).toBe(tooMany);
    expect(transferLineHint({ product: soap, qty: '90000000000', packs: '1000000000' })).toBe(tooMany);
    // Cartons typed for an item with no pack, or no unit, never read as too many.
    expect(transferLineHint({ product: cable, qty: '', packs: '2' })).toBe(tooFew);
    expect(transferLineHint({ product: { ...soap, unit: undefined }, qty: '', packs: '99999999999999' })).toBe(tooFew);
    // Unreadable cartons are said to be unreadable, even beside too many pieces.
    expect(transferLineHint({ product: soap, qty: '99999999999999', packs: 'abc' })).not.toBe(tooManyPieces);
    expect(transferLineHint({ product: soap, qty: '', packs: '0' })).toBe(tooFew);
  });

  it('needs a different destination and at least one valid line', () => {
    expect(transferProblem('b1', draft({ toBranchId: '' }))).toBe('Choose the branch the stock goes to.');
    expect(transferProblem('b1', draft({ toBranchId: 'b1', lines: [{ product: cable, qty: '1' }] }))).toBe('Choose a different branch to move the stock to.');
    expect(transferProblem('b1', draft({}))).toBe('Add at least one item.');
    expect(transferProblem('b1', draft({ lines: [{ product: cable, qty: '9' }] }))).toBe('Fix the items marked above.');
    expect(transferProblem('b1', draft({ lines: [{ product: cable, qty: '1.5' }] }))).toBeNull();
  });

  it('builds the request with three-place quantities and leaves empty optional fields out', () => {
    expect(buildTransfer('b1', draft({ lines: [{ product: cable, qty: '1.5' }] }))).toEqual({
      from_branch_id: 'b1', to_branch_id: 'b2', lines: [{ product_id: 'p1', qty: '1.500' }],
    });
    expect(buildTransfer('b1', draft({ transferDate: '2026-10-05', note: ' Test note ', lines: [] }))).toMatchObject({
      transfer_date: '2026-10-05', note: 'Test note',
    });
  });
});
