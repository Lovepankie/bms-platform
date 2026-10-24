import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, useNavigate, useSearch } from '@tanstack/react-router';
import { useEffect, useRef } from 'react';
import { SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { businessToday, retail, type SupplierBill } from '../../../api/retail';
import { useStaff } from '../context';
import { MIN_REASON } from './account-state';
import {
  CREDIT_KINDS, blankCredit, buildCreditRequest, canCredit, creditLineProblem, creditLinesFrom, creditProblem, creditTotal, leftMinor, leftQtyMilli, lineCredit, lineUsed, usedLines,
  type CreditForm, type CreditLineForm,
} from './credits-state';
import { usePersistedDraft } from './idempotency';
import { qtyString, showQty } from './maths';
import { showDate } from './transfers';
import { Gate, Problem, money } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';

// The supplier credit note form (the purchasing capability, issue #199). Choose the supplier invoice the credit note is against, say
// whether goods are sent back or a price is adjusted, and type, line by line, how many are sent back (or how much is taken off), then
// the supplier's credit note number, the date, the reason and the total as printed. The page shows the limits as the server will
// apply them: what the invoice billed, what earlier credit notes took, and what the invoice still has to pay. The server decides.

/** Find a supplier invoice that can take a credit note. */
export function InvoicePicker({ onPick }: { onPick: (bill: SupplierBill) => void }) {
  const search = useLiveSearch();
  const term = search.term;
  const found = useQuery({ queryKey: ['retail', 'supplier-bills', 'credit-pick', term], queryFn: ({ signal }) => retail.listSupplierBills({ query: term, openOnly: true, signal }) });
  const rows = (found.data?.items ?? []).filter(canCredit);
  return (
    <div className="stack" data-tour="credit-invoice">
      <SearchBox id="credit-bill-search" label="Find the supplier invoice" hint="Type our invoice number, the supplier's invoice number or the supplier." search={search} fetching={found.isFetching} count={found.data ? rows.length : undefined} />
      <Problem error={found.error} />
      <SearchResults fetching={found.isFetching}>
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {rows.map((b) => (
            <li key={b.id} className="rt-card" data-testid={`pick-${b.bill_no}`}>
              <p>
                <strong>{b.bill_no}</strong> (invoice {b.supplier_ref}) from {b.supplier_name}, {showDate(b.bill_date)}, still owed <strong>{money(b.open_minor ?? 0)}</strong>
              </p>
              <button type="button" className="btn-sm" onClick={() => onPick(b)} aria-label={`Credit invoice ${b.bill_no}`}>Credit this invoice</button>
            </li>
          ))}
        </ul>
      </SearchResults>
      {found.data && rows.length === 0 && <p className="empty-state">{term ? 'No invoice that can take a credit note matches.' : 'No supplier invoice can take a credit note: each is paid in full or already voided.'}</p>}
    </div>
  );
}

function CreditLineCard({ line, kind, index, onChange }: { line: CreditLineForm; kind: CreditForm['kind']; index: number; onChange: (l: CreditLineForm) => void }) {
  const used = lineUsed(line, kind);
  const problem = used ? creditLineProblem(line, kind) : null;
  return (
    <li className="rt-card" data-testid={`credit-line-${index}`}>
      <p>
        <strong>{line.description}</strong> from {line.receiptNo}
        <span className="hint">
          {' '}The invoice billed {showQty(line.billedQty)} {line.unit} at {money(line.unitPriceMinor)} each, {money(line.lineTotalMinor)} in all.
          {line.creditedMilli > 0 || line.creditedMinor > 0 ? ` Earlier credit notes took ${showQty(qtyString(line.creditedMilli))} ${line.unit} and ${money(line.creditedMinor)}.` : ''}
          {kind === 'goods' ? ` Up to ${showQty(qtyString(leftQtyMilli(line)))} ${line.unit} can still be sent back.` : ` Up to ${money(leftMinor(line))} can still be taken off.`}
        </span>
      </p>
      {kind === 'goods' ? (
        <div className="rt-row">
          <div>
            <label htmlFor={`cq-${index}`}>Quantity of {line.description} sent back</label>
            <input id={`cq-${index}`} inputMode="decimal" value={line.qty} placeholder="0" onChange={(e) => onChange({ ...line, qty: e.target.value })} aria-invalid={problem !== null} />
          </div>
          <div>
            <label htmlFor={`cu-${index}`}>Credit for one {line.unit} of {line.description}</label>
            <input id={`cu-${index}`} inputMode="numeric" value={line.unitCredit} onChange={(e) => onChange({ ...line, unitCredit: e.target.value })} aria-invalid={problem !== null} />
          </div>
        </div>
      ) : (
        <>
          <label htmlFor={`ca-${index}`}>Amount to take off {line.description}</label>
          <input id={`ca-${index}`} inputMode="numeric" value={line.amount} placeholder="0" onChange={(e) => onChange({ ...line, amount: e.target.value })} aria-invalid={problem !== null} />
        </>
      )}
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      {used && problem === null && <p data-testid={`credit-line-total-${index}`}>Credit <strong>{money(lineCredit(line, kind))}</strong></p>}
    </li>
  );
}

/** The whole form, controlled by the page so a draft survives a reload. `bill` is the chosen invoice once it has loaded. */
export function CreditFormView({ form, bill, loadingLines, busy, error, onChange, onSubmit, onOtherInvoice }: {
  form: CreditForm;
  bill?: SupplierBill;
  loadingLines?: boolean;
  busy?: boolean;
  error?: unknown;
  onChange: (form: CreditForm) => void;
  onSubmit: () => void;
  onOtherInvoice: () => void;
}) {
  const problem = creditProblem(form, bill?.open_minor);
  const used = usedLines(form);
  const setLine = (i: number, line: CreditLineForm) => onChange({ ...form, lines: form.lines.map((l, n) => (n === i ? line : l)) });
  return (
    <form className="stack" onSubmit={(e) => { e.preventDefault(); if (!problem && !busy) onSubmit(); }} data-tour="credit-form">
      {bill && (
        <section className="rt-card" aria-label="The invoice" data-testid="credit-invoice">
          <p>
            Credit note against <strong>{bill.bill_no}</strong> (invoice {bill.supplier_ref}) from {bill.supplier_name}. The invoice is for {money(bill.total_minor ?? 0)} and still has <strong>{money(bill.open_minor ?? 0)}</strong> to pay.
          </p>
          <button type="button" className="btn-ghost" onClick={onOtherInvoice}>Choose another invoice</button>
        </section>
      )}
      <fieldset data-tour="credit-kind">
        <legend>What the credit note is for</legend>
        {CREDIT_KINDS.map((k) => (
          <p key={k.value}>
            <label>
              <input type="radio" name="credit-kind" checked={form.kind === k.value} onChange={() => onChange({ ...form, kind: k.value })} />
              <span><strong>{k.label}.</strong> {k.sentence}</span>
            </label>
          </p>
        ))}
      </fieldset>
      <h2>The lines</h2>
      {loadingLines && <p className="loading">Loading</p>}
      {!loadingLines && form.lines.length === 0 && <p className="empty-state">This invoice has no lines to credit.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }} data-tour="credit-lines">
        {form.lines.map((l, i) => <CreditLineCard key={l.billLineId} line={l} kind={form.kind} index={i} onChange={(x) => setLine(i, x)} />)}
      </ul>
      {used.length > 0 && <p className="hint" data-testid="credit-lines-total">The lines come to {money(creditTotal(form))}.</p>}
      <label htmlFor="credit-ref">Supplier&apos;s credit note number</label>
      <input id="credit-ref" value={form.supplierRef} maxLength={60} onChange={(e) => onChange({ ...form, supplierRef: e.target.value })} />
      <label htmlFor="credit-date">Date of the credit note</label>
      <input id="credit-date" type="date" value={form.creditDate} onChange={(e) => onChange({ ...form, creditDate: e.target.value })} />
      <label htmlFor="credit-reason">Why is it given? (at least {MIN_REASON} characters)</label>
      <input id="credit-reason" value={form.reason} maxLength={300} onChange={(e) => onChange({ ...form, reason: e.target.value })} />
      <label htmlFor="credit-total">Credit total, as printed</label>
      <input id="credit-total" inputMode="numeric" value={form.total} onChange={(e) => onChange({ ...form, total: e.target.value })} />
      <label htmlFor="credit-note">Note (optional)</label>
      <input id="credit-note" value={form.note} maxLength={300} onChange={(e) => onChange({ ...form, note: e.target.value })} />
      {problem && form.billId !== '' && used.length > 0 && <p role="alert" className="rt-flag" data-testid="credit-problem">{problem}</p>}
      <Problem error={error} />
      <BusyButton type="submit" className="rt-primary" busy={busy === true} disabled={problem !== null} data-tour="credit-save">Save credit note</BusyButton>

    </form>
  );
}

