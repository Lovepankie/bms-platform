import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, Link } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { type Closure, cancelClosure, closureView, requestClosure } from '../../../api/closure';
import { ActionBar } from '../../../app/ui/ActionBar';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { RowChevron } from '../../../app/ui/ListRow';
import { Skeleton } from '../../../app/ui/Skeleton';
import { useStaff } from '../context';
import { dayWords } from '../plan';
import { resetProofProblem } from '../users/state';

// Business set-up > Close the business (issue #571, ADR-090, FR-TEN-09). Only the Owner asks, in two steps: what closing means, then
// a review of exactly what happens with the Owner's own proof (the authenticator code with two-step sign-in, else the password).
// From the request the business is read only, a full copy of the data is made for the Owner, and Rincol Tech may remove the
// business after the cooling-off period; until then the Owner can cancel and everything works again. Administrators see the
// state; nobody else reaches the page.

export const CLOSURE_QUERY = ['closure'] as const;

/** What happens when the Owner closes the business, in the order it happens; the review step lists exactly these. */
export function whatHappens(coolingOffDays: number): string[] {
  return [
    'The business becomes read only at once. Everyone can still sign in, look at everything and download the data, but nobody can add or change anything.',
    'A full copy of your data is made for you. Save it from Download my data in Business set-up.',
    `After ${coolingOffDays} days Rincol Tech may remove the business. Until then you can cancel the closing and everything works again.`,
    'Removal deletes the names, phone numbers, ID numbers and other personal details of your staff, customers and members, and every stored file.',
    'Money records (sales, payments, invoices, expenses, the journal) are kept read only, without names, for five years after the end of that financial year, then deleted.',
  ];
}

/** The line that says where a closing stands, for the Owner and the administrators. */
export function requestedSentence(c: Closure): string {
  return `${c.requested_by_name ?? 'The owner'} asked to close the business. It is read only now. Rincol Tech may remove it from ${dayWords(c.remove_after)}.`;
}

export function CloseScreen() {
  const { me } = useStaff();
  const mfaEnabled = me.mfa_enabled === true;
  const queryClient = useQueryClient();
  const closure = useQuery({ queryKey: CLOSURE_QUERY, queryFn: closureView });
  const [step, setStep] = useState<'about' | 'review'>('about');
  const [typed, setTyped] = useState('');
  const [tried, setTried] = useState(false);

  async function refresh(saved: Closure) {
    queryClient.setQueryData(CLOSURE_QUERY, saved);
    await queryClient.invalidateQueries({ queryKey: ['plan-status'] });
  }

  // toast-exempt: success screen, the Closing asked for screen replaces the form
  const ask = useMutation({
    mutationFn: () => requestClosure(mfaEnabled ? { totp_code: typed.trim() } : { password: typed }),
    onSuccess: async (saved) => {
      setTyped('');
      setStep('about');
      await refresh(saved);
    },
    onError: () => setTyped(''),
  });
  const cancel = useMutation({ mutationFn: cancelClosure, onSuccess: refresh, meta: { success: 'Closing cancelled' } });

  if (closure.isError) {
    return (
      <main>
        <h1>Close the business</h1>
        <p role="alert">Could not load the closing of the business.</p>
      </main>
    );
  }
  const c = closure.data;
  if (!c) {
    return (
      <main>
        <h1>Close the business</h1>
        <Skeleton loading rows={4} />
      </main>
    );
  }
  const days = c.cooling_off_days ?? 30;
  const proofProblem = resetProofProblem(mfaEnabled, typed);

  function onConfirm(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (proofProblem === null) ask.mutate();
  }

  if (c.status === 'requested') {
    return (
      <main className="stack">
        <h1>Close the business</h1>
        <section className="rt-card stack" aria-labelledby="close-state" data-tour="close-state">
          <h2 id="close-state">Closing asked for</h2>
          <p>{requestedSentence(c)}</p>
          <Link className="link-row" to={'/staff/setup' as never}>
            Save your copy of the data: Download my data in Business set-up
            <RowChevron />
          </Link>
          {cancel.isError && (
            <p role="alert" className="alert alert-danger">
              {cancel.error instanceof Error ? cancel.error.message : 'Could not cancel the closing. Try again.'}
            </p>
          )}
          {!c.can_change && <p className="hint">Only the owner of the business can cancel the closing.</p>}
        </section>
        {c.can_change && (
          <ActionBar>
            <BusyButton type="button" className="btn-primary btn-block" busy={cancel.isPending} onClick={() => cancel.mutate()} data-tour="close-cancel">
              Cancel the closing
            </BusyButton>
          </ActionBar>
        )}
      </main>
    );
  }

  if (!c.can_change) {
    return (
      <main className="stack">
        <h1>Close the business</h1>
        <p data-tour="close-about">Only the owner of the business can close it. Nothing is closed now.</p>
      </main>
    );
  }

  if (step === 'about') {
    return (
      <main className="stack">
        <h1>Close the business</h1>
        <section className="rt-card stack" aria-labelledby="close-about" data-tour="close-about">
          <h2 id="close-about">Stop using BMS for good</h2>
          <p>
            This is for a business that is stopping for good and wants its data removed, as the Data Protection and Privacy Act allows.
            To stop paying but keep everything, cancel the subscription in Business set-up instead.
          </p>
        </section>
        <ActionBar>
          <button type="button" /* busy-exempt: shows the review, sends nothing */ className="btn-primary btn-block" onClick={() => setStep('review')} data-tour="close-continue">
            See what happens
          </button>
        </ActionBar>
      </main>
    );
  }

  return (
    <main className="stack">
      <h1>Close the business</h1>
      <form className="stack" onSubmit={onConfirm} autoComplete="off" aria-labelledby="close-review">
        <section className="rt-card stack" data-tour="close-review">
          <h2 id="close-review">Check before you close</h2>
          <ol className="stack">
            {whatHappens(days).map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ol>
          <Hint full>Sign-in itself does not change: you sign in as before until the business is removed.</Hint>
        </section>
        <section className="rt-card stack" data-tour="close-proof">
          <h2>Prove it is you</h2>
          {mfaEnabled ? (
            <Field
              label="Code from your authenticator app"
              required
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              spellCheck={false}
              error={proofProblem}
              showError={tried}
            />
          ) : (
            <Field
              label="Your own password"
              required
              type="password"
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              autoComplete="current-password"
              spellCheck={false}
              error={proofProblem}
              showError={tried}
            />
          )}
          {ask.isError && (
            <p role="alert" className="alert alert-danger">
              {ask.error instanceof Error ? ask.error.message : 'Could not ask to close the business. Nothing was changed.'}
            </p>
          )}
          <button type="button" /* busy-exempt: goes back, sends nothing */ className="btn-ghost" disabled={ask.isPending} onClick={() => setStep('about')}>
            Back
          </button>
        </section>
        <ActionBar>
          <BusyButton type="submit" className="btn-danger btn-block" busy={ask.isPending} notReady={proofProblem !== null}>
            Close the business
          </BusyButton>
        </ActionBar>
      </form>
    </main>
  );
}

export const Route = createLazyRoute('/staff/setup/close')({ component: CloseScreen });
