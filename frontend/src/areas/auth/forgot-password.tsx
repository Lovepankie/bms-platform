import { Link, createLazyRoute } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { requestPasswordReset } from '../../api/sign-in-reset';
import { illustrations } from '../../components/illustrations';
import { StatusPanel } from '../../components/states';
import { ErrorLine, StepHeader } from './steps';

// "Forgot password?" (FR-IAM-15, ADR-042): one box for the email or phone, one button. The answer is the same words whether or not an
// account matched, so this page cannot be used to find out who has an account. The link, when there is one, goes to the email the
// business holds for the account and opens /reset-password.

/** `initial` lets a test open the answer; the page always starts at the form. */
export function ForgotPassword({ initial = 'form' }: { initial?: 'form' | 'sent' }) {
  const [sent, setSent] = useState<string | null>(initial === 'sent' ? 'If an account matches, a reset link has been sent.' : null);
  const [login, setLogin] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setMessage(null);
    requestPasswordReset(login.trim())
      .then((words) => setSent(words))
      .catch((error: unknown) => setMessage(error instanceof Error ? error.message : 'The request could not be sent. Try again.'))
      .finally(() => setBusy(false));
  }

  if (sent) {
    return (
      <main className="card auth-card">
        <StepHeader title="Check your email" />
        <StatusPanel tone="success" title="Request received.">
          {sent}
        </StatusPanel>
        <p>
          The link works once and for one hour. If nothing arrives, look in your spam folder, or ask your administrator, who can give you
          a link.
        </p>
        <Link to="/sign-in" className="btn btn-primary btn-lg btn-block">
          Back to sign-in
        </Link>
      </main>
    );
  }

  return (
    <main className="card auth-card">
      <span className="auth-art">{illustrations.secure}</span>
      <StepHeader title="Forgot your password?" lead="Type the email or phone you sign in with. If an account matches, we email a link to choose a new password." />
      <ErrorLine message={message} />
      <form onSubmit={submit} className="form-stack">
        <div>
          <label htmlFor="forgot-login">Email or phone</label>
          <input id="forgot-login" value={login} onChange={(e) => setLogin(e.target.value)} autoComplete="username" maxLength={320} required />
        </div>
        <button className="btn-primary btn-lg" disabled={busy}>
          {busy ? 'Sending' : 'Send me a link'}
        </button>
      </form>
      <p>
        <Link to="/sign-in">Back to sign-in</Link>
      </p>
    </main>
  );
}

export const Route = createLazyRoute('/forgot-password')({ component: ForgotPassword });
