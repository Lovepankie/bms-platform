import { createLazyRoute, useNavigate } from '@tanstack/react-router';
import { type FormEvent, useEffect, useState } from 'react';
import { api, problemOf } from '../../api/client';
import type { MfaEnrolment, SignInResponse } from '../../api/onboarding';
import { normaliseSecondFactor, recoveryCodesText } from '../../auth/codes';
import { setAccessToken, setSessionScope } from '../../auth/session';

// Platform operator sign-in on the platform host (chapter 8 section 8.2, ADR-014): email and
// password, then the mandatory second factor, enrolled at the first sign-in; recovery codes are
// shown once. The first password is set from the one-time setup link (#token=...).

export type Step =
  | { kind: 'password' }
  | { kind: 'challenge'; mfaToken: string }
  | { kind: 'enrol'; mfaToken: string; enrolment: MfaEnrolment }
  | { kind: 'codes'; codes: string[] };

export interface OperatorSignInViewProps {
  step: Step;
  login: string;
  password: string;
  code: string;
  busy: boolean;
  message: string | null;
  onLogin: (v: string) => void;
  onPassword: (v: string) => void;
  onCode: (v: string) => void;
  onSubmitPassword: (e: FormEvent) => void;
  onSubmitChallenge: (e: FormEvent) => void;
  onSubmitEnrolment: (e: FormEvent) => void;
  onCodesSaved: () => void;
}

