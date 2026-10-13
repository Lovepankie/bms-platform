import type { Converted, DocumentSeries, DocumentType, Quotation, QuotationLine, RetailApi, RetailDocument, Sale, SaleRequest } from './retail';

// Fabricated optional documents for VITE_RETAIL_MOCK and for tests (ADR-033, issue #197): the REAL snake_case shapes of
// docs/sdd/07-api-design.md section 7.11.20 held in memory, with the server's rules in small: a numbered series per type and
// branch (INV-HQ-000001), a document issued once per sale and type, a PDF that is "rendered" on the first request, void
// following the sale, and a quotation that is made, cancelled or converted once. Every route answers 404
// capability_not_enabled while the capability is off. Nothing here is real.

export interface DocumentsEnv {
  /** The documents capability of this mock instance. */
  on: () => boolean;
  today: () => string;
  nextId: (prefix: string) => string;
  delay: <T>(value: T) => Promise<T>;
  run: <T>(fn: () => T) => Promise<T>;
  once: <T>(key: string, request: unknown, make: () => T) => T;
  refuse: (status: number, code: string, detail: string) => never;
  sales: () => Sale[];
  branches: () => { id: string; code: string; name: string }[];
  product: (id: string) => { code: string; description: string; unit: string; sellMinor: number } | undefined;
  customer: (id: string) => { name?: string; contact?: string } | undefined;
  /** The sale a conversion makes, through the mock's own sale path. */
  makeSale: (body: SaleRequest, key: string) => Promise<Sale>;
}

export type DocumentsMock = Pick<
  RetailApi,
  | 'listSaleDocuments' | 'issueSaleDocument' | 'issueTransferDocument' | 'listDocuments' | 'renderDocument' | 'documentUrl' | 'documentSeries'
  | 'setDocumentPrefix' | 'renderCreditNote' | 'listQuotations' | 'getQuotation' | 'createQuotation' | 'cancelQuotation' | 'convertQuotation' | 'customerStatementPdf'
>;

const DEFAULT_PREFIX: Record<DocumentType, string> = { quotation: 'QT', invoice: 'INV', delivery_note: 'DN' };
const TYPES: DocumentType[] = ['quotation', 'invoice', 'delivery_note'];

