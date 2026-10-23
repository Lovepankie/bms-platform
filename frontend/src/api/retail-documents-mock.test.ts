import { describe, expect, it } from 'vitest';
import { businessToday, daysBefore } from './retail';
import { createMockRetail, mockMe } from './retail-mock';

// The mock's optional documents follow the server's rules in small (ADR-033, issue #197): off means 404, a document is issued
// once per sale and type and numbered per branch, void follows the sale, a quotation is made, cancelled or converted once while
// it is valid, and a prefix changes later numbers only. Fabricated data.

const branches = mockMe('admin').branches ?? [];
const hq = branches[0]?.id ?? '';
const code = branches[0]?.code ?? '';
const other = branches[1]?.id ?? '';
const today = businessToday();

async function on() {
  const api = createMockRetail();
  const read = await api.capabilities();
  await api.changeCapabilities({ documents: true }, read.version);
  const [bulb] = await api.listProducts({ query: 'P003' });
  return { api, productId: bulb?.id ?? '' };
}

type Api = Awaited<ReturnType<typeof on>>['api'];

const sale = (api: Api, productId: string, branch = hq, qty = '1') =>
  api.createSale({ branch_id: branch, payment_method: 'cash', lines: [{ product_id: productId, qty }] }, `sale-${Math.random()}`);

