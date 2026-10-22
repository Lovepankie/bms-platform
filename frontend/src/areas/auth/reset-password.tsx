import { Link, createLazyRoute, useNavigate } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { api, type MfaEnrolment, problemOf } from '../../api/client';
import { BusyButton } from '../../app/ui/BusyButton';
import { invitationToken, normaliseSecondFactor, plainProblem } from '../../auth/codes';
import { setAccessToken } from '../../auth/session';
import { illustrations } from '../../components/illustrations';
import { StatusPanel } from '../../components/states';
import { InstallPrompt } from '../../install/InstallPrompt';
import { ErrorLine, PasswordFields, RecoveryCodes, StepHeader, TwoStepSetup, passwordReady } from './steps';

// Choosing a new password with the link from "Forgot password?" or from an administrator (FR-IAM-15, ADR-042). The one-time token is
// in the link's fragment, so it never reaches a server log. The same password rules as the first run apply. Saving signs every other
// device out. Two-step sign-in is never skipped: a user who has it types the code next, and a role that requires it sets it up.

export type Step =
  | { kind: 'password' }
  | { kind: 'challenge'; mfaToken: string }
  | { kind: 'enrol'; enrolment: MfaEnrolment; mfaToken: string }
  | { kind: 'codes'; codes: string[] }
  | { kind: 'sign-in' };

export type Next = { kind: 'home' } | Step;

// The token of the link, read once and taken out of the address bar and the browser history at the same moment, so it is not left
// in the address for a screenshot, a shared screen or a back button. It is kept in memory only (never in storage) until the password
// is saved. Reading it again after it was taken gives the same token, so a second render (React may run an initialiser twice) does
// not lose it.
let kept: string | null = null;

export function takeResetToken(): string | null {
  const found = invitationToken(window.location.hash);
  if (found) {
    kept = found;
    window.history.replaceState(null, '', window.location.pathname);
  }
  return kept;
}

export function forgetResetToken(): void {
  kept = null;
}

/** Saves the new password with the token and says where the user goes next; the token is forgotten once the server accepted it. */
export async function submitNewPassword(token: string, password: string): Promise<Next> {
  const { data, error } = await api.POST('/api/v1/auth/staff/password/reset', { body: { token, password } });
  if (error || !data) throw error;
  forgetResetToken();
  if (data.status === 'signed_in' && data.access_token) {
    setAccessToken(data.access_token);
    return { kind: 'home' };
  }
  if (data.status === 'mfa_required' && data.mfa_token) {
    return { kind: 'challenge', mfaToken: data.mfa_token };
  }
  if (data.status === 'mfa_enrolment_required' && data.mfa_token) {
    const mfaToken = data.mfa_token;
    const { data: enrolment, error: enrolError } = await api.POST('/api/v1/auth/staff/mfa/enrol', { body: { mfa_token: mfaToken } });
    if (enrolError || !enrolment) throw enrolError;
    return { kind: 'enrol', enrolment, mfaToken };
  }
  return { kind: 'sign-in' };
}

