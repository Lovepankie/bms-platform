import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { retail, type Category, type Unit, type UsualPackRequest } from '../../../api/retail';
import type { HelpTipKey } from '../../../app/help-tips';
import { HelpTip } from '../../../app/ui/HelpTip';
import { Field } from '../../../app/ui/Field';
import { Segmented } from '../../../app/ui/Segmented';
import { AddPanel } from './catalogue';
import { PURPOSES, type Purpose } from './catalogue-packs';
import { ScreenLink } from './screen-link';
import { Gate, Problem, changeFailureText, requireVersion, useToast } from './ui';
import { useComeBack } from '../../../app/ui/EmptyChoice';
import { UsualPackSheet, holdsText, mayHaveUsualPack, usedForText, usualSizeProblem } from './unit-usual-pack';
import { isStandardPackage, orExistingUnit } from './units-state';

// Categories and Units (#146): the same screen twice. A row can be renamed or switched off, never
// deleted: items and their history keep pointing at it. A switched-off row stays on the items that
// use it and cannot be chosen for a new item or a change of item. A category also has its code start (issue
// #493): its row shows it and opens the category, where items are added. A new category is not asked for one (issue
// #562): the app makes it from the name and shows it; the category's page changes it. Issue #709 (D92, ADR-110): Units is
// in two sections, Single units and Packages, each with its own add button; a package's add form asks how many it usually
// holds.

type Named = Category | Unit;

/** Issue #709: a part of the list with its heading, its HelpTip, a hint and its own add button. */
export type Section = {
  key: string;
  title: string;
  tip: HelpTipKey;
  hint: string;
  add: string;
  /** The rows that belong here, of the whole list. */
  holds: (row: Named) => boolean;
  /** The add form also asks how many one usually holds, and of which single unit (a package). */
  usual?: boolean;
};

/** Issue #709: a package is a package word (is_package, ADR-106) or a unit that usually holds another. */
export const isPackageRow = (row: Named) => ('is_package' in row && row.is_package === true) || ('usual_pack' in row && row.usual_pack !== undefined)
  // Round 2 of #709: a standard package that may also be a single unit (Roll) still sits under Packages.
  || ('is_package' in row && isStandardPackage(row.name));

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
  const [asking, setAsking] = useState(false);
  const [name, setName] = useState(row.name ?? '');
  const off = row.active === false;
  return (
    <li className="rt-card">
      <p>
        <strong>{row.name}</strong> {off && <span className="badge badge-warning">Deleted</span>}
      </p>
      {detail}
      <p className="hint">
        {usedText(row.product_count, noun)}
        {off ? ' Hidden from lists and pickers; past records keep it.' : ''}
      </p>
      {asking ? (
        // Issue #830: the old app's Delete, asked first. Underneath the row is switched off, never removed.
        <div className="stack">
          <p className="alert alert-warning" data-testid={`delete-${noun}-question`}>
            <strong>Delete {row.name}?</strong> It is hidden from lists and pickers. Items, history and reports keep it, and Show deleted brings it back.
          </p>
          <p className="cluster">
            <BusyButton busy={busy ?? false} type="button" className="btn-danger" onClick={() => { setAsking(false); onToggle(); }}>Yes, delete it</BusyButton>
            <button type="button" className="btn-ghost" onClick={() => setAsking(false)}>Keep it</button>
          </p>
        </div>
      ) : editing ? (
        <div className="stack">
          <label htmlFor={`rename-${row.id}`}>New name</label>
          <input id={`rename-${row.id}`} value={name} maxLength={noun === 'category' ? 100 : 30} onChange={(e) => setName(e.target.value)} />
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
          <BusyButton busy={busy ?? false} type="button" className={off ? 'btn-sm' : 'btn-sm btn-danger'} onClick={off ? onToggle : () => setAsking(true)} aria-label={`${off ? 'Bring back' : 'Delete'} ${row.name}`}>
            {off ? 'Bring back' : 'Delete'}
          </BusyButton>
          {extra}
        </p>
      )}
    </li>
  );
}

