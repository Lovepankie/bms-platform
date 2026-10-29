import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, useSearch } from '@tanstack/react-router';
import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { retail, type ImportResult } from '../../../api/retail';
import { setupHelper, type SetupHelperApi } from '../../../api/setup-helper';
import { ItemDraftList, ListReader, useSetupHelper } from '../setup-helper/setup-helper';
import { fromItemDraft, itemsCsv, type ItemEdit } from '../setup-helper/setup-helper-state';
import { Gate, Note, Problem, useProfitAccess, useToast } from './ui';
import { ActionBar } from '../../../app/ui/ActionBar';
import { Hint } from '../../../app/ui/Hint';

// Import items from a file (#146), for a new client, administrators only. Paste the rows or choose a
// CSV file, check it first (nothing is saved), read what would be added, skipped and wrong, then add
// the items. Adding again skips what is already there. A file with a wrong row adds nothing.
// The setup helper (issue #626, ADR-102), while the platform has it on for the business: the app reads a typed list, a photo
// or a file into rows the owner changes or removes; the rows become this screen's file, so this check and Add decide.

const WORDS: Record<string, { label: string; tone: string }> = {
  added: { label: 'Add', tone: 'success' },
  skipped: { label: 'Skip', tone: 'warning' },
  error: { label: 'Problem', tone: 'danger' },
};

export const NOT_UTF8 = 'This file does not look like UTF-8 (save the CSV as UTF-8).';

/**
 * What a chosen file's decoded text becomes: a leading byte order mark is dropped, CRLF and lone CR
 * line ends become LF, and a replacement character (U+FFFD, what a decoder writes for bytes that are
 * not UTF-8) is reported so the person can save the file as UTF-8 before the check.
 */
