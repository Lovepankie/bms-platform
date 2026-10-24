import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, retail, type Converted, type Product, type Quotation } from '../../../api/retail';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { EmptyState } from '../../../components/states';
import { useStaff } from '../context';
import { PdfButton, openDocumentPdf } from './documents-ui';
import { blankConvert, blankQuotation, buildConvertRequest, buildQuotationRequest, convertProblem, quotationProblem, type ConvertForm, type QuotationDraft } from './documents-state';
import { newIdempotencyKey, usePersistedDraft } from './idempotency';
import { showDate } from './maths';
import { UNITS, capabilityOn, holdsAt } from './permissions';
import { draftTotal, newLine, withUnit } from './sale-state';
import { SaleLineCard } from './sale';
import { Gate, Note, Problem, ProductPicker, money, useBranchName, useBranchView, useSingleBranch } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';

// Quotations (documents capability, ADR-033, issue #197): prices offered to a buyer until a date. A quotation reserves no stock and
// posts nothing; it is numbered and its PDF stored like every document, and it can be made into a sale (and its invoice) once,
// while it is valid. It cannot be edited: a change is a new quotation. The server prices the lines as a sale would be priced.

const METHODS: { value: ConvertForm['method']; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'mobile_money', label: 'Mobile money' },
  { value: 'bank', label: 'Bank' },
  { value: 'credit', label: 'Credit (pay later)' },
];

/** The words for where a quotation stands. */
export function quotationState(q: Pick<Quotation, 'status' | 'expired'>): { label: string; tone: 'success' | 'warning' | 'danger' | 'info' } {
  if (q.status === 'converted') return { label: 'Made into a sale', tone: 'success' };
  if (q.status === 'cancelled') return { label: 'Cancelled', tone: 'warning' };
  return q.expired ? { label: 'Expired', tone: 'danger' } : { label: 'Open', tone: 'info' };
}

