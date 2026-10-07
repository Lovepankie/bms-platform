import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { RETAIL_CURRENCY, RETAIL_ZONE, retail, type PriceChange, type Product } from '../../../api/retail';
import { formatMinor, parseMinor } from '../../../components/money';
import { useStaff } from '../context';
import { BackToCatalogue } from './catalogue';
import { Gate, Problem, Success, money, useProfitAccess, useToast } from './ui';

// Items (#146): list with search, category and active filters; add and edit an item's code,
// description, category, unit and whether it is on sale; and change prices only through the price
// form, which keeps the history. Cost shows only with retail.profit.read (the server never sends it
// otherwise), and a price at or below cost is refused by the server with the plain message.

const PRICE = 'retail.price.edit';

/** A moment as "6 Oct 2026, 14:05" in the business's time zone, not the browser's. */
export function showWhen(iso: string | undefined): string {
  if (!iso) return '';
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) return iso;
  const d = new Intl.DateTimeFormat('en-GB', { timeZone: RETAIL_ZONE, day: 'numeric', month: 'short', year: 'numeric' }).format(when);
  const t = new Intl.DateTimeFormat('en-GB', { timeZone: RETAIL_ZONE, hour: '2-digit', minute: '2-digit', hour12: false }).format(when);
  return `${d}, ${t}`;
}

const SOURCES: Record<string, string> = { initial: 'First price', manual: 'Changed by hand', purchase: 'Restock', import: 'Import' };

/** One price change in words: the old and new selling price, and the cost too when the server sent it. */
export function ChangeLine({ change }: { change: PriceChange }) {
  const sell = change.old_sell_minor === undefined ? `Selling price ${money(change.new_sell_minor ?? 0)}` : `Selling price ${money(change.old_sell_minor)} to ${money(change.new_sell_minor ?? 0)}`;
  const costKnown = change.new_cost_minor !== undefined;
  const cost = !costKnown ? '' : change.old_cost_minor === undefined ? `, cost ${money(change.new_cost_minor ?? 0)}` : `, cost ${money(change.old_cost_minor)} to ${money(change.new_cost_minor ?? 0)}`;
  return (
    <li className="rt-card">
      <p><strong>{showWhen(change.at)}</strong> <span className="badge badge-info">{SOURCES[change.source ?? ''] ?? change.source}</span></p>
      <p>{sell}{cost}</p>
      {change.reason && <p className="hint">Reason: {change.reason}</p>}
    </li>
  );
}

export function PriceHistory({ changes }: { changes: PriceChange[] }) {
  if (changes.length === 0) return <p className="empty-state">No price changes yet.</p>;
  return <ul style={{ listStyle: 'none', padding: 0 }}>{[...changes].reverse().map((c) => <ChangeLine key={c.id} change={c} />)}</ul>;
}

export function ProductCard({ product, showCost, canPrice, onEdit, onPrice }: {
  product: Product;
  showCost: boolean;
  canPrice: boolean;
  onEdit: () => void;
  onPrice: () => void;
}) {
  return (
    <li className="rt-card">
      <p>
        <strong>{product.description}</strong> {product.active === false && <span className="badge badge-warning">Switched off</span>}
      </p>
      <p className="hint">{product.code}, {product.category}, per {product.unit}</p>
      <p>
        Sells at <strong>{money(product.sell_minor ?? 0)}</strong>
        {showCost && product.cost_minor !== undefined && <>, cost {money(product.cost_minor)}</>}
      </p>
      <p className="cluster">
        <button type="button" className="btn-sm" onClick={onEdit} aria-label={`Edit ${product.description}`}>Edit</button>
        {canPrice && <button type="button" className="btn-sm" onClick={onPrice} aria-label={`Change the price of ${product.description}`}>Change price</button>}
      </p>
    </li>
  );
}

