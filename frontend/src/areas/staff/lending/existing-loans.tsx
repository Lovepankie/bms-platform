import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState, type FormEvent } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { ExistingLoanError, existingLoans, type ExistingLoan, type ExistingLoansApi, type ExistingLoanSummary } from '../../../api/existing-loans';
import { PAYMENT_METHODS } from '../../../api/lending';
import { useStaff } from '../context';
import { newIdempotencyKey } from '../retail/idempotency';
import { Note, Problem, Success } from '../retail/ui';
import { canBringIn, emptyDraft, emptyRepayment, goLiveWords, toRequest, type LoanDraft, type RepaymentDraft } from './existing-loans-state';
import { money, words } from './loan-state';
import { MemberPicker } from './member-picker';
import { NoAccess } from '../../../app/ui/EmptyState';
import { Hint } from '../../../app/ui/Hint';

// Record an existing loan (issue #466, FR-EXL-01 to FR-EXL-04, ADR-071), for a business moving off a paper register: the
// member, the product, the amount lent, the real date it was given out, the term, the product's rate or the amount the
// borrower agreed to repay, and the repayments already received. "Check" shows the schedule from the real date, the
// repayments applied and the true balance, next due date and days overdue, saving nothing. "Record" keeps it waiting for a
// second person's approval; until then it counts nowhere. Below, the loans still waiting, to withdraw or ask again.

