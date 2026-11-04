import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, useNavigate } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { RETAIL_CURRENCY, businessToday, retail, type Product, type VariantLine } from '../../../api/retail';
import { MAX_VARIANTS, planVariants, repeatedCodes, splitOptions, variantKey, variantLabel, type PlannedVariant } from '../../../api/retail-variants';
import { parseMinor } from '../../../components/money';
import { useStaff } from '../context';
import { duplicateReasonOf, existingOf } from './catalogue-names';
import { CategoryPicker } from './category-picker';
import { ScreenToast } from './toast';
import { Gate, ProductPicker, Problem, money, useProfitAccess, useToast } from './ui';
import { WORDS } from '../../../app/glossary';
import { Hint } from '../../../app/ui/Hint';
import { HelpTip } from '../../../app/ui/HelpTip';
import { ItemCombobox } from '../../../app/ui/ItemCombobox';
import { SuccessScreen } from '../../../app/ui/SuccessScreen';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { icons } from '../../../components/icons';
import { UnitPicker } from './unit-picker';

// Items with sizes and colours (issue #384, ADR-051): the owner of a baby clothes shop chooses the sizes and the prints once and
// every combination becomes a variant with its own code, price and stock. A variant is an item of its own everywhere else (sale,
// restock, stock-take, move stock, returns), named with its size and colour. This file holds the screen that makes a new item with
// sizes, the panel that gives an existing item sizes, the preview both show, and the hooks that tell the grouped lists whether to
// offer the "Size and colour" level. The item's own page is catalogue-family.tsx.

export const SIZES_NEW = '/staff/retail/catalogue/products/sizes/new';
export const SIZES_JOIN = '/staff/retail/catalogue/products/sizes/join';
export const familyPath = (id: string) => `/staff/retail/catalogue/products/sizes/${id}`;

/** True when the business has an item with sizes and colours: the sales lists then offer the "Size and colour" level. */
export function useSalesVariantLevel(): boolean {
  const today = businessToday();
  const levels = useQuery({
    queryKey: ['retail', 'sales', 'summary', 'variant-level'],
    queryFn: ({ signal }) => retail.salesSummary({ level: 'day', chain: ['day'], from: today, to: today, signal }),
    staleTime: 300_000,
    retry: 1,
  });
  return (levels.data?.available_levels ?? []).includes('variant');
}

/** The same for Past restocks, read from the restocks summary so a person who cannot read sales is asked nothing of sales. */
export function useRestockVariantLevel(enabled = true): boolean {
  const today = businessToday();
  const levels = useQuery({
    queryKey: ['retail', 'purchases', 'summary', 'variant-level'],
    queryFn: ({ signal }) => retail.purchasesSummary({ level: 'day', chain: ['day'], from: today, to: today, signal }),
    enabled,
    staleTime: 300_000,
    retry: 1,
  });
  return (levels.data?.available_levels ?? []).includes('variant');
}

/** A variant's size and colour on its card, with the way to the item's page. */
export function VariantNote({ product }: { product: Product }) {
  const v = product.variant;
  if (!v?.family_id) return null;
  return (
    <p className="hint">
      <span className="badge badge-info">{variantLabel(v.size, v.colour)}</span>{' '}
      <Link to={familyPath(v.family_id) as never} aria-label={`All sizes and colours of ${v.family_name ?? 'this item'}`}>All sizes and colours</Link>
    </p>
  );
}

/**
 * The refusal of a name another item of the same unit has (ADR-044, one level up), in the owner's words; an administrator may
 * use it anyway with a reason, which `onAnyway` sends.
 */
