import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useRef, useState, type ReactNode } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { RETAIL_CURRENCY, RETAIL_ZONE, retail, type Category, type PriceChange, type Product, type SimilarProduct } from '../../../api/retail';
import { Field, FieldsNeededNote, focusFirstMissing } from '../../../app/ui/Field';
import { formatMinor, parseMinor } from '../../../components/money';
import { useCrumbTail } from '../../../components/breadcrumbs';
import { useStaff } from '../context';
import { ExistsPanel, SimilarPanel, existingOf, type ExistingItem } from './catalogue-names';
import { NameHintsPanel, useNameHints } from './name-hints';
import { ItemCodeLine } from './new-item-here';
import { PackEditor } from './catalogue-packs';
import { BarcodeEditor } from './catalogue-barcodes';
import { MakeFamilyPanel, SIZES_JOIN, SIZES_NEW, VariantNote } from './catalogue-variants';
import { ItemPhoto, PhotoEditor, shownPhoto } from './item-photo';
import { ScreenLink } from './screen-link';
import { StockMinimumEditor } from './stock-minimum';
import { GroupControls, GroupedCards, useCategoryGroups } from './category-groups';
import { CategoryPicker } from './category-picker';
import { ScreenToast } from './toast';
import { Gate, Problem, Success, money, useProfitAccess, useToast } from './ui';
import { WORDS } from '../../../app/glossary';
import { Hint } from '../../../app/ui/Hint';

// Items (#146): list with search, category and active filters; add and edit an item's
// description, category, unit and whether it is on sale (issue #493: added inside a category, the item
// takes that category and no dropdown is shown; issue #562: the app always makes the Item code, PREFIX-0001, and it is
// shown, never typed or changed); and change prices only through the price
// form, which keeps the history. Cost shows only with retail.profit.read (the server never sends it
// otherwise). A price at or below cost is saved with a warning: the price floor is enforced when a sale
// is made (price_below_cost, unless the seller holds retail.price.below_cost).

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

const SOURCES: Record<string, string> = { initial: 'First price', manual: 'Changed by hand', purchase: 'Purchase', import: 'Import' };

