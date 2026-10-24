import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState, type FormEvent } from 'react';
import type { CollateralDetail, CollateralItem } from '../../../api/collateral';
import { asFormError, openInNewTab, unknownOutcome } from '../../../api/lending-forms';
import { useCollateralApi } from '../../../api/lending-context';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import {
  blankValuation, canRequestRelease, custodyBadge, custodyWords, eventBody, eventProblems, EVENT_WORDS, eventsFrom, ownerWords, typeWords,
  valuationBody, valuationProblems, type EventType,
} from './collateral-state';
import { Group, SaveBar, SelectField, TextField, useFormErrors } from './form-fields';
import { FieldsNeededNote } from '../../../app/ui/Field';
import { localDate, money } from './loan-state';
import { mayManageCollateral, mayReleaseCollateral } from './member-permissions';
import { CollateralGate } from './member-ui';
import { PhotoQueueControl, usePhotoQueue, type Sender } from './photo-queue';
import { SaveGuardNotice, useSaveGuard } from './save-guard';
import { BusyButton } from '../../../app/ui/BusyButton';

// One collateral item (FR-COL-01 to FR-COL-04): the facts, the photos and scans, valuations over time, the custody history
// with the changes that are allowed from where the item is now, and the request to give it back. Giving an item back is not
// done on this screen: this screen sends the request, and a second person agrees or refuses it in the approvals inbox
// (the maker-checker action collateral_release, FR-COL-04). Nothing here is final until they do.

function Facts({ c }: { c: CollateralItem }) {
  return (
    <dl className="ln-facts">
      <dt>Kind</dt><dd>{typeWords(c.collateral_type)}</dd>
      {c.reference_no && (<><dt>Reference</dt><dd>{c.reference_no}</dd></>)}
      <dt>Owner</dt><dd>{c.owner_name ?? ownerWords(c.owner_relationship)}{c.owner_name ? ` (${ownerWords(c.owner_relationship).toLowerCase()})` : ''}</dd>
      <dt>Your estimate</dt><dd>{c.estimated_value_minor != null ? money(c.estimated_value_minor, c.currency) : 'None'}</dd>
      <dt>Value used for loan cover</dt><dd className="ln-strong">{c.collateral_value_minor != null ? money(c.collateral_value_minor, c.currency) : 'None yet'}</dd>
      {c.storage_location && (<><dt>Kept at</dt><dd>{c.storage_location}</dd></>)}
    </dl>
  );
}

function useDetail(id: string) {
  const api = useCollateralApi();
  return useQuery({ queryKey: ['collateral', 'one', id], queryFn: () => api.get(id) });
}

function Photos({ id, canAdd }: { id: string; canAdd: boolean }) {
  const api = useCollateralApi();
  const queryClient = useQueryClient();
  const docs = useQuery({ queryKey: ['collateral', 'documents', id], queryFn: () => api.listDocuments(id) });
  const queue = usePhotoQueue();
  const [error, setError] = useState<string | null>(null);
  const [opening, setOpening] = useState<string | null>(null);
  const sender: Sender<unknown> = async (p, o) => {
    const out = await api.uploadDocument(id, p.blob, p.fileName, o);
    void queryClient.invalidateQueries({ queryKey: ['collateral', 'documents', id] });
    return out;
  };
  const open = async (documentId: string) => {
    setOpening(documentId);
    setError(null);
    try {
      await openInNewTab(() => api.documentUrl(documentId));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The file could not be opened.');
    } finally {
      setOpening(null);
    }
  };
  const items = docs.data ?? [];
  return (
    <section className="rt-card stack" aria-labelledby="col-photos" data-tour="collateral-documents">
      <h2 id="col-photos">Photos and scans</h2>
      {docs.isPending && <p className="loading">Loading photos</p>}
      <Problem error={docs.error} />
      {docs.data && items.length === 0 && <p className="empty-state">No photos yet.</p>}
      {error && <p role="alert" className="field-error">{error}</p>}
      <ul className="ln-list">
        {items.map((d, i) => (
          <li key={d.document_id} className="rt-card cluster">
            <span>Photo or scan {i + 1}</span>
            <span className="hint">{d.created_at ? d.created_at.slice(0, 10) : ''}</span>
            <BusyButton busy={opening === (d.document_id ?? '')} type="button" className="btn-sm" aria-label={`Open photo or scan ${i + 1}`} onClick={() => void open(d.document_id ?? '')}>Open</BusyButton>
          </li>
        ))}
      </ul>
      {canAdd && <PhotoQueueControl id="col-add" label="Add a photo or scan" queue={queue} sender={sender} max={5} />}
    </section>
  );
}

