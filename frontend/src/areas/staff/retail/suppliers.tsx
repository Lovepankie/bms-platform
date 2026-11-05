import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { retail, type Supplier, type SupplierDetails } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useComeBack } from '../../../app/ui/EmptyChoice';
import { Field, FieldsNeededNote, focusFirstMissing } from '../../../app/ui/Field';
import { Card, ListRow, RowChevron, RowList } from '../../../app/ui/ListRow';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { useStaff } from '../context';
import { AddPanel } from './catalogue';
import { ContactLinks } from './contact-links';
import { showDate } from './maths';
import { holds } from './permissions';
import { ScreenLink } from './screen-link';
import { Gate, Problem, changeFailureText, requireVersion, useToast } from './ui';

// Suppliers (issue #820, ADR-125, FR-RET-90), as the owner's old app kept them: a name and a phone, and the optional email,
// address, country, website, payment terms, bank account number, supplier type and notes. The list is searched by name, phone
// or type; each supplier has its own page with these details, the date added, the last delivery date (read from its
// purchases, never typed) and its deliveries, newest first, each opening the purchase. The bank account number comes from the
// server only to those who may edit suppliers, so the screens show it when it is there. A supplier is never deleted: it is
// switched off and stays on its past purchases.

export const DETAIL_KEYS = ['email', 'address', 'country', 'website', 'payment_terms', 'bank_account', 'supplier_type', 'notes'] as const;
export type DetailKey = (typeof DETAIL_KEYS)[number];

export interface SupplierDraft { name: string; contact: string; details: Record<DetailKey, string> }

/** The form's values for a supplier, or an empty form. */
export function draftOf(s?: Supplier): SupplierDraft {
  const d = (s?.details ?? {}) as Partial<Record<DetailKey, string | null>>;
  const details = Object.fromEntries(DETAIL_KEYS.map((k) => [k, d[k] ?? ''])) as Record<DetailKey, string>;
  return { name: s?.name ?? '', contact: s?.contact ?? '', details };
}

/** What is wrong with a typed email, or null; an empty one is fine, every detail is optional. */
export function emailProblem(text: string): string | null {
  const t = text.trim();
  return t === '' || /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/.test(t) ? null : 'Write the email like name@example.com.';
}

/** The link a typed web address opens: https:// added when it has no http or https; null when it is not a web address. */
export function websiteHref(text: string | null | undefined): string | null {
  const t = (text ?? '').trim();
  if (t === '') return null;
  const full = /^https?:\/\//i.test(t) ? t : `https://${t}`;
  try {
    const url = new URL(full);
    if ((url.protocol !== 'https:' && url.protocol !== 'http:') || url.username || !/^[a-z0-9-]+(\.[a-z0-9-]+)+$/i.test(url.hostname)) return null;
    return url.href;
  } catch {
    return null;
  }
}

/** What is wrong with a typed web address, or null. */
export function websiteProblem(text: string): string | null {
  return text.trim() === '' || websiteHref(text) ? null : 'Write the web address like example.com.';
}

/** The body of an add: the name, and only the fields typed. */
export function createBody(draft: SupplierDraft): { name: string; contact?: string; details?: SupplierDetails } {
  const details = Object.fromEntries(DETAIL_KEYS.filter((k) => draft.details[k].trim() !== '').map((k) => [k, draft.details[k].trim()]));
  return {
    name: draft.name.trim(),
    ...(draft.contact.trim() ? { contact: draft.contact.trim() } : {}),
    ...(Object.keys(details).length > 0 ? { details: details as SupplierDetails } : {}),
  };
}

/** The body of an edit: only what changed, a field emptied sent empty so the server clears it. */
export function editBody(before: Supplier, draft: SupplierDraft): { name?: string; contact?: string; details?: SupplierDetails } {
  const was = draftOf(before);
  const details = Object.fromEntries(DETAIL_KEYS.filter((k) => draft.details[k].trim() !== was.details[k].trim()).map((k) => [k, draft.details[k].trim()]));
  return {
    ...(draft.name.trim() !== was.name ? { name: draft.name.trim() } : {}),
    ...(draft.contact.trim() !== was.contact ? { contact: draft.contact.trim() } : {}),
    ...(Object.keys(details).length > 0 ? { details: details as SupplierDetails } : {}),
  };
}

