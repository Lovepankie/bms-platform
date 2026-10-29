import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, useNavigate, useSearch } from '@tanstack/react-router';
import { useEffect, useRef } from 'react';
import { retail, type SupplierOpenItem } from '../../../api/retail';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { PAYABLE_MANAGE, branchesWhere } from './permissions';
import {
  KIND_WORDS, PAYMENT_METHODS, allocatedMinor, blankPayment, buildPaymentRequest, itemAmountProblem, itemKey, lateWords, owedOn, paidOnOf, paymentAmount, paymentProblem, remainingMinor,
  type PaymentForm, type PaymentMethod,
} from './payables-state';
import { BranchField, Star, SupplierField, useSuppliers, type SupplierOption } from './purchasing-ui';
import { showDate } from './transfers';
import { Gate, Problem, Money, money } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';
import { FieldsNeededNote } from '../../../app/ui/Field';
import { WORDS } from '../../../app/glossary';
import { businessToday } from '../../../api/retail';

// The supplier payment form (ADR-033, issue #198; no capability needed since issue #557): choose the supplier and the branch that
// pays, how it is paid, the amount and the day it was paid (today or before; it pays only what was owed on that day). By default the payment clears the oldest first (an invoice or a credit restock, by its due date). Choosing
// instead lets the person give an amount to each one, and the amounts must add up to the payment. Nothing is kept on account: the
// whole payment clears something the supplier is owed.

function OwedList({ items }: { items: SupplierOpenItem[] }) {
  return (
    <ul style={{ listStyle: 'none', padding: 0 }} data-testid="owed-list">
      {items.map((i) => (
        <li key={itemKey(i)} className="rt-card">
          <strong>{KIND_WORDS[i.kind ?? 'bill']} {i.number}</strong>{i.supplier_ref ? ` (invoice ${i.supplier_ref})` : ''}
          <br />
          Due {showDate(i.due_date)}, {lateWords(i.days_past_due).toLowerCase()}. Owed <strong><Money minor={i.open_minor ?? 0} /></strong>
        </li>
      ))}
    </ul>
  );
}