export function NameTaken({ error, busy, onAnyway }: { error: unknown; busy: boolean; onAnyway: (reason: string) => void }) {
  const { me } = useStaff();
  const [reason, setReason] = useState('');
  const found = existingOf(error);
  if (!found) return <Problem error={error} />;
  const admin = found.mayAddAnyway && (me.permissions ?? []).includes('core.settings.manage');
  const why = duplicateReasonOf(reason);
  return (
    <div role="alert" className="alert alert-warning" data-testid="item-name-taken">
      <p>{found.name} ({found.code}) has this name already. Choose another name{admin ? ', or say why both are needed' : ''}.</p>
      {admin && (
        <>
          <label htmlFor="item-name-reason">Why both are needed</label>
          <input id="item-name-reason" value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} autoComplete="off" />
          <BusyButton busy={busy} type="button" disabled={why === null} onClick={() => why && onAnyway(why)}>Use this name anyway</BusyButton>
        </>
      )}
    </div>
  );
}

/**
 * One row of the preview as the owner edits it: the selling price and (with cost read) the cost, as typed. The code is the
 * app's, shown and never typed (issue #562).
 */
export interface PreviewRow extends PlannedVariant {
  sellText: string;
  costText: string;
}

type PriceEdit = Partial<Pick<PreviewRow, 'sellText' | 'costText'>>;

const rowsOf = (plan: PlannedVariant[]): PreviewRow[] => plan.map((p) => ({ ...p, sellText: String(p.sellMinor), costText: String(p.costMinor) }));

/** What the server is sent: every row with its own prices, so the preview the owner checked is exactly what is made. */
export function linesOf(rows: readonly PreviewRow[], profit: boolean): VariantLine[] {
  return rows.map((r) => ({
    ...(r.size ? { size: r.size } : {}),
    ...(r.colour ? { colour: r.colour } : {}),
    sell_minor: parseMinor(r.sellText, RETAIL_CURRENCY) ?? 0,
    ...(profit ? { cost_minor: parseMinor(r.costText, RETAIL_CURRENCY) ?? 0 } : {}),
  }));
}

/** Why the preview cannot be saved yet, in plain words, or null when it can. */
export function previewProblem(rows: readonly PreviewRow[]): string | null {
  if (rows.length === 0) return 'Type at least one size or one colour.';
  if (rows.length > MAX_VARIANTS) return `That makes ${rows.length}. An item has at most ${MAX_VARIANTS} sizes and colours.`;
  if (rows.some((r) => parseMinor(r.sellText, RETAIL_CURRENCY) === null)) return 'Every size and colour needs a selling price.';
  const repeated = repeatedCodes(rows);
  if (repeated.length > 0) return `Two of them would have the code ${repeated[0]}. Spell one of the sizes or colours differently in its first seven letters.`;
  return null;
}

/**
 * The list of what will be made, each row with its code and the prices the owner may change. The rows follow the sizes and
 * colours typed; a price the owner changed is kept while the same size and colour stays in the list.
 */
export function usePreview(plan: PlannedVariant[]) {
  const [edits, setEdits] = useState<Record<string, PriceEdit>>({});
  const rows = rowsOf(plan).map((r) => ({ ...r, ...(edits[variantKey(r.size, r.colour)] ?? {}) }));
  const edit = (r: PreviewRow, change: PriceEdit) =>
    setEdits((e) => ({ ...e, [variantKey(r.size, r.colour)]: { ...(e[variantKey(r.size, r.colour)] ?? {}), ...change } }));
  return { rows, edit, reset: () => setEdits({}) };
}

