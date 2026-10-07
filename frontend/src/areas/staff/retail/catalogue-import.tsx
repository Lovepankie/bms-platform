import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type ImportResult } from '../../../api/retail';
import { BackToCatalogue } from './catalogue';
import { Gate, Note, Problem, Success, useProfitAccess, useToast } from './ui';

// Import items from a file (#146), for a new client, administrators only. Paste the rows or choose a
// CSV file, check it first (nothing is saved), read what would be added, skipped and wrong, then add
// the items. Adding again skips what is already there. A file with a wrong row adds nothing.

const WORDS: Record<string, { label: string; tone: string }> = {
  added: { label: 'Add', tone: 'success' },
  skipped: { label: 'Skip', tone: 'warning' },
  error: { label: 'Problem', tone: 'danger' },
};

export function ImportReport({ result }: { result: ImportResult }) {
  const rows = result.rows ?? [];
  const names = (list: string[] | undefined, noun: string) => (list && list.length > 0 ? <p>New {noun}: {list.join(', ')}.</p> : null);
  return (
    <section aria-label="Import report">
      <h2>{result.dry_run ? 'What would happen' : 'What was done'}</h2>
      <p>
        <strong>{result.added}</strong> {result.dry_run ? 'to add' : 'added'}, <strong>{result.skipped}</strong> skipped, <strong>{result.errors}</strong> with a problem, from {result.rows_read} rows.
      </p>
      {names(result.categories_created, result.dry_run ? 'categories that would be created' : 'categories created')}
      {names(result.units_created, result.dry_run ? 'units that would be created' : 'units created')}
      <div className="table-wrap" tabIndex={0}>
        <table>
          <thead>
            <tr><th className="num">Row</th><th>Item</th><th>What happens</th></tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const w = WORDS[r.outcome ?? ''] ?? { label: r.outcome ?? '', tone: 'info' };
              return (
                <tr key={r.line}>
                  <td className="num">{r.line}</td>
                  <td>{r.code}<br /><span className="hint">{r.description}</span></td>
                  <td><span className={`badge badge-${w.tone}`}>{w.label}</span><br /><span className="hint">{r.message}</span></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function ImportPage() {
  const queryClient = useQueryClient();
  const profit = useProfitAccess();
  const { show, toast } = useToast();
  const [text, setText] = useState('');
  const [checked, setChecked] = useState<{ text: string; result: ImportResult } | null>(null);
  const [done, setDone] = useState<ImportResult | null>(null);
  const check = useMutation({
    mutationFn: () => retail.importProducts(text, true),
    onMutate: () => setDone(null),
    onSuccess: (result) => setChecked({ text, result }),
  });
  const apply = useMutation({
    mutationFn: () => retail.importProducts(text, false),
    onSuccess: (result) => {
      setDone(result);
      setChecked(null);
      setText('');
      show(`Added ${result.added} items.`);
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  const current = checked && checked.text === text ? checked.result : null;
  const ready = current !== null && current.errors === 0 && (current.added ?? 0) > 0;
  const choose = (file: File | undefined) => {
    if (!file) return;
    void file.text().then((t) => { setText(t); setChecked(null); setDone(null); });
  };
  return (
    <Gate screen="importer" title="Import items">
      <BackToCatalogue />
      <Note>
        The first row names the columns: code, description, category, unit, sell price{profit ? ' and, if you wish, cost price' : ''}. Then one item on each row.
        {profit ? '' : ' Leave the cost price out.'} Check the file first: nothing is saved until you add the items.
      </Note>
      <label htmlFor="import-file">Choose a CSV file</label>
      <input id="import-file" type="file" accept=".csv,.txt,text/csv,text/plain" onChange={(e) => choose(e.target.files?.[0])} />
      <label htmlFor="import-text">Or paste the rows here</label>
      <textarea id="import-text" rows={8} value={text} onChange={(e) => { setText(e.target.value); setDone(null); }} spellCheck={false} />
      <Problem error={check.error} />
      <button type="button" className="rt-primary" disabled={text.trim() === '' || check.isPending} onClick={() => check.mutate()}>
        {check.isPending ? 'Checking' : 'Check the file'}
      </button>
      {current && <ImportReport result={current} />}
      {current && current.errors === 0 && (current.added ?? 0) === 0 && <p className="empty-state">There is nothing new to add.</p>}
      {current && (current.errors ?? 0) > 0 && <p role="alert" className="alert alert-danger">Fix the rows with a problem in your file and check it again. Nothing is added while a row has a problem.</p>}
      <Problem error={apply.error} />
      {ready && (
        <button type="button" className="rt-primary" disabled={apply.isPending} onClick={() => apply.mutate()}>
          {apply.isPending ? 'Adding' : `Add ${current.added} items`}
        </button>
      )}
      {done && <Success>Added {done.added} items and skipped {done.skipped} that were already there.</Success>}
      {done && <ImportReport result={done} />}
      {toast}
    </Gate>
  );
}

export const ImportRoute = createLazyRoute('/staff/retail/catalogue/import')({ component: ImportPage });
