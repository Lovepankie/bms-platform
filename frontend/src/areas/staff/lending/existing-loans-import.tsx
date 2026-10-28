import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { existingLoans, type ExistingLoanImportResult, type ExistingLoansApi } from '../../../api/existing-loans';
import { setupHelper, type SetupHelperApi } from '../../../api/setup-helper';
import { EmptyChoice, showsEmptyChoice } from '../../../app/ui/EmptyChoice';
import { Field } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { useStaff } from '../context';
import { NOT_UTF8, readCsvText } from '../retail/catalogue-import';
import { Note, Problem, Success, useSingleBranch } from '../retail/ui';
import { ListReader, LoanDraftList, useSetupHelper } from '../setup-helper/setup-helper';
import { fromLoanDraft, loansCsv, needsConfirming, type LoanEdit } from '../setup-helper/setup-helper-state';
import { IMPORT_COLUMNS, IMPORT_TEMPLATE, canBringIn } from './existing-loans-state';
import { NoAccess } from '../../../app/ui/EmptyState';

// Import existing loans from a CSV file (issue #466, FR-EXL-06, ADR-071), on the pattern of the item import (#146): download
// the template, choose or paste the file, check it (nothing is saved), read what would come in, what is skipped and what is
// wrong, then record the loans. Each recorded loan waits for a second person's approval. A file with a wrong row records
// nothing; recording the same file again skips the loans already in.
// The setup helper (issue #626, ADR-102), while the platform has it on for the business: the app reads a register (typed, a
// photo, or a file) into loans the owner changes or removes; they become this screen's file, with a member new to the app named
// by name and phone, who joins the branch in use. This check and Record decide, as for any file.

const WORDS: Record<string, { label: string; tone: string }> = {
  ready: { label: 'Ready', tone: 'success' },
  added: { label: 'Recorded', tone: 'success' },
  skipped: { label: 'Skip', tone: 'warning' },
  error: { label: 'Problem', tone: 'danger' },
};