/** The preview; {@code codesKnown} is false while the category that makes the codes is not chosen yet. */
export function VariantPreview({ rows, profit, onEdit, codesKnown = true }: { rows: PreviewRow[]; profit: boolean; onEdit: (r: PreviewRow, change: PriceEdit) => void; codesKnown?: boolean }) {
  if (rows.length === 0) return <p className="empty-state">Type the sizes or the colours above and the list of what will be made shows here.</p>;
  return (
    <ul style={{ listStyle: 'none', padding: 0 }}>
      {rows.map((r) => {
        const label = variantLabel(r.size, r.colour);
        const id = `variant-${r.position}`;
        return (
          <li key={variantKey(r.size, r.colour)} className="rt-card">
            <p><strong>{label}</strong></p>
            <p id={`${id}-code`}>{WORDS.itemCode}: {codesKnown ? <><strong>{r.code}</strong>, made by the app</> : 'made by the app once you choose the category'}</p>
            <div className="rt-row">
              {profit && (
                <div>
                  <label htmlFor={`${id}-cost`}>Buying price ({RETAIL_CURRENCY})</label>
                  <input id={`${id}-cost`} inputMode="numeric" value={r.costText} autoComplete="off" onChange={(e) => onEdit(r, { costText: e.target.value })} aria-label={`Buying price of ${label}`} aria-invalid={parseMinor(r.costText, RETAIL_CURRENCY) === null} />
                </div>
              )}
              <div>
                <label htmlFor={`${id}-sell`}>Selling price ({RETAIL_CURRENCY})</label>
                <input id={`${id}-sell`} inputMode="numeric" value={r.sellText} autoComplete="off" onChange={(e) => onEdit(r, { sellText: e.target.value })} aria-label={`Selling price of ${label}`} aria-invalid={parseMinor(r.sellText, RETAIL_CURRENCY) === null} />
              </div>
            </div>
          </li>
        );
      })}
    </ul>
  );
}

/** The two boxes where the owner types the sizes and the colours once. */
export function OptionBoxes({ sizes, colours, onSizes, onColours, prefix }: { sizes: string; colours: string; onSizes: (v: string) => void; onColours: (v: string) => void; prefix: string }) {
  return (
    <>
      <label htmlFor={`${prefix}-sizes`}>Sizes</label>
      <input id={`${prefix}-sizes`} value={sizes} onChange={(e) => onSizes(e.target.value)} autoComplete="off" placeholder="0-3m, 3-6m, 6-12m" />
      <Hint>Separate them with commas, smallest first. Leave empty if it comes in one size.</Hint>
      <label htmlFor={`${prefix}-colours`}>Colours or prints</label>
      <input id={`${prefix}-colours`} value={colours} onChange={(e) => onColours(e.target.value)} autoComplete="off" placeholder="Blue, Pink, Stars" />
      <Hint>Separate them with commas. Leave empty if it comes in one colour.</Hint>
    </>
  );
}

/**
 * Add an item with sizes and colours: the name, category and unit, the prices, then the sizes and colours once. The codes start
 * with the category's next code, made by the app (issue #562).
 */
