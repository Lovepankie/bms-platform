import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { businessToday, retail, type AccountPayment, type CustomerAccount, type OpenSale, type Statement } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import {
  MIN_REASON, accountFormOf, accountProblem, accountRequest, allocatableMinor, buildPaymentRequest, defaultStatementRange, settledPayment, splitCountsWhenPaid, kindWords, limitWords, methodWords, oldestFirst,
  payableSales, paymentProblem, reasonOf, reversalKeys, termsWords, type AccountForm, type ManualRow, type PaymentForm,
} from './account-state';
import { usePersistedDraft, newIdempotencyKey } from './idempotency';
import { CREDIT_MANAGE, DOCUMENT_ISSUE, SALE_VOID, branchesWhere, canIssueDocuments, canOpenAccount, holds, holdsAt, sellingBranches } from './permissions';
import { openMadePdf } from './documents-ui';
import { Problem, Success, changeFailureText, money, requireVersion, useToast } from './ui';

// One credit buyer's account (customer_accounts capability, ADR-033, issue #196): the limit and the terms, what is owed,
// payment on account, the payments taken, and the statement. A seller opens it from Credit buyers or Credit control; the
// limit and terms are set only with retail.credit.manage, a reversal needs retail.sale.void. The server decides every one
// of them again, and counts what is owed over the caller's branch scope.

/** The summary of the account, and the form that sets the limit and the terms for whoever may (retail.credit.manage). */
export function AccountSummary({ account, canManage, busy, error, onSave }: {
  account: CustomerAccount;
  canManage: boolean;
  busy?: boolean;
  error?: unknown;
  onSave: (form: AccountForm) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<AccountForm>(accountFormOf(account));
  const problem = accountProblem(form);
  return (
    <section aria-label="Account" className="rt-card" data-tour="account-summary">
      <p className="rt-total" data-testid="owed">{account.balance_minor && account.balance_minor > 0 ? `Owes ${money(account.balance_minor)}` : 'Owes nothing'}</p>
      <p className="hint">Counted over the branches you may read. A payment on account pays these sales.</p>
      {(account.delivery_fees ?? []).length > 0 && (
        <div data-testid="delivery-fees-owed">
          <p className="rt-total">Also owes {money(account.delivery_fees_owed_minor ?? 0)} of delivery fees, paid on each order:</p>
          <ul>
            {(account.delivery_fees ?? []).map((f) => (
              <li key={f.order_id}>
                <Link to="/staff/retail/orders/$orderId" params={{ orderId: f.order_id ?? '' }}>Order {f.order_no}</Link>, sale {f.sale_no}: {money(f.fee_minor ?? 0)}
              </li>
            ))}
          </ul>
        </div>
      )}
      <p data-testid="limit">{limitWords(account, money)}</p>
      <p data-testid="terms">{termsWords(account.terms_days)}</p>
      {canManage && !editing && (
        <p><button type="button" className="btn-sm" onClick={() => { setForm(accountFormOf(account)); setEditing(true); }}>Change the limit and terms</button></p>
      )}
      {canManage && editing && (
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            if (!problem && !busy) { onSave(form); setEditing(false); }
          }}
        >
          <label htmlFor="credit-limit">Credit limit (leave empty for no limit)</label>
          <input id="credit-limit" inputMode="numeric" value={form.limit} onChange={(e) => setForm({ ...form, limit: e.target.value })} aria-invalid={problem !== null} />
          <label htmlFor="credit-terms">Payment terms in days (leave empty for none)</label>
          <input id="credit-terms" inputMode="numeric" value={form.terms} onChange={(e) => setForm({ ...form, terms: e.target.value })} aria-invalid={problem !== null} />
          <p className="hint">A credit sale to this buyer is refused above the limit unless someone who may override gives a reason. The terms set the date a sale falls due.</p>
          {problem && <p role="alert" className="rt-flag">{problem}</p>}
          <p className="cluster">
            <button type="submit" disabled={problem !== null || busy}>Save</button>
            <button type="button" className="btn-ghost" onClick={() => setEditing(false)}>Cancel</button>
          </p>
        </form>
      )}
      <Problem error={error} />
    </section>
  );
}

