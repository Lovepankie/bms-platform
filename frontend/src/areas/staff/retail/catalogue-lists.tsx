import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { retail, type Category, type Unit } from '../../../api/retail';
import { AddPanel } from './catalogue';
import { ScreenLink } from './screen-link';
import { Gate, Problem, Success, changeFailureText, requireVersion, useToast } from './ui';
import { useComeBack } from '../../../app/ui/EmptyChoice';
import { packText } from './shop-setup-state';
import { UsualPackSheet, mayHaveUsualPack } from './unit-usual-pack';

// Categories and Units (#146): the same screen twice. A row can be renamed or switched off, never
// deleted: items and their history keep pointing at it. A switched-off row stays on the items that
// use it and cannot be chosen for a new item or a change of item. A category also has its code start (issue
// #493): its row shows it and opens the category, where items are added. A new category is not asked for one (issue
// #562): the app makes it from the name and shows it; the category's page changes it.

type Named = Category | Unit;

export function usedText(count: number | undefined, noun: string): string {
  const n = count ?? 0;
  return n === 0 ? `No items use this ${noun} yet.` : `Used by ${n} ${n === 1 ? 'item' : 'items'}.`;
}

export function NamedRow({ row, noun, busy, onRename, onToggle, extra, detail }: {
  row: Named;
  /** A line under the name, such as a category's code start. */
  detail?: ReactNode;
  /** More beside the buttons, such as a link to the items that use this row. */
  extra?: ReactNode;
  noun: string;
  busy?: boolean;
  onRename: (name: string) => void;
  onToggle: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(row.name ?? '');
  const off = row.active === false;
  return (
    <li className="rt-card">
      <p>
        <strong>{row.name}</strong> {off && <span className="badge badge-warning">Switched off</span>}
      </p>
      {detail}
      <p className="hint">
        {usedText(row.product_count, noun)}
        {off ? ' New items cannot choose it.' : ''}
      </p>
      {editing ? (
        <div className="stack">
          <label htmlFor={`rename-${row.id}`}>New name</label>
          <input id={`rename-${row.id}`} value={name} maxLength={noun === 'unit' ? 30 : 100} onChange={(e) => setName(e.target.value)} />
          <p className="cluster">
            <BusyButton busy={busy ?? false} type="button" disabled={name.trim() === '' || name.trim() === row.name} onClick={() => { onRename(name.trim()); setEditing(false); }}>
              Save name
            </BusyButton>
            <button type="button" className="btn-ghost" onClick={() => { setName(row.name ?? ''); setEditing(false); }}>Cancel</button>
          </p>
        </div>
      ) : (
        <p className="cluster">
          <button type="button" className="btn-sm" onClick={() => setEditing(true)} aria-label={`Rename ${row.name}`}>Rename</button>
          <BusyButton busy={busy ?? false} type="button" className="btn-sm" onClick={onToggle} aria-label={`${off ? 'Switch on' : 'Switch off'} ${row.name}`}>
            {off ? 'Switch on' : 'Switch off'}
          </BusyButton>
          {extra}
        </p>
      )}
    </li>
  );
}

function NamedList({ noun, plural, list, create, update, withPrefix = false }: {
  noun: string;
  plural: string;
  list: () => Promise<Named[]>;
  create: (name: string) => Promise<Named>;
  /** Categories (issue #493): the code start the app will make shown on the add form and on each row; the row opens the category. */
  withPrefix?: boolean;
  update: (id: string, version: number, body: { name?: string; active?: boolean }) => Promise<Named>;
}) {
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const [message, setMessage] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [suggested, setSuggested] = useState('');
  const [adding, setAdding] = useState(false);
  // Issue #637: the unit whose usual pack sheet is open.
  const [packFor, setPackFor] = useState<string | null>(null);
  const rows = useQuery({ queryKey: ['retail', 'catalogue', plural], queryFn: list });
  const back = useComeBack();
  const done = (text: string) => {
    setMessage(text);
    show(text, 'success');
    void queryClient.invalidateQueries({ queryKey: ['retail'] });
  };
  const add = useMutation({
    mutationFn: () => create(name.trim()),
    onMutate: () => setMessage(null),
    onSuccess: (row) => {
      setName(''); setSuggested(''); setAdding(false);
      const codes = withPrefix && 'code_prefix' in row && row.code_prefix ? ` Its item codes start with ${row.code_prefix}.` : '';
      done(`Added the ${noun} "${row.name}".${codes}`);
      // Opened from an empty list on a form (issue #619): what was added takes the person back to that form.
      back?.();
    },
  });
  const change = useMutation({
    mutationFn: (v: { id: string; version: number | undefined; body: { name?: string; active?: boolean }; text: (row: Named) => string }) => update(v.id, requireVersion(v.version), v.body).then((row) => ({ row, text: v.text })),
    onMutate: () => { setMessage(null); setFailure(null); },
    onSuccess: ({ row, text }) => done(text(row)),
    onError: (error) => setFailure(changeFailureText(error, queryClient)),
  });
  return (
    <>
      <div data-tour="named-add">
        <AddPanel label={`Add a ${noun}`} open={adding} onOpen={() => setAdding(true)} onClose={() => { setAdding(false); setName(''); setSuggested(''); }}>
          <form onSubmit={(e) => { e.preventDefault(); if (name.trim()) add.mutate(); }}>
            <label htmlFor={`new-${plural}`}>Name of the new {noun}</label>
            <input
              id={`new-${plural}`}
              value={name}
              maxLength={noun === 'unit' ? 30 : 100}
              onChange={(e) => setName(e.target.value)}
              onBlur={() => { if (withPrefix && name.trim()) void retail.suggestCategoryPrefix(name.trim()).then(setSuggested, () => setSuggested('')); }}
              autoComplete="off"
            />
            {withPrefix && (
              <p className="hint" id="new-category-prefix" data-testid="new-category-prefix">
                {suggested
                  ? <>Item codes here will start with <strong>{suggested}</strong>, like {suggested}-0001, made by the app. You can change the letters on the category&apos;s page.</>
                  : 'The app makes the item codes of this category from its name, like BLB-0001 for Bulbs.'}
              </p>
            )}
            <BusyButton busy={add.isPending} type="submit" className="rt-primary" notReady={name.trim() === ''}>{`Add ${noun}`}</BusyButton>
          </form>
          <Problem error={add.error} />
        </AddPanel>
      </div>
      {message && <Success>{message}</Success>}
      <Problem error={failure && new Error(failure)} />
      {rows.isPending && <p className="loading">Loading</p>}
      <Problem error={rows.error} />
      {rows.data && rows.data.length === 0 && <p className="empty-state">No {plural} yet. Add the first one above.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }} data-tour="named-list">
        {(rows.data ?? []).map((r) => (
          <NamedRow
            key={r.id}
            row={r}
            noun={noun}
            detail={withPrefix && 'code_prefix' in r
              ? <p className="hint">Item codes start with <strong>{r.code_prefix}</strong>, next {r.next_code}.</p>
              : 'usual_pack' in r && r.usual_pack ? <p className="hint">{`Usual pack: ${packText(r.usual_pack.size ?? '', r.usual_pack.of_unit ?? '', r.usual_pack.purpose)}.`}</p> : undefined}
            extra={withPrefix
              ? <Link className="btn-sm" to={`/staff/retail/catalogue/categories/${r.id ?? ''}` as never} aria-label={`Open ${r.name} and its items`}>Open</Link>
              : (
                <>
                  <ScreenLink screen="products" to="/staff/retail/catalogue/products" label={`See the items of ${r.name}`}>Items</ScreenLink>
                  {('usual_pack' in r && r.usual_pack || mayHaveUsualPack(r, rows.data ?? [])) && (
                    <button type="button" className="btn-sm" onClick={() => setPackFor(r.id ?? null)} aria-label={`Usual pack of ${r.name}`}>
                      Usual pack
                    </button>
                  )}
                </>
              )}
            busy={change.isPending}
            onRename={(n) => change.mutate({ id: r.id ?? '', version: r.version, body: { name: n }, text: (x) => `Renamed to "${x.name}".` })}
            onToggle={() =>
              change.mutate({
                id: r.id ?? '',
                version: r.version,
                body: { active: r.active === false },
                text: (x) => (x.active === false
                  ? `"${x.name}" is switched off. Items that use it keep it; new items cannot choose it.`
                  : `"${x.name}" is switched on again.`),
              })}
          />
        ))}
      </ul>
      {(rows.data ?? []).filter((r) => r.id === packFor).map((r) => (
        <UsualPackSheet key={`${r.id}-${r.version}`} unit={r} units={rows.data ?? []} onClose={() => setPackFor(null)} onSaved={done} />
      ))}
      {toast}
    </>
  );
}

export function CategoriesPage() {
  return (
    <Gate screen="categories" title="Categories">
      <NamedList noun="category" plural="categories" withPrefix list={() => retail.listCategories()} create={(n) => retail.createCategory(n)} update={(id, v, b) => retail.updateCategory(id, v, b)} />
    </Gate>
  );
}

export function UnitsPage() {
  return (
    <Gate screen="units" title="Units">
      <NamedList noun="unit" plural="units" list={() => retail.listUnits()} create={(n) => retail.createUnit(n)} update={(id, v, b) => retail.updateUnit(id, v, b)} />
    </Gate>
  );
}

export const CategoriesRoute = createLazyRoute('/staff/retail/catalogue/categories')({ component: CategoriesPage });
export const UnitsRoute = createLazyRoute('/staff/retail/catalogue/units')({ component: UnitsPage });