export function NewFamilyPage() {
  const profit = useProfitAccess();
  const { show, toast } = useToast();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits() });
  const [name, setName] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [unitId, setUnitId] = useState('');
  const [sell, setSell] = useState('');
  const [cost, setCost] = useState('0');
  const [sizes, setSizes] = useState('');
  const [colours, setColours] = useState('');
  const sellMinor = parseMinor(sell, RETAIL_CURRENCY);
  const costMinor = profit ? parseMinor(cost, RETAIL_CURRENCY) : 0;
  const chosen = (categories.data ?? []).find((c) => c.id === categoryId);
  const baseCode = chosen ? chosen.next_code ?? `${chosen.code_prefix ?? ''}-0001` : 'ITEM';
  const plan = planVariants({ baseCode, sizes: splitOptions(sizes), colours: splitOptions(colours), costMinor: costMinor ?? 0, sellMinor: sellMinor ?? 0 });
  const preview = usePreview(plan);
  const problem = previewProblem(preview.rows);
  const complete = name.trim() !== '' && categoryId !== '' && unitId !== '' && sellMinor !== null && costMinor !== null && problem === null;
  const sending = useRef(false);
  const save = useMutation({
    mutationFn: (duplicateReason?: string) => retail.createFamily({
      name: name.trim(), category_id: categoryId, unit_id: unitId, sell_minor: sellMinor ?? 0, cost_minor: costMinor ?? 0,
      sizes: splitOptions(sizes), colours: splitOptions(colours), variants: linesOf(preview.rows, profit),
      ...(duplicateReason ? { duplicate_reason: duplicateReason } : {}),
    }),
    onSuccess: (family) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      void navigate({ to: familyPath(family.id ?? '') as never });
    },
    onSettled: () => { sending.current = false; },
    meta: {
      success: (family: { name?: string; variants?: unknown[] }) => {
        const n = family?.variants?.length ?? preview.rows.length;
        return `Item ${family?.name ?? name.trim()} added with ${n} ${n === 1 ? 'size or colour' : 'sizes and colours'}`;
      },
    },
  });
  const submit = () => {
    if (!complete || save.isPending || sending.current) return;
    sending.current = true;
    save.mutate(undefined);
  };
  return (
    <Gate screen="products" title="Add an item with sizes and colours">
      <p>Use this for something you sell in several sizes, colours or prints, such as a romper in 0-3m, 3-6m and 6-12m. Each size and colour gets its own item code, made by the app from the category, and its own price and stock. You choose them once here.</p>
      <form onSubmit={(e) => { e.preventDefault(); submit(); }}>
        <div data-tour="sizes-name">
          <label htmlFor="sizes-item-name">What is it called?</label>
          <input id="sizes-item-name" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} autoComplete="off" placeholder="Baby romper" />
          <ScreenToast.Provider value={show}>
            <CategoryPicker id="sizes-item-category" categories={(categories.data ?? []).filter((c) => c.active !== false)} value={categoryId} onChange={setCategoryId} />
            <UnitPicker id="sizes-item-unit" units={(units.data ?? []).filter((u) => u.active !== false)} loading={units.data === undefined} load={units} value={unitId} onChange={setUnitId}
              error={null} showError={false} required={false} />
          </ScreenToast.Provider>
          {profit && (
            <>
              <label htmlFor="sizes-item-cost">Buying price ({RETAIL_CURRENCY})</label>
              <input id="sizes-item-cost" inputMode="numeric" value={cost} onChange={(e) => setCost(e.target.value)} autoComplete="off" aria-invalid={costMinor === null} />
              <Hint>What you pay the supplier for one. Leave 0 if you do not know it yet; buying stock sets it.</Hint>
            </>
          )}
          <label htmlFor="sizes-item-sell">Selling price ({RETAIL_CURRENCY})</label>
          <input id="sizes-item-sell" inputMode="numeric" value={sell} onChange={(e) => setSell(e.target.value)} autoComplete="off" aria-invalid={sell.trim() !== '' && sellMinor === null} />
          <Hint>Every size and colour starts at these prices. Change any of them in the list below, for example when a bigger size costs more.</Hint>
        </div>
        <div data-tour="sizes-options">
          <OptionBoxes prefix="sizes-item" sizes={sizes} colours={colours} onSizes={setSizes} onColours={setColours} />
        </div>
        <section data-tour="sizes-preview" aria-label="What will be made">
          <h2>{preview.rows.length === 0 ? 'What will be made' : `${preview.rows.length} to be made`}</h2>
          <VariantPreview rows={preview.rows} profit={profit} onEdit={preview.edit} codesKnown={chosen !== undefined} />
        </section>
        {problem && preview.rows.length > 0 && <p role="note" className="alert alert-warning">{problem}</p>}
        {save.error && <NameTaken error={save.error} busy={save.isPending} onAnyway={(reason) => save.mutate(reason)} />}
        <BusyButton busy={save.isPending} type="submit" className="rt-primary" data-tour="sizes-save" notReady={!complete}>
          {preview.rows.length > 0 ? `Make these ${preview.rows.length}` : 'Make them'}
        </BusyButton>
      </form>
      {toast}
    </Gate>
  );
}

/**
 * Give an item without sizes its sizes and colours (FR-RET-51). The stock it holds now is one of them, which the owner names; it
 * keeps its code, prices, stock and history, and the other combinations are made at its prices.
 */