export function LoanImportReport({ result }: { result: ExistingLoanImportResult }) {
  return (
    <section aria-label="Import report">
      <h2>{result.dry_run ? 'What would happen' : 'What was done'}</h2>
      <p>
        <strong>{result.added}</strong> {result.dry_run ? 'loans ready' : 'loans recorded'}, <strong>{result.skipped}</strong> skipped,{' '}
        <strong>{result.errors}</strong> rows with a problem, from {result.rows_read} rows ({result.loans} loans, {result.repayments} repayments).
      </p>
      {(result.members_added ?? 0) > 0 && (
        <p>{result.dry_run ? `${result.members_added} new members would come in with their loans.` : `${result.members_added} new members came in with their loans.`}</p>
      )}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {(result.rows ?? []).filter((r) => r.message).map((r) => {
          const w = WORDS[r.outcome ?? ''] ?? { label: r.outcome ?? '', tone: 'info' };
          return (
            <li key={r.line} className="rt-card">
              <p><strong>{r.ref || 'No ref'}</strong> <span className={`badge badge-${w.tone}`}>{w.label}</span></p>
              <p className="hint">Row {r.line}</p>
              {r.message && <p>{r.message}</p>}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/** The template as a link to save: the columns in order and fabricated example rows. */
export function TemplateLink() {
  const href = `data:text/csv;charset=utf-8,${encodeURIComponent(IMPORT_TEMPLATE)}`;
  return <a href={href} download="existing-loans-template.csv">Download the template</a>;
}

export function ExistingLoansImport({ api = existingLoans, helperApi = setupHelper }: { api?: ExistingLoansApi; helperApi?: SetupHelperApi }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const helper = useSetupHelper(helperApi);
  const { branchId } = useSingleBranch();
  const [text, setText] = useState('');
  const [draft, setDraft] = useState<LoanEdit[] | null>(null);
  const [productCode, setProductCode] = useState('');
  const products = useQuery({ queryKey: ['lending', 'loan-products', 'active'], queryFn: () => api.products(), enabled: draft !== null });
  const [checked, setChecked] = useState<{ text: string; result: ExistingLoanImportResult } | null>(null);
  const [done, setDone] = useState<ExistingLoanImportResult | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const check = useMutation({
    mutationFn: () => api.importCsv(text, true, branchId ?? undefined),
    onMutate: () => setDone(null),
    onSuccess: (result) => setChecked({ text, result }),
  });
  const commit = useMutation({
    mutationFn: () => api.importCsv(text, false, branchId ?? undefined),
    onSuccess: (result) => {
      setDone(result);
      setChecked(null);
      setText('');
      setDraft(null);
      void queryClient.invalidateQueries({ queryKey: ['lending', 'existing-loans'] });
    },
  });
  const current = checked && checked.text === text ? checked.result : null;
  const readyLoans = current?.added ?? 0;
  const editDraft = (loans: LoanEdit[] | null, code: string = productCode) => {
    setDraft(loans && loans.length > 0 ? loans : null);
    setText(loans && loans.length > 0 ? loansCsv(loans, code) : '');
    setChecked(null);
    setDone(null);
    setWarning(null);
  };
  const chooseProduct = (code: string) => {
    setProductCode(code);
    editDraft(draft, code);
  };
  const choose = (file: File | undefined) => {
    if (!file) return;
    void file.text().then((raw) => {
      const read = readCsvText(raw);
      setDraft(null);
      setText(read.text);
      setWarning(read.notUtf8 ? NOT_UTF8 : null);
      setChecked(null);
      setDone(null);
    });
  };
  if (!canBringIn(me)) {
    return (
      <main className="rt ln">
        <h1>Import existing loans</h1>
        <NoAccess />
      </main>
    );
  }
  return (
    <main className="rt ln">
      <h1>Import existing loans</h1>
      <p><Link to="/staff/lending/existing-loans">Back to existing loans</Link></p>
      <Note>
        The first row names the columns: {IMPORT_COLUMNS.join(', ')}. Each loan has one row of kind loan, and each repayment a row of
        kind repayment with the same ref (the loan's number in the register). Dates are year-month-day, amounts as written in the
        register. Check the file first: nothing is saved until you record the loans.
      </Note>
      <p data-tour="existing-import-template"><TemplateLink /></p>
      {helper.enabled && (
        <ListReader
          photos={helper.photos}
          tour="existing-import-helper"
          noun="loans"
          read={(given) => helperApi.draftLoans(given)}
          count={(d) => d.loans.length}
          drafted={(d) => { if (d.loans.length > 0) editDraft(fromLoanDraft(d.loans, d.currency_exponent)); }}
        />
      )}
      {draft && (
        <>
          {showsEmptyChoice(products) ? (
            <EmptyChoice id="exl-draft-product" label="Loan product" load={products} text="No loan products yet." ask="Ask the owner to add a loan product first." />
          ) : (
            <>
              <Field
                id="exl-draft-product"
                label="Loan product for these loans"
                required
                error={productCode === '' ? 'Choose the loan product.' : null}
                control={(props) => (
                  <select {...props} value={productCode} onChange={(e) => chooseProduct(e.target.value)}>
                    <option value="">Choose the product</option>
                    {(products.data ?? []).map((p) => <option key={p.id} value={p.code}>{p.code}: {p.name}</option>)}
                  </select>
                )}
              />
            </>
          )}
          {!branchId && draft.some((l) => !l.memberNo) && (
            <Hint>New members join the branch in use; choose one at the top.</Hint>
          )}
          <LoanDraftList loans={draft} onChange={(loans) => editDraft(loans)} />
        </>
      )}
      <label htmlFor="exl-file">Choose a CSV file</label>
      <input id="exl-file" type="file" accept=".csv,.txt,text/csv,text/plain" onChange={(e) => choose(e.target.files?.[0])} />
      <label htmlFor="exl-text">Or paste the rows here</label>
      <textarea id="exl-text" data-tour="existing-import-text" rows={8} value={text} spellCheck={false} onChange={(e) => { setText(e.target.value); setDraft(null); setDone(null); setWarning(null); }} />
      {warning && <p role="alert" className="alert alert-warning">{warning}</p>}
      <Problem error={check.error} />
      <BusyButton busy={check.isPending} type="button" className="rt-primary" data-tour="existing-import-check" notReady={text.trim() === '' || (draft !== null && (productCode === '' || needsConfirming(draft)))} onClick={() => check.mutate()}>
        {draft ? 'Check these loans' : 'Check the file'}
      </BusyButton>
      {current && <LoanImportReport result={current} />}
      {current && (current.errors ?? 0) > 0 && (
        <p role="alert" className="alert alert-danger">Fix the rows with a problem in your file and check it again. Nothing is recorded while a row has a problem.</p>
      )}
      {current && current.errors === 0 && readyLoans === 0 && <p className="empty-state">There is nothing new to record.</p>}
      <Problem error={commit.error} />
      {current && current.errors === 0 && readyLoans > 0 && (
        <BusyButton busy={commit.isPending} type="button" className="rt-primary" onClick={() => commit.mutate()}>
          {`Record ${readyLoans} loans and send them for approval`}
        </BusyButton>
      )}
      {done && (
        <Success>
          Recorded {done.added} loans{(done.members_added ?? 0) > 0 ? ` and added ${done.members_added} new members` : ''} and skipped {done.skipped} already in. Each loan waits for a second person's approval.
        </Success>
      )}
      {done && <LoanImportReport result={done} />}
    </main>
  );
}

export const Route = createLazyRoute('/staff/lending/existing-loans/import')({ component: () => <ExistingLoansImport /> });
