import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { asFormError, unknownOutcome } from '../../../api/lending-forms';
import { useMembersApi } from '../../../api/lending-context';
import type { NextOfKin } from '../../../api/members';
import { Problem } from '../retail/ui';
import { Group, SaveBar, SelectField, TextField, useFormErrors } from './form-fields';
import { FieldsNeededNote } from '../../../app/ui/Field';
import { blankKin, kinBody, kinProblems, RELATIONSHIPS, relationshipWords, type KinDraft } from './member-state';
import { normaliseUganda, showPhone } from './member-state';
import { SaveGuardNotice, useSaveGuard } from './save-guard';
import { BusyButton } from '../../../app/ui/BusyButton';

// Next of kin on a member's page (FR-MEM-06, FR-MEM-07): add, change, remove, and say yes or no to a suggested link to
// another member. A change or a removal carries the person's version as If-Match, so two officers cannot overwrite each other.

const ORDER = ['full_name', 'phone'] as const;

function KinForm({ kin, memberId, onDone }: { kin?: NextOfKin; memberId: string; onDone: () => void }) {
  const api = useMembersApi();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<KinDraft>(() => (kin
    ? { fullName: kin.full_name ?? '', phone: kin.phone_e164 ?? '', relationship: kin.relationship ?? 'other', location: kin.location ?? '', isPrimary: !!kin.is_primary }
    : blankKin()));
  const form = useFormErrors(kinProblems(draft), kin ? `nk-${kin.id}` : 'nk-new');
  const [failure, setFailure] = useState<string | null>(null);
  const guard = useSaveGuard<NextOfKin>();
  /** A next of kin already on the member with this name and phone. Rejects when the look itself fails. */
  const lookUp = async (): Promise<NextOfKin | null> => {
    const phone = normaliseUganda(draft.phone);
    return (await api.listKin(memberId)).find((k) => k.phone_e164 === phone && k.full_name?.trim().toLowerCase() === draft.fullName.trim().toLowerCase()) ?? null;
  };
  const save = useMutation({
    mutationFn: () => (kin ? api.updateKin(kin.id ?? '', kin.version ?? 0, kinBody(draft)) : api.addKin(memberId, kinBody(draft))),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['members', 'kin', memberId] });
      void queryClient.invalidateQueries({ queryKey: ['members', 'one', memberId] });
      onDone();
    },
    onError: (raw) => {
      const e = asFormError(raw);
      form.refuse(e.fields, ORDER);
      setFailure(Object.keys(e.fields).length > 0 ? null : e.message);
      // No other save of a new next of kin until a look at the list has worked: the answer may have been lost.
      if (!kin && unknownOutcome(e)) void guard.check(lookUp);
    },
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setFailure(null);
    if (save.isPending || guard.blocked || !form.attempt(ORDER)) return;
    save.mutate();
  };
  return (
    <form onSubmit={submit} noValidate aria-label={kin ? 'Change next of kin' : 'Add a next of kin'} className="mf" data-tour="kin-form">
      <FieldsNeededNote />
      <Group title={kin ? 'Change next of kin' : 'Add a next of kin'}>
        <TextField name="full_name" label="Full name" form={form} required value={draft.fullName} onChange={(v) => setDraft((d) => ({ ...d, fullName: v }))} maxLength={200} />
        <SelectField name="relationship" label="Who they are to the member" form={form} value={draft.relationship} onChange={(v) => setDraft((d) => ({ ...d, relationship: v }))} options={RELATIONSHIPS} />
        <TextField name="phone" label="Phone number" form={form} required type="tel" inputMode="tel" value={draft.phone} onChange={(v) => setDraft((d) => ({ ...d, phone: v }))} />
        <TextField name="location" label="Where they live" form={form} value={draft.location} onChange={(v) => setDraft((d) => ({ ...d, location: v }))} maxLength={200} />
        <label>
          <input type="checkbox" checked={draft.isPrimary} onChange={(e) => setDraft((d) => ({ ...d, isPrimary: e.target.checked }))} />
          The first person to call
        </label>
      </Group>
      <SaveGuardNotice guard={guard} what="next of kin" found={() => <button type="button" className="btn-sm" onClick={onDone}>Close</button>} />
      <Problem error={failure ? new Error(failure) : null} />
      <SaveBar label="Save" busy={save.isPending} blocked={guard.blocked ? 'Wait for the check above before saving again.' : undefined} cancel={<button type="button" className="btn btn-ghost" onClick={onDone}>Cancel</button>} />
    </form>
  );
}