export function MakeFamilyPanel({ product }: { product: Product }) {
  const { me } = useStaff();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [size, setSize] = useState('');
  const [colour, setColour] = useState('');
  const [sizes, setSizes] = useState('');
  const [colours, setColours] = useState('');
  const canManage = (me.permissions ?? []).includes('retail.catalogue.manage');
  const own = { size: size.trim() || undefined, colour: colour.trim() || undefined };
  const plan = planVariants({
    baseCode: product.code ?? '', sizes: [own.size ?? '', ...splitOptions(sizes)].filter((s) => s !== ''), colours: [own.colour ?? '', ...splitOptions(colours)].filter((s) => s !== ''),
    costMinor: product.cost_minor ?? 0, sellMinor: product.sell_minor ?? 0, existing: new Set([variantKey(own.size, own.colour)]), nextPosition: 1,
  });
  const save = useMutation({
    mutationFn: () => retail.makeFamily(product.id ?? '', { ...(own.size ? { size: own.size } : {}), ...(own.colour ? { colour: own.colour } : {}), sizes: [own.size ?? '', ...splitOptions(sizes)].filter((s) => s !== ''), colours: [own.colour ?? '', ...splitOptions(colours)].filter((s) => s !== '') }),
    onSuccess: (family) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      void navigate({ to: familyPath(family.id ?? '') as never });
    },
    meta: { success: `Sizes and colours added to ${product.description ?? 'the item'}` },
  });
  if (!canManage || product.variant) return null;
  if (!open) return <p><button type="button" onClick={() => setOpen(true)}>Give this item sizes and colours</button></p>;
  const ready = (own.size !== undefined || own.colour !== undefined) && !save.isPending;
  return (
    <section className="rt-card" aria-label="Give this item sizes and colours">
      <h3>Give this item sizes and colours</h3>
      <Hint full>The stock you hold now becomes one of them. It keeps its code, prices, stock and history. The others start with no stock, at its prices.</Hint>
      <label htmlFor="make-size">Size of the stock you hold now</label>
      <input id="make-size" value={size} maxLength={30} onChange={(e) => setSize(e.target.value)} autoComplete="off" placeholder="0-3m" />
      <label htmlFor="make-colour">Colour or print of the stock you hold now</label>
      <input id="make-colour" value={colour} maxLength={30} onChange={(e) => setColour(e.target.value)} autoComplete="off" placeholder="Blue" />
      <OptionBoxes prefix="make" sizes={sizes} colours={colours} onSizes={setSizes} onColours={setColours} />
      <p>{plan.length === 0 ? 'Nothing else will be made yet.' : `Also made, at ${money(product.sell_minor ?? 0)}: ${plan.map((p) => `${variantLabel(p.size, p.colour)} (${p.code})`).join(', ')}.`}</p>
      <Problem error={save.error} />
      <BusyButton busy={save.isPending} type="button" className="rt-primary" notReady={!ready} onClick={() => save.mutate()}>Give it sizes and colours</BusyButton>
      <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>Cancel</button>
    </section>
  );
}

/** One item kept apart until now, and the size and colour it becomes; null is the size the app suggests, not changed yet. */
interface JoinRow {
  product: Product;
  size: string | null;
  colour: string;
}

/**
 * The name and the sizes the join suggests (issue #707): the words every picked item shares at its start and its end are the
 * item's name, and what is left of each one is its size ("Twin cable 1.5mm" and "Twin cable 2.5mm" give "Twin cable", "1.5mm"
 * and "2.5mm"). With `base`, the name of the item they join, each size is what is left after that name's words. Every item
 * keeps at least one word for its size; brackets round a size are dropped, "(S)" is "S".
 */
