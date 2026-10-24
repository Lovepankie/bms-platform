import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi, useNavigate } from '@tanstack/react-router';
import { useMemo, useState, type FormEvent } from 'react';
import type { CollateralItem } from '../../../api/collateral';
import { asFormError, unknownOutcome } from '../../../api/lending-forms';
import { useCollateralApi, useMembersApi } from '../../../api/lending-context';
import { useStaff } from '../context';
import { newIdempotencyKey } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { blankCollateral, collateralProblems, createBody, CUSTODY, draftOf, OWNERS, referenceLabel, TYPES, updateBody, type CollateralDraft } from './collateral-state';
import { CollateralGate } from './member-ui';
import { Group, SaveBar, SelectField, TextField, useFormErrors } from './form-fields';
import { FieldsNeededNote } from '../../../app/ui/Field';
import { mayManageCollateral } from './member-permissions';
import { MemberPicker } from './member-picker';
import { PhotoQueueControl, usePhotoQueue, type Sender } from './photo-queue';
import { SaveGuardNotice, useSaveGuard } from './save-guard';
import { NoAccess } from '../../../app/ui/EmptyState';

// Register or change a collateral item (issue #280, FR-COL-01, FR-COL-05): the member it belongs to (found by searching), the
// kind, a description, the title, logbook or plate number, who owns it, what it is worth, whether the owner keeps it or we
// hold it, and photos. A vehicle needs its plate number; an item with the same kind and reference that is still registered is
// refused by the server. One Idempotency-Key per form instance. After an answer that may have been lost the screen looks at the
// member's collateral before it lets the person save again.

const ORDER = ['member_id', 'description', 'reference_no', 'owner_name', 'estimated_value_minor', 'storage_location'] as const;

