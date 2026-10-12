import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { retail, type Customer } from '../../../api/retail';
import { useStaff } from '../context';
import { PRICELIST_MANAGE, PRICE_LISTS, capabilityOn, holds } from './permissions';
import { Problem, changeFailureText, requireVersion } from './ui';

// A credit buyer's customer group and own price list (ADR-033, issue #195). Shown on the Credit buyers page only while
// the price_lists feature is on and only to retail.pricelist.manage: the list a buyer is on is what that buyer pays. The buyer's own list wins
// over the group's list; with neither, the buyer pays the retail price.

export function BuyerPricing({ row }: { row: Customer }) {
  const { me } = useStaff();
  // The lists and groups are read with retail.pricelist.manage (they are negotiated prices); without it a buyer shows nothing here.
  const on = capabilityOn(me, 'retail', PRICE_LISTS) && holds(me, PRICELIST_MANAGE);
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [groupId, setGroupId] = useState(row.customer_group_id ?? '');
  const [listId, setListId] = useState(row.price_list_id ?? '');
  const groups = useQuery({ queryKey: ['retail', 'customer-groups'], queryFn: () => retail.listCustomerGroups(), enabled: on });
  const lists = useQuery({ queryKey: ['retail', 'price-lists'], queryFn: () => retail.listPriceLists(), enabled: on });
  const save = useMutation({
    mutationFn: () => retail.setCustomerPricing(row.id ?? '', requireVersion(row.version), { ...(groupId ? { customer_group_id: groupId } : {}), ...(listId ? { price_list_id: listId } : {}) }),
    onSuccess: () => { setEditing(false); void queryClient.invalidateQueries({ queryKey: ['retail'] }); },
  });
  if (!on) return null;
  const group = (groups.data ?? []).find((g) => g.id === row.customer_group_id);
  const list = (lists.data ?? []).find((l) => l.id === row.price_list_id);
  return (
    <div data-tour="buyer-pricing" data-testid="buyer-pricing">
      <p className="hint">
        {group ? `Group: ${group.name}. ` : 'No group. '}
        {list ? `Own price list: ${list.name}.` : group?.price_list ? `Priced from the group's list ${group.price_list}.` : 'Pays the retail price.'}
      </p>
      {!editing && <p><button type="button" className="btn-sm" onClick={() => setEditing(true)} aria-label={`Change the prices of ${row.name}`}>Change prices</button></p>}
      {editing && (
        <div className="stack">
          <label htmlFor={`buyer-group-${row.id}`}>Customer group</label>
          <select id={`buyer-group-${row.id}`} value={groupId} onChange={(e) => setGroupId(e.target.value)}>
            <option value="">No group</option>
            {(groups.data ?? []).filter((g) => g.active || g.id === row.customer_group_id).map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select>
          <label htmlFor={`buyer-list-${row.id}`}>Own price list (wins over the group's)</label>
          <select id={`buyer-list-${row.id}`} value={listId} onChange={(e) => setListId(e.target.value)}>
            <option value="">No own list</option>
            {(lists.data ?? []).filter((l) => l.active || l.id === row.price_list_id).map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
          </select>
          <Problem error={save.error && new Error(changeFailureText(save.error, queryClient))} />
          <p className="cluster">
            <button type="button" disabled={save.isPending} onClick={() => save.mutate()}>Save</button>
            <button type="button" className="btn-ghost" onClick={() => { setGroupId(row.customer_group_id ?? ''); setListId(row.price_list_id ?? ''); setEditing(false); save.reset(); }}>Cancel</button>
          </p>
        </div>
      )}
    </div>
  );
}