function Valuations({ id, currency, valuations, canAdd }: { id: string; currency?: string; valuations: { id?: string; valued_on?: string; valuer_name?: string; market_value_minor?: number; forced_sale_value_minor?: number; note?: string }[]; canAdd: boolean }) {
  const api = useCollateralApi();
  const queryClient = useQueryClient();
  const today = localDate();
  const [open, setOpen] = useState(false);
  const [d, setD] = useState(blankValuation(today));
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useFormErrors(valuationProblems(d, today), 'cv');
  const guard = useSaveGuard<unknown>();
  /** A valuation of the same day and market value already on the item. Rejects when the look itself fails. */
  const lookUp = async (): Promise<unknown | null> => {
    const body = valuationBody(d);
    return (await api.get(id)).valuations?.find((v) => v.valued_on === body.valued_on && v.market_value_minor === body.market_value_minor) ?? null;
  };
  const ORDER = ['valued_on', 'market_value_minor', 'forced_sale_value_minor'];
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || guard.blocked || !form.attempt(ORDER)) return;
    setBusy(true);
    setFailure(null);
    try {
      await api.addValuation(id, valuationBody(d));
      void queryClient.invalidateQueries({ queryKey: ['collateral'] });
      setOpen(false);
      setD(blankValuation(today));
    } catch (raw) {
      const error = asFormError(raw);
      form.refuse(error.fields, ORDER);
      setFailure(Object.keys(error.fields).length > 0 ? null : error.message);
      if (unknownOutcome(error)) await guard.check(lookUp);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="rt-card stack" aria-labelledby="col-vals" data-tour="collateral-valuations">
      <h2 id="col-vals">Valuations</h2>
      {valuations.length === 0 && <p className="empty-state">No valuation yet. Loan cover uses your estimate.</p>}
      <ul className="ln-list">
        {valuations.map((v) => (
          <li key={v.id} className="rt-card">
            <p><strong>{v.valued_on}</strong>{v.valuer_name ? `, ${v.valuer_name}` : ''}</p>
            <p>Market value {money(v.market_value_minor, currency)}{v.forced_sale_value_minor != null ? `. Quick sale ${money(v.forced_sale_value_minor, currency)}` : ''}</p>
            {v.note && <p className="hint">{v.note}</p>}
          </li>
        ))}
      </ul>
      {canAdd && !open && <button type="button" className="btn-block" onClick={() => setOpen(true)}>Add a valuation</button>}
      {open && (
        <form onSubmit={(e) => void submit(e)} noValidate aria-label="Add a valuation" className="mf">
          <FieldsNeededNote />
          <Group title="Add a valuation">
            <TextField name="valued_on" label="Date of the valuation" form={form} required type="date" value={d.valuedOn} onChange={(v) => setD((x) => ({ ...x, valuedOn: v }))} max={today} />
            <TextField name="valuer" label="Who valued it" form={form} value={d.valuer} onChange={(v) => setD((x) => ({ ...x, valuer: v }))} maxLength={200} />
            <TextField name="market_value_minor" label="Market value (UGX)" form={form} required inputMode="numeric" value={d.market} onChange={(v) => setD((x) => ({ ...x, market: v }))} />
            <TextField name="forced_sale_value_minor" label="Value in a quick sale (UGX, if known)" form={form} inputMode="numeric" value={d.forcedSale} onChange={(v) => setD((x) => ({ ...x, forcedSale: v }))}
              hint="When given, this is the value used for loan cover." />
            <TextField name="note" label="Note" form={form} type="textarea" value={d.note} onChange={(v) => setD((x) => ({ ...x, note: v }))} maxLength={1000} />
          </Group>
          <SaveGuardNotice guard={guard} what="valuation" found={() => <button type="button" className="btn-sm" onClick={() => { setOpen(false); void queryClient.invalidateQueries({ queryKey: ['collateral'] }); guard.release(); }}>Close</button>} />
          <Problem error={failure ? new Error(failure) : null} />
          <SaveBar label="Save valuation" busy={busy} blocked={guard.blocked ? 'Wait for the check above before saving again.' : undefined} cancel={<button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>Cancel</button>} />
        </form>
      )}
    </section>
  );
}

