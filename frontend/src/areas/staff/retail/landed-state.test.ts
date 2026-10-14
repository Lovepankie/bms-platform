import { describe, expect, it } from 'vitest';
import type { GoodsReceipt, LandedCostLine } from '../../../api/retail';
import {
  addReceipt, basisOf, blankLanded, buildLandedRequest, chargeAmount, chargesTotal, costEffectWords, hasProfitFigures, landedProblem, lineProblem, previewKey, removeReceipt,
  sharesLeft, spreadMinor, tickedLines, typedSharesTotal, type LandedForm,
} from './landed-state';

// The rules of the landed cost form (issue #199): the charges and the typed total, the exact spread, the weight and typed amounts
// each method needs, and the request that is sent. Fabricated suppliers, deliveries and amounts.

const receipt = (over: Partial<GoodsReceipt> = {}): GoodsReceipt => ({
  id: 'r1', receipt_no: 'GR-000001', supplier_name: 'Test Supplier 01', received_on: '2026-10-05', status: 'posted',
  lines: [
    { id: 'l1', code: 'P003', description: 'Test bulb', unit: 'piece', qty: '10.000', line_total_minor: 35000 },
    { id: 'l2', code: 'P004', description: 'Test cable', unit: 'roll', qty: '2.000', line_total_minor: 65000 },
  ],
  ...over,
});

function filled(over: Partial<LandedForm> = {}): LandedForm {
  const base = addReceipt(blankLanded('2026-10-12'), receipt());
  return {
    ...base, supplierId: 's2', supplierRef: 'FWD-1', dueDate: '2026-11-11', total: '10000',
    charges: [{ key: 'c1', kind: 'freight', description: '', amount: '6000' }, { key: 'c2', kind: 'duty', description: 'Import duty', amount: '4000' }],
    ...over,
  };
}

describe('spreading a total in exact integers', () => {
  it('always adds up to the total, whatever the proportions', () => {
    for (const [total, bases] of [[10000, [35000, 65000]], [100, [1, 1, 1]], [7, [3, 3, 3]], [1, [5, 5]], [0, [4, 6]], [999_999_999, [123, 456, 789]]] as [number, number[]][]) {
      const shares = spreadMinor(total, bases)!;
      expect(shares.reduce((a, b) => a + b, 0), `${total} over ${bases}`).toBe(total);
    }
  });

  it('gives a share in proportion to the basis', () => {
    expect(spreadMinor(10000, [35000, 65000])).toEqual([3500, 6500]);
    expect(spreadMinor(100, [1, 3])).toEqual([25, 75]);
  });

  it('gives the left over units to the biggest remainders, a tie to the earlier line', () => {
    expect(spreadMinor(100, [1, 1, 1])).toEqual([34, 33, 33]);
    expect(spreadMinor(10, [1, 2, 2])).toEqual([2, 4, 4]);
    // 5 over 3 and 7: 1.5 and 3.5 are tied on a half, so the earlier line takes the odd unit.
    expect(spreadMinor(5, [3, 7])).toEqual([2, 3]);
  });

  it('shares nothing when the bases add up to nothing, or one is negative', () => {
    expect(spreadMinor(100, [0, 0])).toBeNull();
    expect(spreadMinor(100, [])).toBeNull();
    expect(spreadMinor(100, [5, -1])).toBeNull();
  });
});

