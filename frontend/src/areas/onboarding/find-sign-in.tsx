import { Link, createLazyRoute } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { findSignInPage } from '../../api/find-sign-in';
import { BusyButton } from '../../app/ui/BusyButton';
import { Breadcrumbs } from '../../components/breadcrumbs';
import { StatusPanel } from '../../components/states';
import { TourProvider, useTour } from '../../tour/host';
import { ErrorLine, StepHeader } from '../auth/steps';

// "Staff of a business? Find your sign-in page" (issue #383), on the platform home. Staff sign in on their own business's address, so a
// person who lands here with only the platform address types the work email they were invited with and we email them the sign-in page
// of each business where it is an active staff address. The answer is the same words for every address: this page cannot be used to find
// out who works where. The spotlight tour works without a signed-in user, and nothing about it is saved.

/** ASCII only, as the API: an address that needs SMTPUTF8 could never be sent to. */
const EMAIL = /^[\x21-\x3F\x41-\x7E]+@[\x21-\x3F\x41-\x7E]+\.[\x21-\x3F\x41-\x7E]+$/;

export const SENT = 'If this email is used by staff of a business, we have sent the sign-in page of that business to it.';

function TourButton() {
  const { pageTour, start } = useTour();
  if (!pageTour) return null;
  return (
    <p>
      <button type="button" className="btn-ghost btn-sm" onClick={() => start(pageTour)}>
        Show me this page
      </button>
    </p>
  );
}

/** `initial` lets a test open the answer; the page always starts at the form. */
export function FindSignIn({ initial = 'form' }: { initial?: 'form' | 'sent' }) {
  const [sent, setSent] = useState<string | null>(initial === 'sent' ? SENT : null);
  const [email, setEmail] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    const typed = email.trim();
    if (!EMAIL.test(typed) || typed.length > 254) {
      setMessage('Enter your work email in plain letters, for example name@example.com.');
      return;
    }
    setBusy(true);
    setMessage(null);
    findSignInPage(typed)
      .then((words) => setSent(words))
      .catch((error: unknown) => setMessage(error instanceof Error ? error.message : 'The request could not be sent. Try again.'))
      .finally(() => setBusy(false));
  }

  return (
    <TourProvider me={{}} saveProgress={false}>
      <main className="card auth-card">
        <Breadcrumbs items={[{ label: 'Home', to: '/' }, { label: 'Find your sign-in page' }]} />
        {sent ? (
          <>
            <StepHeader title="Check your email" />
            <StatusPanel tone="success" title="Request received.">
              {sent}
            </StatusPanel>
            <p>It can take a few minutes. Look in your spam folder too. Open the link in the email and sign in on your business page.</p>
            <Link to="/" className="btn btn-primary btn-lg btn-block">
              Back to the home page
            </Link>
          </>
        ) : (
          <>
            <StepHeader
              title="Find your sign-in page"
              lead="Staff sign in on their own business page, not here. Type the email your business has for you. We email you the link to your business page."
            />
            <ErrorLine message={message} />
            <form onSubmit={submit} className="form-stack" noValidate>
              <div data-tour="find-email">
                <label htmlFor="find-email">Your work email</label>
                <input
                  id="find-email"
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  autoComplete="email"
                  maxLength={254}
                  required
                />
              </div>
              <BusyButton className="btn-primary btn-lg" busy={busy} data-tour="find-send">
                Email me the link
              </BusyButton>
            </form>
            <p data-tour="find-apply">
              Own a business and want to start? <Link to="/sign-up">Apply to start</Link>
            </p>
            <TourButton />
          </>
        )}
      </main>
    </TourProvider>
  );
}

export const Route = createLazyRoute('/find-sign-in')({ component: FindSignIn });