const LABELS: Record<DetailKey, string> = {
  email: 'Email',
  address: 'Address',
  country: 'Country',
  website: 'Website',
  payment_terms: 'Payment terms',
  bank_account: 'Bank account number',
  supplier_type: 'Supplier type',
  notes: 'Notes',
};

const LENGTHS: Record<DetailKey, number> = {
  email: 254, address: 300, country: 100, website: 300, payment_terms: 100, bank_account: 60, supplier_type: 60, notes: 1000,
};

/** The add and edit form. The bank account number is asked only of those who may edit suppliers. */
export function SupplierForm({ supplier, showBank, busy, onSave, onCancel, saveLabel }: {
  supplier?: Supplier;
  showBank: boolean;
  busy: boolean;
  onSave: (draft: SupplierDraft) => void;
  onCancel?: () => void;
  saveLabel: string;
}) {
  const [draft, setDraft] = useState(() => draftOf(supplier));
  const [tried, setTried] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  const types = useQuery({ queryKey: ['retail', 'suppliers', 'types'], queryFn: () => retail.listSupplierTypes() });
  const set = (key: DetailKey, value: string) => setDraft((d) => ({ ...d, details: { ...d.details, [key]: value } }));
  const problems: Partial<Record<DetailKey | 'name', string | null>> = {
    name: draft.name.trim() === '' ? 'Type the name of the supplier.' : null,
    email: emailProblem(draft.details.email),
    website: websiteProblem(draft.details.website),
  };
  const id = supplier?.id ?? 'new';
  const field = (key: DetailKey, hint?: string, input: Record<string, string> = {}) => (
    <Field
      id={`supplier-${key}-${id}`}
      label={LABELS[key]}
      hint={hint}
      error={problems[key] ?? null}
      showError={tried}
      value={draft.details[key]}
      maxLength={LENGTHS[key]}
      autoComplete="off"
      onChange={(e) => set(key, e.target.value)}
      {...input}
    />
  );
  return (
    <form
      ref={form}
      className="stack"
      aria-label={saveLabel}
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        setTried(true);
        if (Object.values(problems).some((p) => p)) {
          if (form.current) focusFirstMissing(form.current);
          return;
        }
        onSave(draft);
      }}
    >
      <FieldsNeededNote />
      <Field id={`supplier-name-${id}`} label="Name" required error={problems.name ?? null} showError={tried} value={draft.name} maxLength={200} autoComplete="off" onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))} />
      <Field id={`supplier-contact-${id}`} label="Phone or other contact" hint="Call, Text and WhatsApp work with a phone number." value={draft.contact} maxLength={100} autoComplete="off" inputMode="tel" onChange={(e) => setDraft((d) => ({ ...d, contact: e.target.value }))} />
      {field('email', undefined, { type: 'email', inputMode: 'email' })}
      {field('supplier_type', 'For example Wholesaler. Type to pick one you used before, or a new one.', { list: `supplier-types-${id}` })}
      <datalist id={`supplier-types-${id}`}>
        {(types.data ?? []).map((t) => <option key={t} value={t} />)}
      </datalist>
      {field('payment_terms', 'For example 30 days.')}
      {field('address')}
      {field('country')}
      {field('website', 'For example example.com.', { inputMode: 'url' })}
      {showBank && field('bank_account', 'Only people who may edit suppliers see this.')}
      <Field
        id={`supplier-notes-${id}`}
        label={LABELS.notes}
        control={(props) => <textarea {...props} rows={3} maxLength={LENGTHS.notes} value={draft.details.notes} onChange={(e) => set('notes', e.target.value)} />}
      />
      <p className="cluster">
        <BusyButton busy={busy} type="submit" className="rt-primary">{saveLabel}</BusyButton>
        {onCancel && <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>}
      </p>
    </form>
  );
}

/** One supplier on the list: who, how to reach them, the last delivery, and the way to its page. */
function SupplierCard({ s }: { s: Supplier }) {
  const type = s.details?.supplier_type;
  return (
    <li className="rt-card">
      <p>
        <strong>{s.name}</strong> {s.active === false && <span className="badge badge-warning">Switched off</span>}
      </p>
      <p className="hint">{[type, s.contact || 'No contact saved.'].filter(Boolean).join(', ')}</p>
      <ContactLinks contact={s.contact} name={s.name} />
      <p className="hint">{s.last_delivery_on ? `Last delivery ${showDate(s.last_delivery_on)}` : 'No deliveries yet.'}</p>
      <Link className="link-row" to="/staff/retail/catalogue/suppliers/$supplierId" params={{ supplierId: s.id ?? '' }} aria-label={`Open ${s.name}`}>
        Details and deliveries<RowChevron />
      </Link>
      <ScreenLink screen="supplierPayments" to="/staff/retail/supplier-payments/new" search={{ supplier: s.id ?? '' }} label={`Open items of ${s.name}`}>Open items</ScreenLink>
    </li>
  );
}

