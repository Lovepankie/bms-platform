import { Link, createLazyRoute, useNavigate } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { api, type MfaEnrolment, problemOf, type SignInResponse } from '../../api/client';
import { invitationToken, plainProblem } from '../../auth/codes';
import { setAccessToken } from '../../auth/session';
import { illustrations } from '../../components/illustrations';
import { StatusPanel } from '../../components/states';
import { ErrorLine, PasswordFields, RecoveryCodes, StepHeader, TwoStepSetup, passwordReady } from './steps';

// The first run of an invited user (FR-IAM-01, issues #86 and #93). The one-time token arrives in
// the link's fragment, so it never reaches a server log. Three steps, one action each:
//   1. Choose a password. Accepting signs the user in (ADR-025).
//   2. Two-step sign-in: recommended, not required, with a clear Skip for now. When the user's role
//      still requires it (FR-IAM-06 until #93 lands), there is no Skip and the screen says why.
//   3. Recovery codes, only after turning two-step sign-in on.
// Then the staff area, where a new tenant admin lands on the set-up checklist and the first-run
// tour starts (issue #19).

export type Step =
  | { kind: 'password' }
  | { kind: 'offer' }
  | { kind: 'enrol'; enrolment: MfaEnrolment; mfaToken: string | null }
  | { kind: 'codes'; codes: string[] }
  | { kind: 'sign-in' };

const STEPS = 3;

/** `initial` lets a test open any step; the page always starts at the password. */
export function AcceptInvitation({ token, initial = { kind: 'password' } }: { token: string | null; initial?: Step }) {
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

  async function afterPassword(body: SignInResponse) {
    // The token has done its job; drop it from the address bar and the history.
    window.history.replaceState(null, '', window.location.pathname);
    if (body.status === 'signed_in' && body.access_token) {
      setAccessToken(body.access_token);
      setStep({ kind: 'offer' });
    } else if (body.status === 'mfa_enrolment_required' && body.mfa_token) {
      const mfaToken = body.mfa_token;
      const { data, error } = await api.POST('/api/v1/auth/staff/mfa/enrol', { body: { mfa_token: mfaToken } });
      if (error || !data) throw error;
      setStep({ kind: 'enrol', enrolment: data, mfaToken });
    } else {
      setStep({ kind: 'sign-in' });
    }
  }

  function submitPassword(e: FormEvent) {
    e.preventDefault();
    if (!token || !passwordReady(password, confirm)) {
      setMessage('Check the password rules below the boxes.');
      return;
    }
    void run(async () => {
      const { data, error } = await api.POST('/api/v1/auth/staff/invitations/accept', { body: { token, password } });
      if (error || !data) throw error;
      setPassword('');
      setConfirm('');
      await afterPassword(data);
    }, 'Your password could not be saved. Try again.');
  }

  function turnOn() {
    void run(async () => {
      const { data, error } = await api.POST('/api/v1/auth/staff/mfa/enrol', {});
      if (error || !data) throw error;
      setStep({ kind: 'enrol', enrolment: data, mfaToken: null });
    }, 'Two-step sign-in could not start. Try again.');
  }

  function confirmCode(e: FormEvent, mfaToken: string | null) {
    e.preventDefault();
    void run(async () => {
      const { data, error } = await api.POST('/api/v1/auth/staff/mfa/confirm', {
        body: mfaToken ? { mfa_token: mfaToken, code } : { code },
      });
      if (error || !data) throw error;
      setCode('');
      if (data.access_token) setAccessToken(data.access_token);
      setStep({ kind: 'codes', codes: data.recovery_codes ?? [] });
    }, 'That code did not work. Try again.');
  }

  if (!token) {
    return (
      <main className="card auth-card">
        <StepHeader title="Invitation" />
        <StatusPanel tone="error" title="This invitation link is not complete.">
          Open the link from your invitation message again, or ask your administrator for a new one.
        </StatusPanel>
      </main>
    );
  }

  return (
    <main className="card auth-card">
      {step.kind === 'password' && (
        <>
          <span className="auth-art">{illustrations.secure}</span>
          <StepHeader step={1} of={STEPS} title="Choose a password" lead="You will use it with your email or phone to sign in." />
          <ErrorLine message={message} />
          <form onSubmit={submitPassword} className="form-stack" noValidate>
            <PasswordFields password={password} confirm={confirm} onPassword={setPassword} onConfirm={setConfirm} />
            <button className="btn-primary btn-lg" disabled={busy}>
              {busy ? 'Saving' : 'Save and continue'}
            </button>
          </form>
        </>
      )}

      {step.kind === 'offer' && (
        <>
          <StepHeader step={2} of={STEPS} title="Protect your account" />
          <p>
            <span className="badge badge-info">Recommended, not required</span>
          </p>
          <p>
            Two-step sign-in asks for a code from your phone as well as your password. Then nobody can sign in as you,
            even if they learn your password. It takes about two minutes.
          </p>
          <ErrorLine message={message} />
          <div className="form-actions">
            <button type="button" className="btn-primary btn-lg" disabled={busy} onClick={turnOn}>
              {busy ? 'Starting' : 'Turn on two-step sign-in'}
            </button>
            <button type="button" className="btn-lg" disabled={busy} onClick={home}>
              Skip for now
            </button>
          </div>
        </>
      )}

      {step.kind === 'enrol' && (
        <>
          <StepHeader
            step={2}
            of={STEPS}
            title="Set up two-step sign-in"
            lead={step.mfaToken ? 'Your role needs two-step sign-in, so this step cannot be skipped.' : undefined}
          />
          <ErrorLine message={message} />
          <TwoStepSetup enrolment={step.enrolment} code={code} onCode={setCode} busy={busy} onSubmit={(e) => confirmCode(e, step.mfaToken)}>
            {!step.mfaToken && (
              <button type="button" className="btn-ghost" disabled={busy} onClick={home}>
                Skip for now
              </button>
            )}
          </TwoStepSetup>
        </>
      )}

      {step.kind === 'codes' && (
        <>
          <StepHeader step={3} of={STEPS} title="Save your recovery codes" />
          <RecoveryCodes codes={step.codes} onDone={home} />
        </>
      )}

      {step.kind === 'sign-in' && (
        <>
          <StepHeader title="Welcome" />
          <StatusPanel tone="success" title="Your password is set.">
            Sign in with your email or phone and your new password.
          </StatusPanel>
          <Link to="/sign-in" className="btn btn-primary btn-lg btn-block">
            Sign in
          </Link>
        </>
      )}
    </main>
  );
}

function AcceptInvitationPage() {
  const [token] = useState(() => invitationToken(window.location.hash));
  return <AcceptInvitation token={token} />;
}

export const Route = createLazyRoute('/accept-invitation')({ component: AcceptInvitationPage });
