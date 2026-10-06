import { createLazyRoute, Link } from '@tanstack/react-router';
import { type FormEvent, useEffect, useState } from 'react';
import { applicantReply, applicantStatus, type ApplicantView, OnboardingError, statusLabel, verifyApplicant } from '../../api/onboarding';
import { loadApplicantToken, MODULES, readLinkToken, saveApplicantToken } from './forms';

// The applicant page (FR-ONB-02, FR-ONB-04, spec section 10), opened from the emailed link
// (/sign-up/verify#token=...). Opening it only shows the application: the email is confirmed by
// pressing "Confirm my email", so a mail scanner that opens links confirms nothing (review N4).
// The token is read from the fragment, removed from the address bar and kept for this tab only,
// so it is never sent in a URL.

const NEXT_STEP: Record<string, string> = {
  submitted: 'We are looking at your application. We will contact you by phone or email.',
  needs_info: 'We need a little more information. Answer below and we will look again.',
  verified: 'Your application is verified. We are setting up your account and will email you a link to sign in.',
  activated: 'Your account is ready. Check your email for the link to set your password and sign in.',
  rejected: 'We cannot accept this application at this time.',
  expired: 'This application has expired. You are welcome to apply again.',
};

export interface ApplicantPageProps {
  view: ApplicantView | null;
  loading: boolean;
  error: string | null;
  reply: string;
  replying: boolean;
  confirming: boolean;
  onReplyChange: (value: string) => void;
  onReply: (e: FormEvent) => void;
  onConfirm: () => void;
}

export function ApplicantPage({
  view,
  loading,
  error,
  reply,
  replying,
  confirming,
  onReplyChange,
  onReply,
  onConfirm,
}: ApplicantPageProps) {
  if (loading) {
    return (
      <main className="card auth-card">
        <h1>Your application</h1>
        <p role="status">Opening your application</p>
      </main>
    );
  }
  if (!view) {
    return (
      <main className="card auth-card">
        <h1>Your application</h1>
        <p role="alert" className="alert alert-danger">
          {error ?? 'This link is not valid or has expired.'}
        </p>
        <p>
          <Link to="/sign-up">Apply again with the same email</Link> to get a new link.
        </p>
      </main>
    );
  }
  const modules = (view.modules ?? []).map((key) => MODULES.find((m) => m.key === key)?.label ?? key);
  return (
    <main className="card auth-card">
      <h1>Your application</h1>
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
      <dl className="facts">
        <dt>Business</dt>
        <dd>{view.business_name}</dd>
        <dt>Reference</dt>
        <dd>{view.reference}</dd>
        <dt>Status</dt>
        <dd>
          <span className="badge-brand">{statusLabel(view.status)}</span>
        </dd>
        <dt>Wanted</dt>
        <dd>{modules.join(', ')}</dd>
      </dl>
      {view.email_verified === false && (view.status === 'submitted' || view.status === 'needs_info') ? (
        <section aria-labelledby="confirm-heading" className="form-stack">
          <h2 id="confirm-heading">Confirm your email</h2>
          <p>Press the button to confirm this is your email address. We then look at your application.</p>
          <button className="btn-brand" type="button" disabled={confirming} onClick={onConfirm}>
            {confirming ? 'Confirming' : 'Confirm my email'}
          </button>
        </section>
      ) : (
        <p role="status">{NEXT_STEP[view.status ?? ''] ?? ''}</p>
      )}
      {view.status === 'needs_info' && (
        <section aria-labelledby="question-heading">
          <h2 id="question-heading">Our question</h2>
          <p className="alert alert-info">{view.operator_note}</p>
          <form onSubmit={onReply} className="form-stack" aria-busy={replying}>
            <div>
              <label htmlFor="reply">Your answer</label>
              <textarea id="reply" value={reply} onChange={(e) => onReplyChange(e.target.value)} rows={4} maxLength={1000} required />
            </div>
            <button className="btn-brand" type="submit" disabled={replying || reply.trim().length === 0}>
              {replying ? 'Sending' : 'Send answer'}
            </button>
          </form>
        </section>
      )}
      {view.status === 'rejected' && view.reject_reason && <p className="alert alert-info">{view.reject_reason}</p>}
    </main>
  );
}

function Applicant() {
  const [view, setView] = useState<ApplicantView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reply, setReply] = useState('');
  const [replying, setReplying] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [token, setToken] = useState<string | null>(null);

  useEffect(() => {
    const fromLink = readLinkToken(window.location.hash);
    if (fromLink) {
      saveApplicantToken(fromLink);
      window.history.replaceState(null, '', window.location.pathname);
    }
    const current = fromLink ?? loadApplicantToken();
    setToken(current);
    if (!current) {
      setLoading(false);
      return;
    }
    applicantStatus(current)
      .then(setView)
      .catch((err: unknown) => setError(err instanceof OnboardingError ? err.message : 'We could not open your application.'))
      .finally(() => setLoading(false));
  }, []);

  function confirm() {
    if (!token || confirming) return;
    setConfirming(true);
    setError(null);
    verifyApplicant(token)
      .then(setView)
      .catch((err: unknown) => setError(err instanceof OnboardingError ? err.message : 'We could not confirm your email.'))
      .finally(() => setConfirming(false));
  }

  function sendReply(e: FormEvent) {
    e.preventDefault();
    if (!token || replying) return;
    setReplying(true);
    setError(null);
    applicantReply(token, reply)
      .then((next) => {
        setView(next);
        setReply('');
      })
      .catch((err: unknown) => setError(err instanceof OnboardingError ? err.message : 'We could not send your answer.'))
      .finally(() => setReplying(false));
  }

  return (
    <ApplicantPage
      view={view}
      loading={loading}
      error={error}
      reply={reply}
      replying={replying}
      confirming={confirming}
      onReplyChange={setReply}
      onReply={sendReply}
      onConfirm={confirm}
    />
  );
}

export const VerifyRoute = createLazyRoute('/sign-up/verify')({ component: Applicant });
export const StatusRoute = createLazyRoute('/application')({ component: Applicant });
