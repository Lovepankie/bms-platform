import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { RETAIL_CURRENCY, businessToday, retail, type PriceList, type PriceListItem } from '../../../api/retail';
import { formatMinor } from '../../../components/money';
import { useStaff } from '../context';
import { AddPanel } from './catalogue';
import { STATUS_LABEL, belowCostHint, buildItemRequest, emptyItemForm, fromQty, itemProblem, unitChoices, withProduct, type ItemForm } from './price-list-state';
import { Gate, Note, ProductPicker, Problem, Success, changeFailureText, requireVersion } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useComeBack } from '../../../app/ui/EmptyChoice';

// Price lists (ADR-033, issue #195; docs/specs/trade-capabilities.md section 3). A list holds the price of an item in a
// unit (its base unit or a pack) from a quantity and for a date range. The retail price stays the item's own and is
// never copied here. A price is added, never edited: a new open ended price takes over from the current one on its first
// day, an open price is ended on a day, and what was recorded stays. A list is switched off, never deleted. Prices show
// to everyone who can use the list; the warning for a price not above cost is shown only with retail.profit.read.

const money = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY);

export function ListCard({ list, selected, busy, onOpen, onChange }: {
  list: PriceList;
  selected: boolean;
  busy?: boolean;
  onOpen: () => void;
  onChange: (body: { name?: string; strict?: boolean; active?: boolean }) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(list.name ?? '');
  const [pressed, setPressed] = useState<'name' | 'strict' | 'active' | null>(null);
  const off = list.active === false;
  return (
    <li className="rt-card" data-testid="price-list-card">
      <p>
        <strong>{list.name}</strong>{' '}
        {off && <span className="badge badge-warning">Switched off</span>}{' '}
        {list.strict && <span className="badge">Strict</span>}
      </p>
      <p className="hint">{list.current_items ?? 0} prices in force or coming. {list.strict ? 'An item missing from this list cannot be sold to its customers.' : 'An item missing from this list is sold at the retail price.'}</p>
      {editing ? (
        <div className="stack">
          <label htmlFor={`list-name-${list.id}`}>Name</label>
          <input id={`list-name-${list.id}`} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
          <p className="cluster">
            <BusyButton type="button" busy={busy === true && pressed === 'name'} disabled={busy || name.trim() === ''} onClick={() => { setPressed('name'); onChange({ name: name.trim() }); setEditing(false); }}>Save</BusyButton>
            <button type="button" className="btn-ghost" onClick={() => { setName(list.name ?? ''); setEditing(false); }}>Cancel</button>
          </p>
        </div>
      ) : (
        <p className="cluster">
          <button type="button" className="btn-sm" aria-pressed={selected} onClick={onOpen} aria-label={`Prices in ${list.name}`}>{selected ? 'Hide prices' : 'Prices'}</button>
          <button type="button" className="btn-sm" onClick={() => setEditing(true)} aria-label={`Rename ${list.name}`}>Rename</button>
          <BusyButton type="button" className="btn-sm" busy={busy === true && pressed === 'strict'} disabled={busy} onClick={() => { setPressed('strict'); onChange({ strict: !list.strict }); }} aria-label={`${list.strict ? 'Stop requiring' : 'Require'} a price for every item in ${list.name}`}>
            {list.strict ? 'Allow retail fallback' : 'Make strict'}
          </BusyButton>
          <BusyButton type="button" className="btn-sm" busy={busy === true && pressed === 'active'} disabled={busy} onClick={() => { setPressed('active'); onChange({ active: off }); }} aria-label={`${off ? 'Switch on' : 'Switch off'} ${list.name}`}>{off ? 'Switch on' : 'Switch off'}</BusyButton>
        </p>
      )}
    </li>
  );
}

export function ItemRow({ item, showCost, busy, ending, onEnd }: { item: PriceListItem; showCost: boolean; busy?: boolean; ending?: boolean; onEnd: (item: PriceListItem) => void }) {
  return (
    <tr data-testid="price-item">
      <td>{item.description} <span className="hint">({item.code})</span></td>
      <td>{item.unit}</td>
      <td>{fromQty(item.min_qty)}</td>
      <td className="num">{money(item.price_minor ?? 0)}{showCost && item.below_cost && <> <span className="badge badge-warning">Not above cost</span></>}</td>
      <td>{item.valid_from}</td>
      <td>{item.valid_to ?? 'No end'}</td>
      <td>{STATUS_LABEL[item.status ?? 'current'] ?? item.status}</td>
      <td>
        {!item.valid_to && item.status !== 'ended' && (
          <BusyButton type="button" className="btn-sm" busy={ending === true} disabled={busy} onClick={() => onEnd(item)} aria-label={`End the price of ${item.description} in ${item.unit}`}>End</BusyButton>
        )}
      </td>
    </tr>
  );
}

/** The form for one new price: an item found with the usual search, the unit, the quantity break, the price and the dates. */
export function AddPriceForm({ today, busy, error, onSubmit, onCancel }: {
  today: string;
  busy?: boolean;
  error?: unknown;
  onSubmit: (form: ItemForm) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<ItemForm>(emptyItemForm(today));
  const problem = itemProblem(form, today);
  const warn = belowCostHint(form);
  const units = form.product ? unitChoices(form.product) : [];
  return (
    <section className="rt-card" aria-label="Add a price" data-tour="price-list-add">
      <ProductPicker id="price-item-search" showPrice showStock={false} addLabel="Choose" onAdd={(p) => setForm((f) => withProduct(f, p))} />
      {form.product && (
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            if (!problem && !busy) onSubmit(form);
          }}
        >
          <p><strong>{form.product.description}</strong> ({form.product.code}), retail price {money(form.product.sell_minor ?? 0)} per {form.product.unit}</p>
          <label htmlFor="price-unit">Price for</label>
          {/* empty: the item's own unit is always the first choice. */}
          <select id="price-unit" value={form.unitId} onChange={(e) => setForm({ ...form, unitId: e.target.value })}>
            {units.map((u) => <option key={u.id} value={u.id}>{u.label}</option>)}
          </select>
          <label htmlFor="price-min">From quantity (leave empty for any quantity)</label>
          <input id="price-min" inputMode="decimal" value={form.minQty} onChange={(e) => setForm({ ...form, minQty: e.target.value })} />
          <label htmlFor="price-amount">Price per unit</label>
          <input id="price-amount" inputMode="numeric" value={form.price} onChange={(e) => setForm({ ...form, price: e.target.value })} aria-invalid={form.price !== '' && problem !== null} />
          <label htmlFor="price-from">First day</label>
          <input id="price-from" type="date" value={form.validFrom} min={today} onChange={(e) => setForm({ ...form, validFrom: e.target.value })} />
          <label htmlFor="price-to">Last day (leave empty for no end)</label>
          <input id="price-to" type="date" value={form.validTo} onChange={(e) => setForm({ ...form, validTo: e.target.value })} />
          {warn && <p role="alert" className="rt-flag">This price is not above what the item costs. It can be saved, but a seller needs permission to sell below cost.</p>}
          {problem && form.price !== '' && <Note>{problem}</Note>}
          <Problem error={error} />
          <p className="cluster">
            <BusyButton type="submit" className="rt-primary" busy={busy === true} notReady={problem !== null}>Save price</BusyButton>
            <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>
          </p>
        </form>
      )}
      {!form.product && <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>}
    </section>
  );
}

