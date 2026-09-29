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

const box = { display: 'grid', gap: 8, maxWidth: 360 } as const;

function SignIn() {
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>({ kind: 'password' });
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

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
    <main>
      <h1>Staff sign-in</h1>
      {message && <p role="alert">{message}</p>}

      {step.kind === 'password' && (
        <form onSubmit={submitPassword} style={box}>
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
          <button disabled={busy}>Sign in</button>
        </form>
      )}

      {step.kind === 'challenge' && (
        <form onSubmit={(e) => submitChallenge(e, step.mfaToken)} style={box}>
          <p>Enter the code from your authenticator app. Lost your phone? Enter one of your recovery codes.</p>
          <label>
            Code
            <input value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" required />
          </label>
          <button disabled={busy}>Verify</button>
        </form>
      )}

      {step.kind === 'enrol' && (
        <form onSubmit={(e) => submitEnrolment(e, step.mfaToken)} style={box}>
          <p>Your role requires a second factor. Add this account to an authenticator app, then enter the code it shows.</p>
          <p>
            Secret: <code>{step.enrolment.secret}</code>
          </p>
          <p style={{ wordBreak: 'break-all', fontSize: 12 }}>
            <a href={step.enrolment.otpauth_uri}>{step.enrolment.otpauth_uri}</a>
          </p>
          <label>
            Code from the app
            <input value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" required />
          </label>
          <button disabled={busy}>Turn on</button>
        </form>
      )}

      {step.kind === 'codes' && (
        <section style={box}>
          <h2>Your recovery codes</h2>
          <p>
            Each code signs you in once if you lose your phone. Save them somewhere safe now: they are shown only this
            once.
          </p>
          <pre>{recoveryCodesText(step.codes)}</pre>
          <button onClick={() => void navigate({ to: '/staff' })}>I have saved them</button>
        </section>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/sign-in')({ component: SignIn });
