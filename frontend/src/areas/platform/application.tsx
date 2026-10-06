import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, Link, useParams } from '@tanstack/react-router';
import { type FormEvent, useEffect, useState } from 'react';
import {
  activateApplication,
  type ActivationResult,
  type ApplicationDetail,
  type Decision,
  decide,
  getApplication,
  OnboardingError,
  type PossibleDuplicate,
  statusLabel,
} from '../../api/onboarding';
import {
  type ActivateForm,
  activateFormFrom,
  activateProblems,
  MODULES,
  type Problems,
  TERMS,
  toActivateRequest,
  toggle,
} from '../onboarding/forms';

// One application (FR-ONB-05 to FR-ONB-07): the details, the possible-duplicate warning, the
// decisions (Verify, Needs info with a note the applicant sees, Reject with a reason) and Activate.

export function DuplicateWarning({ duplicates }: { duplicates: PossibleDuplicate[] }) {
  if (duplicates.length === 0) return null;
  return (
    <section className="alert alert-warning" role="note" aria-labelledby="dup-heading">
      <div>
      <h2 id="dup-heading">Possible repeat</h2>
      <p>This may be a business that applied or had a free month before. Check before you activate a trial.</p>
      <ul>
        {duplicates.map((d) => (
          <li key={`${d.kind}-${d.id}`}>
            {d.kind === 'tenant' ? 'Tenant' : 'Application'} {d.reference}: {d.name} ({statusLabel(d.status) || d.status}), same{' '}
            {(d.matched_on ?? []).map((m) => m.replace('_', ' ')).join(', ')}
          </li>
        ))}
      </ul>
      </div>
    </section>
  );
}

export function ApplicationFacts({ detail }: { detail: ApplicationDetail }) {
  const a = detail.application ?? {};
  return (
    <dl className="facts">
      <dt>Status</dt>
      <dd>{statusLabel(a.status)}</dd>
      <dt>Business</dt>
      <dd>{a.business_name}</dd>
      <dt>Contact</dt>
      <dd>
        {a.contact_name}, {a.contact_phone}, {a.contact_email}
      </dd>
      <dt>Country</dt>
      <dd>{a.country}</dd>
      <dt>Wanted</dt>
      <dd>
        {(a.modules ?? []).join(', ')}, {a.term}, {a.way_in === 'paid' ? 'subscribe now' : 'one month free'}
      </dd>
      {a.agent_code && (
        <>
          <dt>Agent code</dt>
          <dd>{a.agent_code} (recorded only)</dd>
        </>
      )}
      {a.message && (
        <>
          <dt>Message</dt>
          <dd className="pre-line">{a.message}</dd>
        </>
      )}
      {a.operator_note && (
        <>
          <dt>Our question</dt>
          <dd className="pre-line">{a.operator_note}</dd>
        </>
      )}
      {a.applicant_reply && (
        <>
          <dt>Their answer</dt>
          <dd className="pre-line">{a.applicant_reply}</dd>
        </>
      )}
      {a.reject_reason && (
        <>
          <dt>Reason given</dt>
          <dd className="pre-line">{a.reject_reason}</dd>
        </>
      )}
      {a.status === 'activated' && (
        <>
          <dt>Activated</dt>
          <dd>
            {a.activated_at?.slice(0, 16).replace('T', ' ')} as {a.activated_tenant_slug}, {a.activation_way === 'paid' ? 'paid' : 'free month'}
            {a.activation_note ? `: ${a.activation_note}` : ''}
          </dd>
        </>
      )}
    </dl>
  );
}

export interface DecisionPanelProps {
  status: string;
  busy: boolean;
  note: string;
  onNote: (v: string) => void;
  onDecide: (d: Decision) => void;
}

export function DecisionPanel({ status, busy, note, onNote, onDecide }: DecisionPanelProps) {
  const canVerify = status === 'submitted' || status === 'needs_info';
  const canAsk = status === 'submitted' || status === 'verified';
  const canReject = canVerify || status === 'verified';
  if (!canVerify && !canAsk && !canReject) return null;
  return (
    <section aria-labelledby="decide-heading" className="form-stack">
      <h2 id="decide-heading">Decide</h2>
      {canVerify && (
        <button className="btn-brand" type="button" disabled={busy} onClick={() => onDecide('verify')}>
          Verify
        </button>
      )}
      <div>
        <label htmlFor="decision-note">Note to the applicant (for Needs info or Reject; they see it)</label>
        <textarea id="decision-note" value={note} onChange={(e) => onNote(e.target.value)} rows={3} maxLength={1000} />
      </div>
      <div className="cluster">
        {canAsk && (
          <button type="button" disabled={busy || note.trim().length === 0} onClick={() => onDecide('needs-info')}>
            Needs info
          </button>
        )}
        {canReject && (
          <button type="button" disabled={busy || note.trim().length === 0} onClick={() => onDecide('reject')}>
            Reject
          </button>
        )}
      </div>
    </section>
  );
}

export interface ActivateFormViewProps {
  form: ActivateForm;
  problems: Problems;
  busy: boolean;
  onChange: (form: ActivateForm) => void;
  onSubmit: (e: FormEvent) => void;
}

