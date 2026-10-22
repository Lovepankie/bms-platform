import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { retail, type DocumentSeries, type DocumentType, type RetailDocument } from '../../../api/retail';
import { Highlight } from '../../../app/ui/SearchBox';
import { useStaff } from '../context';
import { documentState, issuable, prefixProblem, tidyPrefix, typeLabel } from './documents-state';
import { newIdempotencyKey } from './idempotency';
import { showDate } from './maths';
import { DOCUMENT_ISSUE, canIssueDocuments, holdsAt } from './permissions';
import { Problem, money } from './ui';

// The parts of the optional documents screens that more than one page shows (documents capability, ADR-033, issue #197):
// opening a document's PDF, the card of a document, the numbered series, and the documents of one sale. A document is an
// after-step: none of this is in the way of recording a sale, and none of it draws while the capability is off.

/**
 * A new, empty tab for a PDF that is about to be made, opened before the waiting so a pop-up blocker does not stop it. Its opener is
 * cut at once, so the page that loads there (a signed file link) can never reach back into this one.
 */
function openTab(): Window | null {
  if (typeof window === 'undefined' || typeof window.open !== 'function') return null;
  const tab = window.open('about:blank', '_blank');
  if (tab) tab.opener = null;
  return tab;
}

/**
 * Opens the PDF of a document in a new tab. A document whose PDF was never stored (a render failed when it was issued) is rendered
 * from its snapshot first. The tab is opened before the waiting so a browser does not take it for a pop-up.
 */
export async function openDocumentPdf(doc: Pick<RetailDocument, 'id' | 'pdf_document_id'>): Promise<void> {
  const tab = openTab();
  try {
    const stored = doc.pdf_document_id ? doc.pdf_document_id : (await retail.renderDocument(doc.id ?? '')).pdf_document_id;
    const url = await retail.documentUrl(stored ?? '');
    if (tab) tab.location.href = url;
    else if (typeof window !== 'undefined') window.location.href = url;
  } catch (e) {
    tab?.close();
    throw e;
  }
}

/**
 * Opens the credit note PDF of a return in a new tab, opened before the waiting. The credit note carries the return's own number;
 * nothing here numbers anything.
 */
export async function openCreditNotePdf(returnId: string): Promise<void> {
  const tab = openTab();
  try {
    const made = await retail.renderCreditNote(returnId);
    const url = await retail.documentUrl(made.pdf_document_id ?? '');
    if (tab) tab.location.href = url;
    else if (typeof window !== 'undefined') window.location.href = url;
  } catch (e) {
    tab?.close();
    throw e;
  }
}

/** The button that opens a credit note as a PDF: only while documents are on and the user may issue them at the return's branch. */
export function CreditNotePdf({ returnId, branchId }: { returnId: string; branchId?: string }) {
  const { me } = useStaff();
  const open = useMutation({ mutationFn: () => openCreditNotePdf(returnId) });
  if (!canIssueDocuments(me) || !holdsAt(me, DOCUMENT_ISSUE, branchId)) return null;
  return (
    <p>
      <BusyButton busy={open.isPending} type="button" className="btn-sm" data-tour="credit-note-pdf" onClick={() => open.mutate()}>
        Open the credit note as a PDF
      </BusyButton>
      <Problem error={open.error} />
    </p>
  );
}

/**
 * Opens a PDF that has to be made first (the customer statement, which is rendered on each request) in a new tab. The tab is opened
 * before the waiting, as {@link openDocumentPdf} does, so a pop-up blocker does not stop it.
 */