export function ExistingLoanView({ loan }: { loan: ExistingLoan }) {
  const p = loan.position;
  const c = loan.currency;
  return (
    <section aria-label="The loan as the register leaves it" className="rt-card stack">
      <h2>{loan.loan_id ? `${loan.loan_no}: ${words(loan.status)}` : 'What it would be'}</h2>
      {p && (
        <dl className="facts">
          <dt>Still owed</dt>
          <dd>{money(p.total_outstanding_minor, c)}</dd>
          <dt>Principal still owed</dt>
          <dd>{money(p.principal_outstanding_minor, c)}</dd>
          <dt>In arrears</dt>
          <dd>{money(p.arrears_minor, c)}</dd>
          <dt>Days overdue</dt>
          <dd>{p.days_past_due ?? 0}</dd>
          <dt>Next due</dt>
          <dd>{p.next_due_date ?? 'Nothing more'}</dd>
          <dt>Repaid so far</dt>
          <dd>{money(p.total_paid_minor, c)}</dd>
        </dl>
      )}
      {p?.settled && <p>The register shows this loan repaid in full. It is closed when approved.</p>}
      {(loan.warnings ?? []).map((w) => <Note key={w}>{w}</Note>)}
      <div className="table-wrap" tabIndex={0}>
        <table aria-label="Schedule">
          <thead>
            <tr><th>No</th><th>Due</th><th className="num">Principal</th><th className="num">Interest</th><th className="num">Paid</th><th className="num">Unpaid</th><th>Status</th></tr>
          </thead>
          <tbody>
            {(loan.schedule ?? []).map((i) => (
              <tr key={i.no}>
                <td>{i.no}</td>
                <td>{i.due_date}</td>
                <td className="num">{money(i.principal_due_minor, c)}</td>
                <td className="num">{money(i.interest_due_minor, c)}</td>
                <td className="num">{money(i.paid_minor, c)}</td>
                <td className="num">{money(i.unpaid_minor, c)}</td>
                <td>{words(i.status)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function RepaymentRow({ r, i, error, onChange, onRemove }: {
  r: RepaymentDraft;
  i: number;
  error: (field: string) => string | undefined;
  onChange: (r: RepaymentDraft) => void;
  onRemove: () => void;
}) {
  const id = (f: string) => `el-r${i}-${f}`;
  const dateError = error(`repayments[${i}].value_date`);
  const amountError = error(`repayments[${i}].amount_minor`);
  return (
    <fieldset className="rt-card">
      <legend>Repayment {i + 1}</legend>
      <label htmlFor={id('date')}>Date paid</label>
      <input id={id('date')} type="date" value={r.date} onChange={(e) => onChange({ ...r, date: e.target.value })} aria-invalid={!!dateError} />
      {dateError && <p className="field-error" role="alert">{dateError}</p>}
      <label htmlFor={id('amount')}>Amount paid</label>
      <input id={id('amount')} inputMode="decimal" value={r.amount} onChange={(e) => onChange({ ...r, amount: e.target.value })} aria-invalid={!!amountError} />
      {amountError && <p className="field-error" role="alert">{amountError}</p>}
      <label htmlFor={id('method')}>How it was paid</label>
      <select id={id('method')} value={r.method} onChange={(e) => onChange({ ...r, method: e.target.value })}>
        {PAYMENT_METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
      </select>
      <label htmlFor={id('note')}>Receipt number or note</label>
      <input id={id('note')} maxLength={100} value={r.note} onChange={(e) => onChange({ ...r, note: e.target.value })} />
      <button type="button" className="btn-sm btn-ghost" onClick={onRemove}>Remove this repayment</button>
    </fieldset>
  );
}

export function ExistingLoanForm({ api = existingLoans }: { api?: ExistingLoansApi }) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<LoanDraft>(emptyDraft);
  const [chosen, setChosen] = useState<{ id: string; name: string; no: string } | null>(null);
  const [idempotencyKey, setKey] = useState(newIdempotencyKey);
  const [problems, setProblems] = useState<Record<string, string>>({});
  const [checked, setChecked] = useState<{ body: string; loan: ExistingLoan } | null>(null);
  const [recorded, setRecorded] = useState<ExistingLoan | null>(null);
  const products = useQuery({ queryKey: ['lending', 'loan-products', 'active'], queryFn: () => api.products() });
  const product = (products.data ?? []).find((p) => p.id === draft.productId);
  const currency = product?.current_version?.currency;
  const request = toRequest(draft, currency);
  const bodyText = request.body ? JSON.stringify(request.body) : '';
  const current = checked && checked.body === bodyText ? checked.loan : null;
  const set = <K extends keyof LoanDraft>(key: K, value: LoanDraft[K]) => {
    setDraft((d) => ({ ...d, [key]: value }));
    setRecorded(null);
  };
  const fieldErrors = (e: unknown): Record<string, string> => (e instanceof ExistingLoanError ? e.fields : {});
  const check = useMutation({
    mutationFn: () => api.check(request.body!),
    onSuccess: (loan) => {
      setChecked({ body: bodyText, loan });
      setProblems({});
    },
    onError: (e) => setProblems(fieldErrors(e)),
  });
  const record = useMutation({
    mutationFn: () => api.record(request.body!, idempotencyKey),
    onSuccess: (loan) => {
      setRecorded(loan);
      setChecked(null);
      setDraft(emptyDraft());
      setChosen(null);
      setKey(newIdempotencyKey());
      void queryClient.invalidateQueries({ queryKey: ['lending', 'existing-loans'] });
    },
    onError: (e) => setProblems(fieldErrors(e)),
  });
  const error = (field: string): string | undefined => problems[field];
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!request.body) {
      setProblems(request.problems);
      return;
    }
    check.mutate();
  };
  const setRepayment = (i: number, r: RepaymentDraft) =>
    set('repayments', draft.repayments.map((x, k) => (k === i ? r : x)));
  return (
    <form className="mf" aria-label="Record an existing loan" noValidate onSubmit={submit} data-tour="existing-form">
      <MemberPicker id="el-member" chosen={chosen} error={error('member_id')} errorId="el-member-error"
        onChoose={(m) => { setChosen(m); set('memberId', m?.id ?? null); }} />
      <label htmlFor="el-product">Loan product</label>
      <select id="el-product" value={draft.productId} onChange={(e) => set('productId', e.target.value)} aria-invalid={!!error('product_id')}>
        <option value="">Choose the product</option>
        {(products.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.code}: {p.name}</option>)}
      </select>
      {error('product_id') && <p className="field-error" role="alert">{error('product_id')}</p>}
      <label htmlFor="el-principal">Amount lent</label>
      <input id="el-principal" inputMode="decimal" value={draft.principal} onChange={(e) => set('principal', e.target.value)} aria-invalid={!!error('principal_minor')} />
      {error('principal_minor') && <p className="field-error" role="alert">{error('principal_minor')}</p>}
      <label htmlFor="el-date">Date the money was given out</label>
      <input id="el-date" type="date" value={draft.disbursementDate} onChange={(e) => set('disbursementDate', e.target.value)} aria-invalid={!!error('disbursement_date')} />
      {error('disbursement_date') && <p className="field-error" role="alert">{error('disbursement_date')}</p>}
      <label htmlFor="el-term">Term{product?.current_version?.term_unit ? ` (${product.current_version.term_unit}s)` : ''}</label>
      <input id="el-term" inputMode="numeric" value={draft.term} placeholder={product?.current_version?.default_term_count?.toString()} onChange={(e) => set('term', e.target.value)} aria-invalid={!!error('term_count')} />
      {error('term_count') && <p className="field-error" role="alert">{error('term_count')}</p>}
      <label htmlFor="el-agreed">Amount the borrower agreed to repay (optional)</label>
      <input id="el-agreed" inputMode="decimal" value={draft.agreedTotal} onChange={(e) => set('agreedTotal', e.target.value)} aria-invalid={!!error('agreed_total_minor')} />
      <Hint>Leave it empty to use the product's rate. When given, the difference is spread as interest.</Hint>
      {error('agreed_total_minor') && <p className="field-error" role="alert">{error('agreed_total_minor')}</p>}
      <label htmlFor="el-ref">Number in the register (optional)</label>
      <input id="el-ref" maxLength={40} value={draft.registerRef} onChange={(e) => set('registerRef', e.target.value)} aria-invalid={!!error('register_ref')} />
      {error('register_ref') && <p className="field-error" role="alert">{error('register_ref')}</p>}
      <h2>Repayments already received</h2>
      {draft.repayments.length === 0 && <Hint full>None yet. Add each repayment the register shows, in any order.</Hint>}
      {draft.repayments.map((r, i) => (
        <RepaymentRow key={i} r={r} i={i} error={error} onChange={(x) => setRepayment(i, x)}
          onRemove={() => set('repayments', draft.repayments.filter((_, k) => k !== i))} />
      ))}
      <button type="button" className="btn-sm" onClick={() => set('repayments', [...draft.repayments, emptyRepayment()])}>Add a repayment</button>
      {Object.keys(problems).length > 0 && <p role="alert" className="alert alert-danger">Some entries need a look. They are marked above.</p>}
      <Problem error={Object.keys(problems).length === 0 ? (check.error ?? record.error) : null} />
      <BusyButton busy={check.isPending} type="submit" className="rt-primary" data-tour="existing-check">Check</BusyButton>
      {current && <ExistingLoanView loan={current} />}
      {current && (
        <BusyButton busy={record.isPending} type="button" className="rt-primary" onClick={() => record.mutate()}>
          Record and send for approval
        </BusyButton>
      )}
      {recorded && (
        <Success>
          Recorded {recorded.loan_no}. It counts once a second person approves it in the approvals inbox.
        </Success>
      )}
    </form>
  );
}

function WaitingRow({ loan, api }: { loan: ExistingLoanSummary; api: ExistingLoansApi }) {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['lending', 'existing-loans'] });
  const cancel = useMutation({ mutationFn: () => api.cancel(loan.loan_id ?? '', reason.trim()), onSuccess: refresh });
  const again = useMutation({ mutationFn: () => api.askAgain(loan.loan_id ?? ''), onSuccess: refresh });
  return (
    <li className="rt-card stack">
      <p>
        <Link to="/staff/lending/loans/$loanId" params={{ loanId: loan.loan_id ?? '' }}><strong>{loan.loan_no}</strong></Link>
        {' '}{loan.member_no}{loan.register_ref ? `, register ${loan.register_ref}` : ''}, given out {loan.disbursement_date}
      </p>
      <p>{loan.approval_request_id ? 'Waiting for a second person in the approvals inbox.' : 'Not sent for approval: the last request was rejected, expired or changed.'}</p>
      {!loan.approval_request_id && (
        <BusyButton busy={again.isPending} type="button" className="btn-sm" onClick={() => again.mutate()}>Ask for approval again</BusyButton>
      )}
      <label htmlFor={`el-why-${loan.loan_id}`}>Why withdraw it</label>
      <input id={`el-why-${loan.loan_id}`} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <BusyButton busy={cancel.isPending} type="button" className="btn-sm btn-danger" disabled={reason.trim() === ''} onClick={() => cancel.mutate()}>Withdraw</BusyButton>
      <Problem error={cancel.error ?? again.error} />
    </li>
  );
}

export function WaitingLoans({ api = existingLoans }: { api?: ExistingLoansApi }) {
  const waiting = useQuery({ queryKey: ['lending', 'existing-loans', 'brought_in'], queryFn: () => api.list('brought_in') });
  return (
    <section aria-label="Waiting for approval" data-tour="existing-waiting">
      <h2>Waiting for approval</h2>
      <Problem error={waiting.error} />
      {waiting.data && waiting.data.length === 0 && <p className="empty-state">No existing loan is waiting.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {(waiting.data ?? []).map((l) => <WaitingRow key={l.loan_id} loan={l} api={api} />)}
      </ul>
    </section>
  );
}

export function ExistingLoansPage({ api = existingLoans }: { api?: ExistingLoansApi }) {
  const { me } = useStaff();
  const goLive = useQuery({ queryKey: ['lending', 'go-live'], queryFn: () => api.goLive(), enabled: canBringIn(me) });
  return (
    <main className="rt ln">
      <h1>Existing loans</h1>
      {!canBringIn(me) ? (
        <NoAccess />
      ) : (
        <>
          <div data-tour="existing-go-live">
            <Note>{goLiveWords(goLive.data)} No penalty is charged for the time before a loan is brought in.</Note>
          </div>
          <p data-tour="existing-import-link"><Link to="/staff/lending/existing-loans/import">Import many loans from a CSV file</Link></p>
          <ExistingLoanForm api={api} />
          <WaitingLoans api={api} />
        </>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff/lending/existing-loans')({ component: () => <ExistingLoansPage /> });