describe('documents in the mock', () => {
  it('answers 404 capability_not_enabled while the capability is off, and a sale needs none', async () => {
    const api = createMockRetail();
    const [bulb] = await api.listProducts({ query: 'P003' });
    const made = await sale(api, bulb?.id ?? '');
    await expect(api.listSaleDocuments(made.id ?? '')).rejects.toMatchObject({ status: 404, code: 'capability_not_enabled' });
    await expect(api.issueSaleDocument(made.id ?? '', { type: 'invoice' }, 'k1')).rejects.toMatchObject({ status: 404 });
    await expect(api.listDocuments({})).rejects.toMatchObject({ status: 404 });
    await expect(api.documentSeries()).rejects.toMatchObject({ status: 404 });
    await expect(api.listQuotations({})).rejects.toMatchObject({ status: 404 });
    await expect(api.customerStatementPdf(made.id ?? '', {})).rejects.toMatchObject({ status: 404 });
  });

  it('numbers an invoice in its branch, issues it once per sale and keeps the number', async () => {
    const { api, productId } = await on();
    const a = await sale(api, productId);
    const first = await api.issueSaleDocument(a.id ?? '', { type: 'invoice' }, 'k1');
    expect(first).toMatchObject({ number: `INV-${code}-000001`, type: 'invoice', status: 'issued', created: true });
    expect(first.pdf_document_id).toBeTruthy();
    // Another key finds it already issued; the same key replays.
    const again = await api.issueSaleDocument(a.id ?? '', { type: 'invoice' }, 'k2');
    expect(again).toMatchObject({ id: first.id, created: false });
    const b = await sale(api, productId);
    expect((await api.issueSaleDocument(b.id ?? '', { type: 'invoice' }, 'k3')).number).toBe(`INV-${code}-000002`);
    const [pen] = await api.listProducts({ query: 'P001' });
    const c = await sale(api, pen?.id ?? '', other);
    expect((await api.issueSaleDocument(c.id ?? '', { type: 'invoice' }, 'k4')).number).toMatch(/^INV-[A-Z0-9]+-000001$/);
    expect((await api.listSaleDocuments(a.id ?? ''))).toHaveLength(1);
    expect((await api.listDocuments({ type: 'invoice' })).items).toHaveLength(3);
    expect((await api.listDocuments({ query: `INV-${code}-000002` })).items).toHaveLength(1);
  });

  it('shows prices on a delivery note only when asked', async () => {
    const { api, productId } = await on();
    const a = await sale(api, productId, hq, '2');
    const plain = await api.issueSaleDocument(a.id ?? '', { type: 'delivery_note' }, 'k1');
    expect(plain.total_minor).toBeUndefined();
    const b = await sale(api, productId, hq, '2');
    const priced = await api.issueSaleDocument(b.id ?? '', { type: 'delivery_note', show_prices: true }, 'k2');
    expect(priced.total_minor).toBe(12000);
  });

  it('changes the prefix of later numbers only', async () => {
    const { api, productId } = await on();
    const a = await sale(api, productId);
    await api.issueSaleDocument(a.id ?? '', { type: 'invoice' }, 'k1');
    await expect(api.setDocumentPrefix('invoice', 'tax')).rejects.toMatchObject({ status: 422 });
    const rows = await api.setDocumentPrefix('invoice', 'TAX');
    expect(rows.find((r) => r.type === 'invoice')).toMatchObject({ prefix: 'TAX', default_prefix: 'INV' });
    const b = await sale(api, productId);
    expect((await api.issueSaleDocument(b.id ?? '', { type: 'invoice' }, 'k2')).number).toBe(`TAX-${code}-000002`);
    expect((await api.listSaleDocuments(a.id ?? ''))[0]?.number).toBe(`INV-${code}-000001`);
  });

  it('makes a quotation that reserves nothing, converts it once to a sale and its invoice, and refuses a second time', async () => {
    const { api, productId } = await on();
    const before = (await api.listProducts({ query: 'P003', branchId: hq }))[0]?.qty;
    const valid = daysBefore(today, -10);
    const q = await api.createQuotation({ branch_id: hq, buyer_name: 'Test Buyer 02', valid_until: valid, lines: [{ product_id: productId, qty: '2', unit_price_minor: 5000 }] }, 'q1');
    expect(q).toMatchObject({ number: `QT-${code}-000001`, status: 'open', expired: false, total_minor: 10000 });
    expect((await api.listProducts({ query: 'P003', branchId: hq }))[0]?.qty).toBe(before);
    const done = await api.convertQuotation(q.id ?? '', { payment_method: 'cash', keep_quoted_prices: true, issue_invoice: true }, 'c1');
    expect(done.invoice).toMatchObject({ number: `INV-${code}-000001`, source_document_id: q.document_id });
    expect((await api.getQuotation(q.id ?? '')).status).toBe('converted');
    await expect(api.convertQuotation(q.id ?? '', { payment_method: 'cash' }, 'c2')).rejects.toMatchObject({ code: 'quotation_not_open' });
    await expect(api.createQuotation({ branch_id: hq, buyer_name: 'x', valid_until: daysBefore(today, 1), lines: [{ product_id: productId, qty: '1' }] }, 'q2')).rejects.toMatchObject({ status: 422 });
  });

  it('cancels an open quotation once, and its document reads as void', async () => {
    const { api, productId } = await on();
    const q = await api.createQuotation({ branch_id: hq, buyer_name: 'Test Buyer 02', valid_until: daysBefore(today, -5), lines: [{ product_id: productId, qty: '1' }] }, 'q1');
    const cancelled = await api.cancelQuotation(q.id ?? '', 'Buyer went elsewhere');
    expect(cancelled.status).toBe('cancelled');
    expect((await api.listDocuments({ type: 'quotation' })).items?.[0]?.status).toBe('void');
    await expect(api.cancelQuotation(q.id ?? '', 'Again')).rejects.toMatchObject({ code: 'quotation_not_open' });
    await expect(api.convertQuotation(q.id ?? '', { payment_method: 'cash' }, 'c1')).rejects.toMatchObject({ code: 'quotation_not_open' });
  });

  it('lays out a sample printed receipt for View receipt, so it can be seen offline (issue #500)', async () => {
    const api = createMockRetail();
    const [bulb] = await api.listProducts({ query: 'P003' });
    const made = await sale(api, bulb?.id ?? '', hq, '2');
    const { printed, sale_version } = await api.saleReceiptPrinted(made.id ?? '');
    expect(sale_version).toBe(made.version ?? 1);
    expect(printed).toMatchObject({ title: 'RECEIPT', number: made.sale_no, sale_no: made.sale_no, payment: 'Cash', currency: 'UGX', status: 'Paid in full by Cash.' });
    expect(printed?.lines).toHaveLength(1);
    expect(printed?.lines?.[0]).toMatchObject({ no: 1, description: bulb?.description, detail: bulb?.code, quantity: '2' });
    expect(printed?.total).toBe((made.total_minor ?? 0).toLocaleString('en-US'));
    expect(printed?.received).toBe(`Received by ${printed?.buyer_name}`);
    expect(printed?.date).toMatch(/^\d{1,2} [A-Z][a-z]{2} \d{4}$/);
  });
});