export function ActivateFormView({ form, problems, busy, onChange, onSubmit }: ActivateFormViewProps) {
  const set = (patch: Partial<ActivateForm>) => onChange({ ...form, ...patch });
  return (
    <form onSubmit={onSubmit} className="form-stack" aria-labelledby="activate-heading" aria-busy={busy}>
      <h2 id="activate-heading">Activate</h2>
      <fieldset>
        <legend>Modules</legend>
        {MODULES.map((m) => (
          <label key={m.key}>
            <input
              type="checkbox"
              checked={form.modules.includes(m.key)}
              onChange={(e) => set({ modules: toggle(form.modules, m.key, e.target.checked) })}
            />
            {m.label}
          </label>
        ))}
        {problems.modules && <span className="field-error">{problems.modules}</span>}
      </fieldset>
      <div>
        <label htmlFor="activate-term">Term</label>
        <select id="activate-term" value={form.term} onChange={(e) => set({ term: e.target.value })}>
          {TERMS.map((t) => (
            <option key={t.key} value={t.key}>
              {t.label}
            </option>
          ))}
        </select>
      </div>
      <fieldset>
        <legend>Way in</legend>
        <label>
          <input type="radio" name="activate-way" checked={form.way_in === 'trial'} onChange={() => set({ way_in: 'trial' })} />
          One month free (trial)
        </label>
        <label>
          <input type="radio" name="activate-way" checked={form.way_in === 'paid'} onChange={() => set({ way_in: 'paid' })} />
          Paid: payment received
        </label>
      </fieldset>
      <div>
        <label htmlFor="activate-note">Payment received (method, reference, date){form.way_in === 'paid' ? '' : ' (optional)'}</label>
        <textarea
          id="activate-note"
          value={form.payment_note}
          onChange={(e) => set({ payment_note: e.target.value })}
          rows={2}
          maxLength={500}
          aria-invalid={Boolean(problems.payment_note)}
          aria-describedby={problems.payment_note ? 'activate-note-problem' : undefined}
        />
        {problems.payment_note && (
          <span id="activate-note-problem" className="field-error">
            {problems.payment_note}
          </span>
        )}
      </div>
      <div>
        <label htmlFor="activate-slug">Address name (slug, cannot change later)</label>
        <input
          id="activate-slug"
          value={form.slug}
          onChange={(e) => set({ slug: e.target.value.toLowerCase() })}
          maxLength={63}
          aria-invalid={Boolean(problems.slug)}
          aria-describedby={problems.slug ? 'activate-slug-problem' : undefined}
        />
        {problems.slug && (
          <span id="activate-slug-problem" className="field-error">
            {problems.slug}
          </span>
        )}
      </div>
      <button className="btn-brand" type="submit" disabled={busy}>
        {busy ? 'Activating' : 'Activate and send the link'}
      </button>
    </form>
  );
}

export function ActivationDone({ result }: { result: ActivationResult }) {
  return (
    <section role="status" className="alert alert-success">
      <div>
      {result.activated_now ? (
        <>
          <p>
            Tenant <strong>{result.tenant_slug}</strong> is created. The activation link is queued for the applicant's email.
          </p>
          {result.admin_invitation_url && (
            <p className="wrap-anywhere">
              Shown once, in case the email does not arrive: <code>{result.admin_invitation_url}</code>
            </p>
          )}
        </>
      ) : (
        <p>This application was already activated as {result.tenant_slug}. Nothing was changed.</p>
      )}
      </div>
    </section>
  );
}

function ApplicationScreen() {
  const { applicationId } = useParams({ from: '/platform/applications/$applicationId' });
  const client = useQueryClient();
  const query = useQuery({ queryKey: ['application', applicationId], queryFn: () => getApplication(applicationId) });
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState<ActivateForm | null>(null);
  const [problems, setProblems] = useState<Problems>({});
  const [result, setResult] = useState<ActivationResult | null>(null);

  useEffect(() => {
    if (query.data && form === null) setForm(activateFormFrom(query.data));
  }, [query.data, form]);

  async function run(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await action();
      await client.invalidateQueries({ queryKey: ['applications'] });
    } catch (err) {
      setError(err instanceof OnboardingError ? err.message : 'Something went wrong. Try again.');
    } finally {
      setBusy(false);
    }
  }

  function onDecide(decision: Decision) {
    void run(async () => {
      const next = await decide(applicationId, decision, note);
      client.setQueryData(['application', applicationId], next);
      setNote('');
    });
  }

  function onActivate(e: FormEvent) {
    e.preventDefault();
    if (!form) return;
    const found = activateProblems(form);
    setProblems(found);
    if (Object.keys(found).length > 0) return;
    void run(async () => {
      const done = await activateApplication(applicationId, toActivateRequest(form));
      setResult(done);
      await client.invalidateQueries({ queryKey: ['application', applicationId] });
    });
  }

  return (
    <main>
      <p>
        <Link to="/platform">Back to applications</Link>
      </p>
      <h1>Application {query.data?.application?.reference}</h1>
      {query.isPending && <p role="status" className="loading">Loading</p>}
      {query.isError && (
        <p role="alert" className="alert alert-danger">
          {query.error.message}
        </p>
      )}
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
      {result && <ActivationDone result={result} />}
      {query.data && (
        <>
          <DuplicateWarning duplicates={query.data.possible_duplicates ?? []} />
          <ApplicationFacts detail={query.data} />
          <DecisionPanel status={query.data.application?.status ?? ''} busy={busy} note={note} onNote={setNote} onDecide={onDecide} />
          {query.data.application?.status === 'verified' && form && (
            <ActivateFormView form={form} problems={problems} busy={busy} onChange={setForm} onSubmit={onActivate} />
          )}
        </>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/platform/applications/$applicationId')({ component: ApplicationScreen });