export function ProductForm({ product, onSaved, onCancel }: { product?: Product; onSaved: (text: string) => void; onCancel: () => void }) {
  const profit = useProfitAccess();
  const queryClient = useQueryClient();
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits() });
  const [code, setCode] = useState(product?.code ?? '');
  const [description, setDescription] = useState(product?.description ?? '');
  const [categoryId, setCategoryId] = useState(product?.category_id ?? '');
  const [unitId, setUnitId] = useState(product?.unit_id ?? '');
  const [active, setActive] = useState(product?.active !== false);
  const [sell, setSell] = useState('');
  const [cost, setCost] = useState('0');
  const sellMinor = parseMinor(sell, RETAIL_CURRENCY);
  const costMinor = parseMinor(cost, RETAIL_CURRENCY);
  // A switched-off category or unit is offered only when it is the item's own.
  const categoryChoices = (categories.data ?? []).filter((c) => c.active !== false || c.id === product?.category_id);
  const unitChoices = (units.data ?? []).filter((u) => u.active !== false || u.id === product?.unit_id);
  const complete = code.trim() !== '' && description.trim() !== '' && categoryId !== '' && unitId !== '' && (product ? true : sellMinor !== null && costMinor !== null);
  const save = useMutation({
    mutationFn: () =>
      product
        ? retail.updateProduct(product.id ?? '', product.version ?? 1, { code: code.trim(), description: description.trim(), category_id: categoryId, unit_id: unitId, active })
        : retail.createProduct({ code: code.trim(), description: description.trim(), category_id: categoryId, unit_id: unitId, sell_minor: sellMinor ?? 0, cost_minor: profit ? (costMinor ?? 0) : 0 }),
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved(product ? `Saved the changes to "${saved.description}".` : `Added the item "${saved.description}" (${saved.code}).`);
    },
  });
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (complete) save.mutate(); }}>
      <h2>{product ? 'Change this item' : 'Add an item'}</h2>
      <label htmlFor="item-code">Code</label>
      <input id="item-code" value={code} maxLength={40} onChange={(e) => setCode(e.target.value)} autoComplete="off" />
      <label htmlFor="item-description">Description</label>
      <input id="item-description" value={description} maxLength={300} onChange={(e) => setDescription(e.target.value)} autoComplete="off" />
      <label htmlFor="item-category">Category</label>
      <select id="item-category" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
        <option value="">Choose a category</option>
        {categoryChoices.map((c) => <option key={c.id} value={c.id}>{c.name}{c.active === false ? ' (switched off)' : ''}</option>)}
      </select>
      <label htmlFor="item-unit">Unit</label>
      <select id="item-unit" value={unitId} onChange={(e) => setUnitId(e.target.value)}>
        <option value="">Choose a unit</option>
        {unitChoices.map((u) => <option key={u.id} value={u.id}>{u.name}{u.active === false ? ' (switched off)' : ''}</option>)}
      </select>
      {product ? (
        <>
          <label htmlFor="item-active">
            <input id="item-active" type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> On sale (clear this to switch the item off)
          </label>
          <p className="hint">To change the price, use Change price on the list.</p>
        </>
      ) : (
        <>
          <label htmlFor="item-sell">Selling price ({RETAIL_CURRENCY})</label>
          <input id="item-sell" inputMode="numeric" value={sell} onChange={(e) => setSell(e.target.value)} aria-invalid={sell.trim() !== '' && sellMinor === null} autoComplete="off" />
          {profit ? (
            <>
              <label htmlFor="item-cost">Cost price ({RETAIL_CURRENCY})</label>
              <input id="item-cost" inputMode="numeric" value={cost} onChange={(e) => setCost(e.target.value)} aria-invalid={costMinor === null} autoComplete="off" />
              <p className="hint">Leave 0 if you do not know it yet. A restock sets the cost.</p>
            </>
          ) : (
            <p className="hint">The cost is set when you record a restock.</p>
          )}
        </>
      )}
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={!complete || save.isPending}>{save.isPending ? 'Saving' : product ? 'Save changes' : 'Add item'}</button>
      <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>
    </form>
  );
}

