import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import type { Me } from '../../../api/client';
import { type StaffUser, UsersError, users } from '../../../api/users';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field, FieldsNeededNote } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { Sheet } from '../../../app/ui/Sheet';
import { sayDone } from '../retail/toast';
import { showPhone } from '../lending/member-state';
import { canManageUsers, contactChange, contactDraftOf, resetProofProblem } from './state';

// Change a person's name, email or phone (issue #883; ADR-062). The same form serves My details, from the person menu, and Change
// email or phone on the Users page. The server decides who may change whom: an administrator's contact is the Owner's alone, and
// the Owner's own email or phone needs the Owner's password or authenticator code first. This form asks for that proof on a second
// step, as the reset of a sign-in and the plan do (reset-panel.tsx, plan.tsx), and shows any refusal in the server's plain words.
// Nothing typed as proof is kept once it is sent.

/** The answers that mean the server wants the Owner's proof: the form moves to the proof step and says why. */
const PROOF_CODES = new Set(['step_up_required', 'password_required', 'totp_required', 'invalid_password', 'invalid_mfa_code', 'account_locked']);

const NO_ANSWER = 'The answer did not reach your phone, so we cannot tell if the change was saved. Close this and open it again to check.';

export function ContactEditor({ user, self, owner, mfaEnabled, withName, onDone, onCancel }: {
  user: StaffUser;
  /** The signed-in person's own details. */
  self: boolean;
  /** The signed-in person is the Owner (`me.is_owner`): their own email or phone asks for proof first. */
  owner: boolean;
  /** `me.mfa_enabled`: the proof is the authenticator code, otherwise the password. */
  mfaEnabled: boolean;
  /** Ask for the name too (My details); the Users page changes the email or phone only. */
  withName: boolean;
  onDone: (saved: StaffUser) => void;
  onCancel: () => void;
}) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState(() => contactDraftOf(user));
  const [step, setStep] = useState<'edit' | 'proof'>('edit');
  const [proofKind, setProofKind] = useState<'code' | 'password'>(mfaEnabled ? 'code' : 'password');
  const [typed, setTyped] = useState('');
  const [tried, setTried] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const sending = useRef(false);
  const change = contactChange(user, draft, withName);
  // Once the server has asked for proof, Back keeps asking for it, even while /me still says this person is not the Owner.
  const [serverAsked, setServerAsked] = useState(false);
  const needsProof = change.contact && ((self && owner) || serverAsked);
  const proofProblem = resetProofProblem(proofKind === 'code', typed);
  const who = user.full_name ?? 'this person';

  async function send(withProof: boolean) {
    if (sending.current) return;
    sending.current = true;
    setBusy(true);
    setError(null);
    const proof = !withProof ? {} : proofKind === 'code' ? { totp_code: typed.trim() } : { password: typed };
    // The proof is cleared as soon as it is sent: a retry needs the next code, and a password is never kept on the page. The
    // emptied box says nothing of its own until the next press, so only the server's answer shows.
    setTyped('');
    setTried(false);
    try {
      const saved = await users.update(user.id ?? '', user.version ?? 0, { ...change.body, ...proof });
      sayDone(self ? 'Your details are saved' : `New email or phone saved for ${who}`);
      if (self) void queryClient.invalidateQueries({ queryKey: ['me'] });
      void queryClient.invalidateQueries({ queryKey: ['staff-users'] });
      onDone(saved);
    } catch (e) {
      const code = e instanceof UsersError ? e.code : undefined;
      if (code && PROOF_CODES.has(code)) {
        // The server asked for the other kind of proof, or knew this person is the Owner before /me did: ask for what it wants.
        if (code === 'totp_required') setProofKind('code');
        if (code === 'password_required') setProofKind('password');
        if (code === 'totp_required' || code === 'password_required' || code === 'step_up_required') void queryClient.invalidateQueries({ queryKey: ['me'] });
        setServerAsked(true);
        setStep('proof');
      }
      setError(code === 'network' ? NO_ANSWER : e instanceof Error ? e.message : 'Something went wrong. Try again.');
    } finally {
      sending.current = false;
      setBusy(false);
    }
  }

  function next() {
    setTried(true);
    if (!change.ready || busy) return;
    if (Object.keys(change.body).length === 0) {
      setError('Nothing has changed. Change a field, or tap Cancel.');
      return;
    }
    if (needsProof) {
      setError(null);
      setStep('proof');
      return;
    }
    void send(false);
  }

  if (step === 'proof') {
    return (
      <form
        key="proof"
        className="stack"
        aria-label="Show it is you"
        autoComplete="off"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          setTried(true);
          if (proofProblem === null) void send(true);
        }}
      >
        <FieldsNeededNote />
        <p>
          You are the owner, and your email and phone are where a new password link goes. To change them, show it is you with{' '}
          {proofKind === 'code' ? 'the code from your authenticator app' : 'your own password'}.
        </p>
        <ul>
          {change.body.email !== undefined && <li>New email: <strong>{change.body.email}</strong></li>}
          {change.body.phone !== undefined && <li>New phone: <strong>{showPhone(change.body.phone)}</strong></li>}
        </ul>
        {proofKind === 'code' ? (
          <Field
            id="contact-proof"
            label="Code from your authenticator app"
            required
            value={typed}
            onChange={(e) => setTyped(e.target.value.replace(/\D/g, '').slice(0, 6))}
            inputMode="numeric"
            autoComplete="one-time-code"
            spellCheck={false}
            error={tried || typed !== '' ? proofProblem : null}
            showError={tried}
          />
        ) : (
          <Field
            id="contact-proof"
            label="Your own password"
            required
            type="password"
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            autoComplete="current-password"
            spellCheck={false}
            error={tried || typed !== '' ? proofProblem : null}
            showError={tried}
          />
        )}
        {error && <p role="alert" className="alert alert-danger">{error}</p>}
        <Hint full>{user.email ? 'After the change you can hand ownership to someone else only a day later. We tell your old email.' : 'After the change you can hand ownership to someone else only a day later.'}</Hint>
        <p className="cluster">
          <button type="button" /* busy-exempt: back waits for the request */ className="btn-ghost" disabled={busy} onClick={() => { setStep('edit'); setError(null); setTyped(''); setTried(false); }}>
            Back
          </button>
          <BusyButton type="submit" className="rt-primary" busy={busy}>Save the change</BusyButton>
        </p>
      </form>
    );
  }

  return (
    <form
      key="edit"
      className="stack"
      aria-label={self ? 'My details' : `Change the email or phone of ${who}`}
      autoComplete="off"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        next();
      }}
    >
      {withName && <FieldsNeededNote />}
      {withName && (
        <Field id="contact-name" label="Full name" required value={draft.fullName} maxLength={200} autoComplete="name" onChange={(e) => setDraft({ ...draft, fullName: e.target.value })} error={change.problems.fullName} showError={tried} />
      )}
      <Field
        id="contact-phone"
        label="Phone number"
        type="tel"
        inputMode="tel"
        autoComplete="off"
        placeholder="0712 345 678"
        value={draft.phone}
        onChange={(e) => setDraft({ ...draft, phone: e.target.value })}
        hint={self ? 'A Uganda mobile number. You can sign in with it.' : 'A Uganda mobile number. They can sign in with it.'}
        error={change.problems.phone}
        showError={tried}
      />
      <Field
        id="contact-email"
        label="Email address"
        type="email"
        inputMode="email"
        autoComplete="off"
        value={draft.email}
        onChange={(e) => setDraft({ ...draft, email: e.target.value })}
        hint={self ? 'You can sign in with it, and a new password link comes here.' : user.status !== 'active' ? 'They can sign in with it.' : user.email ? 'They can sign in with it. We send a short note to the old and the new email.' : 'They can sign in with it. We send a short note to the new email.'}
        error={change.problems.email}
        showError={tried}
      />
      {needsProof && <p className="hint">You are the owner, so the next step asks for your {proofKind === 'code' ? 'authenticator code' : 'password'}.</p>}
      {error && <p role="alert" className="alert alert-danger">{error}</p>}
      <p className="cluster">
        <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
        <BusyButton type="submit" className="rt-primary" busy={busy}>{needsProof ? 'Next' : 'Save'}</BusyButton>
      </p>
    </form>
  );
}

