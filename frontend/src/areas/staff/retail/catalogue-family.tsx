import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { retail, type Family, type Product } from '../../../api/retail';
import { planVariants, splitOptions, variantKey, variantLabel } from '../../../api/retail-variants';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { useStaff } from '../context';
import { PriceForm } from './catalogue-products';
import { OptionBoxes, VariantPreview, linesOf, previewProblem, usePreview } from './catalogue-variants';
import { ItemPhoto, PhotoEditor, shownPhoto } from './item-photo';
import { showQty } from './maths';
import { ScreenLink } from './screen-link';
import { Gate, Problem, Success, money, useProfitAccess, useToast } from './ui';

// The page of an item with sizes and colours (issue #384, FR-RET-51, FR-RET-52): every size and colour with its code, price and
// stock, the item's stock per shop and store and in all (every variant together, over the branches the person may see), more
// sizes or colours, a new name or category for all of them at once, and the price and details of one size. The links lead to the
// screens that work on each size as an item of its own: sell, restock, move stock, count stock.

const route = getRouteApi('/staff/retail/catalogue/products/sizes/$familyId');

const CATALOGUE = 'retail.catalogue.manage';
const PRICE = 'retail.price.edit';

export function FamilyPage() {
  const { familyId } = route.useParams();
  return <FamilyScreen familyId={familyId} />;
}

type Editing = { kind: 'price' | 'variant'; product: Product } | null;

export function FamilyScreen({ familyId }: { familyId: string }) {
  const { me } = useStaff();
  const profit = useProfitAccess();
  const { show, toast } = useToast();
  const [message, setMessage] = useState<string | null>(null);
  const [editing, setEditing] = useState<Editing>(null);
  const family = useQuery({ queryKey: ['retail', 'family', familyId], queryFn: () => retail.getFamily(familyId) });
  useCrumbLabel(family.data?.name);
  const canManage = (me.permissions ?? []).includes(CATALOGUE);
  const canPrice = (me.permissions ?? []).includes(PRICE);
  const done = (text: string) => { setMessage(text); show(text); setEditing(null); };
  const f = family.data;
  return (
    <Gate screen="products" title={f?.name ?? 'Sizes and colours'}>
      <p className="cluster" data-tour="family-links">
        <Link className="btn-sm" to={'/staff/retail/catalogue/products' as never}>Items</Link>
        <ScreenLink screen="sale" to="/staff/retail/sale">Record a sale</ScreenLink>
        <ScreenLink screen="restock" to="/staff/retail/restock">Restock</ScreenLink>
        <ScreenLink screen="transfer" to="/staff/retail/transfer">Move stock</ScreenLink>
        <ScreenLink screen="stock" to="/staff/retail/stock">Stock</ScreenLink>
        <ScreenLink screen="salesHistory" to="/staff/retail/sales">Sales</ScreenLink>
      </p>
      <p className="hint">Each size and colour is an item of its own when you sell, restock, move or count stock: look for its name with the size and colour, such as {f?.variants?.[0]?.description ?? 'Baby romper (0-3m, Blue)'}.</p>
      {message && <Success>{message}</Success>}
      {family.isPending && <p className="loading">Loading</p>}
      <Problem error={family.error} />
      {f && <p>{f.category}, counted in {f.unit}. {f.variants?.length ?? 0} sizes and colours.</p>}
      {editing?.kind === 'price' && <PriceForm product={editing.product} onSaved={done} onCancel={() => setEditing(null)} />}
      {editing?.kind === 'variant' && <VariantForm product={editing.product} onSaved={done} onCancel={() => setEditing(null)} />}
      {!editing && (
        <>
          <section data-tour="family-photo" aria-label="Photo of this item">
            <h2>Photo</h2>
            {f && canManage && (
              <PhotoEditor
                inputId={f.id ?? 'item'}
                photoId={f.photo_id}
                label={f.name ?? ''}
                onUpload={(file) => retail.setFamilyPhoto(f.id ?? '', file)}
                onClear={() => retail.clearFamilyPhoto(f.id ?? '')}
                onSaved={(text) => { setMessage(text); show(text); }}
              />
            )}
            {f && !canManage && (f.photo_id ? <ItemPhoto photoId={f.photo_id} label={f.name ?? ''} size={96} /> : <p className="empty-state">No photo yet.</p>)}
            <p className="hint">Every size and colour shows this photo unless it has its own, which you add with Change.</p>
          </section>
          <section data-tour="family-stock" aria-label="Stock of this item">
            <h2>Stock</h2>
            {f && <StockOf family={f} />}
          </section>
          <section data-tour="family-variants" aria-label="Sizes and colours">
            <h2>Sizes and colours</h2>
            {f && (
              <ul style={{ listStyle: 'none', padding: 0 }}>
                {(f.variants ?? []).map((v) => (
                  <li key={v.id} className="rt-card">
                    <ItemPhoto photoId={shownPhoto(v)} label={v.description ?? ''} />
                    <p>
                      <strong>{variantLabel(v.variant?.size, v.variant?.colour)}</strong>{' '}
                      {v.active === false && <span className="badge badge-warning">Inactive</span>}
                    </p>
                    <p className="hint">{v.code}</p>
                    <p>
                      Sells at <strong>{money(v.sell_minor ?? 0)}</strong>
                      {profit && v.cost_minor !== undefined && <>, cost {money(v.cost_minor)}</>}
                      . In stock: <strong>{showQty(v.stock_on_hand ?? '0')}</strong>
                    </p>
                    <p className="cluster">
                      {canPrice && <button type="button" className="btn-sm" onClick={() => { setMessage(null); setEditing({ kind: 'price', product: v }); }} aria-label={`Change the price of ${v.description}`}>Change price</button>}
                      {canManage && <button type="button" className="btn-sm" onClick={() => { setMessage(null); setEditing({ kind: 'variant', product: v }); }} aria-label={`Change ${v.description}`}>Change</button>}
                    </p>
                  </li>
                ))}
              </ul>
            )}
          </section>
          {canManage && (
            <section data-tour="family-add" aria-label="Add more sizes or colours">
              <h2>Add more sizes or colours</h2>
              {f && <AddVariants family={f} onDone={(text) => { setMessage(text); show(text); }} />}
            </section>
          )}
          {canManage && f && <RenameFamily family={f} onDone={(text) => { setMessage(text); show(text); }} />}
        </>
      )}
      {toast}
    </Gate>
  );
}

