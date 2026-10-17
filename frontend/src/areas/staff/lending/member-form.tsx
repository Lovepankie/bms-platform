import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi, useNavigate } from '@tanstack/react-router';
import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { asFormError, unknownOutcome } from '../../../api/lending-forms';
import { useMembersApi } from '../../../api/lending-context';
import type { DuplicateCandidate, MemberRecord } from '../../../api/members';
import { branchLabel } from '../../../auth/branch';
import { useStaff } from '../context';
import { newIdempotencyKey } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { Group, SaveBar, SelectField, TextField, useFormErrors } from './form-fields';
import { localDate } from './loan-state';
import { MembersGate } from './member-ui';
import { SaveGuardNotice, useSaveGuard } from './save-guard';
import { mayCreateMember, mayEditMember, memberBranches } from './member-permissions';
import {
  blankMember, createBody, draftOf, duplicateHold, GENDERS, ID_TYPES, isEmpty, MARITAL, memberProblems, normaliseNin, normaliseUganda, reasonWords,
  updateBody, type MemberDraft,
} from './member-state';
import { PhotoQueueControl, usePhotoQueue, type Sender } from './photo-queue';

// Create and edit a member (issue #280, FR-MEM-01 to FR-MEM-04, FR-MEM-09, FR-MEM-10). One column, big inputs, a sticky save
// bar. Before anything is saved the screen asks the server for likely duplicates by phone, national ID and name and shows
// them (masked), and holds the save for the same national ID, or for the same phone until the person ticks that this is
// someone else. An edit sends the member's version as If-Match; a stale version keeps what was typed and says so.
// One Idempotency-Key per form instance, stable across retries. After an answer that may have been lost the screen looks
// the member up before it lets the person save again, so a retry cannot register someone twice.

const ORDER = ['branch_id', 'full_name', 'phone', 'alt_phone', 'national_id', 'other_id_number', 'date_of_birth', 'monthly_income_minor'] as const;
const STATUSES = [
  { value: 'active', label: 'Active' },
  { value: 'inactive', label: 'Not active for now' },
  { value: 'exited', label: 'Left the business' },
];

/** The likely duplicates of what is typed, asked after a short pause and only once the entries are worth asking about. */
function useDuplicates(draft: MemberDraft, selfId: string | undefined, allowed: boolean): { candidates: DuplicateCandidate[]; checking: boolean } {
  const api = useMembersApi();
  const phone = normaliseUganda(draft.phone) ?? undefined;
  const nin = draft.idType === 'nin' ? (normaliseNin(draft.nationalId) ?? undefined) : undefined;
  const name = draft.fullName.trim().length >= 4 ? draft.fullName.trim() : undefined;
  const [settled, setSettled] = useState({ phone, nin, name });
  useEffect(() => {
    const t = setTimeout(() => setSettled({ phone, nin, name }), 400);
    return () => clearTimeout(t);
  }, [phone, nin, name]);
  // The check is a create-permission route: a user who may only change members gets no check (and no silent refusal).
  const ask = allowed && (settled.phone !== undefined || settled.nin !== undefined || settled.name !== undefined);
  const found = useQuery({
    queryKey: ['members', 'duplicates', settled.phone ?? '', settled.nin ?? '', settled.name ?? ''],
    queryFn: ({ signal }) => api.duplicateCheck({ full_name: settled.name, phone: settled.phone, national_id: settled.nin }, signal),
    enabled: ask,
    staleTime: 30_000,
    retry: false,
  });
  const candidates = (ask ? (found.data ?? []) : []).filter((c) => c.id !== selfId);
  return { candidates, checking: ask && found.isFetching };
}

export function DuplicateList({ candidates, hold, confirmed, onConfirm, phoneShared = false }: {
  /** The server refused the phone as shared, so the box shows even when the check found nobody (or was not allowed). */
  phoneShared?: boolean;
  candidates: readonly DuplicateCandidate[];
  hold: 'nin' | 'phone' | null;
  confirmed: boolean;
  onConfirm: (yes: boolean) => void;
}) {
  if (candidates.length === 0 && !phoneShared) return null;
  const ninHeld = hold === 'nin';
  const phoneOnly = phoneShared || candidates.some((c) => (c.match_reasons ?? []).includes('phone'));
  return (
    <section className="alert alert-warning mf-dups" role="status" aria-labelledby="mf-dups-title" data-tour="member-duplicates">
      <h2 id="mf-dups-title">{candidates.length > 0 ? 'Someone like this is already registered' : 'This phone number is already on another member'}</h2>
      <ul className="ln-list">
        {candidates.map((c) => (
          <li key={c.id ?? c.member_no}>
            <strong>{c.member_no}</strong>
            {c.full_name ? `, ${c.full_name}` : ''} (matches on {reasonWords(c.match_reasons)})
            {c.phone_e164_masked ? <span className="hint"> Phone {c.phone_e164_masked}</span> : null}
            {c.id && c.in_scope ? <> <Link to="/staff/lending/members/$memberId" params={{ memberId: c.id }} target="_blank" rel="noreferrer">Open this member</Link></> : null}
          </li>
        ))}
      </ul>
      {ninHeld && <p>This national ID number is already registered, so this member cannot be saved. Check the number, or open the member above.</p>}
      {phoneOnly && !ninHeld && (
        <label>
          <input type="checkbox" checked={confirmed} onChange={(e) => onConfirm(e.target.checked)} />
          This is a different person who shares the phone number
        </label>
      )}
    </section>
  );
}

