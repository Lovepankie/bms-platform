import { createLazyRoute } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { applyForAccount, OnboardingError } from '../../api/onboarding';
import {
  COUNTRIES,
  EMPTY_SIGN_UP,
  MODULES,
  type Problems,
  type SignUpForm,
  signUpProblems,
  TERMS,
  toggle,
  toSignUpRequest,
  WAYS_IN,
} from './forms';

// The public sign-up page on the platform host (FR-ONB-01, spec section 10): phone first, plain
// wording, the Rincoltech brand and the footer from the shared shell. No sign-in. The answer is the
// same whether or not the email is known: "check your email".

export interface SignUpViewProps {
  form: SignUpForm;
  problems: Problems;
  busy: boolean;
  error: string | null;
  done: boolean;
  onChange: (form: SignUpForm) => void;
  onSubmit: (e: FormEvent) => void;
}

function Field({ id, label, problem, children }: { id: string; label: string; problem?: string; children: React.ReactNode }) {
  return (
    <div>
      <label htmlFor={id}>{label}</label>
      {children}
      {problem && (
        <span id={`${id}-problem`} className="field-error">
          {problem}
        </span>
      )}
    </div>
  );
}

export function SignUpView({ form, problems, busy, error, done, onChange, onSubmit }: SignUpViewProps) {
  if (done) {
    return (
      <main className="card auth-card">
        <h1>Check your email</h1>
        <p role="status">
          We have sent a link to <strong>{form.contact_email}</strong>. Open it to confirm your email. We then look at your
          application and contact you.
        </p>
        <p>No email after a few minutes? Look in your spam folder, or apply again with the same email to get a new link.</p>
      </main>
    );
  }
  const set = (patch: Partial<SignUpForm>) => onChange({ ...form, ...patch });
  const describedBy = (name: string) => (problems[name] ? `${name}-problem` : undefined);
  return (
    <main className="card auth-card">
      <h1>Start using BMS</h1>
      <p>Tell us about your business. We check every application and then send you a link to sign in.</p>
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
      <form onSubmit={onSubmit} className="form-stack" noValidate aria-busy={busy}>
        <Field id="business_name" label="Business name" problem={problems.business_name}>
          <input
            id="business_name"
            value={form.business_name}
            onChange={(e) => set({ business_name: e.target.value })}
            maxLength={200}
            autoComplete="organization"
            required
            aria-invalid={Boolean(problems.business_name)}
            aria-describedby={describedBy('business_name')}
          />
        </Field>
        <Field id="contact_name" label="Your name" problem={problems.contact_name}>
          <input
            id="contact_name"
            value={form.contact_name}
            onChange={(e) => set({ contact_name: e.target.value })}
            maxLength={200}
            autoComplete="name"
            required
            aria-invalid={Boolean(problems.contact_name)}
            aria-describedby={describedBy('contact_name')}
          />
        </Field>
        <Field id="contact_phone" label="Phone number" problem={problems.contact_phone}>
          <input
            id="contact_phone"
            type="tel"
            inputMode="tel"
            value={form.contact_phone}
            onChange={(e) => set({ contact_phone: e.target.value })}
            maxLength={20}
            autoComplete="tel"
            placeholder="0700 000 000"
            required
            aria-invalid={Boolean(problems.contact_phone)}
            aria-describedby={describedBy('contact_phone')}
          />
        </Field>
        <Field id="contact_email" label="Email" problem={problems.contact_email}>
          <input
            id="contact_email"
            type="email"
            inputMode="email"
            value={form.contact_email}
            onChange={(e) => set({ contact_email: e.target.value })}
            maxLength={254}
            autoComplete="email"
            required
            aria-invalid={Boolean(problems.contact_email)}
            aria-describedby={describedBy('contact_email')}
          />
        </Field>
        <Field id="country" label="Country">
          <select id="country" value={form.country} onChange={(e) => set({ country: e.target.value })}>
            {COUNTRIES.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </Field>
        <fieldset aria-describedby={describedBy('modules')}>
          <legend>What do you want to use?</legend>
          {MODULES.map((m) => (
            <label key={m.key}>
              <input
                type="checkbox"
                name="modules"
                value={m.key}
                checked={form.modules.includes(m.key)}
                onChange={(e) => set({ modules: toggle(form.modules, m.key, e.target.checked) })}
              />
              {m.label}
            </label>
          ))}
          {problems.modules && (
            <span id="modules-problem" className="field-error">
              {problems.modules}
            </span>
          )}
        </fieldset>
        <fieldset>
          <legend>How do you want to start?</legend>
          {WAYS_IN.map((w) => (
            <label key={w.key}>
              <input type="radio" name="way_in" value={w.key} checked={form.way_in === w.key} onChange={() => set({ way_in: w.key })} />
              {w.label}
            </label>
          ))}
        </fieldset>
        <fieldset>
          <legend>Pay</legend>
          {TERMS.map((t) => (
            <label key={t.key}>
              <input type="radio" name="term" value={t.key} checked={form.term === t.key} onChange={() => set({ term: t.key })} />
              {t.label}
            </label>
          ))}
        </fieldset>
        <Field id="agent_code" label="Agent code (if someone referred you)" problem={problems.agent_code}>
          <input
            id="agent_code"
            value={form.agent_code}
            onChange={(e) => set({ agent_code: e.target.value })}
            maxLength={40}
            aria-invalid={Boolean(problems.agent_code)}
            aria-describedby={describedBy('agent_code')}
          />
        </Field>
        <Field id="message" label="Anything we should know (optional)" problem={problems.message}>
          <textarea id="message" value={form.message} onChange={(e) => set({ message: e.target.value })} maxLength={1000} rows={3} />
        </Field>
        {/* The hidden field: off screen, out of the tab order and hidden from screen readers. */}
        <div className="visually-hidden" aria-hidden="true">
          <label htmlFor="website">Website</label>
          <input id="website" name="website" tabIndex={-1} autoComplete="off" value={form.website} onChange={(e) => set({ website: e.target.value })} />
        </div>
        <button className="btn-brand" type="submit" disabled={busy}>
          {busy ? 'Sending' : 'Send application'}
        </button>
      </form>
    </main>
  );
}

function SignUp() {
  const [form, setForm] = useState<SignUpForm>(EMPTY_SIGN_UP);
  const [problems, setProblems] = useState<Problems>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    const found = signUpProblems(form);
    setProblems(found);
    if (Object.keys(found).length > 0) {
      setError('Check the fields marked below.');
      return;
    }
    setBusy(true);
    setError(null);
    applyForAccount(toSignUpRequest(form))
      .then(() => setDone(true))
      .catch((err: unknown) => {
        if (err instanceof OnboardingError) {
          const server: Problems = {};
          err.fields.forEach((f) => (server[f.field] = f.message));
          setProblems(server);
          setError(err.message);
        } else {
          setError('We could not send your application. Check your connection and try again.');
        }
      })
      .finally(() => setBusy(false));
  }

  return <SignUpView form={form} problems={problems} busy={busy} error={error} done={done} onChange={setForm} onSubmit={submit} />;
}

export const Route = createLazyRoute('/sign-up')({ component: SignUp });
