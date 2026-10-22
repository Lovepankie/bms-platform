import { describe, expect, it } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { BRING_IN, IMPORT_COLUMNS, IMPORT_TEMPLATE, canBringIn, emptyDraft, goLiveWords, toRequest } from './existing-loans-state';

// The pure rules of the existing-loans screens (issue #466, FR-EXL-01, FR-EXL-06): who may bring loans in, the form as a
// request in minor units, the go-live date in words and the CSV template. Fabricated figures.

describe('bringing in existing loans', () => {
  it('is offered to a holder of lending.loans.bring_in in a business with lending', () => {
    expect(canBringIn({ permissions: [BRING_IN] })).toBe(true);
    expect(canBringIn({ permissions: ['lending.loans.read'] })).toBe(false);
    expect(canBringIn({ permissions: [BRING_IN], modules: ['retail'] })).toBe(false);
    expect(canBringIn(mockMe('sales'))).toBe(false);
  });

  it('turns the form into the request: whole shillings as minor units, blank repayment rows left out, optional fields dropped', () => {
    const { body, problems } = toRequest(
      {
        ...emptyDraft(),
        memberId: 'm1',
        productId: 'p1',
        principal: '1,200,000',
        disbursementDate: '2026-01-15',
        term: '3',
        agreedTotal: '',
        registerRef: ' REG-001 ',
        repayments: [
          { date: '2026-02-15', amount: '520,000', method: 'cash', note: 'Receipt 101' },
          { date: '', amount: '', method: 'cash', note: '' },
          { date: '2026-03-01', amount: '300000', method: 'mtn_momo', note: ' ' },
        ],
      },
      'UGX',
    );
    expect(problems).toEqual({});
    expect(body).toEqual({
      member_id: 'm1',
      product_id: 'p1',
      principal_minor: 1_200_000,
      disbursement_date: '2026-01-15',
      term_count: 3,
      agreed_total_minor: undefined,
      register_ref: 'REG-001',
      repayments: [
        { value_date: '2026-02-15', amount_minor: 520_000, payment_method_key: 'cash', note: 'Receipt 101' },
        { value_date: '2026-03-01', amount_minor: 300_000, payment_method_key: 'mtn_momo', note: undefined },
      ],
    });
  });

  it('names every entry that cannot be sent, by the server\'s field name', () => {
    const { body, problems } = toRequest(
      {
        ...emptyDraft(),
        principal: 'abc',
        term: '0',
        agreedTotal: 'x',
        repayments: [{ date: '15/02/2026', amount: '', method: 'cash', note: 'paid' }],
      },
      'UGX',
    );
    expect(body).toBeUndefined();
    expect(Object.keys(problems).sort()).toEqual([
      'agreed_total_minor',
      'disbursement_date',
      'member_id',
      'principal_minor',
      'product_id',
      'repayments[0].amount_minor',
      'repayments[0].value_date',
      'term_count',
    ]);
  });

  it('says where the go-live date comes from, or that there is none', () => {
    expect(goLiveWords(undefined)).toMatch(/no go-live date yet/);
    expect(goLiveWords({ go_live_date: '2026-03-01', source: 'set' })).toMatch(/2026-03-01 \(set by the Owner or the operator\)/);
    expect(goLiveWords({ go_live_date: '2026-03-01', source: 'first_admin_sign_in' })).toMatch(/first signed in/);
  });

  it('offers a template with the columns in order and a loan with its repayments tied by ref', () => {
    const lines = IMPORT_TEMPLATE.trim().split('\n');
    expect(lines[0]).toBe(IMPORT_COLUMNS.join(','));
    expect(lines.every((l) => l.split(',').length === IMPORT_COLUMNS.length)).toBe(true);
    expect(lines.filter((l) => l.startsWith('repayment,REG-001,'))).toHaveLength(2);
    expect(lines.filter((l) => l.startsWith('loan,'))).toHaveLength(2);
  });
});
