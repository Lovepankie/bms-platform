import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { SmsError, sms, type MemberSmsOptOut } from '../../../api/sms';
import { useStaff } from '../context';
import { LiftConfirm } from '../sms/lift';

// The member's text-message choice, on the member's own page (extras slice B3, issue #218; FR-NTF-14; ADR-036 decision 8):
// staff stop reminders to the member, or every text message, or lift it. A stop recorded here follows the member to any number
// and does not touch someone else who later has the same number. Receipts are texts too: stopping "every text message"
// stops them as well, which is the member's right to ask. The server checks the member's branch and the permission.

export const SCOPES = [
  { value: 'reminders', label: 'Stop reminders', words: 'Reminders and arrears notices are not sent. Receipts still are.' },
  { value: 'all', label: 'Stop every text message', words: 'No text message at all is sent, receipts included.' },
];

/** The member's choice in words. */
export function choiceWords(state: Pick<MemberSmsOptOut, 'opted_out' | 'scope'>): string {
  if (!state.opted_out) return 'This member gets text messages.';
  return SCOPES.find((s) => s.value === state.scope)?.words ?? 'This member has asked not to be texted.';
}

/** Stopping more is anyone's who may change the member; stopping less (narrowing, lifting) is the administrator's. */
export function scopesOffered(state: Pick<MemberSmsOptOut, 'opted_out' | 'scope'>, isAdministrator: boolean) {
  return SCOPES.filter((s) => {
    if (state.opted_out && state.scope === s.value) return false;
    return isAdministrator || !state.opted_out || state.scope !== 'all';
  });
}

export function MemberSmsView({ state, canChange, isAdministrator = false, busy, error, onSet, onLift }: {
  state: MemberSmsOptOut;
  canChange: boolean;
  isAdministrator?: boolean;
  busy: boolean;
  error: string | null;
  onSet: (scope: string) => void;
  /** Asked for with a reason, from the confirmation panel */
  onLift: (reason: string) => void;
}) {
  const [lifting, setLifting] = useState(false);
  return (
    <section className="rt-card stack" aria-labelledby="member-sms">
      <h2 id="member-sms">Text messages</h2>
      <p>
        {state.opted_out ? <span className="badge badge-warning">Opted out</span> : <span className="badge badge-success">Receives texts</span>}{' '}
        {choiceWords(state)}
      </p>
      {state.opted_out && state.recipient_masked && (
        <p className="hint">Recorded when the member's number was {state.recipient_masked}. It applies to any number the member has.</p>
      )}
      {error && <p role="alert" className="field-error">{error}</p>}
      {canChange && (
        <p className="cluster">
          {scopesOffered(state, isAdministrator).map((s) => (
            <button key={s.value} type="button" className="btn-ghost" disabled={busy} onClick={() => onSet(s.value)}>
              {state.opted_out ? `Change to: ${s.label.toLowerCase()}` : s.label}
            </button>
          ))}
          {state.opted_out && isAdministrator && (
            <button type="button" className="rt-primary" disabled={busy || lifting} onClick={() => setLifting(true)}>
              Allow text messages again
            </button>
          )}
        </p>
      )}
      {lifting && canChange && isAdministrator && (
        <LiftConfirm
          what="this member"
          busy={busy}
          error={null}
          onConfirm={(reason) => { onLift(reason); setLifting(false); }}
          onCancel={() => setLifting(false)}
        />
      )}
    </section>
  );
}

export function MemberSmsControl({ memberId }: { memberId: string }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const held = me.permissions ?? [];
  const [failure, setFailure] = useState<string | null>(null);
  const key = ['sms', 'member', memberId];
  const read = useQuery({ queryKey: key, queryFn: () => sms.memberOptOut(memberId), enabled: held.includes('lending.members.read'), retry: false });
  const write = useMutation({
    mutationFn: (v: { scope: string } | { lift: string }) => ('lift' in v ? sms.liftMemberOptOut(memberId, v.lift) : sms.setMemberOptOut(memberId, v.scope)),
    onSuccess: (next) => {
      setFailure(null);
      queryClient.setQueryData(key, next);
      void queryClient.invalidateQueries({ queryKey: ['sms', 'opt-outs'] });
    },
    onError: (error) => setFailure(error instanceof SmsError ? error.message : 'Could not save the choice.'),
  });
  // Without permission to read members the card is not shown at all; the server would refuse the read.
  if (!held.includes('lending.members.read') || read.isPending || !read.data) return null;
  return (
    <MemberSmsView
      state={read.data}
      canChange={held.includes('lending.members.update')}
      isAdministrator={held.includes('core.settings.manage')}
      busy={write.isPending}
      error={failure}
      onSet={(scope) => write.mutate({ scope })}
      onLift={(reason) => write.mutate({ lift: reason })}
    />
  );
}
