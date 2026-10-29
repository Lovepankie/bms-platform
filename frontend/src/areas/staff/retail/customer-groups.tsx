import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { retail, type CustomerGroup, type PriceList } from '../../../api/retail';
import { AddPanel } from './catalogue';
import { ScreenLink, addOn } from './screen-link';
import { Gate, Problem, changeFailureText, requireVersion } from './ui';
import { WORDS } from '../../../app/glossary';
import { EmptyChoice, showsEmptyChoice } from '../../../app/ui/EmptyChoice';
import { useOptionalStaff, useStaff } from '../context';

// Customer groups (ADR-033, issue #195): customers that share a price list. A buyer's own list wins over the list of
// the group; a buyer is put in a group on the Credit buyers page. A group is switched off, never deleted. Each group
// names the list its buyers are priced from, or none.

export function GroupCard({ group, lists, listsLoad, busy, onSave, extra }: {
  group: CustomerGroup;
  /** More under the group's line, such as links to its buyers and its price list. */
  extra?: ReactNode;
  lists: PriceList[];
  /** The read of the price lists: a failed one is said with Try again, never as "No price lists yet." */
  listsLoad?: { isError: boolean; refetch: () => unknown; data?: PriceList[] };
  busy?: boolean;
  onSave: (body: { name: string; price_list_id?: string; active: boolean }) => void;
}) {
  // A card drawn outside the staff layout (a test) offers no way to the Price lists screen.
  const me = useOptionalStaff()?.me;
  const choosable = lists.filter((l) => l.active || l.id === group.price_list_id);
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
      {extra}
      {editing ? (
        <div className="stack">
          <label htmlFor={`group-name-${group.id}`}>Name</label>
          <input id={`group-name-${group.id}`} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
          {!(listsLoad ? showsEmptyChoice(listsLoad, listsLoad.data && choosable) : choosable.length === 0) ? (
            <>
              <label htmlFor={`group-list-${group.id}`}>Price list</label>
              <select id={`group-list-${group.id}`} value={listId} onChange={(e) => setListId(e.target.value)}>
                <option value="">No price list</option>
                {choosable.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
              </select>
            </>
          ) : (
            <EmptyChoice id={`group-list-${group.id}`} label="Price list" load={listsLoad} text="No price lists yet." ask="Ask the owner to add a price list first."
              add={me ? addOn(me, 'priceLists', 'Add a price list', '/staff/retail/price-lists') : null} />
          )}
          <p className="cluster">
            <BusyButton busy={busy ?? false} type="button" disabled={name.trim() === ''} onClick={() => { save(group.active !== false, name.trim(), listId); setEditing(false); }}>Save</BusyButton>
            <button type="button" className="btn-ghost" onClick={() => { setName(group.name ?? ''); setListId(group.price_list_id ?? ''); setEditing(false); }}>Cancel</button>
          </p>
        </div>
      ) : (
        <p className="cluster">
          <button type="button" className="btn-sm" onClick={() => setEditing(true)} aria-label={`Edit ${group.name}`}>Edit</button>
          <BusyButton busy={busy ?? false} type="button" className="btn-sm" onClick={() => save(off)} aria-label={`${off ? 'Switch on' : 'Switch off'} ${group.name}`}>{off ? 'Switch on' : 'Switch off'}</BusyButton>
        </p>
      )}
    </li>
  );
}

export function CustomerGroups() {
  const { me } = useStaff();
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
    meta: { success: 'Customer group added' },
  });
  const change = useMutation({
    mutationFn: (v: { group: CustomerGroup; body: { name: string; price_list_id?: string; active: boolean } }) => retail.updateCustomerGroup(v.group.id ?? '', requireVersion(v.group.version), v.body),
    onMutate: () => setFailure(null),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['retail'] }),
    onError: (error) => setFailure(changeFailureText(error, queryClient)),
    meta: {
      success: (_: unknown, v: { group: CustomerGroup; body: { active: boolean } }) => (v.body.active === (v.group.active !== false) ? 'Changes saved' : v.body.active ? 'Group switched on' : 'Group switched off'),
      // A switch on or off can be taken back from the toast (#682): the same change the other way, on the version it made.
      undo: (r: CustomerGroup, v: { group: CustomerGroup; body: { name: string; price_list_id?: string; active: boolean } }) =>
        v.body.active === (v.group.active !== false) ? null : () => retail.updateCustomerGroup(r.id ?? '', requireVersion(r.version), { ...v.body, active: !v.body.active }),
    },
  });
  return (
    <Gate screen="customerGroups" title="Customer groups">
      <p data-tour="customer-groups-help">Put customers that share prices in a group, then give the group a price list. A customer's own price list wins over the group's.</p>
      <div data-tour="customer-groups-add"><AddPanel label="Add a customer group" open={adding} onOpen={() => setAdding(true)} onClose={() => { setAdding(false); setName(''); setListId(''); create.reset(); }}>
        <form onSubmit={(e) => { e.preventDefault(); if (name.trim()) create.mutate(); }} data-tour="customer-group-new">
          <label htmlFor="new-group-name">Name</label>
          <input id="new-group-name" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} autoComplete="off" />
          {showsEmptyChoice(lists, lists.data?.filter((l) => l.active)) ? (
            <EmptyChoice id="new-group-list" label="Price list (optional)" load={lists} text="No price lists yet." ask="Ask the owner to add a price list first."
              add={addOn(me, 'priceLists', 'Add a price list', '/staff/retail/price-lists')} />
          ) : (
            <>
              <label htmlFor="new-group-list">Price list (optional)</label>
              <select id="new-group-list" value={listId} onChange={(e) => setListId(e.target.value)}>
                <option value="">No price list</option>
                {(lists.data ?? []).filter((l) => l.active).map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
              </select>
            </>
          )}
          <BusyButton busy={create.isPending} type="submit" className="rt-primary" notReady={name.trim() === ''}>Add group</BusyButton>
          <Problem error={create.error} />
        </form>
      </AddPanel></div>
      <Problem error={failure && new Error(failure)} />
      {groups.isPending && <p className="loading">Loading</p>}
      <Problem error={groups.error} />
      {groups.data && groups.data.length === 0 && <p className="empty-state">No customer groups yet.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }} data-tour="customer-groups-list">
        {(groups.data ?? []).map((g) => (
          <GroupCard
            key={g.id}
            group={g}
            lists={lists.data ?? []}
            listsLoad={lists}
            busy={change.isPending}
            onSave={(body) => change.mutate({ group: g, body })}
            extra={(
              <p className="cluster">
                <ScreenLink screen="buyers" to="/staff/retail/catalogue/buyers" label={`See the customers who owe in ${g.name}`}>{WORDS.customersWhoOwe}</ScreenLink>
                {g.price_list && <ScreenLink screen="priceLists" to="/staff/retail/price-lists" label={`See the price list ${g.price_list}`}>Price lists</ScreenLink>}
              </p>
            )}
          />
        ))}
      </ul>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/customer-groups')({ component: CustomerGroups });