/** The early hint of the price form: only when cost is known to the caller. The server refuses a price at or below cost itself. */
export function belowCostHint(sellMinor: number | null, costMinor: number | null): string | null {
  if (sellMinor === null || costMinor === null || costMinor <= 0 || sellMinor > costMinor) return null;
  return `The selling price should be above the cost, ${formatMinor(costMinor, RETAIL_CURRENCY)}.`;
}

export function PriceForm({ product, onSaved, onCancel }: { product: Product; onSaved: (text: string) => void; onCancel: () => void }) {
  const profit = useProfitAccess();
  const queryClient = useQueryClient();
  const [sell, setSell] = useState(String(product.sell_minor ?? 0));
  const [cost, setCost] = useState(String(product.cost_minor ?? 0));
  const [reason, setReason] = useState('');
  const sellMinor = parseMinor(sell, RETAIL_CURRENCY);
  const costMinor = profit ? parseMinor(cost, RETAIL_CURRENCY) : null;
  const history = useQuery({ queryKey: ['retail', 'price-history', product.id], queryFn: () => retail.priceHistory(product.id ?? '') });
  const sellChanged = sellMinor !== null && sellMinor !== product.sell_minor;
  const costChanged = profit && costMinor !== null && costMinor !== product.cost_minor;
  const valid = sellMinor !== null && (!profit || costMinor !== null) && reason.trim() !== '' && (sellChanged || costChanged);
  const hint = belowCostHint(sellMinor, profit ? costMinor : null);
  const save = useMutation({
    mutationFn: () => retail.editPrices(product.id ?? '', product.version ?? 1, {
      ...(sellChanged ? { sell_minor: sellMinor ?? 0 } : {}),
      ...(costChanged ? { cost_minor: costMinor ?? 0 } : {}),
      reason: reason.trim(),
    }),
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved(`The price of "${saved.description}" is now ${money(saved.sell_minor ?? 0)}.`);
    },
  });
  return (
    <>
      <form onSubmit={(e) => { e.preventDefault(); if (valid) save.mutate(); }}>
        <h2>Change the price of {product.description}</h2>
        <p>Now sells at <strong>{money(product.sell_minor ?? 0)}</strong>{profit && product.cost_minor !== undefined && <>, cost {money(product.cost_minor)}</>}.</p>
        <label htmlFor="price-sell">New selling price ({RETAIL_CURRENCY})</label>
        <input id="price-sell" inputMode="numeric" value={sell} onChange={(e) => setSell(e.target.value)} aria-invalid={sellMinor === null} autoComplete="off" />
        {profit && (
          <>
            <label htmlFor="price-cost">New cost price ({RETAIL_CURRENCY})</label>
            <input id="price-cost" inputMode="numeric" value={cost} onChange={(e) => setCost(e.target.value)} aria-invalid={costMinor === null} autoComplete="off" />
          </>
        )}
        {hint && <p role="note" className="alert alert-warning">{hint}</p>}
        <label htmlFor="price-reason">Why is the price changing?</label>
        <input id="price-reason" value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} autoComplete="off" />
        <Problem error={save.error} />
        <button type="submit" className="rt-primary" disabled={!valid || save.isPending}>{save.isPending ? 'Saving' : 'Save the new price'}</button>
        <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>
      </form>
      <h2>Price history</h2>
      {history.isPending && <p className="loading">Loading</p>}
      <Problem error={history.error} />
      {history.data && <PriceHistory changes={history.data} />}
    </>
  );
}

type View = { mode: 'list' } | { mode: 'new' } | { mode: 'edit' | 'price'; id: string };

