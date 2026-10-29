import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi, useNavigate } from '@tanstack/react-router';
import { useState, type FormEvent } from 'react';
import type { Loan } from '../../../api/lending';
import { asFormError } from '../../../api/lending-forms';
import { useApplicationsApi } from '../../../api/lending-context';
import type { LoanAppraisal } from '../../../api/loan-applications';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { NoAccess } from '../../../app/ui/EmptyState';
import { FieldsNeededNote } from '../../../app/ui/Field';
import { exponentOf } from '../../../components/money';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { sayDone } from '../retail/toast';
import { blankDecision, DECISION_ORDER, decisionBody, decisionProblems, selfApproval, termWords, type DecisionDraft } from './application-state';
import { AppraisalCard, ApplicationFacts } from './application-view';
import { Group, SaveBar, TextField, useFormErrors } from './form-fields';
import { LendingGate } from './loans';
import { currencyOf, money } from './loan-state';
import { applicationSteps } from './permissions';

// The decision on an appraised application (issue #280 slice 4, FR-ORG-06, FR-ORG-07, FR-APR-03): approve at or below what was
// asked for, or decline with a reason. The appraisal advises; this decides. Maker-checker: the person who sent or appraised the
// application cannot approve it (the server and the database refuse it too), so for them Approve is shown switched off with the
// reason, and Decline stays. The server also checks the member's details, the collateral cover and the business's loan limit.

const LABELS: Record<DecisionDraft['decision'], string> = { '': 'Record the decision', approve: 'Approve the loan', reject: 'Decline the application' };

export function DecisionForm({ loan, appraisal }: { loan: Loan; appraisal?: LoanAppraisal }) {
  const { me } = useStaff();
  const api = useApplicationsApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const currency = currencyOf(loan.currency);
  const [draft, setDraft] = useState<DecisionDraft>(() => blankDecision(loan));
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const form = useFormErrors(decisionProblems(draft, loan), 'dc');
  const own = selfApproval(loan, me.user_id);
  const set = <K extends keyof DecisionDraft>(key: K, value: DecisionDraft[K]) => setDraft((d) => ({ ...d, [key]: value }));
  const error = form.error('decision');

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || !form.attempt(DECISION_ORDER)) return;
    setBusy(true);
    setFailure(null);
    try {
      await api.decide(loan.id ?? '', loan.version ?? 0, decisionBody(draft, loan));
      sayDone(draft.decision === 'reject' ? 'Application declined' : 'Application approved');
      void queryClient.invalidateQueries({ queryKey: ['lending'] });
      void navigate({ to: '/staff/lending/loans/$loanId', params: { loanId: loan.id ?? '' } });
    } catch (raw) {
      const failed = asFormError(raw);
      form.refuse(failed.fields, DECISION_ORDER);
      setFailure(Object.keys(failed.fields).length > 0 ? null : failed.message);
      void queryClient.invalidateQueries({ queryKey: ['lending', 'loan', loan.id] });
    } finally {
      setBusy(false);
    }
  }

  const choice = (value: 'approve' | 'reject', label: string, disabled = false) => (
    <label className="mf-check">
      <input type="radio" name="dc-decision" id={value === 'approve' ? form.idOf('decision') : undefined} value={value} checked={draft.decision === value} disabled={disabled}
        aria-describedby={error ? `${form.idOf('decision')}-error` : undefined} aria-invalid={error ? true : undefined}
        onChange={() => { form.edited('decision'); set('decision', value); }} onBlur={() => form.touch('decision')} />
      <span>{label}</span>
    </label>
  );

  return (
    <form className="mf" onSubmit={(e) => void submit(e)} noValidate aria-label="Decide on the application" data-tour="decide-form">
      <section className="rt-card stack" aria-labelledby="dc-loan" data-tour="decide-loan">
        <h2 id="dc-loan">The application</h2>
        <p>Loan {loan.loan_no}, member {loan.member_no}, product {loan.product_code}</p>
        <ApplicationFacts loan={loan} />
      </section>
      {appraisal ? <AppraisalCard appraisal={appraisal} currency={currency} /> : <p className="alert alert-info">No appraisal is on record.</p>}
      <FieldsNeededNote />
      <fieldset className="mf-group" data-tour="decide-choice" aria-required="true">
        <legend>Your decision<span className="field-star" aria-hidden="true"> *</span></legend>
        {own && <p className="alert alert-info" id="dc-own">{own}</p>}
        {choice('approve', 'Approve', !!own)}
        {choice('reject', 'Decline')}
        {error && <p id={`${form.idOf('decision')}-error`} className="field-error" role="alert">{error}</p>}
      </fieldset>
      {draft.decision === 'approve' && (
        <Group title="What is approved" tourId="decide-terms">
          <TextField name="approved_principal_minor" label={`Amount to lend (${currency})`} form={form} required inputMode={exponentOf(currency) ? 'decimal' : 'numeric'} value={draft.amount} onChange={(v) => set('amount', v)}
            hint={`Asked for ${money(loan.requested_principal_minor, currency)}. You can approve that or less.`} />
          <TextField name="approved_term_count" label={`How long, in ${termWords(2, loan.term_unit).replace(/^2 /, '')}`} form={form} required inputMode="numeric" value={draft.term} onChange={(v) => set('term', v)}
            hint={`Asked for ${termWords(loan.requested_term_count, loan.term_unit)}. You can approve that or less.`} />
          <TextField name="note" label="Note for the file" form={form} type="textarea" rows={2} maxLength={1000} value={draft.note} onChange={(v) => set('note', v)} />
        </Group>
      )}
      {draft.decision === 'reject' && (
        <Group title="Why it is declined">
          <TextField name="note" label="Reason" form={form} required type="textarea" rows={3} maxLength={1000} value={draft.note} onChange={(v) => set('note', v)}
            hint="The member may ask why. Keep it short and plain." />
        </Group>
      )}
      <Problem error={failure ? new Error(failure) : null} />
      <SaveBar label={LABELS[draft.decision]} busy={busy} tourId="decide-save"
        cancel={<Link to="/staff/lending/loans/$loanId" params={{ loanId: loan.id ?? '' }} className="btn btn-ghost">Cancel</Link>} />
    </form>
  );
}

const route = getRouteApi('/staff/lending/loans/$loanId/decide');

function DecidePage() {
  const { loanId } = route.useParams();
  return <DecideBody loanId={loanId} />;
}

/** The page body for a loan, with its gate: the step must be offered to this session for this loan (applicationSteps). */
export function DecideBody({ loanId }: { loanId: string }) {
  const { me } = useStaff();
  const api = useApplicationsApi();
  const loan = useQuery({ queryKey: ['lending', 'loan', loanId], queryFn: () => api.get(loanId) });
  const appraisals = useQuery({ queryKey: ['lending', 'loan', loanId, 'appraisals'], queryFn: () => api.appraisals(loanId) });
  const l = loan.data;
  useCrumbLabel(l?.loan_no ? `Decide ${l.loan_no}` : undefined);
  return (
    <LendingGate title="Decision">
      {(loan.isPending || appraisals.isPending) && <p className="loading">Loading the application</p>}
      <Problem error={loan.error ?? appraisals.error} />
      {l && appraisals.data && (applicationSteps(me, l).includes('decide') ? <DecisionForm loan={l} appraisal={appraisals.data[0]} /> : <NoAccess what="decide on this application" />)}
    </LendingGate>
  );
}

export const Route = createLazyRoute('/staff/lending/loans/$loanId/decide')({ component: DecidePage });
