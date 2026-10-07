import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type Customer, type Supplier } from '../../../api/retail';
import { useStaff } from '../context';
import { AddPanel, BackToCatalogue } from './catalogue';
import { canUse } from './permissions';
import { Gate, Problem, Success, money, useToast } from './ui';

// Suppliers and Credit buyers (#146): a name and a contact, added and edited by the shop. A supplier
// can be switched off (it stays on its past restocks); a credit buyer is never removed. The contact
// is shown as typed and is not written to the audit log.

type Person = Supplier | Customer;

export interface PersonEdit { name?: string; contact?: string }

export function PersonRow({ row, busy, onSave, onToggle }: {
  row: Person;
  busy?: boolean;
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
      {editing ? (
        <div className="stack">
          <label htmlFor={`name-${row.id}`}>Name</label>
          <input id={`name-${row.id}`} value={name} maxLength={200} onChange={(e) => setName(e.target.value)} />
          <label htmlFor={`contact-${row.id}`}>Phone or other contact</label>
          <input id={`contact-${row.id}`} value={contact} maxLength={100} onChange={(e) => setContact(e.target.value)} />
          <p className="cluster">
            <button
              type="button"
              disabled={busy || name.trim() === ''}
              onClick={() => {
                onSave({ ...(name.trim() !== row.name ? { name: name.trim() } : {}), ...(contact.trim() !== (row.contact ?? '') ? { contact: contact.trim() } : {}) });
                setEditing(false);
              }}
            >
              Save
            </button>
            <button type="button" className="btn-ghost" onClick={() => { setName(row.name ?? ''); setContact(row.contact ?? ''); setEditing(false); }}>Cancel</button>
          </p>
        </div>
      ) : (
        <p className="cluster">
          <button type="button" className="btn-sm" onClick={() => setEditing(true)} aria-label={`Edit ${row.name}`}>Edit</button>
          {onToggle && (
            <button type="button" className="btn-sm" disabled={busy} onClick={onToggle} aria-label={`${off ? 'Switch on' : 'Switch off'} ${row.name}`}>
              {off ? 'Switch on' : 'Switch off'}
            </button>
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
  list: (q: { query: string; cursor?: string }) => Promise<PersonPage>;
  create: (b: { name: string; contact?: string }) => Promise<Person>;
  update: (id: string, version: number, b: PersonEdit & { active?: boolean }) => Promise<Person>;
  canAdd: boolean;
  canSwitch: boolean;
  searchable?: boolean;
}) {
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const [message, setMessage] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [contact, setContact] = useState('');
  const [adding, setAdding] = useState(false);
  const [search, setSearch] = useState('');
  const rows = useInfiniteQuery({
    queryKey: ['retail', 'catalogue', queryKey, search],
    queryFn: ({ pageParam }) => list({ query: search, ...(pageParam ? { cursor: pageParam } : {}) }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
  });
  const people = (rows.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const done = (text: string) => {
    setMessage(text);
    show(text);
    void queryClient.invalidateQueries({ queryKey: ['retail'] });
  };
  const add = useMutation({
    mutationFn: () => create({ name: name.trim(), ...(contact.trim() ? { contact: contact.trim() } : {}) }),
    onMutate: () => setMessage(null),
    onSuccess: (row) => { setName(''); setContact(''); setAdding(false); done(`Added the ${noun} "${row.name}".`); },
  });
  const change = useMutation({
    mutationFn: (v: { id: string; version: number; body: PersonEdit & { active?: boolean }; text: (row: Person) => string }) => update(v.id, v.version, v.body).then((row) => ({ row, text: v.text })),
    onMutate: () => setMessage(null),
    onSuccess: ({ row, text }) => done(text(row)),
  });
  return (
    <>
      <BackToCatalogue />
      {note && <p className="hint">{note}</p>}
      {canAdd && (
        <AddPanel label={`Add a ${noun}`} open={adding} onOpen={() => setAdding(true)} onClose={() => { setAdding(false); setName(''); setContact(''); }}>
        <form onSubmit={(e) => { e.preventDefault(); if (name.trim()) add.mutate(); }}>
          <label htmlFor={`new-${queryKey}-name`}>Name</label>
          <input id={`new-${queryKey}-name`} value={name} maxLength={200} onChange={(e) => setName(e.target.value)} autoComplete="off" />
          <label htmlFor={`new-${queryKey}-contact`}>Phone or other contact (optional)</label>
          <input id={`new-${queryKey}-contact`} value={contact} maxLength={100} onChange={(e) => setContact(e.target.value)} autoComplete="off" />
          <button type="submit" className="rt-primary" disabled={add.isPending || name.trim() === ''}>{add.isPending ? 'Adding' : `Add ${noun}`}</button>
        </form>
        <Problem error={add.error} />
        </AddPanel>
      )}
      {message && <Success>{message}</Success>}
      <Problem error={change.error} />
      {rows.isPending && <p className="loading">Loading</p>}
      <Problem error={rows.error} />
      {searchable && (
        <>
          <label htmlFor={`search-${queryKey}`}>Search by name</label>
          <input id={`search-${queryKey}`} type="search" value={search} onChange={(e) => setSearch(e.target.value)} autoComplete="off" />
        </>
      )}
      {rows.data && people.length === 0 && <p className="empty-state">{search ? `No ${plural} match that name.` : `No ${plural} yet.`}</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {people.map((r) => (
          <PersonRow
            key={r.id}
            row={r}
            busy={change.isPending}
            onSave={(edit) => change.mutate({ id: r.id ?? '', version: r.version ?? 1, body: edit, text: (x) => `Saved the changes to "${x.name}".` })}
            {...(canSwitch
              ? {
                  onToggle: () =>
                    change.mutate({
                      id: r.id ?? '',
                      version: r.version ?? 1,
                      body: { active: 'active' in r && r.active === false },
                      text: (x) => ('active' in x && x.active === false
                        ? `"${x.name}" is switched off. It stays on past restocks; new restocks cannot choose it.`
                        : `"${x.name}" is switched on again.`),
                    }),
                }
              : {})}
          />
        ))}
      </ul>
      {rows.hasNextPage && <button type="button" onClick={() => void rows.fetchNextPage()} disabled={rows.isFetchingNextPage}>{rows.isFetchingNextPage ? 'Loading' : `Show more ${plural}`}</button>}
      {toast}
    </>
  );
}

function SuppliersPage() {
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

function BuyersPage() {
  return (
    <Gate screen="buyers" title="Credit buyers">
      <PeopleList
        note="What each buyer owes is counted over all the branches you may read, whichever branch is chosen."
        noun="credit buyer" plural="credit buyers" queryKey="buyers"
        list={(q) => retail.listCustomerPage(q)} create={(b) => retail.createCustomer(b)}
        update={(id, v, b) => retail.updateCustomer(id, v, b)}
        canAdd canSwitch={false} searchable
      />
    </Gate>
  );
}

export const SuppliersRoute = createLazyRoute('/staff/retail/catalogue/suppliers')({ component: SuppliersPage });
export const BuyersRoute = createLazyRoute('/staff/retail/catalogue/buyers')({ component: BuyersPage });