export function SuppliersPage() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const back = useComeBack();
  const { show, toast } = useToast();
  const [adding, setAdding] = useState(false);
  const search = useLiveSearch();
  const query = search.term;
  const rows = useQuery({
    queryKey: ['retail', 'catalogue', 'suppliers', query],
    queryFn: ({ signal }) => retail.listSuppliers({ query, signal }),
    placeholderData: keepPreviousData,
  });
  const finding = rows.isPlaceholderData || rows.isFetching;
  const people = rows.data ?? [];
  const add = useMutation({
    mutationFn: (draft: SupplierDraft) => retail.createSupplier(createBody(draft)),
    onSuccess: (row) => {
      setAdding(false);
      show(`Added the supplier "${row.name}".`, 'success');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      // Opened from an empty list on a form (issue #619): what was added takes the person back to that form.
      back?.();
    },
  });
  return (
    <Gate screen="suppliers" title="Suppliers">
      <div data-tour="person-add">
        <AddPanel label="Add a supplier" open={adding} onOpen={() => setAdding(true)} onClose={() => setAdding(false)}>
          <SupplierForm showBank={holds(me, 'retail.catalogue.manage')} busy={add.isPending} saveLabel="Add supplier" onSave={(d) => add.mutate(d)} />
          <Problem error={add.error} />
        </AddPanel>
      </div>
      <div data-tour="person-search">
        <SearchBox id="search-suppliers" label="Search by name, phone or type" search={search} fetching={finding} count={rows.data ? people.length : undefined} />
      </div>
      {rows.isPending && <p className="loading">Loading</p>}
      <Problem error={rows.error} />
      {rows.data && people.length === 0 && (query ? <NoResults term={query} onClear={search.clear} noun="suppliers" /> : <p className="empty-state">No suppliers yet. Add the first one above.</p>)}
      <div data-tour="person-list">
        <SearchResults fetching={finding}>
          <ul style={{ listStyle: 'none', padding: 0 }}>
            {people.map((s) => <SupplierCard key={s.id} s={s} />)}
          </ul>
        </SearchResults>
      </div>
      {toast}
    </Gate>
  );
}

/** A detail on the supplier's page, left out when empty. */
function Fact({ label, value }: { label: string; value: string | null | undefined }) {
  if (!value) return null;
  return (
    <>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </>
  );
}