/** One price change in words: the old and new selling price, and the cost too when the server sent it. */
export function ChangeLine({ change }: { change: PriceChange }) {
  // The server sends null for a price the change has no earlier value of, and leaves cost out without retail.profit.read.
  const sell = change.old_sell_minor == null ? `Selling price ${money(change.new_sell_minor ?? 0)}` : `Selling price ${money(change.old_sell_minor)} to ${money(change.new_sell_minor ?? 0)}`;
  const cost = change.new_cost_minor == null
    ? ''
    : change.old_cost_minor == null
      ? `, buying price ${money(change.new_cost_minor)}`
      : change.old_cost_minor === change.new_cost_minor
        ? `, buying price stays ${money(change.new_cost_minor)}`
        : `, buying price ${money(change.old_cost_minor)} to ${money(change.new_cost_minor)}`;
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

export function ProductCard({ product, showCost, canPrice, onEdit, onPrice, extra }: {
  product: Product;
  /** More beside the buttons, such as a link to the item's stock. */
  extra?: ReactNode;
  showCost: boolean;
  canPrice: boolean;
  onEdit: () => void;
  onPrice: () => void;
}) {
  return (
    <li className="rt-card">
      <ItemPhoto photoId={shownPhoto(product)} label={product.description ?? ''} />
      <p>
        <strong>{product.description}</strong> {product.active === false && <span className="badge badge-warning">Inactive</span>}
      </p>
      <p className="hint">{product.code}, {product.category}, per {product.unit}</p>
      <VariantNote product={product} />
      <p>
        Sells at <strong>{money(product.sell_minor ?? 0)}</strong>
        {showCost && product.cost_minor !== undefined && <>, last paid {money(product.cost_minor)}</>}
      </p>
      <p className="cluster">
        <button type="button" className="btn-sm" onClick={onEdit} aria-label={`Edit ${product.description}`}>Edit</button>
        {canPrice && <button type="button" className="btn-sm" onClick={onPrice} aria-label={`Change the price of ${product.description}`}>Change price</button>}
        {extra}
      </p>
    </li>
  );
}

/** The plain warning shown while an item that still has stock in a branch you can see is marked inactive (issue #342). */
export function inactiveWarning(stockOnHand: string | undefined, branches: number | undefined): string {
  if (!branches) return '';
  return `This item still has stock in the branches you can see (${stockOnHand} in all). Inactive items cannot be sold, bought or moved, so that stock stays put until you correct it on the Stock page or switch the item on again.`;
}

export function ProductForm({ product, category: fixed, onSaved, onCancel, onOpen }: {
  product?: Product;
  /** Issue #493: the category the item is added in; the form then has no category dropdown. */
  category?: Category;
  onSaved: (text: string) => void;
  onCancel: () => void;
  onOpen?: (id: string) => void;
}) {
  const { me } = useStaff();
  const profit = useProfitAccess();
  const queryClient = useQueryClient();
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits() });
  const [description, setDescription] = useState(product?.description ?? '');
  const [categoryId, setCategoryId] = useState(product?.category_id ?? fixed?.id ?? '');
  const [showErrors, setShowErrors] = useState(false);
  const [unitId, setUnitId] = useState(product?.unit_id ?? '');
  const [active, setActive] = useState(product?.active !== false);
  const canManage = (me.permissions ?? []).includes('retail.catalogue.manage');
  // Issue #384: a size or colour of an item takes its name, category and unit from the item; it changes its own size and colour.
  const variant = product?.variant;
  const [size, setSize] = useState(variant?.size ?? '');
  const [colour, setColour] = useState(variant?.colour ?? '');
  const [sell, setSell] = useState('');
  const [cost, setCost] = useState('0');
  const sellMinor = parseMinor(sell, RETAIL_CURRENCY);
  const costMinor = parseMinor(cost, RETAIL_CURRENCY);
  // A switched-off category or unit is offered only when it is the item's own.
  const categoryChoices = (categories.data ?? []).filter((c) => c.active !== false || c.id === product?.category_id);
  const unitChoices = (units.data ?? []).filter((u) => u.active !== false || u.id === product?.unit_id);
  const complete = description.trim() !== '' && categoryId !== '' && unitId !== '' && (product ? true : sellMinor !== null && costMinor !== null)
    && (!variant || size.trim() !== '' || colour.trim() !== '');
  const chosen = fixed ?? (categories.data ?? []).find((c) => c.id === categoryId);
  const movedFrom = product && !variant && categoryId !== product.category_id ? product.category : null;
  // The name is kept once per unit (issue #352): a close name is a warning, the same name is refused, an administrator may add it anyway.
  const [similar, setSimilar] = useState<SimilarProduct[] | null>(null);
  const [exists, setExists] = useState<ExistingItem | null>(null);
  const [acceptedKey, setAcceptedKey] = useState('');
  const nameKey = `${description.trim()}|${unitId}`;
  const nameChanged = !variant && (!product || description.trim() !== product.description || unitId !== product.unit_id);
  const canAddAnyway = (me.permissions ?? []).includes('core.settings.manage');
  // A second press before the first request has started must not send another: the ref answers at once, the mutation state a render later.
  const sending = useRef(false);
  const save = useMutation({
    mutationFn: (duplicateReason?: string) => {
      const reason = duplicateReason ? { duplicate_reason: duplicateReason } : {};
      if (product && variant) return retail.updateProduct(product.id ?? '', product.version ?? 1, { active, variant_size: size.trim(), variant_colour: colour.trim() });
      return product
        ? retail.updateProduct(product.id ?? '', product.version ?? 1, { description: description.trim(), category_id: categoryId, unit_id: unitId, active, ...reason })
        : retail.createProduct({ description: description.trim(), category_id: categoryId, unit_id: unitId, sell_minor: sellMinor ?? 0, cost_minor: profit ? (costMinor ?? 0) : 0, ...reason });
    },
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved(product ? `Saved the changes to "${saved.description}".` : `Added the item "${saved.description}" (${saved.code}).`);
    },
    onError: (error) => {
      const found = existingOf(error);
      if (found) setExists({ ...found, mayAddAnyway: found.mayAddAnyway && canAddAnyway });
    },
    onSettled: () => { sending.current = false; },
  });
  const check = useMutation({
    mutationFn: () => retail.similarProducts({ description: description.trim(), unitId, ...(product ? { excludeId: product.id } : {}) }),
    onSuccess: (found) => {
      const same = (found.exact ?? [])[0];
      if (same) setExists({ id: same.id ?? '', code: same.code ?? '', name: same.description ?? '', mayAddAnyway: canAddAnyway });
      else if ((found.similar ?? []).length > 0) setSimilar(found.similar ?? []);
      else {
        save.mutate(undefined);
        return;
      }
      sending.current = false;
    },
    onError: () => { sending.current = false; },
  });
  const busy = save.isPending || check.isPending;
  // Issue #353: while a new item's name is typed, the items it may already be and the category of items like it.
  const hints = useNameHints(description, !product && canManage);
  const form = useRef<HTMLFormElement>(null);
  const submit = () => {
    if (!complete) {
      // The send button stays enabled (docs/ui-rules.md rule 10): a press shows what is missing and goes to it.
      setShowErrors(true);
      if (form.current) focusFirstMissing(form.current);
      return;
    }
    if (busy || sending.current) return;
    sending.current = true;
    if (nameChanged && acceptedKey !== nameKey) check.mutate();
    else save.mutate(undefined);
  };
  const forget = () => { setExists(null); setSimilar(null); save.reset(); };
  return (
    <form ref={form} noValidate onSubmit={(e) => { e.preventDefault(); submit(); }}>
      <h2>{product ? 'Change this item' : fixed ? `Add an item to ${fixed.name}` : 'Add an item'}</h2>
      <FieldsNeededNote />
      {fixed ? (
        <p data-tour="item-category-fixed">Category: <strong>{fixed.name}</strong></p>
      ) : !variant && (
        // Hatibu, 23 Oct 2026: the category comes first, before the description, so the code can follow from it.
        <CategoryPicker
          id="item-category"
          categories={categoryChoices}
          value={categoryId}
          onChange={setCategoryId}
          required
          error={categoryId === '' ? 'Choose a category.' : null}
          showError={showErrors}
          hint={movedFrom ? `Moving it from ${movedFrom} keeps its item code.` : undefined}
        />
      )}
      {product
        ? <p id="item-code" data-testid="item-code">{WORDS.itemCode}: <strong>{product.code}</strong>. It never changes.</p>
        : <ItemCodeLine id="item-code" category={chosen} />}
      {variant ? (
        <>
          <p>
            <strong>{product?.description}</strong>. {product?.category}, per {product?.unit}.{' '}
            <Link to={`/staff/retail/catalogue/products/sizes/${variant.family_id ?? ''}` as never}>Change the name or category of {variant.family_name}</Link>
          </p>
          <p className="hint">This is one size or colour of {variant.family_name}. Its name, category and unit come from the item, so all of its sizes and colours stay together.</p>
          <label htmlFor="item-size">Size</label>
          <input id="item-size" value={size} maxLength={30} onChange={(e) => setSize(e.target.value)} autoComplete="off" />
          <label htmlFor="item-colour">Colour or print</label>
          <input id="item-colour" value={colour} maxLength={30} onChange={(e) => setColour(e.target.value)} autoComplete="off" />
        </>
      ) : (
        <>
          <Field
            id="item-description"
            label="Description"
            required
            error={description.trim() === '' ? 'Type what the item is.' : null}
            showError={showErrors}
            value={description}
            maxLength={300}
            onChange={(e) => { setDescription(e.target.value); forget(); }}
            autoComplete="off"
          />
          {!product && !exists && !similar && (
            <NameHintsPanel id="item" hints={hints} categoryId={categoryId} categoryFixed={fixed !== undefined}
              choosable={(c) => categoryChoices.some((x) => x.id === c)} openLabel="Open" onOpen={(id) => (onOpen ?? (() => undefined))(id)} onUseCategory={setCategoryId} />
          )}
          <Field
            id="item-unit"
            label={WORDS.countedIn}
            required
            error={unitId === '' ? 'Choose a unit.' : null}
            showError={showErrors}
            control={(props) => (
              <select {...props} value={unitId} onChange={(e) => { setUnitId(e.target.value); forget(); }}>
                <option value="">Choose a unit</option>
                {unitChoices.map((u) => <option key={u.id} value={u.id}>{u.name}{u.active === false ? ' (switched off)' : ''}</option>)}
              </select>
            )}
          />
        </>
      )}
      {product ? (
        <>
          {canManage && (
            <>
              <label htmlFor="item-inactive">
                <input id="item-inactive" type="checkbox" checked={!active} onChange={(e) => setActive(!e.target.checked)} /> Inactive
              </label>
              <Hint full>An inactive item is not offered when selling, buying or moving stock, and leaves the stock list unless you ask for inactive items. It is never deleted: its history and reports stay.</Hint>
              {!active && (product.stock_branches ?? 0) > 0 && (
                <p role="note" className="alert alert-warning">{inactiveWarning(product.stock_on_hand, product.stock_branches)}</p>
              )}
            </>
          )}
          <p className="hint">To change the price, use Change price on the list.</p>
          <PackEditor product={product} />
          <BarcodeEditor product={product} />
          <StockMinimumEditor productId={product.id ?? ''} unit={product.unit} />
          {canManage && (
            <PhotoEditor
              inputId={product.id ?? 'item'}
              photoId={product.photo_id}
              shownId={shownPhoto(product)}
              label={product.description ?? ''}
              onUpload={(file) => retail.setProductPhoto(product.id ?? '', file)}
              onClear={() => retail.clearProductPhoto(product.id ?? '')}
            />
          )}
          <MakeFamilyPanel product={product} />
        </>
      ) : (
        <>
          {profit ? (
            <>
              <label htmlFor="item-cost">Buying price ({RETAIL_CURRENCY})</label>
              <input id="item-cost" inputMode="numeric" value={cost} onChange={(e) => setCost(e.target.value)} aria-invalid={costMinor === null} autoComplete="off" />
              <Hint>What you pay the supplier for one. Leave 0 if you do not know it yet; buying stock sets it.</Hint>
            </>
          ) : null}
          <Field
            id="item-sell"
            label={`Selling price (${RETAIL_CURRENCY})`}
            required
            error={sellMinor === null ? `Type the price you sell one at, in ${RETAIL_CURRENCY}.` : null}
            showError={showErrors}
            inputMode="numeric"
            value={sell}
            onChange={(e) => setSell(e.target.value)}
            autoComplete="off"
          />
          {!profit && <p className="hint">The buying price is set when you buy stock.</p>}
        </>
      )}
      {exists && (
        <ExistsPanel
          item={exists}
          canAddAnyway={exists.mayAddAnyway}
          busy={save.isPending}
          onOpen={(id) => (onOpen ?? (() => undefined))(id)}
          onAddAnyway={(reason) => { if (sending.current) return; sending.current = true; save.mutate(reason); }}
          onChangeName={forget}
        />
      )}
      {similar && !exists && (
        <SimilarPanel
          items={similar}
          busy={save.isPending}
          onOpen={(id) => (onOpen ?? (() => undefined))(id)}
          onSaveAnyway={() => { if (sending.current) return; sending.current = true; setAcceptedKey(nameKey); setSimilar(null); save.mutate(undefined); }}
          onChangeName={forget}
        />
      )}
      {!exists && <Problem error={save.error ?? check.error} />}
      <BusyButton busy={busy} type="submit" className="rt-primary" disabled={exists !== null || similar !== null}>{product ? 'Save changes' : 'Add item'}</BusyButton>
      <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>
    </form>
  );
}