describe('the charges and the typed total', () => {
  it('adds the charges and counts an amount that is not valid as none', () => {
    const f = filled();
    expect(chargesTotal(f)).toBe(10000);
    expect(chargeAmount({ key: 'x', kind: 'other', description: '', amount: '1,500' })).toBe(1500);
    expect(chargeAmount({ key: 'x', kind: 'other', description: '', amount: '0' })).toBeNull();
    expect(chargeAmount({ key: 'x', kind: 'other', description: '', amount: 'a lot' })).toBeNull();
    expect(chargesTotal({ ...f, charges: [...f.charges, { key: 'c3', kind: 'other', description: '', amount: 'x' }] })).toBe(10000);
  });

  it('stops a review when the typed total is not the sum of the charges, and says both figures', () => {
    expect(landedProblem(filled())).toBeNull();
    expect(landedProblem(filled({ total: '9000' }))).toMatch(/UGX 9,000 but the charges come to UGX 10,000/);
    expect(landedProblem(filled({ total: '' }))).toMatch(/invoice total/i);
  });

  it('asks for the payee, the invoice number, the due date, a charge and a valid amount, in that order', () => {
    expect(landedProblem(filled({ supplierId: '' }))).toMatch(/who the charges are owed to/i);
    expect(landedProblem(filled({ supplierRef: ' ' }))).toMatch(/invoice number/i);
    expect(landedProblem(filled({ dueDate: '' }))).toMatch(/due/i);
    expect(landedProblem(filled({ charges: [] }))).toMatch(/at least one charge/i);
    expect(landedProblem(filled({ charges: [{ key: 'c1', kind: 'freight', description: '', amount: '' }] }))).toMatch(/marked in red/i);
  });
});

describe('the goods', () => {
  it('adds the lines of a delivery all ticked, once, and takes them off with the delivery', () => {
    const once = addReceipt(blankLanded('2026-10-12'), receipt());
    expect(once.lines.map((l) => [l.receiptLineId, l.ticked, l.valueMinor, l.qty])).toEqual([['l1', true, 35000, '10.000'], ['l2', true, 65000, '2.000']]);
    expect(addReceipt(once, receipt())).toBe(once);
    expect(once.receipts).toEqual([{ id: 'r1', receiptNo: 'GR-000001', supplierName: 'Test Supplier 01', receivedOn: '2026-10-05' }]);
    const two = addReceipt(once, receipt({ id: 'r2', receipt_no: 'GR-000002', lines: [{ id: 'l3', description: 'Test pipe', unit: 'metre', qty: '4.000', line_total_minor: 8000 }] }));
    expect(two.lines).toHaveLength(3);
    expect(removeReceipt(two, 'r1').lines.map((l) => l.receiptLineId)).toEqual(['l3']);
  });

  it('needs at least one ticked line', () => {
    const f = filled();
    expect(tickedLines(f)).toHaveLength(2);
    expect(landedProblem({ ...f, lines: f.lines.map((l) => ({ ...l, ticked: false })) })).toMatch(/tick the goods/i);
  });
});

describe('each method', () => {
  it('uses the value for a spread by value and the quantity for a spread by quantity', () => {
    const [bulb, cable] = filled().lines;
    expect(basisOf(bulb!, 'value')).toBe(35000);
    expect(basisOf(cable!, 'quantity')).toBe(2000);
    expect(landedProblem(filled({ method: 'quantity' }))).toBeNull();
  });

  it('refuses a goods value of nothing, and says to choose another method', () => {
    const f = filled();
    const free = { ...f, lines: f.lines.map((l) => ({ ...l, valueMinor: 0 })) };
    expect(landedProblem(free)).toMatch(/add up to nothing/i);
    expect(landedProblem({ ...free, method: 'quantity' })).toBeNull();
  });

  it('needs a weight above zero on every ticked line for a spread by weight', () => {
    const f = filled({ method: 'weight' });
    expect(lineProblem(f.lines[0]!, 'weight')).toMatch(/weight/i);
    expect(landedProblem(f)).toMatch(/marked in red/i);
    const weighed = { ...f, lines: f.lines.map((l, i) => ({ ...l, weight: i === 0 ? '12.5' : '0' })) };
    expect(landedProblem(weighed)).toMatch(/marked in red/i);
    expect(landedProblem({ ...weighed, lines: weighed.lines.map((l) => ({ ...l, weight: '3' })) })).toBeNull();
    // An unticked line needs no weight.
    expect(landedProblem({ ...f, lines: [{ ...f.lines[0]!, weight: '3' }, { ...f.lines[1]!, ticked: false }] })).toBeNull();
  });

  it('needs the typed shares to add up to the total, and says how much is left', () => {
    const f = filled({ method: 'manual' });
    const typed = (a: string, b: string): LandedForm => ({ ...f, lines: [{ ...f.lines[0]!, amount: a }, { ...f.lines[1]!, amount: b }] });
    expect(landedProblem(f)).toMatch(/marked in red/i);
    expect(sharesLeft(typed('3000', '5000'))).toBe(2000);
    expect(typedSharesTotal(typed('3000', '5000'))).toBe(8000);
    expect(landedProblem(typed('3000', '5000'))).toMatch(/UGX 2,000 of the total is not given to a line/);
    expect(landedProblem(typed('6000', '5000'))).toMatch(/UGX 1,000 more is given to lines than the total/);
    expect(landedProblem(typed('4000', '6000'))).toBeNull();
    expect(landedProblem(typed('10,000', '0'))).toBeNull();
  });
});