export function SupplierPage({ supplierId }: { supplierId: string }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const [editing, setEditing] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const supplier = useQuery({ queryKey: ['retail', 'suppliers', 'one', supplierId], queryFn: () => retail.getSupplier(supplierId) });
  const deliveries = useInfiniteQuery({
    queryKey: ['retail', 'suppliers', 'one', supplierId, 'deliveries'],
    queryFn: ({ pageParam, signal }) => retail.listSupplierDeliveries(supplierId, { ...(pageParam ? { cursor: pageParam } : {}), signal }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
  });
  const s = supplier.data;
  useCrumbLabel(s?.name);
  const canEdit = holds(me, 'retail.catalogue.manage');
  const change = useMutation({
    mutationFn: (v: { body: Parameters<typeof retail.updateSupplier>[2]; text: (row: Supplier) => string }) =>
      retail.updateSupplier(supplierId, requireVersion(s?.version), v.body).then((row) => ({ row, text: v.text })),
    onMutate: () => setFailure(null),
    onSuccess: ({ row, text }) => {
      setEditing(false);
      show(text(row), 'success');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
    onError: (error) => setFailure(changeFailureText(error, queryClient)),
  });
  const d = s?.details ?? {};
  const site = websiteHref(d.website);
  const items = (deliveries.data?.pages ?? []).flatMap((p) => p.items ?? []);
  return (
    <Gate screen="suppliers" title={s?.name ?? 'Supplier'}>
      <Problem error={supplier.error} />
      <Problem error={failure && new Error(failure)} />
      {supplier.isPending && <p className="loading">Loading</p>}
      <section data-tour="supplier-details" aria-label="Details">
        {s?.active === false && <p><span className="badge badge-warning">Switched off</span></p>}
        {s && !editing && (
          <dl className="receipt-facts">
            <Fact label="Supplier type" value={d.supplier_type} />
            <Fact label="Phone or other contact" value={s.contact} />
            <Fact label="Email" value={d.email} />
            <Fact label="Address" value={d.address} />
            <Fact label="Country" value={d.country} />
            <Fact label="Website" value={d.website} />
            <Fact label="Payment terms" value={d.payment_terms} />
            <Fact label="Bank account number" value={d.bank_account} />
            <Fact label="Date added" value={s.created_at ? showDate(s.created_at.slice(0, 10)) : undefined} />
            <dt>Last delivery</dt>
            <dd>{s.last_delivery_on ? showDate(s.last_delivery_on) : 'None yet'}</dd>
          </dl>
        )}
        {s && !editing && d.notes && <p className="supplier-notes">{d.notes}</p>}
      </section>
      <div data-tour="supplier-contact">
        {s && !editing && (
          <>
            <ContactLinks contact={s.contact} name={s.name} />
            {(d.email || site) && (
              <p className="contact-links">
                {d.email && <a className="btn btn-sm" href={`mailto:${d.email}`} aria-label={`Email ${s.name}`}>Email</a>}
                {site && <a className="btn btn-sm" href={site} target="_blank" rel="noopener noreferrer" aria-label={`Open the website of ${s.name}`}>Website</a>}
              </p>
            )}
          </>
        )}
      </div>
      {canEdit && (
        // The anchor of the page tour is drawn before the supplier arrives, so it is there when the tour starts.
        <div data-tour="supplier-edit">
          {!s ? null : editing ? (
            <SupplierForm
              supplier={s}
              showBank
              busy={change.isPending}
              saveLabel="Save"
              onCancel={() => setEditing(false)}
              onSave={(draft) => {
                const body = editBody(s, draft);
                if (Object.keys(body).length === 0) { setEditing(false); return; }
                change.mutate({ body, text: (x) => `Saved the changes to "${x.name}".` });
              }}
            />
          ) : (
            <p className="cluster">
              <button type="button" onClick={() => setEditing(true)} aria-label={`Edit ${s.name}`}>Edit</button>
              <BusyButton
                busy={change.isPending}
                type="button"
                aria-label={`${s.active === false ? 'Switch on' : 'Switch off'} ${s.name}`}
                onClick={() => change.mutate({
                  body: { active: s.active === false },
                  text: (x) => (x.active === false
                    ? `"${x.name}" is switched off. It stays on past purchases; new purchases cannot choose it.`
                    : `"${x.name}" is switched on again.`),
                })}
              >
                {s.active === false ? 'Switch on' : 'Switch off'}
              </BusyButton>
            </p>
          )}
        </div>
      )}
      <div data-tour="supplier-deliveries">
        <Card title="Deliveries">
          {deliveries.isPending && <p className="loading">Loading</p>}
          <Problem error={deliveries.error} />
          {deliveries.data && items.length === 0 && <p className="empty-state">No deliveries from this supplier yet.</p>}
          {items.length > 0 && (
            <RowList label="Deliveries, newest first">
              {items.map((x) => (
                <ListRow
                  key={x.id}
                  to={`/staff/retail/restocks/${x.id ?? ''}`}
                  title={`Purchase ${x.purchase_no ?? ''}`}
                  meta={[showDate(x.purchased_on), `${x.lines ?? 0} ${x.lines === 1 ? 'item' : 'items'}`, x.voided ? 'Voided' : ''].filter(Boolean).join(', ')}
                />
              ))}
            </RowList>
          )}
          {deliveries.hasNextPage && (
            <button type="button" onClick={() => void deliveries.fetchNextPage()} disabled={deliveries.isFetchingNextPage}>
              {deliveries.isFetchingNextPage ? 'Loading' : 'Show older deliveries'}
            </button>
          )}
        </Card>
      </div>
      {toast}
    </Gate>
  );
}

const route = getRouteApi('/staff/retail/catalogue/suppliers/$supplierId');

function SupplierRoutePage() {
  const { supplierId } = route.useParams();
  return <SupplierPage key={supplierId} supplierId={supplierId} />;
}

export const SuppliersRoute = createLazyRoute('/staff/retail/catalogue/suppliers')({ component: SuppliersPage });
export const SupplierRoute = createLazyRoute('/staff/retail/catalogue/suppliers/$supplierId')({ component: SupplierRoutePage });