/** The item's stock: every variant together, per shop or store the person may see, and in all. */
function StockOf({ family }: { family: Family }) {
  const { me } = useStaff();
  const nameOf = (id: string | undefined) => me.branches?.find((b) => b.id === id)?.name ?? 'Branch';
  const rows = family.stock_by_branch ?? [];
  return (
    <>
      <p>In all: <strong>{showQty(family.stock_on_hand ?? '0')} {family.unit}</strong></p>
      {rows.length === 0 ? (
        <p className="empty-state">No stock of this item yet. Restock it, and each size and colour shows its own count.</p>
      ) : (
        <ul>
          {rows.map((b) => <li key={b.branch_id}>{nameOf(b.branch_id)}: {showQty(b.qty ?? '0')}</li>)}
        </ul>
      )}
    </>
  );
}

/** More sizes or colours: a combination the item has is left out, the new ones start at the price of its first size. */
function AddVariants({ family, onDone }: { family: Family; onDone: (text: string) => void }) {
  const profit = useProfitAccess();
  const queryClient = useQueryClient();
  const [sizes, setSizes] = useState('');
  const [colours, setColours] = useState('');
  const variants = family.variants ?? [];
  const first = variants[0];
  const existing = new Set(variants.map((v) => variantKey(v.variant?.size, v.variant?.colour)));
  const next = Math.max(-1, ...variants.map((v) => v.variant?.position ?? 0)) + 1;
  // A new colour goes with every size the item has, and a new size with every colour, unless the person names others.
  const plan = planVariants({
    baseCode: family.base_code ?? '',
    sizes: splitOptions(sizes).length > 0 ? [...(family.sizes ?? []), ...splitOptions(sizes)] : (family.sizes ?? []),
    colours: splitOptions(colours).length > 0 ? [...(family.colours ?? []), ...splitOptions(colours)] : (family.colours ?? []),
    costMinor: first?.cost_minor ?? 0, sellMinor: first?.sell_minor ?? 0, existing, nextPosition: next,
  });
  const preview = usePreview(plan);
  const problem = previewProblem(preview.rows);
  const save = useMutation({
    mutationFn: () => retail.addVariants(family.id ?? '', { sizes: splitOptions(sizes), colours: splitOptions(colours), variants: linesOf(preview.rows, profit) }),
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      setSizes('');
      setColours('');
      preview.reset();
      onDone(`Added ${(saved.variants?.length ?? 0) - variants.length} to ${saved.name}.`);
    },
  });
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (problem === null && !save.isPending) save.mutate(); }}>
      <OptionBoxes prefix="family-add" sizes={sizes} colours={colours} onSizes={setSizes} onColours={setColours} />
      <VariantPreview rows={preview.rows} profit={profit} onEdit={preview.edit} />
      {problem && preview.rows.length > 0 && <p role="note" className="alert alert-warning">{problem}</p>}
      <Problem error={save.error} />
      <BusyButton busy={save.isPending} type="submit" disabled={problem !== null}>{preview.rows.length > 0 ? `Add these ${preview.rows.length}` : 'Add'}</BusyButton>
    </form>
  );
}

