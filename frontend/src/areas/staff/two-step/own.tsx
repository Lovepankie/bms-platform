import { useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import type { MfaEnrolment } from '../../../api/client';
import { twoStep } from '../../../api/two-step';
import { ErrorLine, RecoveryCodes, TwoStepSetup } from '../../auth/steps';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useStaff } from '../context';
import { deadlineWords } from './state';

// Turn on your own two-step sign-in from inside the app (issue #348; ADR-046 decision 7). Until now only the first run of an invited
// user and a forced enrolment could do it. Same three moves as the first run: start (a QR code and a key), type the first code, save the
// ten recovery codes once. The person who is covered by the business's policy is told the date; anyone else is just offered it.

type Step = { kind: 'start' } | { kind: 'code'; enrolment: MfaEnrolment } | { kind: 'codes'; codes: string[] };

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Try again.');

export function OwnTwoStep({ blocking = false }: { blocking?: boolean }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const [step, setStep] = useState<Step>({ kind: 'start' });
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const due = me.mfa_enabled ? null : deadlineWords(me.mfa_due_by, me.tenant_timezone);

  async function run(action: () => Promise<void>) {
    if (busy) return;
    // toast-exempt: success screen, each step opens the next (the code, then the recovery codes that say it is on)
    setBusy(true);
    setMessage(null);
    try {
      await action();
    } catch (e) {
      setMessage(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  const start = () =>
    run(async () => {
      const enrolment = await twoStep.start(password);
      setPassword('');
      setStep({ kind: 'code', enrolment });
    });

  function confirm(e: FormEvent) {
    e.preventDefault();
    void run(async () => {
      const codes = await twoStep.confirm(code);
      setCode('');
      setStep({ kind: 'codes', codes });
    });
  }

  return (
    <main className="rt ln stack" data-tour="twostep-own">
      <h1>Your two-step sign-in</h1>
      {step.kind === 'start' && me.mfa_enabled && (
        <p role="status" className="alert alert-success">
          Two-step sign-in is on for your account. You give a code from your phone each time you sign in.
        </p>
      )}
      {step.kind === 'start' && !me.mfa_enabled && (
        <>
          {blocking && (
            <p role="alert" className="alert alert-warning" data-tour="twostep-blocking">
              Nothing else in the app opens until two-step sign-in is on for you.
            </p>
          )}
          {!blocking && due && (
            <p role="status" className="alert alert-warning">
              Your business asks you to set it up by {due} (you can sign in until the end of that day). After that you cannot sign in without it.
            </p>
          )}
          <p>
            Two-step sign-in asks for a code from your phone as well as your password. Then nobody can sign in as you, even if they learn your
            password. It takes about two minutes.
          </p>
          <ErrorLine message={message} />
          <label data-tour="twostep-password">
            Your password, to confirm it is you
            <input type="password" autoComplete="current-password" value={password} disabled={busy} onChange={(e) => setPassword(e.target.value)} />
          </label>
          <p className="cluster" data-tour="twostep-turn-on">
            <BusyButton type="button" className="btn-primary btn-lg" busy={busy} notReady={password === ''} onClick={() => void start()}>
              Turn on two-step sign-in
            </BusyButton>
            {!blocking && (
              <Link to="/staff" className="btn-ghost">
                Not now
              </Link>
            )}
          </p>
        </>
      )}
      {step.kind === 'code' && (
        <>
          <ErrorLine message={message} />
          <TwoStepSetup enrolment={step.enrolment} code={code} onCode={setCode} busy={busy} onSubmit={confirm} />
        </>
      )}
      {step.kind === 'codes' && (
        <>
          <p role="status" className="alert alert-success">
            Two-step sign-in is on.
          </p>
          <RecoveryCodes
            codes={step.codes}
            doneLabel="Done"
            onDone={() => {
              setStep({ kind: 'start' });
              // Only now does the app learn it is on: a blocked person must not lose this page before the codes are saved.
              void queryClient.invalidateQueries({ queryKey: ['me'] });
            }}
          />
        </>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff/two-step')({ component: OwnTwoStep });