/** The choices for turning a quotation into a sale: how it is paid, whether to keep the quoted prices, and whether to make the invoice. */
export function ConvertPanel({ form, hasTerms, busy, onChange, onConvert }: { form: ConvertForm; hasTerms: boolean; busy?: boolean; onChange: (form: ConvertForm) => void; onConvert: () => void }) {
  const problem = convertProblem(form, hasTerms);
  return (
    <fieldset data-testid="convert-panel">
      <legend>Make it a sale</legend>
      <label htmlFor="convert-method">How is it paid?</label>
      <select id="convert-method" value={form.method} onChange={(e) => onChange({ ...form, method: e.target.value as ConvertForm['method'] })}>
        {METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
      </select>
      {form.method === 'credit' && (
        <>
          <label htmlFor="convert-due">Buyer will pay by{hasTerms ? ' (leave empty to use their terms)' : ''}</label>
          <input id="convert-due" type="date" value={form.dueDate} onChange={(e) => onChange({ ...form, dueDate: e.target.value })} />
        </>
      )}
      <label className="check">
        <input type="checkbox" checked={form.keepPrices} onChange={(e) => onChange({ ...form, keepPrices: e.target.checked })} /> Keep the quoted prices (otherwise today&apos;s prices apply)
      </label>
      <label className="check">
        <input type="checkbox" checked={form.invoice} onChange={(e) => onChange({ ...form, invoice: e.target.checked })} /> Also make the invoice
      </label>
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      <BusyButton type="button" className="rt-primary" busy={busy === true} disabled={problem !== null} onClick={onConvert}>
        Make the sale
      </BusyButton>
    </fieldset>
  );
}

/** One quotation: who it is for, until when, what it comes to, its lines, and what can still be done with it. */
function QuotationCard({ q, branchName }: { q: Quotation; branchName?: string }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const state = quotationState(q);
  const [panel, setPanel] = useState<'convert' | 'cancel' | null>(null);
  const [form, setForm] = useState<ConvertForm>(blankConvert());
  const [key, setKey] = useState(newIdempotencyKey);
  const [reason, setReason] = useState('');
  const [done, setDone] = useState<Converted | null>(null);
  const customers = useQuery({ queryKey: ['retail', 'customers'], queryFn: () => retail.listCustomers(), enabled: panel === 'convert' && q.customer_id !== undefined });
  const hasTerms = (customers.data ?? []).find((c) => c.id === q.customer_id)?.terms_days !== undefined;
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['retail', 'quotations'] });
    void queryClient.invalidateQueries({ queryKey: ['retail', 'documents'] });
  };
  const convert = useMutation({
    mutationFn: () => retail.convertQuotation(q.id ?? '', buildConvertRequest(form), key),
    onSuccess: (r) => {
      setDone(r);
      setPanel(null);
      setKey(newIdempotencyKey());
      void queryClient.invalidateQueries({ queryKey: ['retail', 'products'] });
      void queryClient.invalidateQueries({ queryKey: ['retail', 'stock'] });
      refresh();
    },
  });
  const cancel = useMutation({
    mutationFn: () => retail.cancelQuotation(q.id ?? '', reason),
    onSuccess: () => {
      setPanel(null);
      refresh();
    },
  });
  const open = q.status === 'open' && !q.expired;
  // A store never sells (ADR-048), so a quotation made there cannot be turned into a sale.
  const canSell = holdsAt(me, 'retail.sale.create', q.branch_id) && !(me.branches ?? []).some((b) => b.id === q.branch_id && b.sells_goods === false);
  return (
    <li className="rt-card" data-testid={`quotation-${q.id}`}>
      <p>
        <strong>{q.number}</strong> <span className={`badge badge-${state.tone}`}>{state.label}</span>
      </p>
      <p>{q.buyer_name}{q.buyer_contact ? `, ${q.buyer_contact}` : ''}</p>
      <p className="hint">
        {showDate(q.quote_date)}, valid until <strong>{showDate(q.valid_until)}</strong>, <strong>{money(q.total_minor ?? 0)}</strong>{branchName ? `, ${branchName}` : ''}
        {q.price_list ? `, price list ${q.price_list}` : ''}
      </p>
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {(q.lines ?? []).map((l) => (
          <li key={l.line_no} className="hint">{l.qty} {l.unit} {l.description} at {money(l.unit_price_minor ?? 0)} = {money(l.line_total_minor ?? 0)}</li>
        ))}
      </ul>
      {q.notes && <p className="hint">{q.notes}</p>}
      {q.status === 'converted' && <p className="hint">Sale {q.converted_sale_no}</p>}
      {q.status === 'cancelled' && q.cancel_reason && <p className="hint">Cancelled because: {q.cancel_reason}</p>}
      <div className="rt-row">
        <PdfButton doc={{ id: q.document_id, pdf_document_id: q.pdf_document_id, number: q.number }} />
        {open && canSell && <button type="button" className="btn-sm" onClick={() => setPanel(panel === 'convert' ? null : 'convert')}>Make it a sale</button>}
        {open && <button type="button" className="btn-sm" onClick={() => setPanel(panel === 'cancel' ? null : 'cancel')}>Cancel</button>}
      </div>
      {q.expired && <p className="hint">The last day has passed, so it cannot be made into a sale. Make a new quotation.</p>}
      {panel === 'convert' && <ConvertPanel form={form} hasTerms={hasTerms} busy={convert.isPending} onChange={setForm} onConvert={() => convert.mutate()} />}
      {panel === 'cancel' && (
        <fieldset>
          <legend>Cancel this quotation</legend>
          <label htmlFor={`cancel-${q.id}`}>Why? (it stays on record)</label>
          <input id={`cancel-${q.id}`} value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} />
          <BusyButton type="button" busy={cancel.isPending} disabled={reason.trim() === ''} onClick={() => cancel.mutate()}>
            Cancel the quotation
          </BusyButton>
        </fieldset>
      )}
      <Problem error={convert.error ?? cancel.error} />
      {done && (
        <div role="status" className="alert alert-success" data-testid="converted">
          <p>Sale {done.sale_no} made.</p>
          {done.note && <p className="hint" data-testid="convert-note">{done.note}</p>}
          {done.invoice && <button type="button" className="btn-sm" onClick={() => void openDocumentPdf(done.invoice ?? {})}>Open invoice {done.invoice.number}</button>}
        </div>
      )}
    </li>
  );
}

