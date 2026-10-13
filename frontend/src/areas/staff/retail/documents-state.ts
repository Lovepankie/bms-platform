import { daysBefore, type ConvertRequest, type DocumentType, type QuotationRequest, type RetailDocument, type SalePayment } from '../../../api/retail';
import { qtyString } from './maths';
import { defaultPrice, lineFigures, lineHint, type DraftLine } from './sale-state';
import { chosenPack } from './units-state';

// The pure logic of the optional documents screens (documents capability, ADR-033, issue #197): the words for a
// document and its state, which documents a sale may still be given, the form of a quotation and the request it sends,
// and the prefix rule. The server is the authority on all of it (numbers, void, expiry); this only saves a round trip and
// says things plainly. Documents are an after-step: nothing here is needed to record a sale.

export const TYPE_LABEL: Record<DocumentType, string> = {
  quotation: 'Quotation',
  invoice: 'Invoice',
  delivery_note: 'Delivery note',
};

export const typeLabel = (type: string | undefined): string => TYPE_LABEL[type as DocumentType] ?? type ?? '';

export type Tone = 'success' | 'warning' | 'danger' | 'info';

/** What a document is, in words: issued, void (its sale was voided or its quotation cancelled), and for a quotation its stage. */
export function documentState(d: Pick<RetailDocument, 'status' | 'type' | 'quotation_status'>): { label: string; tone: Tone } {
  if (d.status === 'void') return { label: 'Void', tone: 'warning' };
  if (d.type === 'quotation') {
    if (d.quotation_status === 'converted') return { label: 'Made into a sale', tone: 'success' };
    if (d.quotation_status === 'expired') return { label: 'Expired', tone: 'danger' };
    return { label: 'Open', tone: 'info' };
  }
  return { label: 'Issued', tone: 'success' };
}

/** The documents a sale can still be given: one invoice and one delivery note each, none for a voided sale. */
export function issuable(existing: readonly RetailDocument[], saleStatus: string | undefined): ('invoice' | 'delivery_note')[] {
  if (saleStatus === 'voided') return [];
  return (['invoice', 'delivery_note'] as const).filter((t) => !existing.some((d) => d.type === t));
}

/** The prefix of a series: one to eight capital letters or digits, starting with a letter (the server's rule). */
export const PREFIX = /^[A-Z][A-Z0-9]{0,7}$/;

export function prefixProblem(prefix: string): string | null {
  if (prefix === '') return 'Enter a prefix, for example INV.';
  if (!PREFIX.test(prefix)) return 'Use one to eight capital letters or digits, starting with a letter.';
  return null;
}

/** A typed prefix as the series wants it: capitals, no spaces. */
export const tidyPrefix = (typed: string): string => typed.replace(/\s+/g, '').toUpperCase().slice(0, 8);

export interface QuotationDraft {
  branchId: string;
  customerId: string;
  buyerName: string;
  buyerContact: string;
  validUntil: string;
  notes: string;
  lines: DraftLine[];
}

/** A quotation is valid for thirty days unless the maker says otherwise. */
export const QUOTATION_DAYS = 30;

export function blankQuotation(branchId: string, today: string): QuotationDraft {
  return { branchId, customerId: '', buyerName: '', buyerContact: '', validUntil: daysBefore(today, -QUOTATION_DAYS), notes: '', lines: [] };
}

/** What stops the quotation being saved, in plain words, or null. Stock is not checked: a quotation reserves nothing. */
export function quotationProblem(draft: QuotationDraft, today: string): string | null {
  if (draft.customerId === '' && draft.buyerName.trim() === '') return 'Choose the buyer from the list, or type their name.';
  if (draft.validUntil === '') return 'Enter the last day the prices are offered.';
  if (draft.validUntil < today) return 'The last day cannot be before today.';
  if (draft.lines.length === 0) return 'Add at least one item.';
  if (draft.lines.some((l) => lineFigures(l) === null)) return 'Check the quantity and price on each item.';
  // A pack that does not convert exactly is the only line hint a quotation shares with a sale.
  if (draft.lines.some((l) => (lineHint(l) ?? '').includes('does not convert exactly'))) return 'Fix the items marked above.';
  return null;
}

/** The request a quotation form sends: only a price that differs from the one a sale would take is sent. */
export function buildQuotationRequest(draft: QuotationDraft): QuotationRequest {
  return {
    branch_id: draft.branchId,
    ...(draft.customerId ? { customer_id: draft.customerId } : { buyer_name: draft.buyerName.trim() }),
    ...(!draft.customerId && draft.buyerContact.trim() ? { buyer_contact: draft.buyerContact.trim() } : {}),
    valid_until: draft.validUntil,
    ...(draft.notes.trim() ? { notes: draft.notes.trim() } : {}),
    lines: draft.lines.map((l) => {
      const f = lineFigures(l);
      if (!f) throw new Error('invalid line');
      const pack = chosenPack(l.product, l.unitId);
      return {
        product_id: l.product.id ?? '',
        qty: qtyString(f.qtyMilli),
        ...(pack ? { unit_id: pack.unit_id } : {}),
        ...(f.priceMinor !== defaultPrice(l.product, pack?.unit_id) ? { unit_price_minor: f.priceMinor } : {}),
      };
    }),
  };
}

export interface ConvertForm {
  method: SalePayment;
  dueDate: string;
  keepPrices: boolean;
  invoice: boolean;
}

export const blankConvert = (): ConvertForm => ({ method: 'cash', dueDate: '', keepPrices: true, invoice: true });

/** What stops a quotation being turned into a sale, or null. A credit sale needs a date unless the buyer's terms give one. */
export function convertProblem(form: ConvertForm, hasTerms: boolean): string | null {
  if (form.method === 'credit' && form.dueDate === '' && !hasTerms) return 'Enter the date the buyer will pay.';
  return null;
}

export function buildConvertRequest(form: ConvertForm): ConvertRequest {
  return {
    payment_method: form.method,
    ...(form.method === 'credit' && form.dueDate !== '' ? { due_date: form.dueDate } : {}),
    keep_quoted_prices: form.keepPrices,
    issue_invoice: form.invoice,
  };
}