export function joinSuggestion(names: readonly string[], base?: string): { name: string; sizes: string[] } {
  const items = names.map((n) => n.trim().split(/\s+/).filter((w) => w !== ''));
  const lists = base === undefined ? items : [base.trim().split(/\s+/).filter((w) => w !== ''), ...items];
  if (items.length === 0 || lists.length < 2) return { name: '', sizes: names.map(() => '') };
  const sameAt = (at: (words: string[]) => string | undefined) => {
    const first = at(lists[0] ?? [])?.toLocaleLowerCase();
    return first !== undefined && lists.every((words) => at(words)?.toLocaleLowerCase() === first);
  };
  const shortest = Math.min(...items.map((words) => words.length));
  let start = 0;
  while (start < shortest - 1 && sameAt((words) => words[start])) start += 1;
  let end = 0;
  while (start + end < shortest - 1 && sameAt((words) => words[words.length - 1 - end])) end += 1;
  const first = items[0] ?? [];
  const name = [...first.slice(0, start), ...first.slice(first.length - end)].join(' ');
  return {
    // A name that started mid way ("2.5mm twin cable" gives "cable") starts with a capital, as an item's name does.
    name: name.charAt(0).toLocaleUpperCase() + name.slice(1),
    sizes: items.map((words) => words.slice(start, words.length - end).join(' ').replace(/^\((.*)\)$/, '$1').slice(0, 30)),
  };
}

/** The step 1 search: each match says whether it is ticked, and a tap ticks it or takes the tick off. */
function TickPicker({ picked, onToggle }: { picked: readonly JoinRow[]; onToggle: (p: Product) => void }) {
  const search = useLiveSearch();
  const products = useQuery({
    queryKey: ['retail', 'products', undefined, search.term],
    queryFn: ({ signal }) => retail.listProducts({ query: search.term, signal }),
    enabled: search.term !== '',
  });
  return (
    <ItemCombobox
      id="join-search"
      label="Find an item by name, code or category"
      search={search}
      // An item that is a size of another item already cannot be joined again, so it is not offered.
      results={search.term === '' ? undefined : products.data?.filter((p) => !p.variant)}
      fetching={products.isPending}
      error={products.isError ? <Problem error={products.error} /> : undefined}
      optionKey={(p) => p.id ?? p.code ?? ''}
      onPick={onToggle}
      renderOption={(p) => {
        const ticked = picked.some((r) => r.product.id === p.id);
        return (
          <>
            <span className="cb-text"><strong>{p.description}</strong> ({p.code})</span>
            <span className="cb-verb">{ticked ? <>{icons.check} Ticked</> : 'Tick'}</span>
          </>
        );
      }}
    />
  );
}

/**
 * Put items together as sizes of one item (FR-RET-51, issue #707): "Cable 1.5mm" and "Cable 2.5mm" typed as two items become
 * the sizes of one item, in three plain steps: pick the items, name the item, check the sizes. The name and the sizes are
 * suggested from the items' names and can be changed. Each item keeps its code, prices, stock and history; its name becomes
 * the item's with its size and colour. "Add more sizes to an item that already has sizes" starts the same steps from that
 * item, which keeps its own name and category (issue #395); it has one search of its own, never beside the first step's.
 */
