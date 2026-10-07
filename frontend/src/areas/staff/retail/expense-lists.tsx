import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useId, useState } from 'react';
import { retail, type ExpenseCategory, type ExpenseItem } from '../../../api/retail';
import { useToast } from './toast';
import { Gate, Problem } from './ui';

// The expense lists (FR-RET-17): categories and the items in them, kept by whoever holds
// retail.expense.manage. Nothing is deleted: a category or item is switched off and stays on old records.
// Every change carries the version the list showed (If-Match); if someone else changed it first the server
// refuses in plain words and the list is read again.

function RenameField({ label, value, disabled, onSave }: { label: string; value: string; disabled: boolean; onSave: (name: string) => void }) {
  const id = useId();
  const [name, setName] = useState(value);
  const changed = name.trim() !== '' && name.trim() !== value;
  return (
    <div className="rt-row">
      <div>
        <label htmlFor={id}>{label}</label>
        <input id={id} value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
      </div>
      <button type="button" className="btn-sm" disabled={!changed || disabled} onClick={() => onSave(name.trim())}>Save name</button>
    </div>
  );
}

export function ItemRow({ category, item, busy, change }: { category: ExpenseCategory; item: ExpenseItem; busy: boolean; change: (run: () => Promise<unknown>) => void }) {
  const id = useId();
  const version = item.version ?? 0;
  const update = (body: { name?: string; active?: boolean; requires_explanation?: boolean }) => change(() => retail.updateExpenseItem(category.id ?? '', item.id ?? '', body, version));
  return (
    <li className="rt-card">
      <strong>
        {item.name}{' '}
        {item.requires_explanation && <span className="badge badge-info">Needs an explanation</span>}{' '}
        {item.active === false && <span className="badge badge-warning">Switched off</span>}
      </strong>
      <RenameField label={`Rename ${item.name}`} value={item.name ?? ''} disabled={busy} onSave={(name) => update({ name })} />
      <label htmlFor={id} style={{ fontWeight: 400 }}>
        <input id={id} type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={item.requires_explanation === true} disabled={busy}
          onChange={(e) => update({ requires_explanation: e.target.checked })} />
        Needs an explanation when used
      </label>
      <button type="button" className="btn-sm" disabled={busy} onClick={() => update({ active: item.active === false })}>
        {item.active === false ? `Switch ${item.name} on` : `Switch ${item.name} off`}
      </button>
    </li>
  );
}

function AddItem({ category, busy, change }: { category: ExpenseCategory; busy: boolean; change: (run: () => Promise<unknown>) => void }) {
  const nameId = useId();
  const flagId = useId();
  const [name, setName] = useState('');
  const [flag, setFlag] = useState(false);
  return (
    <form onSubmit={(e) => {
      e.preventDefault();
      if (name.trim() === '' || busy) return;
      change(() => retail.createExpenseItem(category.id ?? '', { name: name.trim(), requires_explanation: flag }));
      setName('');
      setFlag(false);
    }}>
      <label htmlFor={nameId}>New item in {category.name}</label>
      <input id={nameId} value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
      <label htmlFor={flagId} style={{ fontWeight: 400 }}>
        <input id={flagId} type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={flag} onChange={(e) => setFlag(e.target.checked)} />
        Needs an explanation when used
      </label>
      <button type="submit" className="btn-sm" disabled={name.trim() === '' || busy}>Add item</button>
    </form>
  );
}

export function CategoryCard({ category, busy, change }: { category: ExpenseCategory; busy: boolean; change: (run: () => Promise<unknown>) => void }) {
  const version = category.version ?? 0;
  const update = (body: { name?: string; active?: boolean }) => change(() => retail.updateExpenseCategory(category.id ?? '', body, version));
  return (
    <section className="rt-card" aria-label={category.name}>
      <strong>
        {category.name} {category.active === false && <span className="badge badge-warning">Switched off</span>}
      </strong>
      <RenameField label={`Rename ${category.name}`} value={category.name ?? ''} disabled={busy} onSave={(name) => update({ name })} />
      <button type="button" className="btn-sm" disabled={busy} onClick={() => update({ active: category.active === false })}>
        {category.active === false ? `Switch ${category.name} on` : `Switch ${category.name} off`}
      </button>
      {(category.items ?? []).length === 0 && <p className="hint">No items yet.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {(category.items ?? []).map((i) => <ItemRow key={i.id} category={category} item={i} busy={busy} change={change} />)}
      </ul>
      <AddItem category={category} busy={busy} change={change} />
    </section>
  );
}

export function ExpenseLists({ categories, busy, error, change }: { categories: ExpenseCategory[]; busy: boolean; error: unknown; change: (run: () => Promise<unknown>) => void }) {
  const nameId = useId();
  const [name, setName] = useState('');
  return (
    <>
      <Problem error={error} />
      <form onSubmit={(e) => {
        e.preventDefault();
        if (name.trim() === '' || busy) return;
        change(() => retail.createExpenseCategory({ name: name.trim() }));
        setName('');
      }}>
        <label htmlFor={nameId}>New category</label>
        <input id={nameId} value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
        <button type="submit" className="btn-sm" disabled={name.trim() === '' || busy}>Add category</button>
      </form>
      {categories.length === 0 && <p className="empty-state">No categories yet. Add the first one above.</p>}
      {categories.map((c) => <CategoryCard key={c.id} category={c} busy={busy} change={change} />)}
    </>
  );
}

function ExpenseListsScreen() {
  const queryClient = useQueryClient();
  const { show, toast } = useToast();
  const categories = useQuery({ queryKey: ['retail', 'expense-categories', false], queryFn: () => retail.listExpenseCategories() });
  const change = useMutation({
    mutationFn: (run: () => Promise<unknown>) => run(),
    onSuccess: () => show('Saved.'),
    // Success or a refusal (for example a stale version), the lists are read again so the next change uses what is current.
    onSettled: () => void queryClient.invalidateQueries({ queryKey: ['retail', 'expense-categories'] }),
  });
  return (
    <Gate screen="expenseSetup" title="Expense lists">
      <p className="hint">Categories and items are what the expense form offers. Switching one off keeps it on old records.</p>
      {categories.isError && <Problem error={categories.error} />}
      {categories.isPending && <p className="loading">Loading</p>}
      {categories.data && <ExpenseLists categories={categories.data} busy={change.isPending} error={change.error} change={(run) => change.mutate(run)} />}
      {toast}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/expense-lists')({ component: ExpenseListsScreen });