const METHODS: { value: PaymentForm['method']; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'mobile_money', label: 'Mobile money' },
  { value: 'bank', label: 'Bank' },
];

export const blankPayment = (branchId: string, today: string): PaymentForm => ({ branchId, amount: '', method: 'cash', paidOn: today, manual: false, rows: [] });

/**
 * Payment on account: money from the buyer that is not tied to one sale. Oldest sale first unless the cashier chooses the
 * sales; the form shows where the money would go before anything is saved. Imported sales are not offered (they were
 * never posted to the ledger and are paid on their own).
 */
export function PaymentPanel({ form, open, branches, today, busy, error, onChange, onSubmit }: {
  form: PaymentForm;
  open: readonly OpenSale[];
  branches: { id?: string; name?: string; code?: string }[];
  today: string;
  busy?: boolean;
  error?: unknown;
  onChange: (form: PaymentForm) => void;
  onSubmit: () => void;
}) {
  const payable = payableSales(open);
  const todayOnly = splitCountsWhenPaid(form, open);
  const shown = settledPayment(form, open, today);
  const problem = paymentProblem(shown, open, today);
  const amountText = form.amount.trim();
  const preview = !form.manual && problem === null ? oldestFirst(open, buildPaymentRequest(shown).amount_minor, shown.paidOn).parts : [];
  const rowOf = (saleId: string): ManualRow => form.rows.find((r) => r.saleId === saleId) ?? { saleId, text: '' };
  const setRow = (saleId: string, text: string) =>
    onChange({ ...form, rows: [...form.rows.filter((r) => r.saleId !== saleId), { saleId, text }] });
  return (
    <section aria-label="Payment on account" className="rt-card" data-tour="account-pay">
      <h2>Take a payment</h2>
      {payable.length === 0 ? (
        <p className="empty-state">{open.length > 0 ? 'What this buyer owes is from imported sales; pay those from the sale itself.' : 'This buyer owes nothing.'}</p>
      ) : (
        <form className="stack" onSubmit={(e) => { e.preventDefault(); if (!problem && !busy) onSubmit(); }}>
          <p className="hint">Up to {money(allocatableMinor(open))} can be paid here.</p>
          <label htmlFor="pay-branch">Branch that received the money</label>
          <select id="pay-branch" value={form.branchId} onChange={(e) => onChange({ ...form, branchId: e.target.value })}>
            <option value="">Choose a branch</option>
            {branches.map((b) => <option key={b.id} value={b.id}>{branchLabel(b)}</option>)}
          </select>
          <label htmlFor="pay-amount">Amount received</label>
          <input id="pay-amount" inputMode="numeric" value={form.amount} onChange={(e) => onChange({ ...form, amount: e.target.value })} />
          <label htmlFor="pay-method">Paid by</label>
          <select id="pay-method" value={form.method} onChange={(e) => onChange({ ...form, method: e.target.value as PaymentForm['method'] })}>
            {METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
          </select>
          <label htmlFor="pay-date">Date received</label>
          <input id="pay-date" type="date" value={shown.paidOn} min={todayOnly ? today : undefined} max={today} readOnly={todayOnly} onChange={(e) => onChange({ ...form, paidOn: e.target.value })} />
          {todayOnly && <p className="hint" data-testid="account-pay-today-only">A payment is dated today. If you took the money earlier, ask your administrator.</p>}
          <label className="check">
            <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={form.manual} onChange={(e) => onChange({ ...form, manual: e.target.checked, rows: [] })} />
            Choose which sales it pays
          </label>
          {!form.manual && preview.length > 0 && (
            <div data-testid="pay-preview">
              <p className="hint">The oldest sales are paid first:</p>
              <ul>
                {preview.map((p) => <li key={p.saleId}>{p.saleNo}: {money(p.amountMinor)}</li>)}
              </ul>
            </div>
          )}
          {form.manual && (
            <div className="stack" data-testid="pay-manual">
              {payable.map((s) => (
                <div key={s.sale_id}>
                  <label htmlFor={`alloc-${s.sale_id}`}>{s.sale_no}, {s.sale_date}, owes {money(s.balance_minor ?? 0)}</label>
                  <input id={`alloc-${s.sale_id}`} inputMode="numeric" value={rowOf(s.sale_id ?? '').text} onChange={(e) => setRow(s.sale_id ?? '', e.target.value)} />
                </div>
              ))}
            </div>
          )}
          {problem && amountText !== '' && <p role="alert" className="rt-flag">{problem}</p>}
          <Problem error={error} />
          <button type="submit" className="rt-primary" disabled={problem !== null || busy}>{busy ? 'Saving' : 'Save payment'}</button>
        </form>
      )}
    </section>
  );
}

/** The payments taken on account, newest first. A reversal undoes a payment as a whole; to change who it pays, reverse it and take it again. */
export function ReceiptList({ items, canReverse, busy, error, nameOf, onReverse }: {
  items: readonly AccountPayment[];
  canReverse: (branchId: string | undefined) => boolean;
  busy?: boolean;
  error?: unknown;
  nameOf: (branchId: string | undefined) => string;
  onReverse: (receipt: AccountPayment, reason: string) => void;
}) {
  const [reversing, setReversing] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  if (items.length === 0) return <p className="empty-state">No payment has been taken on account yet.</p>;
  return (
    <>
    <Problem error={error} />
    <ul style={{ listStyle: 'none', padding: 0 }}>
      {items.map((r) => (
        <li key={r.id} className="rt-card" data-testid={`receipt-${r.receipt_no}`}>
          <p>
            <strong>{r.receipt_no}</strong> {r.status === 'reversed' && <span className="badge badge-warning">Reversed</span>}
            <br />
            {money(r.amount_minor ?? 0)} by {methodWords(r.method)} on {r.paid_on}, received at {nameOf(r.branch_id)}.
          </p>
          <p className="hint">
            {(r.allocations ?? []).map((a) => `${a.sale_no} ${money(a.amount_minor ?? 0)}`).join(', ')}
            {r.allocation === 'manual' ? ' (chosen by hand)' : ''}
          </p>
          {r.status === 'reversed' && <p className="hint">Reversed: {r.reverse_reason}</p>}
          {r.status !== 'reversed' && canReverse(r.branch_id) && reversing !== r.id && (
            <p><button type="button" className="btn-sm" onClick={() => { setReversing(r.id ?? null); setReason(''); }} aria-label={`Reverse payment ${r.receipt_no}`}>Reverse</button></p>
          )}
          {reversing === r.id && (
            <form className="stack" onSubmit={(e) => { e.preventDefault(); const why = reasonOf(reason); if (why && !busy) { onReverse(r, why); setReversing(null); } }}>
              <label htmlFor={`reverse-${r.id}`}>Why is it reversed? (at least {MIN_REASON} characters)</label>
              <input id={`reverse-${r.id}`} value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} />
              <p className="hint">The sales owe this money again. To pay different sales, take the payment again afterwards.</p>
              <p className="cluster">
                <button type="submit" disabled={reasonOf(reason) === null || busy}>Reverse this payment</button>
                <button type="button" className="btn-ghost" onClick={() => setReversing(null)}>Cancel</button>
              </p>
            </form>
          )}
        </li>
      ))}
    </ul>
    </>
  );
}