export async function openMadePdf(make: () => Promise<Blob>): Promise<void> {
  const tab = openTab();
  try {
    const url = URL.createObjectURL(await make());
    if (tab) tab.location.href = url;
    else window.location.href = url;
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch (e) {
    tab?.close();
    throw e;
  }
}

/** The button that opens a document's PDF, with its own waiting and its own failure. */
export function PdfButton({ doc, label = 'Open PDF' }: { doc: Pick<RetailDocument, 'id' | 'pdf_document_id' | 'number'>; label?: string }) {
  const open = useMutation({ mutationFn: () => openDocumentPdf(doc) });
  return (
    <>
      <BusyButton busy={open.isPending} type="button" className="btn-sm" aria-label={`${label} of ${doc.number ?? 'the document'}`} onClick={() => open.mutate()}>
        {label}
      </BusyButton>
      <Problem error={open.error} />
    </>
  );
}

/** One document as a card: its number, what it is, who for, the day and the amount, and how to open it. */
export function DocumentCard({ doc, branchName, term = '' }: { doc: RetailDocument; branchName?: string; term?: string }) {
  const state = documentState(doc);
  return (
    <li className="rt-card" data-testid={`document-${doc.id}`}>
      <p>
        <strong><Highlight text={doc.number ?? ''} term={term} /></strong> <span className={`badge badge-${state.tone}`}>{state.label}</span>
      </p>
      <p>
        {typeLabel(doc.type)}
        {doc.buyer_name ? <>, <Highlight text={doc.buyer_name} term={term} /></> : null}
      </p>
      <p className="hint">
        {showDate(doc.issue_date)}
        {doc.total_minor !== undefined ? `, ${money(doc.total_minor)}` : ''}
        {doc.type === 'quotation' && doc.valid_until ? `, valid until ${showDate(doc.valid_until)}` : ''}
        {branchName ? `, ${branchName}` : ''}
      </p>
      {doc.status === 'void' && <p className="hint">Its sale or quotation was cancelled. The PDF stays as it was issued.</p>}
      <PdfButton doc={doc} />
    </li>
  );
}

export function DocumentCards({ items, branchOf, term }: { items: RetailDocument[]; branchOf?: (id: string | undefined) => string; term?: string }) {
  if (items.length === 0) return <p className="empty-state">No documents found.</p>;
  return (
    <ul style={{ listStyle: 'none', padding: 0 }}>
      {items.map((d) => <DocumentCard key={d.id} doc={d} branchName={branchOf?.(d.branch_id)} term={term} />)}
    </ul>
  );
}

/** The types a series numbers: a credit note is numbered by its return, not here. */
const NUMBERED: DocumentType[] = ['quotation', 'invoice', 'delivery_note'];

/** One series: its prefix (changed by whoever may change settings) and, per branch, how many were issued and the next number. */
export function SeriesRow({ series, canEdit, busy, onSave }: { series: DocumentSeries; canEdit: boolean; busy?: boolean; onSave: (type: DocumentType, prefix: string) => void }) {
  const type = (series.type ?? 'invoice') as DocumentType;
  const [typed, setTyped] = useState(series.prefix ?? '');
  const problem = prefixProblem(typed);
  const changed = typed !== (series.prefix ?? '');
  return (
    <li className="rt-card" data-testid={`series-${type}`}>
      <strong>{typeLabel(type)}</strong>
      {canEdit ? (
        <div className="rt-row">
          <div>
            <label htmlFor={`prefix-${type}`}>Prefix</label>
            <input id={`prefix-${type}`} value={typed} maxLength={8} aria-invalid={problem !== null} onChange={(e) => setTyped(tidyPrefix(e.target.value))} />
          </div>
          <BusyButton busy={busy ?? false} type="button" disabled={!changed || problem !== null} aria-label={`Save the ${typeLabel(type)} prefix`} onClick={() => onSave(type, typed)}>
            Save
          </BusyButton>
        </div>
      ) : (
        <p>Prefix {series.prefix}</p>
      )}
      {canEdit && problem && changed && <p role="alert" className="rt-flag">{problem}</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {(series.branches ?? []).map((b) => (
          <li key={b.branch_id} className="hint">
            {b.branch_code}: next {b.next}, {b.issued} issued
          </li>
        ))}
      </ul>
      {series.prefix !== series.default_prefix && <p className="hint">The usual prefix is {series.default_prefix}. Numbers already issued keep theirs.</p>}
    </li>
  );
}

export function SeriesPanel({ series, canEdit, busy, onSave }: { series: DocumentSeries[]; canEdit: boolean; busy?: boolean; onSave: (type: DocumentType, prefix: string) => void }) {
  const shown = series.filter((s) => NUMBERED.includes((s.type ?? '') as DocumentType));
  return (
    <section aria-label="Document numbers" data-tour="document-numbers">
      <h2>Document numbers</h2>
      <p className="hint">Each document is numbered in its branch: the prefix, the branch code and a counter that never skips and never repeats. Changing a prefix changes later numbers only.</p>
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {shown.map((s) => <SeriesRow key={s.type} series={s} canEdit={canEdit} busy={busy} onSave={onSave} />)}
      </ul>
    </section>
  );
}

/**
 * The documents of one sale, and the buttons that make the ones it does not have (an invoice, a delivery note). Shown only
 * while the documents capability is on and the user may issue documents at the sale's branch; a sale never needs it.
 */
export function SaleDocuments({ saleId, status, branchId }: { saleId: string; status?: string; branchId?: string }) {
  const { me } = useStaff();
  const allowed = canIssueDocuments(me) && holdsAt(me, DOCUMENT_ISSUE, branchId);
  const queryClient = useQueryClient();
  const [showPrices, setShowPrices] = useState(false);
  const docs = useQuery({ queryKey: ['retail', 'sale-documents', saleId], queryFn: () => retail.listSaleDocuments(saleId), enabled: allowed });
  const issue = useMutation({
    mutationFn: (type: 'invoice' | 'delivery_note') =>
      retail.issueSaleDocument(saleId, { type, ...(type === 'delivery_note' && showPrices ? { show_prices: true } : {}) }, newIdempotencyKey()),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['retail', 'sale-documents', saleId] });
      void queryClient.invalidateQueries({ queryKey: ['retail', 'documents'] });
    },
  });
  if (!allowed) return null;
  const items = docs.data ?? [];
  const more = issuable(items, status);
  return (
    <section aria-label="Documents of this sale" data-tour="sale-documents">
      <h2>Documents</h2>
      <p className="hint">The till receipt is the default. Make an invoice or a delivery note only when the buyer needs one.</p>
      {docs.isPending && <p className="loading">Loading</p>}
      <Problem error={docs.error} />
      {items.length > 0 && <ul style={{ listStyle: 'none', padding: 0 }}>{items.map((d) => <DocumentCard key={d.id} doc={d} />)}</ul>}
      {more.includes('delivery_note') && (
        <label className="check">
          <input type="checkbox" checked={showPrices} onChange={(e) => setShowPrices(e.target.checked)} /> Show the prices on the delivery note
        </label>
      )}
      <div className="rt-row">
        {more.map((t) => (
          <BusyButton key={t} busy={issue.isPending && issue.variables === t} type="button" disabled={issue.isPending} onClick={() => issue.mutate(t)}>
            {`Make ${typeLabel(t).toLowerCase()}`}
          </BusyButton>
        ))}
      </div>
      {status === 'voided' && <p className="hint">A voided sale takes no new document.</p>}
      <Problem error={issue.error} />
    </section>
  );
}