export function readCsvText(raw: string): { text: string; notUtf8: boolean } {
  const text = raw.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n');
  return { text, notUtf8: text.includes('\uFFFD') };
}

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
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {rows.map((r) => {
          const w = WORDS[r.outcome ?? ''] ?? { label: r.outcome ?? '', tone: 'info' };
          return (
            <li key={r.line} className="rt-card">
              <p><strong>{r.code || 'Code from its category'}</strong> <span className={`badge badge-${w.tone}`}>{w.label}</span></p>
              <p className="hint">Row {r.line}{r.description ? `, ${r.description}` : ''}</p>
              <p>{r.message}</p>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/** `shop`: the kind of shop chosen in Set up your shop, when the owner came from there; it helps the app match names. */
export function ImportPage({ helperApi = setupHelper, shop }: { helperApi?: SetupHelperApi; shop?: string } = {}) {
  const queryClient = useQueryClient();
  const profit = useProfitAccess();
  const helper = useSetupHelper(helperApi);
  const [draft, setDraft] = useState<ItemEdit[] | null>(null);
  const { show, toast } = useToast();
  const [text, setText] = useState('');
  // Owner decision D45 (issue #562): Let the app make the codes, unticked by default; a check is only good for the tick it ran with.
  const [makeCodes, setMakeCodes] = useState(false);
  const [checked, setChecked] = useState<{ text: string; makeCodes: boolean; result: ImportResult } | null>(null);
  const [report, setReport] = useState<ImportResult | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  // toast-exempt: a check of the file that saves nothing; the report below says what it found
  const check = useMutation({
    mutationFn: (codes: boolean) => retail.importProducts(text, true, codes),
    onMutate: () => setReport(null),
    onSuccess: (result, codes) => setChecked({ text, makeCodes: codes, result }),
  });
  const apply = useMutation({
    mutationFn: () => retail.importProducts(text, false, makeCodes),
    onSuccess: (result) => {
      setReport(result);
      setChecked(null);
      setText('');
      setDraft(null);
      show(`Added ${result.added ?? 0} items, skipped ${result.skipped ?? 0}`, 'success');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  const current = checked && checked.text === text && checked.makeCodes === makeCodes ? checked.result : null;
  const ready = current !== null && current.errors === 0 && (current.added ?? 0) > 0;
  // A file already checked is checked again with the new choice, so the report always matches the tick.
  const tick = (codes: boolean) => {
    setMakeCodes(codes);
    if (checked && checked.text === text) check.mutate(codes);
  };
  const editDraft = (rows: ItemEdit[] | null) => {
    setDraft(rows && rows.length > 0 ? rows : null);
    setText(rows && rows.length > 0 ? itemsCsv(rows, profit) : '');
    setChecked(null);
    setReport(null);
    setWarning(null);
  };
  const choose = (file: File | undefined) => {
    if (!file) return;
    void file.text().then((raw) => {
      const read = readCsvText(raw);
      setDraft(null);
      setText(read.text);
      setWarning(read.notUtf8 ? NOT_UTF8 : null);
      setChecked(null);
      setReport(null);
    });
  };
  return (
    <Gate screen="importer" title="Import items">
      <div data-tour="import-note">
        <Note>
          The first row names the columns: code, description, category, unit{profit ? ', buying price (if you wish)' : ''}, selling price. Then one item on each row. A code in the file is kept, unless you tick Let the app make the codes below. Leave a code empty, or leave out the code column, and the app gives the item the next code of its category, such as BLB-0001.
          {profit ? '' : ' Leave the buying price out.'} Check the file first: nothing is saved until you add the items.
        </Note>
      </div>
      {helper.enabled && (
        <ListReader
          photos={helper.photos}
          tour="import-helper"
          noun="items"
          read={(given) => helperApi.draftItems(given, shop)}
          count={(d) => d.rows.length}
          drafted={(d) => { if (d.rows.length > 0) editDraft(fromItemDraft(d.rows, d.currency_exponent)); }}
        />
      )}
      {draft && <ItemDraftList rows={draft} withCost={profit} onChange={editDraft} />}
      <label htmlFor="import-file">Choose a CSV file</label>
      <input id="import-file" type="file" data-tour="import-file" accept=".csv,.txt,text/csv,text/plain" onChange={(e) => choose(e.target.files?.[0])} />
      <label htmlFor="import-text">Or paste the rows here</label>
      <textarea id="import-text" data-tour="import-text" rows={8} value={text} onChange={(e) => { setText(e.target.value); setDraft(null); setReport(null); setWarning(null); }} spellCheck={false} />
      <label htmlFor="import-make-codes">
        <input id="import-make-codes" type="checkbox" data-tour="import-make-codes" checked={makeCodes} onChange={(e) => tick(e.target.checked)} /> Let the app make the codes
      </label>
      <Hint full>Tick this to give every item the next code of its category and leave out the codes in your file. Without the tick, a code in your file is kept.</Hint>
      {warning && <p role="alert" className="alert alert-warning">{warning}</p>}
      <Problem error={check.error} />
      <ActionBar>
        <BusyButton busy={check.isPending} type="button" className="rt-primary" data-tour="import-check" notReady={text.trim() === ''} onClick={() => check.mutate(makeCodes)}>
          {draft ? 'Check these items' : 'Check the file'}
        </BusyButton>
      </ActionBar>
      {current && <ImportReport result={current} />}
      {current && current.errors === 0 && (current.added ?? 0) === 0 && <p className="empty-state">There is nothing new to add.</p>}
      {current && (current.errors ?? 0) > 0 && <p role="alert" className="alert alert-danger">Fix the rows with a problem in your file and check it again. Nothing is added while a row has a problem.</p>}
      <Problem error={apply.error} />
      {ready && (
        <BusyButton busy={apply.isPending} type="button" className="rt-primary" onClick={() => apply.mutate()}>
          {`Add ${current.added} items`}
        </BusyButton>
      )}
      {report && <ImportReport result={report} />}
      {toast}
    </Gate>
  );
}

function ImportScreen() {
  const { shop } = useSearch({ strict: false }) as { shop?: string };
  return <ImportPage shop={typeof shop === 'string' ? shop : undefined} />;
}

export const ImportRoute = createLazyRoute('/staff/retail/catalogue/import')({ component: ImportScreen });
