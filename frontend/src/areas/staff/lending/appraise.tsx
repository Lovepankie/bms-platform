import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi, useNavigate } from '@tanstack/react-router';
import { useState, type FormEvent } from 'react';
import type { Loan } from '../../../api/lending';
import { asFormError } from '../../../api/lending-forms';
import { useApplicationsApi, useMembersApi } from '../../../api/lending-context';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { NoAccess } from '../../../app/ui/EmptyState';
import { exponentOf } from '../../../components/money';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { APPRAISAL_ORDER, appraisalBody, appraisalProblems, type AppraisalDraft } from './application-state';
import { ApplicationFacts } from './application-view';
import { Group, SaveBar, TextField, useFormErrors } from './form-fields';
import { LendingGate } from './loans';
import { currencyOf, money } from './loan-state';
import { holds, MEMBERS_READ } from './member-permissions';
import { applicationSteps } from './permissions';

// Appraise an application (issue #280 slice 4, FR-ORG-04, FR-ORG-05): the member's income and what they already pay each month,
// and notes from the visit. The server scores the loan with the business's weights, looks at the member's other loans, the loans
// they guarantee and their guarantors' loans, and keeps it all as a snapshot; the loan page shows the result. Appraising again
// records a newer snapshot. Nothing here is a field the form needs, so it has no starred field.

export function AppraiseForm({ loan }: { loan: Loan }) {
  const { me } = useStaff();
  const api = useApplicationsApi();
  const members = useMembersApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const currency = currencyOf(loan.currency);
  const [draft, setDraft] = useState<AppraisalDraft>({ income: '', obligations: '', notes: '' });
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const form = useFormErrors(appraisalProblems(draft, currency), 'apf');
  const member = useQuery({
    queryKey: ['members', 'one', loan.member_id],
    queryFn: () => members.get(loan.member_id ?? ''),
    enabled: !!loan.member_id && holds(me, MEMBERS_READ),
  });
  const declared = member.data?.monthly_income_minor;
  const largest = Math.max(0, ...(loan.provisional_schedule ?? []).map((r) => r.total_minor ?? 0));
  const inputMode = exponentOf(currency) ? 'decimal' : 'numeric';
  const set = <K extends keyof AppraisalDraft>(key: K, value: AppraisalDraft[K]) => setDraft((d) => ({ ...d, [key]: value }));

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || !form.attempt(APPRAISAL_ORDER)) return;
    setBusy(true);
    setFailure(null);
    try {
      await api.appraise(loan.id ?? '', loan.version ?? 0, appraisalBody(draft, currency));
      void queryClient.invalidateQueries({ queryKey: ['lending'] });
      void navigate({ to: '/staff/lending/loans/$loanId', params: { loanId: loan.id ?? '' } });
    } catch (raw) {
      const error = asFormError(raw);
      form.refuse(error.fields, APPRAISAL_ORDER);
      setFailure(Object.keys(error.fields).length > 0 ? null : error.message);
      // A newer version (someone else appraised, or a lost answer that did save): read the loan again for the next press.
      void queryClient.invalidateQueries({ queryKey: ['lending', 'loan', loan.id] });
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="mf" onSubmit={(e) => void submit(e)} noValidate aria-label="Appraise the application" data-tour="appraise-form">
      <section className="rt-card stack" aria-labelledby="apf-loan" data-tour="appraise-loan">
        <h2 id="apf-loan">The application</h2>
        <p>Loan {loan.loan_no}, member {loan.member_no}, product {loan.product_code}</p>
        <ApplicationFacts loan={loan} />
        {largest > 0 && <p>The largest payment would be <strong className="num">{money(largest, currency)}</strong>.</p>}
      </section>
      <Group title="What the member earns and pays" tourId="appraise-money">
        <TextField name="declared_monthly_income_minor" label={`Income a month (${currency})`} form={form} inputMode={inputMode} value={draft.income} onChange={(v) => set('income', v)}
          hint={declared != null ? `Leave empty to use the income on the member's details, ${money(declared, currency)}.` : 'Leave empty to use the income on the member\'s details.'} />
        <TextField name="monthly_obligations_minor" label={`Other payments a month (${currency})`} form={form} inputMode={inputMode} value={draft.obligations} onChange={(v) => set('obligations', v)}
          hint="Rent, school fees, other loans: what the member already pays each month. Leave empty for none." />
        <TextField name="visit_notes" label="Notes from the visit" form={form} type="textarea" rows={4} maxLength={2000} value={draft.notes} onChange={(v) => set('notes', v)} />
      </Group>
      <Problem error={failure ? new Error(failure) : null} />
      <SaveBar label={loan.status === 'appraised' ? 'Appraise again' : 'Appraise'} busy={busy} tourId="appraise-save"
        cancel={<Link to="/staff/lending/loans/$loanId" params={{ loanId: loan.id ?? '' }} className="btn btn-ghost">Cancel</Link>} />
    </form>
  );
}

const route = getRouteApi('/staff/lending/loans/$loanId/appraise');

function AppraisePage() {
  const { loanId } = route.useParams();
  return <AppraiseBody loanId={loanId} />;
}

/** The page body for a loan, with its gate: the step must be offered to this session for this loan (applicationSteps). */
export function AppraiseBody({ loanId }: { loanId: string }) {
  const { me } = useStaff();
  const api = useApplicationsApi();
  const loan = useQuery({ queryKey: ['lending', 'loan', loanId], queryFn: () => api.get(loanId) });
  const l = loan.data;
  useCrumbLabel(l?.loan_no ? `Appraise ${l.loan_no}` : undefined);
  return (
    <LendingGate title="Appraise">
      {loan.isPending && <p className="loading">Loading the application</p>}
      <Problem error={loan.error} />
      {l && (applicationSteps(me, l).includes('appraise') ? <AppraiseForm loan={l} /> : <NoAccess what="appraise this application" />)}
    </LendingGate>
  );
}

export const Route = createLazyRoute('/staff/lending/loans/$loanId/appraise')({ component: AppraisePage });
