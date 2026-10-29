import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Fragment, useState, type FormEvent } from 'react';
import type { Loan } from '../../../api/lending';
import { asFormError } from '../../../api/lending-forms';
import { useApplicationsApi } from '../../../api/lending-context';
import type { LoanAppraisal, LoanStatusChange } from '../../../api/loan-applications';
import { BusyButton } from '../../../app/ui/BusyButton';
import { FieldsNeededNote } from '../../../app/ui/Field';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { sayDone } from '../retail/toast';
import { exposureOf, flagWords, purposeWords, rateWords, recommendationWords, scoreParts, termWords } from './application-state';
import { typeWords } from './collateral-state';
import { TextField, useFormErrors } from './form-fields';
import { money, words } from './loan-state';
import { holds, COLLATERAL_READ, MEMBERS_READ } from './member-permissions';
import { applicationSteps, type ApplicationStep } from './permissions';

// The application part of the loan page (issue #280 slices 3 and 4): where the application stands, its terms, guarantors and
// collateral, the latest appraisal, the provisional schedule, and the next step the session may take. The next step sits in a
// bar stuck to the bottom of a phone (one primary action); the others are plain buttons above. The server checks every step.

/** Statuses in which a loan is still an application (nothing paid out). */
export const APPLICATION_STATUSES = ['draft', 'submitted', 'appraised', 'rejected', 'cancelled'];

const day = (iso: string | undefined) => (iso ? iso.slice(0, 10) : '');

function statusLine(l: Loan, sentBack: LoanStatusChange | undefined): string {
  const c = l.currency;
  switch (l.status) {
    case 'draft':
      return sentBack ? `Sent back on ${day(sentBack.at)}: ${sentBack.reason ?? ''}` : 'Draft: not sent for appraisal yet.';
    case 'submitted':
      return `Sent for appraisal on ${day(l.submitted_at)}. Waiting for an appraisal.`;
    case 'appraised':
      return 'Appraised. Waiting for a decision.';
    case 'approved':
      return `Approved on ${day(l.approved_at)} for ${money(l.approved_principal_minor, c)} over ${termWords(l.approved_term_count, l.term_unit)}. Ready to pay out.`;
    case 'rejected':
      return `Declined: ${l.rejected_reason ?? ''}`;
    case 'cancelled':
      return l.cancelled_reason === 'approval_expired' ? 'Cancelled: the approval ran out before the loan was paid out.' : `Cancelled: ${l.cancelled_reason ?? ''}`;
    default:
      return words(l.status);
  }
}

export function ApplicationFacts({ loan: l }: { loan: Loan }) {
  const c = l.currency;
  return (
    <dl className="ln-facts">
      <dt>Asked for</dt>
      <dd>{money(l.requested_principal_minor, c)}</dd>
      <dt>Over</dt>
      <dd>{termWords(l.requested_term_count, l.term_unit)}</dd>
      {l.approved_principal_minor != null && (
        <>
          <dt className="ln-strong">Approved</dt>
          <dd className="ln-strong">{money(l.approved_principal_minor, c)}, {termWords(l.approved_term_count, l.term_unit)}</dd>
        </>
      )}
      <dt>Interest</dt>
      <dd>{rateWords(l.interest_rate_bp, l.rate_unit, l.interest_method)}</dd>
      <dt>For</dt>
      <dd>{purposeWords(l.purpose_category)}{l.purpose_text ? `: ${l.purpose_text}` : ''}</dd>
      <dt>Money wanted on</dt>
      <dd>{l.proposed_disbursement_date ?? 'The day it is sent'}</dd>
    </dl>
  );
}

