import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type CustomerGroup, type PriceList } from '../../../api/retail';
import { AddPanel } from './catalogue';
import { Gate, Problem, changeFailureText, requireVersion } from './ui';

// Customer groups (ADR-033, issue #195): customers that share a price list. A buyer's own list wins over the list of
// the group; a buyer is put in a group on the Credit buyers page. A group is switched off, never deleted. Each group
// names the list its buyers are priced from, or none.

export function GroupCard({ group, lists, busy, onSave }: {
  group: CustomerGroup;
  lists: PriceList[];
  busy?: boolean;
  onSave: (body: { name: string; price_list_id?: string; active: boolean }) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(group.name ?? '');
  const [listId, setListId] = useState(group.price_list_id ?? '');
  const off = group.active === false;
  const save = (active: boolean, nextName = group.name ?? '', nextList = group.price_list_id ?? '') =>
    onSave({ name: nextName, ...(nextList ? { price_list_id: nextList } : {}), active });
  return (
    <li className="rt-card" data-testid="customer-group-card">
      <p>
        <strong>{group.name}</strong> {off && <span className="badge badge-warning">Switched off</span>}
      </p>
      <p className="hint">
        {group.price_list ? <>Priced from the list {group.price_list}.</> : 'No price list: its customers pay the retail price.'} {group.customer_count ?? 0} customers.
      </p>
      {editing ? (
        <div className="stack">
          <label htmlFor={`group-name-${group.id}`}>Name</label>
          <input id={`group-name-${group.id}`} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
          <label htmlFor={`group-list-${group.id}`}>Price list</label>
          <select id={`group-list-${group.id}`} value={listId} onChange={(e) => setListId(e.target.value)}>
            <option value="">No price list</option>
            {lists.filter((l) => l.active || l.id === group.price_list_id).map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
          </select>
          <p className="cluster">
            <button type="button" disabled={busy || name.trim() === ''} onClick={() => { save(group.active !== false, name.trim(), listId); setEditing(false); }}>Save</button>
            <button type="button" className="btn-ghost" onClick={() => { setName(group.name ?? ''); setListId(group.price_list_id ?? ''); setEditing(false); }}>Cancel</button>
          </p>
        </div>
      ) : (
        <p className="cluster">
          <button type="button" className="btn-sm" onClick={() => setEditing(true)} aria-label={`Edit ${group.name}`}>Edit</button>
          <button type="button" className="btn-sm" disabled={busy} onClick={() => save(off)} aria-label={`${off ? 'Switch on' : 'Switch off'} ${group.name}`}>{off ? 'Switch on' : 'Switch off'}</button>
        </p>
      )}
    </li>
  );
}

export function CustomerGroups() {
  const queryClient = useQueryClient();
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [listId, setListId] = useState('');
  const [failure, setFailure] = useState<string | null>(null);
  const groups = useQuery({ queryKey: ['retail', 'customer-groups'], queryFn: () => retail.listCustomerGroups() });
  const lists = useQuery({ queryKey: ['retail', 'price-lists'], queryFn: () => retail.listPriceLists() });
  const create = useMutation({
    mutationFn: () => retail.createCustomerGroup({ name: name.trim(), ...(listId ? { price_list_id: listId } : {}) }),
    onSuccess: () => { setName(''); setListId(''); setAdding(false); void queryClient.invalidateQueries({ queryKey: ['retail'] }); },
  });
  const change = useMutation({
    mutationFn: (v: { group: CustomerGroup; body: { name: string; price_list_id?: string; active: boolean } }) => retail.updateCustomerGroup(v.group.id ?? '', requireVersion(v.group.version), v.body),
    onMutate: () => setFailure(null),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['retail'] }),
    onError: (error) => setFailure(changeFailureText(error, queryClient)),
  });
  return (
    <Gate screen="customerGroups" title="Customer groups">
      <p>Put customers that share prices in a group, then give the group a price list. A customer's own price list wins over the group's.</p>
      <AddPanel label="Add a customer group" open={adding} onOpen={() => setAdding(true)} onClose={() => { setAdding(false); setName(''); setListId(''); create.reset(); }}>
        <form onSubmit={(e) => { e.preventDefault(); if (name.trim()) create.mutate(); }} data-tour="customer-group-new">
          <label htmlFor="new-group-name">Name</label>
          <input id="new-group-name" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} autoComplete="off" />
          <label htmlFor="new-group-list">Price list (optional)</label>
          <select id="new-group-list" value={listId} onChange={(e) => setListId(e.target.value)}>
            <option value="">No price list</option>
            {(lists.data ?? []).filter((l) => l.active).map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
          </select>
          <button type="submit" className="rt-primary" disabled={create.isPending || name.trim() === ''}>{create.isPending ? 'Adding' : 'Add group'}</button>
          <Problem error={create.error} />
        </form>
      </AddPanel>
      <Problem error={failure && new Error(failure)} />
      {groups.isPending && <p className="loading">Loading</p>}
      <Problem error={groups.error} />
      {groups.data && groups.data.length === 0 && <p className="empty-state">No customer groups yet.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {(groups.data ?? []).map((g) => (
          <GroupCard key={g.id} group={g} lists={lists.data ?? []} busy={change.isPending} onSave={(body) => change.mutate({ group: g, body })} />
        ))}
      </ul>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/customer-groups')({ component: CustomerGroups });
