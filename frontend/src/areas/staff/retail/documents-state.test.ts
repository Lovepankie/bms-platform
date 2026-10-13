import { describe, expect, it } from 'vitest';
import type { Product, RetailDocument } from '../../../api/retail';
import {
  QUOTATION_DAYS, blankConvert, blankQuotation, buildConvertRequest, buildQuotationRequest, convertProblem, documentState, issuable, prefixProblem, quotationProblem, tidyPrefix, typeLabel,
} from './documents-state';
import { newLine } from './sale-state';

// The pure logic of the documents screens (documents capability, ADR-033, issue #197). Fabricated data.

const doc = (over: Partial<RetailDocument>): RetailDocument => ({ id: 'd1', type: 'invoice', number: 'INV-HQ-000001', status: 'issued', ...over });
const bulb: Product = { id: 'p1', code: 'P003', description: 'Test Bulb', unit: 'piece', sell_minor: 6000, currency: 'UGX', active: true };
const today = '2026-10-08';

describe('the words for a document', () => {
  it('names the types and says where a document stands', () => {
    expect(typeLabel('delivery_note')).toBe('Delivery note');
    expect(typeLabel('mystery')).toBe('mystery');
    expect(documentState(doc({}))).toEqual({ label: 'Issued', tone: 'success' });
    expect(documentState(doc({ status: 'void' }))).toEqual({ label: 'Void', tone: 'warning' });
    expect(documentState(doc({ type: 'quotation', quotation_status: 'open' })).label).toBe('Open');
    expect(documentState(doc({ type: 'quotation', quotation_status: 'expired' })).label).toBe('Expired');
    expect(documentState(doc({ type: 'quotation', quotation_status: 'converted' })).label).toBe('Made into a sale');
    // A cancelled quotation is void whatever else it says.
    expect(documentState(doc({ type: 'quotation', status: 'void', quotation_status: 'cancelled' })).label).toBe('Void');
  });
});

describe('which documents a sale can still be given', () => {
  it('offers the ones it has not got, and none for a voided sale', () => {
    expect(issuable([], 'completed')).toEqual(['invoice', 'delivery_note']);
    expect(issuable([doc({ type: 'invoice' })], 'completed')).toEqual(['delivery_note']);
    expect(issuable([doc({ type: 'invoice' }), doc({ type: 'delivery_note' })], 'completed')).toEqual([]);
    expect(issuable([], 'voided')).toEqual([]);
  });
});

describe('the prefix of a series', () => {
  it('follows the server: one to eight capitals or digits, starting with a letter', () => {
    expect(prefixProblem('INV')).toBeNull();
    expect(prefixProblem('A1B2C3D4')).toBeNull();
    expect(prefixProblem('')).toMatch(/Enter a prefix/);
    for (const bad of ['inv', '1INV', 'INV-1', 'ABCDEFGHI', 'IN V']) expect(prefixProblem(bad), bad).not.toBeNull();
    expect(tidyPrefix(' in v-1 ')).toBe('INV-1');
    expect(tidyPrefix('abcdefghijk')).toBe('ABCDEFGH');
  });
});

describe('a quotation form', () => {
  const filled = () => ({ ...blankQuotation('b1', today), buyerName: 'Test Buyer 01', lines: [newLine(bulb)] });

  it('is valid for thirty days unless changed, and says what is missing', () => {
    expect(blankQuotation('b1', today).validUntil).toBe('2026-11-07');
    expect(QUOTATION_DAYS).toBe(30);
    expect(quotationProblem(blankQuotation('b1', today), today)).toMatch(/buyer/);
    expect(quotationProblem({ ...blankQuotation('b1', today), buyerName: 'x' }, today)).toMatch(/at least one item/);
    expect(quotationProblem({ ...filled(), validUntil: '2026-10-07' }, today)).toMatch(/before today/);
    expect(quotationProblem({ ...filled(), validUntil: today }, today)).toBeNull();
    expect(quotationProblem({ ...filled(), lines: [{ ...newLine(bulb), qty: '' }] }, today)).toMatch(/quantity and price/);
    expect(quotationProblem(filled(), today)).toBeNull();
  });

  it('sends only a price that differs from the one a sale would take, and the buyer one way or the other', () => {
    expect(buildQuotationRequest(filled())).toEqual({ branch_id: 'b1', buyer_name: 'Test Buyer 01', valid_until: '2026-11-07', lines: [{ product_id: 'p1', qty: '1.000' }] });
    const typed = { ...filled(), buyerContact: ' +256700000001 ', notes: ' Delivered free ', lines: [{ ...newLine(bulb), qty: '2.5', price: '5000' }] };
    expect(buildQuotationRequest(typed)).toEqual({
      branch_id: 'b1', buyer_name: 'Test Buyer 01', buyer_contact: '+256700000001', valid_until: '2026-11-07', notes: 'Delivered free',
      lines: [{ product_id: 'p1', qty: '2.500', unit_price_minor: 5000 }],
    });
    // A buyer from the list sends the id and not the typed name or phone.
    expect(buildQuotationRequest({ ...typed, customerId: 'c1' })).toMatchObject({ customer_id: 'c1' });
    expect(buildQuotationRequest({ ...typed, customerId: 'c1' })).not.toHaveProperty('buyer_name');
    expect(buildQuotationRequest({ ...typed, customerId: 'c1' })).not.toHaveProperty('buyer_contact');
  });
});

describe('turning a quotation into a sale', () => {
  it('keeps the quoted prices and makes the invoice unless told otherwise', () => {
    expect(buildConvertRequest(blankConvert())).toEqual({ payment_method: 'cash', keep_quoted_prices: true, issue_invoice: true });
  });

  it('needs a date for a credit sale unless the buyer has terms', () => {
    const credit = { ...blankConvert(), method: 'credit' as const };
    expect(convertProblem(credit, false)).toMatch(/date/);
    expect(convertProblem(credit, true)).toBeNull();
    expect(convertProblem({ ...credit, dueDate: '2026-11-01' }, false)).toBeNull();
    expect(buildConvertRequest({ ...credit, dueDate: '2026-11-01', invoice: false })).toEqual({ payment_method: 'credit', due_date: '2026-11-01', keep_quoted_prices: true, issue_invoice: false });
    expect(convertProblem(blankConvert(), false)).toBeNull();
  });
});
