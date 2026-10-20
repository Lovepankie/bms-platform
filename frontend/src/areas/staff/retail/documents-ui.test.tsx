import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { DocumentSeries, RetailDocument } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { DocumentsPage } from './documents';
import { CreditNotePdf, DocumentCard, DocumentCards, SaleDocuments, SeriesPanel } from './documents-ui';
import { RetailHome } from './home';
import { canIssueDocuments, canUse, screensFor } from './permissions';
import { ConvertPanel, QuotationForm, quotationState, QuotationsPage } from './quotations';
import { blankConvert } from './documents-state';

// The documents screens (documents capability, ADR-033, issue #197). With the capability off nothing is drawn: "all off equals
// today". Names and amounts are fabricated.

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
const branch = mockMe('admin').branches?.[0]?.id ?? '';
const on = (me: ReturnType<typeof mockMe>) => ({ ...me, module_capabilities: { retail: ['documents'] } });
const admin = on(mockMe('admin'));
const seller = on(mockMe('sales'));

const page = (me: ReturnType<typeof mockMe>, node: ReactElement) =>
  renderToString(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient()}>
        <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );

const invoice: RetailDocument = {
  id: 'd1', type: 'invoice', number: 'INV-HQ-000001', status: 'issued', branch_id: branch, issue_date: '2026-10-08', buyer_name: 'Test Buyer 01', currency: 'UGX', total_minor: 12000, sale_id: 's1',
};
const quotation: RetailDocument = { id: 'd2', type: 'quotation', number: 'QT-HQ-000001', status: 'issued', issue_date: '2026-10-08', buyer_name: 'Test Buyer 02', currency: 'UGX', total_minor: 50000, valid_until: '2026-11-07', quotation_status: 'open' };
const noop = () => undefined;

describe('the pages are closed unless the feature is on', () => {
  it('refuses the documents and quotations pages while the feature is off, and to a seller while it is on', () => {
    expect(page(mockMe('admin'), <DocumentsPage />)).toContain('You do not have access');
    expect(page(mockMe('admin'), <QuotationsPage />)).toContain('You do not have access');
    expect(page(seller, <DocumentsPage />)).toContain('You do not have access');
    expect(page(seller, <QuotationsPage />)).toContain('You do not have access');
  });

  it('opens them to an administrator with the feature on, with the anchors of their tours', () => {
    const docs = page(admin, <DocumentsPage />);
    expect(docs).not.toContain('You do not have access');
    expect(docs).toContain('data-tour="documents-help"');
    expect(docs).toContain('data-tour="documents-list"');
    expect(docs).toContain('Number or buyer');
    const quotes = page(admin, <QuotationsPage />);
    expect(quotes).toContain('data-tour="quotation-new"');
    expect(quotes).toContain('data-tour="quotations-list"');
    expect(quotes).toContain('New quotation');
  });

  it('offers no New quotation at a store, and says why (ADR-048)', () => {
    const store = on(mockMe('admin'));
    store.branches = (store.branches ?? []).map((b) => (b.id === branch ? { ...b, sells_goods: false } : b));
    const quotes = page(store, <QuotationsPage />);
    expect(quotes).toContain('is a store, and a store does not sell or quote');
    expect(quotes).not.toContain('New quotation');
  });

  it('offers the tiles only while the feature is on and the permission is held', () => {
    const tiles = (me: ReturnType<typeof mockMe>) => screensFor(me).map((s) => s.screen);
    expect(tiles(mockMe('admin'))).not.toContain('documents');
    expect(tiles(admin)).toEqual(expect.arrayContaining(['documents', 'quotations']));
    expect(tiles(seller)).not.toContain('documents');
    expect(canUse(admin, 'documents')).toBe(true);
    expect(canUse(mockMe('admin'), 'quotations')).toBe(false);
    expect(canIssueDocuments(admin)).toBe(true);
    expect(canIssueDocuments(mockMe('admin'))).toBe(false);
    expect(canIssueDocuments(seller)).toBe(false);
    expect(page(admin, <RetailHome />)).toContain('data-tour="retail-documents"');
    expect(page(mockMe('admin'), <RetailHome />)).not.toContain('retail-documents');
  });
});

describe('the documents of a sale', () => {
  it('draws nothing while the feature is off or the user may not issue, and a panel otherwise', () => {
    expect(page(mockMe('admin'), <SaleDocuments saleId="s1" status="completed" branchId={branch} />)).toBe('');
    expect(page(seller, <SaleDocuments saleId="s1" status="completed" branchId={branch} />)).toBe('');
    const html = page(admin, <SaleDocuments saleId="s1" status="completed" branchId={branch} />);
    expect(html).toContain('aria-label="Documents of this sale"');
    expect(html).toContain('The till receipt is the default');
  });
});