/** The form that makes a quotation: buyer, last day, notes and lines. One key per draft, kept with it until saved. */
export function QuotationForm({ branchId, onSaved }: { branchId: string; onSaved: (q: Quotation) => void }) {
  const { me } = useStaff();
  const today = businessToday();
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<QuotationDraft>(`quotation:${me.user_id ?? ''}:${branchId}`, blankQuotation(branchId, today));
  const unitsOn = capabilityOn(me, 'retail', UNITS);
  const customers = useQuery({ queryKey: ['retail', 'customers'], queryFn: () => retail.listCustomers() });
  const save = useMutation({
    mutationFn: () => retail.createQuotation(buildQuotationRequest(draft), key),
    onSuccess: (q) => {
      finish();
      onSaved(q);
    },
  });
  const update = (patch: Partial<QuotationDraft>) => setDraft((d) => ({ ...d, ...patch }));
  const setLine = (i: number, patch: { qty?: string; price?: string }) => setDraft((d) => ({ ...d, lines: d.lines.map((l, n) => (n === i ? { ...l, ...patch } : l)) }));
  const add = (p: Product) => setDraft((d) => (d.lines.some((l) => l.product.id === p.id) ? d : { ...d, lines: [...d.lines, newLine(p)] }));
  const problem = quotationProblem(draft, today);
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (!problem && !save.isPending) save.mutate();
      }}
    >
      <fieldset data-tour="quotation-buyer">
        <legend>Who is it for?</legend>
        <label htmlFor="quote-customer">Credit buyer</label>
        <select id="quote-customer" value={draft.customerId} onChange={(e) => update({ customerId: e.target.value, buyerName: '', buyerContact: '' })}>
          <option value="">Not on the list: type their name</option>
          {(customers.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        {draft.customerId === '' && (
          <>
            <label htmlFor="quote-name">Buyer name</label>
            <input id="quote-name" value={draft.buyerName} maxLength={200} onChange={(e) => update({ buyerName: e.target.value })} />
            <label htmlFor="quote-contact">Phone (optional)</label>
            <input id="quote-contact" inputMode="tel" value={draft.buyerContact} maxLength={100} onChange={(e) => update({ buyerContact: e.target.value })} />
          </>
        )}
        <label htmlFor="quote-valid">Prices are offered until</label>
        <input id="quote-valid" type="date" min={today} value={draft.validUntil} onChange={(e) => update({ validUntil: e.target.value })} />
        <label htmlFor="quote-notes">Notes (optional, printed on the quotation)</label>
        <input id="quote-notes" value={draft.notes} maxLength={300} onChange={(e) => update({ notes: e.target.value })} />
      </fieldset>

      <div data-tour="quotation-items">
        <ProductPicker id="quote-search" showPrice showStock={false} onAdd={add} />
      </div>
      <h2>Items</h2>
      {draft.lines.length === 0 && <EmptyState art="noSales" title="No items yet.">Type an item above and tap it in the list.</EmptyState>}
      {draft.lines.map((l, i) => (
        <SaleLineCard
          key={`${l.product.id}-${i}`}
          line={l}
          index={i}
          unitsOn={unitsOn}
          onQty={(v) => setLine(i, { qty: v })}
          onPrice={(v) => setLine(i, { price: v })}
          onUnit={(unitId) => setDraft((d) => ({ ...d, lines: d.lines.map((x, n) => (n === i ? withUnit(x, unitId) : x)) }))}
          onRemove={() => setDraft((d) => ({ ...d, lines: d.lines.filter((_, n) => n !== i) }))}
        />
      ))}
      <p className="rt-total" aria-live="polite">Total {money(draftTotal(draft.lines))}</p>
      <p className="hint">Stock is not reserved. A price list, if the buyer has one, prices the items as it would on a sale.</p>
      {problem && draft.lines.length > 0 && <Note>{problem}</Note>}
      <Problem error={save.error} />
      <BusyButton type="submit" className="rt-primary" busy={save.isPending} disabled={problem !== null} data-tour="quotation-save">
        Save quotation
      </BusyButton>

      {draft.lines.length > 0 && !save.isPending && <button type="button" onClick={() => { discard(); save.reset(); }}>Clear this quotation</button>}
    </form>
  );
}