/** The whole form, controlled by the page so a draft survives a reload. `items` is what the supplier is owed, oldest due first. */
export function PaymentFormView({ form, suppliers, branches, items, owed, loadingItems, busy, error, onChange, onSubmit }: {
  form: PaymentForm;
  suppliers: SupplierOption[];
  branches: { id?: string; name?: string; code?: string }[];
  items?: SupplierOpenItem[];
  owed?: number;
  loadingItems?: boolean;
  busy?: boolean;
  error?: unknown;
  onChange: (form: PaymentForm) => void;
  onSubmit: () => void;
}) {
  const problem = paymentProblem(form, items, owed);
  const left = remainingMinor(form);
  return (
    <form className="stack" onSubmit={(e) => { e.preventDefault(); if (!problem && !busy) onSubmit(); }} data-tour="payment-form">
      <FieldsNeededNote />
      <div className="stack" data-tour="payment-supplier">
        <SupplierField id="pay-supplier" required value={form.supplierId} suppliers={suppliers} onChange={(supplierId) => onChange({ ...form, supplierId, amounts: {} })} />
        {form.supplierId !== '' && loadingItems && <p className="loading">Loading</p>}
        {form.supplierId !== '' && items && (
          <section aria-label="What is owed">
            {items.length === 0 ? <p className="empty-state">This supplier is owed nothing.</p> : <p data-testid="owed-total">This supplier is owed <strong><Money minor={owed ?? 0} /></strong>.</p>}
            {form.allocation === 'oldest_first' && items.length > 0 && <OwedList items={items} />}
          </section>
        )}
      </div>
      <div className="stack" data-tour="payment-amount">
        <BranchField id="pay-branch" required label="Paid from the till or bank of" value={form.branchId} branches={branches} onChange={(branchId) => onChange({ ...form, branchId })} />
        <label htmlFor="pay-method">{WORDS.paidHow}<Star /></label>
        <select id="pay-method" aria-required value={form.method} onChange={(e) => onChange({ ...form, method: e.target.value as PaymentMethod })}>
          {PAYMENT_METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
        </select>
        <label htmlFor="pay-amount">Amount paid<Star /></label>
        <input id="pay-amount" inputMode="numeric" aria-required value={form.amount} onChange={(e) => onChange({ ...form, amount: e.target.value })} />
        <label htmlFor="pay-date">Date paid<Star /></label>
        <input id="pay-date" type="date" aria-required max={businessToday()} value={paidOnOf(form)} onChange={(e) => onChange({ ...form, paidOn: e.target.value === businessToday() ? '' : e.target.value, amounts: {} })} />
        <p className="hint">Today or an earlier day; it pays what was owed that day.</p>
      </div>
      <label htmlFor="pay-ref">Reference, such as a bank or mobile money number (optional)</label>
      <input id="pay-ref" value={form.reference} maxLength={60} onChange={(e) => onChange({ ...form, reference: e.target.value })} />
      <label htmlFor="pay-note">Note (optional)</label>
      <input id="pay-note" value={form.note} maxLength={300} onChange={(e) => onChange({ ...form, note: e.target.value })} />
      <fieldset data-tour="payment-allocation">
        <legend>What does this payment pay?</legend>
        <label>
          <input type="radio" name="pay-allocation" checked={form.allocation === 'oldest_first'} onChange={() => onChange({ ...form, allocation: 'oldest_first' })} />
          <span>The oldest first <span className="hint">(recommended)</span></span>
        </label>
        <label>
          <input type="radio" name="pay-allocation" checked={form.allocation === 'manual'} onChange={() => onChange({ ...form, allocation: 'manual' })} />
          <span>Let me choose</span>
        </label>
      </fieldset>
      {form.allocation === 'manual' && (
        <section aria-label="Choose what it pays" data-testid="pay-manual" className="stack">
          {!items && <p className="hint">Choose a supplier to see what is owed.</p>}
          {items?.map((i) => {
            const text = form.amounts[itemKey(i)] ?? '';
            const bad = itemAmountProblem(i, text);
            return (
              <div key={itemKey(i)} className="rt-card">
                <label htmlFor={`amt-${itemKey(i)}`}>
                  Pay on {KIND_WORDS[i.kind ?? 'bill']} {i.number}{i.supplier_ref ? ` (invoice ${i.supplier_ref})` : ''}, due {showDate(i.due_date)}, {lateWords(i.days_past_due).toLowerCase()}, owed <Money minor={i.open_minor ?? 0} />
                </label>
                <input id={`amt-${itemKey(i)}`} inputMode="numeric" value={text} onChange={(e) => onChange({ ...form, amounts: { ...form.amounts, [itemKey(i)]: e.target.value } })} aria-invalid={bad !== null} />
                {bad && <p role="alert" className="rt-flag num">{bad}</p>}
              </div>
            );
          })}
          <p className="num" data-testid="pay-remaining" aria-live="polite">
            Given out <Money minor={allocatedMinor(form.amounts)} /> of <Money minor={paymentAmount(form) ?? 0} />. {left === 0 ? 'Nothing is left to give out.' : left > 0 ? `${money(left)} is left to give out.` : `${money(-left)} too much is given out.`}
          </p>
        </section>
      )}
      {problem && (form.amount.trim() !== '' || form.supplierId !== '') && <p role="alert" className="rt-flag num">{problem}</p>}
      <Problem error={error} />
      <BusyButton type="submit" className="rt-primary" busy={busy === true} notReady={problem !== null} data-tour="payment-save">Save payment</BusyButton>

    </form>
  );
}

export function SupplierPaymentNewPage() {
  const { me, branch } = useStaff();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const params = useSearch({ strict: false }) as { supplier?: string };
  const branches = branchesWhere(me, PAYABLE_MANAGE).filter((b) => b.id);
  const defaultBranch = branches.find((b) => b.id === branch)?.id ?? (branches.length === 1 ? (branches[0]?.id ?? '') : '');
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<PaymentForm>(`supplier-payment:${me.user_id ?? ''}`, blankPayment('', defaultBranch));
  // "Pay this supplier" from the payables report or an invoice arrives with the supplier chosen.
  const wanted = typeof params.supplier === 'string' ? params.supplier : '';
  const applied = useRef('');
  useEffect(() => {
    if (wanted && applied.current !== wanted) {
      applied.current = wanted;
      setDraft((d) => ({ ...d, supplierId: wanted, amounts: {} }));
    }
  }, [wanted, setDraft]);
  const suppliers = useSuppliers(draft.supplierId);
  const open = useQuery({ queryKey: ['retail', 'supplier-open-items', draft.supplierId], queryFn: () => retail.supplierOpenItems(draft.supplierId), enabled: draft.supplierId !== '' });
  // A payment dated earlier pays only what was bought on or before that day (issue #557).
  const items = draft.supplierId !== '' && open.data?.items ? owedOn(open.data.items, paidOnOf(draft)) : undefined;
  const owed = items?.reduce((sum, i) => sum + (i.open_minor ?? 0), 0);
  const save = useMutation({
    mutationFn: () => retail.createSupplierPayment(buildPaymentRequest(draft, items ?? []), key),
    onSuccess: (payment) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      void navigate({ to: '/staff/retail/supplier-payments/$paymentId', params: { paymentId: payment.id ?? '' } });
    },
    meta: { success: 'Payment recorded' },
  });
  return (
    <Gate screen="supplierPayments" title={WORDS.payASupplier}>
      <p className="cluster">
        <Link className="btn btn-sm" to="/staff/retail/supplier-payables">{WORDS.whatIOweSuppliers}</Link>
        <Link className="btn btn-sm" to="/staff/retail/supplier-payments">Supplier payments</Link>
        {draft.supplierId !== '' && <Link className="btn btn-sm" to="/staff/retail/suppliers/$supplierId/statement" params={{ supplierId: draft.supplierId }}>Statement</Link>}
      </p>
      <PaymentFormView
        form={draft}
        suppliers={suppliers}
        branches={branches}
        items={items}
        owed={owed}
        loadingItems={open.isFetching && !open.data}
        busy={save.isPending}
        error={save.error ?? open.error}
        onChange={(f) => setDraft(() => f)}
        onSubmit={() => save.mutate()}
      />
      {draft.amount !== '' && !save.isPending && <button type="button" className="btn-ghost" onClick={() => { discard(); save.reset(); }}>Clear this form</button>}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/supplier-payments/new')({ component: SupplierPaymentNewPage });
