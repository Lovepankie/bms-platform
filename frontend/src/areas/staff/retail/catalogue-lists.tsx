import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type Category, type Unit } from '../../../api/retail';
import { AddPanel, BackToCatalogue } from './catalogue';
import { Gate, Problem, Success, useToast } from './ui';

// Categories and Units (#146): the same screen twice. A row can be renamed or switched off, never
// deleted: items and their history keep pointing at it. A switched-off row stays on the items that
// use it and cannot be chosen for a new item or a change of item.

type Named = Category | Unit;

export function usedText(count: number | undefined, noun: string): string {
  const n = count ?? 0;
  return n === 0 ? `No items use this ${noun} yet.` : `Used by ${n} ${n === 1 ? 'item' : 'items'}.`;
}

export function NamedRow({ row, noun, busy, onRename, onToggle }: {
  row: Named;
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
      <p className="hint">
        {usedText(row.product_count, noun)}
        {off ? ' New items cannot choose it.' : ''}
      </p>
      {editing ? (
        <div className="stack">
          <label htmlFor={`rename-${row.id}`}>New name</label>
          <input id={`rename-${row.id}`} value={name} maxLength={noun === 'unit' ? 30 : 100} onChange={(e) => setName(e.target.value)} />
          <p className="cluster">
            <button type="button" disabled={busy || name.trim() === '' || name.trim() === row.name} onClick={() => { onRename(name.trim()); setEditing(false); }}>
              Save name
            </button>
            <button type="button" className="btn-ghost" onClick={() => { setName(row.name ?? ''); setEditing(false); }}>Cancel</button>
          </p>
        </div>
      ) : (
        <p className="cluster">
          <button type="button" className="btn-sm" onClick={() => setEditing(true)} aria-label={`Rename ${row.name}`}>Rename</button>
          <button type="button" className="btn-sm" disabled={busy} onClick={onToggle} aria-label={`${off ? 'Switch on' : 'Switch off'} ${row.name}`}>
            {off ? 'Switch on' : 'Switch off'}
          </button>
        </p>
      )}
    </li>
  );
}

function NamedList({ noun, plural, list, create, update }: {
  noun: string;
  plural: string;
  list: () => Promise<Named[]>;
  create: (name: string) => Promise<Named>;
  update: (id: string, version: number, body: { name?: string; active?: boolean }) => Promise<Named>;
}) {
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const [message, setMessage] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [adding, setAdding] = useState(false);
  const rows = useQuery({ queryKey: ['retail', 'catalogue', plural], queryFn: list });
  const done = (text: string) => {
    setMessage(text);
    show(text);
    void queryClient.invalidateQueries({ queryKey: ['retail'] });
  };
  const add = useMutation({
    mutationFn: () => create(name.trim()),
    onMutate: () => setMessage(null),
    onSuccess: (row) => { setName(''); setAdding(false); done(`Added the ${noun} "${row.name}".`); },
  });
  const change = useMutation({
    mutationFn: (v: { id: string; version: number; body: { name?: string; active?: boolean }; text: (row: Named) => string }) => update(v.id, v.version, v.body).then((row) => ({ row, text: v.text })),
    onMutate: () => setMessage(null),
    onSuccess: ({ row, text }) => done(text(row)),
  });
  return (
    <>
      <BackToCatalogue />
      <AddPanel label={`Add a ${noun}`} open={adding} onOpen={() => setAdding(true)} onClose={() => { setAdding(false); setName(''); }}>
        <form onSubmit={(e) => { e.preventDefault(); if (name.trim()) add.mutate(); }}>
          <label htmlFor={`new-${plural}`}>Name of the new {noun}</label>
          <input id={`new-${plural}`} value={name} maxLength={noun === 'unit' ? 30 : 100} onChange={(e) => setName(e.target.value)} autoComplete="off" />
          <button type="submit" className="rt-primary" disabled={add.isPending || name.trim() === ''}>{add.isPending ? 'Adding' : `Add ${noun}`}</button>
        </form>
        <Problem error={add.error} />
      </AddPanel>
      {message && <Success>{message}</Success>}
      <Problem error={change.error} />
      {rows.isPending && <p className="loading">Loading</p>}
      <Problem error={rows.error} />
      {rows.data && rows.data.length === 0 && <p className="empty-state">No {plural} yet. Add the first one above.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {(rows.data ?? []).map((r) => (
          <NamedRow
            key={r.id}
            row={r}
            noun={noun}
            busy={change.isPending}
            onRename={(n) => change.mutate({ id: r.id ?? '', version: r.version ?? 1, body: { name: n }, text: (x) => `Renamed to "${x.name}".` })}
            onToggle={() =>
              change.mutate({
                id: r.id ?? '',
                version: r.version ?? 1,
                body: { active: r.active === false },
                text: (x) => (x.active === false
                  ? `"${x.name}" is switched off. Items that use it keep it; new items cannot choose it.`
                  : `"${x.name}" is switched on again.`),
              })}
          />
        ))}
      </ul>
      {toast}
    </>
  );
}

function CategoriesPage() {
  return (
    <Gate screen="categories" title="Categories">
      <NamedList noun="category" plural="categories" list={() => retail.listCategories()} create={(n) => retail.createCategory(n)} update={(id, v, b) => retail.updateCategory(id, v, b)} />
    </Gate>
  );
}

function UnitsPage() {
  return (
    <Gate screen="units" title="Units">
      <NamedList noun="unit" plural="units" list={() => retail.listUnits()} create={(n) => retail.createUnit(n)} update={(id, v, b) => retail.updateUnit(id, v, b)} />
    </Gate>
  );
}

export const CategoriesRoute = createLazyRoute('/staff/retail/catalogue/categories')({ component: CategoriesPage });
export const UnitsRoute = createLazyRoute('/staff/retail/catalogue/units')({ component: UnitsPage });