/**
 * The delivery note of a stock move: quantities only, never prices, for whoever carries the stock. Shown only while the documents
 * capability is on, to a user who may issue documents at the source branch, for a move that was not cancelled. Issuing is
 * idempotent by move, so a second tap shows the same note.
 */
export function TransferDocument({ transferId, branchId, status }: { transferId: string; branchId?: string; status?: string }) {
  const { me } = useStaff();
  const allowed = canIssueDocuments(me) && holdsAt(me, DOCUMENT_ISSUE, branchId) && status !== 'voided' && status !== 'cancelled';
  const [made, setMade] = useState<RetailDocument | null>(null);
  const issue = useMutation({ mutationFn: () => retail.issueTransferDocument(transferId, newIdempotencyKey()), onSuccess: setMade });
  if (!allowed) return null;
  return (
    <section aria-label="Delivery note of this move" data-tour="transfer-document">
      <h2>Delivery note</h2>
      <p className="hint">Quantities only, never prices. For the person who carries the stock.</p>
      {made ? (
        <ul style={{ listStyle: 'none', padding: 0 }}><DocumentCard doc={made} /></ul>
      ) : (
        <BusyButton busy={issue.isPending} type="button" onClick={() => issue.mutate()}>Make delivery note</BusyButton>
      )}
      <Problem error={issue.error} />
    </section>
  );
}