function Editing({ id, mode, onSaved, onCancel }: { id: string; mode: 'edit' | 'price'; onSaved: (text: string) => void; onCancel: () => void }) {
  const product = useQuery({ queryKey: ['retail', 'product', id], queryFn: () => retail.getProduct(id), gcTime: 0 });
  if (product.isPending) return <p className="loading">Loading</p>;
  if (!product.data) return <Problem error={product.error} />;
  return mode === 'edit' ? <ProductForm product={product.data} onSaved={onSaved} onCancel={onCancel} /> : <PriceForm product={product.data} onSaved={onSaved} onCancel={onCancel} />;
}

export function ProductList({ onEdit, onPrice, onAdd }: { onEdit: (id: string) => void; onPrice: (id: string) => void; onAdd: () => void }) {
  const { me } = useStaff();
  const profit = useProfitAccess();
  const [query, setQuery] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [activeFilter, setActiveFilter] = useState<'active' | 'off' | 'all'>('active');
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const active = activeFilter === 'all' ? undefined : activeFilter === 'active';
  const page = useInfiniteQuery({
    queryKey: ['retail', 'catalogue', 'products', query, categoryId, activeFilter],
    queryFn: ({ pageParam }) => retail.listCatalogue({ query, categoryId, ...(active === undefined ? {} : { active }), ...(pageParam ? { cursor: pageParam } : {}) }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
  });
  const items = (page.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const canPrice = (me.permissions ?? []).includes(PRICE);
  return (
    <>
      <BackToCatalogue />
      <button type="button" className="rt-primary" onClick={onAdd}>Add an item</button>
      <label htmlFor="items-search">Search by name, code or category</label>
      <input id="items-search" type="search" value={query} onChange={(e) => setQuery(e.target.value)} autoComplete="off" />
      <label htmlFor="items-category">Category</label>
      <select id="items-category" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
        <option value="">All categories</option>
        {(categories.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select>
      <label htmlFor="items-active">Show</label>
      <select id="items-active" value={activeFilter} onChange={(e) => setActiveFilter(e.target.value as 'active' | 'off' | 'all')}>
        <option value="active">Items on sale</option>
        <option value="off">Items switched off</option>
        <option value="all">All items</option>
      </select>
      {page.isPending && <p className="loading">Loading</p>}
      <Problem error={page.error} />
      {page.data && items.length === 0 && <p className="empty-state">No items found.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {items.map((p) => (
          <ProductCard key={p.id} product={p} showCost={profit} canPrice={canPrice} onEdit={() => onEdit(p.id ?? '')} onPrice={() => onPrice(p.id ?? '')} />
        ))}
      </ul>
      {page.hasNextPage && <button type="button" onClick={() => void page.fetchNextPage()} disabled={page.isFetchingNextPage}>{page.isFetchingNextPage ? 'Loading' : 'Show more items'}</button>}
    </>
  );
}

function ProductsPage() {
  const [view, setView] = useState<View>({ mode: 'list' });
  const [message, setMessage] = useState<string | null>(null);
  const { show, toast } = useToast();
  const back = () => setView({ mode: 'list' });
  const saved = (text: string) => { setMessage(text); show(text); back(); };
  return (
    <Gate screen="products" title="Items">
      {message && view.mode === 'list' && <Success>{message}</Success>}
      {view.mode === 'list' && <ProductList onAdd={() => { setMessage(null); setView({ mode: 'new' }); }} onEdit={(id) => { setMessage(null); setView({ mode: 'edit', id }); }} onPrice={(id) => { setMessage(null); setView({ mode: 'price', id }); }} />}
      {view.mode === 'new' && <ProductForm onSaved={saved} onCancel={back} />}
      {(view.mode === 'edit' || view.mode === 'price') && <Editing id={view.id} mode={view.mode} onSaved={saved} onCancel={back} />}
      {toast}
    </Gate>
  );
}

export const ProductsRoute = createLazyRoute('/staff/retail/catalogue/products')({ component: ProductsPage });
