import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, useNavigate } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { businessToday, retail, type SupplierBillMatch } from '../../../api/retail';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { MIN_REASON } from './account-state';
import { showQty } from './maths';
import {
  billLineProblem, billLinesFrom, billLineTotal, billLinesTotal, billProblem, blankBill, buildBillRequest, invoiceSentence, matchKey, matchReady, ticked,
  type BillForm, type BillLineForm,
} from './payables-state';
import { SupplierField, useSuppliers, type SupplierOption } from './purchasing-ui';
import { Gate, Problem, money } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Hint } from '../../../app/ui/Hint';

// The supplier invoice form (the purchasing capability, ADR-033, issue #198). Choose the supplier and the goods received from them
// that the invoice is for, then type what the invoice says: its number, dates, the supplier's price and the total as printed. The
// page checks the invoice against what was ordered and received (the server's match, repeated as the form changes) and shows, line
// by line, what the invoice says, what the goods were received at, and the difference. A difference above what the business allows
// needs a reason; a total that is not the sum of the lines stops the save.

/** The three-way check: for each line what the invoice says, what the goods were received at, and the difference. */
export function MatchPanel({ match, checking }: { match: SupplierBillMatch; checking?: boolean }) {
  return (
    <section className="rt-card" aria-label="Check against the goods received" data-testid="bill-match" aria-busy={checking}>
      <h2>Checked against the goods received</h2>
      <div className="table-wrap" tabIndex={0}>
        <table>
          <thead>
            <tr><th>Item</th><th className="num">Invoice says</th><th className="num">Received at</th><th className="num">Difference</th></tr>
          </thead>
          <tbody>
            {(match.lines ?? []).map((l, i) => (
              <tr key={l.receipt_line_id ?? i}>
                <td>{l.description}</td>
                <td className="num">{money(l.line_total_minor ?? 0)}</td>
                <td className="num">{money(l.cleared_minor ?? 0)}</td>
                <td className="num">{money(l.variance_minor ?? 0)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td><strong>Total</strong></td>
              <td className="num"><strong>{money(match.total_minor ?? 0)}</strong></td>
              <td className="num"><strong>{money(match.cleared_minor ?? 0)}</strong></td>
              <td className="num"><strong>{money(match.variance_minor ?? 0)}</strong></td>
            </tr>
          </tfoot>
        </table>
      </div>
      <p data-testid="bill-variance">{invoiceSentence(match.variance_minor)}</p>
      <p className="hint">The business allows a difference of up to {money(match.tolerance_minor ?? 0)} without a reason.</p>
      {match.total_mismatch && (
        <p role="alert" className="alert alert-danger" data-testid="bill-total-mismatch">The total you typed is not the sum of the lines. Check both against the paper invoice before saving.</p>
      )}
      {match.reason_required && (
        <p role="alert" className="alert alert-warning" data-testid="bill-reason-required">This invoice differs from the goods received by more than the business allows. Say why below before saving.</p>
      )}
    </section>
  );
}

function BillLineCard({ line, index, onChange }: { line: BillLineForm; index: number; onChange: (line: BillLineForm) => void }) {
  const problem = line.ticked ? billLineProblem(line) : null;
  return (
    <li className="rt-card" data-testid={`bill-line-${index}`}>
      <label>
        <input type="checkbox" checked={line.ticked} onChange={(e) => onChange({ ...line, ticked: e.target.checked })} />
        <span>
          <strong>Bill {line.description}</strong> from {line.receiptNo}
          <span className="hint"> {showQty(line.unbilledQty)} {line.unit} not yet on an invoice, received at {money(line.receivedCostMinor)} each{line.orderNo ? `, order ${line.orderNo}` : ''}</span>
        </span>
      </label>
      {line.ticked && (
        <div className="rt-row">
          <div>
            <label htmlFor={`bq-${index}`}>Quantity billed of {line.description}</label>
            <input id={`bq-${index}`} inputMode="decimal" value={line.qty} onChange={(e) => onChange({ ...line, qty: e.target.value })} aria-invalid={problem !== null} />
          </div>
          <div>
            <label htmlFor={`bp-${index}`}>Supplier&apos;s price for one {line.unit} of {line.description}</label>
            <input id={`bp-${index}`} inputMode="numeric" value={line.price} onChange={(e) => onChange({ ...line, price: e.target.value })} aria-invalid={problem !== null} />
          </div>
        </div>
      )}
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      {line.ticked && problem === null && <p>Invoice line <strong>{money(billLineTotal(line))}</strong></p>}
    </li>
  );
}

/** The whole form, controlled by the page so a draft survives a reload. */
export function BillFormView({ form, suppliers, loadingLines, match, checking, matchError, busy, error, onChange, onSubmit }: {
  form: BillForm;
  suppliers: SupplierOption[];
  loadingLines?: boolean;
  match?: SupplierBillMatch;
  checking?: boolean;
  matchError?: unknown;
  busy?: boolean;
  error?: unknown;
  onChange: (form: BillForm) => void;
  onSubmit: () => void;
}) {
  const problem = billProblem(form, match);
  const setLine = (i: number, line: BillLineForm) => onChange({ ...form, lines: form.lines.map((l, n) => (n === i ? line : l)) });
  return (
    <form className="stack" onSubmit={(e) => { e.preventDefault(); if (!problem && !busy && !checking) onSubmit(); }} data-tour="bill-form">
      <div className="stack" data-tour="bill-goods">
        <SupplierField id="bill-supplier" value={form.supplierId} suppliers={suppliers} onChange={(supplierId) => onChange({ ...form, supplierId, lines: [], varianceReason: '' })} />
        {form.supplierId !== '' && (
          <>
            <h2>Goods received and not yet invoiced</h2>
            {loadingLines && <p className="loading">Loading</p>}
            {!loadingLines && form.lines.length === 0 && <p className="empty-state">Nothing received from this supplier is waiting for an invoice.</p>}
            <ul style={{ listStyle: 'none', padding: 0 }}>
              {form.lines.map((l, i) => <BillLineCard key={l.receiptLineId} line={l} index={i} onChange={(line) => setLine(i, line)} />)}
            </ul>
          </>
        )}
      </div>
      <div className="stack" data-tour="bill-details">
        <label htmlFor="bill-ref">Supplier&apos;s invoice number</label>
        <input id="bill-ref" value={form.supplierRef} maxLength={60} onChange={(e) => onChange({ ...form, supplierRef: e.target.value })} />
        <label htmlFor="bill-date">Invoice date</label>
        <input id="bill-date" type="date" value={form.billDate} onChange={(e) => onChange({ ...form, billDate: e.target.value })} />
        <label htmlFor="bill-due">Due on</label>
        <input id="bill-due" type="date" value={form.dueDate} onChange={(e) => onChange({ ...form, dueDate: e.target.value })} />
        <label htmlFor="bill-total">Invoice total, as printed</label>
        <input id="bill-total" inputMode="numeric" value={form.total} onChange={(e) => onChange({ ...form, total: e.target.value })} />
      </div>
      {ticked(form).length > 0 && <p className="hint" data-testid="bill-lines-total">The lines you ticked come to {money(billLinesTotal(form))}.</p>}
      {checking && <p className="hint" role="status">Checking against the goods received</p>}
      <Problem error={matchError} />
      {match && <MatchPanel match={match} checking={checking} />}
      {match?.reason_required && (
        <>
          <label htmlFor="bill-reason">Why does the invoice differ from the goods received? (at least {MIN_REASON} characters)</label>
          <textarea id="bill-reason" rows={3} maxLength={300} value={form.varianceReason} onChange={(e) => onChange({ ...form, varianceReason: e.target.value })} />
        </>
      )}
      <label htmlFor="bill-note">Note (optional)</label>
      <input id="bill-note" value={form.note} maxLength={300} onChange={(e) => onChange({ ...form, note: e.target.value })} />
      {problem && ticked(form).length > 0 && <p role="alert" className="rt-flag">{problem}</p>}
      <Problem error={error} />
      <BusyButton type="submit" className="rt-primary" busy={busy === true} disabled={checking} notReady={problem !== null} data-tour="bill-save">Save supplier invoice</BusyButton>

    </form>
  );
}

/** A value that follows `value` after it has stayed the same for `ms` milliseconds. */
function useSettled<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return settled;
}

export function SupplierBillNewPage() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<BillForm>(`supplier-bill:${me.user_id ?? ''}`, blankBill(businessToday()));
  const suppliers = useSuppliers(draft.supplierId);
  const unbilled = useQuery({ queryKey: ['retail', 'unbilled-receipts', draft.supplierId], queryFn: () => retail.unbilledReceipts(draft.supplierId), enabled: draft.supplierId !== '' });
  // Until the person ticks or types, the lines are the supplier's own unbilled goods; the first change keeps the whole form.
  const form: BillForm = draft.lines.length === 0 && unbilled.data && draft.supplierId !== '' ? { ...draft, lines: billLinesFrom(unbilled.data.items ?? []) } : draft;
  const key2 = matchReady(form) ? matchKey(form) : '';
  const settledKey = useSettled(key2, 400);
  const match = useQuery({
    queryKey: ['retail', 'bill-match', settledKey],
    queryFn: () => retail.matchSupplierBill(buildBillRequest(form, true)),
    enabled: settledKey !== '' && settledKey === key2,
    placeholderData: keepPreviousData,
    retry: false,
  });
  const checking = key2 !== '' && (key2 !== settledKey || match.isFetching);
  const shown = key2 === '' ? undefined : match.data;
  const save = useMutation({
    mutationFn: () => retail.createSupplierBill(buildBillRequest(form), key),
    onSuccess: (bill) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      void navigate({ to: '/staff/retail/supplier-bills/$billId', params: { billId: bill.id ?? '' } });
    },
    meta: { success: 'Invoice saved' },
  });
  return (
    <Gate screen="supplierBills" title="New supplier invoice">
      <Hint>Record an invoice against goods you have already received. Use the number, the dates and the total printed on the invoice.</Hint>
      <BillFormView
        form={form}
        suppliers={suppliers}
        loadingLines={unbilled.isFetching && draft.supplierId !== '' && !unbilled.data}
        match={shown}
        checking={checking}
        matchError={match.error}
        busy={save.isPending}
        error={save.error}
        onChange={(f) => setDraft(() => f)}
        onSubmit={() => save.mutate()}
      />
      {ticked(form).length > 0 && !save.isPending && <button type="button" className="btn-ghost" onClick={() => { discard(); save.reset(); }}>Clear this form</button>}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/supplier-bills/new')({ component: SupplierBillNewPage });