export function OperatorSignInView(p: OperatorSignInViewProps) {
  return (
    <main className="card auth-card">
      <h1>Operator sign-in</h1>
      {p.message && (
        <p role="alert" className="alert alert-danger">
          {p.message}
        </p>
      )}
      {p.step.kind === 'password' && (
        <form onSubmit={p.onSubmitPassword} className="form-stack" aria-busy={p.busy}>
          <div>
            <label htmlFor="op-login">Email</label>
            <input id="op-login" type="email" value={p.login} onChange={(e) => p.onLogin(e.target.value)} autoComplete="username" required />
          </div>
          <div>
            <label htmlFor="op-password">Password</label>
            <input
              id="op-password"
              type="password"
              value={p.password}
              onChange={(e) => p.onPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </div>
          <button className="btn-brand" type="submit" disabled={p.busy}>
            {p.busy ? 'Signing in' : 'Sign in'}
          </button>
        </form>
      )}
      {p.step.kind === 'challenge' && (
        <form onSubmit={p.onSubmitChallenge} className="form-stack" aria-busy={p.busy}>
          <p>Enter the code from your authenticator app, or one of your recovery codes.</p>
          <div>
            <label htmlFor="op-code">Code</label>
            <input id="op-code" value={p.code} onChange={(e) => p.onCode(e.target.value)} autoComplete="one-time-code" inputMode="numeric" className="input-code" required />
          </div>
          <button className="btn-brand" type="submit" disabled={p.busy}>
            Verify
          </button>
        </form>
      )}
      {p.step.kind === 'enrol' && (
        <form onSubmit={p.onSubmitEnrolment} className="form-stack" aria-busy={p.busy}>
          <p>Operators must use a second factor. Add this account to an authenticator app, then enter the code it shows.</p>
          <div className="secret-box">
            <code>{p.step.enrolment.secret}</code>
          </div>
          <p className="wrap-anywhere">
            <a href={p.step.enrolment.otpauth_uri}>{p.step.enrolment.otpauth_uri}</a>
          </p>
          <div>
            <label htmlFor="op-enrol-code">Code from the app</label>
            <input id="op-enrol-code" value={p.code} onChange={(e) => p.onCode(e.target.value)} autoComplete="one-time-code" inputMode="numeric" className="input-code" required />
          </div>
          <button className="btn-brand" type="submit" disabled={p.busy}>
            Turn on
          </button>
        </form>
      )}
      {p.step.kind === 'codes' && (
        <section className="form-stack">
          <h2>Your recovery codes</h2>
          <p>Each code signs you in once if you lose your phone. Save them somewhere safe now: they are shown only this once.</p>
          <pre className="codes-box">{recoveryCodesText(p.step.codes)}</pre>
          <button className="btn-brand" type="button" onClick={p.onCodesSaved}>
            I have saved them
          </button>
        </section>
      )}
    </main>
  );
}

function OperatorSignIn() {
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>({ kind: 'password' });
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => setSessionScope('platform'), []);

  async function afterSignIn(body: SignInResponse) {
    if (body.status === 'signed_in' && body.access_token) {
      setAccessToken(body.access_token);
      if (body.recovery_codes && body.recovery_codes.length > 0) setStep({ kind: 'codes', codes: body.recovery_codes });
      else await navigate({ to: '/platform' });
      return;
    }
    const mfaToken = body.mfa_token ?? '';
    if (body.status === 'mfa_required') setStep({ kind: 'challenge', mfaToken });
    else if (body.status === 'mfa_enrolment_required') {
      const { data, error } = await api.POST('/api/v1/platform/auth/mfa/enrol', { body: { mfa_token: mfaToken } });
      if (error || !data) throw error;
      setStep({ kind: 'enrol', mfaToken, enrolment: data });
    }
  }

  async function run(action: () => Promise<void>) {
    if (busy) return;
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

  return (
    <OperatorSignInView
      step={step}
      login={login}
      password={password}
      code={code}
      busy={busy}
      message={message}
      onLogin={setLogin}
      onPassword={setPassword}
      onCode={setCode}
      onSubmitPassword={(e) => {
        e.preventDefault();
        void run(async () => {
          const { data, error } = await api.POST('/api/v1/platform/auth/login', { body: { login, password } });
          if (error || !data) throw error;
          setPassword('');
          await afterSignIn(data);
        });
      }}
      onSubmitChallenge={(e) => {
        e.preventDefault();
        if (step.kind !== 'challenge') return;
        const factor = normaliseSecondFactor(code);
        if (factor.kind === 'invalid') {
          setMessage('Enter the 6 digit code from your app, or one recovery code.');
          return;
        }
        void run(async () => {
          const { data, error } = await api.POST('/api/v1/platform/auth/mfa/verify', {
            body: { mfa_token: step.mfaToken, code: factor.code },
          });
          if (error || !data) throw error;
          setCode('');
          await afterSignIn(data);
        });
      }}
      onSubmitEnrolment={(e) => {
        e.preventDefault();
        if (step.kind !== 'enrol') return;
        void run(async () => {
          const { data, error } = await api.POST('/api/v1/platform/auth/mfa/confirm', {
            body: { mfa_token: step.mfaToken, code: code.replace(/\s+/g, '') },
          });
          if (error || !data) throw error;
          setCode('');
          await afterSignIn(data);
        });
      }}
      onCodesSaved={() => void navigate({ to: '/platform' })}
    />
  );
}

/**
 * The first password, with the one-time setup token printed by deploy/sql/create-platform-user.sql
 * (docs/runbooks/onboard-tenant.md): typed in, or carried in a link as #token=....
 */
export function OperatorSetupView(p: {
  token: string;
  password: string;
  busy: boolean;
  message: string | null;
  done: boolean;
  onToken: (v: string) => void;
  onPassword: (v: string) => void;
  onSubmit: (e: FormEvent) => void;
}) {
  return (
    <main className="card auth-card">
      <h1>Set your operator password</h1>
      {p.message && (
        <p role="alert" className="alert alert-danger">
          {p.message}
        </p>
      )}
      {p.done ? (
        <p role="status">
          Your password is set. <a href="/platform/sign-in">Sign in</a> and turn on two-step sign-in.
        </p>
      ) : (
        <form onSubmit={p.onSubmit} className="form-stack" aria-busy={p.busy}>
          <div>
            <label htmlFor="op-setup-token">Setup token</label>
            <input id="op-setup-token" value={p.token} onChange={(e) => p.onToken(e.target.value.trim())} autoComplete="off" required />
          </div>
          <div>
            <label htmlFor="op-new-password">New password (at least 12 characters)</label>
            <input
              id="op-new-password"
              type="password"
              value={p.password}
              onChange={(e) => p.onPassword(e.target.value)}
              autoComplete="new-password"
              minLength={12}
              required
            />
          </div>
          <button className="btn-brand" type="submit" disabled={p.busy}>
            {p.busy ? 'Saving' : 'Set password'}
          </button>
        </form>
      )}
    </main>
  );
}

function OperatorSetup() {
  const [token, setToken] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  useEffect(() => {
    const fromLink = /(?:^#|&)token=([A-Za-z0-9_-]{20,128})(?:&|$)/.exec(window.location.hash)?.[1];
    if (fromLink) {
      setToken(fromLink);
      window.history.replaceState(null, '', window.location.pathname);
    }
  }, []);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!token || busy) return;
    setBusy(true);
    setMessage(null);
    api
      .POST('/api/v1/platform/auth/setup', { body: { token, password } })
      .then(({ error }) => {
        if (error) setMessage(problemOf(error).detail ?? 'Could not set the password.');
        else {
          setPassword('');
          setDone(true);
        }
      })
      .catch(() => setMessage('Could not reach the server. Try again.'))
      .finally(() => setBusy(false));
  }

  return (
    <OperatorSetupView
      token={token}
      password={password}
      busy={busy}
      message={message}
      done={done}
      onToken={setToken}
      onPassword={setPassword}
      onSubmit={submit}
    />
  );
}

export const Route = createLazyRoute('/platform/sign-in')({ component: OperatorSignIn });
export const SetupRoute = createLazyRoute('/platform/setup')({ component: OperatorSetup });
