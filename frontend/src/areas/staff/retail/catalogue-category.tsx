import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field } from '../../../app/ui/Field';
import { retail, type Category, type Product } from '../../../api/retail';
import { useCrumbLabel, useCrumbTail } from '../../../components/breadcrumbs';
import { useStaff } from '../context';
import { Editing, ProductCard, ProductForm } from './catalogue-products';
import { ScreenLink } from './screen-link';
import { Gate, Problem, Success, changeFailureText, requireVersion, useProfitAccess, useToast } from './ui';

// One category (issue #493, owner decisions D14 and D16): its items, and Add an item, which opens the item form with this category
// fixed (no category dropdown). Every item added gets the category's next code, such as BLB-0001, made by the app and never
// typed (issue #562). The code start is shown and, for whoever manages the catalogue, changed here; items made before keep
// their codes. Moving an item to
// another category is on the item's own form (Edit), which keeps its code.

const route = getRouteApi('/staff/retail/catalogue/categories/$categoryId');
const MANAGE = 'retail.catalogue.manage';

/** The line under the category's name: how its item codes are made. */
export function codeLine(category: Category): string {
  const next = category.next_code ?? `${category.code_prefix ?? ''}-0001`;
  return `Item codes here start with ${category.code_prefix ?? ''}. The app gives the next item added ${next}.`;
}

/** A code start as typed: 2 to 4 letters A to Z, or what is wrong with it. */
export function prefixProblem(raw: string): string | null {
  return /^[A-Za-z]{2,4}$/.test(raw.trim()) ? null : 'Use 2 to 4 letters, A to Z, such as BLB.';
}

function PrefixEditor({ category, onSaved }: { category: Category; onSaved: (text: string) => void }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [prefix, setPrefix] = useState(category.code_prefix ?? '');
  const [failure, setFailure] = useState<string | null>(null);
  const problem = prefixProblem(prefix);
  const save = useMutation({
    mutationFn: () => retail.updateCategory(category.id ?? '', requireVersion(category.version), { code_prefix: prefix.trim().toUpperCase() }),
    onMutate: () => setFailure(null),
    onSuccess: (row) => { setOpen(false); onSaved(`Item codes in ${row.name} now start with ${row.code_prefix}. Items made before keep their codes.`); },
    onError: (error) => setFailure(changeFailureText(error, queryClient)),
  });
  if (!open) {
    return <button type="button" className="btn-sm" data-tour="category-prefix" onClick={() => { setPrefix(category.code_prefix ?? ''); setOpen(true); }}>Change the code start</button>;
  }
  return (
    <form data-tour="category-prefix" noValidate onSubmit={(e) => { e.preventDefault(); if (!problem) save.mutate(); }}>
      <Field
        id="category-prefix"
        label="Code start"
        required
        hint="New items get codes with these letters. Items made before keep their codes."
        error={problem}
        showError={save.isIdle && prefix !== category.code_prefix}
        value={prefix}
        maxLength={4}
        autoCapitalize="characters"
        autoComplete="off"
        onChange={(e) => setPrefix(e.target.value)}
      />
      <p className="cluster">
        <BusyButton busy={save.isPending} type="submit">Save the code start</BusyButton>
        <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>Cancel</button>
      </p>
      <Problem error={failure && new Error(failure)} />
    </form>
  );
}

type View = { mode: 'list' } | { mode: 'new' } | { mode: 'edit' | 'price'; id: string };

export function CategoryPage({ categoryId }: { categoryId: string }) {
  const { me } = useStaff();
  const profit = useProfitAccess();
  const { show, toast } = useToast();
  const queryClient = useQueryClient();
  const [view, setView] = useState<View>({ mode: 'list' });
  const [message, setMessage] = useState<string | null>(null);
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const category = (categories.data ?? []).find((c) => c.id === categoryId);
  const items = useQuery({
    queryKey: ['retail', 'catalogue', 'products', '', categoryId, 'all'],
    queryFn: () => retail.listCatalogue({ categoryId }),
  });
  useCrumbLabel(category?.name);
  const canManage = (me.permissions ?? []).includes(MANAGE);
  const canPrice = (me.permissions ?? []).includes('retail.price.edit');
  const back = () => setView({ mode: 'list' });
  const saved = (text: string) => {
    setMessage(text);
    show(text, 'success');
    void queryClient.invalidateQueries({ queryKey: ['retail'] });
    back();
  };
  const open = (id: string) => { setMessage(null); setView({ mode: 'edit', id }); };
  useCrumbTail(view.mode === 'new' ? 'Add an item' : undefined, back);
  const card = (p: Product) => (
    <ProductCard
      key={p.id}
      product={p}
      showCost={profit}
      canPrice={canPrice}
      onEdit={() => open(p.id ?? '')}
      onPrice={() => { setMessage(null); setView({ mode: 'price', id: p.id ?? '' }); }}
      extra={<ScreenLink screen="stock" to="/staff/retail/stock" label={`See the stock of ${p.description}`}>Stock</ScreenLink>}
    />
  );
  const list = items.data?.items ?? [];
  return (
    <Gate screen="categories" title={category?.name ?? 'Category'}>
      <Problem error={categories.error} />
      {categories.data && !category && <p className="empty-state">This category could not be found.</p>}
      {view.mode === 'list' && (categories.isPending || category) && (
        // The anchors of the page tour are drawn before the category arrives, so they are there when the tour starts.
        <>
          {message && <Success>{message}</Success>}
          {category?.active === false && <p><span className="badge badge-warning">Switched off</span></p>}
          <p className="hint" data-tour="category-codes">{category ? codeLine(category) : 'Loading'}</p>
          {canManage && (
            <p className="cluster">
              {category?.active === false
                ? <span className="hint">Switch this category on to add items to it.</span>
                : <button type="button" className="rt-primary" data-tour="category-add-item" disabled={!category} onClick={() => { setMessage(null); setView({ mode: 'new' }); }}>Add an item</button>}
            </p>
          )}
          {canManage && (category
            ? <PrefixEditor key={category.version} category={category} onSaved={saved} />
            : <button type="button" className="btn-sm" data-tour="category-prefix" disabled>Change the code start</button>)}
          <h2>Items in {category?.name ?? 'this category'}</h2>
          <div data-tour="category-items">
            {items.isPending && <p className="loading">Loading</p>}
            <Problem error={items.error} />
            {category && items.data && list.length === 0 && <p className="empty-state">No items in {category.name} yet. Tap Add an item to add the first one.</p>}
            <ul style={{ listStyle: 'none', padding: 0 }}>{list.map(card)}</ul>
          </div>
        </>
      )}
      {category && view.mode === 'new' && <ProductForm category={category} onSaved={saved} onCancel={back} onOpen={open} />}
      {(view.mode === 'edit' || view.mode === 'price') && <Editing id={view.id} mode={view.mode} onSaved={saved} onCancel={back} onOpen={open} />}
      {toast}
    </Gate>
  );
}

function CategoryRoutePage() {
  const { categoryId } = route.useParams();
  return <CategoryPage key={categoryId} categoryId={categoryId} />;
}

export const CategoryRoute = createLazyRoute('/staff/retail/catalogue/categories/$categoryId')({ component: CategoryRoutePage });
