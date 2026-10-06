import { describe, expect, it } from 'vitest';
import type { Product } from '../../../api/retail';
import { buildTransfer, transferLineHint, transferProblem, type TransferDraft } from './transfer-state';

const cable: Product = { id: 'p1', code: 'P001', description: '2.5mm twin cable 100m roll', unit: 'roll', qty: '4.000' };
const draft = (patch: Partial<TransferDraft>): TransferDraft => ({ toBranchId: 'b2', transferDate: '', note: '', lines: [], ...patch });

describe('stock move form (FR-RET-16)', () => {
  it('hints when a line is more than the source branch holds, and never for what it holds', () => {
    expect(transferLineHint({ product: cable, qty: '4' })).toBeNull();
    expect(transferLineHint({ product: cable, qty: '4.001' })).toBe('Only 4 roll at the branch you are moving from.');
    expect(transferLineHint({ product: { ...cable, qty: '-2.000' }, qty: '1' })).toBe('Only 0 roll at the branch you are moving from.');
    expect(transferLineHint({ product: cable, qty: '0' })).toBe('Enter a quantity above zero.');
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