/**
 * The price form's warning, only when the caller can see cost. The server accepts such a price (the rule is
 * enforced on a sale, `price_below_cost`, unless the seller holds retail.price.below_cost), so this only warns.
 */
export function belowCostHint(sellMinor: number | null, costMinor: number | null): string | null {
  if (sellMinor === null || costMinor === null || costMinor <= 0 || sellMinor > costMinor) return null;
  return `This price is not above the cost, ${formatMinor(costMinor, RETAIL_CURRENCY)}. A sale at this price is refused unless the seller may sell below cost.`;
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
        <p>Now sells at <strong>{money(product.sell_minor ?? 0)}</strong>{profit && product.cost_minor !== undefined && <>, last paid {money(product.cost_minor)}</>}.</p>
        {profit && (
          <>
            <label htmlFor="price-cost">Last paid ({RETAIL_CURRENCY})</label>
            <input id="price-cost" inputMode="numeric" value={cost} onChange={(e) => setCost(e.target.value)} aria-invalid={costMinor === null} autoComplete="off" />
            <Hint>The last price you paid for one, to help set the selling price. Profit uses the average buying price of the stock in each shop, which every purchase keeps up to date.</Hint>
          </>
        )}
        <label htmlFor="price-sell">New selling price ({RETAIL_CURRENCY})</label>
        <input id="price-sell" inputMode="numeric" value={sell} onChange={(e) => setSell(e.target.value)} aria-invalid={sellMinor === null} autoComplete="off" />
        {hint && <p role="note" className="alert alert-warning">{hint}</p>}
        <label htmlFor="price-reason">Why is the price changing?</label>
        <input id="price-reason" value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} autoComplete="off" />
        <Problem error={save.error} />
        <BusyButton busy={save.isPending} type="submit" className="rt-primary" notReady={!valid}>Save the new price</BusyButton>
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

