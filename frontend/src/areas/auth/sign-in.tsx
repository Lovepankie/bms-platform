import { createLazyRoute, useNavigate } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { api, type MfaEnrolment, problemOf, type SignInResponse } from '../../api/client';
import { normaliseSecondFactor, recoveryCodesText } from '../../auth/codes';
import { setAccessToken } from '../../auth/session';

// Staff sign-in (FR-IAM-04 to FR-IAM-06, FR-IAM-11): password, then the second factor when the
// account has one, or TOTP enrolment when the role requires it. Recovery codes are shown once,
// right after enrolment, and never again.

type Step =
  | { kind: 'password' }
  | { kind: 'challenge'; mfaToken: string }
  | { kind: 'enrol'; mfaToken: string; enrolment: MfaEnrolment }
  | { kind: 'codes'; codes: string[] };

function SignIn() {
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>({ kind: 'password' });
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  function copySecret(secret: string) {
    void navigator.clipboard?.writeText(secret).then(() => setCopied(true));
  }

  async function afterSignIn(body: SignInResponse) {
    if (body.status === 'signed_in' && body.access_token) {
      setAccessToken(body.access_token);
      if (body.recovery_codes && body.recovery_codes.length > 0) {
        setStep({ kind: 'codes', codes: body.recovery_codes });
      } else {
        await navigate({ to: '/staff' });
      }
      return;
    }
    const mfaToken = body.mfa_token ?? '';
    if (body.status === 'mfa_required') {
      setStep({ kind: 'challenge', mfaToken });
    } else if (body.status === 'mfa_enrolment_required') {
      const { data, error } = await api.POST('/api/v1/auth/staff/mfa/enrol', { body: { mfa_token: mfaToken } });
      if (error || !data) throw error;
      setStep({ kind: 'enrol', mfaToken, enrolment: data });
    }
  }

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setMessage(null);
    try {
      await action();
    } catch (error) {
      setMessage(problemOf(error).detail ?? 'Something went wrong. Try again.');
    } finally {
      setBusy(false);
    }
  }

  function submitPassword(e: FormEvent) {
    e.preventDefault();
    void run(async () => {
      const { data, error } = await api.POST('/api/v1/auth/staff/login', { body: { login, password } });
      if (error || !data) throw error;
      setPassword('');
      await afterSignIn(data);
    });
  }

  function submitChallenge(e: FormEvent, mfaToken: string) {
    e.preventDefault();
    const factor = normaliseSecondFactor(code);
    if (factor.kind === 'invalid') {
      setMessage('Enter the 6 digit code from your app, or one recovery code.');
      return;
    }
    void run(async () => {
      const { data, error } = await api.POST('/api/v1/auth/staff/mfa/verify', {
        body: { mfa_token: mfaToken, code: factor.code },
      });
      if (error || !data) throw error;
      setCode('');
      await afterSignIn(data);
    });
  }

  function submitEnrolment(e: FormEvent, mfaToken: string) {
    e.preventDefault();
    void run(async () => {
      const { data, error } = await api.POST('/api/v1/auth/staff/mfa/confirm', {
        body: { mfa_token: mfaToken, code: code.replace(/\s+/g, '') },
      });
      if (error || !data) throw error;
      setCode('');
      await afterSignIn(data);
    });
  }

  return (
    <main className="card auth-card">
      <h1>Staff sign-in</h1>
      {step.kind === 'password' && <p className="lead">Use the email or phone and the password you set from your invitation.</p>}
      {message && <p role="alert" className="alert alert-danger">{message}</p>}

      {step.kind === 'password' && (
        <form onSubmit={submitPassword} className="form-stack">
          <label>
            Email or phone
            <input value={login} onChange={(e) => setLogin(e.target.value)} autoComplete="username" required />
          </label>
          <label>
            Password
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </label>
          <button className="btn-primary" disabled={busy}>{busy ? 'Signing in' : 'Sign in'}</button>
        </form>
      )}

      {step.kind === 'challenge' && (
        <form onSubmit={(e) => submitChallenge(e, step.mfaToken)} className="form-stack">
          <p className="muted">Enter the code from your authenticator app. Lost your phone? Enter one of your recovery codes.</p>
          <label>
            Code
            <input className="input-code" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" required />
          </label>
          <button className="btn-primary" disabled={busy}>Verify</button>
        </form>
      )}

      {step.kind === 'enrol' && (
        <form onSubmit={(e) => submitEnrolment(e, step.mfaToken)} className="form-stack">
          <h2>Set up your authenticator app</h2>
          <p className="muted">Your role needs a second step at sign-in. It keeps your account safe even if someone learns your password.</p>
          <ol className="steps">
            <li>Open an authenticator app on your phone, for example Google Authenticator or Microsoft Authenticator.</li>
            <li>Add an account and type in this setup key, or open the link on this phone.</li>
            <li>Enter the 6 digit code the app shows.</li>
          </ol>
          <div className="secret-box">
            <code aria-label="Setup key">{step.enrolment.secret}</code>
            <button type="button" className="btn-sm" onClick={() => copySecret(step.enrolment.secret ?? '')}>
              {copied ? 'Copied' : 'Copy'}
            </button>
          </div>
          <p className="hint">
            <a href={step.enrolment.otpauth_uri}>Open in my authenticator app</a>
          </p>
          <label>
            Code from the app
            <input className="input-code" inputMode="numeric" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" required />
          </label>
          <button className="btn-primary" disabled={busy}>Turn on</button>
        </form>
      )}

      {step.kind === 'codes' && (
        <section className="form-stack">
          <h2>Save your recovery codes</h2>
          <p>
            Each code signs you in once if you lose your phone. Save them somewhere safe now: they are shown only this
            once.
          </p>
          <pre className="codes-box">{recoveryCodesText(step.codes)}</pre>
          <button className="btn-primary" onClick={() => void navigate({ to: '/staff' })}>I have saved them</button>
        </section>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/sign-in')({ component: SignIn });