describe('the request', () => {
  it('names the payee, the charges, the method and the ticked lines, and the typed total', () => {
    expect(buildLandedRequest(filled({ note: ' By road ' }))).toEqual({
      supplier_id: 's2', supplier_ref: 'FWD-1', cost_date: '2026-10-12', due_date: '2026-11-11', method: 'value', total_minor: 10000, note: 'By road',
      charges: [{ kind: 'freight', amount_minor: 6000 }, { kind: 'duty', description: 'Import duty', amount_minor: 4000 }],
      lines: [{ receipt_line_id: 'l1' }, { receipt_line_id: 'l2' }],
    });
  });

  it('sends a weight only for the weight method and an amount only for typed amounts', () => {
    const weighed = filled({ method: 'weight' });
    const lines = weighed.lines.map((l, i) => ({ ...l, weight: i === 0 ? '12.5' : '3', amount: '99' }));
    expect(buildLandedRequest({ ...weighed, lines }).lines).toEqual([{ receipt_line_id: 'l1', weight: '12.500' }, { receipt_line_id: 'l2', weight: '3.000' }]);
    const manual = filled({ method: 'manual' });
    expect(buildLandedRequest({ ...manual, lines: manual.lines.map((l, i) => ({ ...l, weight: '5', amount: i === 0 ? '4000' : '6000' })) }).lines).toEqual([
      { receipt_line_id: 'l1', amount_minor: 4000 }, { receipt_line_id: 'l2', amount_minor: 6000 },
    ]);
  });

  it('leaves unticked lines out, and the preview key ignores the note but not a charge', () => {
    const f = filled();
    expect(buildLandedRequest({ ...f, lines: [f.lines[0]!, { ...f.lines[1]!, ticked: false }] }).lines).toEqual([{ receipt_line_id: 'l1' }]);
    expect(previewKey({ ...f, note: 'x' })).toBe(previewKey(f));
    expect(previewKey({ ...f, charges: [{ ...f.charges[0]!, amount: '6001' }, f.charges[1]!] })).not.toBe(previewKey(f));
  });
});

describe('the words for a result', () => {
  const line = (over: Partial<LandedCostLine>): LandedCostLine => ({ description: 'Test bulb', allocated_minor: 3500, ...over });

  it('says the cost moved, or why it did not, and says nothing without profit access', () => {
    expect(costEffectWords(line({ cost_before_minor: 3500, cost_after_minor: 3850, cost_applied: true }))).toBe('Cost of one goes from UGX 3,500 to UGX 3,850.');
    expect(costEffectWords(line({ cost_before_minor: 3500, cost_after_minor: 3500, cost_applied: false, cost_note: 'None is left in stock.' }))).toBe('Cost not changed. None is left in stock.');
    expect(costEffectWords(line({}))).toBe('');
  });

  it('knows whether the figures only profit readers see came back', () => {
    expect(hasProfitFigures([line({ to_inventory_minor: 3000, to_cogs_minor: 500 })])).toBe(true);
    expect(hasProfitFigures([line({})])).toBe(false);
    expect(hasProfitFigures(undefined)).toBe(false);
  });
});