export function JoinPage() {
  const queryClient = useQueryClient();
  const [typedName, setTypedName] = useState<string | null>(null);
  const [rows, setRows] = useState<JoinRow[]>([]);
  // Looking for the item with sizes to add to, before it is chosen.
  const [adding, setAdding] = useState(false);
  // The item they join when it exists already: any one of its sizes names it.
  const [target, setTarget] = useState<{ id: string; name: string } | null>(null);
  // An item without sizes picked in the "item that already has sizes" search: said, not ignored.
  const [notSized, setNotSized] = useState<string | null>(null);
  const [done, setDone] = useState<{ id: string; name: string; count: number } | null>(null);
  const targetItem = useQuery({ queryKey: ['retail', 'family', target?.id], queryFn: () => retail.getFamily(target?.id ?? ''), enabled: target !== null });
  const there = targetItem.data?.variants ?? [];
  const suggested = joinSuggestion(rows.map((r) => r.product.description ?? ''), target?.name);
  const name = typedName ?? suggested.name;
  const sizeOf = (r: JoinRow, i: number) => r.size ?? suggested.sizes[i] ?? '';
  const unit = target ? targetItem.data?.unit_id : rows[0]?.product.unit_id;
  const keys = rows.map((r, i) => variantKey(sizeOf(r, i).trim() || undefined, r.colour.trim() || undefined));
  const taken = new Set(there.map((v) => variantKey(v.variant?.size, v.variant?.colour)));
  const fewest = target ? 1 : 2;
  // Until the item they join is read, no row is judged against it (its unit is not known yet).
  const problem = rows.length < fewest
    ? fewest === 1 ? 'Tick the items to add as sizes.' : 'Tick 2 or more items.'
    : target && targetItem.isError
      ? `${target.name} could not be read. Try again, or make a new item instead.`
      : target && !targetItem.data
        ? `Reading ${target.name}.`
        : rows.some((r) => r.product.unit_id !== unit)
      ? 'Pick items with the same single unit: the sizes of one item are kept the same way.'
      : rows.some((r, i) => sizeOf(r, i).trim() === '' && r.colour.trim() === '')
        ? 'Give each one its size, its colour or both.'
        : new Set(keys).size !== keys.length
          ? 'Two of them have the same size and colour.'
          : keys.some((k) => taken.has(k))
            ? `${target?.name} has one of these sizes and colours already.`
            : there.length + rows.length > MAX_VARIANTS
              ? `An item has at most ${MAX_VARIANTS} sizes and colours, and ${target?.name} has ${there.length}.`
              : !target && name.trim() === '' ? 'Say what customers call the item.' : null;
  // success-screen: the saved sizes and colours replace the form
  const save = useMutation({
    meta: { success: (family: { name?: string }) => `${rows.length} ${rows.length === 1 ? 'item' : 'items'} saved as sizes and colours of ${family?.name ?? target?.name ?? name.trim()}` },
    mutationFn: (duplicateReason?: string) => retail.joinFamily(target?.id ?? null, {
      // An item that exists keeps its name and category: only the items go with it.
      ...(target ? {} : { name: name.trim() }),
      items: rows.map((r, i) => ({ product_id: r.product.id ?? '', ...(sizeOf(r, i).trim() ? { size: sizeOf(r, i).trim() } : {}), ...(r.colour.trim() ? { colour: r.colour.trim() } : {}) })),
      ...(duplicateReason && !target ? { duplicate_reason: duplicateReason } : {}),
    }),
    onSuccess: (family) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      setDone({ id: family.id ?? '', name: family.name ?? target?.name ?? name.trim(), count: there.length + rows.length });
    },
  });
  const change = (i: number, patch: Partial<JoinRow>) => setRows((all) => all.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const toggle = (p: Product) => setRows((all) => (all.some((r) => r.product.id === p.id) ? all.filter((r) => r.product.id !== p.id) : [...all, { product: p, size: null, colour: '' }]));
  const startAgain = () => {
    setRows([]);
    setTypedName(null);
    setTarget(null);
    setAdding(false);
    setNotSized(null);
    setDone(null);
    save.reset();
  };
  const title = 'Put items together as sizes of one item';
  if (done) {
    return (
      <Gate screen="products" title={title}>
        <SuccessScreen
          level={2}
          title={`${done.name} now has ${done.count} ${done.count === 1 ? 'size' : 'sizes'}`}
          detail="Each one keeps its code, stock, price and history."
          actions={[{ label: 'Open it', to: familyPath(done.id) }, { label: 'Put more together', onClick: startAgain }]}
        />
      </Gate>
    );
  }
  if (adding && !target) {
    return (
      <Gate screen="products" title={title}>
        <section data-tour="join-existing" aria-label="Add more sizes to an item that already has sizes">
          <h2>Add more sizes to an item that already has sizes</h2>
          <p>Search for any one of its sizes and tap it. Then tick the items that become its new sizes.</p>
          <ProductPicker
            id="join-target-search" showStock={false} addLabel="Choose"
            onAdd={(p) => {
              if (p.variant?.family_id) {
                setNotSized(null);
                setTarget({ id: p.variant.family_id, name: p.variant.family_name ?? '' });
              } else {
                setNotSized(`${p.description ?? 'That item'} has no sizes yet. Search for one size of the item you want to add to.`);
              }
            }}
          />
          {notSized && <p role="note" className="alert alert-warning">{notSized}</p>}
          <button type="button" className="btn-ghost" onClick={startAgain}>Back</button>
        </section>
      </Gate>
    );
  }
  return (
    <Gate screen="products" title={title}>
      <p>
        You have Cable 1.5mm and Cable 2.5mm as two items? Put them together as one item, Cable, with two sizes. Each keeps its code, stock, price and history.{' '}
        <HelpTip tip="joinSizes" />
      </p>
      <section data-tour="join-pick" aria-labelledby="join-step-1" className="stack">
        <h2 id="join-step-1">1. Pick the items</h2>
        <TickPicker picked={rows} onToggle={toggle} />
        {rows.length === 0 ? (
          <p className="hint">{target ? 'Tick the items that become new sizes.' : 'Tick 2 or more items. Type part of a name to find them.'}</p>
        ) : (
          <ul className="stack" style={{ listStyle: 'none', padding: 0 }} aria-label="Ticked items">
            {rows.map((r) => (
              <li key={r.product.id} className="cluster">
                <span aria-hidden="true">{icons.check}</span>
                <span><strong>{r.product.description}</strong> ({r.product.code})</span>
                <button type="button" className="btn-ghost btn-sm" aria-label={`Remove ${r.product.description}`} onClick={() => toggle(r.product)}>Remove</button>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section data-tour="join-name" aria-labelledby="join-step-2" className="stack">
        <h2 id="join-step-2">2. Name the item</h2>
        {target ? (
          <>
            <p>They become sizes of <strong>{target.name}</strong>, which keeps its name.</p>
            <button type="button" className="btn-ghost" onClick={() => { setTarget(null); setAdding(false); }}>Make a new item instead</button>
          </>
        ) : (
          <>
            <label htmlFor="join-name">What do customers call it?</label>
            <input id="join-name" value={name} maxLength={200} onChange={(e) => setTypedName(e.target.value)} autoComplete="off" placeholder="Twin cable" />
          </>
        )}
      </section>
      <section data-tour="join-list" aria-labelledby="join-step-3" className="stack">
        <h2 id="join-step-3">3. Check the sizes</h2>
        {rows.length === 0 && <p className="empty-state">Each item you tick shows here with its size.</p>}
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {rows.map((r, i) => (
            <li key={r.product.id} className="rt-card">
              <p><strong>{r.product.description}</strong> ({r.product.code})</p>
              <div className="rt-row">
                <div>
                  <label htmlFor={`join-size-${r.product.id}`}>Size</label>
                  <input id={`join-size-${r.product.id}`} value={sizeOf(r, i)} maxLength={30} onChange={(e) => change(i, { size: e.target.value })} autoComplete="off" aria-label={`Size of ${r.product.description}`} />
                </div>
                <div>
                  <label htmlFor={`join-colour-${r.product.id}`}>Colour or print, if any</label>
                  <input id={`join-colour-${r.product.id}`} value={r.colour} maxLength={30} onChange={(e) => change(i, { colour: e.target.value })} autoComplete="off" aria-label={`Colour of ${r.product.description}`} />
                </div>
              </div>
            </li>
          ))}
        </ul>
      </section>
      {problem && rows.length > 0 && <p role="note" className="alert alert-warning">{problem}</p>}
      {save.error && <NameTaken error={save.error} busy={save.isPending} onAnyway={(reason) => save.mutate(reason)} />}
      <BusyButton busy={save.isPending} type="button" className="rt-primary" data-tour="join-save" notReady={problem !== null} onClick={() => save.mutate(undefined)}>
        Put them together
      </BusyButton>
      {!target && (
        <p>
          <button type="button" className="btn-ghost btn-sm" onClick={() => setAdding(true)}>Add more sizes to an item that already has sizes</button>
        </p>
      )}
    </Gate>
  );
}

export const NewFamilyRoute = createLazyRoute(SIZES_NEW)({ component: NewFamilyPage });
export const JoinRoute = createLazyRoute(SIZES_JOIN)({ component: JoinPage });