/** A new name for the item renames every size and colour; a new category moves them all. */
function RenameFamily({ family, onDone }: { family: Family; onDone: (text: string) => void }) {
  const queryClient = useQueryClient();
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const [open, setOpen] = useState(false);
  const [name, setName] = useState(family.name ?? '');
  const [categoryId, setCategoryId] = useState(family.category_id ?? '');
  const save = useMutation({
    mutationFn: () => retail.updateFamily(family.id ?? '', family.version ?? 1, {
      ...(name.trim() !== family.name ? { name: name.trim() } : {}),
      ...(categoryId !== family.category_id ? { category_id: categoryId } : {}),
    }),
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      setOpen(false);
      onDone(`Saved. Every size and colour is now called ${saved.name} and is in ${saved.category}.`);
    },
  });
  if (!open) return <p><button type="button" onClick={() => setOpen(true)} data-tour="family-rename">Change the name or category</button></p>;
  const changed = name.trim() !== '' && (name.trim() !== family.name || categoryId !== family.category_id);
  return (
    <section className="rt-card" aria-label="Change the name or category">
      <label htmlFor="family-name">Name</label>
      <input id="family-name" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} autoComplete="off" />
      <label htmlFor="family-category">Category</label>
      <select id="family-category" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
        {(categories.data ?? []).filter((c) => c.active !== false || c.id === family.category_id).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select>
      <p className="hint">Every size and colour takes the new name and category. Past sales stay as they were.</p>
      <Problem error={save.error} />
      <BusyButton busy={save.isPending} type="button" className="rt-primary" disabled={!changed} onClick={() => save.mutate()}>Save</BusyButton>
      <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>Cancel</button>
    </section>
  );
}

/** One size and colour: its size, colour, code and whether it is on sale. Its name, category and unit come from the item. */
function VariantForm({ product, onSaved, onCancel }: { product: Product; onSaved: (text: string) => void; onCancel: () => void }) {
  const queryClient = useQueryClient();
  const [size, setSize] = useState(product.variant?.size ?? '');
  const [colour, setColour] = useState(product.variant?.colour ?? '');
  const [code, setCode] = useState(product.code ?? '');
  const [active, setActive] = useState(product.active !== false);
  const save = useMutation({
    mutationFn: () => retail.updateProduct(product.id ?? '', product.version ?? 1, { code: code.trim(), active, variant_size: size.trim(), variant_colour: colour.trim() }),
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved(`Saved the changes to "${saved.description}".`);
    },
  });
  const ready = code.trim() !== '' && (size.trim() !== '' || colour.trim() !== '') && !save.isPending;
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (ready) save.mutate(); }}>
      <h2>Change {product.description}</h2>
      <label htmlFor="variant-size">Size</label>
      <input id="variant-size" value={size} maxLength={30} onChange={(e) => setSize(e.target.value)} autoComplete="off" />
      <label htmlFor="variant-colour">Colour or print</label>
      <input id="variant-colour" value={colour} maxLength={30} onChange={(e) => setColour(e.target.value)} autoComplete="off" />
      <label htmlFor="variant-code">Code</label>
      <input id="variant-code" value={code} maxLength={40} onChange={(e) => setCode(e.target.value)} autoComplete="off" />
      <label htmlFor="variant-inactive">
        <input id="variant-inactive" type="checkbox" checked={!active} onChange={(e) => setActive(!e.target.checked)} /> Inactive
      </label>
      <p className="hint">An inactive size is not offered when selling, restocking or moving stock. It is never deleted: its history stays.</p>
      <PhotoEditor
        inputId={product.id ?? 'size'}
        photoId={product.photo_id}
        shownId={shownPhoto(product)}
        label={product.description ?? ''}
        onUpload={(file) => retail.setProductPhoto(product.id ?? '', file)}
        onClear={() => retail.clearProductPhoto(product.id ?? '')}
      />
      <p className="hint">A photo here is for this size alone. Without one, it shows the item's photo.</p>
      <Problem error={save.error} />
      <BusyButton busy={save.isPending} type="submit" className="rt-primary" disabled={!ready}>Save changes</BusyButton>
      <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>
    </form>
  );
}

export const FamilyRoute = createLazyRoute('/staff/retail/catalogue/products/sizes/$familyId')({ component: FamilyPage });