function Items({ list, showCost }: { list: PriceList; showCost: boolean }) {
  const queryClient = useQueryClient();
  const search = useLiveSearch();
  const [ended, setEnded] = useState(false);
  const [adding, setAdding] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const query = search.term;
  const today = businessToday();
  const rows = useInfiniteQuery({
    queryKey: ['retail', 'price-list-items', list.id, query, ended],
    queryFn: ({ pageParam, signal }) => retail.listPriceListItems(list.id ?? '', { query, includeEnded: ended, ...(pageParam ? { cursor: pageParam } : {}), signal }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
    placeholderData: keepPreviousData,
  });
  const finding = rows.isPlaceholderData || (rows.isFetching && !rows.isFetchingNextPage);
  const items = (rows.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['retail'] });
  const add = useMutation({
    mutationFn: (form: ItemForm) => retail.addPriceListItem(list.id ?? '', buildItemRequest(form)),
    onSuccess: (item) => {
      setAdding(false);
      setMessage(`Saved the price of ${item.description} in ${item.unit}.${item.status === 'scheduled' ? ` It starts on ${item.valid_from}.` : ''}`);
      refresh();
    },
  });
  const end = useMutation({
    mutationFn: (item: PriceListItem) => retail.endPriceListItem(list.id ?? '', item.id ?? '', today),
    onMutate: () => { setMessage(null); setFailure(null); },
    onSuccess: (item) => { setMessage(`The price of ${item.description} in ${item.unit} ends today.`); refresh(); },
    onError: (error) => setFailure(changeFailureText(error, queryClient)),
  });
  return (
    <section aria-label={`Prices in ${list.name}`} data-tour="price-list-items">
      <h2>{list.name}</h2>
      {list.active === false && <Note>This list is switched off, so sales do not use it.</Note>}
      {adding ? (
        <AddPriceForm today={today} busy={add.isPending} error={add.error} onSubmit={(f) => add.mutate(f)} onCancel={() => { setAdding(false); add.reset(); }} />
      ) : (
        <p><button type="button" onClick={() => { setAdding(true); setMessage(null); }}>Add a price</button></p>
      )}
      {message && <Success>{message}</Success>}
      <Problem error={failure && new Error(failure)} />
      <SearchBox id="price-search" label="Search prices by item name or code" search={search} fetching={finding} count={rows.data ? items.length : undefined} more={rows.hasNextPage} />
      <label><input type="checkbox" checked={ended} onChange={(e) => setEnded(e.target.checked)} /> Show ended prices</label>
      {rows.isPending && <p className="loading">Loading</p>}
      <Problem error={rows.error} />
      {rows.data && items.length === 0 && (query ? <NoResults term={query} onClear={search.clear} noun="prices" /> : <p className="empty-state">No prices in this list yet. Items without a price are sold at the retail price.</p>)}
      <SearchResults fetching={finding}>
      {items.length > 0 && (
        <div className="table-wrap" tabIndex={0}>
          <table>
            <thead>
              <tr><th>Item</th><th>Unit</th><th>Quantity</th><th className="num">Price</th><th>First day</th><th>Last day</th><th>Status</th><th><span className="visually-hidden">Actions</span></th></tr>
            </thead>
            <tbody>
              {items.map((i) => <ItemRow key={i.id} item={i} showCost={showCost} busy={end.isPending} ending={end.isPending && end.variables?.id === i.id} onEnd={(x) => end.mutate(x)} />)}
            </tbody>
          </table>
        </div>
      )}
      </SearchResults>
      {rows.hasNextPage && <button type="button" onClick={() => void rows.fetchNextPage()} disabled={rows.isFetchingNextPage}>{rows.isFetchingNextPage ? 'Loading' : 'Show more prices'}</button>}
    </section>
  );
}