describe('a document in the lists', () => {
  const card = (d: RetailDocument, term = '') => page(admin, <ul><DocumentCard doc={d} branchName="Test Branch A" term={term} /></ul>);

  it('shows its number, what it is, who for, the day, the amount and the way to open it', () => {
    const html = card(invoice);
    expect(html).toContain('INV-HQ-000001');
    expect(html).toContain('Invoice, Test Buyer 01');
    expect(html).toContain('UGX 12,000');
    expect(html).toContain('Test Branch A');
    expect(html).toContain('aria-label="Open PDF of INV-HQ-000001"');
    expect(html).toContain('Issued');
  });

  it('says a document is void and that its PDF stays as issued, and a quotation its last day', () => {
    expect(card({ ...invoice, status: 'void' })).toContain('The PDF stays as it was issued');
    expect(card({ ...invoice, status: 'void' })).toContain('Void');
    expect(card(quotation)).toContain('valid until 7 Nov 2026');
    expect(card({ ...quotation, quotation_status: 'expired' })).toContain('Expired');
  });

  it('says so when there are none', () => {
    expect(page(admin, <DocumentCards items={[]} />)).toContain('No documents found.');
    expect(page(admin, <DocumentCards items={[invoice, quotation]} />)).toContain('QT-HQ-000001');
  });
});

describe('the numbered series', () => {
  const series: DocumentSeries[] = [
    { type: 'invoice', prefix: 'TAX', default_prefix: 'INV', branches: [{ branch_id: 'b1', branch_code: 'HQ', issued: 2, next: 'TAX-HQ-000003' }] },
    { type: 'quotation', prefix: 'QT', default_prefix: 'QT', branches: [{ branch_id: 'b1', branch_code: 'HQ', issued: 0, next: 'QT-HQ-000001' }] },
  ];

  it('shows the next number of each branch, the usual prefix once changed, and no credit note yet', () => {
    const html = renderToString(<SeriesPanel series={series} canEdit={false} onSave={noop} />);
    expect(html).toContain('next TAX-HQ-000003, 2 issued');
    expect(html).toContain('next QT-HQ-000001, 0 issued');
    expect(html).toContain('The usual prefix is INV');
    expect(html).toContain('Prefix TAX');
    expect(html).not.toContain('Credit note');
    expect(html).not.toContain('<input');
    expect(html).toContain('data-tour="document-numbers"');
  });

  it('offers the prefix to whoever may change settings', () => {
    const html = renderToString(<SeriesPanel series={series} canEdit onSave={noop} />);
    expect(html).toContain('id="prefix-invoice"');
    expect(html).toContain('aria-label="Save the Invoice prefix"');
  });
});

describe('a quotation', () => {
  it('says where it stands', () => {
    expect(quotationState({ status: 'open', expired: false })).toEqual({ label: 'Open', tone: 'info' });
    expect(quotationState({ status: 'open', expired: true }).label).toBe('Expired');
    expect(quotationState({ status: 'converted' }).label).toBe('Made into a sale');
    expect(quotationState({ status: 'cancelled' }).label).toBe('Cancelled');
  });

  it('turns into a sale with a few plain choices', () => {
    const html = renderToString(<ConvertPanel form={blankConvert()} hasTerms={false} onChange={noop} onConvert={noop} />);
    expect(html).toContain('Make it a sale');
    expect(html).toContain('Keep the quoted prices');
    expect(html).toContain('Also make the invoice');
    expect(html).not.toContain('convert-due');
    const credit = renderToString(<ConvertPanel form={{ ...blankConvert(), method: 'credit' }} hasTerms={false} onChange={noop} onConvert={noop} />);
    expect(credit).toContain('id="convert-due"');
    expect(credit).toContain('Enter the date the buyer will pay.');
    expect(renderToString(<ConvertPanel form={{ ...blankConvert(), method: 'credit' }} hasTerms onChange={noop} onConvert={noop} />)).toContain('leave empty to use their terms');
  });

  it('draws the form with the buyer, the last day and the items to add', () => {
    const html = page(admin, <QuotationForm branchId={branch} onSaved={noop} />);
    expect(html).toContain('Prices are offered until');
    expect(html).toContain('data-tour="quotation-buyer"');
    expect(html).toContain('data-tour="quotation-items"');
    expect(html).toContain('Save quotation');
    expect(html).toContain('Stock is not reserved');
  });
});

describe('the credit note as a PDF', () => {
  it('is offered only while documents are on, to a user who may issue them at the return branch', () => {
    expect(page(mockMe('admin'), <CreditNotePdf returnId="r1" branchId={branch} />)).toBe('');
    expect(page(seller, <CreditNotePdf returnId="r1" branchId={branch} />)).toBe('');
    const html = page(admin, <CreditNotePdf returnId="r1" branchId={branch} />);
    expect(html).toContain('Open the credit note as a PDF');
    expect(html).toContain('data-tour="credit-note-pdf"');
  });
});