function CustodyChange({ id, version, custody, onDone }: { id: string; version: number; custody?: string; onDone: () => void }) {
  const api = useCollateralApi();
  const queryClient = useQueryClient();
  const options = eventsFrom(custody);
  const [type, setType] = useState<EventType>(options[0] ?? 'note');
  const [location, setLocation] = useState('');
  const [counterparty, setCounterparty] = useState('');
  const [note, setNote] = useState('');
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useFormErrors(eventProblems(type, location, note), 'ce');
  const ORDER = ['location', 'note'];
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || !form.attempt(ORDER)) return;
    setBusy(true);
    setFailure(null);
    try {
      await api.recordEvent(id, version, eventBody(type, location, counterparty, note));
      void queryClient.invalidateQueries({ queryKey: ['collateral'] });
      onDone();
    } catch (raw) {
      const error = asFormError(raw);
      form.refuse(error.fields, ORDER);
      setFailure(Object.keys(error.fields).length > 0 ? null : error.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <form onSubmit={(e) => void submit(e)} noValidate aria-label="Record a change" className="mf">
      {(type === 'received_into_custody' || type === 'moved' || type === 'note') && <FieldsNeededNote />}
      <Group title="Record a change">
        <SelectField name="event_type" label="What happened" form={form} value={type} onChange={(v) => setType(v as EventType)} options={options.map((o) => ({ value: o, label: EVENT_WORDS[o] }))} />
        {(type === 'received_into_custody' || type === 'moved') && <TextField name="location" label="Where it is kept now" form={form} required value={location} onChange={setLocation} maxLength={200} />}
        {(type === 'seized' || type === 'disposed') && <TextField name="counterparty" label="Who took it or bought it (if any)" form={form} value={counterparty} onChange={setCounterparty} maxLength={200} />}
        <TextField name="note" label="Note" form={form} required={type === 'note'} type="textarea" value={note} onChange={setNote} maxLength={1000} />
      </Group>
      <Problem error={failure ? new Error(failure) : null} />
      <SaveBar label="Save" busy={busy} cancel={<button type="button" className="btn btn-ghost" onClick={onDone}>Cancel</button>} />
    </form>
  );
}

function ReleaseRequest({ id, version, onDone }: { id: string; version: number; onDone: () => void }) {
  const api = useCollateralApi();
  const queryClient = useQueryClient();
  const [by, setBy] = useState('');
  const [note, setNote] = useState('');
  const [failure, setFailure] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || by.trim() === '') return;
    setBusy(true);
    setFailure(null);
    try {
      await api.requestRelease(id, version, { collected_by: by.trim(), note: note.trim() || undefined });
      setSent(true);
      void queryClient.invalidateQueries({ queryKey: ['collateral'] });
    } catch (error) {
      setFailure(error instanceof Error ? error.message : 'Something went wrong. Try again.');
    } finally {
      setBusy(false);
    }
  }
  if (sent) {
    return (
      <div role="status" className="alert alert-success stack">
        <p>The request is sent. A second person must agree in the approvals inbox before the item is given back.</p>
        <button type="button" className="btn-sm" onClick={onDone}>Done</button>
      </div>
    );
  }
  return (
    <form onSubmit={(e) => void submit(e)} noValidate aria-label="Ask to give the item back" className="mf">
      <Group title="Ask to give the item back">
        <label htmlFor="rel-by">Who is collecting it</label>
        <input id="rel-by" value={by} maxLength={200} onChange={(e) => setBy(e.target.value)} aria-required="true" autoComplete="off" />
        <label htmlFor="rel-note">Note</label>
        <textarea id="rel-note" rows={2} value={note} maxLength={1000} onChange={(e) => setNote(e.target.value)} />
        <p className="field-hint">A loan that this item secures must be closed first. A second person agrees in the approvals inbox.</p>
      </Group>
      <Problem error={failure ? new Error(failure) : null} />
      <SaveBar label="Send the request" busy={busy} blocked={by.trim() === '' ? 'Say who is collecting it.' : undefined}
        cancel={<button type="button" className="btn btn-ghost" onClick={onDone}>Cancel</button>} />
    </form>
  );
}

