import { keepPreviousData, useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type Customer, type Supplier } from '../../../api/retail';
import { useStaff } from '../context';
import { BuyerAccount } from './buyer-account';
import { BuyerPricing } from './buyer-pricing';
import { AddPanel } from './catalogue';
import { canUse } from './permissions';
import { ScreenLink } from './screen-link';
import { Gate, Problem, Success, changeFailureText, money, requireVersion, useToast } from './ui';
import { WORDS } from '../../../app/glossary';
import { useComeBack } from '../../../app/ui/EmptyChoice';

// Suppliers and Credit buyers (#146): a name and a contact, added and edited by the shop. A supplier
// can be switched off (it stays on its past restocks); a credit buyer is never removed. The contact
// is shown as typed and is not written to the audit log.

type Person = Supplier | Customer;

export interface PersonEdit { name?: string; contact?: string }

export function PersonRow({ row, busy, extra, onSave, onToggle }: {
  row: Person;
  busy?: boolean;
  /** More about the person, between the balance and the buttons (a buyer's price list). */
  extra?: ReactNode;
  onSave: (edit: PersonEdit) => void;
  onToggle?: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(row.name ?? '');
  const [contact, setContact] = useState(row.contact ?? '');
  const off = 'active' in row && row.active === false;
  const owed = 'balance_minor' in row ? row.balance_minor : undefined;
  return (
    <li className="rt-card">
      <p>
        <strong>{row.name}</strong> {off && <span className="badge badge-warning">Switched off</span>}
      </p>
      <p className="hint">{row.contact ? row.contact : 'No contact saved.'}</p>
      {owed !== undefined && (
        <p>{owed > 0 ? <>Owes <strong>{money(owed)}</strong> on credit sales, all your branches.</> : 'Owes nothing, all your branches.'}</p>
      )}
      {extra}
      {editing ? (
        <div className="stack">
          <label htmlFor={`name-${row.id}`}>Name</label>
          <input id={`name-${row.id}`} value={name} maxLength={200} onChange={(e) => setName(e.target.value)} />
          <label htmlFor={`contact-${row.id}`}>Phone or other contact</label>
          <input id={`contact-${row.id}`} value={contact} maxLength={100} onChange={(e) => setContact(e.target.value)} />
          <p className="cluster">
            <BusyButton
              busy={busy ?? false}
              type="button"
              disabled={name.trim() === ''}
              onClick={() => {
                onSave({ ...(name.trim() !== row.name ? { name: name.trim() } : {}), ...(contact.trim() !== (row.contact ?? '') ? { contact: contact.trim() } : {}) });
                setEditing(false);
              }}
            >
              Save
            </BusyButton>
            <button type="button" className="btn-ghost" onClick={() => { setName(row.name ?? ''); setContact(row.contact ?? ''); setEditing(false); }}>Cancel</button>
          </p>
        </div>
      ) : (
        <p className="cluster">
          <button type="button" className="btn-sm" onClick={() => setEditing(true)} aria-label={`Edit ${row.name}`}>Edit</button>
          {onToggle && (
            <BusyButton busy={busy ?? false} type="button" className="btn-sm" onClick={onToggle} aria-label={`${off ? 'Switch on' : 'Switch off'} ${row.name}`}>
              {off ? 'Switch on' : 'Switch off'}
            </BusyButton>
          )}
        </p>
      )}
    </li>
  );
}

interface PersonPage { items?: Person[]; next_cursor?: string | null }

function PeopleList({ noun, plural, queryKey, list, create, update, canAdd, canSwitch, note, searchable }: {
  noun: string;
  note?: string;
  plural: string;
  queryKey: string;
  list: (q: { query: string; cursor?: string; signal?: AbortSignal }) => Promise<PersonPage>;
  create: (b: { name: string; contact?: string }) => Promise<Person>;
  update: (id: string, version: number, b: PersonEdit & { active?: boolean }) => Promise<Person>;
  canAdd: boolean;
  canSwitch: boolean;
  searchable?: boolean;
}) {
  const queryClient = useQueryClient();
  const back = useComeBack();
  const { show, toast } = useToast();
  const [message, setMessage] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [contact, setContact] = useState('');
  const [adding, setAdding] = useState(false);
  const search = useLiveSearch();
  const query = search.term;
  const rows = useInfiniteQuery({
    queryKey: ['retail', 'catalogue', queryKey, query],
    queryFn: ({ pageParam, signal }) => list({ query, ...(pageParam ? { cursor: pageParam } : {}), signal }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
    placeholderData: keepPreviousData,
  });
  const finding = rows.isPlaceholderData || (rows.isFetching && !rows.isFetchingNextPage);
  const people = (rows.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const done = (text: string) => {
    setMessage(text);
    show(text, 'success');
    void queryClient.invalidateQueries({ queryKey: ['retail'] });
  };
  const add = useMutation({
    mutationFn: () => create({ name: name.trim(), ...(contact.trim() ? { contact: contact.trim() } : {}) }),
    onMutate: () => setMessage(null),
    onSuccess: (row) => {
      setName(''); setContact(''); setAdding(false); done(`Added the ${noun} "${row.name}".`);
      // Opened from an empty list on a form (issue #619): what was added takes the person back to that form.
      back?.();
    },
  });
  const change = useMutation({
    mutationFn: (v: { id: string; version: number | undefined; body: PersonEdit & { active?: boolean }; text: (row: Person) => string }) => update(v.id, requireVersion(v.version), v.body).then((row) => ({ row, text: v.text })),
    onMutate: () => { setMessage(null); setFailure(null); },
    onSuccess: ({ row, text }) => done(text(row)),
    onError: (error) => setFailure(changeFailureText(error, queryClient)),
  });
  return (
    <>
      {note && <p className="hint">{note}</p>}
      {canAdd && (
        <div data-tour="person-add">
        <AddPanel label={`Add a ${noun}`} open={adding} onOpen={() => setAdding(true)} onClose={() => { setAdding(false); setName(''); setContact(''); }}>
        <form onSubmit={(e) => { e.preventDefault(); if (name.trim()) add.mutate(); }}>
          <label htmlFor={`new-${queryKey}-name`}>Name</label>
          <input id={`new-${queryKey}-name`} value={name} maxLength={200} onChange={(e) => setName(e.target.value)} autoComplete="off" />
          <label htmlFor={`new-${queryKey}-contact`}>Phone or other contact (optional)</label>
          <input id={`new-${queryKey}-contact`} value={contact} maxLength={100} onChange={(e) => setContact(e.target.value)} autoComplete="off" />
          <BusyButton busy={add.isPending} type="submit" className="rt-primary" notReady={name.trim() === ''}>{`Add ${noun}`}</BusyButton>
        </form>
        <Problem error={add.error} />
        </AddPanel>
        </div>
      )}
      {message && <Success>{message}</Success>}
      <Problem error={failure && new Error(failure)} />
      {rows.isPending && <p className="loading">Loading</p>}
      <Problem error={rows.error} />
      {searchable && (
        <div data-tour="person-search">
          <SearchBox id={`search-${queryKey}`} label="Search by name or phone" search={search} fetching={finding} count={rows.data ? people.length : undefined} more={rows.hasNextPage} />
        </div>
      )}
      {rows.data && people.length === 0 && (query ? <NoResults term={query} onClear={search.clear} noun={plural} /> : <p className="empty-state">{`No ${plural} yet.`}</p>)}
      <div data-tour="person-list">
      <SearchResults fetching={finding}>
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {people.map((r) => (
          <PersonRow
            key={r.id}
            row={r}
            busy={change.isPending}
            extra={'balance_minor' in r
              ? <><BuyerPricing row={r} /><BuyerAccount row={r} /></>
              : <p><ScreenLink screen="supplierPayments" to="/staff/retail/supplier-payments/new" search={{ supplier: r.id ?? '' }} label={`Open items of ${r.name}`}>Open items</ScreenLink></p>}
            onSave={(edit) => change.mutate({ id: r.id ?? '', version: r.version, body: edit, text: (x) => `Saved the changes to "${x.name}".` })}
            {...(canSwitch
              ? {
                  onToggle: () =>
                    change.mutate({
                      id: r.id ?? '',
                      version: r.version,
                      body: { active: 'active' in r && r.active === false },
                      text: (x) => ('active' in x && x.active === false
                        ? `"${x.name}" is switched off. It stays on past purchases; new purchases cannot choose it.`
                        : `"${x.name}" is switched on again.`),
                    }),
                }
              : {})}
          />
        ))}
      </ul>
      </SearchResults>
      </div>
      {rows.hasNextPage && <button type="button" onClick={() => void rows.fetchNextPage()} disabled={rows.isFetchingNextPage || rows.isPlaceholderData}>{rows.isFetchingNextPage ? 'Loading' : `Show more ${plural}`}</button>}
      {toast}
    </>
  );
}

export function SuppliersPage() {
  const { me } = useStaff();
  return (
    <Gate screen="suppliers" title="Suppliers">
      <PeopleList
        noun="supplier" plural="suppliers" queryKey="suppliers"
        list={() => retail.listSuppliers().then((items) => ({ items }))} create={(b) => retail.createSupplier(b)}
        update={(id, v, b) => retail.updateSupplier(id, v, b)}
        canAdd={canUse(me, 'suppliers')} canSwitch
      />
    </Gate>
  );
}

export function BuyersPage() {
  return (
    <Gate screen="buyers" title={WORDS.customersWhoOwe}>
      <PeopleList
        note="What each buyer owes is counted over all the branches you may read, whichever branch is chosen."
        noun="customer" plural="customers who owe" queryKey="buyers"
        list={(q) => retail.listCustomerPage(q)} create={(b) => retail.createCustomer(b)}
        update={(id, v, b) => retail.updateCustomer(id, v, b)}
        canAdd canSwitch={false} searchable
      />
    </Gate>
  );
}

export const SuppliersRoute = createLazyRoute('/staff/retail/catalogue/suppliers')({ component: SuppliersPage });
export const BuyersRoute = createLazyRoute('/staff/retail/catalogue/buyers')({ component: BuyersPage });