export function MemberForm({ member: loaded, onSaved }: { member?: MemberRecord; onSaved?: (m: MemberRecord) => void }) {
  const { me, branch } = useStaff();
  const api = useMembersApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  // The member as last read: a stale version can be replaced by the latest without touching what was typed.
  const [member, setBase] = useState(loaded);
  const editing = member !== undefined;
  const allowed = memberBranches(me);
  const startBranch = allowed.find((b) => b.id === branch)?.id ?? (allowed.length === 1 ? allowed[0]?.id : undefined) ?? '';
  const [draft, setDraft] = useState<MemberDraft>(() => (member ? draftOf(member) : blankMember(startBranch)));
  const [status, setStatus] = useState(member?.status ?? 'active');
  const [idempotencyKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const guard = useSaveGuard<DuplicateCandidate>();
  const [serverPhone, setServerPhone] = useState(false);
  const [saved, setSaved] = useState<MemberRecord | null>(null);
  const idFront = usePhotoQueue();
  const idBack = usePhotoQueue();
  const original = useMemo(() => (member ? draftOf(member) : undefined), [member]);
  const problems = memberProblems(draft, localDate(), editing, original);
  const form = useFormErrors(problems, 'mf');
  const mayCheck = !editing || (me.permissions ?? []).includes('lending.members.create');
  const found = useDuplicates(draft, member?.id, mayCheck);
  const { checking } = found;
  // On an edit, a phone the member already has is not a shared phone: the box and the hold apply only when the phone was changed.
  const phoneChanged = !editing || normaliseUganda(draft.phone) !== normaliseUganda(original?.phone ?? '');
  const candidates = phoneChanged ? found.candidates : found.candidates.filter((c) => !(c.match_reasons ?? []).includes('phone') || (c.match_reasons ?? []).includes('nin'));
  const phoneShared = serverPhone && phoneChanged;
  const hold = duplicateHold(candidates, draft.confirmedNotDuplicate) ?? (phoneShared && !draft.confirmedNotDuplicate ? 'phone' : null);
  const set = <K extends keyof MemberDraft>(key: K, value: MemberDraft[K]) => setDraft((d) => ({ ...d, [key]: value }));

  const sendPhotos = async (id: string): Promise<boolean> => {
    const send = (kind: 'id_front' | 'id_back'): Sender<unknown> => (p, o) => api.uploadDocument(id, kind, p.blob, p.fileName, o);
    const a = await idFront.sendAll(send('id_front'));
    const b = await idBack.sendAll(send('id_back'));
    return a.failed + b.failed === 0;
  };

  const photosBusy = idFront.busy || idBack.busy;

  const finish = (m: MemberRecord) => {
    void queryClient.invalidateQueries({ queryKey: ['members'] });
    if (onSaved) onSaved(m);
    else void navigate({ to: '/staff/lending/members/$memberId', params: { memberId: m.id ?? '' } });
  };

  /** The member this form may already have saved: the same phone and the same name. Rejects when the look itself fails. */
  const lookUp = async (): Promise<DuplicateCandidate | null> => {
    const phone = normaliseUganda(draft.phone);
    if (!phone) return null;
    const candidates = await api.duplicateCheck({ phone });
    return candidates.find((c) => (c.match_reasons ?? []).includes('phone') && c.full_name?.trim().toLowerCase() === draft.fullName.trim().toLowerCase()) ?? null;
  };

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setFailure(null);
    if (!form.attempt(ORDER)) return;
    if (hold || guard.blocked || photosBusy) return;
    setBusy(true);
    try {
      if (member) {
        const body = updateBody(member, draft);
        if (status !== member.status) body.status = status as 'active';
        if (isEmpty(body) && !body.status) {
          finish(member);
          return;
        }
        finish(await api.update(member.id ?? '', member.version ?? 0, body));
        return;
      }
      const created = await api.create(createBody(draft), idempotencyKey);
      setSaved(created);
      if (idFront.waiting + idBack.waiting === 0 || (await sendPhotos(created.id ?? ''))) finish(created);
    } catch (raw) {
      const error = asFormError(raw);
      form.refuse(error.fields, ORDER);
      if (error.code === 'version_conflict') setStale(true);
      if (error.code === 'duplicate_phone') setServerPhone(true);
      if (error.code === 'duplicate_phone' || error.code === 'duplicate_nin') void queryClient.invalidateQueries({ queryKey: ['members', 'duplicates'] });
      setFailure(Object.keys(error.fields).length > 0 && error.code !== 'version_conflict' ? null : error.message);
      // The answer may have been lost: look the member up, and allow no other save until that look has worked.
      if (!editing && unknownOutcome(error)) await guard.check(lookUp);
    } finally {
      setBusy(false);
    }
  }

  const branchName = branchLabel((me.branches ?? []).find((b) => b.id === draft.branchId));
  const blocked = hold === 'nin' ? 'This national ID is already registered.' : hold === 'phone' ? 'Tick the box above to say this is a different person.' : guard.blocked ? 'Wait for the check above before saving again.' : photosBusy ? 'Wait for the photo to be ready.' : undefined;
  const photoSender = (kind: 'id_front' | 'id_back'): Sender<unknown> | undefined => (saved ? (p, o) => api.uploadDocument(saved.id ?? '', kind, p.blob, p.fileName, o) : undefined);

  if (saved) {
    return (
      <section className="rt-card stack" aria-live="polite">
        <h2>{saved.full_name} is saved</h2>
        <p>Member number {saved.member_no}. A photo did not go through. Send it again, or open the member and add it later.</p>
        <PhotoQueueControl id="mf-front-retry" label="Photo of the front of the ID" queue={idFront} sender={photoSender('id_front')} />
        <PhotoQueueControl id="mf-back-retry" label="Photo of the back of the ID" queue={idBack} sender={photoSender('id_back')} />
        <Link to="/staff/lending/members/$memberId" params={{ memberId: saved.id ?? '' }} className="btn btn-block rt-primary">Open the member</Link>
      </section>
    );
  }

  return (
    <form className="mf" onSubmit={(e) => void submit(e)} noValidate aria-label={editing ? 'Change member details' : 'Add a member'} data-tour="member-form">
      {form.count(ORDER) > 0 && (
        <p role="alert" className="alert alert-danger">Some entries need a look. They are marked below.</p>
      )}
      {stale && (
        <div role="alert" className="alert alert-warning">
          <p>Someone else changed this member since you opened it. What you typed is kept.</p>
          <button type="button" className="btn-sm" onClick={() => void api.get(member?.id ?? '').then((latest) => { setBase(latest); setStale(false); }).catch(() => setFailure('The latest version could not be loaded. Check the connection.'))}>Use their changes and keep mine</button>
        </div>
      )}
      <SaveGuardNotice guard={guard} what="member" found={(c) => <Link to="/staff/lending/members/$memberId" params={{ memberId: c.id ?? '' }}>Open {c.member_no}</Link>} />
      <Group title="Who is the member" tourId="member-who">
        {editing ? (
          <p className="field-hint">Branch: {branchName}. To move a member to another branch, ask an administrator.</p>
        ) : (
          <SelectField name="branch_id" label="Branch" form={form} required value={draft.branchId} onChange={(v) => set('branchId', v)}
            options={allowed.map((b) => ({ value: b.id ?? '', label: branchLabel(b) }))} blankLabel="Choose a branch" />
        )}
        <TextField name="full_name" label="Full name" form={form} required value={draft.fullName} onChange={(v) => set('fullName', v)} autoComplete="off" maxLength={200} />
        <TextField name="phone" label="Phone number" form={form} required type="tel" inputMode="tel" value={draft.phone} onChange={(v) => { setServerPhone(false); set('phone', v); }}
          hint="For example 0712 345 678. A +256 number works too." />
        <TextField name="alt_phone" label="Another phone number (if any)" form={form} type="tel" inputMode="tel" value={draft.altPhone} onChange={(v) => set('altPhone', v)} />
      </Group>
      <Group title="ID" tourId="member-id">
        <SelectField name="id_type" label="Kind of ID" form={form} value={draft.idType} onChange={(v) => set('idType', v)} options={ID_TYPES} />
        {draft.idType === 'nin' && (
          <TextField name="national_id" label="National ID number (NIN)" form={form} required value={draft.nationalId} onChange={(v) => set('nationalId', v)}
            hint="From the card. It starts with CM or CF." />
        )}
        {draft.idType !== 'nin' && draft.idType !== 'none' && (
          <TextField name="other_id_number" label="ID number" form={form} required value={draft.otherIdNumber} onChange={(v) => set('otherIdNumber', v)} maxLength={40} />
        )}
      </Group>
      <DuplicateList candidates={candidates} phoneShared={phoneShared} hold={hold} confirmed={draft.confirmedNotDuplicate} onConfirm={(yes) => set('confirmedNotDuplicate', yes)} />
      {checking && candidates.length === 0 && <p className="field-hint" role="status">Checking whether this member is already registered</p>}
      <Group title="About the member">
        <TextField name="date_of_birth" label="Date of birth" form={form} type="date" value={draft.dateOfBirth} onChange={(v) => set('dateOfBirth', v)} max={localDate()} />
        <SelectField name="gender" label="Gender" form={form} value={draft.gender} onChange={(v) => set('gender', v)} options={GENDERS} />
        <SelectField name="marital_status" label="Marital status" form={form} value={draft.maritalStatus} onChange={(v) => set('maritalStatus', v)} options={MARITAL} />
        <TextField name="occupation" label="Work or business" form={form} value={draft.occupation} onChange={(v) => set('occupation', v)} maxLength={120} />
        <TextField name="monthly_income_minor" label="Usual income a month (UGX)" form={form} inputMode="numeric" value={draft.monthlyIncome} onChange={(v) => set('monthlyIncome', v)}
          hint={editing && original?.monthlyIncome !== '' ? 'A saved income can be changed but not removed. Type 0 for none.' : undefined} />
      </Group>
      <Group title="Where the member lives">
        <TextField name="district" label="District" form={form} value={draft.district} onChange={(v) => set('district', v)} maxLength={100} />
        <TextField name="village" label="Village or town" form={form} value={draft.village} onChange={(v) => set('village', v)} maxLength={100} />
        <TextField name="location" label="Street or landmark" form={form} value={draft.location} onChange={(v) => set('location', v)} maxLength={200} />
      </Group>
      {!editing && (
        <Group title="Photo of the ID" tourId="member-id-photo">
          <PhotoQueueControl id="mf-front" label="Front of the ID" queue={idFront} hint="Take it flat and in good light. It is shrunk before it is sent." />
          <PhotoQueueControl id="mf-back" label="Back of the ID (optional)" queue={idBack} />
          <p className="field-hint">You can also add the photo later from the member's page. Without it, the details cannot be checked.</p>
        </Group>
      )}
      {editing && (
        <Group title="Status">
          <SelectField name="status" label="Member status" form={form} value={status} onChange={setStatus} options={STATUSES} />
        </Group>
      )}
      <Problem error={failure ? new Error(failure) : null} />
      <SaveBar label={editing ? 'Save changes' : 'Save member'} busyLabel="Saving" busy={busy} blocked={blocked}
        cancel={<Link to={editing ? '/staff/lending/members/$memberId' : '/staff/lending/members'} params={editing ? { memberId: member?.id ?? '' } : undefined as never} className="btn btn-ghost">Cancel</Link>}
        tourId="member-save" />
    </form>
  );
}

function NewMember() {
  const { me } = useStaff();
  return (
    <MembersGate title="Add a member" tourId="member-new">
      {mayCreateMember(me) ? <MemberForm /> : <p className="empty-state">You do not have access to add members.</p>}
    </MembersGate>
  );
}

const editRoute = getRouteApi('/staff/lending/members/$memberId/edit');

function EditMember() {
  const { memberId } = editRoute.useParams();
  const { me } = useStaff();
  const api = useMembersApi();
  const member = useQuery({ queryKey: ['members', 'one', memberId], queryFn: () => api.get(memberId) });
  return (
    <MembersGate title="Change member details" tourId="member-edit">
      {member.isPending && <p className="loading">Loading the member</p>}
      <Problem error={member.error} />
      {member.data && (mayEditMember(me, member.data.branch_id) ? <MemberForm member={member.data} /> : <p className="empty-state">You do not have access to change this member.</p>)}
    </MembersGate>
  );
}

export const NewRoute = createLazyRoute('/staff/lending/members/new')({ component: NewMember });
export const EditRoute = createLazyRoute('/staff/lending/members/$memberId/edit')({ component: EditMember });
