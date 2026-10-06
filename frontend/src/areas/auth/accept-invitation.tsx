import { Link, createLazyRoute } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { api, problemOf } from '../../api/client';
import { invitationToken, passwordProblem } from '../../auth/codes';

// Invitation acceptance (FR-IAM-01): the one-time token arrives in the link's fragment, so it is
// never sent to a server log; the invitee chooses a password and then signs in.

function AcceptInvitation() {
  const [token] = useState(() => invitationToken(window.location.hash));
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const problem = passwordProblem(password, confirm);
    if (problem || !token) {
      setMessage(problem ?? 'This link is not complete.');
      return;
    }
    setBusy(true);
    const { error } = await api.POST('/api/v1/auth/staff/invitations/accept', { body: { token, password } });
    setBusy(false);
    if (error) {
      setMessage(problemOf(error).detail ?? 'The invitation could not be accepted.');
      return;
    }
    // The token has done its job; drop it from the address bar and history.
    window.history.replaceState(null, '', window.location.pathname);
    setDone(true);
  }

  if (!token) {
    return (
      <main className="card auth-card">
        <h1>Invitation</h1>
        <p role="alert" className="alert alert-warning">This invitation link is not complete. Ask your administrator for a new one.</p>
      </main>
    );
  }
  if (done) {
    return (
      <main className="card auth-card">
        <h1>Welcome</h1>
        <p className="alert alert-success">Your password is set. You can sign in now.</p>
        <Link to="/sign-in" className="btn btn-primary btn-block">
          Sign in
        </Link>
      </main>
    );
  }
  return (
    <main className="card auth-card">
      <h1>Accept your invitation</h1>
      <p className="lead">Choose a password for your staff account. You will use it with your email or phone to sign in.</p>
      {message && <p role="alert" className="alert alert-danger">{message}</p>}
      <form onSubmit={(e) => void submit(e)} className="form-stack">
        <label>
          New password (at least 10 characters)
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
            required
          />
        </label>
        <label>
          Repeat the password
          <input
            type="password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            autoComplete="new-password"
            required
          />
        </label>
        <button className="btn-primary" disabled={busy}>{busy ? 'Saving' : 'Set password'}</button>
      </form>
    </main>
  );
}

export const Route = createLazyRoute('/accept-invitation')({ component: AcceptInvitation });