export function CollateralView({ detail, collateralId }: { detail: CollateralDetail; collateralId: string }) {
  const { me } = useStaff();
  const [panel, setPanel] = useState<'change' | 'release' | null>(null);
  const c: CollateralItem = detail.item ?? {};
  const manage = mayManageCollateral(me, c.branch_id);
  const release = mayReleaseCollateral(me, c.branch_id) && canRequestRelease(c.custody_status);
  return (
    <>
      <section className="rt-card ln-head" aria-label="Item" data-tour="collateral-header">
        <p className="ln-item-head"><strong>{typeWords(c.collateral_type)}</strong><span className={custodyBadge(c.custody_status)}>{custodyWords(c.custody_status)}</span></p>
        <Facts c={c} />
        <p className="cluster" data-tour="collateral-actions">
          {c.member_id && <Link to="/staff/lending/members/$memberId" params={{ memberId: c.member_id }} className="btn btn-ghost">Open the member</Link>}
          {manage && <Link to="/staff/lending/collateral/$collateralId/edit" params={{ collateralId }} className="btn rt-primary">Change details</Link>}
          {manage && panel === null && eventsFrom(c.custody_status).length > 0 && <button type="button" className="btn-ghost" onClick={() => setPanel('change')}>Record a change</button>}
          {release && panel === null && <button type="button" className="btn-ghost" onClick={() => setPanel('release')}>Ask to give it back</button>}
        </p>
        {panel === 'change' && <CustodyChange id={collateralId} version={c.version ?? 0} custody={c.custody_status} onDone={() => setPanel(null)} />}
        {panel === 'release' && <ReleaseRequest id={collateralId} version={c.version ?? 0} onDone={() => setPanel(null)} />}
      </section>
      <Photos id={collateralId} canAdd={manage} />
      <Valuations id={collateralId} currency={c.currency} valuations={detail.valuations ?? []} canAdd={manage && c.custody_status !== 'disposed'} />
      <section className="rt-card stack" aria-labelledby="col-hist" data-tour="collateral-history">
        <h2 id="col-hist">History</h2>
        {(detail.events ?? []).length === 0 && <p className="empty-state">No changes recorded yet.</p>}
        <ul className="ln-list">
          {(detail.events ?? []).map((ev) => (
            <li key={ev.id} className="rt-card">
              <p><strong>{EVENT_WORDS[ev.event_type as EventType] ?? ev.event_type}</strong>{ev.occurred_at ? `, ${ev.occurred_at.slice(0, 10)}` : ''}</p>
              {ev.to_status && ev.from_status !== ev.to_status && <p>Now: {custodyWords(ev.to_status)}</p>}
              {ev.location && <p>At {ev.location}</p>}
              {ev.note && <p className="hint">{ev.note}</p>}
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}

const route = getRouteApi('/staff/lending/collateral/$collateralId');

function CollateralPage() {
  const { collateralId } = route.useParams();
  const detail = useDetail(collateralId);
  const c = detail.data?.item;
  useCrumbLabel(c ? typeWords(c.collateral_type) : undefined);
  return (
    <CollateralGate title={c?.description ?? 'Collateral'} tourId="collateral-page">
      <Link to="/staff/lending/collateral" className="btn btn-ghost">Back to collateral</Link>
      {detail.isPending && <p className="loading">Loading the item</p>}
      <Problem error={detail.error} />
      {detail.data && <CollateralView detail={detail.data} collateralId={collateralId} />}
    </CollateralGate>
  );
}

export const Route = createLazyRoute('/staff/lending/collateral/$collateralId')({ component: CollateralPage });