function NamedList({ noun, plural, list, create, update, withPrefix = false, sections }: {
  noun: string;
  plural: string;
  list: () => Promise<Named[]>;
  create: (name: string, usual?: UsualPackRequest) => Promise<Named>;
  /** Categories (issue #493): the code start the app will make shown on the add form and on each row; the row opens the category. */
  withPrefix?: boolean;
  update: (id: string, version: number, body: { name?: string; active?: boolean }) => Promise<Named>;
  /** Issue #709: the list in parts, each with its own add button; without, one list. */
  sections?: Section[];
}) {
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const [failure, setFailure] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [suggested, setSuggested] = useState('');
  // The section whose add form is open ('' for a list in one part), or null.
  const [adding, setAdding] = useState<string | null>(null);
  const [usual, setUsual] = useState<{ ofUnitId: string; size: string; purpose: Purpose }>({ ofUnitId: '', size: '', purpose: 'both' });
  const [sent, setSent] = useState(false);
  // Issue #637: the unit whose usual pack sheet is open.
  const [packFor, setPackFor] = useState<string | null>(null);
  const rows = useQuery({ queryKey: ['retail', 'catalogue', plural], queryFn: list });
  const back = useComeBack();
  const all = rows.data ?? [];
  // Issue #830: a deleted (switched off) row is hidden until Show deleted is ticked.
  const [showDeleted, setShowDeleted] = useState(false);
  const deletedCount = all.filter((r) => r.active === false).length;
  const shown = showDeleted ? all : all.filter((r) => r.active !== false);
  const singles = all.filter((r) => r.active !== false && !isPackageRow(r));
  const done = (text: string) => {
    show(text, 'success');
    void queryClient.invalidateQueries({ queryKey: ['retail'] });
  };
  const close = () => { setAdding(null); setName(''); setSuggested(''); setSent(false); setUsual({ ofUnitId: '', size: '', purpose: 'both' }); };
  const asksUsual = sections?.find((s) => s.key === adding)?.usual === true;
  const ofUnitId = usual.ofUnitId || (singles[0]?.id ?? '');
  // Review of #722: with no single unit switched on there is nothing for a package to hold, so it says so and sends nothing.
  // Round 2 of #709: the usual size is optional, a package each item says the size of has none; one given needs a single
  // unit to count, and with none switched on the form says so and sends nothing.
  const givesSize = asksUsual && usual.size.trim() !== '';
  const sizeProblem = !givesSize ? null
    : ofUnitId === '' ? 'Add a single unit first, such as piece, for this package to hold.'
      : usualSizeProblem(usual.size, { name: name.trim() }, singles.find((u) => u.id === ofUnitId) as Unit | undefined);
  const add = useMutation({
    mutationFn: () => create(name.trim(), givesSize ? { size: usual.size.trim(), of_unit_id: ofUnitId, purpose: usual.purpose } : undefined),
    onSuccess: (row) => {
      close();
      // Issue #698: a unit the shop has under another spelling or case is not added again.
      if ('reused' in row && row.reused) return done(`You already have ${row.name ?? ''}.`);
      const codes = withPrefix && 'code_prefix' in row && row.code_prefix ? ` Its item codes start with ${row.code_prefix}.` : '';
      done(`Added the ${noun} "${row.name}".${codes}`);
      // Opened from an empty list on a form (issue #619): what was added takes the person back to that form.
      back?.();
    },
  });
  const change = useMutation({
    mutationFn: (v: { id: string; version: number | undefined; body: { name?: string; active?: boolean }; text: (row: Named) => string }) => update(v.id, requireVersion(v.version), v.body).then((row) => ({ row, text: v.text })),
    onMutate: () => setFailure(null),
    onSuccess: ({ row, text }) => done(text(row)),
    onError: (error) => setFailure(changeFailureText(error, queryClient)),
  });
  const addPanel = (key: string, label: string, nameLabel: string, send: string) => (
    <AddPanel label={label} open={adding === key} onOpen={() => { close(); setAdding(key); }} onClose={close}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); setSent(true); if (name.trim() && sizeProblem === null) add.mutate(); }}>
        <label htmlFor={`new-${plural}-${key}`}>{nameLabel}</label>
        <input
          id={`new-${plural}-${key}`}
          value={name}
          maxLength={noun === 'category' ? 100 : 30}
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
        {asksUsual && (
          <>
            <Field
              id={`new-${plural}-${key}-size`}
              label="How many does it usually hold?"
              optional
              inputMode="decimal"
              autoComplete="off"
              placeholder="10"
              hint="Each item can be different. Leave it empty if each item says."
              value={usual.size}
              onChange={(e) => setUsual({ ...usual, size: e.target.value })}
              error={sizeProblem}
              showError={sent}
            />
            <Field
              id={`new-${plural}-${key}-of`}
              label="Of which single unit"
              control={(props) => (
                // empty: a shop always has its standard single units (ADR-110); Piece is first
                <select {...props} value={ofUnitId} onChange={(e) => setUsual({ ...usual, ofUnitId: e.target.value })}>
                  {singles.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
                </select>
              )}
            />
            <p className="field-label">Used for</p>
            <Segmented label="Used for" options={PURPOSES} value={usual.purpose} onChange={(purpose) => setUsual({ ...usual, purpose })} />
          </>
        )}
        <BusyButton busy={add.isPending} type="submit" className="rt-primary" notReady={name.trim() === '' || sizeProblem !== null}>{send}</BusyButton>
      </form>
      <Problem error={add.error} />
    </AddPanel>
  );
  const rowOf = (r: Named) => (
    <NamedRow
      key={r.id}
      row={r}
      noun={noun}
      detail={withPrefix && 'code_prefix' in r
        ? <p className="hint">Item codes start with <strong>{r.code_prefix}</strong>, next {r.next_code}.</p>
        : 'name' in r && isPackageRow(r) ? (
          <>
            <p className="hint">{holdsText(r as Unit)}</p>
            {(r as Unit).usual_pack && <p className="hint">{usedForText((r as Unit).usual_pack?.purpose)}</p>}
          </>
        ) : undefined}
      extra={withPrefix
        ? <Link className="btn-sm" to={`/staff/retail/catalogue/categories/${r.id ?? ''}` as never} aria-label={`Open ${r.name} and its items`}>Open</Link>
        : (
          <>
            <ScreenLink screen="products" to="/staff/retail/catalogue/products" label={`See the items of ${r.name}`}>Items</ScreenLink>
            {/* A dozen always holds 12 (ADR-110): there is nothing to change. */}
            {isPackageRow(r) && !(r as Unit).fixed_count && ('usual_pack' in r && r.usual_pack || mayHaveUsualPack(r as Unit, all as Unit[])) && (
              <button type="button" className="btn-sm" onClick={() => setPackFor(r.id ?? null)} aria-label={`What one ${r.name} usually holds`}>
                Usually holds
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
            ? `Deleted "${x.name}". Items that use it keep it; Show deleted brings it back.`
            : `"${x.name}" is back.`),
        })}
    />
  );
  return (
    <>
      {!sections && <div data-tour="named-add">{addPanel('', `Add a ${noun}`, `Name of the new ${noun}`, `Add ${noun}`)}</div>}
      <Problem error={failure && new Error(failure)} />
      {rows.isPending && <p className="loading">Loading</p>}
      <Problem error={rows.error} />
      {rows.data && rows.data.length === 0 && <p className="empty-state">No {plural} yet. Add the first one {sections ? 'below' : 'above'}.</p>}
      {deletedCount > 0 && (
        <label htmlFor={`show-deleted-${plural}`}>
          <input id={`show-deleted-${plural}`} type="checkbox" checked={showDeleted} onChange={(e) => setShowDeleted(e.target.checked)} /> Show deleted ({deletedCount})
        </label>
      )}
      {sections ? sections.map((s, i) => (
        <section key={s.key} className="stack" aria-labelledby={`${plural}-${s.key}`} data-testid={`${plural}-${s.key}`}>
          <div className="help-tip-heading">
            <h2 id={`${plural}-${s.key}`}>{s.title}</h2>
            <HelpTip tip={s.tip} />
          </div>
          <p className="hint">{s.hint}</p>
          <div data-tour={i === 0 ? 'named-add' : undefined}>{addPanel(s.key, s.add, `Name of the new ${s.key === 'packages' ? 'package' : 'single unit'}`, s.add)}</div>
          <ul style={{ listStyle: 'none', padding: 0 }} data-tour={i === 0 ? 'named-list' : undefined}>
            {shown.filter(s.holds).map(rowOf)}
          </ul>
        </section>
      )) : (
        <ul style={{ listStyle: 'none', padding: 0 }} data-tour="named-list">
          {shown.map(rowOf)}
        </ul>
      )}
      {all.filter((r) => r.id === packFor).map((r) => (
        <UsualPackSheet key={`${r.id}-${r.version}`} unit={r as Unit} units={all as Unit[]} onClose={() => setPackFor(null)} onSaved={done} />
      ))}
      {toast}
    </>
  );
}

/** Issue #709 (D92, ADR-110): the two parts of the Units page. */
export const UNIT_SECTIONS: Section[] = [
  { key: 'single', title: 'Single units', tip: 'singleUnit', hint: 'One piece, one metre, one litre.', add: 'Add a single unit', holds: (r) => !isPackageRow(r) },
  { key: 'packages', title: 'Packages', tip: 'package', hint: 'Things that hold single units.', add: 'Add a package', holds: isPackageRow, usual: true },
];

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
      <NamedList noun="unit" plural="units" sections={UNIT_SECTIONS} list={() => retail.listUnits()} create={(n, usual) => orExistingUnit(retail.createUnit(n, usual))} update={(id, v, b) => retail.updateUnit(id, v, b)} />
    </Gate>
  );
}

export const CategoriesRoute = createLazyRoute('/staff/retail/catalogue/categories')({ component: CategoriesPage });
export const UnitsRoute = createLazyRoute('/staff/retail/catalogue/units')({ component: UnitsPage });