export function SupplierCreditNewPage() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const params = useSearch({ strict: false }) as { bill?: string };
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<CreditForm>(`supplier-credit:${me.user_id ?? ''}`, blankCredit(businessToday()));
  // "Credit note" from an invoice arrives with the invoice chosen.
  const wanted = typeof params.bill === 'string' ? params.bill : '';
  const applied = useRef('');
  useEffect(() => {
    if (wanted && applied.current !== wanted) {
      applied.current = wanted;
      setDraft((d) => (d.billId === wanted ? d : { ...d, billId: wanted, lines: [], total: '' }));
    }
  }, [wanted, setDraft]);
  const billId = draft.billId;
  const bill = useQuery({ queryKey: ['retail', 'supplier-bill', billId], queryFn: () => retail.getSupplierBill(billId), enabled: billId !== '' });
  // What earlier credit notes that stand have taken from each line, so the limits shown are the real ones.
  const earlier = useQuery({
    queryKey: ['retail', 'supplier-credits', 'of-bill', billId],
    queryFn: async () => {
      const page = await retail.listSupplierCredits({ billId, status: 'posted' });
      return Promise.all((page.items ?? []).map((c) => retail.getSupplierCredit(c.id ?? '')));
    },
    enabled: billId !== '',
  });
  // Until the person types, the lines are the invoice's own; the first change keeps the whole form.
  const form: CreditForm = draft.lines.length === 0 && bill.data && earlier.data && billId !== '' ? { ...draft, lines: creditLinesFrom(bill.data, earlier.data) } : draft;
  const save = useMutation({
    mutationFn: () => retail.createSupplierCredit(buildCreditRequest(form), key),
    onSuccess: (made) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      void navigate({ to: '/staff/retail/supplier-credits/$creditId', params: { creditId: made.id ?? '' } });
    },
  });
  return (
    <Gate screen="supplierCredits" title="New supplier credit note">
      <p className="hint">Record a credit note the supplier gave you. Use the number, the date and the total printed on it.</p>
      {billId === '' ? (
        <InvoicePicker onPick={(b) => setDraft((d) => ({ ...d, billId: b.id ?? '', lines: [], total: '' }))} />
      ) : (
        <>
          <Problem error={bill.error ?? earlier.error} />
          <CreditFormView
            form={form}
            bill={bill.data}
            loadingLines={(bill.isFetching || earlier.isFetching) && form.lines.length === 0}
            busy={save.isPending}
            error={save.error}
            onChange={(f) => setDraft(() => f)}
            onSubmit={() => save.mutate()}
            onOtherInvoice={() => { discard(); save.reset(); }}
          />
        </>
      )}
      {billId !== '' && !save.isPending && <button type="button" className="btn-ghost" onClick={() => { discard(); save.reset(); }}>Clear this form</button>}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/supplier-credits/new')({ component: SupplierCreditNewPage });