function Parties({ loan: l }: { loan: Loan }) {
  const { me } = useStaff();
  const c = l.currency;
  const guarantors = l.guarantors ?? [];
  const pledges = l.collateral ?? [];
  const readMembers = holds(me, MEMBERS_READ);
  const readCollateral = holds(me, COLLATERAL_READ);
  return (
    <>
      <section className="rt-card stack" aria-labelledby="ap-guarantors" data-tour="application-parties">
        <h2 id="ap-guarantors">Guarantors</h2>
        {guarantors.length === 0 ? <p className="empty-state">No guarantor.</p> : (
          <ul className="ln-list">
            {guarantors.map((g) => (
              <li key={g.member_id} className="ln-item-head">
                <span>
                  {readMembers ? <Link to="/staff/lending/members/$memberId" params={{ memberId: g.member_id ?? '' }}>Member {g.member_no}</Link> : `Member ${g.member_no ?? ''}`}
                  {g.relationship ? `, ${g.relationship}` : ''}
                </span>
                <span className="num">{money(g.guaranteed_amount_minor, c)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section className="rt-card stack" aria-labelledby="ap-collateral">
        <h2 id="ap-collateral">Collateral</h2>
        {pledges.length === 0 ? <p className="empty-state">No collateral.</p> : (
          <ul className="ln-list">
            {pledges.map((p) => (
              <li key={p.collateral_id} className="ln-item-head">
                <span>
                  {readCollateral ? <Link to="/staff/lending/collateral/$collateralId" params={{ collateralId: p.collateral_id ?? '' }}>{typeWords(p.collateral_type)}</Link> : typeWords(p.collateral_type)}
                  {p.collateral_value_minor != null ? <span className="ln-muted">, worth {money(p.collateral_value_minor, c)}</span> : null}
                </span>
                <span className="num">{money(p.pledged_value_minor, c)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

/** The latest appraisal: the score and band, what the score recommends, the warnings, the parts of the score and other loans. */
/** `earlier`: the application was sent back and sent again since, so this one no longer stands and is kept only for reference. */
export function AppraisalCard({ appraisal: a, currency, earlier = false }: { appraisal: LoanAppraisal; currency: string | undefined; earlier?: boolean }) {
  const parts = scoreParts(a);
  return (
    <section className="rt-card stack" aria-labelledby="ap-appraisal" data-tour="application-appraisal">
      <h2 id="ap-appraisal">{earlier ? 'Earlier appraisal' : 'Appraisal'}</h2>
      {earlier && <p className="alert alert-info" role="note">From before the application was sent back. It no longer stands: the application is waiting to be appraised again.</p>}
      <p className="ln-item-head">
        <span className="ap-score">{a.score} of 100</span>
        <span className="badge">Band {a.band}</span>
      </p>
      <p>The score suggests: <strong>{recommendationWords(a.recommendation)}</strong>. It only advises; a person decides.</p>
      {(a.flags ?? []).length > 0 && (
        <div className="alert alert-warning" role="note">
          <ul>
            {(a.flags ?? []).map((f) => <li key={f}>{flagWords(f)}</li>)}
          </ul>
        </div>
      )}
      <dl className="ln-facts">
        {parts.map((p) => (
          <Fragment key={p.label}>
            <dt>{p.label}</dt>
            <dd>{p.points} of {p.max}</dd>
          </Fragment>
        ))}
        <dt>Income a month</dt>
        <dd>{a.declared_monthly_income_minor != null ? money(a.declared_monthly_income_minor, currency) : 'Not given'}</dd>
        <dt>Other payments a month</dt>
        <dd>{money(a.monthly_obligations_minor ?? 0, currency)}</dd>
        <dt>Appraised on</dt>
        <dd>{day(a.created_at)}</dd>
      </dl>
      {a.visit_notes && <p className="ln-muted">Visit notes: {a.visit_notes}</p>}
      {exposureOf(a).filter((g) => g.loans.length > 0).map((g) => (
        <div key={g.label} className="stack">
          <h3>{g.label}</h3>
          <ul className="ln-list">
            {g.loans.map((x) => (
              <li key={x.loan_no} className="ln-item-head">
                <span>{x.loan_no}, {words(x.status)}{(x.days_past_due ?? 0) > 0 ? <span className="rt-flag"> {x.days_past_due} days late</span> : null}</span>
                <span className="num">{money(x.outstanding_minor, currency)}</span>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </section>
  );
}

function ProvisionalSchedule({ loan: l }: { loan: Loan }) {
  const rows = l.provisional_schedule ?? [];
  if (rows.length === 0) return null;
  const c = l.currency;
  return (
    <>
      <h2 id="ap-schedule" data-tour="application-schedule">Payments it would mean</h2>
      <p className="ln-muted">Worked out from the {l.approved_principal_minor != null ? 'approved' : 'asked'} amount and time. The real schedule is set when the loan is paid out.</p>
      <div className="table-wrap" tabIndex={0} role="region" aria-labelledby="ap-schedule">
        <table>
          <thead>
            <tr>
              <th className="num">No</th>
              <th>Due date</th>
              <th className="num">Principal</th>
              <th className="num">Interest</th>
              <th className="num">Fees</th>
              <th className="num">Total</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.no}>
                <td className="num">{r.no}</td>
                <td>{r.due_date}</td>
                <td className="num">{money(r.principal_minor, c)}</td>
                <td className="num">{money(r.interest_minor, c)}</td>
                <td className="num">{money(r.fee_minor, c)}</td>
                <td className="num">{money(r.total_minor, c)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

/** A note the step needs (why it goes back, why it is cancelled), with its error under the box once the person leaves it. */
function NoteForm({ name, label, button, done, send, onDone }: { name: string; label: string; button: string; done: string; send: (note: string) => Promise<unknown>; onDone: () => void }) {
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const form = useFormErrors(note.trim() === '' ? { note: 'Write a short note. The loan officer reads it.' } : {}, name);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || !form.attempt(['note'])) return;
    setBusy(true);
    setFailure(null);
    try {
      await send(note.trim());
      sayDone(done);
      onDone();
    } catch (raw) {
      setFailure(asFormError(raw).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className="rt-card stack" aria-label={button} onSubmit={(e) => void submit(e)} noValidate>
      <FieldsNeededNote />
      <TextField name="note" label={label} form={form} required type="textarea" rows={2} maxLength={1000} value={note} onChange={setNote} />
      <Problem error={failure ? new Error(failure) : null} />
      <BusyButton busy={busy} type="submit" className="btn-block">{button}</BusyButton>
    </form>
  );
}

const STEP_LABELS: Record<ApplicationStep, string> = {
  submit: 'Send for appraisal',
  edit: 'Change the application',
  appraise: 'Appraise',
  decide: 'Decide',
  send_back: 'Send back for changes',
  cancel: 'Cancel the application',
};

/** The application on the loan page. */
export function ApplicationPanel({ loan: l }: { loan: Loan }) {
  const { me } = useStaff();
  const api = useApplicationsApi();
  const queryClient = useQueryClient();
  const id = l.id ?? '';
  const [open, setOpen] = useState<'send_back' | 'cancel' | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  // A cancelled application keeps the appraisal that informed the cancel; a draft never had one that still stands.
  const appraised = ['appraised', 'approved', 'rejected', 'submitted', 'cancelled'].includes(l.status ?? '');
  const appraisals = useQuery({ queryKey: ['lending', 'loan', id, 'appraisals'], queryFn: () => api.appraisals(id), enabled: appraised });
  const history = useQuery({ queryKey: ['lending', 'loan', id, 'history'], queryFn: () => api.history(id), enabled: l.status === 'draft' });
  const last = history.data?.at(-1);
  const sentBack = last && last.to_status === 'draft' && last.from_status !== null && last.from_status !== undefined ? last : undefined;
  const steps = applicationSteps(me, l);
  // One primary action: the step that moves the application on, if the session may take it.
  const primary = (['submit', 'decide', 'appraise'] as const).find((s) => steps.includes(s) && !(s === 'appraise' && l.status === 'appraised' && steps.includes('decide')));
  const others = steps.filter((s) => s !== primary);
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['lending'] });

  async function sendNow() {
    if (busy) return;
    setBusy(true);
    setFailure(null);
    try {
      await api.submit(id, l.version ?? 0);
      sayDone('Sent for appraisal');
      refresh();
    } catch (raw) {
      setFailure(asFormError(raw).message);
      refresh();
    } finally {
      setBusy(false);
    }
  }

  const stepControl = (s: ApplicationStep, className: string) => {
    if (s === 'submit') return <BusyButton key={s} busy={busy} type="button" className={className} onClick={() => void sendNow()} data-tour="application-next">{STEP_LABELS[s]}</BusyButton>;
    if (s === 'edit') return <Link key={s} to="/staff/lending/loans/$loanId/edit" params={{ loanId: id }} className={`btn ${className}`}>{STEP_LABELS[s]}</Link>;
    if (s === 'appraise') {
      return <Link key={s} to="/staff/lending/loans/$loanId/appraise" params={{ loanId: id }} className={`btn ${className}`} data-tour={s === primary ? 'application-next' : undefined}>{l.status === 'appraised' ? 'Appraise again' : STEP_LABELS[s]}</Link>;
    }
    if (s === 'decide') return <Link key={s} to="/staff/lending/loans/$loanId/decide" params={{ loanId: id }} className={`btn ${className}`} data-tour="application-next">{STEP_LABELS[s]}</Link>;
    return <button key={s} type="button" aria-pressed={open === s} onClick={() => setOpen(open === s ? null : s)}>{STEP_LABELS[s]}</button>;
  };

  return (
    <>
      <section className="rt-card stack" aria-labelledby="ap-title" data-tour="application-status">
        <h2 id="ap-title">Application</h2>
        <p className={l.status === 'rejected' || l.status === 'cancelled' || sentBack ? 'alert alert-warning' : undefined}>{statusLine(l, sentBack)}</p>
        <ApplicationFacts loan={l} />
      </section>
      {others.length > 0 && (
        <div className="ln-actions" role="group" aria-label="Application steps" data-tour="application-steps">
          {others.map((s) => stepControl(s, ''))}
        </div>
      )}
      {open === 'send_back' && (
        <NoteForm name="sb" label="What needs changing" button="Send back for changes" done="Sent back for changes" send={(note) => api.sendBack(id, l.version ?? 0, note)} onDone={() => { setOpen(null); refresh(); }} />
      )}
      {open === 'cancel' && (
        <NoteForm name="cn" label="Why it is cancelled" button="Cancel the application" done="Application cancelled" send={(note) => api.cancel(id, l.version ?? 0, note)} onDone={() => { setOpen(null); refresh(); }} />
      )}
      <Problem error={failure ? new Error(failure) : null} />
      <Problem error={appraisals.error} />
      {appraisals.data?.[0] && <AppraisalCard appraisal={appraisals.data[0]} currency={l.currency} earlier={l.status === 'submitted'} />}
      <Parties loan={l} />
      <ProvisionalSchedule loan={l} />
      {primary && (
        <div className="mf-savebar">
          <div className="mf-savebar-row">{stepControl(primary, 'rt-primary')}</div>
        </div>
      )}
    </>
  );
}