/** The statement: the opening balance, each movement with the running balance, the closing balance, and the imported sales apart. */
export function StatementView({ statement }: { statement: Statement }) {
  const lines = statement.lines ?? [];
  const imported = statement.before_system_start;
  return (
    <div data-testid="statement">
      <div className="table-wrap" tabIndex={0}>
        <table>
          <caption className="hint">From {statement.from} to {statement.to}</caption>
          <thead>
            <tr><th>Date</th><th>What</th><th className="num">Owed</th><th className="num">Paid</th><th className="num">Balance</th></tr>
          </thead>
          <tbody>
            <tr><td colSpan={4}><strong>Opening balance</strong></td><td className="num">{money(statement.opening_minor ?? 0)}</td></tr>
            {lines.map((l, i) => (
              <tr key={`${l.kind}-${l.reference}-${i}`}>
                <td>{l.date}</td>
                <td>{kindWords(l.kind)}<br /><span className="hint">{l.reference}{l.method ? `, ${methodWords(l.method)}` : ''}</span></td>
                <td className="num">{l.debit_minor ? money(l.debit_minor) : ''}</td>
                <td className="num">{l.credit_minor ? money(l.credit_minor) : ''}</td>
                <td className="num">{money(l.balance_minor ?? 0)}</td>
              </tr>
            ))}
            <tr><td colSpan={4}><strong>Closing balance</strong></td><td className="num"><strong>{money(statement.closing_minor ?? 0)}</strong></td></tr>
          </tbody>
        </table>
      </div>
      {lines.length === 0 && <p className="empty-state">Nothing happened on this account in these dates.</p>}
      {imported && (imported.sales ?? []).length > 0 && (
        <section aria-label="Before system start" data-testid="before-system-start">
          <h3>Before system start</h3>
          <p className="hint">These credit sales were brought in from the old records. They are not in the accounts of this system, so they are listed apart and are not in the closing balance above.</p>
          <ul>
            {(imported.sales ?? []).map((s) => <li key={s.sale_id}>{s.sale_no}, {s.sale_date}: {money(s.total_minor ?? 0)}</li>)}
          </ul>
          <p className="rt-total">With these, the buyer owes {money(statement.balance_minor ?? 0)}.</p>
        </section>
      )}
    </div>
  );
}