export function createDocumentsMock(env: DocumentsEnv): DocumentsMock {
  const docs: (RetailDocument & { pdf?: string })[] = [];
  const quotations: (Quotation & { source?: string })[] = [];
  const counters = new Map<string, number>();
  const prefixes = new Map<DocumentType, string>();
  const gate = <T>(fn: () => T): Promise<T> => env.run(() => (env.on() ? fn() : env.refuse(404, 'capability_not_enabled', 'This capability is not enabled for the tenant.')));
  const branch = (id: string) => env.branches().find((b) => b.id === id)?.code ?? 'HQ';
  const number = (type: DocumentType, branchId: string, seq: number) => `${prefixes.get(type) ?? DEFAULT_PREFIX[type]}-${branch(branchId)}-${String(seq).padStart(6, '0')}`;
  const take = (type: DocumentType, branchId: string): { number: string; seq: number } => {
    const key = `${type}|${branchId}`;
    const seq = (counters.get(key) ?? 0) + 1;
    counters.set(key, seq);
    return { number: number(type, branchId, seq), seq };
  };
  const saleOf = (id: string): Sale => env.sales().find((s) => s.id === id) ?? env.refuse(404, 'not_found', 'That could not be found.');
  const view = (d: RetailDocument & { pdf?: string }, created = false): RetailDocument => {
    const sale = d.sale_id ? env.sales().find((s) => s.id === d.sale_id) : undefined;
    const quotation = d.quotation_id ? quotations.find((q) => q.id === d.quotation_id) : undefined;
    const void_ = sale?.status === 'voided' || quotation?.status === 'cancelled';
    const { pdf, ...rest } = d;
    return {
      ...rest,
      status: void_ ? 'void' : 'issued',
      created,
      ...(pdf ? { pdf_document_id: pdf } : {}),
      ...(quotation ? { quotation_status: quotation.status === 'open' && (quotation.valid_until ?? '') < env.today() ? 'expired' : quotation.status } : {}),
    };
  };
  const render = (d: RetailDocument & { pdf?: string }) => {
    d.pdf = d.pdf ?? env.nextId('f');
  };
  const quotationView = (q: Quotation): Quotation => {
    const doc = docs.find((d) => d.quotation_id === q.id);
    return { ...q, expired: q.status === 'open' && (q.valid_until ?? '') < env.today(), ...(doc?.pdf ? { pdf_document_id: doc.pdf } : {}) };
  };

  const issue = (saleId: string, type: DocumentType, showPrices: boolean | undefined, source?: string): { doc: RetailDocument & { pdf?: string }; created: boolean } => {
    const sale = saleOf(saleId);
    if (sale.status === 'voided') env.refuse(422, 'sale_voided', 'A voided sale takes no new document.');
    const existing = docs.find((d) => d.sale_id === saleId && d.type === type);
    if (existing) return { doc: existing, created: false };
    const n = take(type, sale.branch_id ?? '');
    const prices = type === 'invoice' || showPrices === true;
    const buyer = sale.buyer_name ?? (sale.customer_id ? env.customer(sale.customer_id)?.name : undefined);
    const doc: RetailDocument & { pdf?: string } = {
      id: env.nextId('d'), type, number: n.number, branch_id: sale.branch_id, issue_date: env.today(), sale_id: saleId, currency: 'UGX',
      ...(source ? { source_document_id: source } : {}), ...(buyer ? { buyer_name: buyer } : {}), ...(prices ? { total_minor: sale.total_minor } : {}), created_at: new Date().toISOString(),
    };
    docs.push(doc);
    return { doc, created: true };
  };

  const series = (): DocumentSeries[] =>
    TYPES.map((type) => ({
      type, prefix: prefixes.get(type) ?? DEFAULT_PREFIX[type], default_prefix: DEFAULT_PREFIX[type],
      branches: env.branches().map((b) => {
        const issued = counters.get(`${type}|${b.id}`) ?? 0;
        return { branch_id: b.id, branch_code: b.code, issued, next: number(type, b.id, issued + 1) };
      }),
    }));

  return {
    listSaleDocuments: (saleId) => gate(() => docs.filter((d) => d.sale_id === saleId).map((d) => view(d))),

    issueSaleDocument: (saleId, body, key) =>
      gate(() =>
        env.once(`document|${key}`, { saleId, ...body }, () => {
          const { doc, created } = issue(saleId, body.type as DocumentType, body.show_prices);
          render(doc);
          return view(doc, created);
        }),
      ),

    issueTransferDocument: (transferId, key) =>
      gate(() =>
        env.once(`document|${key}`, { transferId }, () => {
          let doc = docs.find((d) => d.transfer_id === transferId);
          const created = doc === undefined;
          if (!doc) {
            const branchId = env.branches()[0]?.id ?? '';
            doc = { id: env.nextId('d'), type: 'delivery_note', number: take('delivery_note', branchId).number, branch_id: branchId, issue_date: env.today(), transfer_id: transferId, currency: 'UGX', created_at: new Date().toISOString() };
            docs.push(doc);
          }
          render(doc);
          return view(doc, created);
        }),
      ),

    listDocuments: ({ branchId, type, from, to, query }) =>
      gate(() => {
        const q = (query ?? '').trim().toLowerCase();
        const items = docs
          .filter((d) => (!branchId || d.branch_id === branchId) && (!type || d.type === type) && (!from || (d.issue_date ?? '') >= from) && (!to || (d.issue_date ?? '') <= to))
          .filter((d) => !q || (d.number ?? '').toLowerCase().includes(q) || (d.buyer_name ?? '').toLowerCase().includes(q))
          .map((d) => view(d))
          .reverse();
        return { items };
      }),

    renderDocument: (id) =>
      gate(() => {
        const d = docs.find((x) => x.id === id) ?? env.refuse(404, 'not_found', 'That could not be found.');
        render(d);
        return view(d);
      }),

    documentUrl: (pdfId) => gate(() => `/mock/documents/${pdfId}.pdf`),

    documentSeries: () => gate(series),

    setDocumentPrefix: (type, prefix) =>
      gate(() => {
        if (!/^[A-Z][A-Z0-9]{0,7}$/.test(prefix)) env.refuse(422, 'validation_failed', 'The prefix is one to eight capital letters or digits, starting with a letter.');
        prefixes.set(type, prefix);
        return series();
      }),

    listQuotations: ({ branchId, status, query }) =>
      gate(() => {
        const q = (query ?? '').trim().toLowerCase();
        const items = quotations
          .filter((x) => (!branchId || x.branch_id === branchId) && (!status || x.status === status))
          .filter((x) => !q || (x.number ?? '').toLowerCase().includes(q) || (x.buyer_name ?? '').toLowerCase().includes(q))
          .map(quotationView)
          .reverse();
        return { items };
      }),

    getQuotation: (id) => gate(() => quotationView(quotations.find((x) => x.id === id) ?? env.refuse(404, 'not_found', 'That could not be found.'))),

    createQuotation: (body, key) =>
      gate(() =>
        env.once(`quotation|${key}`, body, () => {
          const today = env.today();
          if ((body.valid_until ?? '') < today) env.refuse(422, 'validation_failed', 'A quotation cannot be valid until a day that has passed.');
          const branchId = body.branch_id ?? env.branches()[0]?.id ?? '';
          const lines: QuotationLine[] = body.lines.map((l, n) => {
            const p = env.product(l.product_id) ?? env.refuse(422, 'validation_failed', 'No such product.');
            const price = l.unit_price_minor ?? p.sellMinor;
            const qty = Number(l.qty);
            return { line_no: n + 1, product_id: l.product_id, code: p.code, description: p.description, qty: String(qty), unit: p.unit, unit_price_minor: price, line_total_minor: Math.round(price * qty) };
          });
          const buyer = body.customer_id ? env.customer(body.customer_id)?.name : body.buyer_name;
          const n = take('quotation', branchId);
          const id = env.nextId('q');
          const doc: RetailDocument & { pdf?: string } = {
            id: env.nextId('d'), type: 'quotation', number: n.number, branch_id: branchId, issue_date: today, quotation_id: id, currency: 'UGX', ...(buyer ? { buyer_name: buyer } : {}),
            total_minor: lines.reduce((s, l) => s + (l.line_total_minor ?? 0), 0), valid_until: body.valid_until, created_at: new Date().toISOString(),
          };
          render(doc);
          docs.push(doc);
          const q: Quotation = {
            id, number: n.number, document_id: doc.id, branch_id: branchId, quote_date: today, valid_until: body.valid_until, ...(body.customer_id ? { customer_id: body.customer_id } : {}),
            buyer_name: buyer ?? '', ...(body.buyer_contact ? { buyer_contact: body.buyer_contact } : {}), currency: 'UGX', total_minor: doc.total_minor ?? 0, ...(body.notes ? { notes: body.notes } : {}),
            status: 'open', expired: false, lines, created_at: new Date().toISOString(), version: 1,
          };
          quotations.push(q);
          return quotationView(q);
        }),
      ),

    cancelQuotation: (id, reason) =>
      gate(() => {
        const q = quotations.find((x) => x.id === id) ?? env.refuse(404, 'not_found', 'That could not be found.');
        if (q.status !== 'open') env.refuse(409, 'quotation_not_open', `This quotation is ${q.status} already.`);
        if (reason.trim() === '') env.refuse(422, 'validation_failed', 'Give a reason.');
        q.status = 'cancelled';
        q.cancel_reason = reason.trim();
        q.version = (q.version ?? 1) + 1;
        return quotationView(q);
      }),

    convertQuotation: (id, body, key) =>
      env.run(() => (env.on() ? undefined : env.refuse(404, 'capability_not_enabled', 'This capability is not enabled for the tenant.'))).then(
        () => {
          const q = quotations.find((x) => x.id === id) ?? env.refuse(404, 'not_found', 'That could not be found.');
          if (q.status !== 'open') env.refuse(409, 'quotation_not_open', `This quotation is ${q.status} already.`);
          if ((q.valid_until ?? '') < env.today()) env.refuse(422, 'quotation_expired', 'This quotation has expired. Make a new one.');
          return env
            .makeSale(
              {
                branch_id: q.branch_id, payment_method: body.payment_method as SaleRequest['payment_method'], ...(q.customer_id ? { customer_id: q.customer_id } : { buyer_name: q.buyer_name }),
                ...(body.due_date ? { due_date: body.due_date } : {}),
                lines: (q.lines ?? []).map((l) => ({ product_id: l.product_id ?? '', qty: l.qty ?? '1', ...(body.keep_quoted_prices ? { unit_price_minor: l.unit_price_minor } : {}) })),
              },
              `convert|${key}`,
            )
            .then((sale): Converted => {
              q.status = 'converted';
              q.converted_sale_id = sale.id;
              q.converted_sale_no = sale.sale_no;
              q.version = (q.version ?? 1) + 1;
              const source = docs.find((d) => d.quotation_id === q.id)?.id;
              let invoice: RetailDocument | undefined;
              if (body.issue_invoice) {
                const made = issue(sale.id ?? '', 'invoice', true, source);
                render(made.doc);
                invoice = view(made.doc, made.created);
              }
              return { quotation_id: q.id, sale_id: sale.id, sale_no: sale.sale_no, ...(invoice ? { invoice } : {}) };
            });
        },
      ),

    renderCreditNote: (returnId) => gate(() => ({ return_id: returnId, credit_note_no: 'CN-MOCK', status: 'issued', pdf_document_id: env.nextId('f'), created: true })),

    customerStatementPdf: () => gate(() => new Blob(['%PDF-1.4 mock statement'], { type: 'application/pdf' })),
  };
}