/** `initial` lets a test open any step; the page always starts at the password. */
export function ResetPassword({ token, initial = { kind: 'password' } }: { token: string | null; initial?: Step }) {
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>(initial);
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [code, setCode] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(action: () => Promise<void>, fallback: string) {
    if (busy) return;
    setBusy(true);
    setMessage(null);
    try {
      await action();
    } catch (error) {
      setMessage(plainProblem(problemOf(error), fallback));
    } finally {
      setBusy(false);
    }
  }

  const home = () => void navigate({ to: '/staff' });

  function submitPassword(e: FormEvent) {
    e.preventDefault();
    if (!token || !passwordReady(password, confirm)) {
      setMessage('Check the password rules below the boxes.');
      return;
    }
    void run(async () => {
      const next = await submitNewPassword(token, password);
      setPassword('');
      setConfirm('');
      if (next.kind === 'home') home();
      else setStep(next);
    }, 'Your password could not be saved. Try again.');
  }

  function submitChallenge(e: FormEvent, mfaToken: string) {
    e.preventDefault();
    const factor = normaliseSecondFactor(code);
    if (factor.kind === 'invalid') {
      setMessage('Enter the 6 digit code from your app, or one recovery code.');
      return;
    }
    void run(async () => {
      const { data, error } = await api.POST('/api/v1/auth/staff/mfa/verify', { body: { mfa_token: mfaToken, code: factor.code } });
      if (error || !data) throw error;
      setCode('');
      if (data.access_token) setAccessToken(data.access_token);
      home();
    }, 'That code did not work. Try again.');
  }

  function confirmCode(e: FormEvent, mfaToken: string) {
    e.preventDefault();
    void run(async () => {
      const { data, error } = await api.POST('/api/v1/auth/staff/mfa/confirm', { body: { mfa_token: mfaToken, code } });
      if (error || !data) throw error;
      setCode('');
      if (data.access_token) setAccessToken(data.access_token);
      setStep({ kind: 'codes', codes: data.recovery_codes ?? [] });
    }, 'That code did not work. Try again.');
  }

  if (!token && step.kind === 'password') {
    return (
      <main className="card auth-card">
        <StepHeader title="Choose a new password" />
        <StatusPanel
          tone="error"
          title="This reset link is not complete."
          action={
            <Link to="/forgot-password" className="btn btn-primary">
              Ask for a new link
            </Link>
          }
        >
          Open the link from your email again, or ask for a new one. An administrator can also give you a link.
        </StatusPanel>
      </main>
    );
  }

  return (
    <main className="card auth-card">
      {step.kind === 'password' && (
        <>
          <span className="auth-art">{illustrations.secure}</span>
          <StepHeader title="Choose a new password" lead="You will use it with your email or phone to sign in. Every device you were signed in on will be signed out." />
          <ErrorLine message={message} />
          <form onSubmit={submitPassword} className="form-stack" noValidate>
            <PasswordFields password={password} confirm={confirm} onPassword={setPassword} onConfirm={setConfirm} />
            <BusyButton className="btn-primary btn-lg" busy={busy}>
              Save and sign in
            </BusyButton>
          </form>
          <InstallPrompt />
        </>
      )}

      {step.kind === 'challenge' && (
        <>
          <StepHeader title="Enter your code" lead="Your password is changed. Open your authenticator app and type the 6 digit code it shows." />
          <ErrorLine message={message} />
          <form onSubmit={(e) => submitChallenge(e, step.mfaToken)} className="form-stack">
            <div>
              <label htmlFor="reset-code">Code</label>
              <input id="reset-code" className="input-code" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" required />
              <span className="field-hint">Lost your phone? Type one of your recovery codes instead.</span>
            </div>
            <BusyButton className="btn-primary btn-lg" busy={busy}>
              Continue
            </BusyButton>
          </form>
        </>
      )}

      {step.kind === 'enrol' && (
        <>
          <StepHeader step={1} of={2} title="Set up two-step sign-in" lead="Your role needs a code from your phone as well as your password." />
          <ErrorLine message={message} />
          <TwoStepSetup enrolment={step.enrolment} code={code} onCode={setCode} busy={busy} onSubmit={(e) => confirmCode(e, step.mfaToken)} />
        </>
      )}

      {step.kind === 'codes' && (
        <>
          <StepHeader step={2} of={2} title="Save your recovery codes" />
          <RecoveryCodes codes={step.codes} onDone={home} />
        </>
      )}

      {step.kind === 'sign-in' && (
        <>
          <StepHeader title="Your password is changed" />
          <StatusPanel tone="success" title="Your password is changed.">
            Sign in with your email or phone and the new password. If your account is locked after too many tries, wait 15 minutes first.
          </StatusPanel>
          <Link to="/sign-in" className="btn btn-primary btn-lg btn-block">
            Sign in
          </Link>
          <InstallPrompt />
        </>
      )}
    </main>
  );
}

function ResetPasswordPage() {
  const [token] = useState(takeResetToken);
  return <ResetPassword token={token} />;
}

export const Route = createLazyRoute('/reset-password')({ component: ResetPasswordPage });