type Status = 'all' | 'open' | 'converted' | 'cancelled';

export function QuotationsPage() {
  const { all, branchId, branchName } = useBranchView();
  const nameOf = useBranchName();
  const single = useSingleBranch();
  const { me } = useStaff();
  const atStore = (me.branches ?? []).some((b) => b.id === single.branchId && b.sells_goods === false);
  const queryClient = useQueryClient();
  const search = useLiveSearch();
  const [status, setStatus] = useState<Status>('open');
  const [making, setMaking] = useState(false);
  const [formKey, setFormKey] = useState(0);
  const list = useInfiniteQuery({
    queryKey: ['retail', 'quotations', all ? 'all' : branchId, status, search.term],
    queryFn: ({ pageParam, signal }) => retail.listQuotations({ branchId: all ? undefined : (branchId ?? undefined), status: status === 'all' ? undefined : status, query: search.term, cursor: pageParam, signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor,
    enabled: all || branchId !== null,
    placeholderData: keepPreviousData,
  });
  const items = (list.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const fetching = list.isPlaceholderData || (list.isFetching && !list.isFetchingNextPage && list.fetchStatus !== 'idle');
  return (
    <Gate screen="quotations" title="Quotations" wide>
      <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
      <p className="hint" data-tour="quotations-help">A quotation offers prices until a date. It reserves no stock and posts nothing. When the buyer says yes, make it a sale here.</p>
      {atStore && <p className="hint">{single.branchName} is a store, and a store does not sell or quote. Choose a shop to make a quotation.</p>}
      {!making && !atStore && (
        <button type="button" className="rt-primary" disabled={single.branchId === null} data-tour="quotation-new" onClick={() => setMaking(true)}>
          New quotation
        </button>
      )}
      {!making && !atStore && single.branchId === null && <p className="hint">Choose one branch at the top of the page to make a quotation.</p>}
      {making && !atStore && single.branchId !== null && (
        <section aria-label="New quotation">
          <h2>New quotation for {single.branchName}</h2>
          <QuotationForm
            key={formKey}
            branchId={single.branchId}
            onSaved={() => {
              setMaking(false);
              setFormKey((k) => k + 1);
              void queryClient.invalidateQueries({ queryKey: ['retail', 'quotations'] });
              void queryClient.invalidateQueries({ queryKey: ['retail', 'documents'] });
            }}
          />
          <button type="button" onClick={() => setMaking(false)}>Close the form</button>
        </section>
      )}
      <div data-tour="quotations-list">
        <SearchBox id="search-quotations" label="Number or buyer" search={search} fetching={fetching} count={list.data ? items.length : undefined} more={list.hasNextPage} />
        <label htmlFor="quotation-status">Show</label>
        <select id="quotation-status" value={status} onChange={(e) => setStatus(e.target.value as Status)}>
          <option value="open">Open</option>
          <option value="converted">Made into a sale</option>
          <option value="cancelled">Cancelled</option>
          <option value="all">All</option>
        </select>
        {list.isPending && list.fetchStatus !== 'idle' && <p className="loading">Loading</p>}
        <Problem error={list.error} />
        {list.data && (
          <SearchResults fetching={fetching}>
            {items.length === 0 ? (
              search.term ? <NoResults term={search.term} onClear={search.clear} noun="quotations" /> : <p className="empty-state">No quotations here.</p>
            ) : (
              <ul style={{ listStyle: 'none', padding: 0 }}>
                {items.map((q) => <QuotationCard key={q.id} q={q} branchName={all ? nameOf(q.branch_id) : undefined} />)}
              </ul>
            )}
          </SearchResults>
        )}
        {list.hasNextPage && (
          <button type="button" disabled={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
            {list.isFetchingNextPage ? 'Loading' : 'Show more'}
          </button>
        )}
      </div>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/quotations')({ component: QuotationsPage });
