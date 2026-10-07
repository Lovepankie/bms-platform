import { createLazyRoute, useNavigate } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { api, type MfaEnrolment, problemOf, type SignInResponse } from '../../api/client';
import { normaliseSecondFactor, plainProblem } from '../../auth/codes';
import { setAccessToken } from '../../auth/session';
import { illustrations } from '../../components/illustrations';
import { ErrorLine, RecoveryCodes, StepHeader, TwoStepSetup } from './steps';

// Staff sign-in (FR-IAM-04 to FR-IAM-06, FR-IAM-11): password, then the second factor when the
// account has one, or TOTP enrolment when the role requires it. Recovery codes are shown once,
// right after enrolment, and never again. The screens share their pieces with the first run of
// accept-invitation.tsx (issue #86).

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
  const [shown, setShown] = useState(false);

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
    if (busy) return;
    setBusy(true);
    setMessage(null);
    try {
      await action();
    } catch (error) {
      setMessage(plainProblem(problemOf(error), 'Something went wrong. Try again.'));
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
      {step.kind === 'password' && (
        <>
          <span className="auth-art">{illustrations.secure}</span>
          <StepHeader title="Staff sign-in" lead="Use the email or phone and the password you set from your invitation." />
          <ErrorLine message={message} />
          <form onSubmit={submitPassword} className="form-stack">
            <div>
              <label htmlFor="login">Email or phone</label>
              <input id="login" value={login} onChange={(e) => setLogin(e.target.value)} autoComplete="username" required />
            </div>
            <div>
              <label htmlFor="password">Password</label>
              <div className="password-row">
                <input
                  id="password"
                  type={shown ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete="current-password"
                  required
                />
                <button type="button" className="btn-sm" aria-pressed={shown} onClick={() => setShown(!shown)}>
                  {shown ? 'Hide' : 'Show'}
                </button>
              </div>
            </div>
            <button className="btn-primary btn-lg" disabled={busy}>{busy ? 'Signing in' : 'Sign in'}</button>
          </form>
        </>
      )}

      {step.kind === 'challenge' && (
        <>
          <StepHeader title="Enter your code" lead="Open your authenticator app and type the 6 digit code it shows." />
          <ErrorLine message={message} />
          <form onSubmit={(e) => submitChallenge(e, step.mfaToken)} className="form-stack">
            <div>
              <label htmlFor="challenge-code">Code</label>
              <input id="challenge-code" className="input-code" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" required />
              <span className="field-hint">Lost your phone? Type one of your recovery codes instead.</span>
            </div>
            <button className="btn-primary btn-lg" disabled={busy}>{busy ? 'Checking' : 'Continue'}</button>
          </form>
        </>
      )}

      {step.kind === 'enrol' && (
        <>
          <StepHeader step={1} of={2} title="Set up two-step sign-in" lead="Your role needs a code from your phone as well as your password." />
          <ErrorLine message={message} />
          <TwoStepSetup enrolment={step.enrolment} code={code} onCode={setCode} busy={busy} onSubmit={(e) => submitEnrolment(e, step.mfaToken)} />
        </>
      )}

      {step.kind === 'codes' && (
        <>
          <StepHeader step={2} of={2} title="Save your recovery codes" />
          <RecoveryCodes codes={step.codes} onDone={() => void navigate({ to: '/staff' })} />
        </>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/sign-in')({ component: SignIn });
