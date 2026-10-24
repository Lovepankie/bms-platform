import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useEffect, useRef, useState } from 'react';
import { users } from '../../../api/users';
import { type Choice, type Policy, twoStep } from '../../../api/two-step';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useStaff } from '../context';
import { DEFAULT_GRACE, canManageTwoStep, choiceOf, deadlineWords, differs, graceChoices, needsProof, policyOf, ready, reviewLines } from './state';
import { NoAccess } from '../../../app/ui/EmptyState';

// Two-step sign-in (issue #348; ADR-046): who must use a code from their phone as well as their password. It is optional until an
// administrator chooses otherwise. Three choices (nobody, chosen roles, everyone), a grace period, and a plain-words review that is read
// and confirmed before anything is saved. The tenant admin is covered too when the choice includes them, and the review says so.

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Try again.');

const CHOICES: { policy: Policy; title: string; words: string }[] = [
  { policy: 'off', title: 'Optional', words: 'Nobody has to. Each person can still turn it on for themselves.' },
  { policy: 'roles', title: 'Required for the roles I choose', words: 'For example the cashiers, or the administrators.' },
  { policy: 'everyone', title: 'Required for everyone', words: 'Everyone who signs in to the staff area.' },
];

export function TwoStepPolicyScreen() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const allowed = canManageTwoStep(me);
  const settings = useQuery({ queryKey: ['settings'], queryFn: twoStep.settings, enabled: allowed });
  const roles = useQuery({ queryKey: ['staff-roles'], queryFn: users.roles, enabled: allowed });
  const [choice, setChoice] = useState<Choice | null>(null);
  const [reviewing, setReviewing] = useState(false);
  const [busy, setBusy] = useState(false);
  // The proof of an administrator who relaxes the rule: a code from their authenticator, or their own password when they have none.
  const [proof, setProof] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // One save at a time: a second tap before the first answer must not send a second request.
  const working = useRef(false);

  // The form starts from the saved settings once, then belongs to the administrator: a refresh after saving must not wipe a new edit.
  const saved = settings.data;
  useEffect(() => {
    if (saved && choice === null) setChoice(choiceOf(saved));
  }, [saved, choice]);

  if (!allowed) {
    return (
      <main className="rt ln" data-tour="twostep-page">
        <h1>Two-step sign-in</h1>
        <NoAccess />
      </main>
    );
  }

  const staffRoles = (roles.data ?? []).filter((r) => r.key);
  const names: Record<string, string> = Object.fromEntries(staffRoles.map((r) => [r.key as string, r.name ?? (r.key as string)]));
  const current = saved ? choiceOf(saved) : null;
  const savedState = saved && current ? { choice: current, enforceFrom: saved.mfa_enforce_from, timezone: saved.timezone } : null;
  const changed = choice !== null && current !== null && differs(choice, current);

  function pick(policy: Policy) {
    if (!choice) return;
    setReviewing(false);
    setNotice(null);
    setProof('');
    setChoice({ ...choice, policy });
  }

  function toggle(key: string) {
    if (!choice) return;
    setReviewing(false);
    const has = choice.roles.includes(key);
    setChoice({ ...choice, roles: has ? choice.roles.filter((k) => k !== key) : [...choice.roles, key] });
  }

  async function save() {
    if (!choice || !saved || !current || working.current) return;
    working.current = true;
    setBusy(true);
    setError(null);
    try {
      const asked = savedState && needsProof(savedState, choice, new Date()) ? (me.mfa_enabled ? { totp_code: proof.trim() } : { password: proof }) : {};
      const after = await twoStep.save(saved.version ?? 1, choice, current, asked);
      setProof('');
      const now = policyOf(after);
      const date = deadlineWords(after.mfa_enforce_from, after.timezone);
      setNotice(
        now === 'off'
          ? 'Saved. Two-step sign-in is optional again.'
          : date
            ? `Saved. People who have not set it up have until the end of ${date}.`
            : 'Saved. People who have not set it up are asked at their next sign-in.',
      );
      setReviewing(false);
      setChoice(choiceOf(after));
      await queryClient.invalidateQueries({ queryKey: ['settings'] });
      // The recommendation and the reminders follow the policy.
      await queryClient.invalidateQueries({ queryKey: ['me'] });
    } catch (e) {
      setError(messageOf(e));
    } finally {
      working.current = false;
      setBusy(false);
    }
  }

  return (
    <main className="rt ln stack" data-tour="twostep-page">
      <h1>Two-step sign-in</h1>
      <p data-tour="twostep-intro">
        Two-step sign-in asks for a code from a person&apos;s phone as well as their password. Then nobody can sign in as them, even if they learn
        the password. It takes each person about two minutes to set up.
      </p>
      {notice && (
        <p role="status" className="alert alert-success">
          {notice}
        </p>
      )}
      {settings.isPending && <p className="loading">Loading your choice</p>}
      {settings.isError && (
        <p role="alert" className="alert alert-danger">
          {messageOf(settings.error)}
        </p>
      )}
      <fieldset className="stack" data-tour="twostep-choice">
        <legend>Who must use it?</legend>
        {CHOICES.map((c) => (
          <label key={c.policy} className="rt-card">
            <input
              type="radio"
              name="two-step-policy"
              value={c.policy}
              checked={choice?.policy === c.policy}
              disabled={choice === null || busy}
              onChange={() => pick(c.policy)}
            />{' '}
            <strong>{c.title}</strong>
            <span className="hint"> {c.words}</span>
          </label>
        ))}
        {choice?.policy === 'roles' && (
          <div className="stack" role="group" aria-label="Roles that must use it">
            {roles.isPending && <p className="loading">Loading the roles</p>}
            {roles.isError && (
              <p role="alert" className="alert alert-danger">
                Could not load the roles.
              </p>
            )}
            {staffRoles.map((r) => (
              <label key={r.key}>
                <input type="checkbox" checked={choice.roles.includes(r.key as string)} disabled={busy} onChange={() => toggle(r.key as string)} /> {r.name ?? r.key}
              </label>
            ))}
            {!ready(choice) && <p className="hint">Tick at least one role.</p>}
          </div>
        )}
      </fieldset>
      {choice && choice.policy !== 'off' && (
        <div data-tour="twostep-grace">
          <label>
            How long do people have to set it up?
            <select
              value={choice.graceDays}
              disabled={busy}
              onChange={(e) => {
                setReviewing(false);
                setChoice({ ...choice, graceDays: Number(e.target.value) });
              }}
            >
              {graceChoices(choice.graceDays).map((g) => (
                <option key={g.days} value={g.days}>
                  {g.label}
                </option>
              ))}
            </select>
          </label>
          <p className="hint">
            Until then they can keep signing in and see a reminder. {DEFAULT_GRACE} days is a good time for most businesses.
          </p>
        </div>
      )}
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
      {choice && !reviewing && (
        <p className="cluster">
          <button type="button" /* busy-exempt: opens the review */ className="rt-primary" disabled={busy || !changed || !ready(choice)} onClick={() => setReviewing(true)}>
            Review and save
          </button>
          {!changed && <span className="hint">Nothing has changed yet.</span>}
        </p>
      )}
      {choice && reviewing && (
        <div className="alert alert-warning stack" role="group" aria-label="Check before you save">
          <p>
            <strong>Please check this before you save.</strong>
          </p>
          <ul className="stack">
            {reviewLines(choice, names, me, new Date(), savedState ?? undefined).map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
          {savedState && needsProof(savedState, choice, new Date()) && (
            <label>
              {me.mfa_enabled ? 'The current code from your authenticator app' : 'Your own password'}
              <input
                type={me.mfa_enabled ? 'text' : 'password'}
                inputMode={me.mfa_enabled ? 'numeric' : undefined}
                autoComplete={me.mfa_enabled ? 'one-time-code' : 'current-password'}
                value={proof}
                disabled={busy}
                onChange={(e) => setProof(e.target.value)}
              />
            </label>
          )}
          <p className="cluster">
            <BusyButton type="button" className="rt-primary" busy={busy} notReady={savedState !== null && needsProof(savedState, choice, new Date()) && proof.trim() === ''} onClick={() => void save()}>
              {choice.policy === 'off' ? 'Yes, make it optional' : 'Yes, save this'}
            </BusyButton>
            <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={busy} onClick={() => setReviewing(false)}>
              Go back
            </button>
          </p>
        </div>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff/setup/two-step')({ component: TwoStepPolicyScreen });