/**
 * My details (issue #883), from the person menu: the signed-in person's name, phone and email. Someone who may manage the staff
 * changes their own here; anyone else reads them and is told to ask an administrator, since the server lets only a holder of
 * core.users.manage change a sign-in.
 */
export function MyDetailsSheet({ me, open, onClose }: { me: Me; open: boolean; onClose: () => void }) {
  const mayChange = canManageUsers(me);
  const person = useQuery({ queryKey: ['staff-user', me.user_id], queryFn: () => users.get(me.user_id ?? ''), enabled: open && mayChange, gcTime: 0 });
  return (
    <Sheet open={open} onClose={onClose} title="My details">
      {!mayChange ? (
        <div className="stack">
          <dl className="stack">
            <div><dt className="hint">Name</dt><dd>{me.full_name}</dd></div>
            <div><dt className="hint">Phone number</dt><dd>{me.phone_e164 ? showPhone(me.phone_e164) : 'None'}</dd></div>
            <div><dt className="hint">Email address</dt><dd>{me.email ?? 'None'}</dd></div>
          </dl>
          <p role="note" className="alert alert-info">To change your name, phone or email, ask an administrator of your business.</p>
        </div>
      ) : person.isPending ? (
        <p className="loading">Loading your details</p>
      ) : person.isError ? (
        <p role="alert" className="alert alert-danger">{person.error instanceof Error ? person.error.message : 'Could not load your details.'}</p>
      ) : (
        <ContactEditor user={person.data} self owner={me.is_owner === true} mfaEnabled={me.mfa_enabled === true} withName onDone={onClose} onCancel={onClose} />
      )}
    </Sheet>
  );
}