export function PriceLists() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const back = useComeBack();
  const [open, setOpen] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [strict, setStrict] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const lists = useQuery({ queryKey: ['retail', 'price-lists'], queryFn: () => retail.listPriceLists() });
  const create = useMutation({
    mutationFn: () => retail.createPriceList({ name: name.trim(), strict }),
    onSuccess: (l) => {
      setName(''); setStrict(false); setAdding(false); setOpen(l.id ?? null); void queryClient.invalidateQueries({ queryKey: ['retail'] });
      // Opened from an empty list on a form (issue #619): what was added takes the person back to that form.
      back?.();
    },
  });
  const change = useMutation({
    mutationFn: (v: { list: PriceList; body: { name?: string; strict?: boolean; active?: boolean } }) => retail.updatePriceList(v.list.id ?? '', requireVersion(v.list.version), v.body),
    onMutate: () => setFailure(null),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['retail'] }),
    onError: (error) => setFailure(changeFailureText(error, queryClient)),
  });
  const selected = (lists.data ?? []).find((l) => l.id === open);
  return (
    <Gate screen="priceLists" title="Price lists" wide>
      <p data-tour="price-list-help">A price list gives some customers their own prices, with a lower price for larger quantities. An item that is not in a list is sold at its retail price.</p>
      <AddPanel label="Add a price list" open={adding} onOpen={() => setAdding(true)} onClose={() => { setAdding(false); setName(''); create.reset(); }}>
        <form onSubmit={(e) => { e.preventDefault(); if (name.trim()) create.mutate(); }} data-tour="price-list-new">
          <label htmlFor="new-list-name">Name</label>
          <input id="new-list-name" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} autoComplete="off" />
          <label><input type="checkbox" checked={strict} onChange={(e) => setStrict(e.target.checked)} /> Strict: an item that is not in this list cannot be sold to its customers</label>
          <BusyButton type="submit" className="rt-primary" busy={create.isPending} notReady={name.trim() === ''}>Add price list</BusyButton>

          <Problem error={create.error} />
        </form>
      </AddPanel>
      <Problem error={failure && new Error(failure)} />
      {lists.isPending && <p className="loading">Loading</p>}
      <Problem error={lists.error} />
      {lists.data && lists.data.length === 0 && <p className="empty-state">No price lists yet.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }} data-tour="price-list-lists">
        {(lists.data ?? []).map((l) => (
          <ListCard key={l.id} list={l} selected={open === l.id} busy={change.isPending} onOpen={() => setOpen(open === l.id ? null : (l.id ?? null))} onChange={(body) => change.mutate({ list: l, body })} />
        ))}
      </ul>
      {selected && <Items key={selected.id} list={selected} showCost={(me.permissions ?? []).includes('retail.profit.read')} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/price-lists')({ component: PriceLists });
