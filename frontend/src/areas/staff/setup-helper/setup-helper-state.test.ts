import { describe, expect, it } from 'vitest';
import type { ItemDraftRow, LoanDraftRow } from '../../../api/setup-helper';
import { csvCell, fromItemDraft, fromLoanDraft, itemsCsv, loansCsv, majorText, reasonText } from './setup-helper-state';

// The setup helper's rows become exactly the import file a person could have typed (issue #626, ADR-102), so the import's
// check decides what is added. Every name and number is fabricated.

const sugar: ItemDraftRow = {
  description: 'Test Sugar 1kg', category: 'Test Groceries', unit: 'Piece', sell_price_minor: 5500, cost_price_minor: 4800, pack_unit: 'Carton', pack_factor: '10',
};
const oil: ItemDraftRow = {
  description: 'Test Oil, 2L', category: 'Test Groceries', unit: 'Litre', sell_price_minor: null, cost_price_minor: null, pack_unit: null, pack_factor: null,
};

describe('the item import file', () => {
  it('writes amounts in the currency\'s major units', () => {
    expect(majorText(5500, 0)).toBe('5500');
    expect(majorText(12050, 2)).toBe('120.50');
    expect(majorText(5, 2)).toBe('0.05');
    expect(majorText(null, 0)).toBe('');
  });

  it('has the buying price only for someone who may see it, and the pack columns only with a pack', () => {
    const rows = fromItemDraft([sugar, oil], 0);
    expect(itemsCsv(rows, true)).toBe(
      'description,category,unit,cost_price,sell_price,pack_unit,pack_factor\n'
        + 'Test Sugar 1kg,Test Groceries,Piece,4800,5500,Carton,10\n'
        + '"Test Oil, 2L",Test Groceries,Litre,,,,\n',
    );
    expect(itemsCsv(rows.slice(1), false)).toBe('description,category,unit,sell_price\n"Test Oil, 2L",Test Groceries,Litre,\n');
  });

  it('quotes a cell with a comma or a quote and keeps it on one line', () => {
    expect(csvCell('Test "Big" Pot')).toBe('"Test ""Big"" Pot"');
    expect(csvCell('two\nlines')).toBe('two lines');
  });
});

const known: LoanDraftRow = {
  ref: 'Y-01', member_no: 'M000001', member_full_name: 'Test Member Known', member_name: 'T. Known', member_phone: '+256700000201',
  principal_minor: 400000, disbursement_date: '2026-08-01', term_months: 2, agreed_total_minor: 480000, rate_percent: '10', total_worked_out: true,
  payments: [{ date: '2026-09-01', amount_minor: 240000 }, { date: null, amount_minor: null }],
};
const fresh: LoanDraftRow = {
  ref: 'R261027-01', member_no: null, member_full_name: null, member_name: 'Test Member New', member_phone: '+256700000202',
  principal_minor: 200000, disbursement_date: null, term_months: null, agreed_total_minor: null, rate_percent: null, total_worked_out: false, payments: [],
};

describe('the existing loans import file', () => {
  it('names every borrower by the register\'s name and phone, so the check matches and warns, with a cash row for each payment', () => {
    const csv = loansCsv(fromLoanDraft([known, fresh], 0), 'LOAN1').split('\n');
    expect(csv[0]).toBe('kind,ref,member_no,member_name,member_phone,product_code,principal,disbursement_date,term,agreed_total,purpose,repayment_date,amount,method,note');
    expect(csv[1]).toBe('loan,Y-01,,T. Known,+256700000201,LOAN1,400000,2026-08-01,2,480000,,,,,');
    expect(csv[2]).toBe('repayment,Y-01,,,,,,,,,,2026-09-01,240000,cash,');
    expect(csv[3]).toBe('loan,R261027-01,,Test Member New,+256700000202,LOAN1,200000,,,,,,,,');
    expect(csv).toHaveLength(5);
  });
});

describe('why there are no rows', () => {
  it('always says the way on in plain words, with no dash', () => {
    for (const reason of ['no_provider', 'all_failed', 'daily_cap', 'unreadable', null] as const) {
      const text = reasonText(reason, 'items');
      expect(text).toMatch(/type|Type|photo/);
      expect(text).not.toMatch(/[\u2013\u2014]| - /);
    }
    expect(reasonText(null, 'loans')).toBe('No loans were found in your list. Type them below or import a file.');
  });
});