function KinRow({ kin, memberId, canChange, onEdit }: { kin: NextOfKin; memberId: string; canChange: boolean; onEdit: () => void }) {
  const api = useMembersApi();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [asking, setAsking] = useState(false);
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['members', 'kin', memberId] });
  const fail = (e: unknown) => setError(e instanceof Error ? e.message : 'Something went wrong. Try again.');
  const remove = useMutation({ mutationFn: () => api.removeKin(kin.id ?? '', kin.version ?? 0), onSuccess: () => { setAsking(false); refresh(); }, onError: fail });
  const decide = useMutation({ mutationFn: (d: 'confirm' | 'reject') => api.decideKinLink(kin.id ?? '', kin.version ?? 0, d), onSuccess: refresh, onError: fail });
  return (
    <li className="rt-card stack">
      <p className="ln-item-head">
        <strong>{kin.full_name}</strong>
        {kin.is_primary && <span className="badge badge-info">First to call</span>}
      </p>
      <p>{relationshipWords(kin.relationship)}. {showPhone(kin.phone_e164)}{kin.location ? `. ${kin.location}` : ''}</p>
      {kin.link_status === 'confirmed' || (kin.linked_member_no && kin.link_status !== 'suggested' && kin.link_status !== 'rejected') ? (
        <p className="hint">Also a member: {kin.linked_member_no}</p>
      ) : null}
      {kin.link_status === 'suggested' && (
        <div className="alert alert-info" role="group" aria-label="Suggested link">
          <p>A member has this phone number. Is this person the same as that member?</p>
          {canChange && (
            <p className="cluster">
              <BusyButton busy={decide.isPending && decide.variables === 'confirm'} type="button" className="btn-sm" disabled={decide.isPending} onClick={() => decide.mutate('confirm')}>Yes, the same person</BusyButton>
              <BusyButton busy={decide.isPending && decide.variables === 'reject'} type="button" className="btn-sm btn-ghost" disabled={decide.isPending} onClick={() => decide.mutate('reject')}>No</BusyButton>
            </p>
          )}
        </div>
      )}
      {error && <p role="alert" className="field-error">{error}</p>}
      {canChange && !asking && (
        <p className="cluster">
          <button type="button" className="btn-sm" onClick={onEdit} aria-label={`Change ${kin.full_name ?? 'next of kin'}`}>Change</button>
          <button type="button" className="btn-sm btn-ghost" onClick={() => setAsking(true)} aria-label={`Remove ${kin.full_name ?? 'next of kin'}`}>Remove</button>
        </p>
      )}
      {asking && (
        <div role="group" aria-label="Remove next of kin" className="cluster">
          <span>Remove {kin.full_name}?</span>
          <BusyButton busy={remove.isPending} type="button" className="btn-sm" onClick={() => remove.mutate()}>Yes, remove</BusyButton>
          <button type="button" className="btn-sm btn-ghost" onClick={() => setAsking(false)}>Keep</button>
        </div>
      )}
    </li>
  );
}

export function NextOfKinSection({ memberId, canChange }: { memberId: string; canChange: boolean }) {
  const api = useMembersApi();
  const [editing, setEditing] = useState<string | null>(null);
  const kin = useQuery({ queryKey: ['members', 'kin', memberId], queryFn: () => api.listKin(memberId) });
  const items = kin.data ?? [];
  return (
    <section className="rt-card stack" aria-labelledby="kin-title" data-tour="member-kin">
      <h2 id="kin-title">Next of kin</h2>
      {kin.isPending && <p className="loading">Loading next of kin</p>}
      <Problem error={kin.error} />
      {kin.data && items.length === 0 && <p className="empty-state">No next of kin yet. The member's details cannot be checked without one.</p>}
      <ul className="ln-list">
        {items.map((k) => (editing === k.id
          ? <li key={k.id} className="rt-card"><KinForm kin={k} memberId={memberId} onDone={() => setEditing(null)} /></li>
          : <KinRow key={k.id} kin={k} memberId={memberId} canChange={canChange} onEdit={() => setEditing(k.id ?? null)} />))}
      </ul>
      {canChange && editing === null && <button type="button" className="btn-block" onClick={() => setEditing('new')}>Add a next of kin</button>}
      {editing === 'new' && <KinForm memberId={memberId} onDone={() => setEditing(null)} />}
    </section>
  );
}