const route = getRouteApi('/staff/retail/accounts/$customerId');

export function AccountPage() {
  const { me, branch } = useStaff();
  const { customerId } = route.useParams();
  return <AccountScreen customerId={customerId} me={me} branch={branch} />;
}

export function AccountScreen({ customerId, me, branch }: { customerId: string; me: ReturnType<typeof useStaff>['me']; branch: string | null }) {
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const allowed = canOpenAccount(me);
  const today = businessToday();
  const range = defaultStatementRange();
  const [from, setFrom] = useState(range.from);
  const [to, setTo] = useState(range.to);
  const [message, setMessage] = useState<string | null>(null);
  // One key per receipt: a failed reversal of one receipt must not give the next receipt the same key on another path.
  const reverseKeys = useRef(reversalKeys(newIdempotencyKey));
  const payBranches = sellingBranches(branchesWhere(me, 'retail.sale.create'), undefined);
  const firstBranch = payBranches.find((b) => b.id === branch)?.id ?? (payBranches.length === 1 ? payBranches[0]?.id : undefined) ?? '';
  const { key, draft, setDraft, discard } = usePersistedDraft<PaymentForm>(`account-payment:${me.user_id ?? ''}:${customerId}`, blankPayment(firstBranch, today));
  const account = useQuery({ queryKey: ['retail', 'account', customerId], queryFn: () => retail.customerAccount(customerId), enabled: allowed });
  const balance = useQuery({ queryKey: ['retail', 'account-balance', customerId], queryFn: () => retail.customerBalance(customerId), enabled: allowed });
  const payments = useQuery({ queryKey: ['retail', 'account-payments', customerId], queryFn: () => retail.listAccountPayments(customerId), enabled: allowed });
  const ranged = from !== '' && to !== '' && from <= to;
  const statement = useQuery({ queryKey: ['retail', 'account-statement', customerId, from, to], queryFn: () => retail.customerStatement(customerId, { from, to }), enabled: allowed && ranged });
  useCrumbLabel(account.data?.name);
  const done = (text: string) => { setMessage(text); show(text); void queryClient.invalidateQueries({ queryKey: ['retail'] }); };
  const save = useMutation({
    mutationFn: (form: AccountForm) => retail.setCustomerAccount(customerId, requireVersion(account.data?.version), accountRequest(form)),
    onSuccess: () => done('Saved the credit limit and terms.'),
  });
  const pay = useMutation({
    mutationFn: () => retail.payOnAccount(customerId, buildPaymentRequest(settledPayment(draft, balance.data?.open_sales ?? [], today)), key),
    onMutate: () => setMessage(null),
    onSuccess: (r) => { discard(); done(`Saved ${r.receipt_no}: ${money(r.amount_minor ?? 0)}.`); },
  });
  const reverse = useMutation({
    mutationFn: (v: { receipt: AccountPayment; reason: string }) => retail.reverseAccountPayment(customerId, v.receipt.id ?? '', v.reason, reverseKeys.current.keyFor(v.receipt.id ?? '', v.reason)),
    onSuccess: (r, v) => { reverseKeys.current.done(v.receipt.id ?? ''); done(`Reversed ${r.receipt_no}.`); },
  });
  // The statement as a PDF (documents capability, issue #197): rendered as at today for the dates chosen, never stored.
  const statementPdf = useMutation({ mutationFn: () => openMadePdf(() => retail.customerStatementPdf(customerId, { from, to })) });
  const nameOf = (id: string | undefined) => branchLabel((me.branches ?? []).find((b) => b.id === id));
  if (!allowed) {
    return (
      <main className="rt">
        <h1>Customer account</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  return (
    <main className="rt rt-wide">
      <h1>{account.data?.name ?? 'Customer account'}</h1>
      <p><Link to="/staff/retail/catalogue/buyers">Back to credit buyers</Link></p>
      {account.isPending && <SkeletonList label="Loading the account" />}
      <Problem error={account.error} />
      {message && <Success>{message}</Success>}
      {account.data && (
        <AccountSummary
          account={account.data}
          canManage={holds(me, CREDIT_MANAGE)}
          busy={save.isPending}
          error={save.error && new Error(changeFailureText(save.error, queryClient))}
          onSave={(form) => save.mutate(form)}
        />
      )}
      {holds(me, 'retail.sale.create') && balance.data && (
        <PaymentPanel
          form={draft}
          open={balance.data.open_sales ?? []}
          branches={payBranches}
          today={today}
          busy={pay.isPending}
          error={pay.error}
          onChange={(form) => setDraft(form)}
          onSubmit={() => pay.mutate()}
        />
      )}
      <section aria-label="Payments taken" data-tour="account-payments">
        <h2>Payments taken</h2>
        <ReceiptList
          items={payments.data?.items ?? []}
          canReverse={(b) => holdsAt(me, SALE_VOID, b)}
          busy={reverse.isPending}
          error={reverse.error}
          nameOf={nameOf}
          onReverse={(receipt, reason) => reverse.mutate({ receipt, reason })}
        />
      </section>
      <section aria-label="Statement" data-tour="account-statement">
        <h2>Statement</h2>
        <div className="rt-row">
          <div><label htmlFor="st-from">From</label><input id="st-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
          <div><label htmlFor="st-to">To</label><input id="st-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
        </div>
        {!ranged && <p role="alert" className="rt-flag">The start date must not be after the end date.</p>}
        {statement.isPending && ranged && <SkeletonList label="Loading the statement" />}
        <Problem error={statement.error} />
        {statement.data && <StatementView statement={statement.data} />}
        {canIssueDocuments(me) && holds(me, DOCUMENT_ISSUE) && (
          <p>
            <button type="button" disabled={!ranged || statementPdf.isPending} data-tour="account-statement-pdf" onClick={() => statementPdf.mutate()}>
              {statementPdf.isPending ? 'Making the PDF' : 'Open the statement as a PDF'}
            </button>
            <Problem error={statementPdf.error} />
          </p>
        )}
      </section>
      {toast}
    </main>
  );
}

export const Route = createLazyRoute('/staff/retail/accounts/$customerId')({ component: AccountPage });