export function Editing({ id, mode, onSaved, onCancel, onOpen }: { id: string; mode: 'edit' | 'price'; onSaved: (text: string) => void; onCancel: () => void; onOpen: (id: string) => void }) {
  const product = useQuery({ queryKey: ['retail', 'product', id], queryFn: () => retail.getProduct(id), gcTime: 0 });
  useCrumbTail(product.data?.description, onCancel);
  if (product.isPending) return <p className="loading">Loading</p>;
  if (!product.data) return <Problem error={product.error} />;
  return mode === 'edit' ? <ProductForm key={product.data.id} product={product.data} onSaved={onSaved} onCancel={onCancel} onOpen={onOpen} /> : <PriceForm product={product.data} onSaved={onSaved} onCancel={onCancel} />;
}

export function ProductList({ onEdit, onPrice, onAdd }: { onEdit: (id: string) => void; onPrice: (id: string) => void; onAdd: () => void }) {
  const { me } = useStaff();
  const profit = useProfitAccess();
  const search = useLiveSearch();
  const query = search.term;
  const [categoryId, setCategoryId] = useState('');
  const [activeFilter, setActiveFilter] = useState<'active' | 'off' | 'all'>('active');
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const active = activeFilter === 'all' ? undefined : activeFilter === 'active';
  const page = useInfiniteQuery({
    queryKey: ['retail', 'catalogue', 'products', query, categoryId, activeFilter],
    queryFn: ({ pageParam, signal }) => retail.listCatalogue({ query, categoryId, ...(active === undefined ? {} : { active }), ...(pageParam ? { cursor: pageParam } : {}), signal }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
    placeholderData: keepPreviousData,
  });
  const finding = page.isPlaceholderData || (page.isFetching && !page.isFetchingNextPage);
  const items = (page.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const canPrice = (me.permissions ?? []).includes(PRICE);
  const g = useCategoryGroups({ screen: 'items', items, categoryOf: (p) => p.category, resetKey: [query, categoryId, activeFilter].join('|'), searching: query !== '', pending: page.isPlaceholderData });
  const card = (p: Product) => <ProductCard key={p.id} product={p} showCost={profit} canPrice={canPrice} onEdit={() => onEdit(p.id ?? '')} onPrice={() => onPrice(p.id ?? '')} extra={<ScreenLink screen="stock" to="/staff/retail/stock" label={`See the stock of ${p.description}`}>Stock</ScreenLink>} />;
  return (
    <>
      <p className="cluster">
        <button type="button" onClick={onAdd} data-tour="product-add">Add an item</button>
        <Link className="btn" to={SIZES_NEW as never} data-tour="product-add-sizes">Add an item with sizes and colours</Link>
        <Link className="btn" to={SIZES_JOIN as never} data-tour="product-join-sizes">Join items as sizes of one item</Link>
      </p>
      <div data-tour="product-search"><SearchBox id="items-search" label="Search by name, code or category" search={search} fetching={finding} count={page.data ? items.length : undefined} more={page.hasNextPage} /></div>
      <div className="rt-row" data-tour="product-filters">
        <div>
          <CategoryPicker id="items-category" categories={categories.data ?? []} value={categoryId} onChange={setCategoryId} noneLabel="All categories" allowAdd={false} />
        </div>
        <div>
          <label htmlFor="items-active">Show</label>
          <select id="items-active" value={activeFilter} onChange={(e) => setActiveFilter(e.target.value as 'active' | 'off' | 'all')}>
            <option value="active">On sale</option>
            <option value="off">Inactive</option>
            <option value="all">All items, active and inactive</option>
          </select>
        </div>
      </div>
      {page.isPending && <p className="loading">Loading</p>}
      <Problem error={page.error} />
      {page.data && items.length === 0 && (query ? <NoResults term={query} onClear={search.clear} noun="items" /> : <p className="empty-state">No items found.</p>)}
      <div data-tour="product-list">
        <SearchResults fetching={finding}>
          {items.length > 0 && <GroupControls state={g} paged={page.hasNextPage} />}
          {g.active ? <GroupedCards state={g} renderItem={card} /> : <ul style={{ listStyle: 'none', padding: 0 }}>{items.map(card)}</ul>}
        </SearchResults>
      </div>
      {page.hasNextPage && <button type="button" onClick={() => void page.fetchNextPage()} disabled={page.isFetchingNextPage || page.isPlaceholderData}>{page.isFetchingNextPage ? 'Loading' : 'Show more items'}</button>}
    </>
  );
}

export function ProductsPage() {
  const [view, setView] = useState<View>({ mode: 'list' });
  const [message, setMessage] = useState<string | null>(null);
  const { show, toast } = useToast();
  const back = () => setView({ mode: 'list' });
  const open = (id: string) => { setMessage(null); setView({ mode: 'edit', id }); };
  const saved = (text: string) => { setMessage(text); show(text, 'success'); back(); };
  return (
    <Gate screen="products" title="Items">
      {message && view.mode === 'list' && <Success>{message}</Success>}
      {view.mode === 'list' && <ProductList onAdd={() => { setMessage(null); setView({ mode: 'new' }); }} onEdit={(id) => { setMessage(null); setView({ mode: 'edit', id }); }} onPrice={(id) => { setMessage(null); setView({ mode: 'price', id }); }} />}
      <ScreenToast.Provider value={show}>
        {view.mode === 'new' && <ProductForm onSaved={saved} onCancel={back} onOpen={open} />}
        {(view.mode === 'edit' || view.mode === 'price') && <Editing id={view.id} mode={view.mode} onSaved={saved} onCancel={back} onOpen={open} />}
      </ScreenToast.Provider>
      {toast}
    </Gate>
  );
}

export const ProductsRoute = createLazyRoute('/staff/retail/catalogue/products')({ component: ProductsPage });