export function CollateralForm({ item: loaded, memberId }: { item?: CollateralItem; memberId?: string }) {
  const api = useCollateralApi();
  const members = useMembersApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [item, setBase] = useState(loaded);
  const editing = item !== undefined;
  const [draft, setDraft] = useState<CollateralDraft>(() => (item ? draftOf(item) : blankCollateral(memberId)));
  const [chosen, setChosen] = useState<{ id: string; name: string; no: string } | null>(null);
  const [idempotencyKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const guard = useSaveGuard<CollateralItem>();
  const [stale, setStale] = useState(false);
  const [saved, setSaved] = useState<CollateralItem | null>(null);
  const photos = usePhotoQueue();
  const original = useMemo(() => (item ? draftOf(item) : undefined), [item]);
  const problems = collateralProblems(draft, editing, original);
  const form = useFormErrors(problems, 'cf');
  // A member given in the address (from the member's page) is read once to show the name.
  const given = useQuery({ queryKey: ['members', 'one', memberId], queryFn: () => members.get(memberId ?? ''), enabled: !!memberId && !editing });
  const shown = chosen ?? (given.data && draft.memberId === given.data.id ? { id: given.data.id ?? '', name: given.data.full_name ?? '', no: given.data.member_no ?? '' } : null);
  const set = <K extends keyof CollateralDraft>(key: K, value: CollateralDraft[K]) => setDraft((d) => ({ ...d, [key]: value }));

  const sender = (id: string): Sender<unknown> => (p, o) => api.uploadDocument(id, p.blob, p.fileName, o);
  const done = (c: CollateralItem) => {
    void queryClient.invalidateQueries({ queryKey: ['collateral'] });
    void navigate({ to: '/staff/lending/collateral/$collateralId', params: { collateralId: c.id ?? '' } });
  };

  /** An item of this kind and description already on the member. Rejects when the look itself fails. */
  const lookUp = async (): Promise<CollateralItem | null> => {
    const list = await api.list({ memberId: draft.memberId });
    return list.items?.find((c) => c.collateral_type === draft.type && c.description?.trim() === draft.description.trim()) ?? null;
  };

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || guard.blocked || photos.busy) return;
    setFailure(null);
    if (!form.attempt(ORDER)) return;
    setBusy(true);
    try {
      if (item) {
        const body = updateBody(item, draft);
        done(Object.keys(body).length === 0 ? item : await api.update(item.id ?? '', item.version ?? 0, body));
        return;
      }
      const created = await api.create(createBody(draft), idempotencyKey);
      setSaved(created);
      if (photos.waiting === 0) done(created);
      else if ((await photos.sendAll(sender(created.id ?? ''))).failed === 0) done(created);
    } catch (raw) {
      const error = asFormError(raw);
      form.refuse(error.fields, ORDER);
      if (error.code === 'version_conflict') setStale(true);
      setFailure(Object.keys(error.fields).length > 0 && error.code !== 'version_conflict' ? null : error.message);
      // The answer may have been lost, and an item with no reference number has nothing on the server to stop a second one:
      // look at the member's collateral, and allow no other save until that look has worked.
      if (!editing && unknownOutcome(error)) await guard.check(lookUp);
    } finally {
      setBusy(false);
    }
  }

  if (saved) {
    return (
      <section className="rt-card stack" aria-live="polite">
        <h2>The collateral is registered</h2>
        <p>A photo did not go through. Send it again, or open the item and add it later.</p>
        <PhotoQueueControl id="cf-photos-retry" label="Photos of the item" queue={photos} sender={sender(saved.id ?? '')} max={5} />
        <Link to="/staff/lending/collateral/$collateralId" params={{ collateralId: saved.id ?? '' }} className="btn btn-block rt-primary">Open the item</Link>
      </section>
    );
  }
  const owned = draft.ownerRelationship !== 'self';
  return (
    <form className="mf" onSubmit={(e) => void submit(e)} noValidate aria-label={editing ? 'Change collateral' : 'Register collateral'} data-tour="collateral-form">
      <FieldsNeededNote />
      {form.count(ORDER) > 0 && <p role="alert" className="alert alert-danger">Some entries need a look. They are marked below.</p>}
      {stale && (
        <div role="alert" className="alert alert-warning">
          <p>Someone else changed this item since you opened it. What you typed is kept.</p>
          <button type="button" className="btn-sm" onClick={() => void api.get(item?.id ?? '').then((d) => { setBase(d.item); setStale(false); }).catch(() => setFailure('The latest version could not be loaded. Check the connection.'))}>Use their changes and keep mine</button>
        </div>
      )}
      <SaveGuardNotice guard={guard} what="item" canRelease found={(c) => <Link to="/staff/lending/collateral/$collateralId" params={{ collateralId: c.id ?? '' }}>Open it</Link>} />
      <Group title="The item" tourId="collateral-item">
        {!editing && <MemberPicker id={form.idOf('member_id')} chosen={shown} error={form.error('member_id')} errorId={`${form.idOf('member_id')}-error`}
          onChoose={(m) => { setChosen(m); set('memberId', m?.id ?? ''); }} />}
        {!editing && (
          <SelectField name="type" label="Kind of collateral" form={form} value={draft.type} onChange={(v) => set('type', v)} options={TYPES} />
        )}
        <TextField name="description" label="Describe the item" form={form} required type="textarea" rows={3} value={draft.description} onChange={(v) => set('description', v)} maxLength={300} />
        <TextField name="reference_no" label={referenceLabel(draft.type)} form={form} required={draft.type === 'vehicle'} value={draft.referenceNo} onChange={(v) => set('referenceNo', v)} maxLength={60}
          hint="The same number cannot be registered twice while the item is still held." />
      </Group>
      <Group title="Who owns it and what it is worth" tourId="collateral-owner">
        <SelectField name="owner_relationship" label="Who owns it" form={form} value={draft.ownerRelationship} onChange={(v) => setDraft((d) => ({ ...d, ownerRelationship: v, ownerName: v === 'self' ? '' : d.ownerName }))} options={OWNERS} />
        {owned && <TextField name="owner_name" label="Owner's name" form={form} required={draft.ownerRelationship === 'other'} value={draft.ownerName} onChange={(v) => set('ownerName', v)} maxLength={200} />}
        <TextField name="estimated_value_minor" label="What it is worth (UGX, your estimate)" form={form} inputMode="numeric" value={draft.value} onChange={(v) => set('value', v)}
          hint="A valuation can be added later. Until then the loan cover uses this estimate." />
      </Group>
      {!editing && (
        <Group title="Where the item is">
          <SelectField name="custody" label="Who keeps it" form={form} value={draft.custody} onChange={(v) => set('custody', v)} options={CUSTODY} />
          {draft.custody === 'in_custody' && <TextField name="storage_location" label="Where it is kept" form={form} required value={draft.storageLocation} onChange={(v) => set('storageLocation', v)} maxLength={200} />}
        </Group>
      )}
      {!editing && (
        <Group title="Photos" tourId="collateral-photos">
          <PhotoQueueControl id="cf-photos" label="Photos or scans of the item and its papers" queue={photos} max={5} hint="Up to five. Photos are shrunk before they are sent." />
        </Group>
      )}
      <Problem error={failure ? new Error(failure) : null} />
      <SaveBar label={editing ? 'Save changes' : 'Register collateral'} busy={busy} blocked={guard.blocked ? 'Wait for the check above before saving again.' : photos.busy ? 'Wait for the photo to be ready.' : undefined}
        cancel={editing ? <Link to="/staff/lending/collateral/$collateralId" params={{ collateralId: item?.id ?? '' }} className="btn btn-ghost">Cancel</Link> : <Link to="/staff/lending/collateral" className="btn btn-ghost">Cancel</Link>} tourId="collateral-save" />
    </form>
  );
}

const newRoute = getRouteApi('/staff/lending/collateral/new');

function NewCollateral() {
  const { me } = useStaff();
  const { memberId } = newRoute.useSearch();
  return (
    <CollateralGate title="Register collateral" tourId="collateral-new">
      {mayManageCollateral(me) ? <CollateralForm memberId={memberId} /> : <NoAccess what="register collateral" />}
    </CollateralGate>
  );
}

const editRoute = getRouteApi('/staff/lending/collateral/$collateralId/edit');

function EditCollateral() {
  const { collateralId } = editRoute.useParams();
  const { me } = useStaff();
  const api = useCollateralApi();
  const detail = useQuery({ queryKey: ['collateral', 'one', collateralId], queryFn: () => api.get(collateralId) });
  const item = detail.data?.item;
  return (
    <CollateralGate title="Change collateral" tourId="collateral-edit">
      {detail.isPending && <p className="loading">Loading the item</p>}
      <Problem error={detail.error} />
      {item && (mayManageCollateral(me, item.branch_id) ? <CollateralForm item={item} /> : <NoAccess what="change this item" />)}
    </CollateralGate>
  );
}

export const NewRoute = createLazyRoute('/staff/lending/collateral/new')({ component: NewCollateral });
export const EditRoute = createLazyRoute('/staff/lending/collateral/$collateralId/edit')({ component: EditCollateral });
